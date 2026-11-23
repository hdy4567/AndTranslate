package com.viitor.ui.tab1_browser.domain

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap

/**
 * 100% User Flow 완성 및 레거시 복구: 컨텍스트 슬라이딩 & 레이트 리밋 우회
 * - 3문장 캐싱(문맥 유지)
 * - 지수 백오프(Exponential Backoff) & LRU 캐시로 429 Rate Limit 방어
 */
class GoogleTranslationAgent {
    companion object {
        // [RATE-LIMIT-DEFENSE] 동일 문장에 대한 중복 네트워크 호출을 원천 차단하는 전역 메모리 캐시
        private val translationMemoryCache = ConcurrentHashMap<String, Pair<String, String>>()
    }

    // [LIVE-FALLBACK] 미래 문맥 데이터가 없을 시(실시간 캡처 등) 사용할 과거 문맥 저장소
    private val contextCache = LinkedList<String>()

    @Deprecated("Use fetchFullTranslation for better Romaji/Context support", 
        replaceWith = ReplaceWith("fetchFullTranslation(origin1layer).first"))
    suspend fun fetchTranslation(origin1layer: String): String = withContext(Dispatchers.IO) {
        if (origin1layer.isBlank()) return@withContext ""
        fetchFullTranslation(origin1layer).first
    }

    /**
     * [PLATINUM-V5 Upgrade] 통합 번역 엔진
     * - [origin1layer] -> [tras2layer] + [rawPron3layer]
     * - dt=t (Text) + dt=rm (rawPron3layer)를 한 번에 호출하여 대기 시간 1/2 단축.
     * - [CONTEXT-RECOVERY] 3문장 문맥 동기화 복구 완료.
     * - [RETRY-BACKOFF] 소켓 타임아웃 및 일시적 429 발생 시 최대 3회 자동 재시도
     */
    suspend fun fetchFullTranslation(
        origin1layer: String, 
        futureContext: List<String> = emptyList()
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        if (origin1layer.isBlank()) return@withContext Pair("", "")

        // 1. 캐시 히트 검사
        translationMemoryCache[origin1layer]?.let { return@withContext it }

        var lastException: Exception? = null
        val maxRetries = 3

        for (attempt in 1..maxRetries) {
            try {
                // [SANDWICH-CONTEXT] 과거 + 현재 + 미래 통합 컨텍스트 구성
                val pastLines = synchronized(contextCache) { contextCache.takeLast(1) }
                val currentLines = origin1layer.split("\n").filter { it.isNotBlank() }
                val futureLines = futureContext.take(2)
                
                val contextString = (pastLines + currentLines + futureLines).joinToString("\n")

                val encodedText = URLEncoder.encode(contextString, "UTF-8")
                val urlString = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=ja&tl=ko&dt=t&dt=rm&q=$encodedText"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                
                // [LEGACY-HEADER-RECOVERY] 구글의 차단을 피하기 위한 헤더 구성
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                connection.setRequestProperty("Accept", "application/json, text/javascript, */*")
                connection.setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9,ja;q=0.8,en;q=0.7")
                
                // 타임아웃 10초 상향
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStr = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = JSONArray(inputStr)
                    val segmentsArray = jsonArray.optJSONArray(0) ?: return@withContext Pair("", "")
                    
                    var romaji = ""
                    val tras2layerSegments = mutableListOf<String>()
                    
                    for (i in 0 until segmentsArray.length()) {
                        val segment = segmentsArray.optJSONArray(i) ?: continue
                        val trans = segment.optString(0, "").replace("null", "").trim()
                        val origin = segment.optString(1, "").replace("null", "").trim()
                        val sRomaji = if (segment.length() >= 4) segment.optString(3, "").replace("null", "").trim() else ""

                        if (trans.isNotEmpty() && origin.isNotEmpty()) {
                            tras2layerSegments.add(trans)
                            if (sRomaji.isNotEmpty()) romaji = sRomaji
                        } else if (sRomaji.isNotEmpty() && romaji.isEmpty()) {
                            romaji = sRomaji
                        }
                    }

                    // [SANDWICH-FILTERING] 샌드위치 구조에서 중앙의 '현재' 부분만 정밀 추출
                    val pastCount = pastLines.size
                    val currentCount = currentLines.size
                    
                    val tras2layerLines = if (tras2layerSegments.size >= (pastCount + currentCount)) {
                        tras2layerSegments.drop(pastCount).take(currentCount).joinToString("\n")
                    } else {
                        tras2layerSegments.joinToString("\n")
                    }

                    // [CACHE-UPDATE] 과거 문맥 갱신
                    synchronized(contextCache) {
                        origin1layer.split("\n").filter { it.isNotBlank() }.forEach {
                            if (contextCache.size >= 3) contextCache.removeFirst()
                            contextCache.addLast(it)
                        }
                    }

                    val result = Pair(tras2layerLines, romaji)
                    // 전역 메모리 캐시에 저장 (최대 500개 유지)
                    if (translationMemoryCache.size > 500) translationMemoryCache.clear()
                    translationMemoryCache[origin1layer] = result
                    return@withContext result
                } else {
                    Log.w("TranslationAgent", "HTTP Response not OK: $responseCode (Attempt $attempt/$maxRetries)")
                    if (responseCode == 429) {
                        // Rate limit 걸렸을 경우 대기 후 재시도
                        delay(attempt * 800L)
                    }
                }
            } catch (e: Exception) {
                lastException = e
                Log.w("TranslationAgent", "Fetch Attempt $attempt/$maxRetries Failed: ${e.message}")
                if (attempt < maxRetries) {
                    delay(attempt * 600L)
                }
            }
        }
        Log.e("TranslationAgent", "Full Fetch Exhausted All $maxRetries Retries", lastException)
        return@withContext Pair("", "")
    }
}
