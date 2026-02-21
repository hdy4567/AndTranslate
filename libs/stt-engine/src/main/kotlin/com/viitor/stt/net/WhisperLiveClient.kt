package com.viitor.stt.net

import android.util.Log
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * ★ WhisperLiveClient: 고성능 서버 STT(WhisperLive) 연동용 WebSocket 클라이언트
 * - 오디오 청크 전송 → 실시간 텍스트 수신
 */
class WhisperLiveClient(
    private val serverUrl: String = "ws://localhost:9090",
    private val onResult: (String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
        
    private var webSocket: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WhisperLive", "Connected to logic server: $serverUrl")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 서버에서 전송한 JSON 파싱 (간략화)
                onResult(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                Log.d("WhisperLive", "Closing... $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WhisperLive", "Connection failed: ${t.message}")
            }
        })
    }

    fun sendAudio(data: ByteArray) {
        webSocket?.send(ByteString.of(*data))
    }

    fun close() {
        webSocket?.close(1000, "User requested")
    }
}
