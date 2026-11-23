package com.viitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.viitor.pipeline.core.GlobalSubtitleRelay
import com.viitor.pipeline.core.PipelineEngine
import com.viitor.pipeline.core.SmartSubtitleAgent
import com.viitor.pipeline.core.SubtitleData
import com.viitor.service.overlay.OverlayViewManager

/**
 * [SubtitleCaptureService] (Standard Ignition - Decoupled Architecture)
 * - Features: Overlay lifecycle delegation, background pipeline engine coordination, notification handling.
 */
class SubtitleCaptureService : Service() {
    companion object {
        private const val TAG = "SubtitleCaptureService"
        private const val CHANNEL_ID = "AndTrans_Capture_Channel"
        private const val NOTIFICATION_ID = 1010
    }

    private val agent = SmartSubtitleAgent()
    private val engine = PipelineEngine(agent)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayViewManager: OverlayViewManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        overlayViewManager = OverlayViewManager(this).apply {
            initOverlay()
        }

        // [STREAM-BINDING] 브라우저(Relay) 또는 에이전트로부터 데이터 수신 및 UI 반영
        val streamListener: (SubtitleData) -> Unit = { data ->
            mainHandler.post {
                Log.i(TAG, "[5.RELAY] Received: origin1layer=${data.origin1layer.take(20)}, tras2layer=${data.tras2layer?.take(20)}")
                overlayViewManager?.updateSubtitles(data)
            }
        }

        // 1. 브라우저 자막 스트림 연결
        GlobalSubtitleRelay.onNewData = streamListener

        // 2. 외부 OCR/추출 에이전트 스트림 연결
        agent.onSubtitleResult = streamListener

        engine.ignite()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "🟢 Service Start Command Received")
        intent?.getStringExtra("videoId")?.let { videoId ->
            agent.processSource(videoId)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndTranslate Active")
            .setContentText("Real-time subtitle hijacking and translation enabled.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Subtitle Capture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        overlayViewManager?.destroy()
        overlayViewManager = null
        engine.shutdown()
        super.onDestroy()
    }
}
