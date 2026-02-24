package com.viitor.db

import androidx.room.*

/**
 * ★ Glossary
 * 대용량 도메인 사전 엔터티. FTS4를 연동하여 초고속 검색 제공.
 */
@Entity(tableName = "glossary")
data class Glossary(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "source_text") val sourceText: String,
    @ColumnInfo(name = "target_text") val targetText: String,
    @ColumnInfo(name = "domain") val domain: String = "general",
    @ColumnInfo(name = "last_used") val lastUsed: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_active") val isActive: Boolean = true
)

/**
 * Glossary FTS 테이블 (검색 최적화)
 */
@Fts4(contentEntity = Glossary::class)
@Entity(tableName = "glossary_fts")
data class GlossaryFts(
    val source_text: String,
    val target_text: String
)

@Dao
interface GlossaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: Glossary)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<Glossary>)

    @Update
    suspend fun update(entry: Glossary)

    @Delete
    suspend fun delete(entry: Glossary)

    @Query("SELECT * FROM glossary WHERE is_active = 1")
    suspend fun getAllActive(): List<Glossary>

    /**
     * FTS를 사용한 초정밀 매칭 및 검색
     */
    @Query("""
        SELECT * FROM glossary 
        JOIN glossary_fts ON glossary.id = glossary_fts.rowid 
        WHERE glossary_fts MATCH :query
    """)
    suspend fun search(query: String): List<Glossary>
    
    @Query("SELECT * FROM glossary WHERE source_text = :source LIMIT 1")
    suspend fun getExactMatch(source: String): Glossary?

    @Query("SELECT COUNT(*) FROM glossary")
    suspend fun getCount(): Int
}
