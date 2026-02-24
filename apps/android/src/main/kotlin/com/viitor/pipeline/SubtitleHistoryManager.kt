package com.viitor.pipeline

import android.content.Context
import android.util.Log
import com.viitor.db.AndTranslateDatabase
import com.viitor.db.SubtitleHistory
import kotlinx.coroutines.*
import java.util.*

/**
 * ★ SubtitleHistoryManager
 * 자막 이력을 체계적으로 관리하고 대용량 데이터를 안전하게 보관/삭제함.
 */
class SubtitleHistoryManager(context: Context) {
    private val db = AndTranslateDatabase.getDatabase(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionId = UUID.randomUUID().toString()

    companion object {
        private const val TAG = "HistoryMgr"
        private const val RETENTION_DAYS = 7L // 7일 지나면 자동 파기
    }

    /**
     * 자막 한 줄을 DB에 영구 저장
     */
    fun save(original: String, translated: String?, engine: String, confidence: Float) {
        if (original.isBlank()) return
        
        scope.launch {
            try {
                val entry = SubtitleHistory(
                    timestamp = System.currentTimeMillis(),
                    originalText = original,
                    translatedText = translated,
                    languageFrom = "ja",
                    languageTo = "ko",
                    confidence = confidence,
                    engineType = engine,
                    sessionId = sessionId
                )
                db.historyDao().insert(entry)
                
                // 주기적으로 오래된 데이터 정리 (1% 확률로 실행하여 오버헤드 최소화)
                if (System.currentTimeMillis() % 100 == 0L) {
                    cleanupOldRecords()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save history: ${e.message}")
            }
        }
    }

    /**
     * 특정 키워드로 과거 자막 검색
     */
    suspend fun search(query: String): List<SubtitleHistory> = withContext(Dispatchers.IO) {
        db.historyDao().searchHistory(query)
    }

    private suspend fun cleanupOldRecords() {
        val threshold = System.currentTimeMillis() - (RETENTION_DAYS * 24 * 60 * 60 * 1000L)
        db.historyDao().deleteOldHistory(threshold)
        Log.d(TAG, "Cleanup: Old records before ${Date(threshold)} removed.")
    }

    fun clearAll() {
        scope.launch { db.historyDao().deleteAll() }
    }
}
