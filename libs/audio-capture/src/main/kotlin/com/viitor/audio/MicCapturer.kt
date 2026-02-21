package com.viitor.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.viitor.audio.domain.AudioChunk
import java.io.Closeable
import kotlin.concurrent.thread

/**
 * ★ MicCapturer: 고성능 마이크 오디오 기록기
 * - 16000Hz, 16bit, Mono 고정 (STT 최적화)
 * - 40ms~100ms 단위 실시간 덩어리 수집
 */
class MicCapturer(
    private val sampleRate: Int = 16000,
    private val chunkMs: Int = 100
) : Closeable {
    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private var captureThread: Thread? = null

    companion object {
        private const val TAG = "MicCapturer"
    }

    @SuppressLint("MissingPermission")
    fun startCapture(onChunk: (AudioChunk) -> Unit) {
        if (isRunning) return
        
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, 
            AudioFormat.CHANNEL_IN_MONO, 
            AudioFormat.ENCODING_PCM_16BIT
        )
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            return
        }

        audioRecord?.startRecording()
        isRunning = true

        val bufferSize = (sampleRate * 2 * chunkMs / 1000)
        val buffer = ByteArray(bufferSize)

        captureThread = thread(name = "MicCaptureThread", priority = Thread.MAX_PRIORITY) {
            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    val data = buffer.copyOfRange(0, read)
                    onChunk(AudioChunk(data, (read.toLong() * 1000L) / (sampleRate * 2)))
                }
            }
        }
    }

    override fun close() {
        isRunning = false
        audioRecord?.apply {
            try { stop() } catch(e: Exception) {}
            release()
        }
        audioRecord = null
    }
}
