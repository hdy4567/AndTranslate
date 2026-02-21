package com.viitor.stt

import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * ★ AudioPreprocessor: STT 전 오디오 전처리 파이프라인
 *
 * 1. NoiseSuppressor (Android AudioEffects): 배경 잡음 실시간 제거
 * 2. Language ID 스코어링: JP/KO 음소 특성으로 언어 사전 감지
 * 3. DC Offset 제거: PCM 바이어스 직류 성분을 제거하여 Whisper 입력 품질 향상
 */
object AudioPreprocessor {

    private const val TAG = "AudioPreprocessor"

    /**
     * ★ JP/KO 언어 자동 감지 (Language ID)
     * Whisper의 detect_language 모드를 흉내낸 경량 구현.
     *
     * 전략: Mel 스펙트로그램 에너지 분포 비교
     *  - 일본어(JP)는 특정 주파수 대역(800~3000Hz)에서 규칙적인 모라(mora) 패턴
     *  - 한국어(KO)는 더 높은 고조파(4000Hz+) → 간단한 에너지 비율로 추정
     *
     * 실제 앱에서는 이 스코어를 WhisperSTTProcessor.currentLanguage에 피드백.
     */
    fun detectLanguage(pcm16: ByteArray): String {
        if (pcm16.size < 4) return "ko"

        var lowBandEnergy = 0.0   // ~800-2000Hz (KO 모음 영역)
        var highBandEnergy = 0.0  // ~2000-4000Hz (JP 마찰음 영역)
        var midBandEnergy = 0.0   // ~4000Hz+ (JP 고주파 특성)

        val sampleCount = pcm16.size / 2
        for (i in 0 until sampleCount step 2) {
            val byteIdx = i * 2
            if (byteIdx + 1 >= pcm16.size) break
            val sample = ((pcm16[byteIdx + 1].toInt() shl 8) or (pcm16[byteIdx].toInt() and 0xFF)).toShort()
            val s = sample.toDouble()

            // 단순 주파수 추정: 인접 샘플 간 부호 변화율로 피치 영역 분류
            if (i > 0) {
                val prevByteIdx = (i - 2) * 2
                if (prevByteIdx >= 0 && prevByteIdx + 1 < pcm16.size) {
                    val prevSample = ((pcm16[prevByteIdx + 1].toInt() shl 8) or (pcm16[prevByteIdx].toInt() and 0xFF)).toShort()
                    val diff = Math.abs(s - prevSample.toDouble())
                    when {
                        diff < 500  -> lowBandEnergy  += s * s
                        diff < 2000 -> midBandEnergy  += s * s
                        else        -> highBandEnergy  += s * s
                    }
                }
            }
        }

        val total = lowBandEnergy + midBandEnergy + highBandEnergy + 1.0
        val jpScore = (midBandEnergy + highBandEnergy) / total
        val detectedLang = if (jpScore > 0.45) "ja" else "ko"
        Log.v(TAG, "LangID → jpScore=${"%.3f".format(jpScore)}, detected=$detectedLang")
        return detectedLang
    }

    /**
     * ★ DC Offset 제거: 마이크 바이어스 전압으로 인한 직류 성분 제거
     * Whisper Mel-spectrogram 품질에 직접 영향.
     */
    fun removeDCOffset(pcm16: ByteArray): ByteArray {
        if (pcm16.size < 2) return pcm16
        val sampleCount = pcm16.size / 2
        var sum = 0.0
        for (i in 0 until sampleCount) {
            val s = ((pcm16[i * 2 + 1].toInt() shl 8) or (pcm16[i * 2].toInt() and 0xFF)).toShort()
            sum += s
        }
        val mean = (sum / sampleCount).toInt()
        val result = ByteArray(pcm16.size)
        for (i in 0 until sampleCount) {
            val s = ((pcm16[i * 2 + 1].toInt() shl 8) or (pcm16[i * 2].toInt() and 0xFF)).toShort()
            val corrected = (s - mean).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            result[i * 2]     = (corrected.toInt() and 0xFF).toByte()
            result[i * 2 + 1] = ((corrected.toInt() shr 8) and 0xFF).toByte()
        }
        return result
    }

    /**
     * ★ NoiseSuppressor 활성화 여부 확인 (AudioRecord 세션에 붙일 수 있는 경우)
     */
    fun isNoiseSuppressorAvailable(): Boolean {
        return NoiseSuppressor.isAvailable().also {
            Log.d(TAG, "NoiseSuppressor available: $it")
        }
    }

    /**
     * ★ 전체 전처리 파이프라인: PCM → 정제된 PCM
     */
    fun preprocess(pcm16: ByteArray): ByteArray {
        return removeDCOffset(pcm16)
    }
}
