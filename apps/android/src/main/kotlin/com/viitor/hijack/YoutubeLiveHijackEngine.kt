package com.viitor.hijack

import com.viitor.hijack.IHijackEngine

/**
 * [YoutubeLiveHijackEngine] - "Total Fishnet" 레이더 엔진 V5 (Final Stable)
 * 도메인/경로 가리지 않고 모든 텍스트 패킷 및 메모리/DOM 전수 조사
 */
object YoutubeLiveHijackEngine : IHijackEngine {
    override val urlPattern = Regex(".*youtube\\.com/watch.*")
    override val shutdownScript = "if(window.LiveRadar) window.LiveRadar.active = false;"

    override val injectScript = """
        (function() {
            const VER = 'STABLE-V7-DEEP';
            const TAG = '🔱 [SONAR-V7]';
            
            const log = (m) => {
                if (window.AndTransBridge) window.AndTransBridge.onLog(TAG + ' ' + m);
            };
            
            if (window.andTransV7Active) {
                log('Status: Sonar-V7 already diving.');
                return;
            }
            window.andTransV7Active = true;

            const DeepSonar = {
                seen: new Set(),
                
                isNoise(t) {
                    if (!t || t.length < 2) return true;
                    // 한글이 포함되어 있으면 노이즈 게이트 무조건 통과 (최우선순위)
                    if (/[\uAC00-\uD7AF]/.test(t)) return false; 
                    if (/^[0-9:\s,.]+$/.test(t)) return true;
                    return false;
                },

                analyze(raw, source) {
                    if (!raw || raw.length < 5) return;

                    // 1. JSON-LIKE 캡처
                    const patterns = [
                        /"text"\s*:\s*"(.*?)"/g,
                        /"captionText"\s*:\s*"(.*?)"/g,
                        /"utf8"\s*:\s*"(.*?)"/g,
                        /"simpleText"\s*:\s*"(.*?)"/g
                    ];

                    patterns.forEach(p => {
                        let m;
                        while ((m = p.exec(raw)) !== null) {
                            this.dispatch(m[1], source + '-JSON');
                        }
                    });

                    // 2. 바이너리/Protobuf 내 한글 강제 인양 (Deep Dive)
                    // 한글 단어(2자 이상)가 포함된 연속된 문자열 뭉치 추출
                    const koreanChunks = raw.match(/[\uAC00-\uD7AF\s\w,.!?]{2,100}/g);
                    if (koreanChunks) {
                        koreanChunks.forEach(chunk => {
                            if ([\uAC00-\uD7AF].test(chunk)) {
                                this.dispatch(chunk, source + '-RAW');
                            }
                        });
                    }
                },

                dispatch(txt, src) {
                    if (!txt) return;
                    let clean = txt;
                    try {
                        // 유니코드 이스케이프 복구 (\uXXXX)
                        clean = txt.replace(/\\u([0-9a-fA-F]{4})/g, (m, grp) => String.fromCharCode(parseInt(grp, 16))).trim();
                    } catch(e) {}
                    
                    if (!this.isNoise(clean) && !this.seen.has(clean)) {
                        this.seen.add(clean);
                        log('🌊 [HIT][' + src + '] ' + clean);
                        if (window.AndTransBridge && window.AndTransBridge.onSubtitleCaptured) {
                            window.AndTransBridge.onSubtitleCaptured(clean);
                        }
                    }
                },

                hookNetwork() {
                    const self = this;
                    const decoder = new TextDecoder('utf-8');

                    // A. FETCH Hook
                    const of = window.fetch;
                    window.fetch = async function(...args) {
                        const res = await of.apply(this, args);
                        const url = args[0]?.toString() || '';
                        
                        if (url.includes('youtubei/v1/') || url.includes('videoplayback')) {
                            res.clone().arrayBuffer().then(b => {
                                // 바이너리 데이터를 텍스트로 일단 강제 변환 (utf-8)
                                self.analyze(decoder.decode(b), 'FETCH');
                            }).catch(() => {});
                        }
                        return res;
                    };

                    // B. XHR Hook
                    const ox = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(m, u) {
                        this.addEventListener('load', function() {
                            if (u.includes('v1/') || u.includes('googlevideo')) {
                                try {
                                    const r = this.responseText || decoder.decode(this.response);
                                    self.analyze(r, 'XHR');
                                } catch(e) {}
                            }
                        });
                        return ox.apply(this, arguments);
                    };
                }
            };

            DeepSonar.hookNetwork();
            log('🚀 SONAR-V7-DEEP IGNITED: Target Korean Live Capture.');
        })();
    """.trimIndent()
}
