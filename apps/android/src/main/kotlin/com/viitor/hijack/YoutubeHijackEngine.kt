package com.viitor.hijack

import android.util.Log

/**
 * [YoutubeHijackEngine] - 유튜브 자막 하이재킹 엔진 (Platinum V5 - Ultra-Robust Protection)
 * - URL: youtube.com
 * - 성능: 0.1s 고정밀 앵커링 + MutationObserver 하이브리드
 * - 강화: 전역 에러 캐칭 및 브릿지 연결성 강화
 */
object YoutubeHijackEngine : IHijackEngine {
    // VOD 엔진은 일반 페이지용 (라이브 레이더에 양보)
    override val urlPattern = Regex("m\\.youtube\\.com/(?!watch).*")

    override val injectScript = """
        (function() {
            const TAG = '[ANDTRANS][SHADOW-V5]';
            console.log(TAG + ' Initiating Ultra-Robust Injection...');

            try {
                // 기존 인스턴스 정리
                if (window.andTransIntervalId) {
                    clearInterval(window.andTransIntervalId);
                    console.log(TAG + ' Previous interval cleared.');
                }
                if (window.andTransObserver) {
                    window.andTransObserver.disconnect();
                    console.log(TAG + ' Previous observer disconnected.');
                }

                const ShadowCore = {
                    lastUrl: "",
                    lastText: "",
                    lastSyncTime: 0,
                    jaTrackLock: false,
                    isFullScriptLoaded: false, // [V5-OPT] 대본 로드 완료 플래그
                    
                    ignite() {
                        console.log(TAG + ' Engine Igniting (0.1s Precision Mode).');
                        this.installNetworkSniffer();
                        this.installStealthCSS();
                        
                        // [V5-MODE-CHECK] 라이브 여부에 따른 가벼운 상태 체크
                        this.checkModeChange();
                        
                        // 100ms 고성능 감시 루프
                        window.andTransIntervalId = setInterval(() => {
                            try { this.tick(); } catch(e) { console.error(TAG + ' Tick Error: ' + e); }
                        }, 100);
                        this.setupObserver();
                        this.bindVideoEvents();
                        this.fetchFullScript(); // [V5-EARLY-SYNC]
                        console.log(TAG + ' Engine READY.');
                    },

                    installNetworkSniffer() {
                        if (window.andTransNetworkSnifferInstalled) return;
                        window.andTransNetworkSnifferInstalled = true;
                        
                        const originalFetch = window.fetch;
                        window.fetch = async function() {
                            // [YIELD-TO-LIVE] 라이브 모드라면 스니핑 주도권을 LiveRadar에게 완전히 양보
                            if (window.LiveRadar && window.LiveRadar.active) return originalFetch.apply(this, arguments);

                            const url = arguments[0];
                            const response = await originalFetch.apply(this, arguments);
                            if (typeof url === 'string' && url.includes('api/timedtext')) {
                                response.clone().text().then(text => {
                                    try {
                                        if(text && text.includes('events')) {
                                            window.AndTransBridge?.onLog?.('🎯 [NETWORK-FETCH] Sniffed timedtext API response! Size: ' + text.length);
                                            if(text.length > 4000) {
                                                let chunksCount = Math.ceil(text.length / 4000);
                                                for(let i=0; i<chunksCount; i++) {
                                                    let chunk = text.substring(i * 4000, (i+1) * 4000);
                                                    window.AndTransBridge?.onFullTranscriptCapturedChunked?.(chunk, i, chunksCount);
                                                }
                                            } else {
                                                window.AndTransBridge?.onFullTranscriptCaptured?.(text);
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
                                            window.AndTransBridge?.onLog?.('🎯 [NETWORK-XHR] Sniffed timedtext API response! Size: ' + text.length);
                                            if(text.length > 4000) {
                                                let chunksCount = Math.ceil(text.length / 4000);
                                                for(let i=0; i<chunksCount; i++) {
                                                    let chunk = text.substring(i * 4000, (i+1) * 4000);
                                                    window.AndTransBridge?.onFullTranscriptCapturedChunked?.(chunk, i, chunksCount);
                                                }
                                            } else {
                                                window.AndTransBridge?.onFullTranscriptCaptured?.(text);
                                            }
                                        }
                                    } catch (e) {}
                                }
                            });
                            return originalXHR.apply(this, arguments);
                        };
                        console.log(TAG + ' Network Sniffer (Fetch/XHR) Active.');
                    },

                    installStealthCSS() {
                        if (document.getElementById('andtrans-v5-style')) return;
                        const style = document.createElement('style');
                        style.id = 'andtrans-v5-style';
                        style.textContent = '.ytp-caption-window-container { display: none !important; } .ytp-caption-segment { display: none !important; }';
                        document.head.appendChild(style);
                        console.log(TAG + ' Stealth CSS applied.');
                    },

                    tick() {
                        const video = document.querySelector('video');
                        if (!video) return;

                        // URL 및 광고 전환 감지
                        if (this.lastUrl !== location.href) {
                            console.log(TAG + ' [FLUSH] URL Shift detected: ' + location.href);
                            this.lastUrl = location.href;
                            this.jaTrackLock = false;
                            this.isFullScriptLoaded = false; // [V5-OPT] 로드 상태 리셋

                            // [BRIDGE-SIGNAL] 네이티브에 비디오 변경 알림 (버퍼 리셋 트리거)
                            try {
                                const vId = new URL(location.href).searchParams.get('v');
                                if (vId && window.AndTransBridge && window.AndTransBridge.onVideoDetected) {
                                    window.AndTransBridge.onVideoDetected(vId);
                                    this.fetchFullScript(); // [BULK-SYNC] 7000자 전체 대본 확보 시도
                                }
                            } catch(e) {}
                            
                            this.enforceCC();
                            this.checkModeChange();
                        }

                        // [YIELD-TO-LIVE] 라이브 패킷 레이더가 작동 중이면 VOD 엔진의 동작을 정지 (양보)
                        if (window.LiveRadar && window.LiveRadar.active) return;

                        // CC 버튼 상태 감시는 이제 autoSelectJA()가 총괄함

                        // Native 브릿지로 시간 동기화 전송 (0.3s)
                        const now = Date.now();
                        if (now - this.lastSyncTime >= 300) {
                            this.lastSyncTime = now;
                            const curTime = Math.floor(video.currentTime * 1000);
                            if (window.AndTransBridge) window.AndTransBridge.onTimeUpdate(curTime);
                        }
                        
                        this.capture(false);
                    },

                    enforceCC() {
                        this.autoSelectJA();
                    },
 
                    autoSelectJA() {
                        if (this.jaTrackLock) return;
                        
                        // [V5-SMART-CC] 설정 메뉴를 완전히 배제한 배후 타겟팅
                        const ccBtn = document.querySelector('.ytp-subtitles-button') || 
                                      Array.from(document.querySelectorAll('button[aria-label*="자막"], button[aria-label*="Captions"]'))
                                           .find(b => !/설정|Settings/i.test(b.getAttribute('aria-label') || ''));
                        
                        if (ccBtn) {
                            if (ccBtn.getAttribute('aria-pressed') !== 'true') {
                                // [BETA-GUARD] 배후 이벤트(Pointer/Mouse)는 오류 방지를 위해 잠시 주석 처리
                                // ccBtn.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
                                // ccBtn.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
                                ccBtn.click();
                            }
                            this.jaTrackLock = true;
                        } else {
                            setTimeout(() => this.autoSelectJA(), 800);
                        }
                    },

                    capture(force) {
                        try {
                            // [V5-OPT] 대본이 이미 있다면 실시간 CC 캡처(DOM 스캔) 생략
                            if (this.isFullScriptLoaded && !force) return;

                            const segs = document.querySelectorAll('.ytp-caption-segment');
                            if (segs.length === 0) return;

                            // [V5-LINE-SEPARATION] 공백이 아닌 || 구분자로 합쳐서 1레이어 개별성 유지
                            const text = Array.from(segs).map(s => s.innerText.trim()).filter(t => !!t).join(' || ');
                            if (!text) return;

                            if (force || text !== this.lastText) {
                                this.lastText = text;
                                const video = document.querySelector('video');
                                const aTime = video ? Math.floor(video.currentTime * 1000) : 0;
                                
                                const payload = JSON.stringify({ anchorTimeMs: aTime, origin1layer: text });
                                console.log(TAG + ' [HIJACK] Captured: ' + text.substring(0, 20));
                                 if (payload.length > 3900) {
                                    this.isFullScriptLoaded = false; 
                                    this.fetchFullScript(); 
                                }

                                // [CHUNK-RELAY] 4000자 단위 청킹 스트림 전송
                                if (window.AndTransBridge) {
                                    if (payload.length > 4000) {
                                        const total = Math.ceil(payload.length / 4000);
                                        for (let i = 0; i < total; i++) {
                                            const chunk = payload.substring(i * 4000, (i + 1) * 4000);
                                            window.AndTransBridge.onSubtitleExtractedChunked(chunk, i, total);
                                        }
                                    } else {
                                        window.AndTransBridge.onSubtitleExtracted(payload);
                                    }
                                }
                            }
                        } catch (e) {
                            console.error(TAG + ' Capture Failed: ' + e);
                        }
                    },

                    setupObserver() {
                        const player = document.querySelector('.html5-video-player') || document.body;
                        if (window.andTransObserver) window.andTransObserver.disconnect();
                        
                        let lastUrl = window.location.href;
                        window.andTransObserver = new MutationObserver(() => {
                            const currentUrl = window.location.href;
                            // [NAV-GUARD] URL 변경 감지 (AND 연산자 확장 로직)
                            if (currentUrl !== lastUrl) {
                                console.log(TAG + ' [NAV] URL Changed, re-anchoring.');
                                lastUrl = currentUrl;
                                this.lastText = ''; // URL 변경 시 중복 체크 리셋
                                this.capture(true);
                                this.autoSelectJA(); // 신규 영상에 대한 트랙 재탐색
                                return;
                            }
                            this.capture(false);
                        });
                        window.andTransObserver.observe(player, { childList: true, subtree: true });
                        console.log(TAG + ' Hybrid Navigation Guard Armed.');
                    },

                    bindVideoEvents() {
                        const v = document.querySelector('video');
                        if (v) {
                            v.addEventListener('seeking', () => this.capture(true));
                            v.addEventListener('play', () => this.capture(true));
                            v.addEventListener('pause', () => this.capture(true));
                        }
                        // 수동 클릭 대응
                        document.addEventListener('click', (e) => {
                            if (e.target.closest('.ytp-subtitles-button') || e.target.closest('.ytp-settings-button')) {
                                setTimeout(() => this.capture(true), 600);
                            }
                        }, true);
                    },

                    // [REMOTE-CONTROL] 외부(Native)로부터의 재생 제어 요청 수신
                    toggle() {
                        const v = document.querySelector('video');
                        if (v) {
                            if (v.paused) v.play();
                            else v.pause();
                            console.log(TAG + ' Playback toggled remotely.');
                        }
                    },
                    seekTo(ms) {
                        const v = document.querySelector('video');
                        if (v) {
                            v.currentTime = ms / 1000.0;
                            v.play(); // 점프 후 자동 재생
                            console.log(TAG + ' Seek to ' + ms + 'ms executed.');
                        }
                    },

                    fetchFullScript() {
                        try {
                            // [V5-BRUTE-FORCE] 전역 HTML 정규식 전수 조사 (가장 강력한 입구 전략)
                            const html = document.documentElement.innerHTML;
                            const timedTextRegex = /"(https:[^"]*?\/api\/timedtext[^"]*?)"/g;
                            let match;
                            let baseUrl = null;
                            let fetchOrigin = "UNKNOWN";

                            while ((match = timedTextRegex.exec(html)) !== null) {
                                let url = match[1].replace(/\\u0026/g, '&').replace(/\\/g, '');
                                if (url.includes('vssId') && (url.includes('ja') || url.includes('.ja'))) {
                                    baseUrl = url;
                                    fetchOrigin = "0순위: HTML-REGEX-BRUTEFORCE";
                                    break; 
                                }
                            }

                            // 2차 백업: window 객체 얕은 탐색 (데스크탑 / 모바일 범용)
                            if (!baseUrl) {
                                const tracksInit = window.ytInitialPlayerResponse?.captions?.playerCaptionsTracklistRenderer?.captionTracks;
                                const tracksConfig = window.ytplayer?.config?.args?.raw_player_response?.filter ? window.ytplayer.config.args.raw_player_response.filter(x => x.captionTracks)?.pop()?.captionTracks : window.ytplayer?.config?.args?.raw_player_response?.captions?.playerCaptionsTracklistRenderer?.captionTracks;
                                
                                // 모바일 전용 3차 백업 (ytm-watch)
                                const ytmWatch = document.querySelector('ytm-watch');
                                const tracksYtm = ytmWatch ? ytmWatch.playerResponse?.captions?.playerCaptionsTracklistRenderer?.captionTracks : null;

                                let finalTracks = null;
                                if (tracksInit && tracksInit.length > 0) { finalTracks = tracksInit; fetchOrigin = "1순위: window.ytInitialPlayerResponse"; }
                                else if (tracksYtm && tracksYtm.length > 0) { finalTracks = tracksYtm; fetchOrigin = "3순위: 모바일 DOM <ytm-watch>"; }
                                else if (tracksConfig && tracksConfig.length > 0) { finalTracks = tracksConfig; fetchOrigin = "2순위: window.ytplayer.config"; }

                                const ja = Array.isArray(finalTracks) ? finalTracks.find(t => t.languageCode === 'ja' || t.vssId?.includes('ja')) : null;
                                if (ja?.baseUrl) baseUrl = ja.baseUrl;
                            }

                            if (baseUrl) {
                                window.AndTransBridge?.onLog?.('🎯 [V5-SCRIPT-ORIGIN] Found Script URL via: ' + fetchOrigin);
                                fetch(baseUrl + '&fmt=json3').then(x => x.json()).then(d => {
                                    const payload = JSON.stringify(d);
                                    window.AndTransBridge?.onLog?.('🔥 Bulk Script Data Captured: ' + payload.length + ' chars');

                                    
                                    if(payload.length > 4000) {
                                        let chunksCount = Math.ceil(payload.length / 4000);
                                        for(let i=0; i<chunksCount; i++) {
                                            let chunk = payload.substring(i * 4000, (i+1) * 4000);
                                            window.AndTransBridge?.onFullTranscriptCapturedChunked?.(chunk, i, chunksCount);
                                        }
                                    } else {
                                        window.AndTransBridge?.onFullTranscriptCaptured?.(payload);
                                    }
                                }).catch(e => window.AndTransBridge?.onLog?.('Bulk Fetch Fail: ' + e));
                            } else {
                                setTimeout(() => this.fetchFullScript(), 5000);
                            }
                        } catch(err) {
                            window.AndTransBridge?.onLog?.('JS-FATAL: ' + err.message);
                        }
                    },

                    installStealthCSS() {
                        const style = document.createElement('style');
                        style.textContent = '.ytp-caption-window-container { visibility: hidden !important; opacity:0; }';
                        document.head.appendChild(style);
                    },

                    checkModeChange() {
                        if (window.LiveRadar) {
                            window.LiveRadar.active = true;
                        }
                    }
                };

                ShadowCore.ignite();
                window.andTransShadow = ShadowCore; // 디버깅용 노출

            } catch (err) {
                console.error(TAG + ' FATAL CRASH: ' + err);
            }
        })();
    """.trimIndent()

    override val shutdownScript = """
        (function() {
            if (window.andTransIntervalId) { clearInterval(window.andTransIntervalId); window.andTransIntervalId = null; }
            if (window.andTransObserver) { window.andTransObserver.disconnect(); window.andTransObserver = null; }
            console.log('[ANDTRANS][SHADOW] Shutdown executed.');
        })();
    """.trimIndent()
}
