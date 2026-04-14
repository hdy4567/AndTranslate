package com.viitor.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.viitor.pipeline.core.PipelineEngine
import com.viitor.pipeline.core.SmartSubtitleAgent
import com.viitor.pipeline.core.SubtitleData

/**
 * [SubtitleCaptureService] (Standard Ignition - Platinum Overlay V5)
 * - Features: Dual-axis pinch zoom (XY), Premium Dashboard, Invisible Hijacking.
 */
class SubtitleCaptureService : Service() {
    companion object {
        private const val TAG = "SubtitleCaptureService"
        private const val CHANNEL_ID = "AndTrans_Capture_Channel"
        private const val NOTIFICATION_ID = 1010
    }

    private val agent = SmartSubtitleAgent()
    private val engine = PipelineEngine(agent)
    private var windowManager: WindowManager? = null
    private var containerView: LinearLayout? = null
    private lateinit var subtitleContainer: LinearLayout
    private lateinit var settingsContainer: LinearLayout
    private lateinit var alphaSliderContainer: LinearLayout  // [H버튼] 투명도 슬라이더 패널
    private val mainHandler = Handler(Looper.getMainLooper())

    // UI Components
    private lateinit var tvOriginal: TextView
    private lateinit var tvTranslated: TextView
    private lateinit var tvPronunciation: TextView

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        
        initOverlayUI()
        
        // [STREAM-BINDING] 브라우저(Relay) 또는 에이전트로부터 데이터 수신 및 UI 반영
        val streamListener: (SubtitleData) -> Unit = { data ->
            mainHandler.post {
                // [5.RELAY] 데이터 수신 확인 (GlobalSubtitleRelay 또는 Agent)
                Log.i(TAG, "[5.RELAY] Received: original=${data.original.take(20)}, translated=${data.translated?.take(20)}")
                // [6.OVERLAY] UI 반영
                tvOriginal.text = data.original
                tvTranslated.text = data.translated
                tvPronunciation.text = data.pronunciation
                Log.d(TAG, "[6.OVERLAY] UI updated.")
            }
        }

        // 1. 브라우저 자막 스트림 연결
        com.viitor.pipeline.core.GlobalSubtitleRelay.onNewData = streamListener

        // 2. 외부 OCR/추출 에이전트 스트림 연결
        agent.onSubtitleResult = streamListener
        
        engine.ignite()
    }
    
    private fun initOverlayUI() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        val params = LayoutParams(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                LayoutParams.TYPE_PHONE,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 50
        params.y = 500

        // 1. Root Container (Draggable & Scalable)
        containerView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E62C2C2C")) // Premium Gray-Glass
            setPadding(30, 20, 30, 20)
            elevation = 20f
        }

        // [PINCH-TO-SCALE-XY] 자유자재 가로/세로 리사이징 가동
        containerView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f
            
            private var initialWidth: Int = 0
            private var initialHeight: Int = 0
            private var initialDistX: Float = 0f
            private var initialDistY: Float = 0f
            private var isScaling = false

            override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isScaling = false
                        return true
                    }
                    android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                        if (event.pointerCount == 2) {
                            initialWidth = params.width
                            initialHeight = if (params.height > 0) params.height else v.height
                            initialDistX = Math.abs(event.getX(0) - event.getX(1))
                            initialDistY = Math.abs(event.getY(0) - event.getY(1))
                            isScaling = true
                        }
                        return true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (isScaling && event.pointerCount == 2) {
                            val currentDistX = Math.abs(event.getX(0) - event.getX(1))
                            val currentDistY = Math.abs(event.getY(0) - event.getY(1))
                            
                            if (initialDistX > 10) params.width = (initialWidth * (currentDistX / initialDistX)).toInt().coerceIn(300, 2500)
                            if (initialDistY > 10) params.height = (initialHeight * (currentDistY / initialDistY)).toInt().coerceIn(100, 2000)
                        } else if (!isScaling) {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                        }
                        windowManager?.updateViewLayout(containerView, params)
                        return true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_POINTER_UP -> {
                        isScaling = false
                        return true
                    }
                }
                return false
            }
        })

        // 2. Premium Top Dashboard (Repair, Settings, Close)
        val dashboard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 10)
        }

        // Left Group (H, T)
        val leftGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.START
        }

        val rightGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            gravity = Gravity.END
        }

        val btnHide = createMiniButton("H", Color.parseColor("#A0A0A0")) {
            // [H] 슬라이더 패널 토글
            val isSliderVisible = alphaSliderContainer.visibility == View.VISIBLE
            alphaSliderContainer.visibility = if (isSliderVisible) View.GONE else View.VISIBLE
        }
        btnHide.alpha = 0.6f
        
        val btnTrans = createMiniButton("T", Color.parseColor("#FFD54F")) { 
            val newState = !com.viitor.pipeline.core.GlobalSubtitleRelay.isPronunciationEnabled
            com.viitor.pipeline.core.GlobalSubtitleRelay.isPronunciationEnabled = newState
            tvPronunciation.visibility = if (newState) View.VISIBLE else View.GONE
            Log.i(TAG, "[SYNC] Pronunciation Enabled: $newState")
        }

        val btnRepair = createMiniButton("🛠", Color.parseColor("#64B5F6")) {
            com.viitor.pipeline.core.GlobalSubtitleRelay.requestRepair()
        }

        // ⏯ [REMOTE-PLAY] 동영상 정지/재생 제어
        val btnPlayToggle = createMiniButton("⏯", Color.parseColor("#81C784")) {
            com.viitor.pipeline.core.GlobalSubtitleRelay.togglePlayback()
        }

        val btnSettings = createMiniButton("⚙", Color.WHITE) {
            val isSettingsVisible = settingsContainer.visibility == View.VISIBLE
            if (isSettingsVisible) {
                settingsContainer.visibility = View.GONE
                subtitleContainer.visibility = View.VISIBLE
            } else {
                settingsContainer.visibility = View.VISIBLE
                subtitleContainer.visibility = View.GONE
            }
            // 레이아웃 갱신 강제 수행 (증발 방지)
            containerView?.requestLayout()
        }

        val btnClose = createMiniButton("✕", Color.parseColor("#FF5252")) {
            containerView?.visibility = View.GONE
        }

        leftGroup.addView(btnHide)
        leftGroup.addView(btnTrans)

        rightGroup.addView(btnRepair)
        rightGroup.addView(btnPlayToggle)
        rightGroup.addView(btnSettings)
        rightGroup.addView(btnClose)

        dashboard.addView(leftGroup)
        dashboard.addView(rightGroup)

        // 3. Root Level Setup
        containerView?.alpha = 0.90f // [PLATINUM-V5] 기본 투명도 90% (0.9f) 수정 완료

        // [H-SLIDER] 투명도 조절 SeekBar 패널 (H 버튼 클릭 시 표시)
        alphaSliderContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(10, 8, 10, 8)
            setBackgroundColor(Color.parseColor("#33000000"))
        }
        val alphaLabel = TextView(this).apply {
            text = "투명도"
            setTextColor(Color.parseColor("#A0A0A0"))
            textSize = 11f
        }
        val seekBar = SeekBar(this).apply {
            max = 100
            progress = 90 // 초기값 90% 수정 완료
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        // [6.OVERLAY] 투명도 실시간 적용
                        containerView?.alpha = progress / 100f
                        Log.d(TAG, "[6.OVERLAY] Alpha set to ${progress}%")
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        alphaSliderContainer.addView(alphaLabel)
        alphaSliderContainer.addView(seekBar)
        subtitleContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settingsContainer = LinearLayout(this).apply { 
            orientation = LinearLayout.VERTICAL 
            visibility = View.GONE
            setPadding(20, 20, 20, 20)
        }

        // Subtitle Views (Styling V5: White Bold Original & Aura Pink Translation)
        tvOriginal = createSubtitleTextView(18f, Color.WHITE).apply {
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
        }
        tvTranslated = createSubtitleTextView(18f, Color.parseColor("#FF4081")).apply {
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
        }
        tvPronunciation = createSubtitleTextView(16f, Color.parseColor("#FFD54F"))
        subtitleContainer.addView(tvOriginal)
        subtitleContainer.addView(tvTranslated)
        subtitleContainer.addView(tvPronunciation)

        // Settings View (Switch) - [REPAIR] 테마 에러 방지를 위해 순정 Switch 부품 사용
        val switchPron = android.widget.Switch(this).apply {
            text = "한국식 발음 표시"
            setTextColor(Color.WHITE)
            isChecked = com.viitor.pipeline.core.GlobalSubtitleRelay.isPronunciationEnabled
            setOnCheckedChangeListener { _, isChecked ->
                tvPronunciation.visibility = if (isChecked) View.VISIBLE else View.GONE
                com.viitor.pipeline.core.GlobalSubtitleRelay.isPronunciationEnabled = isChecked
            }
        }
        
        val btnColorPnk = createMiniButton("COLOR-PINK", Color.parseColor("#FF4081")) {
            val color = Color.parseColor("#FF4081")
            tvTranslated.setTextColor(color)
            com.viitor.pipeline.core.GlobalSubtitleRelay.currentTranslationColor = color
        }
        val btnColorWht = createMiniButton("COLOR-WHITE", Color.WHITE) {
            val color = Color.WHITE
            tvTranslated.setTextColor(color)
            com.viitor.pipeline.core.GlobalSubtitleRelay.currentTranslationColor = color
        }
        val btnColorPnk2 = createMiniButton("ff4081", Color.parseColor("#FF4081")) {
            val color = Color.parseColor("#FF4081")
            tvTranslated.setTextColor(color)
            com.viitor.pipeline.core.GlobalSubtitleRelay.currentTranslationColor = color
        }
        
        val colorLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 10)
        }
        colorLayout.addView(btnColorPnk)
        colorLayout.addView(btnColorWht)
        colorLayout.addView(btnColorPnk2)

        settingsContainer.addView(switchPron)
        settingsContainer.addView(colorLayout)

        containerView?.addView(dashboard)
        containerView?.addView(alphaSliderContainer)  // [H-SLIDER] 대시보드 바로 아래
        containerView?.addView(subtitleContainer)
        containerView?.addView(settingsContainer)

        try {
            windowManager?.addView(containerView, params)
            Log.i(TAG, "[STREAM] Premium Unified Overlay Dashboard Deployed")
        } catch (e: Exception) {
            Log.e(TAG, "Fail overlay deploy", e)
        }
    }

    private fun createMiniButton(txt: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = txt
            setTextColor(color)
            textSize = 14f
            minWidth = 0
            minHeight = 0
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(15, 5, 15, 5)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 15 }
            setOnClickListener { onClick() }
        }
    }

    private fun createSubtitleTextView(sz: Float, clr: Int): TextView {
        return TextView(this).apply {
            textSize = sz
            setTextColor(clr)
            setPadding(0, 5, 0, 5)
            text = "---"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "🟢 Service Start Command Received")
        intent?.getStringExtra("videoId")?.let { videoId ->
            agent.processSource(videoId)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AndTranslate Active")
            .setContentText("Real-time subtitle hijacking and translation enabled.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Subtitle Capture Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        if (containerView != null) {
            windowManager?.removeView(containerView)
        }
        engine.shutdown()
        super.onDestroy()
    }
}
