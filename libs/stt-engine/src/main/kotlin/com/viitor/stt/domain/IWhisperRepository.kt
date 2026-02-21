package com.viitor.stt.domain

/**
 * ★ IWhisperRepository
 */
interface IWhisperRepository {
    // ERR_MODE_MISMATCH: @TranscriptionMode(PROVISIONAL|FINAL)
    suspend fun transcribe(
        audioData: ByteArray,
        mode: TranscriptionMode
    ): String
}
