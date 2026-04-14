package com.viitor.ui.overlay

import android.content.Context
import android.util.Log

/**
 * ★ SideControllerManager: 화면 측면 부착 컨트롤러 (Expandable)
 * - Main Bubble 클릭 -> 메뉴 확장 (Play/Pause, Settings, Close)
 */
class SideControllerManager(private val context: Context) {
    companion object {
        private const val TAG = "SideControllerManager"
    }

    private var isExpanded = false

    /** 🚀 컨트롤러 노출 */
    fun show() {
        Log.i(TAG, "🎭 Showing Side Controller Bubble")
    }

    /** 🛑 컨트롤러 숨기기 */
    fun hide() {
        Log.i(TAG, "🎭 Hiding Side Controller")
    }

    /** 🔄 상태 토글 */
    fun toggleMenu() {
        isExpanded = !isExpanded
        Log.d(TAG, "Menu Expanded: $isExpanded")
    }
}
