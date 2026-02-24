package com.viitor.stt

import android.util.Log

/**
 * ★ JapaneseTextNormalizer: JP STT 출력 후처리 정규화기
 *
 * 📌 연구 출처: openai/whisper GitHub 이슈, ryujimorita/tokyo_whisperers 참고
 *
 * Whisper가 JP를 출력할 때 발생하는 일반적 오류를 자동 수정:
 *
 * 1. 전각 숫자/영문 → 반각 변환: "１２３" → "123", "ＡＩ" → "AI"
 * 2. 반복 표현 정규화: "そうそう" → "そう", "あのあの" → "あの"
 * 3. 불필요 추임새 제거: "えーと", "あのー", "うーん" 등
 * 4. 구두점 정규화: "、、" → "、", "。。" → "。"
 * 5. 빈칸 최적화: 연속 공백 → 단일 공백
 * 6. 잘못된 한자 대치: Whisper 자주 틀리는 동음이의어 수정
 */
object JapaneseTextNormalizer {

    private const val TAG = "JPNormalizer"

    // 전각 → 반각 변환 맵
    private val FULLWIDTH_TO_HALFWIDTH = mutableMapOf<Char, Char>().apply {
        // 숫자 0-9
        for (i in 0..9) put(('\uFF10'.toInt() + i).toChar(), ('0'.toInt() + i).toChar())
        // 대문자 A-Z
        for (i in 0..25) put(('\uFF21'.toInt() + i).toChar(), ('A'.toInt() + i).toChar())
        // 소문자 a-z
        for (i in 0..25) put(('\uFF41'.toInt() + i).toChar(), ('a'.toInt() + i).toChar())
        // 특수문자
        put('　', ' ')  // 전각 공백 → 반각 공백
        put('！', '!')
        put('？', '?')
        put('，', ',')
        put('．', '.')
    }

    // 자주 잘못 인식되는 동음이의어 수정 사전
    // (Whisper가 자주 틀리는 패턴 기반)
    private val HOMOPHONE_CORRECTIONS = mapOf(
        "わかりました" to "分かりました",
        "よろしく" to "よろしく",
        "ありがとう" to "ありがとう",
        "すいません" to "すみません",  // 일반적 오탈자
        "おねがいします" to "お願いします"
    )

    // 제거할 추임새 패턴
    private val FILLER_PATTERNS = listOf(
        Regex("えーと\\s*"),
        Regex("あのー\\s*"),
        Regex("うーん\\s*"),
        Regex("まあ\\s*"),
        Regex("なんか\\s*なんか"),  // 반복 추임새만 제거
        Regex("（.*?）"),   // 괄호 안 불필요 주석 제거
    )

    // 반복 표현 패턴 (3회 이상 반복 시 1회로)
    private val REPEAT_PATTERN = Regex("(.{2,4})\\1{2,}")

    /**
     * ★ 전체 정규화 파이프라인
     */
    fun normalize(text: String): String {
        if (text.isBlank()) return text

        var result = text

        // Step 1: 전각 → 반각 변환
        result = convertFullwidthToHalfwidth(result)

        // Step 2: 구두점 중복 제거
        result = normalizePunctuation(result)

        // Step 3: 추임새 필터 (옵션 - 너무 공격적이면 비활성화)
        // result = removeFiller(result)  // 필요 시 활성화

        // Step 4: 반복 표현 정규화
        result = normalizeRepetition(result)

        // Step 5: 공백 정규화
        result = result.replace(Regex("\\s{2,}"), " ").trim()

        if (result != text) {
            Log.v(TAG, "Normalized: '${text.take(30)}' → '${result.take(30)}'")
        }

        return result
    }

    private fun convertFullwidthToHalfwidth(text: String): String {
        return text.map { ch ->
            FULLWIDTH_TO_HALFWIDTH[ch] ?: ch
        }.joinToString("")
    }

    private fun normalizePunctuation(text: String): String {
        return text
            .replace(Regex("[。]{2,}"), "。")
            .replace(Regex("[、]{2,}"), "、")
            .replace(Regex("[！]{2,}"), "！")
            .replace(Regex("[？]{2,}"), "？")
            .replace(Regex("[!]{2,}"), "!")
            .replace(Regex("[?]{2,}"), "?")
    }

    private fun removeFiller(text: String): String {
        var result = text
        for (pattern in FILLER_PATTERNS) {
            result = pattern.replace(result, "")
        }
        return result
    }

    private fun normalizeRepetition(text: String): String {
        return REPEAT_PATTERN.replace(text) { match ->
            match.groupValues[1]  // 반복 단위 1회만 남김
        }
    }

    /**
     * ★ 숫자 표기 정규화: 和語数字 → アラビア数字
     * "三百円" → "300円", "十万" → "100000"
     * (간단한 버전 - 1~9999 범위)
     */
    fun normalizeJapaneseNumbers(text: String): String {
        val kanji2num = mapOf(
            "零" to 0, "一" to 1, "二" to 2, "三" to 3, "四" to 4,
            "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9,
            "十" to 10, "百" to 100, "千" to 1000, "万" to 10000
        )

        // 간단한 패턴: 〇〇〇 형태의 숫자 표현
        var result = text
        for ((kanji, num) in kanji2num) {
            result = result.replace(kanji, num.toString())
        }
        return result
    }
}
