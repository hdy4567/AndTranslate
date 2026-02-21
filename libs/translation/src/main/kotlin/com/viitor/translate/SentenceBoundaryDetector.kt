package com.viitor.translate

import android.util.Log

/**
 * ★ SentenceBoundaryDetector: 부분 문장이 번역 가능한 단위인지 판단
 *
 * KO 번역 실시간성 핵심:
 * 문장 전체가 끝나길 기다리지 않고, 의미 단위(Chunk)가 완성된 직후 바로 번역 트리거.
 *
 * 전략:
 * 1. 종결 패턴 감지: 일본어 문말 표현(です, ます, だ, だよ, ね, よ 등)
 * 2. 구두점: 。！？、 등 JP 문장 부호
 * 3. 길이 기반 강제 트리거: 40자 이상 쌓이면 강제 번역 실행
 */
object SentenceBoundaryDetector {

    private const val TAG = "SBD"
    private const val FORCE_TRANSLATE_LENGTH = 40

    // 일본어 문말 표현 패턴 (문장 완성 신호)
    private val JP_SENTENCE_END = listOf(
        "です", "ます", "だ。", "ね。", "よ。", "か。",
        "た。", "で。", "ない。", "ました", "ません",
        "た！", "た？", "。", "！", "？"
    )

    // 한국어 문말 표현 패턴
    private val KO_SENTENCE_END = listOf(
        "습니다", "입니다", "있어요", "없어요", "이에요",
        "할게요", "할까요", "네.", "요.", "다.", "까?"
    )

    /**
     * 주어진 텍스트가 의미 단위 완성인지 판단.
     * @return true면 즉시 번역 트리거 권장
     */
    fun isSentenceComplete(text: String, lang: String = "ja"): Boolean {
        if (text.isBlank()) return false

        // 강제 트리거: 누적 텍스트가 너무 길면 강제로 번역
        if (text.length >= FORCE_TRANSLATE_LENGTH) {
            Log.v(TAG, "Force trigger: length=${text.length}")
            return true
        }

        val patterns = if (lang == "ja") JP_SENTENCE_END else KO_SENTENCE_END
        val complete = patterns.any { text.trimEnd().endsWith(it) }
        if (complete) Log.v(TAG, "SBD complete: '${text.takeLast(10)}'")
        return complete
    }

    /**
     * 문장을 번역 가능한 청크 단위로 분할.
     * 예: "おはようございます。今日もよろしく！" → ["おはようございます。", "今日もよろしく！"]
     */
    fun splitToChunks(text: String): List<String> {
        val delimiters = Regex("[。！？.!?]")
        val parts = delimiters.split(text).filter { it.isNotBlank() }
        val matches = delimiters.findAll(text).map { it.value }.toList()
        
        return parts.mapIndexed { index, part ->
            if (index < matches.size) part + matches[index] else part
        }
    }
}
