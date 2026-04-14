package com.viitor.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ★ [VocabularyEntity] (3번 탭 저장소 모델)
 * - word: 일본어 원문
 * - translated: 한국어 번역
 * - contextText: 추출 당시의 문맥(문장)
 * - timestamp: 저장 시점
 */
@Entity(tableName = "vocabulary_vault")
data class VocabularyEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val word: String,
    val translated: String?,
    val contextText: String?,
    val anchorTimeMs: Long = 0L, // [TIME-WARP] 캡처 당시의 비디오 시간(ms)
    val videoId: String? = null, // [PLATINUM-V5] 원본 영상 ID 매핑
    val timestamp: Long = System.currentTimeMillis(),
    val source: String = "Tab4_Lens" // Tab2_Script or Tab4_Lens
)
