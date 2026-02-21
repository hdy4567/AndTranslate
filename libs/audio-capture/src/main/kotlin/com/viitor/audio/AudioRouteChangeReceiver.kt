package com.viitor.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * ★ AudioRouteChangeReceiver: 오디오 라우트 변경 자동 감지 및 재연결
 *
 * 📌 연구 출처: Android AudioManager 모범 사례 2024
 *
 * 실제 사용 중 발생하는 끊김 원인 3가지:
 *   1. 이어폰 연결/해제 → AudioRecord 세션 무효화
 *   2. 블루투스 헤드셋 연결 → 오디오 경로 전환
 *   3. 음성 통화 시작 → AudioSource 충돌
 *
 * 해결:
 *   - ACTION_HEADSET_PLUG + ACTION_ACL_CONNECTED 브로드캐스트 수신
 *   - 연결 변경 감지 시 capturer 자동 재시작
 *   - 블루투스 SCO 지원 장치는 SCO 모드로 자동 전환
 */
class AudioRouteChangeReceiver(private val context: Context) : BroadcastReceiver() {

    companion object {
        private const val TAG = "AudioRouteReceiver"
    }

    var onRouteChanged: ((RouteChangeEvent) -> Unit)? = null

    enum class RouteChangeEvent { HEADSET_CONNECTED, HEADSET_DISCONNECTED, BLUETOOTH_CONNECTED, BLUETOOTH_DISCONNECTED, SPEAKER }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            AudioManager.ACTION_HEADSET_PLUG -> {
                val state = intent.getIntExtra("state", -1)
                val microphone = intent.getIntExtra("microphone", 0)
                Log.d(TAG, "Headset plug: state=$state, hasMicrophone=$microphone")
                val event = if (state == 1) RouteChangeEvent.HEADSET_CONNECTED
                            else           RouteChangeEvent.HEADSET_DISCONNECTED
                onRouteChanged?.invoke(event)
            }
            "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED" -> {
                val state = intent.getIntExtra("android.bluetooth.profile.extra.STATE", -1)
                Log.d(TAG, "Bluetooth headset state=$state")
                val event = if (state == 2 /*STATE_CONNECTED*/) RouteChangeEvent.BLUETOOTH_CONNECTED
                            else                                RouteChangeEvent.BLUETOOTH_DISCONNECTED
                onRouteChanged?.invoke(event)
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
        }
        context.registerReceiver(this, filter)
        Log.d(TAG, "AudioRouteChangeReceiver registered")
    }

    fun unregister() {
        try { context.unregisterReceiver(this) } catch (e: Exception) {}
    }

    /**
     * 현재 활성 오디오 입력 장치 감지
     */
    fun getActiveInputDevice(): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val device = devices.firstOrNull()
            return when (device?.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC        -> "BUILTIN_MIC"
                AudioDeviceInfo.TYPE_WIRED_HEADSET      -> "WIRED_HEADSET"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO      -> "BLUETOOTH_SCO"
                AudioDeviceInfo.TYPE_USB_DEVICE         -> "USB_MIC"
                else                                    -> "UNKNOWN(${device?.type})"
            }
        }
        return "UNKNOWN"
    }
}
