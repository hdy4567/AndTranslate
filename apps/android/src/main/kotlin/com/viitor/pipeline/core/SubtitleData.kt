package com.viitor.pipeline.core

/**
 * [SubtitleData]
 * - 1탭(YouTube)에서 탈취하여 전체 파이프라인으로 흘려보내는 자막 데이터 유닛.
 * - 2탭(Script)과 3탭(Words)에서도 공용으로 사용함.
 */
data class SubtitleData(
    val videoId: String = "UNKNOWN",
    val anchorTimeMs: Long = 0L,
    val original: String = "",
    val translated: String? = null,
    val romaji: String? = null,
    val pronunciation: String? = null
)
