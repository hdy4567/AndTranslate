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
    suspend fun execute(language: String, onStatus: (String) -> Unit): String {
        // ★ [엔터프라이즈] XNNPACK 하드웨어 엑셀러레이터 덕분에 Whisper TFLite 추론 속도가 폭발적으로 상승함.
        // 따라서 조약한 VOSK 대신, 극강의 정확도를 자랑하는 Whisper를 압도적 Primary 엔진으로 격상!
        Log.d("STT", "Attempting to load Whisper(XNNPACK-accelerated) model for lang=$language")
        onStatus("Whisper 엔진 기동 중...")
        
        val whisperResult = try {
            Log.d("STT", ">>> STT Start: Whisper loading...")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                    whisper.loadModel(language)
                }
            }
        } catch (e: Exception) {
            Log.e("STT", "Whisper load critical error: ${e.message}")
            null
        } finally {
            Log.d("STT", "<<< STT End: Whisper loading attempt finished")
        }
        
        if (whisperResult?.isSuccess == true) {
            Log.d("STT", "Lightning-fast Whisper model loaded successfully for lang=$language")
            onStatus("Vosk 예비 엔진 준비 중...")
            // VOSK는 백그라운드 예비용으로만 대기
            @Suppress("OPT_IN_USAGE")
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) { 
                Log.d("STT", ">>> Background: Vosk loading...")
                vosk.loadModel(language, onStatus) 
                Log.d("STT", "<<< Background: Vosk loading finished")
            }
            return "WHISPER"
        }

        // Whisper 실패 시 VOSK로 Fallback
        Log.w("STT", "Whisper load failed/timeout → fallback to VOSK [$language]")
        onStatus("Vosk 엔진으로 전환 중 (압축 해제 포함)...")
        Log.d("STT", ">>> STT Start: Vosk fallback loading...")
        val voskResult = vosk.loadModel(language, onStatus)
        Log.d("STT", "<<< STT End: Vosk fallback loading finished")

        return if (voskResult.isSuccess) {
            Log.d("STT", "Vosk model loaded successfully for lang=$language")
            "VOSK"
        } else {
            Log.e("STT", "Both Whisper and Vosk failed for lang=$language")
            "NONE"
        }
    }
}

