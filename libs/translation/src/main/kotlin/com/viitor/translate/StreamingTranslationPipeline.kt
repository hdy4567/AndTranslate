package com.viitor.translate

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.PriorityBlockingQueue

/**
 * ★ StreamingTranslationPipeline: Kotlin Flow 기반 실시간 스트리밍 번역 파이프라인
 *
 * 📌 연구 출처:
 *   - Picovoice Cheetah Streaming STT + Orca TTS 아키텍처
 *   - ML Kit Translation API 스트리밍 패턴
 *   - kotlinlang.org Flow 공식 문서 (2024)
 *
 * 아키텍처:
 *   STT Token → [TranslationRequest 큐] → [Priority Queue] → Flow<TranslationResult>
 *
 * 핵심 특성:
 *   1. 단어/구절 단위 스트리밍: 30자 이상 축적 OR 문장 경계 감지 시 즉시 번역 트리거
 *   2. 우선순위 큐: Final(우선순위 1) > Provisional(우선순위 2) → UI 스터터 제거
 *   3. Flow 기반: collect()로 구독하면 자동으로 번역 결과 실시간 수신
 *   4. 역압(Backpressure) 처리: BUFFER 전략으로 오버플로우 방지
 */
class StreamingTranslationPipeline {

    companion object {
        private const val TAG = "StreamingPipeline"
        private const val STREAM_TRIGGER_LENGTH = 25  // 이 글자 수 이상 쌓이면 즉시 번역
        private const val MAX_QUEUE_SIZE = 50
    }

    enum class TranslationPriority(val value: Int) {
        FINAL(1), PROVISIONAL(2), BACKGROUND(3)
    }

    data class TranslationRequest(
        val text: String,
        val sourceLang: String,
        val targetLang: String,
        val priority: TranslationPriority,
        val requestId: Long = System.nanoTime()
    ) : Comparable<TranslationRequest> {
        override fun compareTo(other: TranslationRequest): Int {
            return this.priority.value.compareTo(other.priority.value)
        }
    }

    data class TranslationResult(
        val original: String,
        val translated: String,
        val priority: TranslationPriority,
        val latencyMs: Long
    )

    // 우선순위 큐 (Final이 Provisional보다 먼저 처리됨)
    private val priorityQueue = PriorityBlockingQueue<TranslationRequest>(MAX_QUEUE_SIZE)
    private val resultChannel  = Channel<TranslationResult>(Channel.BUFFERED)

    private var pendingText = StringBuilder()
    private var lastFlushTime = 0L

    /**
     * ★ 텍스트 조각을 파이프라인에 투입
     * 충분히 쌓이거나 문장이 끝나면 자동으로 번역 큐에 투입
     */
    fun feed(
        text: String,
        priority: TranslationPriority = TranslationPriority.PROVISIONAL,
        sourceLang: String = "ja",
        targetLang: String = "ko"
    ) {
        pendingText.append(text)
        val now = System.currentTimeMillis()

        val shouldFlush = when {
            pendingText.length >= STREAM_TRIGGER_LENGTH                         -> true
            SentenceBoundaryDetector.isSentenceComplete(pendingText.toString()) -> true
            now - lastFlushTime > 2000 && pendingText.isNotBlank()              -> true  // 2초마다 강제 flush
            priority == TranslationPriority.FINAL                               -> true
            else                                                                 -> false
        }

        if (shouldFlush) {
            val chunk = pendingText.toString().trim()
            if (chunk.isNotBlank()) {
                val request = TranslationRequest(chunk, sourceLang, targetLang, priority)
                if (priorityQueue.size < MAX_QUEUE_SIZE) {
                    priorityQueue.offer(request)
                    Log.v(TAG, "Queued (${priority.name}, len=${chunk.length}): '${chunk.take(20)}'")
                } else {
                    Log.w(TAG, "Queue full! Dropping low-priority request.")
                }
                pendingText = StringBuilder()
                lastFlushTime = now
            }
        }
    }

    /**
     * ★ 번역 실행 Flow - suspend 번역 함수를 주입받아 실행
     * 호출 측: pipeline.translationFlow { text, src, tgt -> translate(text, src, tgt) }
     *           .collect { result -> updateUI(result) }
     */
    fun translationFlow(
        translateFn: suspend (String, String, String) -> String
    ): Flow<TranslationResult> = flow {
        while (true) {
            val request = priorityQueue.poll() ?: run {
                kotlinx.coroutines.delay(20)
                return@run null
            } ?: continue

            val startMs = System.currentTimeMillis()
            try {
                val translated = translateFn(request.text, request.sourceLang, request.targetLang)
                val latency = System.currentTimeMillis() - startMs

                if (translated.isNotBlank()) {
                    emit(TranslationResult(
                        original   = request.text,
                        translated = translated,
                        priority   = request.priority,
                        latencyMs  = latency
                    ))
                    Log.v(TAG, "Translated (${latency}ms, ${request.priority.name}): '${translated.take(25)}'")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Translation failed: ${e.message}")
            }
        }
    }

    /**
     * 큐에 FINAL 우선순위로 강제 flush
     */
    fun flushAsFinal(sourceLang: String = "ja", targetLang: String = "ko") {
        val text = pendingText.toString().trim()
        if (text.isNotBlank()) {
            priorityQueue.offer(TranslationRequest(text, sourceLang, targetLang, TranslationPriority.FINAL))
            pendingText = StringBuilder()
            lastFlushTime = System.currentTimeMillis()
        }
    }

    fun pendingCount() = priorityQueue.size
    fun clear() { priorityQueue.clear(); pendingText = StringBuilder() }
}
