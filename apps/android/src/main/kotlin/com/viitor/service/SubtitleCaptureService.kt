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
    private val overlayManager: SubtitleOverlayManager by lazy { SubtitleOverlayManager(this) }
    private val vadConfig = VadConfig().apply { 
        thresholdDb = -65.0f // ⚡ [Browser-Optimal] -65dB is the sweet spot for browser audio capture.
        minSilenceMs = 300 
    }

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
    private var lastAudioTime = System.currentTimeMillis()
    private var isFirstAudio = true
    private var lastStatusUpdateTime = 0L
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    // [Smart Subtitle Agent] 모든 컴포넌트가 융합된 단일 요원
    private var subtitleAgent: com.viitor.pipeline.SmartSubtitleAgent? = null
    
    // 유저 활동(핑) 감지를 위한 브로드캐스트 리시버
    private val userPingReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.viitor.ACTION_USER_PING") {
                lastStatusUpdateTime = System.currentTimeMillis()
            }
        }
    }
    
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
    private var activeLanguage = "ja" // ⚡ [Enterprise Fix] JP 캐치력 상승을 위한 주력 언어 설정

    // ★ [Viitor Killer] 압도적 실시간 멀티라인 자막 렌더링 매니저 (Zero GC)
    private val subtitleScroller: com.viitor.subtitle.ISubtitleScroller = com.viitor.subtitle.SubtitleScroller(maxLines = 2)

    // ⚡ [Dynamic Domain Engine] Dynamic slang/domain mapping loader
    private val glossaryManager = object {
        private var glossary = mutableMapOf<String, String>()
        
        fun load(context: android.content.Context) {
            try {
                val json = context.assets.open("glossary.json").bufferedReader().use { it.readText() }
                val root = org.json.JSONObject(json)
                val domains = root.optJSONObject("domains") ?: return
                val keys = domains.keys()
                while (keys.hasNext()) {
                    val domain = domains.getJSONObject(keys.next())
                    val slangKeys = domain.keys()
                    while (slangKeys.hasNext()) {
                        val k = slangKeys.next()
                        glossary[k] = domain.getString(k)
                    }
                }
                android.util.Log.d("Glossary", "Loaded ${glossary.size} terms from glossary.json")
            } catch (e: Exception) {
                android.util.Log.e("Glossary", "Load failure: ${e.message}")
            }
        }

        fun apply(text: String): String {
            var result = text
            for ((k, v) in glossary) {
                if (result.contains(k)) result = result.replace(k, v)
            }
            return result
        }
    }

    // ★ [Enterprise NLP Preprocessor] JP->KO 번역 퀄리티 SSS급 부스팅을 위한 클렌징 엔진
    private val nlpPreprocessor = object {
        private val fillerRegex = Regex("^(あの|ええと|えー|えっと|그게|그니까|저기|음|어)[,、\\s]*")
        private val stutterRegex = Regex("([一-龯ぁ-んァ-ン]+)[,、\\s]+\\1")

        fun optimizeForTranslation(text: String): String {
            if (text.isBlank()) return text
            var polished = text
            polished = polished.replace(stutterRegex, "$1")
            polished = polished.replace(fillerRegex, "")
            polished = glossaryManager.apply(polished) // ⚡ [Viitor-Logic] Dynamic glossary
            return polished.trim()
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        // 유저 활동 리시버 등록
        val filter = android.content.IntentFilter("com.viitor.ACTION_USER_PING")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(userPingReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(userPingReceiver, filter)
        }

        capturer = MediaProjectionCapturer()
        vad = VoiceActivityDetector(vadConfig)
        silenceDetector = SilenceDetector(
            silenceThresholdMs = vadConfig.minSilenceMs,
            thresholdDb = vadConfig.thresholdDb
        )
        buffer = AudioChunkBuffer(maxSize = 1000)
        
        whisperProcessor = WhisperSTTProcessor(this)
        voskProcessor = com.viitor.stt.VoskSTTProcessor(this)
        
        loadModelUseCase = com.viitor.stt.usecase.LoadWhisperModelUseCase(whisperProcessor, voskProcessor)
        provisionalTranscribeUseCase = ProvisionalTranscribeUseCase(whisperProcessor, voskProcessor)
        finalTranscribeUseCase = FinalTranscribeUseCase(whisperProcessor, voskProcessor)
        translator = LocalTranslator(this)
        
        nllbTranslator = com.viitor.translate.NLLBTranslator(this)
        serviceScope.launch(Dispatchers.IO) { nllbTranslator.loadModel() }
        
        speculativeDecoder = com.viitor.stt.SpeculativeDecoder(whisperProcessor, whisperProcessor)
        
        thermalMonitor = com.viitor.audio.ThermalMonitor(this)
        thermalMonitor.start()

        audioFocusManager = com.viitor.audio.AudioFocusManager(this)
        audioFocusManager.onFocusLost  = { isCaptureActive = false }
        audioFocusManager.onFocusGained = { isCaptureActive = true }
        // audioFocusManager.requestFocus() // ⚡ [Duck 방지] 브라우저 소리를 줄이거나 멈추게 하지 않음
        isCaptureActive = true

        serviceWatchdog = com.viitor.audio.ServiceWatchdog(this)
        serviceWatchdog.register()

        audioRouteReceiver = com.viitor.audio.AudioRouteChangeReceiver(this)
        audioRouteReceiver.onRouteChanged = { event ->
            adaptiveVAD.reset()
        }
        audioRouteReceiver.register()

        stabilityManager.onMemoryModerate = { prefixTranslationCache.clear() }
        stabilityManager.onMemoryLow = {
            prefixTranslationCache.clear()
            contextCacheManager.clear()
        }
        stabilityManager.onMemoryCritical = {
            prefixTranslationCache.clear()
            contextCacheManager.clear()
        }
        registerComponentCallbacks(stabilityManager)
        
        glossaryManager.load(this)
        sideController = com.viitor.ui.SideControllerManager(this)
        renderer = SubtitleRenderer(overlayManager)
        
        createNotificationChannel()
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
        if (intent?.action == "ACTION_STOP") {
            try { sideController.hide() } catch(e: Exception) {}
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        
        if (intent?.action == "ACTION_START_RECOGNITION") {
            if (pendingResultCode != 0 && pendingResultData != null && !isRecognitionJobActive) {
                startRecognitionWorkflow()
            }
            return START_STICKY
        }

        val resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("RESULT_DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("RESULT_DATA") as? Intent
        }

        if (resultCode != 0 && resultData != null) {
            this.pendingResultCode = resultCode
            this.pendingResultData = resultData
            isRecognitionJobActive = false
            
            createNotificationChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1001, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1001, createNotification())
            }
            sideController.show()

            // ⚡ [Connectivity Fix] 캡처 권한을 받자마자 내부 워크플로우를 즉시 트리거함. 
            // 별도의 버튼 없이도 시스템 캡처 버튼 누르는 즉시 루프가 시작되도록 연결.
            startRecognitionWorkflow()
        }
        
        return START_STICKY
    }

    private var workflowScope: CoroutineScope? = null

    private fun startRecognitionWorkflow() {
        if (isRecognitionJobActive) return

        workflowScope?.cancel()
        workflowScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        isRecognitionJobActive = true

        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "AndTranslate::WL").apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L)
        }

        workflowScope?.launch {
            withContext(Dispatchers.Main) {
                renderer.showProvisional(ProvisionalTranscript("🎙️ STT 모델 로드 중...", confidence = 0f))
            }

            val activeEngine = loadModelUseCase.execute("ja") { status ->
                serviceScope.launch(Dispatchers.Main) {
                    renderer.showProvisional(ProvisionalTranscript("🎙️ $status", confidence = 0f))
                }
            }
            if (activeEngine == "NONE") {
                withContext(Dispatchers.Main) {
                    renderer.showProvisional(ProvisionalTranscript("STT 엔진 로드 실패", confidence = 0f))
                }
                isRecognitionJobActive = false
                return@launch
            }

            withContext(Dispatchers.Main) {
                renderer.showProvisional(ProvisionalTranscript("STT 준비 완료. 소리 대기 중...", confidence = 1f))
            }

            // ⚡ [SMART SUBTITLE AGENT IGNITION] - 전문가급 파라미터 기반 융합 요원 가동
            val agent = com.viitor.pipeline.SmartSubtitleAgent(
                context = this@SubtitleCaptureService,
                transcribeUseCase = provisionalTranscribeUseCase,
                localTranslator = translator,
                fastNetworkTranslate = { text -> fastNetworkTranslate(text) },
                renderer = renderer,
                scroller = subtitleScroller,
                sttMutex = sttMutex
            )

            agent.ignite(
                com.viitor.pipeline.SubtitleAgentConfig(
                    activeEngine = activeEngine,
                    sourceLang = activeLanguage,
                    targetLang = "ko",
                    isTranslationEnabled = isTranslationEnabled
                )
            )

            subtitleAgent = agent

            // 3초 후에도 오디오 없으면 안내 메시지 갱신
            serviceScope.launch(Dispatchers.Main) {
                delay(3000)
                if (isFirstAudio) {
                    renderer.showProvisional(ProvisionalTranscript("🎙️ 방송 소리 대기 중... (영상 볼륨 확인)", confidence = 0.4f))
                }
            }

            // WakeLock 유지 루프 (단순 연장 전용, 재시작 없음)
            workflowScope?.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(25 * 60 * 1000L) // 25분마다
                    try { wakeLock?.acquire(30 * 60 * 1000L) } catch (e: Exception) {}
                }
            }

            // 오디오 캡처 시작
            capturer.startCapture(
                this@SubtitleCaptureService,
                pendingResultCode,
                pendingResultData!!,
                onChunk = { chunk ->
                    com.viitor.stt.util.SubtitleForensicAgent.audioFramesAtService.incrementAndGet()
                    if (!isCaptureActive) {
                        if (System.currentTimeMillis() % 1000 < 50) { // Throttled log
                            android.util.Log.w("CaptureService", "⚠️ [Focus-Drop] isCaptureActive is FALSE. Dropping audio.")
                        }
                        return@startCapture
                    }
                    lastAudioTime = System.currentTimeMillis()

                    if (isFirstAudio) {
                        isFirstAudio = false
                        serviceScope.launch(Dispatchers.Main) {
                            renderer.showProvisional(ProvisionalTranscript("🎙️ 캡처 활성화됨. 소리 감지 중...", confidence = 0.8f))
                        }
                    }

                    // ⚡ [Silence-Fix] AudioChunk PCM16 RMS 정밀 계산 후 VAD 주입
                    val data = chunk.data
                    val sampleCount = data.size / 2
                    var sumSq = 0.0
                    for (i in 0 until sampleCount) {
                        val s = ((data[i*2+1].toInt() shl 8) or (data[i*2].toInt() and 0xFF)).toShort().toFloat()
                        sumSq += s.toDouble() * s
                    }
                    val rms = kotlin.math.sqrt(sumSq / sampleCount).toFloat()
                    val rmsDb = 20 * kotlin.math.log10(kotlin.math.max(1e-10, (rms / 32768.0f).toDouble()))

                    // ⚡ [FLOW-CHECK] Step 0. Capture -> Service (RMS=${"%.1f".format(rmsDb)})
                    if (com.viitor.stt.util.SubtitleForensicAgent.audioFramesAtService.get() % 50 == 0) {
                        android.util.Log.d("CaptureService", "🎙️ [FLOW-CHECK] 0. Capture Out (count=${com.viitor.stt.util.SubtitleForensicAgent.audioFramesAtService.get()})")
                    }

                    // 스마트 에이전트 인터페이스로 주입 (내부에서 엔진 및 상태 관리)
                    buffer.writeChunk(chunk)
                    subtitleAgent?.acceptAudio(chunk)

                    // 침묵 감지
                    val silenceEvent = silenceDetector.onChunk(chunk, rmsDb.toFloat())
                    if (silenceEvent?.isEndOfSentence == true) {
                        subtitleAgent?.onSilenceDetected()
                    }
                },
                onError = { error ->
                    android.util.Log.e("Capture", "Audio session error: $error")
                    serviceScope.launch(Dispatchers.Main) {
                        renderer.showProvisional(ProvisionalTranscript("⚠️ 캡처 오류: $error", confidence = 0f))
                    }
                }
            )
        }
    }


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        subtitleAgent?.halt()
        subtitleAgent = null
        workflowScope?.cancel()
        workflowScope = null
        super.onDestroy()
        serviceScope.cancel()
        
        try { unregisterReceiver(userPingReceiver) } catch (e: Exception) {}
        try { wakeLock?.release() } catch (e: Exception) {}
        wakeLock = null

        try { capturer.close() } catch(e: Exception) {}
        isRecognitionJobActive = false
        try { overlayManager.hideAll() } catch(e: Exception) {}
        try { sideController.hide() } catch(e: Exception) {}
        try { audioFocusManager.abandonFocus() } catch(e: Exception) {}
        try { serviceWatchdog.unregister() } catch(e: Exception) {}
        try { audioRouteReceiver.unregister() } catch(e: Exception) {}
        try { unregisterComponentCallbacks(stabilityManager) } catch(e: Exception) {}
        try { whisperProcessor.close() } catch(e: Exception) {}
        try { voskProcessor.close() } catch(e: Exception) {}
        try { translator.close() } catch(e: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("subtitle_service", "Subtitle Service", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "subtitle_service")
            .setContentTitle("AndTranslate")
            .setContentText("Syncing sequence...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    private suspend fun processFinalText(text: String, batch: List<com.viitor.audio.domain.AudioChunk>, tag: String, activeEngine: String) {
        val cleanText = text.replace(Regex("\\s+"), " ").trim()
        // ⚡ [Semantic Filter] 2글자 이하의 무의미한 파편(조사 등)은 렌더링 스킵하여 UI 노이즈 제거
        if (cleanText.length < 2 && !cleanText.matches(Regex("[가-힣ぁ-ヿ]"))) return
        
        val combinedText = if (contextBuffer.isNotBlank()) "$contextBuffer $cleanText" else cleanText
        
        android.util.Log.d("Pipeline", "🎬 [Sequence Assembly] $tag: $combinedText")
        
        val terminalPunctuations = Regex(".*[.!?。！？\\n]$")
        val strongEndingParticles = Regex(".*(ます|입니다|합니다|습니다|해요|세요|라|다|요|죠|군|네|여|です|ますね|ぺこ|なのだ|のです|んだ|이다)$")
        val continuingParticles = Regex(".*(て|で|と|가|けど|from|ni|고|며|나|니|서|면|하곤|하며)$")
        
        var shouldFlush = false
        var isContinuing = false

        if (combinedText.matches(terminalPunctuations) || combinedText.matches(strongEndingParticles)) {
            shouldFlush = true
        } else if (combinedText.matches(continuingParticles)) {
            isContinuing = true
            // ⚡ [Reactive Cut] Faster turn-over for long continuations
            if (combinedText.length > 20) shouldFlush = true 
        } else if (combinedText.length > 30) {
            shouldFlush = true
        }
        if (combinedText.length > 45) shouldFlush = true

        if (shouldFlush) {
            val prevContext = contextBuffer
            contextBuffer = "" 
            if (isContinuing) {
               // ⚡ [Enterprise Context] 페이백 길이를 10자로 확장하여 JP의 '데스네~' 등 어미 생존율 향상
               val paybackLength = kotlin.math.min(combinedText.length, 10)
               contextBuffer = combinedText.substring(combinedText.length - paybackLength)
            }
            
            if (isTranslationEnabled) {
                // ⚡ [Prefix-Nitro] Check cache for instant hit
                val cached = prefixTranslationCache.getExact(combinedText)
                val optimized = nlpPreprocessor.optimizeForTranslation(combinedText)
                
                val translated = if (cached != null) cached else {
                    try {
                        kotlinx.coroutines.coroutineScope {
                            val localDef = async(Dispatchers.Default) { kotlin.runCatching { translator.translate(optimized, "ja", "ko") }.getOrDefault("") }
                            val netDef = async(Dispatchers.IO) { kotlin.runCatching { fastNetworkTranslate(optimized) }.getOrNull() }
                            val result = kotlinx.coroutines.withTimeoutOrNull(450) { netDef.await() } ?: localDef.await()
                            prefixTranslationCache.put(combinedText, result) // Aggressive caching
                            result
                        }
                    } catch (e: Exception) {
                        "" // Fallback to empty translation on error
                    }
                }
                subtitleScroller.commitFinalLine(combinedText, translated)
                withContext(Dispatchers.Main) { renderer.replaceWithFinal(subtitleScroller.buildScrollingTranscript("", "")) }
            } else {
                subtitleScroller.commitFinalLine(combinedText, "")
                withContext(Dispatchers.Main) { renderer.replaceWithFinal(subtitleScroller.buildScrollingTranscript("", "")) }
            }

            if (batch.isNotEmpty() && activeEngine == "VOSK") {
                serviceScope.launch(Dispatchers.IO) {
                    val whisperTranscript = sttMutex.withLock { finalTranscribeUseCase.execute(batch, "WHISPER", prompt = prevContext, language = activeLanguage) }
                    if (whisperTranscript.text.isNotBlank() && whisperTranscript.text.length > cleanText.length * 0.7) {
                        val optimized = nlpPreprocessor.optimizeForTranslation(whisperTranscript.text)
                        val whisperTranslatedText = fastNetworkTranslate(optimized) ?: translator.translate(optimized, "ja", "ko")
                        withContext(Dispatchers.Main) { renderer.replaceWithFinal(BatchTranscript(whisperTranscript.text, chunkCount = 1, translatedText = whisperTranslatedText)) }
                    }
                }
            }
        } else {
            val provisionalToRender = if (isTranslationEnabled && latestProvisionalText.isNotBlank()) {
                subtitleScroller.buildScrollingProvisional(combinedText, "") 
            } else {
                ProvisionalTranscript(combinedText, confidence = 1.0f)
            }
            withContext(Dispatchers.Main) { renderer.showProvisional(provisionalToRender) }
            contextBuffer = combinedText 
        }
    }

    private suspend fun fastNetworkTranslate(text: String): String? = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.isBlank()) return@withContext null
        
        // ⚡ [L2 Cache Hit]
        val cached = prefixTranslationCache.getExact(clean)
        if (cached != null) return@withContext cached

        val maxRetries = 2
        for (attempt in 0 until maxRetries) {
            var connection: java.net.HttpURLConnection? = null
            try {
                // ⚡ [Nitro-Url] URL 인코딩 최적화 및 비공식 gtx 엔드포인트 안정성 확보
                val encodedText = java.net.URLEncoder.encode(clean, "UTF-8")
                val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=ja&tl=ko&dt=t&q=$encodedText"
                connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = 1200 // OS 지연 고려 상향
                    readTimeout = 1200
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                
                if (connection.responseCode == 200) {
                    val raw = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONArray(raw)
                    val segments = json.getJSONArray(0)
                    val sb = StringBuilder()
                    for (i in 0 until segments.length()) {
                        sb.append(segments.getJSONArray(i).getString(0))
                    }
                    val result = sb.toString()
                    prefixTranslationCache.put(clean, result)
                    return@withContext result
                }
            } catch (e: Exception) {
                if (attempt < maxRetries - 1) delay(150L)
            } finally {
                connection?.disconnect()
            }
        }
        return@withContext null
    }
}
