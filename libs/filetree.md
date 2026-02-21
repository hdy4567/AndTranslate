libs/audio-capture/src/main/kotlin/com/viitor/audio/
├── MediaProjectionCapturer.kt                     # ★ 핵심 오디오 입력
│   // @RequiresPermission(RECORD_AUDIO)
│   // class MediaProjectionCapturer(
│   //     val sampleRate: Int = 16000,           // @Const(16000)
│   //     val channelConfig: Int = CHANNEL_IN_MONO,
│   //     val audioFormat: Int = ENCODING_PCM_16BIT
│   // ) : Closeable {
│   //     private var audioRecord: AudioRecord? = null
│   //     private var projection: MediaProjection? = null
│   //     
│   //     @WorkerThread
│   //     fun startCapture(resultIntent: Intent, onChunk: (AudioChunk) -> Unit)
│   //     
│   //     override fun close() { /* release() in finally */ }
│   // }
│
├── VoiceActivityDetector.kt                       # ★ RMS 기반 VAD
│   // class VoiceActivityDetector(config: VadConfig) {
│   //     fun detectVoiceActivity(chunk: AudioChunk): Boolean {
│   //         val rms = calculateRMS(chunk.data)
│   //         return rms > config.thresholdDb  // -40dB
│   //     }
│   // }
│
├── SilenceDetector.kt                             # ★ NEW - 침묵 이벤트 발화
│   // class SilenceDetector(
│   //     val silenceThresholdMs: Long = 500,   // @SilenceThreshold(min=500ms)
│   //     val thresholdDb: Float = -40f
│   // ) {
│   //     private var consecutiveSilenceMs = 0L
│   //     
│   //     fun onChunk(chunk: AudioChunk, rms: Float): SilenceEvent? {
│   //         if (rms < thresholdDb) {
│   //             consecutiveSilenceMs += chunk.durationMs
│   //             if (consecutiveSilenceMs >= silenceThresholdMs) {
│   //                 consecutiveSilenceMs = 0L
│   //                 return SilenceEvent(isEndOfSentence = true)
│   //             }
│   //         } else {
│   //             consecutiveSilenceMs = 0L
│   //         }
│   //         return null
│   //     }
│   // }
│
├── AudioChunkBuffer.kt                            # ★ 수정 - Silence 기반 배치 추출
│   // class AudioChunkBuffer(
│   //     private val maxSize: Int = 3         // @Size(max=3) + DROP_OLDEST
│   // ) {
│   //     private val queue = ArrayDeque<AudioChunk>(maxSize)
│   //     
│   //     @Synchronized
│   //     fun writeChunk(chunk: AudioChunk) { /* DROP_OLDEST 로직 */ }
│   //     
│   //     @Synchronized
│   //     fun getLatestChunk(): AudioChunk? = queue.lastOrNull()
│   //     
│   //     @Synchronized
│   //     fun extractBatchForSilence(
│   //         maxBatchSize: Int = 4,            // @MaxBatchSize(4)
│   //         lookbackSec: Int = 6
│   //     ): List<AudioChunk> {
│   //         val batch = queue.takeLast(maxBatchSize).toList()
│   //         queue.clear()  // 배치 추출 후 큐 비우기
│   //         return batch
│   //     }
│   // }
│
└── domain/
    ├── AudioChunk.kt
    │   // data class AudioChunk(
    │   //     val data: ByteArray,               // PCM 16bit
    │   //     val durationMs: Long               // 1500ms (청크 길이)
    │   // )
    │
    ├── SilenceEvent.kt                            # ★ NEW
    │   // data class SilenceEvent(
    │   //     val isEndOfSentence: Boolean       // true: 배치 STT 트리거
    │   // )
    │
    ├── VadConfig.kt
    │   // data class VadConfig(
    │   //     val thresholdDb: Float = -40f,     // @SilenceThreshold(rms=-40dB)
    │   //     val minSilenceMs: Long = 500       // @SilenceThreshold(min=500ms)
    │   // )
    │
    └── BatchConfig.kt                             # ★ NEW
        // data class BatchConfig(
        //     val maxBatchSize: Int = 4,         // @MaxBatchSize(4)
        //     val lookbackSec: Int = 6           // 6초 이내 청크만 배치 구성
        // )
