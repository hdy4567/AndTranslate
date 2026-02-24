package com.viitor.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * ★ SubtitleHistory
 * 자막 인식 및 번역 이력을 저장하는 엔터프라이즈급 엔터티.
 */
@Entity(tableName = "subtitle_history")
data class SubtitleHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "original_text") val originalText: String,
    @ColumnInfo(name = "translated_text") val translatedText: String?,
    @ColumnInfo(name = "language_from") val languageFrom: String,
    @ColumnInfo(name = "language_to") val languageTo: String,
    @ColumnInfo(name = "confidence") val confidence: Float,
    @ColumnInfo(name = "engine_type") val engineType: String,
    @ColumnInfo(name = "session_id") val sessionId: String // 같은 작업 세션끼리 묶기용
)

@Dao
interface SubtitleHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: SubtitleHistory)

    @Query("SELECT * FROM subtitle_history ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentHistory(limit: Int): Flow<List<SubtitleHistory>>

    @Query("SELECT * FROM subtitle_history WHERE original_text LIKE '%' || :query || '%' OR translated_text LIKE '%' || :query || '%'")
    suspend fun searchHistory(query: String): List<SubtitleHistory>

    @Query("DELETE FROM subtitle_history WHERE timestamp < :threshold")
    suspend fun deleteOldHistory(threshold: Long)

    @Query("SELECT * FROM subtitle_history ORDER BY timestamp DESC")
    suspend fun getAllHistorySync(): List<SubtitleHistory>

    @Query("DELETE FROM subtitle_history")
    suspend fun deleteAll()
}
