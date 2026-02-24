package com.viitor.audio.domain

data class VadConfig(
    var thresholdDb: Float = -90f,     // 민감도를 높이기 위해 -80dB -> -90dB로 조정
    var minSilenceMs: Long = 400       // 실시간 오디오 캐칭(극단적 반응성)을 위해 침묵 판단 기준을 400ms로 단축
)
