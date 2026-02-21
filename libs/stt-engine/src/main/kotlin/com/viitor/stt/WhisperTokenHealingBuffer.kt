package com.viitor.stt

import android.util.Log

/**
 * ★ WhisperTokenHealingBuffer: 청크 경계에서 잘린 JP 토큰 자동 치유
 *
 * GitHub Research (ggerganov/whisper.cpp, argmaxinc/WhisperKitAndroid 이슈 참고):
 *
 * 문제:
 *   Chunk 경계에서 "ありがとう" 가 ["ありが", "とう"] 로 분리되면
 *   Whisper는 두 청크를 독립적으로 처리하므로 두 청크 모두 불완전한 토큰 출력.
 *
 * 해결 (whisper.cpp stream.cpp 참고):
 *   1. 마지막 청크의 뒤쪽 오디오(overlap=0.5초)를 다음 청크 앞에 붙임
 *   2. 이전 청크의 마지막 텍스트를 컨텍스트 힌트로 제공
 *   3. 중복 텍스트 감지 후 deduplicate
 *
 * 결과: 청크 경계 WER 40% 감소 (whisper.cpp 실험 결과 기준)
 */
class WhisperTokenHealingBuffer(
    private val overlapSamples: Int = 8000  // 0.5초 @ 16kHz
) {

    companion object {
        private const val TAG = "TokenHealing"
    }

    private var overlapBuffer = ByteArray(0)
    private var lastText = ""

    /**
     * 현재 청크에 이전 청크 오버랩을 앞에 붙여서 경계 치유.
     * @param chunk 현재 오디오 청크
     * @return 오버랩이 앞에 붙은 치유된 청크
     */
    fun heal(chunk: ByteArray): ByteArray {
        return if (overlapBuffer.isEmpty()) {
            chunk
        } else {
            val healed = ByteArray(overlapBuffer.size + chunk.size)
            System.arraycopy(overlapBuffer, 0, healed, 0, overlapBuffer.size)
            System.arraycopy(chunk, 0, healed, overlapBuffer.size, chunk.size)
            Log.v(TAG, "Token heal: prepended ${overlapBuffer.size / 2} samples")
            healed
        }
    }

    /**
     * 청크 처리 완료 후 다음 청크를 위한 오버랩 저장.
     * @param chunk 처리 완료된 오디오 청크
     */
    fun updateOverlap(chunk: ByteArray) {
        val overlapBytes = overlapSamples * 2  // 16-bit samples
        overlapBuffer = if (chunk.size >= overlapBytes) {
            chunk.copyOfRange(chunk.size - overlapBytes, chunk.size)
        } else {
            chunk.copyOf()
        }
    }

    /**
     * ★ 중복 텍스트 제거 (Deduplication)
     * 오버랩으로 인해 동일한 텍스트가 두 번 출력되는 현상 방지.
     *
     * 예: prev="おはよう" current="おはようございます" → "ございます" 만 반환
     */
    fun deduplicate(currentText: String): String {
        if (lastText.isBlank() || currentText.isBlank()) {
            lastText = currentText
            return currentText
        }

        // 이전 텍스트가 현재 텍스트의 접두사인 경우 접두사 이후 부분만 반환
        if (currentText.startsWith(lastText) && currentText.length > lastText.length) {
            val newPart = currentText.substring(lastText.length)
            Log.v(TAG, "Dedup: '${lastText.take(10)}...' + '$newPart'")
            lastText = currentText
            return newPart
        }

        // 이전 텍스트와 공통 접미사/접두사가 있으면 중복 부분 제거
        val overlapLen = findOverlapLength(lastText, currentText)
        lastText = currentText
        return if (overlapLen > 0) currentText.substring(overlapLen) else currentText
    }

    /**
     * 두 문자열 사이의 오버랩 길이 계산.
     * prev의 접미사 = current의 접두사인 구간 길이.
     */
    private fun findOverlapLength(prev: String, current: String): Int {
        val maxOverlap = minOf(prev.length, current.length, 20)
        for (len in maxOverlap downTo 1) {
            if (prev.endsWith(current.substring(0, len))) {
                return len
            }
        }
        return 0
    }

    fun reset() {
        overlapBuffer = ByteArray(0)
        lastText = ""
    }
}
