package com.viitor.audio.domain

/**
 * ERR_AUDIO_CHUNK_EMPTY: @NotEmpty + require(data.isNotEmpty())
 */
data class AudioChunk(
    val data: ByteArray,               // PCM 16bit
    val durationMs: Long,              // 1500ms (청크 길이)
    val arrivalTimestamp: Long = System.currentTimeMillis()
)
