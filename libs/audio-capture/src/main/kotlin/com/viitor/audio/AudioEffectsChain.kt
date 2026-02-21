package com.viitor.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * ★ AudioEffectsChain: Android AudioEffects 통합 관리자
 *
 * GitHub Research 결과:
 * - NoiseSuppressor: VOICE_COMMUNICATION 모드에서만 효과적 (VOICE_RECOGNITION 에선 주의 필요)
 * - AcousticEchoCanceler: 같은 디바이스 에코 제거 (System Audio 캡처 시 필수)
 * - AutomaticGainControl: 볼륨 자동 정규화 → Whisper 입력 품질 균등화
 *
 * 전략: AGC만 항상 ON, NS/AEC는 마이크 모드일 때만 활성화
 * → STT 모델에 최적화된 깨끗한 오디오 스트림 보장
 */
class AudioEffectsChain {

    companion object {
        private const val TAG = "AudioEffectsChain"
    }

    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null

    /**
     * AudioRecord가 생성된 후 해당 세션 ID로 이펙트 체인을 초기화.
     * @param audioSessionId AudioRecord.getAudioSessionId()
     * @param isMicMode true=마이크 모드, false=시스템 오디오 캡처
     */
    fun attach(audioSessionId: Int, isMicMode: Boolean) {
        try {
            // 1. AutomaticGainControl (항상 활성화) - 볼륨 균등화
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(audioSessionId)?.apply {
                    enabled = true
                    Log.d(TAG, "AGC enabled (sessionId=$audioSessionId)")
                }
            }

            // 2. AcousticEchoCanceler (마이크 모드에서만)
            if (isMicMode && AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                    Log.d(TAG, "AEC enabled")
                }
            }

            // 3. NoiseSuppressor (마이크 모드에서만, VOICE_COMMUNICATION 컨텍스트)
            // ★ Google 가이드라인: VOICE_RECOGNITION에는 NS 비활성화 권고
            // → 마이크 전용 캡처에서는 안전하게 활성화 가능
            if (isMicMode && NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                    Log.d(TAG, "NoiseSuppressor enabled")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "AudioEffectsChain attach failed: ${e.message}")
        }
    }

    /**
     * 이펙트 체인 해제. AudioRecord.stop() 전에 호출.
     */
    fun detach() {
        try {
            noiseSuppressor?.release()
            echoCanceler?.release()
            gainControl?.release()
            noiseSuppressor = null
            echoCanceler = null
            gainControl = null
            Log.d(TAG, "AudioEffectsChain detached")
        } catch (e: Exception) {
            Log.e(TAG, "AudioEffectsChain detach failed: ${e.message}")
        }
    }

    fun isNSActive() = noiseSuppressor?.enabled == true
    fun isAECActive() = echoCanceler?.enabled == true
    fun isAGCActive() = gainControl?.enabled == true
}
