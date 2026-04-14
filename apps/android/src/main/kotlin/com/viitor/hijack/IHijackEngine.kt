package com.viitor.hijack

/**
 * [IHijackEngine] - 플랫폼 독립 하이재킹 엔진 인터페이스
 * 유튜브, 트위치, 니코니코 등 모든 플랫폼이 이 계약을 구현한다.
 *
 * @property injectScript JS Bridge 주입 스크립트 (웹뷰에 eval로 주입)
 * @property shutdownScript 관찰자 및 타이머 제거 스크립트
 * @property urlPattern 이 엔진이 처리하는 URL 정규식 패턴
 */
interface IHijackEngine {
    val injectScript: String
    val shutdownScript: String
    val urlPattern: Regex
}
