package com.viitor.translate

import android.util.LruCache

/**
 * ★ TranslationCache: 번역 결과 캐싱 (DB Index 역할)
 */
class TranslationCache(maxSize: Int = 20) {
    private val cache = LruCache<String, String>(maxSize)
    
    // DB-style Key 생성: "srcLang:targetLang:text"
    private fun generateKey(text: String, src: String, target: String): String {
        return "$src:$target:$text"
    }

    fun put(text: String, src: String, target: String, result: String) {
        cache.put(generateKey(text, src, target), result)
    }
    
    fun get(text: String, src: String, target: String): String? {
        return cache.get(generateKey(text, src, target))
    }

    fun clear() {
        cache.evictAll()
    }
}
