package com.viitor.stt.usecase

import com.viitor.audio.domain.AudioChunk
import com.viitor.stt.AudioPreprocessor
import com.viitor.stt.JapaneseTextNormalizer
import com.viitor.stt.MelSpectrogramEnhancer
import com.viitor.stt.WhisperSTTProcessor
import com.viitor.stt.domain.BatchTranscript
import com.viitor.stt.domain.TranscriptionMode

/**
 * ★ FinalTranscribeUseCase: 배치 처리 (침묵 후)
 * 🔧 개선: 전처리 파이프라인 + 후처리 정규화 실제 연결
 */
class FinalTranscribeUseCase(
    private val whisper: WhisperSTTProcessor,
    private val vosk: com.viitor.stt.VoskSTTProcessor? = null
) {
    suspend fun execute(batch: List<AudioChunk>, engineType: String = "WHISPER", prompt: String = "", language: String = "ja"): BatchTranscript {
        require(batch.isNotEmpty()) { "ERR_EMPTY_BATCH" }

        // ★ 배치 병합 후 전처리 파이프라인 적용 (DC Offset → Pre-emphasis → Hamming)
        val rawMerged = batch.flatMap { it.data.toList() }.toByteArray()
        val enhancedAudio = MelSpectrogramEnhancer.enhance(
            AudioPreprocessor.preprocess(rawMerged)
        )

        return if (engineType == "VOSK" && vosk != null) {
            val result = vosk.transcribe(enhancedAudio)
            BatchTranscript(
                text = JapaneseTextNormalizer.normalize(result.text),
                chunkCount = batch.size
            )
        } else {
            val resultText = whisper.transcribe(
                enhancedAudio,
                mode = TranscriptionMode.FINAL,
                promptText = prompt,
                language = language
            )
            BatchTranscript(
                text = JapaneseTextNormalizer.normalize(resultText),
                chunkCount = batch.size
            )
        }
    }
}
