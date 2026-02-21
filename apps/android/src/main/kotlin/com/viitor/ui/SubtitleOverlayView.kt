package com.viitor.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View

/**
 * ★ SubtitleOverlayView: 최종 자막 View (alpha=0.9, 검은 배경)
 */
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/**
 * ★ SubtitleOverlayView: Material3 디자인이 적용된 실시간 자막 뷰
 * - Pinch-to-zoom (ScaleGestureDetector) 지원
 * - Provisional(회색) / Final(흰색) 자막 구분 렌더링
 */
class SubtitleOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    
    // UI Elements
    lateinit var subtitleText: TextView
    lateinit var container: MaterialCardView
    
    private var scaleFactor = 1.0f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = Math.max(0.5f, Math.min(2.0f, scaleFactor))
            container.scaleX = scaleFactor
            container.scaleY = scaleFactor
            return true
        }
    })
}
