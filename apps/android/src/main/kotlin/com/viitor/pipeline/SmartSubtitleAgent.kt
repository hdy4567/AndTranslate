package com.viitor.pipeline

import android.content.Context
import android.util.Log
import com.viitor.audio.domain.AudioChunk
import com.viitor.stt.usecase.ProvisionalTranscribeUseCase
import com.viitor.translate.LocalTranslator
import com.viitor.subtitle.SubtitleRenderer
import com.viitor.subtitle.ISubtitleScroller
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ★ SubtitleAgentConfig: AI 요원 설정을 위한 정밀 파라미터 셋트
 */
data class SubtitleAgentConfig(
    val activeEngine: String,
    val sourceLang: String = "ja",
    val targetLang: String = "ko",
    val isTranslationEnabled: Boolean = true,
    val transcriptionMode: com.viitor.stt.domain.TranscriptionMode = com.viitor.stt.domain.TranscriptionMode.PROVISIONAL
)

/**
 * ★ SmartSubtitleAgent (AI-Core Orchestrator)
 * 
 * "끈적한 접착(Adhesive Bonding)": 모든 파이프라인 컴포넌트를 단일 객체 내부에 융합하여 
 * 설정 누락이나 의존성 단절을 원천 방지함. 서비스는 오직 이 에이전트와만 통신함.
 */
class SmartSubtitleAgent(
    private val context: Context,
    private val transcribeUseCase: ProvisionalTranscribeUseCase,
    private val localTranslator: LocalTranslator,
    private val fastNetworkTranslate: suspend (String) -> String?,
    private val renderer: SubtitleRenderer,
    private val scroller: ISubtitleScroller,
    private val sttMutex: Mutex
) {
    private var engine: SubtitlePipelineEngine? = null
    private val isRunning = AtomicBoolean(false)
    private val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "SmartSubtitleAgent"
    }

    /**
     * ⚡ [Fusion Execution] 파라미터를 받아 엔진을 생성하고 즉시 가동함.
     * 모든 로직이 이 함수 하나로 "체결"됨.
     */
    fun ignite(config: SubtitleAgentConfig) {
        Log.i(TAG, "🚀 Agent Igniting with configuration: $config")
        
        // 기존 엔진 중지
        halt()

        // 끈적한 결합: Nexus를 통해 모든 컴포넌트를 하나의 동작으로 묶음
        val nexus = PipelineNexus(
            context = context,
            transcribeUseCase = transcribeUseCase,
            localTranslator = localTranslator,
            fastNetworkTranslate = fastNetworkTranslate,
            sttMutex = sttMutex,
            renderer = renderer,
            scroller = scroller
        )

        val newEngine = nexus.createEngine(
            activeEngine = config.activeEngine,
            sourceLang = config.sourceLang,
            targetLang = config.targetLang,
            isTranslationEnabled = config.isTranslationEnabled
        )

        this.engine = newEngine
        newEngine.start()
        isRunning.set(true)
        Log.d(TAG, "✅ Agent is now ACTIVE and monitoring audio.")
    }

    /**
     * 오디오 센서 데이터 주입
     */
    fun acceptAudio(chunk: AudioChunk) {
        if (!isRunning.get()) return
        engine?.pushAudio(chunk)
    }

    /**
     * 침묵 센서 이벤트 처리
     */
    fun onSilenceDetected() {
        if (!isRunning.get()) return
        engine?.onSilenceDetected()
    }

    /**
     * 에이전트 중지 및 자원 해제
     */
    fun halt() {
        if (isRunning.getAndSet(false)) {
            Log.i(TAG, "🛑 Agent Halting...")
            engine?.stop()
            engine = null
        }
    }

    /**
     * 에이전트 상태 확인
     */
    fun isActive(): Boolean = isRunning.get() && engine?.state == SubtitlePipelineEngine.State.RUNNING
}
