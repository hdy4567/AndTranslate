package com.viitor.subtitle

import com.viitor.stt.domain.BatchTranscript
import com.viitor.stt.domain.ProvisionalTranscript
import com.viitor.subtitle.domain.SubtitleSegment

/**
 * ★ SubtitleRenderer: 이중 렌더링 지원
 */
class SubtitleRenderer(
    private val overlay: ISubtitleOverlay
) {
    @Synchronized
    fun showProvisional(transcript: ProvisionalTranscript) {
        overlay.showProvisional(
            SubtitleSegment(
                originalText = transcript.text,
                translatedText = transcript.translatedText,
                isProvisional = true,
                confidence = transcript.confidence
            )
        )
    }
    
    @Synchronized
    fun replaceWithFinal(transcript: BatchTranscript) {
        overlay.replaceWithFinal(
            SubtitleSegment(
                originalText = transcript.text,
                translatedText = transcript.translatedText,
                isProvisional = false,
                confidence = 1.0f
            )
        )
    }

}
