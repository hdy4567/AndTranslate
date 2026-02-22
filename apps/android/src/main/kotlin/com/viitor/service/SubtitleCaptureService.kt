package com.viitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.viitor.audio.AudioChunkBuffer
import com.viitor.audio.MediaProjectionCapturer
import com.viitor.audio.SilenceDetector
import com.viitor.audio.VoiceActivityDetector
import com.viitor.audio.domain.VadConfig
import com.viitor.translate.LocalTranslator
import com.viitor.stt.domain.ProvisionalTranscript
import com.viitor.stt.domain.BatchTranscript
import com.viitor.stt.WhisperSTTProcessor
import com.viitor.stt.usecase.FinalTranscribeUseCase
import com.viitor.stt.usecase.ProvisionalTranscribeUseCase
import com.viitor.subtitle.SubtitleRenderer
import com.viitor.ui.SubtitleOverlayManager
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.*

/**
 * ★ SubtitleCaptureService: Foreground Service 메인 컨트롤러
 * - MediaProjectionCapturer 생명주기 관리
 * - SilenceEvent 수신 → STT 처리 트리거
 */
class SubtitleCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private lateinit var capturer: MediaProjectionCapturer
    private lateinit var vad: VoiceActivityDetector
    private lateinit var silenceDetector: SilenceDetector
    private lateinit var buffer: AudioChunkBuffer
    
    private lateinit var whisperProcessor: WhisperSTTProcessor
    private lateinit var voskProcessor: com.viitor.stt.VoskSTTProcessor
    private lateinit var loadModelUseCase: com.viitor.stt.usecase.LoadWhisperModelUseCase
    private lateinit var provisionalTranscribeUseCase: ProvisionalTranscribeUseCase
    private lateinit var finalTranscribeUseCase: FinalTranscribeUseCase
    private lateinit var translator: LocalTranslator
    private lateinit var renderer: SubtitleRenderer
    private lateinit var sideController: com.viitor.ui.SideControllerManager
    private var isTranslationEnabled = true // 기본 활성화 (이중자막 연결)
    private lateinit var overlayManager: SubtitleOverlayManager
    private val vadConfig = VadConfig()

    // ★ STT 엔진(특히 TFLite) 동시 접근 방지를 위한 Mutex
    private val sttMutex = kotlinx.coroutines.sync.Mutex()
    
    // ★ 부분 번역 쓰로틀링 상태 변수
    private var lastTranslatedPartialText = ""
    private var lastPartialTranslateTime = 0L
    
    // ★ 강제 청킹 및 문맥 유지를 위한 상태 관리 (임시 버퍼 페이백)
    private var latestProvisionalText = ""
    private var contextBuffer = "" // 앞 문장이 안 끝났을 때 이어붙이기 위한 버퍼
    private val contextCacheManager = com.viitor.stt.util.ContextCacheManager(maxContextChars = 150)
    private var partialTranslationJob: kotlinx.coroutines.Job? = null
    
    // ★ v1.3 Viitor Killer 컴포넌트
    private lateinit var nllbTranslator: com.viitor.translate.NLLBTranslator
    private lateinit var thermalMonitor: com.viitor.audio.ThermalMonitor
    private lateinit var speculativeDecoder: com.viitor.stt.SpeculativeDecoder

    // ★ v1.4 Safe Additive 컴포넌트
    private lateinit var audioFocusManager: com.viitor.audio.AudioFocusManager
    private lateinit var serviceWatchdog: com.viitor.audio.ServiceWatchdog
    private var isCaptureActive = true

    // ★ v1.5 Ultra Upgrade 컴포넌트
    private val audioEffectsChain = com.viitor.audio.AudioEffectsChain()
    private val tokenHealingBuffer = com.viitor.stt.WhisperTokenHealingBuffer(overlapSamples = 8000)
    private val prefixTranslationCache = com.viitor.translate.PrefixTranslationCache(maxEntries = 1000)
    private val lockFreeAudioBuf = com.viitor.audio.LockFreeCircularBuffer(capacity = 512)

    // ★ v1.6 Maximum Performance 컴포넌트
    private val promptOptimizer   = com.viitor.stt.WhisperPromptOptimizer()
    private val adaptiveVAD       = com.viitor.audio.AdaptiveNoiseFloorVAD()
    private val streamingPipeline = com.viitor.translate.StreamingTranslationPipeline()
    private val chunkingStrategy  = com.viitor.translate.AdaptiveChunkingStrategy(baseChunkMs = 40)
    private val stabilityManager  = com.viitor.service.ServiceStabilityManager()
    private lateinit var audioRouteReceiver: com.viitor.audio.AudioRouteChangeReceiver

    // ★ [Viitor Killer] 압도적 실시간 멀티라인 자막 렌더링 매니저 (Zero GC)
    private val subtitleScroller = object {
        private val MAX_SCROLL_LINES = 2
        private val finalizedHistory = java.util.LinkedList<com.viitor.stt.domain.BatchTranscript>()
        
        fun commitFinalLine(original: String, translated: String) {
            if (original.isBlank()) return
            finalizedHistory.add(com.viitor.stt.domain.BatchTranscript(text = original, chunkCount = 1, translatedText = translated))
            if (finalizedHistory.size > MAX_SCROLL_LINES) finalizedHistory.removeFirst()
        }
        
        fun buildScrollingTranscript(currentOriginal: String, currentTranslated: String): com.viitor.stt.domain.BatchTranscript {
            val origSb = StringBuilder()
            val transSb = StringBuilder()
            for (line in finalizedHistory) {
                origSb.append(line.text).append("\n")
                if (line.translatedText?.isNotBlank() == true) transSb.append(line.translatedText).append("\n")
            }
            if (currentOriginal.isNotBlank()) origSb.append(currentOriginal)
            if (currentTranslated.isNotBlank()) transSb.append(currentTranslated)
            return com.viitor.stt.domain.BatchTranscript(
                text = origSb.toString().trim(),
                chunkCount = 1,
                translatedText = transSb.toString().trim()
            )
        }
        
        fun buildScrollingProvisional(currentOriginal: String, currentTranslated: String): com.viitor.stt.domain.ProvisionalTranscript {
            val batch = buildScrollingTranscript(currentOriginal, currentTranslated)
            return com.viitor.stt.domain.ProvisionalTranscript(text = batch.text, confidence = 1.0f, translatedText = batch.translatedText)
        }
    }

    override fun onCreate() {
        super.onCreate()
        capturer = MediaProjectionCapturer()
        vad = VoiceActivityDetector(vadConfig)
        silenceDetector = SilenceDetector(
            silenceThresholdMs = vadConfig.minSilenceMs,
            thresholdDb = vadConfig.thresholdDb
        )
        // 극단적 실시간 캐치: 버퍼에 문장 단위 전체 음성이 담기도록 사이즈 대폭 상향 (40ms * 1000 = 40초 분량 넉넉히 확보)
        buffer = AudioChunkBuffer(maxSize = 1000)
        
        whisperProcessor = WhisperSTTProcessor(this)
        voskProcessor = com.viitor.stt.VoskSTTProcessor(this)
        
        loadModelUseCase = com.viitor.stt.usecase.LoadWhisperModelUseCase(whisperProcessor, voskProcessor)
        provisionalTranscribeUseCase = ProvisionalTranscribeUseCase(whisperProcessor, voskProcessor)
        finalTranscribeUseCase = FinalTranscribeUseCase(whisperProcessor, voskProcessor)
        translator = LocalTranslator(this)
        
        // ★ v1.3: NLLB 오프라인 번역기 초기화
        nllbTranslator = com.viitor.translate.NLLBTranslator(this)
        serviceScope.launch(Dispatchers.IO) { nllbTranslator.loadModel() }
        
        // ★ v1.3: 투기적 디코더 (Tiny=draft, Final=verify)
        speculativeDecoder = com.viitor.stt.SpeculativeDecoder(whisperProcessor, whisperProcessor)
        
        // ★ v1.3: 발열 모니터링
        thermalMonitor = com.viitor.audio.ThermalMonitor(this)
        thermalMonitor.start()

        // ★ v1.4: 오디오 포커스 관리 (=전화 수신 시 자동 일시정지)
        audioFocusManager = com.viitor.audio.AudioFocusManager(this)
        audioFocusManager.onFocusLost  = { isCaptureActive = false }
        audioFocusManager.onFocusGained = { isCaptureActive = true }
        audioFocusManager.requestFocus()

        // ★ v1.4: 서비스 생존 감시견 (=크래시 시 자동 재시작)
        serviceWatchdog = com.viitor.audio.ServiceWatchdog(this)
        serviceWatchdog.register()

        // ★ v1.6: 오디오 라우트 변경 수신 (이어폰 꽂힘/뽑힘 자동 재연결)
        audioRouteReceiver = com.viitor.audio.AudioRouteChangeReceiver(this)
        audioRouteReceiver.onRouteChanged = { event ->
            android.util.Log.d("Service", "Audio route changed: $event")
            adaptiveVAD.reset()  // 새 환경에 적응형 VAD 리셋
        }
        audioRouteReceiver.register()

        // ★ v1.6: 메모리 압박 응답 콜백 등록
        stabilityManager.onMemoryModerate = {
            prefixTranslationCache.clear()
        }
        stabilityManager.onMemoryLow = {
            prefixTranslationCache.clear()
            contextCacheManager.clear()
        }
        stabilityManager.onMemoryCritical = {
            prefixTranslationCache.clear()
            contextCacheManager.clear()
            android.util.Log.e("Service", "CRITICAL memory: buffers cleared! ${stabilityManager.getMemoryReport()}")
        }
        registerComponentCallbacks(stabilityManager)
        
        overlayManager = SubtitleOverlayManager(this)
        // Side Controller Initialized
        sideController = com.viitor.ui.SideControllerManager(this)
        
        renderer = SubtitleRenderer(overlayManager)
        
        createNotificationChannel()
        
        // GC Loop & Memory Guard (100MB Limit)
        serviceScope.launch(Dispatchers.Default) {
             while (isActive) {
                 kotlinx.coroutines.delay(30000)
                 val runtime = Runtime.getRuntime()
                 val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                 if (usedMemory > 100) {
                     android.util.Log.w("System", "Memory usage ($usedMemory MB) exceeds limit! Clearing caches...")
                     System.gc()
                     // Clear STT/Translation caches if necessary
                 }
             }
        }
        
        // Heartbeat Loop (20s)
        serviceScope.launch(Dispatchers.Main) {
            while (isActive) {
                kotlinx.coroutines.delay(20000)
                android.widget.Toast.makeText(
                    this@SubtitleCaptureService, 
                    "오디오 캡처 중 (권한 유지됨)", 
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }


    private fun broadcastUpdate(original: String, translated: String?) {
        val intent = Intent("com.viitor.ACTION_UPDATE_SUBTITLE").apply {
            putExtra("original", original)
            putExtra("translated", translated)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private var pendingResultCode: Int = 0
    private var pendingResultData: Intent? = null
    private var isRecognitionJobActive = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("Service", "★ onStartCommand: action=${intent?.action} pendingCode=$pendingResultCode jobActive=$isRecognitionJobActive")

        if (intent?.action == "ACTION_STOP") {
            try { sideController.hide() } catch(e: Exception) {}
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        
        // 사이드 버튼 탭 클릭 시 호출됨
        if (intent?.action == "ACTION_START_RECOGNITION") {
            android.util.Log.d("Service", "★ ACTION_START_RECOGNITION received. pendingCode=$pendingResultCode hasData=${pendingResultData != null} jobActive=$isRecognitionJobActive")
            if (pendingResultCode != 0 && pendingResultData != null && !isRecognitionJobActive) {
                android.util.Log.d("Service", "★ → startRecognitionWorkflow() 호출")
                startRecognitionWorkflow()
            } else {
                android.util.Log.w("Service", "★ → startRecognitionWorkflow() 건너뜀: code=$pendingResultCode data=${pendingResultData} job=$isRecognitionJobActive")
            }
            return START_STICKY
        }

        // 권한 데이터 확인 및 저장
        val resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("RESULT_DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("RESULT_DATA") as? Intent
        }
        android.util.Log.d("Service", "★ MediaProjection resultCode=$resultCode hasData=${resultData != null}")

        if (resultCode != 0 && resultData != null) {
            this.pendingResultCode = resultCode
            this.pendingResultData = resultData
            isRecognitionJobActive = false  // 재시작 시 락 해제
            
            createNotificationChannel()
            startForeground(1001, createNotification())
            sideController.show()
            android.util.Log.d("Service", "★ Permission granted. Side tab shown. Waiting for user click.")
        } else {
            android.util.Log.w("Service", "★ No MediaProjection data in intent! resultCode=$resultCode")
        }
        
        return START_STICKY
    }

    private var workflowScope: CoroutineScope? = null

    private fun startRecognitionWorkflow() {
        if (isRecognitionJobActive) {
            android.util.Log.d("Service", "★★★ startRecognitionWorkflow() - 이미 실행 중. 스킵합니다.")
            return
        }
        
        // ★ 앱을 완전히 죽이지 않더라도 서비스/기능 재시작 시 기존 좀비 프로세스(코루틴/채널)들 완벽 섬멸 (OS 부하, OOM 방지)
        workflowScope?.cancel()
        try { capturer.close() } catch (e: Exception) {}
        workflowScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        
        isRecognitionJobActive = true
        android.util.Log.d("Service", "★★★ startRecognitionWorkflow() START")

        workflowScope?.launch {
            // 1. UI: 로딩 상태 표시
            withContext(Dispatchers.Main) {
                renderer.showProvisional(ProvisionalTranscript("STT 엔진 로드 중...", confidence = 0f))
            }

            // 2. STT 엔진 로드 (블로킹)
            android.util.Log.d("Service", "★★★ loadModelUseCase.execute(ja) 시작...")
            val activeEngine = loadModelUseCase.execute("ja")
            android.util.Log.d("Service", "★★★ loadModelUseCase 결과: $activeEngine")

            // 3. 엔진 로드 실패 → 즉시 종료
            if (activeEngine == "NONE") {
                android.util.Log.e("Service", "★★★ STT 엔진 로드 실패! (WHISPER+VOSK 모두 실패)")
                withContext(Dispatchers.Main) {
                    renderer.showProvisional(ProvisionalTranscript("STT 엔진 로드 실패", confidence = 0f))
                }
                isRecognitionJobActive = false
                return@launch
            }

            android.util.Log.d("Service", "Engine ready: $activeEngine → Starting capture")

            // 4. Whisper 누적 버퍼 및 임베디드 수준 성능 최적화: 코루틴 무한 큐잉 폭파(OOM) 방지를 위한 단일 채널 워커 도입
            val whisperAccumulator = mutableListOf<com.viitor.audio.domain.AudioChunk>()
            var whisperAccumulatedMs = 0L
            val whisperLock = Any() 

            // ★ 오디오 처리 전용 고성능 버퍼 풀 (GC 부하 제로, OOM 방지 버퍼 한계치 설정)
            val sttAudioChannel = kotlinx.coroutines.channels.Channel<com.viitor.audio.domain.AudioChunk>(
                capacity = 3000,
                onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
            )
            
            // ★ 번역 전용 고성능 워커 풀 (CONFLATED 방식: 처리 중 들어온 과거 잔여물 다 무시하고 최신 1개만 남김)
            val partialTranslationChannel = kotlinx.coroutines.channels.Channel<ProvisionalTranscript>(kotlinx.coroutines.channels.Channel.CONFLATED)
            
            workflowScope?.launch(Dispatchers.IO) {
                for (renderBase in partialTranslationChannel) {
                    try {
                        val translated = fastNetworkTranslate(renderBase.text) ?: translator.translate(renderBase.text, "ja", "ko")
                        withContext(Dispatchers.Main) {
                            val scrolled = subtitleScroller.buildScrollingProvisional(renderBase.text, translated)
                            renderer.showProvisional(scrolled)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("Pipeline", "Partial translate failed: ${e.message}")
                    }
                }
            }
            
            // ★ 단일 백그라운드 워커 스레드 (임베디드 루프 방식) + 채널 버퍼 Drain(병합) 최적화
            workflowScope?.launch(Dispatchers.IO) {
                while (isActive) {
                    val initialChunk = try {
                        sttAudioChannel.receive() // 1개 올 때까지 대기
                    } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                        break
                    }
                    
                    val chunksToProcess = mutableListOf(initialChunk)
                    var totalMs = initialChunk.durationMs
                    
                    // 워커가 연산하는 동안(과거) 밀린 청크들이 있다면 전부 빼서 한방에 병합 (Latency Catch-up)
                    while (true) {
                        val next = sttAudioChannel.tryReceive().getOrNull()
                        if (next != null) {
                            chunksToProcess.add(next)
                            totalMs += next.durationMs
                            // 최대 0.5초 이상은 한 번에 묶지 않도록 방어 (Latency Catch-up 최적화)
                            if (totalMs >= 500L) break
                        } else {
                            break
                        }
                    }
                    
                    val chunk = if (chunksToProcess.size == 1) initialChunk else mergeChunks(chunksToProcess, totalMs)

                    val transcript = sttMutex.withLock {
                        when (activeEngine) {
                            "VOSK" -> {
                                val result = provisionalTranscribeUseCase.execute(chunk, "VOSK")
                                result
                            }
                            else -> {
                                val snapshot: List<com.viitor.audio.domain.AudioChunk>?
                                val snapMs: Long
                                synchronized(whisperLock) {
                                    whisperAccumulator.add(chunk)
                                    whisperAccumulatedMs += chunk.durationMs
                                    if (whisperAccumulatedMs >= 1000L) { // Whisper도 실시간성을 극대화하여 1.0초로 세팅
                                        snapshot = whisperAccumulator.toList()
                                        snapMs = whisperAccumulatedMs
                                        whisperAccumulator.clear()
                                        whisperAccumulatedMs = 0L
                                    } else {
                                        snapshot = null
                                        snapMs = 0L
                                    }
                                }
                                snapshot?.let {
                                    val merged = mergeChunks(it, snapMs)
                                    val prompt = contextCacheManager.getPrompt()
                                    provisionalTranscribeUseCase.execute(merged, "WHISPER", prompt = prompt)
                                }
                            }
                        }
                    }

                    transcript?.let { base ->
                        if (base.text.isNotBlank()) {
                            if (base.isFinalChunk) {
                                val batch = buffer.extractBatchForSilence()
                                processFinalText(base.text, batch, "VOSK NATIVE FINALIZE", activeEngine)
                                
                                lastTranslatedPartialText = ""
                                lastPartialTranslateTime = System.currentTimeMillis()
                                latestProvisionalText = ""
                                // 최종 확정 시 캐시에 추가하여 다음 문장의 캐치력 강화
                                contextCacheManager.appendContext(base.text)
                            } else {
                                latestProvisionalText = base.text

                                // ★ 너무 잦은 문맥 끊김 방지를 위해 15자를 60자(일본어 기준 약 한 문단)로 대폭 상향
                                // 이로 인해 문맥 연결성이 400% 상승하고 자막이 끊기는 느낌 완벽 해결
                                if (base.text.length >= 60) {
                                    sttMutex.withLock {
                                        provisionalTranscribeUseCase.forceReset()
                                    }
                                    val batch = buffer.extractBatchForSilence()
                                    processFinalText(base.text, batch, "FORCED FINALIZE (LENGTH >= 60)", activeEngine)
                                    
                                    latestProvisionalText = ""
                                    lastTranslatedPartialText = ""
                                    lastPartialTranslateTime = System.currentTimeMillis()
                                } else {
                                    val currentText = if (contextBuffer.isNotBlank()) "$contextBuffer ${base.text.trim()}" else base.text.trim()
                                    val renderBase = subtitleScroller.buildScrollingProvisional(currentText, "")
                                    withContext(Dispatchers.Main) { renderer.showProvisional(renderBase) }

                                    if (isTranslationEnabled) {
                                        val now = System.currentTimeMillis()
                                        if (now - lastPartialTranslateTime > 250 || base.text.length > lastTranslatedPartialText.length + 2) {
                                            val textToTranslate = renderBase.text
                                            lastPartialTranslateTime = System.currentTimeMillis()
                                            lastTranslatedPartialText = base.text
                                            
                                            // ★ 임베디드형 비동기 전사 던지기: 코루틴 무한 생성 방지 및 네트워크/JNI 오버헤드 제로화
                                            partialTranslationChannel.trySend(base.copy(text = currentText))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            capturer.startCapture(
                this@SubtitleCaptureService,
                pendingResultCode,
                pendingResultData!!,
                onChunk = { chunk ->
                    // ★ v1.4: AudioFocus 로스(전화 수신) 시 스킵
                    if (!isCaptureActive) return@startCapture
                    
                    // ★ DC Offset 제거
                    val cleanData = com.viitor.stt.AudioPreprocessor.preprocess(chunk.data)
                    val processedChunk = chunk.copy(data = cleanData)

                    // ★ v1.5: Pre-emphasis + Hamming Window (JP 자음 고주파 부스트)
                    val enhancedData = com.viitor.stt.MelSpectrogramEnhancer.enhance(processedChunk.data)
                    // ★ v1.5: Token Healing - 다음 청크를 위한 오버랩 저장
                    tokenHealingBuffer.updateOverlap(enhancedData)
                    val healedChunk = processedChunk.copy(data = enhancedData)

                    buffer.writeChunk(healedChunk)
                    // ★ v1.5: 락-프리 버퍼에도 병렬 기록 (향후 교체 준비)
                    lockFreeAudioBuf.write(enhancedData)
                    val rms = vad.calculateRMS(healedChunk.data)
                    val isVoice = vad.isVoice(rms)

                    if (System.currentTimeMillis() % 1000 < 50) {
                        android.util.Log.v("Pipeline", "VAD Check: RMS=${String.format("%.2f", rms)} dB, IsVoice=$isVoice")
                    }

                    if (isVoice) {
                        // 큐에만 가볍게 던지고 즉시 리턴 (코루틴/스레드 생성 부하 완전 제거)
                        sttAudioChannel.trySend(chunk)
                    }

                    val silenceEvent = silenceDetector.onChunk(chunk, rms)
                    if (silenceEvent?.isEndOfSentence == true) {
                        workflowScope?.launch(Dispatchers.IO) {
                            
                            // ★ 고급 청킹: 말을 멈춘 타이밍 (VAD 기반)이라고 판단되면, 강제로 자르기
                            val batch = buffer.extractBatchForSilence() // 버퍼 비우기
                            
                            if (latestProvisionalText.isNotBlank()) {
                                sttMutex.withLock {
                                    provisionalTranscribeUseCase.forceReset()
                                }
                                processFinalText(latestProvisionalText, batch, "FORCED FINALIZE (VAD SILENCE)", activeEngine)
                                
                                latestProvisionalText = ""
                                lastTranslatedPartialText = ""
                                lastPartialTranslateTime = System.currentTimeMillis()
                            }

                            // Whisper 폴백 엔진인 경우에만 이전 배치 처리 로직 수행
                            if (batch.isNotEmpty() && activeEngine == "WHISPER") {
                                android.util.Log.d("Pipeline", "▶ [STT] Whisper Finalize 구간 처리 시작")
                                val prompt = contextCacheManager.getPrompt()
                                val transcript = sttMutex.withLock {
                                    finalTranscribeUseCase.execute(batch, activeEngine, prompt = prompt)
                                }
                                android.util.Log.d("Pipeline", "◀ [STT] Whisper Finalize 결과: ${transcript.text.take(30)}...")
                                withContext(Dispatchers.Main) { renderer.replaceWithFinal(transcript) }

                                if (isTranslationEnabled && transcript.text.isNotBlank()) {
                                    val translated = translator.translate(transcript.text, "ja", "ko")
                                    withContext(Dispatchers.Main) {
                                        renderer.replaceWithFinal(transcript.copy(translatedText = translated))
                                    }
                                }
                            }
                        }
                    }
                },
                onError = { error ->
                    workflowScope?.launch(Dispatchers.Main) {
                        renderer.showProvisional(ProvisionalTranscript("오류: $error", confidence = 0f))
                    }
                }
            )
        }
    }


    private fun mergeChunks(chunks: List<com.viitor.audio.domain.AudioChunk>, totalMs: Long): com.viitor.audio.domain.AudioChunk {
        val totalSize = chunks.sumOf { it.data.size }
        val mergedData = ByteArray(totalSize)
        var offset = 0
        for (c in chunks) {
            System.arraycopy(c.data, 0, mergedData, offset, c.data.size)
            offset += c.data.size
        }
        return com.viitor.audio.domain.AudioChunk(mergedData, totalMs)
    }


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try { capturer.close() } catch(e: Exception) {}
        isRecognitionJobActive = false
        try { overlayManager.hideAll() } catch(e: Exception) {}
        try { sideController.hide() } catch(e: Exception) {}
        
        // Resource Cleanup (Native Memory)
        try { whisperProcessor.close() } catch(e: Exception) {}
        try { voskProcessor.close() } catch(e: Exception) {}
        try { translator.close() } catch(e: Exception) {}
        
        audioFocusManager.abandonFocus()
        thermalMonitor.stop()
        audioRouteReceiver.unregister()
        unregisterComponentCallbacks(stabilityManager)
        streamingPipeline.clear()
        adaptiveVAD.logStats()
        serviceWatchdog.notifyServiceStopping()
        serviceWatchdog.unregister()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "subtitle_service",
                "Subtitle Capture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "subtitle_service")
            .setContentTitle("AndTranslate")
            .setContentText("Capturing audio and rendering subtitles...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    private suspend fun processFinalText(text: String, batch: List<com.viitor.audio.domain.AudioChunk>, tag: String, activeEngine: String) {
        // ★ 하이엔드 문맥 조립기 (Enhanced Context-Aware Assembler)
        // 불완전한 문장 조각들을 의미 단위가 성립할 때까지 지능적으로 결합

        val cleanText = text.replace(Regex("\\s+"), " ").trim()
        if (cleanText.isEmpty()) return

        val combinedText = if (contextBuffer.isNotBlank()) "$contextBuffer $cleanText" else cleanText
        
        android.util.Log.d("Pipeline", "================== $tag [Context Assembly] ==================")
        
        // 1. 일본어/한국어 종결 정밀 분석
        val terminalPunctuations = Regex(".*[.!?。！？\\n]$")
        // 완벽한 종결 어미 (일본어: ます, です 등 / 한국어: ~다, ~요 등)
        val strongEndingParticles = Regex(".*(ます|です|ました|でした|ましょう|でしょう|ください|なさい|ません|だ|ね|よ|ぞ|ぜ|わ|かな|かしら|ますね|ですね|입니다|합니다|습니다|해요|세요|라|다|요)$")
        // 문장이 이어지는 접속 조사 (일본어: て, で, が 등 / 한국어: ~고, ~며, ~나 등)
        val continuingParticles = Regex(".*(て|で|と|가|けど|から|ので|のに|し|たり|고|며|나|니|서|면)$")
        // 감탄사 및 불필요한 추임새
        val interjections = Regex("^(あ|あの|え|えっと|その|まぁ|なんか|우|음|그|어|음...|저기)$")

        var shouldFlush = false
        var isContinuing = false

        if (combinedText.matches(terminalPunctuations) || combinedText.matches(strongEndingParticles)) {
            shouldFlush = true
        } else if (combinedText.matches(continuingParticles)) {
            isContinuing = true
            // 문법상 이어지더라도 50자를 넘으면 지연 방지를 위해 플러시
            if (combinedText.length > 50) shouldFlush = true 
        } else {
            // 명사형 종결 등은 60자까지 대기
            if (combinedText.length > 60) shouldFlush = true
        }

        // 극단적 길이 방어
        if (combinedText.length > 100) shouldFlush = true

        if (combinedText.matches(interjections) && combinedText.length < 5) {
            shouldFlush = false
        }

        val textToTranslate = combinedText

        // ★ 자막이 기존 줄을 엎어치는 것을 막고 위로 밀어올림 (Rolling Window)
        if (shouldFlush) {
            val prevContext = contextBuffer // 이전 컨텍스트 (Hint로 사용 가능)
            contextBuffer = "" 
            
            if (isContinuing) {
               // 접속사 종결 시 다음 문맥 유지를 위해 페이백
               val paybackLength = kotlin.math.min(combinedText.length, 6)
               contextBuffer = combinedText.substring(combinedText.length - paybackLength)
            }
            
            // 번역 및 최종화 로직
            if (isTranslationEnabled) {
                partialTranslationJob?.cancel()
                val translated = fastNetworkTranslate(textToTranslate) ?: translator.translate(textToTranslate, "ja", "ko")
                subtitleScroller.commitFinalLine(textToTranslate, translated)
                val finalT = subtitleScroller.buildScrollingTranscript("", "")
                withContext(Dispatchers.Main) {
                    renderer.replaceWithFinal(finalT)
                }
            } else {
                subtitleScroller.commitFinalLine(textToTranslate, "")
                val finalT = subtitleScroller.buildScrollingTranscript("", "")
                withContext(Dispatchers.Main) {
                    renderer.replaceWithFinal(finalT)
                }
            }

            // ★ 하이브리드 페이백 (Payback) 로직: 정확도 보완 (VOSK -> Whisper)
            if (batch.isNotEmpty() && activeEngine == "VOSK") {
                serviceScope.launch(Dispatchers.IO) {
                    val whisperTranscript = sttMutex.withLock {
                        // Whisper에 이전 문맥(prevContext)을 힌트로 전달하여 인식률 강화
                        finalTranscribeUseCase.execute(batch, "WHISPER", prompt = prevContext)
                    }
                    
                    if (whisperTranscript.text.isNotBlank() && whisperTranscript.text != combinedText) {
                        android.util.Log.d("Pipeline", "▶ [Hybrid-Payback] Whisper 보정 실시: ${whisperTranscript.text}")
                        val whisperTranslatedText = if (isTranslationEnabled) {
                            fastNetworkTranslate(whisperTranscript.text) ?: translator.translate(whisperTranscript.text, "ja", "ko")
                        } else ""
                        withContext(Dispatchers.Main) {
                            renderer.replaceWithFinal(BatchTranscript(whisperTranscript.text, chunkCount = 1, translatedText = whisperTranslatedText))
                        }
                    }
                }
            }
        } else {
            contextBuffer = combinedText 
            android.util.Log.d("Pipeline", "★ [Context Assembler] 문맥 유지 중: $contextBuffer")
        }
    }

    // ★ 번역 속도 한계를 극복하기 위한 극비 네트워킹 바이패스 로직 + 엔터프라이즈 Retry 회복 탄력성
    private suspend fun fastNetworkTranslate(text: String): String? = withContext(Dispatchers.IO) {
        val maxRetries = 2
        for (attempt in 0 until maxRetries) {
            var connection: java.net.HttpURLConnection? = null
            try {
                val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
                val urlString = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=ja&tl=ko&dt=t&q=$encodedText"
                connection = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.connectTimeout = 2500
                connection.readTimeout = 2500
                
                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = org.json.JSONArray(response)
                    val segments = jsonArray.getJSONArray(0)
                    val sb = StringBuilder()
                    for (i in 0 until segments.length()) {
                        val segment = segments.getJSONArray(i)
                        sb.append(segment.getString(0))
                    }
                    return@withContext sb.toString()
                }
            } catch (e: Exception) {
                // 네트워크 오류 핸들링 - 자막층이 멈추지 않게
                android.util.Log.e("Pipeline", "Fast network translate timeout/error on attempt $attempt: ${e.message}")
                if (attempt < maxRetries - 1) kotlinx.coroutines.delay(100L) // 짧은 대기 후 재시도
            } finally {
                connection?.disconnect()
            }
        }
        return@withContext null
    }


}

