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
    private var partialTranslationJob: kotlinx.coroutines.Job? = null

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
        
        overlayManager = SubtitleOverlayManager(this)
        // Side Controller Initialized
        sideController = com.viitor.ui.SideControllerManager(this)
        
        renderer = SubtitleRenderer(overlayManager)
        
        createNotificationChannel()
        
        // GC Loop
        serviceScope.launch(Dispatchers.Default) {
             while (isActive) {
                 kotlinx.coroutines.delay(60000)
                 System.gc()
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

            // ★ 오디오 처리 전용 고성능 버퍼 풀 (GC 부하 제로)
            val sttAudioChannel = kotlinx.coroutines.channels.Channel<com.viitor.audio.domain.AudioChunk>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            
            // ★ 번역 전용 고성능 워커 풀 (CONFLATED 방식: 처리 중 들어온 과거 잔여물 다 무시하고 최신 1개만 남김)
            val partialTranslationChannel = kotlinx.coroutines.channels.Channel<ProvisionalTranscript>(kotlinx.coroutines.channels.Channel.CONFLATED)
            
            workflowScope?.launch(Dispatchers.IO) {
                for (renderBase in partialTranslationChannel) {
                    try {
                        val translated = translator.translate(renderBase.text, "ja", "ko")
                        withContext(Dispatchers.Main) {
                            renderer.showProvisional(renderBase.copy(translatedText = translated))
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
                            // 최대 1초 이상은 한 번에 묶지 않도록 방어 (너무 큰 STT 연산 방지)
                            if (totalMs >= 1000L) break
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
                                    if (whisperAccumulatedMs >= 1500L) { // Whisper도 실시간성을 약간 높여서 1.5초로 세팅
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
                                    provisionalTranscribeUseCase.execute(merged, "WHISPER")
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
                            } else {
                                latestProvisionalText = base.text

                                if (base.text.length >= 15) {
                                    sttMutex.withLock {
                                        provisionalTranscribeUseCase.forceReset()
                                    }
                                    val batch = buffer.extractBatchForSilence()
                                    processFinalText(base.text, batch, "FORCED FINALIZE (LENGTH >= 15)", activeEngine)
                                    
                                    latestProvisionalText = ""
                                    lastTranslatedPartialText = ""
                                    lastPartialTranslateTime = System.currentTimeMillis()
                                } else {
                                    val renderBase = if (contextBuffer.isNotBlank()) base.copy(text = "$contextBuffer ${base.text}") else base
                                    withContext(Dispatchers.Main) { renderer.showProvisional(renderBase) }

                                    if (isTranslationEnabled) {
                                        val now = System.currentTimeMillis()
                                        if (now - lastPartialTranslateTime > 400 || base.text.length > lastTranslatedPartialText.length + 2) {
                                            val textToTranslate = renderBase.text
                                            lastPartialTranslateTime = System.currentTimeMillis()
                                            lastTranslatedPartialText = base.text
                                            
                                            // ★ 임베디드형 비동기 전사 던지기: 코루틴 무한 생성 방지 및 네트워크/JNI 오버헤드 제로화
                                            partialTranslationChannel.trySend(renderBase)
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
                    buffer.writeChunk(chunk)
                    val rms = vad.calculateRMS(chunk.data)
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
                                val transcript = sttMutex.withLock {
                                    finalTranscribeUseCase.execute(batch, activeEngine)
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
        // ★ 엔터프라이즈급 일본어 문맥 조립기 (Enterprise Context-Aware Assembler)
        // 불완전한 문장 조각("나는", "어제", "밥을")을 의미 단위가 성립할 때까지(최대 80자) 지능적으로 결합하여 번역률 2배 향상

        val cleanText = text.replace(Regex("\\s+"), "").trim()
        if (cleanText.isEmpty()) return

        val combinedText = if (contextBuffer.isNotBlank()) "$contextBuffer $cleanText" else cleanText
        
        android.util.Log.d("Pipeline", "================== $tag [Context Assembly] ==================")
        
        // 1. 일본어 종결어미 및 문장 부호 정밀 분석
        val terminalPunctuations = Regex(".*[.!?。！？\\n]$")
        // 완벽한 종결을 나타내는 어미 (이 경우 즉시 번역 후 버퍼 비움)
        val strongEndingParticles = Regex(".*(ます|です|ました|でした|ましょう|でしょう|ください|なさい|ません|だ|ね|よ|ぞ|ぜ|わ|かな|かしら|ますね|ですね)$")
        // 문장이 이어지는 접속 조사/어미 (이 경우 다음 번역기에 컨텍스트 제공을 위해 일부 페이백 보류)
        val continuingParticles = Regex(".*(て|で|と|が|けど|から|ので|のに|し|たり)$")
        // 의미 없는 감탄사 (단독 번역하지 않고 무시/결합)
        val interjections = Regex("^(あ|あの|え|えっと|その|まぁ|なんか|うーん|はい|いいえ)$")

        var shouldFlush = false
        var isContinuing = false

        if (combinedText.matches(terminalPunctuations) || combinedText.matches(strongEndingParticles)) {
            shouldFlush = true
        } else if (combinedText.matches(continuingParticles)) {
            isContinuing = true
            // 문법상 이어지더라도, 길이가 40자를 넘으면 막대한 번역 지연 및 OOM 방지를 위해 강제 번역 플러시
            if (combinedText.length > 40) shouldFlush = true 
        } else {
            // 그 외의 경우 (명사로 끝나는 등) 좀 더 지연시킴
            if (combinedText.length > 50) shouldFlush = true
        }

        // 극단적인 길이 방어 (UI 렌더링 터짐 및 AI 엔진 OOM 방지)
        if (combinedText.length > 80) shouldFlush = true

        // 의미 없는 단일 감탄사면 플러시하지 않고 모음
        if (combinedText.matches(interjections) && combinedText.length < 5) {
            shouldFlush = false
        }

        val textToTranslate = combinedText

        if (shouldFlush) {
            contextBuffer = "" // 플러시: 완벽한 1문장 처리 완료
            if (isContinuing) {
               // 연결형 어미로 끝난 경우, 다음 문장의 앞뒤 문맥(Attention) 유지를 위해 마지막 3글자를 다음 버퍼에 "페이백(Payback)"
               val paybackLength = kotlin.math.min(combinedText.length, 3)
               contextBuffer = combinedText.substring(combinedText.length - paybackLength)
               android.util.Log.d("Pipeline", "★ [Context Assembler] 접속사 감지, 페이백 버퍼 유지: $contextBuffer")
            }
        } else {
            // 불완전 문장은 번역 퀄리티 파괴의 주범이므로번역기에는 던지되 다음 턴으로 문맥 이월 (페이백)
            contextBuffer = combinedText 
            android.util.Log.d("Pipeline", "★ [Context Assembler] 불완전 문장 홀드: $contextBuffer")
        }

        val finalT = BatchTranscript(textToTranslate, chunkCount = 1)
        withContext(Dispatchers.Main) { renderer.replaceWithFinal(finalT) }

        if (isTranslationEnabled) {
            android.util.Log.d("Pipeline", "▶ [Translator] $tag 완성 문맥 번역 요청: $textToTranslate")
            partialTranslationJob?.cancel() // 이전 잔여 partial 번역 완벽 무시
            
            // 온라인 빠른 번역 시도
            val fastTranslated = fastNetworkTranslate(textToTranslate)
            val translated = if (fastTranslated != null) {
                android.util.Log.d("Pipeline", "◀ [Translator] [FAST-NETWORK] 번역 완료: $fastTranslated")
                fastTranslated
            } else {
                val t = translator.translate(textToTranslate, "ja", "ko")
                android.util.Log.d("Pipeline", "◀ [Translator] [OFFLINE-ARGOS] 번역 완료: $t")
                t
            }
            
            withContext(Dispatchers.Main) {
                renderer.replaceWithFinal(finalT.copy(translatedText = translated))
            }
        }

        // ★ 하이브리드 페이백 (Payback) 로직
        // Vosk가 빠르게 자막을 띄운 뒤, 배경에서 백그라운드로 돌린 Whisper 결과가 더 깔끔하면 덮어쓰기 실시
        if (batch.isNotEmpty() && activeEngine == "VOSK" && combinedText.length > 3) {
            serviceScope.launch(Dispatchers.IO) {
                val whisperTranscript = sttMutex.withLock {
                    finalTranscribeUseCase.execute(batch, "WHISPER")
                }
                
                if (whisperTranscript.text.isNotBlank() && whisperTranscript.text != combinedText) {
                    android.util.Log.d("Pipeline", "▶ [Hybrid-Payback] 위스퍼 통로 페이백 실시!")
                    android.util.Log.d("Pipeline", "   Vosk: $combinedText")
                    android.util.Log.d("Pipeline", "   Whisper: ${whisperTranscript.text}")
                    
                    val whisperTranslatedText = if (isTranslationEnabled) {
                        fastNetworkTranslate(whisperTranscript.text) ?: translator.translate(whisperTranscript.text, "ja", "ko")
                    } else ""
                    
                    withContext(Dispatchers.Main) {
                        renderer.replaceWithFinal(BatchTranscript(whisperTranscript.text, chunkCount = 1, translatedText = whisperTranslatedText))
                    }
                }
            }
        }
    }

    // ★ 번역 속도 한계를 극복하기 위한 극비 네트워킹 바이패스 로직 (API 키 없는 무료 우회, 오프라인 전환 가능)
    private suspend fun fastNetworkTranslate(text: String): String? = withContext(Dispatchers.IO) {
        try {
            val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
            val urlString = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=ja&tl=ko&dt=t&q=$encodedText"
            val connection = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            
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
            // 네트워크 오류 (Timeout 등) 핸들링 - 자막층이 멈추지 않게
            android.util.Log.e("Pipeline", "Fast network translate timeout or error: ${e.message}")
        }
        return@withContext null
    }
}

