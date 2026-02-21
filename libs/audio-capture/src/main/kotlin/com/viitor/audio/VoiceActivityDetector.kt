package com.viitor.audio

import com.viitor.audio.domain.AudioChunk
import com.viitor.audio.domain.VadConfig
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * ★ VoiceActivityDetector: RMS 기반 VAD
 */
class VoiceActivityDetector(private val config: VadConfig) {
    fun detectVoiceActivity(chunk: AudioChunk): Boolean {
        val rms = calculateRMS(chunk.data)
        return rms > config.thresholdDb  // -40dB
    }

    fun calculateRMS(data: ByteArray): Float {
        var sum = 0.0
        for (i in 0 until data.size step 2) {
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort()
            sum += sample.toDouble() * sample.toDouble()
        }
        val mean = sum / (data.size / 2)
        val rms = sqrt(mean)
        
        // Relative to max short value (32767)
        return if (rms > 0) (20 * log10(rms / 32767.0)).toFloat() else -100f
    }

    private var noiseFloorDb = config.thresholdDb
    private val smoothingFactor = 0.05f

    /**
     * ★ 엔터프라이즈급 기능: 주변 환경 소음(선풍기, 에어컨 등)을 동적으로 학습해 임계값을 자동 조정
     */
    fun isVoice(rms: Float): Boolean {
        // 노이즈로 짐작되는 구간에서만 바닥 소음 수준(Noise Floor) 갱신
        if (rms < noiseFloorDb + 15f) {
            noiseFloorDb = (noiseFloorDb * (1 - smoothingFactor)) + (rms * smoothingFactor)
        }
        // 극단적 튀는 값 방어
        noiseFloorDb = kotlin.math.max(-90f, kotlin.math.min(-30f, noiseFloorDb))
        
        // 동적 임계값: 기본 임계값과 가변 노이즈 플로어 중 더 엄격한 값 적용 (오경보 최소화)
        val dynamicThreshold = kotlin.math.max(config.thresholdDb, noiseFloorDb + 12f)
        
        return rms > dynamicThreshold
    }
}
