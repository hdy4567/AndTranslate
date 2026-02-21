package com.viitor.stt.util

/**
 * ★ ContextCacheManager: 실시간 스트리밍을 위한 고도화된 문맥 캐싱 매니저
 * - 최근 인식된 텍스트와 토큰 시퀀스를 유지하여 Whisper 엔진의 "캐치력"을 극대화함
 */
class ContextCacheManager(private val maxContextChars: Int = 100) {
    private var rollingContext = ""

    /**
     * 새로운 텍스트를 문맥에 추가 (Rolling Window 방식)
     */
    fun appendContext(text: String) {
        if (text.isBlank()) return
        
        rollingContext = if (rollingContext.isEmpty()) {
            text
        } else {
            "$rollingContext $text"
        }

        // 최대 길이 유지 (오래된 문맥은 버림)
        if (rollingContext.length > maxContextChars) {
            rollingContext = rollingContext.takeLast(maxContextChars)
            // 단어 중간이 잘리지 않도록 첫 공백 이후부터 유지
            val firstSpace = rollingContext.indexOf(' ')
            if (firstSpace != -1) {
                rollingContext = rollingContext.substring(firstSpace + 1)
            }
        }
    }

    /**
     * STT 엔진에 전달할 현재 문맥 힌트 반환
     */
    fun getPrompt(): String {
        return rollingContext.trim()
    }

    /**
     * 엔진 리셋 시 문맥 초기화 (필요시)
     */
    fun clear() {
        rollingContext = ""
    }
}
