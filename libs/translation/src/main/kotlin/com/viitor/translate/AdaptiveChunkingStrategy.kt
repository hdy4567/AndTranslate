package com.viitor.translate

import android.util.Log

/**
 * ★ AdaptiveChunkingStrategy: 발화 속도 기반 동적 청크 크기 조절
 *
 * 📌 연구 출처: WhisperLive 스트리밍 최적화, ggerganov/whisper.cpp stream.cpp
 *
 * 문제:
 *   - 고정 40ms 청크: 빠른 발화(일본어 평균 7~8모라/초)에서 단어 손실
 *   - 너무 큰 청크: 실시간성 저하
 *
 * 해결: 실시간 Speech Rate 측정 → 청크 크기 자동 조절
 *   빠른 발화 → 더 작은 청크 (20ms) → 더 잦은 전송 → 낮은 레이턴시
 *   느린 발화 → 더 큰 청크 (80ms) → 더 빠른 배터리
 *   침묵 구간 → 최대 청크 (100ms) → 최소 CPU
 *
 * 알고리즘:
 *   Speech Rate = (최근 1초 내 유성 프레임 수) / (총 프레임 수)
 *   Target Chunk = BASE_CHUNK × (1 / (1 + speech_rate × AGGRESSIVENESS))
 */
class AdaptiveChunkingStrategy(
    private val baseChunkMs: Int = 40,
    private val minChunkMs:  Int = 20,
    private val maxChunkMs:  Int = 100
) {

    companion object {
        private const val TAG = "AdaptiveChunking"
        private const val WINDOW_FRAMES = 25       // 1초 = 25 frames (40ms 기준)
        private const val AGGRESSIVENESS = 2.0f    // 청크 크기 조절 공격성
    }

    private val speechHistory = ArrayDeque<Boolean>(WINDOW_FRAMES)
    private var currentChunkMs = baseChunkMs
    private var adjustmentCount = 0

    /**
     * ★ 새 VAD 결과를 받아 다음 청크 크기 결정
     */
    fun update(isSpeech: Boolean): Int {
        speechHistory.addLast(isSpeech)
        if (speechHistory.size > WINDOW_FRAMES) speechHistory.removeFirst()

        val speechRate = if (speechHistory.isEmpty()) 0f
                         else speechHistory.count { it }.toFloat() / speechHistory.size

        val targetChunk = when {
            speechRate > 0.85f -> minChunkMs          // 매우 빠른 발화
            speechRate > 0.60f -> (baseChunkMs * 0.7).toInt()  // 빠른 발화
            speechRate > 0.30f -> baseChunkMs          // 보통 발화
            speechRate > 0.10f -> (baseChunkMs * 1.5).toInt()  // 느린 발화
            else               -> maxChunkMs           // 침묵
        }.coerceIn(minChunkMs, maxChunkMs)

        // 급격한 변화 방지 (부드러운 전환)
        val prevChunk = currentChunkMs
        currentChunkMs = ((currentChunkMs * 0.7 + targetChunk * 0.3)).toInt().coerceIn(minChunkMs, maxChunkMs)

        if (currentChunkMs != prevChunk) {
            adjustmentCount++
            if (adjustmentCount % 10 == 0) {
                Log.v(TAG, "Chunk adjusted: ${prevChunk}ms → ${currentChunkMs}ms (speechRate=${"%.2f".format(speechRate)})")
            }
        }

        return currentChunkMs
    }

    fun getCurrentChunkMs() = currentChunkMs
    fun getAdjustmentCount() = adjustmentCount

    fun reset() {
        speechHistory.clear()
        currentChunkMs = baseChunkMs
    }
}
