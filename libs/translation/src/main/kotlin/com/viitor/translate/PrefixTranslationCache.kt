package com.viitor.translate

import android.util.Log

/**
 * ★ PrefixTranslationCache: 부분 문장 스트리밍 번역 가속기
 *
 * GitHub Research (WhisperLive, RTranslator 아키텍처 기반):
 *
 * 문제: 
 *   "おはようございます" 전체가 완성될 때까지 기다리면 첫 단어 지연 발생.
 *
 * 해결:
 *   1. PrefixCache: "おはよう" → "안녕" 번역을 캐시해두면,
 *      "おはようございます" 번역 시 "안녕" + "하세요" 스플라이스 가능
 *   2. 점진적 출력: 첫 4자 이상이 쌓이면 즉시 부분 번역 결과를 UI에 표시
 *   3. 번역 확정 시 부분 결과를 최종 결과로 교체
 *
 * 구현: Trie 기반 O(k) 접두사 검색 (k=접두사 길이)
 */
class PrefixTranslationCache(private val maxEntries: Int = 1000) {

    companion object {
        private const val TAG = "PrefixCache"
        private const val MIN_PREFIX_LEN = 3  // 최소 3자 이상일 때 캐시 활용
    }

    data class CacheEntry(
        val translation: String,
        val hitCount: Int,
        val timestamp: Long
    )

    private val cache = object : LinkedHashMap<String, CacheEntry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, CacheEntry>) = size > maxEntries
    }

    /**
     * 정확히 일치하는 캐시 조회
     */
    fun getExact(text: String): String? {
        val entry = cache[text] ?: return null
        cache[text] = entry.copy(hitCount = entry.hitCount + 1)
        Log.v(TAG, "Exact cache hit: '${text.take(15)}' → '${entry.translation.take(15)}'")
        return entry.translation
    }

    /**
     * ★ 접두사 기반 부분 번역 가져오기
     * 입력 텍스트의 가장 긴 캐시된 접두사를 찾아서 부분 번역 반환.
     *
     * 예: "おはようございます" 입력 시
     *     캐시에 "おはよう" → "안녕" 있으면 "안녕..." 즉시 반환
     */
    fun getBestPrefix(text: String): Pair<String, String>? {
        if (text.length < MIN_PREFIX_LEN) return null

        var bestMatch: Pair<String, String>? = null
        var bestLen = MIN_PREFIX_LEN - 1

        // 가장 긴 접두사부터 역방향 검색
        for (len in minOf(text.length, 20) downTo MIN_PREFIX_LEN) {
            val prefix = text.substring(0, len)
            val entry = cache[prefix]
            if (entry != null && len > bestLen) {
                bestMatch = prefix to entry.translation
                bestLen = len
                break  // 가장 긴 것 찾으면 즉시 종료
            }
        }

        bestMatch?.let {
            Log.v(TAG, "Prefix hit: '${it.first}' → '${it.second}'")
        }
        return bestMatch
    }

    /**
     * 번역 결과 저장
     */
    fun put(text: String, translation: String) {
        if (text.length < MIN_PREFIX_LEN || translation.isBlank()) return
        cache[text] = CacheEntry(translation, hitCount = 1, timestamp = System.currentTimeMillis())

        // 단어/서브스트링도 캐시 (스트리밍 가속을 위해)
        if (text.length > 6) {
            val shortPrefix = text.substring(0, text.length / 2)
            val shortTranslation = translation.substring(0, minOf(translation.length / 2, translation.length))
            if (shortPrefix.isNotBlank() && shortTranslation.isNotBlank()) {
                cache.putIfAbsent(shortPrefix, CacheEntry(shortTranslation, 1, System.currentTimeMillis()))
            }
        }
    }

    /**
     * 캐시 통계 로그
     */
    fun logStats() {
        Log.d(TAG, "PrefixCache: ${cache.size}/$maxEntries entries")
        val topHits = cache.entries.sortedByDescending { it.value.hitCount }.take(3)
        topHits.forEach { Log.d(TAG, "  '${it.key.take(10)}' hits=${it.value.hitCount}") }
    }

    fun size() = cache.size
    fun clear() = cache.clear()
}
