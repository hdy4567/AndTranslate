package com.viitor.stt.usecase

import com.viitor.audio.domain.AudioChunk
import com.viitor.stt.AudioPreprocessor
import com.viitor.stt.JapaneseTextNormalizer
import com.viitor.stt.MelSpectrogramEnhancer
import com.viitor.stt.WhisperSTTProcessor
import com.viitor.stt.domain.ProvisionalTranscript
import com.viitor.stt.domain.TranscriptionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ★ ProvisionalTranscribeUseCase: 단일 청크 즉시 처리
 *
 * 🔧 개선: MelSpectrogramEnhancer + AudioPreprocessor 실제 연결
 *  - DC Offset 제거 → Pre-emphasis(고주파 부스트) → Hamming Window 순 파이프 적용
 *  - JP 마찰음/파열음(た,き,さ,は행 2kHz~8kHz) 인식률 극대화
 *  - Vosk/Whisper 양쪽 다 동일하게 적용
 */
class ProvisionalTranscribeUseCase(
    private val whisper: WhisperSTTProcessor,
    private val vosk: com.viitor.stt.VoskSTTProcessor? = null
) {
    suspend fun execute(chunk: AudioChunk, engineType: String = "WHISPER", prompt: String = ""): ProvisionalTranscript {
        return withContext(Dispatchers.Default) {
            // ★ Enterprise Audio Preprocessing Pipeline (기존엔 연결 안 됐던 부분)
            // DC Offset → Pre-emphasis(고주파 +12dB 부스트) → Hamming Window(경계 누설 제거)
            val enhancedData = MelSpectrogramEnhancer.enhance(
                AudioPreprocessor.preprocess(chunk.data)
            )

            if (engineType == "VOSK" && vosk != null) {
                val result = vosk.transcribe(enhancedData)
                val confidence = if (result.isPartial) 0.4f else 0.8f
                val normalizedText = JapaneseTextNormalizer.normalize(result.text)
                ProvisionalTranscript(
                    text = normalizedText,
                    confidence = confidence,
                    isFinalChunk = !result.isPartial,
                    timestamp = chunk.arrivalTimestamp
                )
            } else {
                val resultText = whisper.transcribe(
                    enhancedData,
                    mode = TranscriptionMode.PROVISIONAL,
                    promptText = prompt
                )
                val normalizedText = JapaneseTextNormalizer.normalize(resultText)
                ProvisionalTranscript(
                    text = normalizedText,
                    confidence = 0.5f,
                    isFinalChunk = false,
                    timestamp = chunk.arrivalTimestamp
                )
            }
        }
    }

    fun forceReset() {
        vosk?.reset()
    }
}
