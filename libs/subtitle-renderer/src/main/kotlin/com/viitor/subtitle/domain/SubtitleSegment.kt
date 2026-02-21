package com.viitor.subtitle.domain

/**
 * ★ SubtitleSegment: 이중 자막 지원
 */
data class SubtitleSegment(
    val originalText: String,
    val translatedText: String? = null,
    val isProvisional: Boolean,        // true: 회색/이탤릭 View, false: 명확한 배경
    val confidence: Float = 1.0f
)

