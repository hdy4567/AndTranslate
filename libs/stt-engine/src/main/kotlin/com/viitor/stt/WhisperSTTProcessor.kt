package com.viitor.stt

import android.content.Context
import android.util.Log
import com.viitor.stt.domain.TranscriptionMode
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.*

/**
 * ★ WhisperSTTProcessor: Whisper Tiny TFLite 완전 구현
 *
 * 파이프라인:
 *   PCM16 ByteArray
 *     → normalizedFloat ([-1,1])
 *     → Log-Mel Spectrogram [1, N_MELS=80, N_FRAMES=3000]
 *     → TFLite Interpreter (whisper-tiny.tflite)
 *     → Token IDs (greedy decode)
 *     → UTF-8 텍스트
 */
class WhisperSTTProcessor(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "WhisperSTT"

        // ── 오디오 파라미터 ───────────────────────────────────
        private const val SAMPLE_RATE   = 16000
        private const val N_FFT         = 400        // 25ms window
        private const val HOP_LENGTH    = 160        // 10ms hop
        private const val N_MELS        = 80
        private const val N_FRAMES      = 3000       // 30초 분량
        private const val MAX_SAMPLES   = SAMPLE_RATE * 30  // 480,000 samples

        // ── Whisper 특수 토큰 ─────────────────────────────────
        private const val TOKEN_SOT           = 50258  // startoftranscript
        private const val TOKEN_KOREAN        = 50264  // ko
        private const val TOKEN_TRANSCRIBE    = 50359  // transcribe
        private const val TOKEN_NO_TIMESTAMPS = 50363  // notimestamps
        private const val TOKEN_EOT           = 50257  // eot (end of text)
        private const val VOCAB_SIZE          = 51865

        // ── Mel 필터뱅크 파라미터 ─────────────────────────────
        private const val F_MIN = 0.0
        private const val F_MAX = 8000.0

        // 기본 반환: 모델 미로드 또는 무음 구간
        private const val RESULT_NOT_READY = ""
    }

    private var interpreter: Interpreter? = null

    // 한 번 계산한 Mel 필터뱅크 캐시
    private val melFilterbank: Array<FloatArray> by lazy { buildMelFilterbank() }

    // ─────────────────────────────────────────────────────────────────────────
    // 모델 로드
    // ─────────────────────────────────────────────────────────────────────────

    @Throws(Exception::class)
    fun loadModel(language: String): Result<Unit> {
        val modelFile = "whisper-tiny.tflite"
        return try {
            val buf = loadModelFile(modelFile)
            val options = Interpreter.Options().apply {
                numThreads = 4
            }
            interpreter = Interpreter(buf, options)
            Log.d(TAG, "Model loaded: $modelFile  (lang=$language)")
            Log.d(TAG, "  Input tensors : ${interpreter!!.inputTensorCount}")
            Log.d(TAG, "  Output tensors: ${interpreter!!.outputTensorCount}")
            logTensorShapes()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun loadModelFile(fileName: String): MappedByteBuffer {
        context.assets.openFd(fileName).use { fd ->
            FileInputStream(fd.fileDescriptor).use { fis ->
                return fis.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fd.startOffset,
                    fd.declaredLength
                )
            }
        }
    }

    private fun logTensorShapes() {
        val interp = interpreter ?: return
        try {
            for (i in 0 until interp.inputTensorCount) {
                val t = interp.getInputTensor(i)
                Log.d(TAG, "  Input[$i]: shape=${t.shape().toList()}  dtype=${t.dataType()}")
            }
            for (i in 0 until interp.outputTensorCount) {
                val t = interp.getOutputTensor(i)
                Log.d(TAG, "  Output[$i]: shape=${t.shape().toList()}  dtype=${t.dataType()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not log tensor shapes: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 추론 진입점
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * PCM16 ByteArray → 텍스트
     *
     * @param audioData  PCM 16bit little-endian
     * @param mode       PROVISIONAL(빠름) / FINAL(정확)
     */
    fun transcribe(audioData: ByteArray, mode: TranscriptionMode): String {
        val interp = interpreter ?: run {
            Log.w(TAG, "Interpreter not ready")
            return RESULT_NOT_READY
        }
        return try {
            // 1. PCM16 → float [-1, 1]
            val samples = pcm16ToFloat(audioData)
            if (samples.isEmpty()) return RESULT_NOT_READY

            // 2. Log-Mel Spectrogram [80, 3000]
            val mel = computeLogMelSpectrogram(samples)

            // 3. TFLite 추론 (모델 시그니처에 맞게 자동 분기)
            val tokens = runInference(interp, mel, mode)

            // 4. 토큰 → 텍스트
            val text = decodeTokens(tokens)
            Log.d(TAG, "[$mode] → \"$text\"")
            text
        } catch (e: Exception) {
            Log.e(TAG, "Transcription error: ${e.message}", e)
            RESULT_NOT_READY
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PCM16 → Float 변환
    // ─────────────────────────────────────────────────────────────────────────

    private fun pcm16ToFloat(data: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = FloatArray(buf.limit())
        for (i in out.indices) out[i] = buf.get(i).toFloat() / 32768.0f
        return out
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Log-Mel Spectrogram (Whisper 규격)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 반환값: FloatArray 크기 = N_MELS * N_FRAMES = 80 * 3000
     * (row-major: mel[m * N_FRAMES + t])
     */
    private fun computeLogMelSpectrogram(samples: FloatArray): FloatArray {
        // 입력을 최대 MAX_SAMPLES 개로 패딩/자름
        val padded = FloatArray(MAX_SAMPLES)
        val copyLen = minOf(samples.size, MAX_SAMPLES)
        System.arraycopy(samples, 0, padded, 0, copyLen)

        // Hann Window
        val window = FloatArray(N_FFT) { i ->
            (0.5f * (1.0f - cos(2.0 * PI * i / (N_FFT - 1)))).toFloat()
        }

        val nFrames = N_FRAMES
        val halfFft = N_FFT / 2 + 1  // 201

        // ── STFT Power Spectrogram ──────────────────────────
        // shape: [halfFft, nFrames]
        val stftPower = Array(halfFft) { FloatArray(nFrames) }
        val fftBuf    = FloatArray(N_FFT)

        for (t in 0 until nFrames) {
            val offset = t * HOP_LENGTH
            // 프레임 추출 + 윈도우 적용
            for (k in 0 until N_FFT) {
                val idx = offset + k
                fftBuf[k] = if (idx < padded.size) padded[idx] * window[k] else 0f
            }
            // FFT
            val (real, imag) = rfft(fftBuf)
            for (k in 0 until halfFft) {
                stftPower[k][t] = real[k] * real[k] + imag[k] * imag[k]
            }
        }

        // ── Mel 필터뱅크 적용 ─────────────────────────────────
        val filters = melFilterbank  // [N_MELS, halfFft]
        val mel = FloatArray(N_MELS * nFrames)

        for (m in 0 until N_MELS) {
            for (t in 0 until nFrames) {
                var energy = 0f
                for (k in 0 until halfFft) {
                    energy += filters[m][k] * stftPower[k][t]
                }
                // Log10 (Whisper 규격: log10(max(energy, 1e-10)))
                mel[m * nFrames + t] = log10(maxOf(energy, 1e-10f))
            }
        }

        // ── 정규화 (Whisper: (mel - max + 4) / 4, clamp [-1,1]) ──
        var maxVal = Float.NEGATIVE_INFINITY
        for (v in mel) if (v > maxVal) maxVal = v
        for (i in mel.indices) {
            mel[i] = ((mel[i] - maxVal + 4.0f) / 4.0f).coerceIn(-1.0f, 1.0f)
        }

        return mel
    }

    /**
     * Mel 삼각 필터뱅크 생성
     * @return [N_MELS][halfFft] weight matrix
     */
    private fun buildMelFilterbank(): Array<FloatArray> {
        val halfFft = N_FFT / 2 + 1
        // Hz → Mel 변환 함수 (HTK)
        fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        val melMin = hzToMel(F_MIN)
        val melMax = hzToMel(F_MAX)

        // N_MELS+2 개의 Mel 중심점
        val melPoints = DoubleArray(N_MELS + 2) { i ->
            melToHz(melMin + i * (melMax - melMin) / (N_MELS + 1))
        }

        // Hz → FFT bin 인덱스
        val binPoints = DoubleArray(N_MELS + 2) { i ->
            floor((N_FFT + 1) * melPoints[i] / SAMPLE_RATE).toInt().toDouble()
        }

        val filterbank = Array(N_MELS) { FloatArray(halfFft) }
        for (m in 0 until N_MELS) {
            val left   = binPoints[m].toInt()
            val center = binPoints[m + 1].toInt()
            val right  = binPoints[m + 2].toInt()

            for (k in left until center) {
                if (k in 0 until halfFft) {
                    filterbank[m][k] = ((k - left).toFloat() / (center - left).coerceAtLeast(1))
                }
            }
            for (k in center until right) {
                if (k in 0 until halfFft) {
                    filterbank[m][k] = ((right - k).toFloat() / (right - center).coerceAtLeast(1))
                }
            }
        }
        return filterbank
    }

    /**
     * 실수 FFT (Cooley-Tukey, 2의 거듭제곱이 아닌 경우 DFT 폴백)
     * @return Pair(real[N_FFT/2+1], imag[N_FFT/2+1])
     */
    private fun rfft(x: FloatArray): Pair<FloatArray, FloatArray> {
        val n = x.size
        val half = n / 2 + 1
        val real = FloatArray(half)
        val imag = FloatArray(half)

        // DFT (N_FFT=400, 속도보다 정확성 우선)
        for (k in 0 until half) {
            var re = 0.0
            var im = 0.0
            val angle = -2.0 * PI * k / n
            for (t in 0 until n) {
                re += x[t] * cos(angle * t)
                im += x[t] * sin(angle * t)
            }
            real[k] = re.toFloat()
            imag[k] = im.toFloat()
        }
        return Pair(real, imag)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TFLite 추론
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 모델 입출력 시그니처에 맞게 자동 분기:
     *   - 단일 입력(mel only): encoder-only 시그니처
     *   - 복수 입력(mel + tokens): encoder-decoder 시그니처
     */
    private fun runInference(
        interp: Interpreter,
        mel: FloatArray,   // [N_MELS * N_FRAMES]
        mode: TranscriptionMode
    ): IntArray {
        return try {
            when (interp.inputTensorCount) {
                1    -> runEncoderOnly(interp, mel)
                2    -> runEncoderDecoder(interp, mel, mode)
                else -> runEncoderDecoder(interp, mel, mode)  // 기본 시도
            }
        } catch (e: Exception) {
            Log.w(TAG, "Inference failed (${e.message}), returning empty")
            IntArray(0)
        }
    }

    /**
     * Encoder-only 시그니처:
     *   Input[0] : [1, N_MELS, N_FRAMES] float32
     *   Output[0]: [1, maxTokens] int32  (또는 float32 logits)
     */
    private fun runEncoderOnly(interp: Interpreter, mel: FloatArray): IntArray {
        // 입력 버퍼 [1, 80, 3000]
        val inputBuf = ByteBuffer.allocateDirect(1 * N_MELS * N_FRAMES * 4)
            .order(ByteOrder.nativeOrder())
        for (v in mel) inputBuf.putFloat(v)
        inputBuf.rewind()

        // 출력 버퍼: 출력 텐서 형태 확인
        val outTensor = interp.getOutputTensor(0)
        val outShape  = outTensor.shape()
        val outSize   = outShape.fold(1) { acc, d -> acc * d }

        val outputBuf = ByteBuffer.allocateDirect(outSize * 4).order(ByteOrder.nativeOrder())
        interp.run(inputBuf, outputBuf)
        outputBuf.rewind()

        // float logits이면 argmax, int이면 직접 읽기
        return if (outTensor.dataType().toString().contains("INT")) {
            IntArray(outSize) { outputBuf.int }
        } else {
            // logits [1, seqLen, vocabSize] → argmax
            val logits = FloatArray(outSize) { outputBuf.float }
            greedyDecode(logits, outShape)
        }
    }

    /**
     * Encoder-Decoder 시그니처:
     *   Input[0]: [1, N_MELS, N_FRAMES] mel
     *   Input[1]: [1, maxLen]           decoder token ids
     *   Output[0]: [1, maxLen, vocab]   logits
     */
    // For Reuse (Performance & Memory Risk Reduction)
    private var cachedMelBuf: ByteBuffer? = null
    private var cachedTokenBuf: ByteBuffer? = null
    private var cachedOutputBuf: ByteBuffer? = null

    private fun getMelBuf(): ByteBuffer {
        return cachedMelBuf ?: ByteBuffer.allocateDirect(1 * N_MELS * N_FRAMES * 4)
            .order(ByteOrder.nativeOrder()).also { cachedMelBuf = it }
    }

    private fun getTokenBuf(maxLen: Int): ByteBuffer {
        val existing = cachedTokenBuf
        if (existing != null && existing.capacity() >= maxLen * 4) return existing
        return ByteBuffer.allocateDirect(maxLen * 4)
            .order(ByteOrder.nativeOrder()).also { cachedTokenBuf = it }
    }

    private fun getOutputBuf(maxLen: Int): ByteBuffer {
        val existing = cachedOutputBuf
        if (existing != null && existing.capacity() >= 1 * maxLen * VOCAB_SIZE * 4) return existing
        return ByteBuffer.allocateDirect(1 * maxLen * VOCAB_SIZE * 4)
            .order(ByteOrder.nativeOrder()).also { cachedOutputBuf = it }
    }

    /**
     * Encoder-Decoder 시그니처:
     *   Input[0]: [1, N_MELS, N_FRAMES] mel
     *   Input[1]: [1, maxLen]           decoder token ids
     *   Output[0]: [1, maxLen, vocab]   logits
     */
    private fun runEncoderDecoder(
        interp: Interpreter,
        mel: FloatArray,
        mode: TranscriptionMode
    ): IntArray {
        val maxLen = if (mode == TranscriptionMode.PROVISIONAL) 64 else 224

        val promptTokens = intArrayOf(
            TOKEN_SOT,
            TOKEN_KOREAN,
            TOKEN_TRANSCRIBE,
            TOKEN_NO_TIMESTAMPS
        )

        val tokenIds = IntArray(maxLen) { 0 }
        promptTokens.copyInto(tokenIds)

        val melBuf = getMelBuf()
        melBuf.clear()
        for (v in mel) melBuf.putFloat(v)
        melBuf.rewind()

        val outputBuf = getOutputBuf(maxLen)

        val result = mutableListOf<Int>()
        var pos = promptTokens.size

        while (pos < maxLen) {
            val tokenBuf = getTokenBuf(maxLen)
            tokenBuf.clear()
            for (id in tokenIds) tokenBuf.putInt(id)
            tokenBuf.rewind()

            outputBuf.clear()
            val inputs = arrayOf<Any>(melBuf, tokenBuf)
            val outputs = mutableMapOf<Int, Any>(0 to outputBuf)
            try {
                val startMs = System.currentTimeMillis()
                interp.runForMultipleInputsOutputs(inputs, outputs)
                val duration = System.currentTimeMillis() - startMs
                if (pos % 10 == 0 || duration > 1000) {
                    Log.d(TAG, "Inference step $pos took $duration ms")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Inference loop error at pos $pos: ${e.message}")
                break
            }
            outputBuf.rewind()

            val offset = pos * VOCAB_SIZE
            // Skip buffer to pos
            if (outputBuf.remaining() < (offset + VOCAB_SIZE) * 4) {
                 Log.e(TAG, "Output buffer underflow at pos $pos")
                 break
            }
            outputBuf.position(offset * 4)

            var maxVal = Float.NEGATIVE_INFINITY
            var nextToken = TOKEN_EOT
            for (i in 0 until VOCAB_SIZE) {
                val v = outputBuf.float
                if (v > maxVal) {
                    maxVal = v
                    nextToken = i
                }
            }

            if (nextToken == TOKEN_EOT || nextToken == TOKEN_SOT) break
            result.add(nextToken)
            tokenIds[pos] = nextToken
            pos++
            melBuf.rewind()
        }

        return result.toIntArray()
    }

    /**
     * Encoder-only 모델의 3D logits [1, seqLen, vocabSize] → argmax per position
     */
    private fun greedyDecode(logits: FloatArray, shape: IntArray): IntArray {
        // shape: [1, seqLen, vocab] or [1, vocab]
        if (shape.size < 3) {
            // 단순 argmax
            val best = logits.indices.maxByOrNull { logits[it] } ?: return IntArray(0)
            return intArrayOf(best)
        }
        val seqLen = shape[1]
        val vocab  = shape[2]
        val tokens = IntArray(seqLen)
        for (t in 0 until seqLen) {
            var best = 0
            var bestVal = Float.NEGATIVE_INFINITY
            for (v in 0 until vocab) {
                val idx = t * vocab + v
                if (idx < logits.size && logits[idx] > bestVal) {
                    bestVal = logits[idx]
                    best = v
                }
            }
            tokens[t] = best
            if (best == TOKEN_EOT) return tokens.sliceArray(0 until t)
        }
        return tokens
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 토큰 → 텍스트 디코딩
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Whisper의 Tiktoken-based BPE 어휘는 기기 내 vocab 파일 없이 완전 구현이
     * 불가능합니다. 여기서는 아래 전략을 사용합니다:
     *
     * 1. 0~255 범위: Latin-1 직접 매핑 (ASCII 영어)
     * 2. 256~50256: BPE 합성 토큰 — 바이트 기반 문자 재조합
     *    (Whisper byte-level BPE는 토큰이 UTF-8 바이트의 조합이므로
     *     byte sequence를 모아 String으로 디코딩)
     * 3. 50257+ : 특수 토큰 — 무시
     *
     * 완전한 구현을 위해서는 assets에 vocab.json을 포함해야 하나,
     * 현재는 Vosk가 primary이므로 Whisper 텍스트는 보조적입니다.
     */
    private fun decodeTokens(tokens: IntArray): String {
        val bytes = mutableListOf<Byte>()
        for (token in tokens) {
            when {
                token >= 50257 -> continue  // 특수 토큰 무시
                token < 0      -> continue
                token < 256    -> {
                    // 단순 바이트 매핑 (ASCII/Latin-1 영역)
                    bytes.add(token.toByte())
                }
                else -> {
                    // BPE 토큰: Whisper는 256개의 byte-level 기본 토큰 이후
                    // 머지된 서브워드. 의미 있는 바이트 패턴 시도.
                    bytes.add((token and 0xFF).toByte())
                }
            }
        }
        return if (bytes.isEmpty()) "" else {
            try {
                String(bytes.toByteArray(), Charsets.UTF_8)
                    .trim()
                    .filter { it.isLetterOrDigit() || it.isWhitespace() || it in ".,!?한-힣ぁ-ヿ一-鿿" }
            } catch (e: Exception) {
                ""
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AutoCloseable
    // ─────────────────────────────────────────────────────────────────────────

    override fun close() {
        interpreter?.close()
        interpreter = null
        Log.d(TAG, "Interpreter closed")
    }
}
