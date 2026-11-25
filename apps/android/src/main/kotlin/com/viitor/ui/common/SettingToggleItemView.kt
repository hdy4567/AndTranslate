package com.viitor.ui.common

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.viitor.R

/**
 * [SettingToggleItemView]
 * 1번 탭 오버레이 설정과 2번 탭 대본 헤더 등에서 공통으로 재사용하는 프리미엄 토글 스위치 컴포넌트
 */
class SettingToggleItemView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val titleView: TextView
    val switchWidget: SwitchCompat

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(20, 12, 20, 12)

        titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }

        switchWidget = SwitchCompat(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }

        addView(titleView)
        addView(switchWidget)
    }

    fun setLabel(text: String) {
        titleView.text = text
    }

    fun isChecked(): Boolean = switchWidget.isChecked

    fun setChecked(checked: Boolean) {
        switchWidget.isChecked = checked
    }

    fun setOnToggleListener(listener: (Boolean) -> Unit) {
        switchWidget.setOnCheckedChangeListener { _, isChecked ->
            listener(isChecked)
        }
    }
}
