package com.viitor.hijack

/**
 * [TwitchHijackEngine] - 트위치 채팅/자막 하이재킹 엔진
 * - URL: twitch.tv
 * - 자막 셀렉터: .chat-line__message (채팅 스트림) or .player-captions-container (자막)
 * - 일본어 채팅 라인만 필터링하여 AndTransBridge로 전달
 */
object TwitchHijackEngine : IHijackEngine {

    override val urlPattern = Regex("twitch\\.tv")

    override val injectScript = """
        javascript:(function() {
            const TAG = '[ANDTRANS][2.JS-HIJACK][TWITCH]';
            console.log(TAG + ' Premium 0.1s Engine Injecting...');

            if (window.andTransObserver) { window.andTransObserver.disconnect(); }
            if (window.andTransIntervalId) { clearInterval(window.andTransIntervalId); }

            let lastText = "";
            let lastSyncTime = 0;

            const captureTwitch = (force = false) => {
                const video = document.querySelector('video');
                if (!video) return;

                // [SELECTOR] 트위치 자막 컨테이너 및 텍스트 추출
                const captions = document.querySelectorAll('.player-captions-container span');
                const fullText = Array.from(captions).map(e => e.innerText).join(' ').trim();
                const aTime = Math.floor(video.currentTime * 1000);

                // [ANCHORING] 0.3초 주기 시간 동기화 브로드캐스트
                if (Math.abs(Date.now() - lastSyncTime) >= 300 || force) {
                    lastSyncTime = Date.now();
                    if (window.AndTransBridge) {
                        AndTransBridge.onTimeUpdate(aTime);
                    }
                }

                if (fullText !== lastText || force) {
                    if (fullText !== lastText) {
                        console.log(TAG + ' [2.JS-HIJACK] Captured: ' + fullText.substring(0, 30));
                    }
                    lastText = fullText;
                    
                    const payload = JSON.stringify({ anchorTimeMs: aTime, originJpText: fullText });
                    if (window.AndTransBridge && fullText) {
                        AndTransBridge.onSubtitleExtracted(payload);
                    }
                }
            };

            // [PERFORMANCE] 0.1초(100ms) 성능 지표용 폴링 엔진
            window.andTransIntervalId = setInterval(() => captureTwitch(false), 100);

            // [REACTIVE] 즉각적 자막 생성을 위한 옵저버 병행
            let targetNode = document.querySelector('.player-captions-container') || document.body;
            window.andTransObserver = new MutationObserver(() => captureTwitch(false));
            window.andTransObserver.observe(targetNode, { childList: true, subtree: true });

            console.log(TAG + ' 0.1s High-Perf Tracking enabled.');
        })();
    """.trimIndent()

    override val shutdownScript = """
        javascript:(function() {
            if (window.andTransObserver) { window.andTransObserver.disconnect(); window.andTransObserver = null; }
            if (window.andTransIntervalId) { clearInterval(window.andTransIntervalId); window.andTransIntervalId = null; }
            console.log('[ANDTRANS][TWITCH] Hijack Shutdown complete.');
        })();
    """.trimIndent()
}
