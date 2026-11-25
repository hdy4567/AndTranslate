package com.viitor.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VocabularyDao {
    @Query("SELECT * FROM vocabulary_vault ORDER BY timestamp DESC")
    fun getAllWords(): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM vocabulary_vault ORDER BY nextReviewAt ASC, wrongCount DESC")
    fun getWordsForReview(): Flow<List<VocabularyEntity>>

    @Query("UPDATE vocabulary_vault SET nextReviewAt = :nextReviewAt, wrongCount = :wrongCount, reviewIntervalDays = :intervalDays WHERE id = :id")
    suspend fun updateReviewProgress(id: Int, nextReviewAt: Long, wrongCount: Int, intervalDays: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWord(entity: VocabularyEntity)

    @Query("DELETE FROM vocabulary_vault WHERE id = :id")
    suspend fun deleteWord(id: Int)
}
