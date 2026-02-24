package com.viitor.pipeline

import android.content.Context
import android.util.Log
import com.viitor.audio.domain.AudioChunk
import com.viitor.stt.usecase.ProvisionalTranscribeUseCase
import com.viitor.translate.LocalTranslator
import com.viitor.stt.domain.ProvisionalTranscript
import com.viitor.stt.domain.BatchTranscript
import com.viitor.subtitle.ISubtitleScroller
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ★ PipelineNexus: 컴포넌트 간 하드코딩 체결을 해제하고
 * 인터페이스 기반 계약(Contract)을 통해 시스템을 하나로 통합하는 전문가급 브리지.
 * 
 * 🔧 최적화: 유즈케이스(UseCase)를 직접 주입받아 전처리 파이프라인(DC Offset, Pre-emphasis 등)을 완벽히 활용함.
 */
class PipelineNexus(
    private val context: Context,
    private val transcribeUseCase: ProvisionalTranscribeUseCase,
    private val localTranslator: LocalTranslator,
    private val fastNetworkTranslate: suspend (String) -> String?,
    private val sttMutex: Mutex,
    private val renderer: com.viitor.subtitle.SubtitleRenderer,
    private val scroller: ISubtitleScroller
) {
    companion object {
        private const val TAG = "PipelineNexus"
    }

    /**
     * ⚡ [Expert Chain] 런타임 의존성을 주입하고 언어 파라미터를 동적으로 바인딩하여
     * 엔진 인스턴스를 생성함. 리플렉션 없이 100% Type-Safe한 동작 보장.
     */
    fun createEngine(
        activeEngine: String,
        sourceLang: String = "ja",
        targetLang: String = "ko",
        isTranslationEnabled: Boolean = true
    ): SubtitlePipelineEngine {
        
        Log.i(TAG, "Initializing Engine via Nexus: Engine=$activeEngine, $sourceLang -> $targetLang")

        val engine = SubtitlePipelineEngine(
            context = context,
            onShowUI = { text, translated ->
                // 인터페이스를 통한 타입 안전한 UI 업데이트
                Log.d(TAG, "[FLOW-CHECK] 5. UI Signal -> Nexus (text='${text.take(20)}')")
                val provisional = scroller.buildScrollingProvisional(text, translated ?: "")
                
                // ⚡ [FLOW-CHECK] Step 6. Scroller -> Overlay (Lines=${provisional.text.count { it == '\n' } + 1})
                Log.v(TAG, "[FLOW-CHECK] 6. Final UI Object (len=${provisional.text.length})")
                
                renderer.showProvisional(provisional)
            },
            onFinalUI = { text, translated ->
                // 히스토리 확정 및 멀티라인 렌더링 동기화
                scroller.commitFinalLine(text, translated ?: "")
                val batch = scroller.buildScrollingTranscript("", "")
                renderer.replaceWithFinal(batch)
            },
            onStatus = { message ->
                renderer.showProvisional(ProvisionalTranscript(message, confidence = 0.3f))
            },
            onError = { error ->
                Log.e(TAG, "Critical Pipeline Error: $error")
            }
        ).apply {
            setLanguages(sourceLang, targetLang)
        }

        // ─── STT Provider 결합 (UseCase 기반) ───────────────────────────────────
        engine.sttProvider = object : SubtitlePipelineEngine.SttProvider {
            override suspend fun process(audioData: ByteArray, prompt: String, language: String): SubtitlePipelineEngine.SttResult {
                return try {
                    // STT 엔진 동시 접근 제한 (Mutex)
                    val result = sttMutex.withLock {
                        // ByteArray를 AudioChunk로 래핑하여 UseCase의 전처리 파이프라인(Enhancer) 통과
                        val chunk = AudioChunk(audioData, durationMs = 1500L) // 엔진 누적 주기에 맞춤
                        transcribeUseCase.execute(
                            chunk = chunk,
                            engineType = activeEngine,
                            prompt = prompt,
                            language = language
                        )
                    }
                    
                    SubtitlePipelineEngine.SttResult(
                        text = result.text,
                        isFinal = result.isFinalChunk,
                        confidence = result.confidence
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "STT Bridge Failure: ${e.message}")
                    SubtitlePipelineEngine.SttResult("")
                }
            }

            override fun reset() { 
                try { transcribeUseCase.forceReset() } catch (e: Exception) {}
                // ⚡ [Logic Fix] reset() 시 scroller.clear()를 호출하면 스크롤 히스토리(이전 자막)가 증발함.
                // 대신 엔진과 유즈케이스 상태만 리셋하도록 분리.
            }

            override fun isReady(): Boolean = transcribeUseCase.isReady(activeEngine)
        }

        // ─── Translation Provider 결합 ────────────────────────────────────
        engine.translateProvider = object : SubtitlePipelineEngine.TranslateProvider {
            override suspend fun translate(text: String, from: String, to: String): String? {
                if (!isTranslationEnabled) return null
                return try {
                    // 고속 네트워크 번역 우선 수행 후 로컬 NLLB로 폴백
                    fastNetworkTranslate(text) ?: localTranslator.translate(text, from, to)
                } catch (e: Exception) {
                    Log.w(TAG, "Translation Bridge Timeout/Error: ${e.message}")
                    null
                }
            }
        }

        return engine
    }
}
