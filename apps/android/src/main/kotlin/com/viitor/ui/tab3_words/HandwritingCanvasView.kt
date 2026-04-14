package com.viitor.ui.tab3_words

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * [HandwritingCanvasView]
 * - C++ 엔진 수준의 부드러운 드로잉을 위한 Native Canvas 최적화 뷰
 * - 삼성 S-Pen 인식 및 필압 지원 기초 설계
 * - 바이올렛 핑크 테마 적용
 */
class HandwritingCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val path = Path()
    private val paint = Paint().apply {
        color = Color.parseColor("#FF4081") // 바이올렛 핑크
        isAntiAlias = true
        strokeWidth = 12f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private var canvasBitmap: Bitmap? = null
    private var drawCanvas: Canvas? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        canvasBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawCanvas = Canvas(canvasBitmap!!)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(canvasBitmap!!, 0f, 0f, null)
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        // [PALM-REJECTION-RELAXED] 스타일러스와 손가락 모두 지원하도록 수정
        // 기존: Stylus가 아니면 return false로 무시했음
        val toolType = event.getToolType(0)
        val isValidInput = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_FINGER
        if (!isValidInput) return false 

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                path.moveTo(x, y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                path.lineTo(x, y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                drawCanvas?.drawPath(path, paint)
                path.reset()
                invalidate()
            }
        }
        return true
    }

    fun clear() {
        canvasBitmap?.eraseColor(Color.TRANSPARENT)
        path.reset()
        invalidate()
    }
}
