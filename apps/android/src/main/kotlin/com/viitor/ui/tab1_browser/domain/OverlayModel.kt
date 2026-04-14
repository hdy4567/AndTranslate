package com.viitor.ui.tab1_browser.domain

/**
 * 援ш? 踰덉뿭 API 諛?諛쒖쓬 蹂?섍린瑜?嫄곗튇 理쒖쥌 ?뚮뜑留?援ъ“泥? * ?곗씠??遺????null???꾨땶 Empty Object 諛섑솚 洹쒖튃??以?섑븿.
 * 
 * @param payload ?먮낯 ?먮쭑 諛??쒓컙 ?뺣낫 援ъ“泥? * @param translateKo ?쒓뎅??踰덉뿭臾?(鍮꾩뼱?덉쓣 ??鍮?臾몄옄??
 * @param pronunciationKo ?쒓뎅??諛쒖쓬 (?듭뀡 鍮꾪솢?깊솕???곗씠???놁쓣 ??鍮?臾몄옄??
 */
data class OverlayModel(
    val payload: SubtitlePayload,
    val translateKo: String,
    val pronunciationKo: String
) {
    companion object {
        /**
         * ?곗씠??遺????諛섑솚??Empty Object
         */
        fun empty(): OverlayModel = OverlayModel(
            payload = SubtitlePayload(0L, ""),
            translateKo = "",
            pronunciationKo = ""
        )
    }
}


