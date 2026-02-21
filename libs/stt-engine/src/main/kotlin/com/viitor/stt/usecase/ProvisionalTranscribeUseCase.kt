package com.viitor.stt.usecase

import com.viitor.audio.domain.AudioChunk
import com.viitor.stt.WhisperSTTProcessor
import com.viitor.stt.domain.ProvisionalTranscript
import com.viitor.stt.domain.TranscriptionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ★ ProvisionalTranscribeUseCase: 단일 청크 즉시 처리
 */
class ProvisionalTranscribeUseCase(
    private val whisper: WhisperSTTProcessor,
    private val vosk: com.viitor.stt.VoskSTTProcessor? = null
) {
    suspend fun execute(chunk: AudioChunk, engineType: String = "WHISPER"): ProvisionalTranscript {
        return withContext(Dispatchers.Default) {
            if (engineType == "VOSK" && vosk != null) {
                // Vosk 처리
                val result = vosk.transcribe(chunk.data)
                // Partial 결과는 신뢰도 낮음
                val confidence = if (result.isPartial) 0.4f else 0.8f
                ProvisionalTranscript(
                    text = result.text,
                    confidence = confidence,
                    isFinalChunk = !result.isPartial
                )
            } else {
                // Whisper 처리 (기존 로직)
                val resultText = whisper.transcribe(
                    chunk.data, 
                    mode = TranscriptionMode.PROVISIONAL
                )
                ProvisionalTranscript(
                    text = resultText,
                    confidence = 0.5f,
                    isFinalChunk = false
                )
            }
        }
    }

    fun forceReset() {
        vosk?.reset()
    }
}
