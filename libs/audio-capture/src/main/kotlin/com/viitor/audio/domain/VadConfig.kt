package com.viitor.audio.domain

data class VadConfig(
    val thresholdDb: Float = -80f,     // 민감도를 높이기 위해 -40dB -> -80dB로 조정
    val minSilenceMs: Long = 500       // 실시간 오디오 캐칭(극단적 반응성)을 위해 침묵 판단 기준을 500ms로 단축
)
