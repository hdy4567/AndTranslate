package com.viitor.ui.tab1_browser.memory

/**
 * 濡깊꽣移??곹깭 諛?硫붾え由??대━???명꽣?섏씠?? */
interface IMemoryManager {
    /**
     * 30遺꾧컙 UI Interaction???놁쓣 ??紐⑤뱺 ?먯쑀??0%瑜??꾪븳 媛앹껜 ?뚭눼 紐⑤뱶 吏꾩엯
     */
    fun startIdleTimer()
    fun resetIdleTimer()
    fun clearMemoryAndStopService()
}


