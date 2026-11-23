package com.viitor.ui.tab1_browser.bridge

import android.webkit.JavascriptInterface

interface IYoutubeJsBridge {
    @JavascriptInterface
    fun onSubtitleExtracted(jsonPayload: String)

    @JavascriptInterface
    fun onSubtitleExtractedChunked(chunk: String, index: Int, total: Int)

    @JavascriptInterface
    fun onTimeUpdate(timeMs: Long)

    @JavascriptInterface
    fun onVideoDetected(videoId: String)

    @JavascriptInterface
    fun onFullTranscriptCaptured(jsonContent: String)

    @JavascriptInterface
    fun onFullTranscriptCapturedChunked(chunk: String, index: Int, total: Int)

    @JavascriptInterface
    fun onSubtitleCaptured(text: String)

    @JavascriptInterface
    fun onLog(msg: String)
}

