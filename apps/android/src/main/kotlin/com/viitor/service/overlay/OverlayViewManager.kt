package com.viitor.service.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.viitor.pipeline.core.GlobalSubtitleRelay
import com.viitor.pipeline.core.SubtitleData
import com.viitor.ui.common.SettingToggleItemView

/**
 * [OverlayViewManager]
 * Manages the floating overlay window lifecycle, UI hierarchy, and touch/pinch-to-scale gestures.
 */
class OverlayViewManager(private val context: Context) {

    companion object {
        private const val TAG = "OverlayViewManager"
    }

    private var windowManager: WindowManager? = null
    private var containerView: LinearLayout? = null
    private lateinit var subtitleContainer: LinearLayout
    private lateinit var settingsScrollContainer: ScrollView
    private lateinit var settingsContainer: LinearLayout
    private lateinit var alphaSliderContainer: LinearLayout

    // UI Components for Subtitles
    private lateinit var origin1layerView: TextView
    private lateinit var tras2layerView: TextView
    private lateinit var pron3layerView: TextView

    private var pronToggleView: SettingToggleItemView? = null
    private var lastData: SubtitleData? = null

    fun initOverlay() {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val displayMetrics = context.resources.displayMetrics
        val params = LayoutParams(
            (displayMetrics.widthPixels * 0.9).toInt(),
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

        // 1. Root Container
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E62C2C2C")) // Premium Gray-Glass
            setPadding(30, 20, 30, 20)
            elevation = 20f
        }
        containerView = root

        setupTouchAndPinchZoom(root, params)

        // 2. Dashboard Header
        val dashboard = buildDashboard()

        // 3. Alpha Slider Panel
        alphaSliderContainer = buildAlphaSliderPanel()

        // 4. Subtitle and Settings Containers
        subtitleContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        
        settingsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 10, 10, 10)
        }

        settingsScrollContainer = ScrollView(context).apply {
            visibility = View.GONE
            addView(settingsContainer)
        }

        // Subtitle Views
        val prefs = context.getSharedPreferences("viitor_overlay_prefs", Context.MODE_PRIVATE)
        val savedTextSize = prefs.getFloat("subtitle_text_size", 18f)
        val savedColor = prefs.getInt("subtitle_color", Color.parseColor("#FF4081"))
        GlobalSubtitleRelay.currentTras2LayerColor = savedColor

        origin1layerView = createSubtitleTextView(savedTextSize, Color.WHITE).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setOnClickListener {
                val currentText = text?.toString().orEmpty()
                val matched = GlobalSubtitleRelay.savedVocabList.firstOrNull { vocab ->
                    vocab.word.isNotBlank() && currentText.contains(vocab.word, ignoreCase = true)
                }
                if (matched != null) {
                    Toast.makeText(
                        context,
                        "★ [보관 단어] ${matched.word} : ${matched.tras2layer ?: "의미 없음"}\n문맥: ${matched.origin1layer ?: "문맥 없음"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        tras2layerView = createSubtitleTextView(savedTextSize, savedColor).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        pron3layerView = createSubtitleTextView((savedTextSize - 2f).coerceAtLeast(12f), Color.parseColor("#FFD54F")).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        subtitleContainer.addView(origin1layerView)
        subtitleContainer.addView(tras2layerView)
        subtitleContainer.addView(pron3layerView)

        buildSettingsContent(settingsContainer)

        root.alpha = prefs.getInt("overlay_alpha", 90) / 100f
        root.addView(dashboard)
        root.addView(alphaSliderContainer)
        root.addView(subtitleContainer)
        root.addView(settingsScrollContainer)

        GlobalSubtitleRelay.onVocabUpdated = {
            lastData?.let { data ->
                origin1layerView.post {
                    updateSubtitles(data)
                }
            }
        }

        try {
            windowManager?.addView(root, params)
            Log.i(TAG, "[STREAM] Premium Unified Overlay Dashboard Deployed")
        } catch (e: Exception) {
            Log.e(TAG, "Fail overlay deploy", e)
        }
    }

    private fun highlightVocabWords(text: String): CharSequence {
        val savedWords = GlobalSubtitleRelay.savedVocabWords
        if (savedWords.isEmpty() || text.isBlank()) return text

        val spannable = SpannableString(text)
        for (word in savedWords) {
            if (word.length < 2 && !word.any { it.isLetter() }) continue
            var startIndex = text.indexOf(word, ignoreCase = true)
            while (startIndex >= 0) {
                val endIndex = startIndex + word.length
                spannable.setSpan(
                    BackgroundColorSpan(Color.parseColor("#FFE57F")),
                    startIndex,
                    endIndex,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    ForegroundColorSpan(Color.parseColor("#1B1B1B")),
                    startIndex,
                    endIndex,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD),
                    startIndex,
                    endIndex,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                startIndex = text.indexOf(word, endIndex, ignoreCase = true)
            }
        }
        return spannable
    }

    fun updateSubtitles(data: SubtitleData) {
        lastData = data
        origin1layerView.text = highlightVocabWords(data.origin1layer)
        tras2layerView.text = data.tras2layer
        pron3layerView.text = data.pron3layer
        Log.d(TAG, "[OVERLAY] UI updated: origin=${data.origin1layer.take(20)}")
    }

    fun destroy() {
        GlobalSubtitleRelay.onVocabUpdated = null
        containerView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove overlay view", e)
            }
        }
        containerView = null
    }

    private fun setupTouchAndPinchZoom(view: View, params: LayoutParams) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f

            private var initialWidth: Int = 0
            private var initialHeight: Int = 0
            private var initialDistX: Float = 0f
            private var initialDistY: Float = 0f
            private var isScaling = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isScaling = false
                        return true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (event.pointerCount == 2) {
                            initialWidth = params.width
                            initialHeight = if (params.height > 0) params.height else v.height
                            initialDistX = Math.abs(event.getX(0) - event.getX(1))
                            initialDistY = Math.abs(event.getY(0) - event.getY(1))
                            isScaling = true
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
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
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                        isScaling = false
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun buildDashboard(): LinearLayout {
        val dashboard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 10)
        }

        val leftGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.START
        }

        val rightGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            gravity = Gravity.END
        }

        val btnHide = createMiniButton("H", Color.parseColor("#A0A0A0")) {
            val isSliderVisible = alphaSliderContainer.visibility == View.VISIBLE
            alphaSliderContainer.visibility = if (isSliderVisible) View.GONE else View.VISIBLE
        }.apply { alpha = 0.6f }

        val btnTrans = createMiniButton("T", Color.parseColor("#FFD54F")) {
            val newState = !GlobalSubtitleRelay.isPron3LayerEnabled
            GlobalSubtitleRelay.isPron3LayerEnabled = newState
            pron3layerView.visibility = if (newState) View.VISIBLE else View.GONE
            pronToggleView?.setChecked(newState)
            Log.i(TAG, "[SYNC] Pronunciation Enabled: $newState")
        }

        val btnRepair = createMiniButton("🛠", Color.parseColor("#64B5F6")) {
            GlobalSubtitleRelay.requestRepair()
        }

        val btnPlayToggle = createMiniButton("⏯", Color.parseColor("#81C784")) {
            GlobalSubtitleRelay.togglePlayback()
        }

        val btnSettings = createMiniButton("⚙", Color.WHITE) {
            toggleSettingsView()
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
        return dashboard
    }

    private fun toggleSettingsView() {
        val isSettingsVisible = settingsScrollContainer.visibility == View.VISIBLE
        if (isSettingsVisible) {
            settingsScrollContainer.visibility = View.GONE
            subtitleContainer.visibility = View.VISIBLE
        } else {
            pronToggleView?.setChecked(GlobalSubtitleRelay.isPron3LayerEnabled)
            settingsScrollContainer.visibility = View.VISIBLE
            subtitleContainer.visibility = View.GONE
        }
        containerView?.requestLayout()
    }

    private fun buildAlphaSliderPanel(): LinearLayout {
        val prefs = context.getSharedPreferences("viitor_overlay_prefs", Context.MODE_PRIVATE)
        val sliderContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 8, 10, 8)
            setBackgroundColor(Color.parseColor("#33000000"))
        }

        val alphaLabel = TextView(context).apply {
            text = "투명도"
            setTextColor(Color.parseColor("#A0A0A0"))
            textSize = 11f
        }

        val seekBar = SeekBar(context).apply {
            max = 100
            progress = prefs.getInt("overlay_alpha", 90)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val safeAlpha = (progress / 100f).coerceIn(0.2f, 1.0f)
                        containerView?.alpha = safeAlpha
                        prefs.edit().putInt("overlay_alpha", progress).apply()
                        Log.d(TAG, "[OVERLAY] Alpha set to ${progress}%")
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

        sliderContainer.addView(alphaLabel)
        sliderContainer.addView(seekBar)
        return sliderContainer
    }

    private fun buildSettingsContent(parent: LinearLayout) {
        val prefs = context.getSharedPreferences("viitor_overlay_prefs", Context.MODE_PRIVATE)
        val savedModeCode = prefs.getString("translation_mode_code", "JA_KO") ?: "JA_KO"
        val initialMode = GlobalSubtitleRelay.TranslationMode.fromCode(savedModeCode)
        GlobalSubtitleRelay.translationMode = initialMode

        // --- 1. Translation Mode Selector ---
        val langLabel = TextView(context).apply {
            text = "🌐 번역 방향 모드 (Translation Mode)"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8)
        }

        val modeLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 12)
        }

        lateinit var btnJaKo: Button
        lateinit var btnEnKo: Button

        fun updateModeButtons(currentMode: GlobalSubtitleRelay.TranslationMode) {
            if (currentMode == GlobalSubtitleRelay.TranslationMode.JA_TO_KO) {
                btnJaKo.setTextColor(Color.parseColor("#FF4081"))
                btnJaKo.setBackgroundColor(Color.parseColor("#44FF4081"))
                btnEnKo.setTextColor(Color.parseColor("#888888"))
                btnEnKo.setBackgroundColor(Color.parseColor("#22FFFFFF"))
            } else {
                btnJaKo.setTextColor(Color.parseColor("#888888"))
                btnJaKo.setBackgroundColor(Color.parseColor("#22FFFFFF"))
                btnEnKo.setTextColor(Color.parseColor("#FF4081"))
                btnEnKo.setBackgroundColor(Color.parseColor("#44FF4081"))
            }
        }

        btnJaKo = createMiniButton("🇯🇵 일본어 ➔ 🇰🇷 한국어 (JA ➔ KO)", Color.WHITE) {
            GlobalSubtitleRelay.translationMode = GlobalSubtitleRelay.TranslationMode.JA_TO_KO
            prefs.edit().putString("translation_mode_code", "JA_KO").apply()
            updateModeButtons(GlobalSubtitleRelay.TranslationMode.JA_TO_KO)
            GlobalSubtitleRelay.requestRepair()
        }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(20, 10, 20, 10)
        }

        btnEnKo = createMiniButton("🇺🇸 영어 ➔ 🇰🇷 한국어 (EN ➔ KO)", Color.WHITE) {
            GlobalSubtitleRelay.translationMode = GlobalSubtitleRelay.TranslationMode.EN_TO_KO
            prefs.edit().putString("translation_mode_code", "EN_KO").apply()
            updateModeButtons(GlobalSubtitleRelay.TranslationMode.EN_TO_KO)
            GlobalSubtitleRelay.requestRepair()
        }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(20, 10, 20, 10)
        }

        updateModeButtons(initialMode)
        modeLayout.addView(btnJaKo)
        modeLayout.addView(btnEnKo)

        // --- 2. Pronunciation Switch (SettingToggleItemView 재사용) ---
        pronToggleView = SettingToggleItemView(context).apply {
            setLabel("🗣 한국식 발음 표시")
            setChecked(GlobalSubtitleRelay.isPron3LayerEnabled)
            setOnToggleListener { isChecked ->
                GlobalSubtitleRelay.isPron3LayerEnabled = isChecked
                pron3layerView.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
        }

        // --- 3. Subtitle Font Size ---
        val sizeLabel = TextView(context).apply {
            text = "🔤 자막 글자 크기 (Font Size)"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 10, 0, 8)
        }

        val sizeLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 12)
        }

        fun updateFontSize(sz: Float) {
            origin1layerView.textSize = sz
            tras2layerView.textSize = sz
            pron3layerView.textSize = (sz - 2f).coerceAtLeast(12f)
            prefs.edit().putFloat("subtitle_text_size", sz).apply()
        }

        val btnSizeSmall = createMiniButton("작게 (14sp)", Color.WHITE) { updateFontSize(14f) }
        val btnSizeNormal = createMiniButton("보통 (18sp)", Color.WHITE) { updateFontSize(18f) }
        val btnSizeLarge = createMiniButton("크게 (22sp)", Color.WHITE) { updateFontSize(22f) }
        sizeLayout.addView(btnSizeSmall)
        sizeLayout.addView(btnSizeNormal)
        sizeLayout.addView(btnSizeLarge)

        // --- 4. Subtitle Color Palette ---
        val colorLabel = TextView(context).apply {
            text = "🎨 번역 자막 색상 (Color)"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 10, 0, 8)
        }

        val colorLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 12)
        }

        fun applyColor(color: Int) {
            tras2layerView.setTextColor(color)
            GlobalSubtitleRelay.currentTras2LayerColor = color
            prefs.edit().putInt("subtitle_color", color).apply()
        }

        val btnColorPnk = createMiniButton("🌸 핑크", Color.parseColor("#FF4081")) { applyColor(Color.parseColor("#FF4081")) }
        val btnColorWht = createMiniButton("⚪ 화이트", Color.WHITE) { applyColor(Color.WHITE) }
        val btnColorYel = createMiniButton("🟡 옐로우", Color.parseColor("#FFD54F")) { applyColor(Color.parseColor("#FFD54F")) }
        val btnColorBlu = createMiniButton("🔵 하늘", Color.parseColor("#64B5F6")) { applyColor(Color.parseColor("#64B5F6")) }

        colorLayout.addView(btnColorPnk)
        colorLayout.addView(btnColorWht)
        colorLayout.addView(btnColorYel)
        colorLayout.addView(btnColorBlu)

        // --- 5. Done / Return Button ---
        val btnDone = Button(context).apply {
            text = "✓ 설정 완료 및 자막 보기"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#FF4081"))
            setOnClickListener { toggleSettingsView() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12 }
        }

        parent.addView(langLabel)
        parent.addView(modeLayout)
        parent.addView(pronToggleView)
        parent.addView(sizeLabel)
        parent.addView(sizeLayout)
        parent.addView(colorLabel)
        parent.addView(colorLayout)
        parent.addView(btnDone)
    }

    private fun createMiniButton(txt: String, color: Int, onClick: () -> Unit): Button {
        return Button(context).apply {
            text = txt
            setTextColor(color)
            textSize = 13f
            minWidth = 0
            minHeight = 0
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            setPadding(16, 8, 16, 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8 }
            setOnClickListener { onClick() }
        }
    }

    private fun createSubtitleTextView(sz: Float, clr: Int): TextView {
        return TextView(context).apply {
            textSize = sz
            setTextColor(clr)
            setPadding(0, 5, 0, 5)
            text = "---"
            setSingleLine(false)
            maxLines = 10
            setHorizontallyScrolling(false)
        }
    }
}
