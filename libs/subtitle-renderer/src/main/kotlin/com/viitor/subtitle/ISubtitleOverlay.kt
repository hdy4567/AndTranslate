package com.viitor.subtitle

import com.viitor.subtitle.domain.SubtitleSegment

/**
 * ★ ISubtitleOverlay: 자막 오버레이 인터페이스
 * - Lib에서는 인터페이스만 정의하고 App에서 WindowManager로 구현
 */
interface ISubtitleOverlay {
    fun showProvisional(segment: SubtitleSegment)
    fun replaceWithFinal(segment: SubtitleSegment)
    fun hideAll()
}
