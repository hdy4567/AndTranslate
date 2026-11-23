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

    private val chunkBuffers = mutableMapOf<Int, StringBuilder>()

    @JavascriptInterface
    override fun onFullTranscriptCaptured(fullTranscript: String) {
        Log.i("YoutubeJsBridge", "[JS-NETWORK-SNIFF] Single Packet: ${fullTranscript.take(50)}...")
        handleFullTranscript(fullTranscript)
    }

    @JavascriptInterface
    override fun onFullTranscriptCapturedChunked(chunk: String, index: Int, total: Int) {
        val buffer = chunkBuffers.getOrPut(total) { StringBuilder() }
        buffer.append(chunk)

        if (index == total - 1) {
            val fullTranscript = buffer.toString()
            Log.w("YoutubeJsBridge", "[JS-NETWORK-SNIFF] Reassembled Packet: ${fullTranscript.length} bytes")
            handleFullTranscript(fullTranscript)
            chunkBuffers.remove(total)
        }
    }

    private fun handleFullTranscript(data: String) {
        val isLiveQueue = data.contains("actions") || data.contains("continuation")
        val isJson3 = data.contains("events")
        
        if (isLiveQueue) {
            Log.e("YoutubeJsBridge", "🎯🎯🎯 [LIVE-RADAR-HIT] 🎯🎯🎯 Size: ${data.length} | Preview: ${data.take(100)}")
        } else if (isJson3) {
            Log.i("YoutubeJsBridge", "💎 [VOD-SCRIPT-HIT] Captured JSON3 Packet. Size: ${data.length}")
        } else {
            Log.d("YoutubeJsBridge", "📡 [RADAR-SIGNAL] Packet Received (Other). Size: ${data.length} | Preview: ${data.take(50)}")
        }
        
        viewModel.onFullTranscriptCaptured(data)
    }

    @JavascriptInterface
    override fun onSubtitleCaptured(text: String) {
        Log.i("YoutubeJsBridge", "🔥 [STT-QUEUE-HIT] $text")
        viewModel.onSubtitleCaptured(text)
    }

    @JavascriptInterface
    override fun onLog(msg: String) {
        Log.d("YoutubeJsBridge", "[JS-CONSOLE] $msg")
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

