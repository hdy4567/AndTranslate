package com.viitor.hijack

import android.util.Log

/**
 * [YoutubeHijackEngine] - 유튜브 자막 하이재킹 엔진 (Platinum V5 - Ultra-Robust Protection)
 * - URL: youtube.com
 * - 성능: 0.1s 고정밀 앵커링 + MutationObserver 하이브리드
 * - 강화: 전역 에러 캐칭 및 브릿지 연결성 강화
 */
object YoutubeHijackEngine : IHijackEngine {

    override val urlPattern = Regex("(youtube\\.com|youtu\\.be)")

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
                    
                    ignite() {
                        console.log(TAG + ' Engine Igniting (0.1s Precision Mode).');
                        this.installStealthCSS();
                        
                        // 100ms 고성능 감시 루프
                        window.andTransIntervalId = setInterval(() => {
                            try { this.tick(); } catch(e) { console.error(TAG + ' Tick Error: ' + e); }
                        }, 100);
                        this.setupObserver();
                        this.bindVideoEvents();
                        console.log(TAG + ' Engine READY.');
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

                            // [BRIDGE-SIGNAL] 네이티브에 비디오 변경 알림 (버퍼 리셋 트리거)
                            try {
                                const vId = new URL(location.href).searchParams.get('v');
                                if (vId && window.AndTransBridge && window.AndTransBridge.onVideoDetected) {
                                    window.AndTransBridge.onVideoDetected(vId);
                                    this.fetchFullScript(); // [BULK-SYNC] 7000자 전체 대본 확보 시도
                                }
                            } catch(e) {}
                            
                            this.enforceCC();
                        }

                        // CC 버튼 강제 활성화 (Look: OFF, Data: ON)
                        const ccBtn = document.querySelector('.ytp-subtitles-button');
                        if (ccBtn && ccBtn.getAttribute('aria-pressed') !== 'true') {
                            console.log(TAG + ' Forcing CC Activation...');
                            ccBtn.click();
                        }

                        // Native 브릿지로 시간 동기화 전송 (0.3s)
                        const now = Date.now();
                        if (now - this.lastSyncTime >= 300) {
                            this.lastSyncTime = now;
                            const curTime = Math.floor(video.currentTime * 1000);
                            if (window.AndTransBridge) window.AndTransBridge.onTimeUpdate(curTime);
                        }
                        
                        // 0.1s 주기로 자막 변화도 직접 감시 (폴링 보조)
                        this.capture(false);
                    },

                    enforceCC() {
                        setTimeout(() => this.autoSelectJA(), 800);
                    },

                    autoSelectJA() {
                        if (this.jaTrackLock) return;
                        const sets = document.querySelector('.ytp-settings-button');
                        if (!sets) return;
                        
                        sets.click();
                        setTimeout(() => {
                            const items = Array.from(document.querySelectorAll('.ytp-menuitem'));
                            const subMenu = items.find(i => i.innerText.includes('자막') || i.innerText.includes('Subtitles'));
                            if (subMenu) {
                                subMenu.click();
                                setTimeout(() => {
                                    const tracks = Array.from(document.querySelectorAll('.ytp-menuitem'));
                                    // 정밀 매칭: 일본어[1] 또는 Japanese 수동 트랙 우선 선택 (자동 생성 트랙 엄격 배제)
                                    const target = tracks.find(t => 
                                        (/일본어|Japanese|ja/i.test(t.innerText)) && 
                                        (!t.innerText.includes('자동')) && 
                                        (!t.innerText.includes('generated')) &&
                                        (!t.innerText.includes('Auto'))
                                    );
                                    if (target) {
                                        console.log(TAG + ' Targeted Japanese track found: ' + target.innerText);
                                        target.click();
                                        this.jaTrackLock = true;
                                    }
                                    sets.click();
                                }, 400);
                            } else sets.click();
                        }, 400);
                    },

                    capture(force) {
                        try {
                            const segs = document.querySelectorAll('.ytp-caption-segment');
                            if (segs.length === 0) return;

                            const text = Array.from(segs).map(s => s.innerText).join(' ').trim();
                            if (!text) return;

                            if (force || text !== this.lastText) {
                                this.lastText = text;
                                const video = document.querySelector('video');
                                const aTime = video ? Math.floor(video.currentTime * 1000) : 0;
                                
                                const payload = JSON.stringify({ anchorTimeMs: aTime, origin1layer: text });
                                console.log(TAG + ' [HIJACK] Captured: ' + text.substring(0, 20));

                                // [CHUNK-RELAY] 7000자 단위 청킹 스트림 전송
                                if (window.AndTransBridge) {
                                    if (payload.length > 7000) {
                                        const total = Math.ceil(payload.length / 7000);
                                        for (let i = 0; i < total; i++) {
                                            const chunk = payload.substring(i * 7000, (i + 1) * 7000);
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
                                this.autoTrack(); // 신규 영상에 대한 트랙 재탐색
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
                            const pResp = window.ytInitialPlayerResponse || (window.ytplayer && window.ytplayer.config && window.ytplayer.config.args ? JSON.parse(window.ytplayer.config.args.player_response) : null);
                            if (!pResp || !pResp.captions) return;
                            
                            const tracks = pResp.captions.playerCaptionsTracklistRenderer.captionTracks;
                            const jaTrack = tracks.find(t => t.languageCode === 'ja' || (t.vssId && t.vssId.includes('.ja')));
                            if (jaTrack && jaTrack.baseUrl) {
                                console.log(TAG + ' Bulk Script Target: ' + jaTrack.baseUrl);
                                fetch(jaTrack.baseUrl + '&fmt=json3')
                                    .then(r => r.json())
                                    .then(data => {
                                        if (window.AndTransBridge && window.AndTransBridge.onFullTranscriptCaptured) {
                                            window.AndTransBridge.onFullTranscriptCaptured(JSON.stringify(data));
                                            console.log(TAG + ' Success: 7000+ characters Bulk Pushed.');
                                        }
                                    }).catch(err => console.error(TAG + ' Fetch Error: ' + err));
                            }
                        } catch(e) { console.error(TAG + ' Bulk Prep Error: ' + e); }
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
