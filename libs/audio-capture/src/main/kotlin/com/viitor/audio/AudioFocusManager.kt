package com.viitor.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * ★ AudioFocusManager: 전화 수신/시스템 오디오 이벤트 대응
 *
 * OS 안정성 핵심:
 * - 전화 수신 → 즉시 캡처 일시 정지 (ANR/충돌 원인 제거)
 * - 통화 종료 → 자동 재개
 * - 다른 앱 오디오 포커스 요청 시 Duck(음소거 아닌 볼륨 감소) 정책 적용
 */
class AudioFocusManager(private val context: Context) {

    companion object { private const val TAG = "AudioFocusManager" }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    var onFocusLost: (() -> Unit)? = null    // 전화/다른 앱 포커스 탈취 시
    var onFocusGained: (() -> Unit)? = null  // 포커스 회복 시

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.w(TAG, "Audio focus lost permanently (e.g. phone call)")
                onFocusLost?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.w(TAG, "Audio focus lost transiently")
                onFocusLost?.invoke()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Audio focus ducked — continuing with reduced priority")
                // Duck: 계속 캡처하되 우선순위 낮춤 — 자막 서비스 특성상 멈추지 않는 게 더 나음
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Audio focus gained — resuming")
                onFocusGained?.invoke()
            }
        }
    }

    /**
     * 오디오 포커스 요청. 캡처 시작 전 필수 호출.
     */
    fun requestFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusChangeListener)
                .setWillPauseWhenDucked(false)  // Duck 시 멈추지 말고 계속 캡처
                .build()
            focusRequest = req
            val result = audioManager.requestAudioFocus(req)
            val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            Log.d(TAG, "Audio focus request: ${if (granted) "GRANTED" else "DENIED"}")
            granted
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    /**
     * 오디오 포커스 반환. 서비스 종료 시 반드시 호출.
     */
    fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        Log.d(TAG, "Audio focus abandoned")
    }
}
