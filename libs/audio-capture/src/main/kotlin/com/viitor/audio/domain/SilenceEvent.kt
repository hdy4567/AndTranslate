package com.viitor.audio.domain

data class SilenceEvent(
    val isEndOfSentence: Boolean       // true: 배치 STT 트리거
)
