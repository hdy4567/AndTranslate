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

    @JavascriptInterface
    override fun onVideoDetected(videoId: String) {
        Log.i("YoutubeJsBridge", "[JS-BRIDGE] Video Detected: $videoId")
        viewModel.onVideoChanged(videoId)
    }

    @JavascriptInterface
    override fun onFullTranscriptCaptured(jsonContent: String) {
        try {
            Log.i("YoutubeJsBridge", "[JS-BRIDGE] 7000+ Bulk Transcript Received.")
            val list = com.viitor.ui.tab2_script.SubtitleParserUtil.parseJson3Content(jsonContent)
            viewModel.updateManualTranscript(list)
            Log.d("YoutubeJsBridge", "[JS-BRIDGE] Registry Synchronized: ${list.size} lines.")
        } catch (e: Exception) {
            Log.e("YoutubeJsBridge", "Bulk Handle Error", e)
        }
    }

    private fun processPayload(jsonPayload: String) {
        try {
            val json = JSONObject(jsonPayload)
            val anchorTimeMs = json.optLong("anchorTimeMs", 0L)
            val origin1layer = json.optString("origin1layer", "")

            if (origin1layer.isNotBlank()) {
                Log.d("YoutubeJsBridge", "[JS-BRIDGE] Captured Subtitle Stream: $origin1layer")
                val payload = SubtitlePayload(anchorTimeMs, origin1layer)
                viewModel.processExtractedSubtitle(payload)
            }
        } catch (e: Exception) {
            Log.e("YoutubeJsBridge", "JSON Parse Error", e)
        }
    }
}

