package com.viitor.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import com.viitor.R
import com.viitor.service.SubtitleCaptureService

/**
 * ★ SideControllerManager: 화면 측면 부착 컨트롤러 (Expandable)
 * - Main Bubble 클릭 -> 메뉴 확장 (Play/Pause, Settings, Close)
 * - Premium UI (Purple Bubble)
 */
class SideControllerManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var controllerView: View? = null
    private var params: WindowManager.LayoutParams? = null
    
    // 드래그를 위한 변수
    private var initialY = 0
    private var initialTouchY = 0f
    private var isRecognitionStarted = false

    fun show() {
        if (controllerView != null) return
        
        try {
            controllerView = LayoutInflater.from(context).inflate(R.layout.layout_side_controller, null)
            
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                x = 0
                y = 0
            }

            setupControls(controllerView!!)
            windowManager.addView(controllerView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    @SuppressLint("ClickableViewAccessibility")
    private fun setupControls(view: View) {
        val mainBubble = view.findViewById<View>(R.id.btn_main_bubble)
        
        var startY = 0f
        var isMoving = false

        mainBubble.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    initialY = params!!.y
                    startY = event.rawY
                    isMoving = false
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val deltaY = event.rawY - startY
                    if (java.lang.Math.abs(deltaY) > 10) {
                        isMoving = true
                        params!!.y = initialY + deltaY.toInt()
                        windowManager.updateViewLayout(controllerView, params)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (!isMoving) {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }

        mainBubble.setOnClickListener {
            val intent = Intent(context, SubtitleCaptureService::class.java).apply {
                action = "ACTION_START_RECOGNITION"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            android.widget.Toast.makeText(context, "자막 캡처 시작", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    fun hide() {
        try {
            if (controllerView != null) {
                windowManager.removeView(controllerView)
                controllerView = null
            }
        } catch (e: Exception) {}
    }
}
