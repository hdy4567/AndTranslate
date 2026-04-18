package com.viitor.pipeline.core

import android.util.Log

/**
 * [SubtitleData]
 * - 1탭(YouTube)에서 탈취하여 전체 파이프라인으로 흘려보내는 자막 데이터 유닛.
 * - [MEMORY-OPTIMIZED]: 인스턴스 필드를 최소화하여 대용량 자막 처리 시 안정성 확보.
 */
data class SubtitleData(
    val videoId: String = "UNKNOWN",
    val anchorTimeMs: Long = 0L,
    val origin1layer: String = "",
    var tras2layer: String? = null,
    var pron3layer: String? = null
) {
    /** [EMPTY-CHECK] 해당 데이터가 비어있는 유효하지 않은 데이터인지 확인 */
    fun isEmpty() = origin1layer.isBlank() && anchorTimeMs == 0L

    companion object {
        private const val TAG = "SubtitleData"

        /** 
         * [EMPTY-OBJECT-PATTERN] 
         * - 데이터 부재 시 null 대신 반환되는 규격화된 빈 객체.
         * - 호출 시 로그를 남겨 데이터 유실 지점을 직관적으로 식별하게 함 (Crash 대신 Signal 선택).
         */
        fun empty(): SubtitleData {
            Log.w(TAG, "[DATA-VACUUM] Empty SubtitleData generated. Check matching logic if this is unexpected.")
            return SubtitleData(
                videoId = "UNKNOWN",
                anchorTimeMs = 0L,
                origin1layer = "",
                tras2layer = "",
                pron3layer = ""
            )
        }
    }
}
