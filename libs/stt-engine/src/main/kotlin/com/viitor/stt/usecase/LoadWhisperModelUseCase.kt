package com.viitor.stt.usecase

import android.util.Log
import com.viitor.stt.VoskSTTProcessor
import com.viitor.stt.WhisperSTTProcessor
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope

/**
 * ★ LoadWhisperModelUseCase: 모델 로딩 with timeout
 */
class LoadWhisperModelUseCase(
    private val whisper: WhisperSTTProcessor,
    private val vosk: VoskSTTProcessor
) {
    suspend fun execute(language: String): String {
        // 1. Vosk 모델은 안드로이드 실시간 처리에 적합하므로 먼저 시도합니다. (Whisper TFLite는 CPU에서 2분 이상 소요되어 파이프라인을 정지시킴)
        Log.d("STT", "Attempting to load Vosk model for lang=$language (Primary engine for real-time)")
        val voskResult = vosk.loadModel(language)
        if (voskResult.isSuccess) {
            Log.d("STT", "Vosk model loaded successfully for lang=$language")
            // 백그라운드에서 Whisper도 혹시 모르니 로딩만 해둠 (선택사항)
            kotlinx.coroutines.GlobalScope.launch {
                whisper.loadModel(language)
            }
            return "VOSK"
        }

        // 2. Vosk 실패 시 → Whisper 모델로 폴백 (엄청 느리지만 최후의 보루)
        Log.w("STT", "Vosk load failed → fallback to Whisper [$language]")
        val whisperResult = withTimeoutOrNull(10_000L) {
            whisper.loadModel(language)
        }

        return if (whisperResult?.isSuccess == true) {
            Log.d("STT", "Whisper model loaded successfully for lang=$language")
            "WHISPER"
        } else {
            Log.e("STT", "Whisper also failed for lang=$language")
            "NONE"
        }
    }

}

