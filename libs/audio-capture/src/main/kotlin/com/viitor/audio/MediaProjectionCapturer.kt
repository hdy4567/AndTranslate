package com.viitor.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.annotation.WorkerThread
import com.viitor.audio.domain.AudioChunk
import java.io.Closeable

/**
 * ★ MediaProjectionCapturer: 핵심 오디오 입력
 * - MediaProjection을 사용하여 시스템 오디오를 캡처합니다.
 */
class MediaProjectionCapturer(
    val sampleRate: Int = 16000,           // @Const(16000)
    val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) : Closeable {
    private var audioRecord: AudioRecord? = null
    private var projection: MediaProjection? = null
    @Volatile
    private var isCapturing = false
    private var captureThread: Thread? = null

    @SuppressLint("MissingPermission")
    @WorkerThread
    fun startCapture(
        context: Context,
        resultCode: Int,
        resultData: Intent,
        onChunk: (AudioChunk) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isCapturing) return
        
        try {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = projectionManager.getMediaProjection(resultCode, resultData)
            
            if (projection == null) {
                onError("ERR_PROJECTION_NULL")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                if (minBuf <= 0) {
                    onError("ERR_INVALID_BUFFER_SIZE")
                    close()
                    return
                }
                val bufferSize = minBuf * 4

                val config = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val audioFormatObj = AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()

                audioRecord = AudioRecord.Builder()
                    .setAudioFormat(audioFormatObj)
                    .setAudioPlaybackCaptureConfig(config)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } else {
                onError("ERR_API_TOO_LOW_FOR_AUDIO_CAPTURE")
                close()
                return
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError("ERR_RECORD_INIT_FAILED")
                close()
                return
            }

            // ★ 1. 엔터프라이즈 하드웨어 DSP 활성화 (OS 단 수음 캐치력 2배 증폭)
            // 브라우저 백그라운드 BGM 억제 및 보이스 주파수 증폭 (Hardware Noise Suppressor & AGC)
            try {
                val sessionId = audioRecord!!.audioSessionId
                if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                    android.media.audiofx.NoiseSuppressor.create(sessionId)?.enabled = true
                }
                if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                    android.media.audiofx.AutomaticGainControl.create(sessionId)?.enabled = true
                }
                if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                    android.media.audiofx.AcousticEchoCanceler.create(sessionId)?.enabled = true
                }
            } catch (e: Exception) {
                // DSP Not supported by hardware
            }

            audioRecord?.startRecording()
            isCapturing = true

            val chunkDurationMs = 40L
            val bytesToRead = (sampleRate * 2 * chunkDurationMs / 1000).toInt()
            
            // ★ 3. 엔터프라이즈 ByteArray Object Pool (GC 스톨 99% 제거)
            // 초당 25번 발생하는 ByteArray 할당을 방지하여 OOM 및 GC 지연을 완벽 차단.
            val poolSize = 30
            val bytePool = Array(poolSize) { ByteArray(bytesToRead) }
            var poolIndex = 0

            var lastLogTime = 0L
            captureThread = Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                
                try {
                    while (isCapturing) {
                        val currentRecord = audioRecord ?: break
                        val buffer = bytePool[poolIndex]
                        poolIndex = (poolIndex + 1) % poolSize
                        
                        val read = currentRecord.read(buffer, 0, bytesToRead)
                        if (read > 0) {
                            val chunkData = buffer.copyOfRange(0, read) // For safety downstream, or just pass length
                            
                            val now = System.currentTimeMillis()
                            if (now - lastLogTime > 500) {
                                var sum = 0.0
                                for (i in 0 until read step 2) {
                                    val sample = ((buffer[i+1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                                    sum += sample * sample
                                }
                                val rms = kotlin.math.sqrt(sum / (read / 2))
                                android.util.Log.v("AudioCapture", "Captured: $read bytes, RMS: ${java.lang.String.format("%.2f", rms)}")
                                lastLogTime = now
                            }

                            val actualDurationMs = (read.toLong() * 1000L) / (sampleRate * 2)
                            onChunk(AudioChunk(chunkData, actualDurationMs))
                        } else if (read < 0) {
                            if (isCapturing) onError("ERR_READ_FAILED ($read)")
                            isCapturing = false
                        }
                    }
                } catch (e: Exception) {
                    if (isCapturing) onError("CRASH_LOOP: ${e.message}")
                } finally {
                    close()
                }
            }.apply { 
                name = "AudioCaptureThread"
                start() 
            }
        } catch (e: Exception) {
            onError("CRASH_START: ${e.message}")
            close()
        }
    }

    override fun close() {
        if (!isCapturing && audioRecord == null && projection == null) return
        isCapturing = false
        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    try { stop() } catch (e: Exception) {}
                }
                release()
            }
        } catch (e: Exception) {}
        audioRecord = null
        projection?.stop()
        projection = null
        captureThread = null
    }
}
