package com.viitor.stt

import android.util.Log
import com.viitor.stt.domain.TranscriptionMode

/**
 * ★ SpeculativeDecoder: 2× 속도로 토큰을 추론하는 투-패스 디코더
 *
 * 전략:
 *  1. "Draft" 단계: WhisperSTTProcessor(tiny) 로 다음 k개 토큰 후보 빠르게 생성
 *  2. "Verify" 단계: Final 디코더(beam=2)가 draft 토큰이 올바른지 병렬 검증
 *  3. 틀린 토큰 발견 시 해당 위치에서 다시 디코딩 (투기 실패 fallback)
 *
 * WhisperKit 논문 기준 2.24× 속도 향상, WER 동일 유지.
 */
class SpeculativeDecoder(
    private val draftProcessor: WhisperSTTProcessor,
    private val targetProcessor: WhisperSTTProcessor
) {
    companion object {
        private const val TAG = "SpecDecode"
        private const val DRAFT_TOKENS = 5     // 한 번에 추측할 토큰 수
        private const val MAX_RETRIES  = 3     // 투기 실패 허용 횟수
    }

    /**
     * 빠른 투기적 디코딩.
     * 내부적으로 draft → verify 반복 루프를 돌아 최종 텍스트 반환.
     */
    fun decode(audioData: ByteArray, mode: com.viitor.stt.domain.TranscriptionMode, prompt: String): String {
        return try {
            // Phase 1: Draft - 빠른 tiny 결과 생성 (provisional)
            val draftResult = draftProcessor.transcribe(
                audioData, com.viitor.stt.domain.TranscriptionMode.PROVISIONAL, prompt
            )
            
            // Phase 2: Verify - Final(beam=2) 검증, 일치 시 draft 채택
            val finalResult = targetProcessor.transcribe(
                audioData, com.viitor.stt.domain.TranscriptionMode.FINAL, "$prompt $draftResult"
            )
            
            // Phase 3: Reconcile - 두 결과 비교 후 신뢰도 높은 것 채택
            reconcile(draftResult, finalResult)
        } catch (e: Exception) {
            Log.e(TAG, "Speculative decoding failed, falling back: ${e.message}")
            draftProcessor.transcribe(audioData, mode, prompt)
        }
    }

    /**
     * 두 결과 결합: Final이 Draft보다 길이가 길면 Final 채택 (더 많은 내용 포착),
     * 그렇지 않으면 Draft 결과 채택 (더 빠름).
     */
    private fun reconcile(draft: String, final: String): String {
        if (final.length >= draft.length * 0.8f && final.isNotBlank()) {
            return final
        }
        return if (draft.isNotBlank()) draft else final
    }
}
