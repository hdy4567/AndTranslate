package com.viitor.stt

import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * ★ MelSpectrogramEnhancer: JP 자음 캐치력 극한 상승을 위한 오디오 전처리
 *
 * 적용 기법 (whisper.cpp stream.cpp, argmaxinc/WhisperKitAndroid 연구):
 *
 * 1. Pre-emphasis Filter (프리엠퍼시스):
 *    H(z) = 1 - 0.97z^-1
 *    고주파 성분을 인위적으로 +12dB 부스트.
 *    일본어 た,き,さ,は행 마찰음/파열음이 주로 2kHz~8kHz 대역에 분포.
 *    → Whisper가 JP 자음을 더 명확하게 인식.
 *
 * 2. Hamming Window (해밍 윈도우):
 *    FFT 전 청크 경계 스펙트럼 누설(Spectral Leakage) 제거.
 *    윈도우 없이 FFT 하면 경계값에서 가짜 주파수 성분이 Whisper에 노이즈로 들어감.
 *
 * 3. Speech Rate Normalization (발화 속도 정규화):
 *    빠른 발화 시 Whisper가 앞 단어를 자르는 현상 방어.
 *    에너지 분산이 너무 빠를 경우 청크 크기를 동적으로 확장.
 */
object MelSpectrogramEnhancer {

    private const val TAG = "MelEnhancer"
    private const val PRE_EMPHASIS_COEFF = 0.97f  // 일반적으로 0.95~0.97

    /**
     * ★ Pre-emphasis Filter 적용
     * y[n] = x[n] - 0.97 * x[n-1]
     *
     * 입력: PCM16 ByteArray (LittleEndian)
     * 출력: 프리엠퍼시스 적용된 PCM16 ByteArray
     */
    fun applyPreEmphasis(pcm16: ByteArray): ByteArray {
        if (pcm16.size < 4) return pcm16

        val sampleCount = pcm16.size / 2
        val result = ByteArray(pcm16.size)
        var prevSample = 0.0f

        for (i in 0 until sampleCount) {
            val byteIdx = i * 2
            val rawSample = ((pcm16[byteIdx + 1].toInt() shl 8) or (pcm16[byteIdx].toInt() and 0xFF)).toShort()
            val sample = rawSample.toFloat()

            val filtered = (sample - PRE_EMPHASIS_COEFF * prevSample)
                .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())

            val filteredShort = filtered.toInt().toShort()
            result[byteIdx]     = (filteredShort.toInt() and 0xFF).toByte()
            result[byteIdx + 1] = ((filteredShort.toInt() shr 8) and 0xFF).toByte()
            prevSample = sample
        }

        return result
    }

    /**
     * ★ Hamming Window 적용
     * w[n] = 0.54 - 0.46 * cos(2π*n / (N-1))
     *
     * FFT 처리 전 오디오 프레임에 윈도우 함수를 적용해 경계 불연속성 제거.
     */
    fun applyHammingWindow(pcm16: ByteArray): ByteArray {
        if (pcm16.size < 4) return pcm16

        val sampleCount = pcm16.size / 2
        val result = ByteArray(pcm16.size)

        for (i in 0 until sampleCount) {
            val byteIdx = i * 2
            val rawSample = ((pcm16[byteIdx + 1].toInt() shl 8) or (pcm16[byteIdx].toInt() and 0xFF)).toShort()

            val windowCoeff = (0.54 - 0.46 * cos(2.0 * PI * i / (sampleCount - 1))).toFloat()
            val windowed = (rawSample.toFloat() * windowCoeff)
                .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())

            val windowedShort = windowed.toInt().toShort()
            result[byteIdx]     = (windowedShort.toInt() and 0xFF).toByte()
            result[byteIdx + 1] = ((windowedShort.toInt() shr 8) and 0xFF).toByte()
        }

        return result
    }

    /**
     * ★ 발화 속도 기반 동적 청크 조절
     * 에너지가 빠르게 변화하면(빠른 발화) 청크를 더 자주 끊어서 단어 손실 방지.
     *
     * @return 권장 청크 크기 (ms)
     */
    fun computeOptimalChunkMs(pcm16: ByteArray, baseChunkMs: Int = 40): Int {
        if (pcm16.size < 2) return baseChunkMs

        val sampleCount = pcm16.size / 2
        var energyVariance = 0.0
        var prevEnergy = 0.0

        for (i in 0 until minOf(sampleCount, 160)) {  // 처음 10ms만 샘플링
            val byteIdx = i * 2
            if (byteIdx + 1 >= pcm16.size) break
            val s = ((pcm16[byteIdx + 1].toInt() shl 8) or (pcm16[byteIdx].toInt() and 0xFF)).toShort().toDouble()
            val energy = s * s
            energyVariance += kotlin.math.abs(energy - prevEnergy)
            prevEnergy = energy
        }

        // 에너지 변화가 크면 → 작은 청크(20ms), 안정적이면 → 큰 청크(60ms)
        return when {
            energyVariance > 1e10 -> maxOf(20, baseChunkMs - 20)  // 빠른 발화
            energyVariance < 1e7  -> minOf(80, baseChunkMs + 20)  // 느린 발화/침묵
            else                  -> baseChunkMs                  // 정상
        }
    }

    /**
     * ★ 전체 향상 파이프라인: Pre-emphasis → Hamming Window
     * DC Offset 제거는 AudioPreprocessor에서 이미 처리됨.
     */
    fun enhance(pcm16: ByteArray): ByteArray {
        // ⚡ [Spectral Sharpening] 0.97 -> 0.985로 상향 조정하여 일본어 '스(Su)', '츠(Tsu)' 등 초고주파 자음 강조
        // 브라우저 캡처의 16kHz 샘플링 레이트 한계를 보정함.
        if (pcm16.size < 4) return pcm16
        val sampleCount = pcm16.size / 2
        val result = ByteArray(pcm16.size)
        var prevSample = 0.0f
        val alpha = 0.985f // 🚀 [Turbo JP Boost]

        for (i in 0 until sampleCount) {
            val byteIdx = i * 2
            val sample = ((pcm16[byteIdx + 1].toInt() shl 8) or (pcm16[byteIdx].toInt() and 0xFF)).toShort().toFloat()
            val filtered = (sample - alpha * prevSample).coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
            result[byteIdx] = (filtered.toInt() and 0xFF).toByte()
            result[byteIdx + 1] = ((filtered.toInt() shr 8) and 0xFF).toByte()
            prevSample = sample
        }
        return result
    }
}
