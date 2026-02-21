package com.viitor.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.viitor.R
import com.viitor.databinding.OverlaySubtitleBinding
import com.viitor.subtitle.ISubtitleOverlay
import com.viitor.subtitle.domain.SubtitleSegment

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View.OnTouchListener

/**
 * ★ SubtitleOverlayManager: WindowManager 오케스트레이터
 * Premium UI + Draggable 기능
 */
import android.content.Intent

/**
 * ★ SubtitleOverlayManager: WindowManager 오케스트레이터
 * Premium UI + Draggable 기능
 */
class SubtitleOverlayManager(private val context: Context) : ISubtitleOverlay {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val themeContext = ContextThemeWrapper(context, R.style.Theme_AndTranslate)
    
    private var provisionalBinding: OverlaySubtitleBinding? = null
    private var finalParams: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private fun setupButtons(closeBtn: View, settingsBtn: View) {
        closeBtn.setOnClickListener {
            val intent = Intent(context, com.viitor.service.SubtitleCaptureService::class.java).apply {
                action = "ACTION_STOP"
            }
            // Service가 이미 실행 중이므로 startService로 Intent 전달 (onStartCommand 호출됨)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        settingsBtn.setOnClickListener {
            val intent = Intent(context, com.viitor.ui.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    override fun showProvisional(segment: SubtitleSegment) {
        mainHandler.post {
            if (provisionalBinding == null) {
                try {
                    provisionalBinding = OverlaySubtitleBinding.inflate(LayoutInflater.from(themeContext))
                    // 하단 중앙 배치 (이미지 1번 스타일)
                    val params = createLayoutParams(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 50)
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    
                    setupButtons(provisionalBinding!!.btnClose, provisionalBinding!!.btnSettings)
                    setupDraggableAndResizable(provisionalBinding!!.root, params)
                    
                    windowManager.addView(provisionalBinding?.root, params)
                } catch (e: Exception) { e.printStackTrace() }
            }
            
            provisionalBinding?.apply {
                // "엔진 초기화 중" 등 특정 텍스트가 오면 로딩 UI(이미지 3번) 보여주기
                if (segment.originalText.contains("초기화") || segment.originalText.contains("준비")) {
                    llLoadingContainer.visibility = View.VISIBLE
                    llSubtitleContainer.visibility = View.GONE
                } else {
                    llLoadingContainer.visibility = View.GONE
                    llSubtitleContainer.visibility = View.VISIBLE
                    
                    tvSubtitle.text = segment.originalText
                    if (segment.translatedText != null) {
                        tvTranslatedSubtitle.text = segment.translatedText
                        tvTranslatedSubtitle.visibility = View.VISIBLE
                    } else {
                        if (tvTranslatedSubtitle.text.isEmpty()) {
                            tvTranslatedSubtitle.text = "..."
                        }
                        tvTranslatedSubtitle.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    override fun replaceWithFinal(segment: SubtitleSegment) {
        mainHandler.post {
            // provisionalBinding이 이미 하단바 역할을 하므로, 여기서도 동일하게 업데이트
            provisionalBinding?.apply {
                llLoadingContainer.visibility = View.GONE
                llSubtitleContainer.visibility = View.VISIBLE
                
                tvSubtitle.text = segment.originalText
                if (segment.translatedText != null) {
                    tvTranslatedSubtitle.text = segment.translatedText
                    tvTranslatedSubtitle.visibility = View.VISIBLE
                } else {
                    // 번역을 기다리는 동안 레이아웃이 붕괴되지 않도록 이전 번역을 유지하거나, ... 로 높이 확보
                    if (tvTranslatedSubtitle.text.isEmpty()) {
                        tvTranslatedSubtitle.text = "..."
                    }
                    tvTranslatedSubtitle.visibility = View.VISIBLE
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableAndResizable(view: View, params: WindowManager.LayoutParams) {
        val scaleDetector = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                view.scaleX *= detector.scaleFactor
                view.scaleY *= detector.scaleFactor
                return true
            }
        })

        view.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun createLayoutParams(gravity: Int, yOffset: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, 
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            this.y = yOffset
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    override fun hideAll() {
        mainHandler.post {
            provisionalBinding?.let { if (it.root.parent != null) windowManager.removeView(it.root) }
            provisionalBinding = null
            finalParams = null
        }
    }
}




