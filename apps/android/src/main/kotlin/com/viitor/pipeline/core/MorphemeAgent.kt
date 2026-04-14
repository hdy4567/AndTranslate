package com.viitor.pipeline.core

/**
 * ★ [MorphemeAgent] (Legacy Smart Splitter)
 * - MeCab 등 무거운 라이브러리 없이 일본어의 한자/히라가나/가타카나 문자 종류 변화를 감지하여 
 *   의미 단위(Token)로 쪼개주는 고성능 정규식 엔진.
 */
object MorphemeAgent {

    private val SEGMENT_REGEX = "(?<=[\u4E00-\u9FFF])(?![ \u4E00-\u9FFF])|(?<![\u4E00-\u9FFF])(?=[\u4E00-\u9FFF])|(?<=[\u3040-\u309F])(?![ \u3040-\u309F])|(?<![\u3040-\u309F])(?=[\u3040-\u309F])|(?<=[\u30A0-\u30FF])(?![ \u30A0-\u30FF])|(?<![\u30A0-\u30FF])(?=[\u30A0-\u30FF])|\\s+".toRegex()

    /**
     * 일본어 문장을 입력받아 단어 단위(Chip) 리스트로 반환
     */
    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        return text.split(SEGMENT_REGEX).filter { it.isNotBlank() }
    }
}
