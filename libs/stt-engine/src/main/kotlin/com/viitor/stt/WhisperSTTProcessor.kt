package com.viitor.stt

import android.content.Context
import android.util.Log
import com.viitor.stt.domain.TranscriptionMode
import com.viitor.stt.util.SubtitleForensicAgent
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.*

/**
 * ★ WhisperSTTProcessor: Enterprise Grade Whisper Tiny Implementation
 *  - High-precision Mel Spectrogram with Hanning Window
 *  - Thread-safe Inference Engine
 *  - Self-Monitoring & Forensic Pipeline
 */
class WhisperSTTProcessor(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "WhisperSTT"
        private const val SAMPLE_RATE = 16000
        private const val N_MELS = 80
        private const val N_FRAMES = 3000
        private const val N_FFT = 400
        private const val HOP_LENGTH = 160
        private const val VOCAB_SIZE = 51865
        private const val RESULT_NOT_READY = "[NOT_READY]"
    }

    private var interpreter: Interpreter? = null
    private var isPunctured = false

    private val melFilterbank: Array<FloatArray> by lazy {
        initMelFilterbank(SAMPLE_RATE, N_FFT, N_MELS)
    }

    fun loadModel(modelFile: String, language: String = "ja"): Result<Unit> {
        return try {
            WhisperVocabulary.init(context)
            val fd = context.assets.openFd(modelFile)
            val fis = FileInputStream(fd.fileDescriptor)
            val buf = fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            val options = Interpreter.Options().apply {
                setNumThreads(2)
                setUseXNNPACK(true)
            }
            interpreter = Interpreter(buf, options)
            Log.d(TAG, "Model loaded: $modelFile")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Load failure: ${e.message}")
            Result.failure(e)
        }
    }

    private val inferenceLock = java.util.concurrent.locks.ReentrantLock()
    private var cachedMelBuf: ByteBuffer? = null

    fun transcribe(audioData: ByteArray, mode: TranscriptionMode, promptText: String = "", language: String = "ja"): String {
        if (SubtitleForensicAgent.isPunished) {
            return "[WHISPER-PUNISHED:ENGINE-STALL]"
        }

        val interp = interpreter ?: return RESULT_NOT_READY
        
        if (!inferenceLock.tryLock(5000, java.util.concurrent.TimeUnit.MILLISECONDS)) return RESULT_NOT_READY

        try {
            val samples = pcm16ToFloat(audioData)
            if (samples.isEmpty()) return ""
            
            val (mel, activeFrames) = computeLogMelSpectrogramWithMeta(samples)
            if (activeFrames <= 0) return ""

            val tokens = runInference(interp, mel, 448)
            val decoded = WhisperVocabulary.decode(tokens)
            
            if (decoded.isNotBlank()) {
                if (!isPunctured) {
                    isPunctured = true
                    Log.i(TAG, "[Engine-Puncture] SUCCESS! Data flowing.")
                }
            }
            SubtitleForensicAgent.recordInference(mode, tokens, decoded)
            return decoded
        } catch (e: Exception) {
            Log.e(TAG, "Transcribe crash: ${e.message}")
            return ""
        } finally {
            inferenceLock.unlock()
        }
    }

    private fun pcm16ToFloat(audioData: ByteArray): FloatArray {
        val samples = FloatArray(audioData.size / 2)
        for (i in samples.indices) {
            val s = (audioData[i * 2].toInt() and 0xFF) or (audioData[i * 2 + 1].toInt() shl 8)
            samples[i] = s.toFloat() / 32768.0f
        }
        return samples
    }

    private fun computeLogMelSpectrogramWithMeta(samples: FloatArray): Pair<FloatArray, Int> {
        val mel = FloatArray(N_MELS * N_FRAMES)
        val frames = min(N_FRAMES, (samples.size - N_FFT) / HOP_LENGTH + 1)
        if (frames <= 0) return Pair(mel, 0)

        val fftSize = N_FFT / 2 + 1
        val window = FloatArray(N_FFT) { i ->
            (0.5f * (1 - cos(2 * PI * i / N_FFT))).toFloat()
        }

        for (i in 0 until frames) {
            val offset = i * HOP_LENGTH
            val frame = FloatArray(N_FFT) { j ->
                if (offset + j < samples.size) samples[offset + j] * window[j] else 0f
            }
            
            val (real, imag) = fft(frame)
            val power = FloatArray(fftSize) { j ->
                real[j] * real[j] + imag[j] * imag[j]
            }

            for (m in 0 until N_MELS) {
                var energy = 0f
                for (j in 0 until fftSize) {
                    energy += power[j] * melFilterbank[m][j]
                }
                val logMel = ln(max(energy, 1e-10f))
                mel[m * N_FRAMES + i] = logMel
            }
        }
        return Pair(mel, frames)
    }

    private fun fft(input: FloatArray): Pair<FloatArray, FloatArray> {
        val n = input.size
        val real = FloatArray(n)
        val imag = FloatArray(n)
        for (k in 0 until n) {
            var r = 0f
            var im = 0f
            for (i in 0 until n) {
                val angle = (2 * PI * k * i / n)
                r += (input[i] * cos(angle)).toFloat()
                im -= (input[i] * sin(angle)).toFloat()
            }
            real[k] = r
            imag[k] = im
        }
        return Pair(real, imag)
    }

    private fun initMelFilterbank(sampleRate: Int, nFft: Int, nMels: Int): Array<FloatArray> {
        val minFreq = 0.0
        val maxFreq = sampleRate / 2.0
        val minMel = freqToMel(minFreq)
        val maxMel = freqToMel(maxFreq)
        
        val melPts = DoubleArray(nMels + 2) { i ->
            melToFreq(minMel + i * (maxMel - minMel) / (nMels + 1))
        }
        
        val binPts = IntArray(nMels + 2) { i ->
            (floor((nFft + 1) * melPts[i] / sampleRate)).toInt()
        }
        
        val filterbank = Array(nMels) { i ->
            FloatArray(nFft / 2 + 1) { j ->
                val f = j
                when {
                    f < binPts[i] -> 0f
                    f <= binPts[i+1] -> (f - binPts[i]).toFloat() / (binPts[i+1] - binPts[i])
                    f <= binPts[i+2] -> (binPts[i+2] - f).toFloat() / (binPts[i+2] - binPts[i+1])
                    else -> 0f
                }
            }
        }
        return filterbank
    }

    private fun freqToMel(f: Double): Double = 2595.0 * log10(1.0 + f / 700.0)
    private fun melToFreq(m: Double): Double = 700.0 * (10.0.pow(m / 2595.0) - 1.0)

    private fun runInference(interp: Interpreter, mel: FloatArray, maxLen: Int): IntArray {
        val input = getMelBuf().apply { rewind(); asFloatBuffer().put(mel) }
        val outputBuf = ByteBuffer.allocateDirect(maxLen * 4).order(ByteOrder.nativeOrder())
        interp.runForMultipleInputsOutputs(arrayOf(input), mapOf(0 to outputBuf))
        val output = IntArray(maxLen)
        outputBuf.rewind(); outputBuf.asIntBuffer().get(output)
        return output
    }

    private fun getMelBuf(): ByteBuffer {
        return cachedMelBuf ?: ByteBuffer.allocateDirect(N_MELS * N_FRAMES * 4).order(ByteOrder.nativeOrder()).also { cachedMelBuf = it }
    }

    override fun close() { interpreter?.close() }
    fun isReady(): Boolean = interpreter != null
}
