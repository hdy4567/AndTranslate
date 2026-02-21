package com.viitor.stt

import android.util.LruCache

/**
 * ★ STTResultCache: LRU 캐시 (중복 방지)
 */
class STTResultCache(maxSize: Int = 20) {
    private val cache = LruCache<String, String>(maxSize)
    fun put(audioHash: String, result: String) {
        cache.put(audioHash, result)
    }
    fun get(audioHash: String): String? {
        return cache.get(audioHash)
    }
}
