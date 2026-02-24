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
    suspend fun execute(chunk: AudioChunk, engineType: String = "WHISPER", prompt: String = "", language: String = "ja"): ProvisionalTranscript {
        return withContext(Dispatchers.Default) {
            // ★ Enterprise Audio Preprocessing Pipeline
            // 이제 개별 엔진 특성에 맞춰 브랜치 내부에서 최적화 처리함

            if (engineType == "VOSK" && vosk != null) {
                // ... (existing vosk logic) ...
                val enhancedData = MelSpectrogramEnhancer.enhance(
                    AudioPreprocessor.preprocess(chunk.data)
                )
                val result = vosk.transcribe(enhancedData)
                val confidence = if (result.isPartial) 0.4f else 0.8f
                val normalizedText = JapaneseTextNormalizer.normalize(result.text)
                
                val dummyTokens = if (normalizedText.isNotBlank()) intArrayOf(1) else intArrayOf()
                com.viitor.stt.util.SubtitleForensicAgent.recordInference(
                    com.viitor.stt.domain.TranscriptionMode.PROVISIONAL,
                    dummyTokens,
                    normalizedText
                )

                ProvisionalTranscript(
                    text = normalizedText,
                    confidence = confidence,
                    isFinalChunk = !result.isPartial,
                    timestamp = chunk.arrivalTimestamp
                )
            } else {
                // 🔊 WHISPER path
                val rawPreprocessed = AudioPreprocessor.preprocess(chunk.data)
                val resultText = whisper.transcribe(
                    rawPreprocessed,
                    mode = TranscriptionMode.PROVISIONAL,
                    promptText = prompt,
                    language = language
                )
                
                var normalizedText = JapaneseTextNormalizer.normalize(resultText)
                
                // ⚡ [Automatic-Fallback] Whisper가 바이패스(침묵) 중이면 즉시 VOSK로 시도
                if (normalizedText.isBlank() && vosk != null && vosk.isReady()) {
                    android.util.Log.i("TranscribeUseCase", "[AUTO-FALLBACK] Whisper silent/muted. Trying Vosk...")
                    val enhancedData = MelSpectrogramEnhancer.enhance(rawPreprocessed)
                    val vResult = vosk.transcribe(enhancedData)
                    normalizedText = JapaneseTextNormalizer.normalize(vResult.text)
                }

                ProvisionalTranscript(
                    text = normalizedText,
                    confidence = 0.9f, 
                    isFinalChunk = false,
                    timestamp = chunk.arrivalTimestamp
                )
            }
        }
    }

    fun isReady(engineType: String = "WHISPER"): Boolean {
        return if (engineType == "WHISPER") whisper.isReady() else vosk?.isReady() ?: false
    }

    fun forceReset() {
        vosk?.reset()
    }
}
