package com.viitor.subtitle

import com.viitor.stt.domain.BatchTranscript
import com.viitor.stt.domain.ProvisionalTranscript

/**
 * ★ ISubtitleScroller: 자막 스크롤 및 누적 관리를 위한 인터페이스 기반 계약.
 */
interface ISubtitleScroller {
    /** 자막 한 줄을 확정하여 스크롤 히스토리에 추가 */
    fun commitFinalLine(original: String, translated: String)
    
    /** 현재 스크롤 상태를 포함한 최종 자막 데이터 생성 */
    fun buildScrollingTranscript(currentOriginal: String, currentTranslated: String): BatchTranscript
    
    /** 로드/초기화 텍스트가 필터링된 화면 표시용 부분 자막 생성 */
    fun buildScrollingProvisional(currentOriginal: String, currentTranslated: String): ProvisionalTranscript
    
    /** 히스토리 초기화 */
    fun clear()
}
