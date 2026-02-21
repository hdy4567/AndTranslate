package com.viitor.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AudioConverter: PCM 16bit ByteArray를 TFLite 모델용 FloatArray(정규화)로 변환
 */
object AudioConverter {
    
    /**
     * @param data PCM 16bit byte array
     * @return FloatArray normalized to -1.0 ~ 1.0
     */
    fun pcmToFloat(data: ByteArray): FloatArray {
        val shortBuffer = ByteBuffer.wrap(data)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        
        val floatArray = FloatArray(shortBuffer.limit())
        for (i in 0 until shortBuffer.limit()) {
            // Short.MAX_VALUE로 나누어 -1.0 ~ 1.0 사이로 정규화
            floatArray[i] = shortBuffer.get(i).toFloat() / 32768.0f
        }
        return floatArray
    }
}
