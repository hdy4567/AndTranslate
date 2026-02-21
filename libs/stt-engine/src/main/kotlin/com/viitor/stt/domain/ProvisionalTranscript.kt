package com.viitor.stt.domain

/**
 * ERR_CONFIDENCE_OUT_OF_RANGE: @FloatRange(from=0.0, to=1.0)
 */
data class ProvisionalTranscript(
    val text: String,
    val translatedText: String? = null,
    val confidence: Float,
    val isFinalChunk: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

