package com.viitor.pipeline.core

import android.util.Log

/**
 * [JapanesePronunciationAgent] (Transliteration Engine - Platinum V5)
 * - Converts Japanese Romaji (from Google dt=rm) to Korean Hangul Reading.
 * - e.g., "sayonara" -> "사요나라", "arigatou" -> "아리가토"
 */
object JapanesePronunciationAgent {

    private val romajiToHangulMap = mapOf(
        // Vowels
        "a" to "아", "i" to "이", "u" to "우", "e" to "에", "o" to "오",
        // K
        "ka" to "카", "ki" to "키", "ku" to "쿠", "ke" to "케", "ko" to "코",
        // S
        "sa" to "사", "shi" to "시", "si" to "시", "su" to "스", "se" to "세", "so" to "소",
        // T
        "ta" to "타", "chi" to "치", "ti" to "티", "tsu" to "츠", "tu" to "츠", "te" to "테", "to" to "토",
        // N
        "na" to "나", "ni" to "니", "nu" to "누", "ne" to "네", "no" to "노",
        // H/F
        "ha" to "하", "hi" to "히", "fu" to "후", "hu" to "후", "he" to "헤", "ho" to "호",
        // M
        "ma" to "마", "mi" to "미", "mu" to "무", "me" to "메", "mo" to "모",
        // Y
        "ya" to "야", "yu" to "유", "yo" to "요",
        // R
        "ra" to "라", "ri" to "리", "ru" to "루", "re" to "레", "ro" to "로",
        // W
        "wa" to "와", "wo" to "오", "n" to "ㄴ",
        // G (Dakuten)
        "ga" to "가", "gi" to "기", "gu" to "구", "ge" to "게", "go" to "고",
        // Z/J (Dakuten)
        "za" to "자", "ji" to "지", "zi" to "지", "zu" to "즈", "ze" to "제", "zo" to "조",
        // D (Dakuten)
        "da" to "다", "di" to "디", "du" to "즈", "de" to "데", "do" to "도",
        // B (Dakuten)
        "ba" to "바", "bi" to "비", "bu" to "부", "be" to "베", "bo" to "보",
        // P (Handakuten)
        "pa" to "파", "pi" to "피", "pu" to "푸", "pe" to "페", "po" to "포",
        
        // Yoon (Capsized sounds)
        "kya" to "캬", "kyu" to "큐", "kyo" to "쿄", 
        "sha" to "샤", "shu" to "슈", "sho" to "쇼", "sya" to "샤", "syu" to "슈", "syo" to "쇼",
        "cha" to "챠", "chu" to "츄", "cho" to "쵸", "tya" to "챠", "tyu" to "츄", "tyo" to "쵸",
        "nya" to "냐", "nyu" to "뉴", "nyo" to "뇨",
        "hya" to "햐", "hyu" to "휴", "hyo" to "효",
        "mya" to "먀", "myu" to "뮤", "myo" to "묘",
        "rya" to "랴", "ryu" to "류", "ryo" to "료",
        "gya" to "갸", "gyu" to "규", "gyo" to "교",
        "ja" to "쟈", "ju" to "쥬", "jo" to "죠", "zya" to "쟈", "zyu" to "쥬", "zyo" to "죠",
        "bya" to "뱌", "byu" to "뷰", "byo" to "뵤",
        "pya" to "퍄", "pyu" to "퓨", "pyo" to "표",
        
        // Special & Foreign
        "wi" to "위", "we" to "웨", "vi" to "비", "vu" to "부",
        "she" to "쉐", "je" to "제", "che" to "체",
        "fa" to "파", "fi" to "피", "fe" to "페", "fo" to "포"
    )

    fun convertRomajiToHangul(romaji: String): String {
        if (romaji.isBlank()) return ""
        
        val input = romaji.lowercase()
        val result = StringBuilder()
        var i = 0
        
        while (i < input.length) {
            // [PLATINUM-V5] 촉음(っ) 처리: 자음 중복 (tt, ss, kk, pp 등)
            if (i + 1 < input.length) {
                val current = input[i]
                val next = input[i + 1]
                if (current == next && current in "stkpbcjrg") {
                    val batchim = when(current) {
                        'k' -> "ㄱ"
                        'p' -> "ㅂ"
                        'g' -> "ㄱ"
                        else -> "ㅅ"
                    }
                    result.append(batchim)
                    i++
                    continue
                }
            }

            // [MATCHING-ENGINE] 3자 -> 2자 -> 1자 순으로 매칭하여 최대 보전
            var matched = false
            for (len in 3 downTo 1) {
                if (i + len <= input.length) {
                    val sub = input.substring(i, i + len)
                    if (romajiToHangulMap.containsKey(sub)) {
                        result.append(romajiToHangulMap[sub])
                        i += len
                        matched = true
                        break
                    }
                }
            }
            
            if (!matched) {
                result.append(input[i])
                i++
            }
        }
        
        val finalHangul = refineHangul(result.toString())
        Log.i("PronunciationAgent", "[LOGCAT-PROB] Romaji: '$romaji' -> Hangul: '$finalHangul'")
        return finalHangul
    }

    private fun refineHangul(text: String): String {
        return text.replace("ㄴ아", "나")
            .replace("ㄴ이", "니")
            .replace("ㄴ우", "누")
            .replace("ㄴ에", "네")
            .replace("ㄴ오", "노")
            .replace("ㅅ타", "스타")
            .replace("ㅅ시", "시")
            .replace("ㅅ스", "스")
            .replace("  ", " ")
            .trim()
    }
}
