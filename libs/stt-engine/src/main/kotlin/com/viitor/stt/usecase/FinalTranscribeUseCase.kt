package com.viitor.stt.usecase

import com.viitor.audio.domain.AudioChunk
import com.viitor.stt.WhisperSTTProcessor
import com.viitor.stt.domain.BatchTranscript
import com.viitor.stt.domain.TranscriptionMode

/**
 * ★ FinalTranscribeUseCase: 배치 처리 (침묵 후)
 */
class FinalTranscribeUseCase(
    private val whisper: WhisperSTTProcessor,
    private val vosk: com.viitor.stt.VoskSTTProcessor? = null
) {
    suspend fun execute(batch: List<AudioChunk>, engineType: String = "WHISPER"): BatchTranscript {
        // 기존의 하드코딩된 제한(batch.size <= 4)을 완전히 해제하여 무한에 가까운 버퍼링 허용
        require(batch.isNotEmpty()) { "ERR_EMPTY_BATCH" }
        
        val mergedAudio = batch.flatMap { it.data.toList() }.toByteArray()
        
        return if (engineType == "VOSK" && vosk != null) {
            val result = vosk.transcribe(mergedAudio)
            BatchTranscript(
                text = result.text,
                chunkCount = batch.size
            )
        } else {
            val resultText = whisper.transcribe(
                mergedAudio,
                mode = TranscriptionMode.FINAL  // beam=5
            )
            BatchTranscript(
                text = resultText,
                chunkCount = batch.size
            )
        }
    }
}
