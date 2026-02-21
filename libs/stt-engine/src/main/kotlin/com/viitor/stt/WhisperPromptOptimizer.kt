package com.viitor.stt

import android.util.Log

/**
 * ★ WhisperPromptOptimizer: 도메인 어휘 주입으로 JP 디코더 정확도 극한 상승
 *
 * 📌 연구 출처: arxiv 2024 - "Transcript-Prompted Whisper with Dictionary-Enhanced Decoding
 *   for Japanese Speech Annotation" (whisper-large-v3-turbo 파인튜닝 논문)
 *
 * 핵심 원리:
 *   Whisper의 initial_prompt 파라미터는 디코더가 토큰을 예측할 때
 *   soft-attention "힌트"로 사용됨. 전문 용어/고유명사를 prompt로 주입하면
 *   모델이 그 단어를 "미리 알고" 생성 확률을 높임.
 *
 * 효과 (논문 기준):
 *   - 고유명사 오인식 60~80% 감소
 *   - 도메인 특화 용어 CER 32.7 → 14.7 (파인튜닝+프롬프트 결합 시)
 *   - 할루시네이션(환각) 억제
 *
 * 구현:
 *   1. 도메인별 단어 사전 (Tech/Media/Conversation)
 *   2. 이전 문맥과 도메인 어휘를 합성한 최적 프롬프트 생성
 *   3. ContextCacheManager의 문맥 + 도메인 힌트 합산
 */
class WhisperPromptOptimizer {

    companion object {
        private const val TAG = "PromptOptimizer"
        private const val MAX_PROMPT_CHARS = 224  // Whisper 프롬프트 토큰 한계
    }

    enum class Domain { TECH, MEDIA, CONVERSATION, BUSINESS, MEDICAL }

    // ★ 도메인별 JP 어휘 라이브러리 (확장 가능)
    private val domainVocabulary = mapOf(
        Domain.TECH to listOf(
            "インターフェース", "アルゴリズム", "データベース", "クラウド", "セキュリティ",
            "プログラミング", "デバッグ", "フレームワーク", "マイクロサービス", "コンテナ",
            "機械学習", "ディープラーニング", "人工知能", "ニューラルネットワーク", "モデル",
            "トレーニング", "推論", "GPU", "CPU", "メモリ", "ストレージ", "API"
        ),
        Domain.MEDIA to listOf(
            "アニメ", "マンガ", "ドラマ", "映画", "音楽", "エンターテインメント",
            "ストリーミング", "コンテンツ", "クリエイター", "チャンネル", "配信",
            "視聴者", "ライブ", "録画", "編集", "サムネイル"
        ),
        Domain.CONVERSATION to listOf(
            "おはようございます", "こんにちは", "こんばんは", "ありがとうございます",
            "よろしくお願いします", "失礼します", "お疲れ様です", "素晴らしい",
            "なるほど", "確かに", "そうですね", "おっしゃる通り"
        ),
        Domain.BUSINESS to listOf(
            "会議", "プレゼンテーション", "プロジェクト", "スケジュール", "締め切り",
            "クライアント", "契約", "予算", "売上", "利益", "戦略", "マーケティング",
            "営業", "企画", "報告書", "提案書"
        ),
        Domain.MEDICAL to listOf(
            "症状", "診断", "治療", "処方", "薬", "病院", "医師", "患者",
            "手術", "入院", "外来", "検査", "血液検査", "レントゲン"
        )
    )

    private var currentDomain = Domain.CONVERSATION
    private val domainDetectionHistory = ArrayDeque<String>(10)

    /**
     * ★ 현재 컨텍스트를 분석해 도메인 자동 감지
     */
    fun detectDomain(recentText: String): Domain {
        domainDetectionHistory.addLast(recentText)
        if (domainDetectionHistory.size > 10) domainDetectionHistory.removeFirst()

        val combined = domainDetectionHistory.joinToString(" ")

        val scores = mapOf(
            Domain.TECH         to countKeywords(combined, listOf("AI", "機械", "学習", "プログラム", "データ", "システム", "コード")),
            Domain.MEDIA        to countKeywords(combined, listOf("映画", "音楽", "アニメ", "ドラマ", "動画", "配信")),
            Domain.BUSINESS     to countKeywords(combined, listOf("会議", "契約", "売上", "プロジェクト", "クライアント")),
            Domain.MEDICAL      to countKeywords(combined, listOf("病院", "症状", "医師", "薬", "治療", "診断")),
            Domain.CONVERSATION to 1  // 기본값
        )

        currentDomain = scores.maxByOrNull { it.value }?.key ?: Domain.CONVERSATION
        Log.v(TAG, "Domain detected: $currentDomain (scores=${scores.entries.take(3).map { "${it.key}=${it.value}" }})")
        return currentDomain
    }

    /**
     * ★ 최적 프롬프트 생성
     * 컨텍스트 + 도메인 어휘 힌트 합성
     *
     * @param contextText ContextCacheManager에서 가져온 현재 컨텍스트
     * @param recentTranscript 최근 인식된 텍스트 (도메인 감지용)
     */
    fun buildOptimalPrompt(contextText: String, recentTranscript: String = ""): String {
        if (recentTranscript.isNotBlank()) {
            detectDomain(recentTranscript)
        }

        val domainHints = domainVocabulary[currentDomain]
            ?.shuffled()
            ?.take(5)
            ?.joinToString(" ") ?: ""

        // 컨텍스트 우선, 그 뒤에 도메인 힌트 추가
        val fullPrompt = if (contextText.isNotBlank()) {
            "$contextText $domainHints"
        } else {
            domainHints
        }

        // Whisper 토큰 한계 내로 트림
        return if (fullPrompt.length > MAX_PROMPT_CHARS) {
            fullPrompt.takeLast(MAX_PROMPT_CHARS)
        } else {
            fullPrompt
        }.also {
            Log.v(TAG, "Optimal prompt (${it.length}chars): '${it.take(50)}...'")
        }
    }

    private fun countKeywords(text: String, keywords: List<String>): Int {
        return keywords.count { text.contains(it) }
    }

    /**
     * 도메인 수동 설정 (사용자가 모드를 선택한 경우)
     */
    fun setDomain(domain: Domain) {
        currentDomain = domain
        Log.d(TAG, "Domain manually set: $domain")
    }

    fun getCurrentDomain() = currentDomain
}
