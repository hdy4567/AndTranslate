package com.viitor.ui.tab1_browser.bridge

import android.util.Log
import android.webkit.JavascriptInterface
import com.viitor.ui.tab1_browser.YoutubeViewModel
import com.viitor.ui.tab1_browser.domain.SubtitlePayload
import org.json.JSONObject

class YoutubeJsBridgeImpl(
    private val viewModel: YoutubeViewModel
) : IYoutubeJsBridge {

    private val chunkBuffer = StringBuilder()

    @JavascriptInterface
    override fun onSubtitleExtracted(jsonPayload: String) {
        processPayload(jsonPayload)
    }

    @JavascriptInterface
    override fun onSubtitleExtractedChunked(chunk: String, index: Int, total: Int) {
        if (index == 0) chunkBuffer.clear()
        chunkBuffer.append(chunk)

        if (index == total - 1) {
            processPayload(chunkBuffer.toString())
            chunkBuffer.clear()
        }
    }

    @JavascriptInterface
    override fun onTimeUpdate(timeMs: Long) {
        viewModel.updateCurrentTime(timeMs)
    }

    private fun processPayload(jsonPayload: String) {
        try {
            val json = JSONObject(jsonPayload)
            val anchorTimeMs = json.optLong("anchorTimeMs", 0L)
            val originJpText = json.optString("originJpText", "")

            if (originJpText.isNotBlank()) {
                Log.d("YoutubeJsBridge", "[JS-BRIDGE] Captured Subtitle Stream: $originJpText")
                val payload = SubtitlePayload(anchorTimeMs, originJpText)
                viewModel.processExtractedSubtitle(payload)
            }
        } catch (e: Exception) {
            Log.e("YoutubeJsBridge", "JSON Parse Error", e)
        }
    }
}

