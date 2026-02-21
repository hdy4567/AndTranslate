package com.viitor.stt

import android.content.Context
import org.vosk.android.StorageService
import java.io.IOException
import android.util.Log

/**
 * ★ VoskSTTProcessor: Fallback 엔진 (실시간성이 높음)
 */
class VoskSTTProcessor(private val context: Context) : AutoCloseable {
    private var voskModelInstance: org.vosk.Model? = null

    /**
     * assets/vosk-model-small-ko-0.22 모델 연결
     */
    /**
     * assets/vosk-model-small-ko-0.22 모델 연결
     * - 동기식 로딩 (비동기 콜백 Hell 제거)
     */
    fun loadModel(language: String = "ko"): Result<Boolean> {
        return try {
            val modelPath = com.viitor.stt.util.VoskModelLoader.loadModel(context, language)
            if (modelPath != null) {
                this.voskModelInstance = org.vosk.Model(modelPath)
                Result.success(true)
            } else {
                Result.failure(Exception("Model path is null for lang=$language (Unpacking failed)"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    private var recognizer: org.vosk.Recognizer? = null

    fun reset() {
        recognizer?.reset()
    }

    fun transcribe(audioData: ByteArray): com.viitor.stt.domain.VoskResult {
        val model = voskModelInstance ?: return com.viitor.stt.domain.VoskResult("[Vosk Not Ready]", false)
        
        try {
            if (recognizer == null) {
                // Default Sample Rate 16k
                recognizer = org.vosk.Recognizer(model, 16000.0f).apply {
                    // 품질 및 구조적 향상을 위한 대체 단어 및 경계 인식 최적화
                    setMaxAlternatives(0) 
                    setWords(true) // ★ 엔터프라이즈급: 단어 단위 경계 분석 활성화로 문장 띄어쓰기 및 마침표 인식률 극대화
                }
            }
            
            val currentRecognizer = recognizer!!
            
            // 스트리밍 방식으로 오디오 데이터 처리
            // Vosk는 acceptWaveForm 호출 시 내부 버퍼에 계속 쌓아가며 부분 결과(partial) 또는 최종 결과(result)를 반환함
            if (currentRecognizer.acceptWaveForm(audioData, audioData.size)) {
                val json = currentRecognizer.result
                return com.viitor.stt.domain.VoskResult(parseVoskResult(json), false)
            } else {
                val json = currentRecognizer.partialResult
                return com.viitor.stt.domain.VoskResult(parseVoskPartial(json), true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // 에러 발생 시 Recognizer 재설정
            recognizer = null
            return com.viitor.stt.domain.VoskResult("[Error] ${e.message}", false)
        }
    }
    
    private fun parseVoskResult(json: String): String {
        return try {
            org.json.JSONObject(json).optString("text", "")
        } catch (e: Exception) { 
            "" 
        }
    }

    private fun parseVoskPartial(json: String): String {
        return try {
            org.json.JSONObject(json).optString("partial", "")
        } catch (e: Exception) { 
            "" 
        }
    }

    override fun close() {
        recognizer?.close()
        recognizer = null
        voskModelInstance?.close()
        voskModelInstance = null
    }

}




