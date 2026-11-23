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
import java.util.concurrent.ThreadLocalRandom

/**
 * [GoogleTranslationAgent] - 고도화된 익명화(Anonymization) 및 엔드포인트 로테이션 번역 엔진
 *
 * [주요 익명화 및 차단 우회 기법]
 * 1. Multi-Endpoint 로테이션: translate.googleapis.com, translate.google.com 분산
 * 2. Client 파라미터 로테이션: gtx, dict-chrome-ex 순환 사용
 * 3. IP Spoofing 헤더 무작위화: X-Forwarded-For, Client-IP, X-Real-IP에 임의 IP 주입
 * 4. User-Agent 풀 로테이션: 최신 모바일(Pixel, Galaxy, iPhone) 및 데스크톱 브라우저 지문 무작위 순환
 * 5. 세션 및 쿠키 추적 원천 차단: useCaches=false, 완전 무상태(Stateless) 연결
 * 6. 중복 요청 방어 전역 메모리 캐시 및 지수 백오프(Exponential Backoff)
 */
class GoogleTranslationAgent {
    companion object {
        private const val TAG = "TranslationAgent"

        // [RATE-LIMIT-DEFENSE] 동일 문장에 대한 중복 네트워크 호출을 원천 차단하는 전역 메모리 캐시
        private val translationMemoryCache = ConcurrentHashMap<String, Pair<String, String>>()

        // [ANONYMIZATION-POOLS]
        private val ENDPOINT_POOL = listOf(
            "https://translate.googleapis.com/translate_a/single",
            "https://translate.google.com/translate_a/single"
        )

        private val CLIENT_POOL = listOf("gtx", "dict-chrome-ex")

        private val USER_AGENTS = listOf(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.6312.118 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.119 Mobile Safari/537.36",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4.1 Mobile/15E148 Safari/604.1",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
        )

        private val ACCEPT_LANGUAGES = listOf(
            "ko-KR,ko;q=0.9,ja;q=0.8,en;q=0.7",
            "ja-JP,ja;q=0.9,ko-KR;q=0.8,ko;q=0.7,en;q=0.6",
            "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
            "en-US,en;q=0.9,ja;q=0.8,ko;q=0.7"
        )

        /** 가상 IP 생성 (X-Forwarded-For 주입용 익명화) */
        fun generateRandomIp(): String {
            val r = ThreadLocalRandom.current()
            return "${r.nextInt(20, 220)}.${r.nextInt(1, 254)}.${r.nextInt(1, 254)}.${r.nextInt(1, 254)}"
        }
    }

    // [LIVE-FALLBACK] 과거 문맥 저장소
    private val contextCache = LinkedList<String>()

    @Deprecated("Use fetchFullTranslation for better Romaji/Context support", 
        replaceWith = ReplaceWith("fetchFullTranslation(origin1layer).first"))
    suspend fun fetchTranslation(origin1layer: String): String = withContext(Dispatchers.IO) {
        if (origin1layer.isBlank()) return@withContext ""
        fetchFullTranslation(origin1layer).first
    }

    /**
     * [PLATINUM-V5 Upgrade] 익명화 통합 번역 엔진
     * - [origin1layer] -> [tras2layer] + [rawPron3layer]
     * - dt=t (Text) + dt=rm (Romaji)
     * - 요청마다 엔드포인트/클라이언트/IP/User-Agent 익명화 로테이션
     */
    suspend fun fetchFullTranslation(
        origin1layer: String, 
        futureContext: List<String> = emptyList()
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        if (origin1layer.isBlank()) return@withContext Pair("", "")

        // 1. 메모리 캐시 히트 검사
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

                // [ANONYMIZED-ENDPOINT-SELECTION] 시도 횟수에 따라 엔드포인트 및 클라이언트 분산
                val baseEndpoint = ENDPOINT_POOL[(attempt - 1) % ENDPOINT_POOL.size]
                val clientParam = CLIENT_POOL[(attempt - 1) % CLIENT_POOL.size]
                val urlString = "$baseEndpoint?client=$clientParam&sl=ja&tl=ko&dt=t&dt=rm&q=$encodedText"
                
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                
                // [STATELESS-SANITIZATION] 캐시 및 쿠키 추적 차단
                connection.useCaches = false
                connection.defaultUseCaches = false

                // [ANONYMIZED-HEADERS] 무작위 지문 주입
                val r = ThreadLocalRandom.current()
                val spoofedIp = generateRandomIp()
                val userAgent = USER_AGENTS[r.nextInt(USER_AGENTS.size)]
                val acceptLang = ACCEPT_LANGUAGES[r.nextInt(ACCEPT_LANGUAGES.size)]

                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("X-Forwarded-For", spoofedIp)
                connection.setRequestProperty("Client-IP", spoofedIp)
                connection.setRequestProperty("X-Real-IP", spoofedIp)
                connection.setRequestProperty("Accept", "application/json, text/javascript, */*")
                connection.setRequestProperty("Accept-Language", acceptLang)
                
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
                    Log.w(TAG, "[$clientParam] HTTP Response not OK: $responseCode (Attempt $attempt/$maxRetries, IP: $spoofedIp)")
                    if (responseCode == 429) {
                        // Rate limit 걸렸을 경우 엔드포인트/클라이언트 교체 대기
                        delay(attempt * 800L)
                    }
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Fetch Attempt $attempt/$maxRetries Failed: ${e.message}")
                if (attempt < maxRetries) {
                    delay(attempt * 600L)
                }
            }
        }
        Log.e(TAG, "Full Fetch Exhausted All $maxRetries Retries", lastException)
        return@withContext Pair("", "")
    }
}
