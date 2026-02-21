package com.viitor.audio

import com.viitor.audio.domain.AudioChunk

/**
 * ★ AudioChunkBuffer: Silence 기반 배치 추출
 * - ERR_RACE_CONDITION: @Synchronized
 * - ERR_BUFFER_OVERFLOW: @Size(max=3) + DROP_OLDEST
 */
class AudioChunkBuffer(
    private val maxSize: Int = 1000 // 40ms 기준 40초 분량 (문장 1개 버퍼로는 충분함)
) {
    private val queue = ArrayDeque<AudioChunk>(maxSize)
    
    @Synchronized
    fun writeChunk(chunk: AudioChunk) { 
        if (queue.size >= maxSize) {
            queue.removeFirst()
        }
        queue.addLast(chunk)
    }
    
    @Synchronized
    fun getLatestChunk(): AudioChunk? = queue.lastOrNull()
    
    @Synchronized
    fun extractBatchForSilence(): List<AudioChunk> {
        val batch = queue.toList()
        queue.clear()
        return batch
    }

    @Synchronized
    fun clear() {
        queue.clear()
    }
}
