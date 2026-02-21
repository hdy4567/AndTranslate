package com.viitor.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

/**
 * ★ ServiceWatchdog: SubtitleCaptureService 자동 재시작 감시견
 *
 * OS 안정성 핵심:
 * - 서비스가 예기치 않게 종료될 경우 자동 재시작
 * - START_STICKY + PendingIntent 방식으로 OS가 직접 재기동
 * - 재시작 간 500ms 딜레이로 연속 충돌 방어
 */
class ServiceWatchdog(private val context: Context) {

    companion object {
        private const val TAG = "ServiceWatchdog"
        const val ACTION_RESTART = "com.viitor.ACTION_RESTART_SERVICE"
    }

    private val restartReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_RESTART) {
                Log.w(TAG, "Watchdog triggered: restarting SubtitleCaptureService")
                try {
                    val serviceIntent = Intent(ctx, com.viitor.service.SubtitleCaptureService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ctx.startForegroundService(serviceIntent)
                    } else {
                        ctx.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Restart failed: ${e.message}")
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter(ACTION_RESTART)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(restartReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(restartReceiver, filter)
        }
        Log.d(TAG, "Watchdog registered")
    }

    fun unregister() {
        try { context.unregisterReceiver(restartReceiver) } catch (e: Exception) {}
        Log.d(TAG, "Watchdog unregistered")
    }

    /**
     * 서비스를 onDestroy()에서 이걸 호출 → Watchdog이 자동 재시작
     */
    fun notifyServiceStopping() {
        Log.w(TAG, "Service stopping signal received — scheduling restart")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            context.sendBroadcast(Intent(ACTION_RESTART))
        }, 500L)
    }
}
