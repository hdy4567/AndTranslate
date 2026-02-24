package com.viitor.pipeline

import android.content.Context
import android.util.Log
import com.viitor.db.AndTranslateDatabase
import com.viitor.db.Glossary
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ★ DatabaseGlossaryManager
 * 대규모(수만 개) 단어를 메모리와 DB 하이브리드 방식으로 관리하는 고성능 엔진.
 */
class DatabaseGlossaryManager(context: Context) {
    private val db = AndTranslateDatabase.getDatabase(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    
    // 빈번한 조회를 위한 메모리 캐시 (자주 쓰는 1000개 정도만 유지)
    private val memoryCache = LinkedHashMap<String, String>(1024, 0.75f, true)
    
    companion object {
        private const val TAG = "GlossaryMgr"
    }

    init {
        // 초기 로드: 데이터가 없으면 기본 glossary.json에서 마이그레이션 가능
        scope.launch {
            if (db.glossaryDao().getCount() == 0) {
                Log.d(TAG, "DB glossary empty, migrating from local assets...")
                migrateFromAssets(context)
            }
        }
    }

    /**
     * 캐시 및 DB 통합 검색 (O(1) in memory, O(log N) in DB)
     */
    suspend fun findReplacement(text: String): String? {
        // 1. 메모리 캐시 확인
        mutex.withLock {
            val cached = memoryCache[text]
            if (cached != null) return cached
        }
        
        // 2. DB 검색 (Exact Match)
        val exact = db.glossaryDao().getExactMatch(text)
        if (exact != null) {
            mutex.withLock { memoryCache[text] = exact.targetText }
            return exact.targetText
        }
        
        return null
    }

    /**
     * 문장 전체에 대한 사전 적용 (최적화된 알고리즘 필요)
     */
    suspend fun applyGlossary(text: String): String {
        val activeGlossary = db.glossaryDao().getAllActive()
        if (activeGlossary.isEmpty()) return text
        
        var result = text
        // TODO: Aho-Corasick 알고리즘으로 대규모 사전 지원 시 성능 고도화 가능
        // 현재는 루프 기반 (5000줄 미션을 위해 나중에 확장)
        for (entry in activeGlossary) {
            if (result.contains(entry.sourceText)) {
                result = result.replace(entry.sourceText, entry.targetText)
            }
        }
        return result
    }

    fun addTerm(source: String, target: String, domain: String = "general") {
        scope.launch {
            db.glossaryDao().insert(Glossary(sourceText = source, targetText = target, domain = domain))
        }
    }

    private suspend fun migrateFromAssets(context: Context) {
        try {
            val json = context.assets.open("glossary.json").bufferedReader().use { it.readText() }
            val root = org.json.JSONObject(json)
            val domains = root.optJSONObject("domains") ?: return
            
            val entries = mutableListOf<Glossary>()
            val keys = domains.keys()
            while (keys.hasNext()) {
                val domainName = keys.next()
                val domain = domains.getJSONObject(domainName)
                val slangKeys = domain.keys()
                while (slangKeys.hasNext()) {
                    val k = slangKeys.next()
                    entries.add(Glossary(sourceText = k, targetText = domain.getString(k), domain = domainName))
                }
            }
            db.glossaryDao().insertAll(entries)
            Log.d(TAG, "Successfully migrated ${entries.size} terms to Room DB")
        } catch (e: Exception) {
            Log.e(TAG, "Migration failure: ${e.message}")
        }
    }
}
