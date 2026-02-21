package com.viitor.audio

import com.viitor.audio.domain.AudioChunk
import com.viitor.audio.domain.SilenceEvent

/**
 * ★ SilenceDetector: 침묵 이벤트 발화
 */
class SilenceDetector(
    val silenceThresholdMs: Long = 500,   // @SilenceThreshold(min=500ms)
    val thresholdDb: Float = -40f
) {
    private var consecutiveSilenceMs = 0L
    
    // ERR_INVALID_SILENCE: @SilenceThreshold(min=500ms, rms=-40dB)

    fun onChunk(chunk: AudioChunk, rms: Float): SilenceEvent? {
        if (rms < thresholdDb) {
            consecutiveSilenceMs += chunk.durationMs
            if (consecutiveSilenceMs >= silenceThresholdMs) {
                consecutiveSilenceMs = 0L
                return SilenceEvent(isEndOfSentence = true)
            }
        } else {
            consecutiveSilenceMs = 0L
        }
        return null
    }
}
