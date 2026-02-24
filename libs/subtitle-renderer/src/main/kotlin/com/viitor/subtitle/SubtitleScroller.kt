package com.viitor.subtitle

import com.viitor.stt.domain.BatchTranscript
import com.viitor.stt.domain.ProvisionalTranscript
import java.util.LinkedList

/**
 * ★ SubtitleScroller: 실시간 멀티라인 자막 렌더링 및 이력 관리 (Zero GC 지향)
 */
class SubtitleScroller(private val maxLines: Int = 2) : ISubtitleScroller {
    private val finalizedHistory = LinkedList<BatchTranscript>()
    
    override fun commitFinalLine(original: String, translated: String) {
        if (original.isBlank()) return
        
        // 중복 방지 (동일 내용이 연속으로 들어오는 경우 무시)
        if (finalizedHistory.isNotEmpty() && finalizedHistory.last().text == original) return
        
        finalizedHistory.add(BatchTranscript(text = original, chunkCount = 1, translatedText = translated))
        if (finalizedHistory.size > maxLines) {
            finalizedHistory.removeFirst()
        }
    }

    override fun buildScrollingTranscript(currentOriginal: String, currentTranslated: String): BatchTranscript {
        val origSb = StringBuilder()
        val transSb = StringBuilder()
        
        // 1. 과거 히스토리 누적
        for (line in finalizedHistory) {
            origSb.append(line.text).append("\n")
            if (line.translatedText?.isNotBlank() == true) {
                transSb.append(line.translatedText).append("\n")
            }
        }
        
        // 2. 현재 진행 중인 텍스트 추가
        if (currentOriginal.isNotBlank()) {
            origSb.append(currentOriginal)
        }
        if (currentTranslated.isNotBlank()) {
            transSb.append(currentTranslated)
        }
        
        val finalTrans = transSb.toString().trim()
        return BatchTranscript(
            text = origSb.toString().trim(),
            chunkCount = 1,
            translatedText = if (finalTrans.isEmpty()) null else finalTrans
        )
    }

    override fun buildScrollingProvisional(currentOriginal: String, currentTranslated: String): ProvisionalTranscript {
        // ⚡ [Debugging Boost] 무조건 필터링하지 않고, 최소한의 유효성만 검사하여 렌더링 유실 방지
        val isMeaningless = currentOriginal.length < 3 && 
            (currentOriginal.contains("로드") || currentOriginal.contains("준비"))
            
        val origToRender = if (isMeaningless) "" else currentOriginal
        val batch = buildScrollingTranscript(origToRender, currentTranslated)
        
        return ProvisionalTranscript(
            text = batch.text,
            confidence = 1.0f,
            translatedText = batch.translatedText
        )
    }

    override fun clear() {
        finalizedHistory.clear()
    }
}
