package com.viitor.ui.tab1_browser.memory

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 30遺??먯쑀??0% ?먯튃??吏?ㅻ뒗 硫붾え由?愿由ъ옄 援ы쁽泥? */
class Tab1MemoryManager(
    private val onDestroyCallback: () -> Unit
) : IMemoryManager {

    private val handler = Handler(Looper.getMainLooper())
    // 30遺?= 30 * 60 * 1000 ms
    private val IDLE_TIMEOUT_MS = 30L * 60L * 1000L

    private val idleStateRunnable = Runnable {
        Log.i("Tab1MemoryManager", "30遺꾧컙 ?ъ슜 ?대젰 ?놁쓬. ?먯쑀??0%瑜?鍮꾩슦湲??꾪빐 ?뚭눼 ?쒖옉.")
        clearMemoryAndStopService()
    }

    override fun startIdleTimer() {
        handler.removeCallbacks(idleStateRunnable)
        handler.postDelayed(idleStateRunnable, IDLE_TIMEOUT_MS)
    }

    override fun resetIdleTimer() {
        startIdleTimer()
    }

    override fun clearMemoryAndStopService() {
        handler.removeCallbacks(idleStateRunnable)
        // Fragment??Activity???깅줉??肄쒕갚 諛쒕룞?섏뿬 釉뚮┸吏 ?댁젣 諛?媛앹껜 GC ?좊룄
        onDestroyCallback.invoke()
    }
}


