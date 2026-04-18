package com.viitor.hijack

import android.util.Log

/**
 * [HijackDispatcher] - URL 기반 플랫폼 엔진 선택기 (싱글톤)
 *
 * 현재 URL을 받아 등록된 IHijackEngine 중 적합한 엔진을 반환한다.
 * 신규 플랫폼 추가 시 engines 리스트에 추가만 하면 된다.
 *
 * @return [IHijackEngine] 해당 URL에 매칭된 엔진, 없으면 null
 */
object HijackDispatcher {
    private const val TAG = "HijackDispatcher"

    /** 플랫폼 우선순위 순 등록 */
    private val engines: List<IHijackEngine> = listOf(
        YoutubeHijackEngine,
        YoutubeLiveHijackEngine,
        YoutubeHijackEngine,
        TwitchHijackEngine
    )

    /**
     * URL을 분석해 적합한 하이재킹 엔진을 반환한다.
     * @param url 현재 WebView URL
     */
    fun resolve(url: String?): IHijackEngine? {
        if (url.isNullOrBlank()) return null
        
        // [RESOLVE-STRATEGY] YouTube를 최우선으로 검사
        val matched = engines.firstOrNull { it.urlPattern.containsMatchIn(url) }
        
        if (matched != null) {
            Log.i(TAG, "🎯 [ENGINE-MATCH] Engine Selection: ${matched::class.simpleName} for URL: $url")
        } else {
            Log.w(TAG, "⚠️ [ENGINE-MISS] No engine matched for URL: $url")
        }
        return matched
    }
}
