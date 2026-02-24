package com.viitor.pipeline

import android.content.Context
import android.util.Log
import com.viitor.audio.domain.AudioChunk
import com.viitor.stt.domain.ProvisionalTranscript
import com.viitor.stt.domain.BatchTranscript
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.time.ExperimentalTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.viitor.stt.util.SubtitleForensicAgent

/**
 * ★ SubtitlePipelineEngine
 *
 * 엔터프라이즈급 완전 분리형 파이프라인 엔진.
 * 모든 상태 전환을 명확한 State Machine으로 관리하여
 * Race Condition, Deadlock, Double-Lock을 원천 봉쇄합니다.
 *
 * 아키텍처:
 *   AudioCapture → RingBuffer → STT Worker → Translation Worker → UI Dispatcher
 *
 * 핵심 설계 원칙:
 *   1. STT Mutex는 오직 STT Worker 내부에서만 사용 (Double-Lock 불가능)
 *   2. UI 업데이트는 반드시 mainDispatcher를 통해 직렬 처리
 *   3. 오디오 캡처 세션과 워크플로우 스코프를 완전 분리
 *   4. 1.5초 누적 윈도우로 Whisper 최소 컨텍스트 보장
 */
class SubtitlePipelineEngine(
    private val context: Context,
    private val onShowUI: suspend (text: String, translated: String?) -> Unit,
    private val onFinalUI: suspend (text: String, translated: String?) -> Unit,
    private val onStatus: suspend (message: String) -> Unit,
    private val onError: (error: String) -> Unit
) {
    companion object {
        private const val TAG = "PipelineEngine"
        
        // ─── 파이프라인 파라미터 ──────────────────────────────
        private const val WHISPER_MIN_ACCUMULATE_MS = 2500L  // ⚡ [Reliability+] 1.5초 -> 2.5초로 늘려 문맥 이해도 95% 달성 목표
        private const val WHISPER_MAX_ACCUMULATE_MS = 6000L  // 최대 누적 6초
        private const val SLIDING_OVERLAP_MS        = 800L   // ⚡ [Balance] 1.5초 -> 0.8초 오버랩
        private const val CHANNEL_CAPACITY          = 1024   // 오디오 채널 용량 
        private const val FORCED_FINALIZE_LEN       = 35     // ⚡ [Quality+] 20 -> 35글자로 상향하여 문장 전체 맥락 확보 후 번역
        private const val TRANSLATION_TIMEOUT_MS    = 1500L  // 번역 타임아웃
        private const val UI_HEARTBEAT_INTERVAL_MS  = 3000L  // UI 하트비트 주기
        private const val AGC_TARGET_AMPLITUDE      = 22000f // ⚡ [Gain Balance] 28000 -> 22000. Prevent signal clipping.
        private const val AGC_MAX_GAIN              = 12.0f  // ⚡ [Gain Balance] 15.0 -> 12.0.
        private const val NOISE_FLOOR_ALPHA         = 0.98f  // 
    }

    // ─── 상태 머신 ─────────────────────────────────────────────
    enum class State { IDLE, LOADING, RUNNING, PAUSED, ERROR }
    @Volatile var state: State = State.IDLE
        private set

    // ─── 내부 상태 ─────────────────────────────────────────────
    private var sourceLang: String = "ja"
    private var targetLang: String = "ko"

    fun setLanguages(source: String, target: String) {
        this.sourceLang = source
        this.targetLang = target
        Log.d(TAG, "Pipeline language set: $source -> $target")
    }

    private var engineScope: CoroutineScope? = null
    private val audioChannel = Channel<AudioChunk>(CHANNEL_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val translationChannel = Channel<Pair<String, String>>(10)
    private val flushChannel = Channel<Unit>(Channel.CONFLATED) // ⚡ [Adhesive Flush] 침묵 즉시 처리를 위한 신호 채널
    
    // AGC 상태 (청크 간 연속성 유지)
    private var agcNoiseFloor = 0.0f
    private var agcGain = 1.0f
    private val agcLock = Any()
    
    // 파이프라인 통계 (자가 검증용)
    private val audioChunksReceived = AtomicLong(0)
    private val sttCallCount = AtomicLong(0)
    private val translationCallCount = AtomicLong(0)
    private val uiUpdateCount = AtomicLong(0)
    val lastAudioTimestamp = AtomicLong(System.currentTimeMillis())
    
    private val isCapturing = AtomicBoolean(false)
    private var contextBuffer = ""
    private var latestProvisional = ""
    
    // 번역 캐시 (LRU 간이 구현)
    private val translationCache = LinkedHashMap<String, String>(64, 0.75f, true)
    private val translationCacheMutex = Mutex()

    // ─── 엔터프라이즈 DB 컴포넌트 ──────────────────────────────
    private val glossaryManager = DatabaseGlossaryManager(context)
    private val historyManager = SubtitleHistoryManager(context)
    
    // ─── STT 인터페이스 ────────────────────────────────────────
    var sttProvider: SttProvider? = null
    var translateProvider: TranslateProvider? = null
    
    interface SttProvider {
        suspend fun process(audioData: ByteArray, prompt: String, language: String): SttResult
        fun reset()
        fun isReady(): Boolean
    }
    
    interface TranslateProvider {
        suspend fun translate(text: String, from: String, to: String): String?
    }
    
    data class SttResult(
        val text: String,
        val isFinal: Boolean = false,
        val confidence: Float = 0.5f
    )

    // ─────────────────────────────────────────────────────────────────────────
    // 파이프라인 시작
    // ─────────────────────────────────────────────────────────────────────────

    fun start() {
        if (state == State.RUNNING) {
            Log.w(TAG, "Pipeline already running, ignoring start()")
            return
        }
        
        state = State.LOADING
        Log.d(TAG, "✅ Pipeline starting (state=$state)")

        engineScope?.cancel()
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        isCapturing.set(true)
        contextBuffer = ""
        latestProvisional = ""
        
        // STT Worker 가동
        engineScope!!.launch { runSttWorker() }
        // Translation Worker 가동
        engineScope!!.launch { runTranslationWorker() }
        // Heartbeat Watchdog 가동
        engineScope!!.launch { runHeartbeat() }
        
        state = State.RUNNING
        com.viitor.stt.util.SubtitleForensicAgent.currentState = "RUNNING"
        Log.d(TAG, "✅ Pipeline started (state=$state)")
    }

    fun stop() {
        Log.d(TAG, "Pipeline stopping (state=$state)")
        state = State.IDLE
        com.viitor.stt.util.SubtitleForensicAgent.currentState = "IDLE"
        isCapturing.set(false)
        engineScope?.cancel()
        engineScope = null
        audioChannel.tryReceive() // drain
        translationChannel.tryReceive()
        logStats()
    }

    fun pause() {
        if (state == State.RUNNING) {
            state = State.PAUSED
            isCapturing.set(false)
            Log.d(TAG, "Pipeline paused")
        }
    }

    fun resume() {
        if (state == State.PAUSED) {
            state = State.RUNNING
            isCapturing.set(true)
            Log.d(TAG, "Pipeline resumed")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 오디오 청크 입력 (캡처 스레드에서 호출)
    // ─────────────────────────────────────────────────────────────────────────

    fun pushAudio(chunk: AudioChunk) {
        if (!isCapturing.get() || state != State.RUNNING) return
        
        audioChunksReceived.incrementAndGet()
        lastAudioTimestamp.set(System.currentTimeMillis())
        
        // ─── 엔터프라이즈급 소프트웨어 AGC ────────────────────────────
        val processedChunk = applyEnterpriseAGC(chunk)
        
        // ⚡ [Forensic Ingress] 엔진 입구에서 카운트를 올려 캡처 서비스와의 연결성 확인
        val sampleCount = processedChunk.data.size / 2
        var sumSq = 0.0
        for (i in 0 until sampleCount) {
            val s = ((processedChunk.data[i*2+1].toInt() shl 8) or (processedChunk.data[i*2].toInt() and 0xFF)).toShort().toFloat()
            sumSq += s.toDouble() * s
        }
        val rms = kotlin.math.sqrt(sumSq / sampleCount).toFloat()
        com.viitor.stt.util.SubtitleForensicAgent.recordAudioDrive(rms)
        
        Log.i(TAG, "[FLOW-CHECK] 1. Audio Ingress -> Engine (RMS=${"%.2f".format(rms)})")
        
        val result = audioChannel.trySend(processedChunk)
        if (result.isFailure) {
            Log.w(TAG, "Audio channel full (capacity=$CHANNEL_CAPACITY), dropping oldest chunk")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 엔터프라이즈 소프트웨어 AGC (Automatic Gain Control)
    //
    // 브라우저 캡처 오디오의 핵심 문제:
    //   - 물리 마이크는 OS가 하드웨어 AGC를 제공하지만
    //   - MediaProjection 오디오는 OS AGC 바이패스됨
    //     → 아주 작은 소리를 Whisper가 인식 못함
    //
    // 해결 전략:
    //   1. 지수 이동평균으로 노이즈 플로어를 추적 (느리게)
    //   2. RMS 기반으로 현재 신호 레벨을 측정
    //   3. 목표 진폭에 도달하도록 게인을 점진적으로 조절
    //   4. 게인 최대값 AGC_MAX_GAIN으로 하드 클리핑 방지
    // ─────────────────────────────────────────────────────────────────────────
    private fun applyEnterpriseAGC(chunk: AudioChunk): AudioChunk {
        val data = chunk.data
        val sampleCount = data.size / 2
        if (sampleCount == 0) return chunk
        
        // 1. RMS 측정
        var sumSq = 0.0
        for (i in 0 until sampleCount) {
            val s = ((data[i*2+1].toInt() shl 8) or (data[i*2].toInt() and 0xFF)).toShort().toFloat()
            sumSq += s.toDouble() * s
        }
        val rms = Math.sqrt(sumSq / sampleCount).toFloat()
        
        synchronized(agcLock) {
            // 2. 노이즈 플로어 추적 (느린 적응 = 목소리 변화 추적 안 함)
            agcNoiseFloor = if (agcNoiseFloor == 0f) rms else agcNoiseFloor * NOISE_FLOOR_ALPHA + rms * (1f - NOISE_FLOOR_ALPHA)
            
            // 3. 신호-노이즈비 추정
            val snr = if (agcNoiseFloor > 1f) rms / agcNoiseFloor else 1f
            
            // 4. 게인 계산 (SNR이 높을수록 (소리 있음) 증폭 적게, SNR이 낮으면 증폭 많이)
            val targetGain = if (rms > 100f) {
                Math.min(AGC_MAX_GAIN, AGC_TARGET_AMPLITUDE / (rms + 1f))
            } else {
                AGC_MAX_GAIN // 소리가 거의 없으면 최대 게인
            }
            
            // 5. 게인 스무딩 (급격한 변화 방지)
            agcGain = agcGain * 0.7f + targetGain * 0.3f
        }
        
        // 6. 게인 적용
        val currentGain = agcGain
        if (Math.abs(currentGain - 1.0f) < 0.05f) return chunk // 게인이 거의 1이면 패스
        
        val out = ByteArray(data.size)
        for (i in 0 until sampleCount) {
            val s = ((data[i*2+1].toInt() shl 8) or (data[i*2].toInt() and 0xFF)).toShort().toFloat()
            val boosted = (s * currentGain).coerceIn(-32767f, 32767f).toInt().toShort()
            out[i*2] = (boosted.toInt() and 0xFF).toByte()
            out[i*2+1] = ((boosted.toInt() shr 8) and 0xFF).toByte()
        }
        
        return chunk.copy(data = out)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STT Worker
    //
    // 슬라이딩 윈도우 알고리즘:
    //   1. WHISPER_MIN_ACCUMULATE_MS (1.5초) 이상 오디오 누적 후 처리
    //   2. Whisper 추론 완료 후 SLIDING_OVERLAP_MS (0.75초) 오버랩 유지
    //   3. 이전 컨텍스트를 프롬프트로 주입 (문맥 연속성)
    //   4. 텍스트가 나오면 번역 채널로 전달
    // ─────────────────────────────────────────────────────────────────────────
    @OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
    private suspend fun runSttWorker() = coroutineScope {
        val accumulator = mutableListOf<AudioChunk>()
        var accumulatedMs = 0L
        val accLock = Any()
        var lastPrompt = ""
        
        Log.d(TAG, "🔊 STT Worker started")
        
        try {
            while (isActive && (state == State.RUNNING || state == State.LOADING)) {
                val currentProvider = sttProvider
                val isReady = currentProvider?.isReady() == true
                com.viitor.stt.util.SubtitleForensicAgent.engineReady = isReady

                // 엔진이 준비되지 않았으면 채널에서 뽑지 않고 대기 (데이터 유실 방지)
                if (!isReady) {
                    delay(200)
                    continue
                }

                // ⚡ [Adhesive Logic] 100ms 대기하며 오디오 데이터 수신 또는 플러시 신호 체크
                val chunk = withTimeoutOrNull(100L) { audioChannel.receive() }
                val flushSignal = flushChannel.tryReceive().getOrNull() != null
                
                if (chunk != null) {
                    synchronized(accLock) {
                        accumulator.add(chunk)
                        accumulatedMs += chunk.durationMs
                        
                        // ⚡ [FLOW-CHECK] Step 2. Worker Recv (Total Acc: $accumulatedMs ms)
                         Log.i(TAG, "[FLOW-CHECK] 2. STT Worker Recv (Acc=${accumulatedMs}ms, Chunks=${accumulator.size})")
                    }
                }
                
                // Whisper 최소 컨텍스트 미달 && 강제 플러시 아님 → 대기
                if (accumulatedMs < WHISPER_MIN_ACCUMULATE_MS && !flushSignal) continue
            
                if (accumulatedMs == 0L) continue
                
                // OOM 방지: 최대 누적 초과 시 오래된 부분 잘라냄
                synchronized(accLock) {
                    if (accumulatedMs > WHISPER_MAX_ACCUMULATE_MS) {
                        var trimMs = 0L
                        val trimmed = mutableListOf<AudioChunk>()
                        for (c in accumulator.reversed()) {
                            if (trimMs < WHISPER_MAX_ACCUMULATE_MS) {
                                trimmed.add(0, c)
                                trimMs += c.durationMs
                            }
                        }
                        accumulator.clear()
                        accumulator.addAll(trimmed)
                        accumulatedMs = trimMs
                    }
                }
                
                // STT 처리
                val snapshot: List<AudioChunk>
                val snapMs: Long
                synchronized(accLock) {
                    snapshot = accumulator.toList()
                    snapMs = accumulatedMs
                }
                
                val providerForInfer = sttProvider
                if (providerForInfer == null || !providerForInfer.isReady()) continue
                
                try {
                    sttCallCount.incrementAndGet()
                    val merged = mergeAudioChunks(snapshot, snapMs)
                    
                    // ⚡ [Safety Switch] STT 처리에 강제 타임아웃(15초)을 두어 엔진 프리징 시 워커 전체가 멈추는 것 방지
                    val result = withTimeoutOrNull(15000L) {
                        val resultRaw = providerForInfer.process(merged.data, lastPrompt, sourceLang)
                        glossaryManager.applyGlossary(resultRaw.text).let { 
                            resultRaw.copy(text = it)
                        }
                    } ?: run {
                        com.viitor.stt.util.SubtitleForensicAgent.recordError("STT Timeout")
                        SubtitlePipelineEngine.SttResult(text = "")
                    }
                    
                    // ⚡ [Busy-Loop Fix] STT 결과와 무관하게 윈도우를 슬라이딩하여 무한 재처리 방지
                    synchronized(accLock) {
                        var keepMs = 0L
                        val toKeep = mutableListOf<AudioChunk>()
                        for (c in accumulator.reversed()) {
                            if (keepMs < SLIDING_OVERLAP_MS) {
                                toKeep.add(0, c)
                                keepMs += c.durationMs
                            } else break
                        }
                        accumulator.clear()
                        accumulator.addAll(toKeep)
                        accumulatedMs = keepMs
                    }

                    if (result.text.isNotBlank()) {
                        Log.d("2StepCheck", "Step 1 (Data): STT→ '${result.text.take(40)}'")
                        latestProvisional = result.text
                        
                        // UI 즉시 반영 (번역 기다리지 않음)
                        val displayText = if (contextBuffer.isNotBlank()) "$contextBuffer ${result.text}" else result.text
                        Log.i(TAG, "[FLOW-CHECK] 4. STT Result -> onShowUI (len=${displayText.length})")
                        withContext(Dispatchers.Main) {
                            onShowUI(displayText, null)
                            uiUpdateCount.incrementAndGet()
                        }
                        
                        // 충분히 길면 확정 처리
                        if (result.text.length >= FORCED_FINALIZE_LEN || result.isFinal) {
                            translationChannel.trySend(Pair(displayText, lastPrompt))
                            lastPrompt = displayText.takeLast(50)
                            contextBuffer = ""
                            latestProvisional = ""
                            sttProvider?.reset()
                        } else {
                            // 번역 채널에 중간 텍스트 전달 (실시간 번역)
                            translationChannel.trySend(Pair(displayText, lastPrompt))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "STT inner processing error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            com.viitor.stt.util.SubtitleForensicAgent.recordError("STT_WORKER_DIE: ${e.message}")
            Log.e(TAG, "STT Worker unexpected crash", e)
        }
        Log.d(TAG, "🔇 STT Worker stopped")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Translation Worker
    //
    // CONFLATED 채널 사용: 번역 중에 새 텍스트 도착하면 기존 취소, 최신만 처리
    // L2 캐시: 동일 텍스트는 즉시 반환 (네트워크 0ms)
    // 타임아웃: TRANSLATION_TIMEOUT_MS 초과 시 원문만 표시
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun runTranslationWorker() = coroutineScope {
        Log.d(TAG, "🌐 Translation Worker started")
        
        for ((originalText, _) in translationChannel) {
            if (!isActive || state != State.RUNNING) break
            
            try {
                translationCallCount.incrementAndGet()
                
                // L2 캐시 조회
                val cached = translationCacheMutex.withLock { translationCache[originalText] }
                if (cached != null) {
                    Log.v(TAG, "Translation cache hit: '${originalText.take(20)}'")
                    withContext(Dispatchers.Main) {
                        onFinalUI(originalText, cached)
                        uiUpdateCount.incrementAndGet()
                    }
                    continue
                }
                
                // 번역 실행 (타임아웃 포함)
                val translated = withTimeoutOrNull(TRANSLATION_TIMEOUT_MS) {
                    translateProvider?.translate(originalText, sourceLang, targetLang)
                }
                
                val result = translated ?: ""
                
                if (result.isNotBlank()) {
                    // L2 캐시 저장
                    translationCacheMutex.withLock {
                        if (translationCache.size > 100) {
                            translationCache.entries.iterator().let { it.next(); it.remove() }
                        }
                        translationCache[originalText] = result
                    }
                    Log.d("2StepCheck", "Step 2 (UI): Rendering '${originalText.take(20)}' → '$result'")
                    withContext(Dispatchers.Main) {
                        onFinalUI(originalText, result)
                        uiUpdateCount.incrementAndGet()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        onFinalUI(originalText, null)
                    }
                }
                
                // ★ [엔터프라이즈 기록] DB에 이력 저장
                historyManager.save(originalText, result.ifBlank { null }, "WHISPER_NLLB", 1.0f)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Translation error: ${e.message}")
                withContext(Dispatchers.Main) { onFinalUI(originalText, null) }
            }
        }
        Log.d(TAG, "🌐 Translation Worker stopped")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Heartbeat Watchdog
    //
    // UI가 사라진 것처럼 보이는 증상을 방지하기 위해
    // 정기적으로 상태 메시지를 전달하고 메모리 상태를 점검합니다.
    // ─────────────────────────────────────────────────────────────────────────
    private suspend fun runHeartbeat() = coroutineScope {
        Log.d(TAG, "💓 Heartbeat started")
        var tickCount = 0
        
        while (isActive && (state == State.RUNNING || state == State.PAUSED)) {
            delay(UI_HEARTBEAT_INTERVAL_MS)
            tickCount++
            
            val now = System.currentTimeMillis()
            val sinceLastAudio = now - lastAudioTimestamp.get()
            
            // ⚡ [Forensic Feedback] 유저가 실시간으로 엔진 상태를 볼 수 있도록 summary 노출
            val forensicSummary = SubtitleForensicAgent.getSummary()
            
            if (sinceLastAudio > 12_000L && latestProvisional.isBlank()) {
                withContext(Dispatchers.Main) {
                    onStatus("🎙️ 소리 대기 중 ($forensicSummary)")
                }
            } else if (tickCount % 10 == 0) {
                // 10초마다 한번씩 조용히 상태 업데이트 (살아있음을 알림)
                withContext(Dispatchers.Main) {
                    onStatus("💓 $forensicSummary")
                }
            }
            
            // 메모리 압박 대응
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
            if (usedMb > 180) {
                Log.w(TAG, "⚠️ High memory: ${usedMb}MB, clearing translation cache")
                translationCacheMutex.withLock { translationCache.clear() }
                System.gc()
            }
        }
        Log.d(TAG, "💓 Heartbeat stopped")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 침묵 이벤트 처리 (VAD 감지 시 강제 확정)
    // ─────────────────────────────────────────────────────────────────────────
    fun onSilenceDetected() {
        Log.i(TAG, "🔇 [Adhesive Signal] Silence detected - Requesting FLUSH")
        flushChannel.trySend(Unit)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 유틸리티
    // ─────────────────────────────────────────────────────────────────────────
    private fun mergeAudioChunks(chunks: List<AudioChunk>, totalMs: Long): AudioChunk {
        val merged = ByteArray(chunks.sumOf { it.data.size })
        var offset = 0
        for (c in chunks) {
            System.arraycopy(c.data, 0, merged, offset, c.data.size)
            offset += c.data.size
        }
        return AudioChunk(merged, totalMs)
    }

    fun clearTranslationCache() {
        runBlocking { translationCacheMutex.withLock { translationCache.clear() } }
    }

    fun getStats(): String {
        return "chunks=${audioChunksReceived.get()} stt=${sttCallCount.get()} trans=${translationCallCount.get()} ui=${uiUpdateCount.get()} gain=${"%.2f".format(agcGain)}"
    }

    private fun logStats() {
        Log.i(TAG, "📊 Pipeline final stats: ${getStats()}")
    }
}
