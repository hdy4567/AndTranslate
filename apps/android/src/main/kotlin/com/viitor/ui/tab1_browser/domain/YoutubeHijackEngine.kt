package com.viitor.ui.tab1_browser.domain

/**
 * Platinum: VOD ?덇굅??濡쒖쭅 蹂듦뎄
 * ?좏뒠釉??밸럭 諛깅룄???ㅽ겕由쏀듃 (Chunked-Relay & Instant-Ignite ?묒옱)
 */
object YoutubeHijackEngine {
    
    val PLATINUM_VOD_SCRIPT = """
        javascript:(function() {
            // [V5-NETWORK-SNIFFER] 1. 유튜브 전체 대본 가로채기 (Fetch & XHR Intercept)
            if (!window.andTransNetworkSnifferInstalled) {
                window.andTransNetworkSnifferInstalled = true;
                
                const originalFetch = window.fetch;
                window.fetch = async function() {
                    const url = arguments[0];
                    const response = await originalFetch.apply(this, arguments);
                    if (typeof url === 'string' && url.includes('api/timedtext')) {
                        response.clone().text().then(text => {
                            try {
                                if(text && text.includes('events')) {
                                    if(text.length > 4000) {
                                        let chunksCount = Math.ceil(text.length / 4000);
                                        for(let i=0; i<chunksCount; i++) {
                                            let chunk = text.substring(i * 4000, (i+1) * 4000);
                                            window.AndTransBridge.onFullTranscriptCapturedChunked(chunk, i, chunksCount);
                                        }
                                    } else {
                                        window.AndTransBridge.onFullTranscriptCaptured(text);
                                    }
                                }
                            } catch (e) {}
                        }).catch(e=>{});
                    }
                    return response;
                };

                const originalXHR = window.XMLHttpRequest.prototype.open;
                window.XMLHttpRequest.prototype.open = function() {
                    this.addEventListener('load', function() {
                        const url = arguments[1] || this.responseURL;
                        if (typeof url === 'string' && url.includes('api/timedtext')) {
                            try {
                                const text = this.responseText;
                                if(text && text.includes('events')) {
                                    if(text.length > 4000) {
                                        let chunksCount = Math.ceil(text.length / 4000);
                                        for(let i=0; i<chunksCount; i++) {
                                            let chunk = text.substring(i * 4000, (i+1) * 4000);
                                            window.AndTransBridge.onFullTranscriptCapturedChunked(chunk, i, chunksCount);
                                        }
                                    } else {
                                        window.AndTransBridge.onFullTranscriptCaptured(text);
                                    }
                                }
                            } catch (e) {}
                        }
                    });
                    return originalXHR.apply(this, arguments);
                };
            }

            // 기존 하이잭 초기화
            if (window.andTransObserver) {
                window.andTransObserver.disconnect();
            }
            if (window.andTransIgniteId) {
                clearInterval(window.andTransIgniteId);
            }

            // [Instant-Ignite 엔진] URL 1초 주기 감시 + 자막 강제 활성화 및 일본어[1] 선택
            let lastUrl = location.href;
            window.andTransIgniteId = setInterval(function() {
                if (lastUrl !== location.href) {
                    lastUrl = location.href;
                    forceCC();
                    autoSelectJa();
                }
            }, 1000);

            function forceCC() {
                const video = document.querySelector('video');
                if (video && !document.querySelector('.ytp-subtitles-button[aria-pressed="true"]')) {
                    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'c', keyCode: 67 }));
                }
            }

            function autoSelectJa() {
                const settingsBtn = document.querySelector('.ytp-settings-button');
                if (settingsBtn) {
                    settingsBtn.click();
                    setTimeout(() => {
                        const menuItems = Array.from(document.querySelectorAll('.ytp-menuitem'));
                        const subMenu = menuItems.find(i => i.innerText.includes('자막') || i.innerText.includes('Subtitles'));
                        if (subMenu) {
                            subMenu.click();
                            setTimeout(() => {
                                const tracks = Array.from(document.querySelectorAll('.ytp-menuitem'));
                                const jaTrack = tracks.find(t => t.innerText.includes('日本語 (1)') || t.innerText.includes('Japanese (1)'));
                                if (jaTrack) {
                                    jaTrack.click();
                                }
                                settingsBtn.click();
                            }, 500);
                        } else {
                            settingsBtn.click();
                        }
                    }, 500);
                }
            }

            let targetNode = document.querySelector('.html5-video-player');
            if(!targetNode) targetNode = document.body;

            window.andTransObserver = new MutationObserver((mutations) => {
                mutations.forEach((mutation) => {
                    const captionSegments = document.querySelectorAll('.ytp-caption-segment');
                    if (captionSegments.length > 0) {
                        let fullText = Array.from(captionSegments).map(e => e.innerText).join(' ');
                        
                        // ?곸긽 ?섎━癒쇳듃媛 ?꾩쭅 濡쒕뵫 ?꾩씠硫??쒓컙? 0?쇰줈 諛⑹뼱
                        let v = document.querySelector('video');
                        let aTime = v ? Math.floor(v.currentTime * 1000) : 0;

                        let payload = {
                            anchorTimeMs: aTime,
                            origin1layer: fullText
                        };
                        
                        let jsonStr = JSON.stringify(payload);
                        if(jsonStr.length > 3900) {
                            // [V5-REFILL] 임계점 도달 시 영상 데이터 재수혈 시도 (단순 트리거)
                            forceCC && forceCC();
                        }
                        if(jsonStr.length > 4000) {
                            let chunksCount = Math.ceil(jsonStr.length / 4000);
                            for(let i=0; i<chunksCount; i++) {
                                let chunk = jsonStr.substring(i * 4000, (i+1) * 4000);
                                AndTransBridge.onSubtitleExtractedChunked(chunk, i, chunksCount);
                            }
                        } else {
                            AndTransBridge.onSubtitleExtracted(jsonStr);
                        }
                    }
                });
            });

            window.andTransObserver.observe(targetNode, { childList: true, subtree: true });
        })();
    """.trimIndent()

    val SHUTDOWN_SCRIPT = """
        javascript:(function() {
            if (window.andTransObserver) {
                window.andTransObserver.disconnect();
                window.andTransObserver = null;
            }
            if (window.andTransIgniteId) {
                clearInterval(window.andTransIgniteId);
                window.andTransIgniteId = null;
            }
        })();
    """.trimIndent()
}

