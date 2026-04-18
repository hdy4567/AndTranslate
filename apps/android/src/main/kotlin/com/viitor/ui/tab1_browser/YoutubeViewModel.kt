package com.viitor.ui.tab1_browser

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viitor.ui.tab1_browser.domain.GoogleTranslationAgent
import com.viitor.ui.tab1_browser.domain.NetworkTranslationException
import com.viitor.ui.tab1_browser.domain.OverlayModel
import com.viitor.ui.tab1_browser.domain.SubtitlePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class YoutubeViewModel : ViewModel() {

    private val _overlayState = MutableStateFlow(OverlayModel.empty())
    val overlayState: StateFlow<OverlayModel> = _overlayState

    private val _currentTimeMs = MutableStateFlow(0L)
    val currentTimeMs: StateFlow<Long> = _currentTimeMs

    var isPron3LayerEnabled: Boolean = false
    private val translationAgent = GoogleTranslationAgent()
    
    // [HIGH-PERF-CHECK] 일본어 포함 여부 고해상도 정규식 캐싱
    private val JP_REGEX = Regex("[\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FFF]")

    private var isEngineAlive = true
    private var batchJob: Job? = null
    private val subtitleBuffer = mutableListOf<SubtitlePayload>()
    private val bufferMutex = Mutex()
    private var lastOrigin1L = ""
    private var lastAnchorTime = 0L 

    // [STREAM-CACHE] 타이핑 동기화를 위한 최신 번역 데이터 보관소
    private val fullTranslationCache = mutableMapOf<Long, com.viitor.pipeline.core.SubtitleData>()

    // [VIDEO-CHANGE-CALLBACK] 영상 전환 시 외부(Fragment/SharedVM)와 연동하기 위한 콜백
    var onVideoDetected: ((String) -> Unit)? = null

    fun processExtractedSubtitle(payload: SubtitlePayload) {
        if (!isEngineAlive) return

        // [NOISE-CANCELING] '자동 생성됨' 등 저품질 자막 텍스트 원천 차단
        val raw = payload.origin1layer.trim()
        if (raw.contains("(자동 생성됨)") || raw.contains("(auto-generated)")) {
            Log.w("YoutubeViewModel", "[NOISE-FILTER] Discarded auto-generated label text: $raw")
            return
        }

        // [HYPER-PASS] 이미 전체 대본(Registry)에 번역본이 있다면 무거운 API 루프 없이 즉시 투출
        val cached = com.viitor.pipeline.core.GlobalSubtitleRelay.findTranscriptMatch(payload.anchorTimeMs)
        if (cached != null && !cached.tras2layer.isNullOrBlank()) {
            com.viitor.pipeline.core.GlobalSubtitleRelay.relay(cached)
            Log.d("YoutubeViewModel", "[HYPER-PASS] Instant Relay from Registry for Time: ${payload.anchorTimeMs}")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            bufferMutex.withLock {
                // [FLUSH-LOGIC] 시간 점프 감지 시 버퍼 즉시 비움 (문맥 오염 방지)
                val isTimeJump = Math.abs(payload.anchorTimeMs - lastAnchorTime) > 2000
                
                // [BLANKING-GUARD] 문맥 전환 또는 유효기간(2.5s) 경과 시 오버레이 잔상 즉시 소거
                val isNewContext = !payload.origin1layer.startsWith(lastOrigin1L) && 
                                   !lastOrigin1L.startsWith(payload.origin1layer)
                
                // [HEARTBEAT-STALE-CHECK] 마지막 캡처와 실제 영상 시간이 2.5초 이상 벌어지면 '말이 끝난 것'으로 간주
                val isStale = Math.abs(currentTimeMs.value - payload.anchorTimeMs) > 2500
                                   
                if (isTimeJump || isStale || (isNewContext && lastOrigin1L.isNotBlank())) {
                    subtitleBuffer.clear()
                    _overlayState.value = OverlayModel.empty()
                    Log.i("YoutubeViewModel", "[STALE-PURGE] Context end or Time-out detected. UI Cleared.")
                    if (isStale) return@launch // 말이 끝났으면 처리 중단
                }

                if (lastOrigin1L == payload.origin1layer && !isTimeJump) return@launch
                
                lastOrigin1L = payload.origin1layer
                lastAnchorTime = payload.anchorTimeMs
                
                // [SYNC-FIRST] ID 고정 없이 원본 타임스탬프 유지
                if (subtitleBuffer.isNotEmpty() && payload.origin1layer.startsWith(subtitleBuffer.last().origin1layer)) {
                    subtitleBuffer[subtitleBuffer.size - 1] = payload
                } else if (subtitleBuffer.size < 5) {
                    subtitleBuffer.add(payload)
                }

                // [TRIPLE-SYNC-STREAMING] 오버레이(Relay) 스트리밍 강제 동기화
                // 배치 내 이전 문장들은 전량 노출하고, 현재 문장만 타이핑 진행도 적용
                val fullRelayJp = subtitleBuffer.joinToString("\n") { it.origin1layer }
                val fullRelayKo = StringBuilder()
                val fullRelayPron = StringBuilder()

                subtitleBuffer.forEachIndexed { index, sb ->
                    val cached = fullTranslationCache[sb.anchorTimeMs]
                    if (cached != null) {
                        if (index == subtitleBuffer.size - 1) {
                            // 마지막 문장: 타이핑 진행률 계산
                            val progress = (sb.origin1layer.length.toFloat() / cached.origin1layer.length.toFloat()).coerceIn(0f, 1f)
                            fullRelayKo.append(cached.tras2layer?.let { it.substring(0, (it.length * progress).toInt()) } ?: "")
                            fullRelayPron.append(cached.pron3layer?.let { it.substring(0, (it.length * progress).toInt()) } ?: "")
                        } else {
                            // 이전 문장들: 전량
                            fullRelayKo.append(cached.tras2layer).append("\n")
                            fullRelayPron.append(cached.pron3layer).append("\n")
                        }
                    }
                }

                val finalKo = fullRelayKo.toString().trim()
                val finalPron = fullRelayPron.toString().trim()

                com.viitor.pipeline.core.GlobalSubtitleRelay.relay(
                    com.viitor.pipeline.core.SubtitleData(
                        anchorTimeMs = (payload.anchorTimeMs - 400).coerceAtLeast(0),
                        origin1layer = fullRelayJp,
                        tras2layer = if (finalKo.isNotEmpty()) finalKo else _overlayState.value.tras2layer,
                        pron3layer = if (finalPron.isNotEmpty()) finalPron else _overlayState.value.pron3layer
                    )
                )
            }

            // 배치 루프가 작동 중이지 않다면 시작 (2초 주기)
            if (batchJob == null || !batchJob!!.isActive) {
                startBatchLoop()
            }
        }
    }

    private fun startBatchLoop() {
        batchJob = viewModelScope.launch(Dispatchers.IO) {
            while (isEngineAlive) {
                // [RHYTHM-SYNC] 번역 주기를 1초로 단축 (실시간성 강화)
                delay(1000)
                
                // [BATCH-JOIN] 버퍼에 쌓인 모든 문장을 하나로 합쳐서 번역 요청
                val batchToProcess = bufferMutex.withLock {
                    if (subtitleBuffer.isEmpty()) return@withLock null
                    val copy = subtitleBuffer.toList()
                    subtitleBuffer.clear()
                    copy
                } ?: continue

                // 모든 문장을 줄바꿈으로 합침 (번역 질 향상 및 유실 방지)
                val combinedOrigin1L = batchToProcess.joinToString("\n") { it.origin1layer }
                val lastPayload = batchToProcess.last()
                val masterTimeId = lastPayload.anchorTimeMs
                
                val hasJapanese = JP_REGEX.containsMatchIn(combinedOrigin1L)
                if (!hasJapanese) continue

                // [UNIFIED-TRANSLATION-V5] 배치를 하나의 문맥으로 묶어 고품질 번역 확보
                val futureCtx = com.viitor.pipeline.core.GlobalSubtitleRelay.findFutureTranscripts(masterTimeId, 3)
                
                // 대본에서 전체 배치가 이미 존재하는지 확인하는 대신, 전체 배치를 한 번에 번역하여 문맥적 완성도를 최우선함
                val res = translationAgent.fetchFullTranslation(combinedOrigin1L, futureCtx)
                val tras2layerLocal = res.first
                val rawPron3layer = res.second

                // [MASTER-CLOCK-SYNC] 2번 레이어 추적성 강화를 위한 시간 축 정밀 고정
                val uiSyncTime = (masterTimeId - 400).coerceAtLeast(0)

                // [SMART-DECOMPOSE] 1:N 미스매치(원문 다수 : 번역 단수) 해결을 위한 고지능 분해 로직
                val currentJpLines = batchToProcess.map { payload -> payload.origin1layer }
                val rawTransLines = tras2layerLocal.split("\n").filter { line -> line.isNotBlank() }
                
                // [DECOMPOSE-RECOVERY] 원문 개수에 맞춰 번역문을 재생산하거나 재배치함
                val finalTransLines = mutableListOf<String>()
                
                if (rawTransLines.size >= currentJpLines.size) {
                    finalTransLines.addAll(rawTransLines.takeLast(currentJpLines.size))
                } else {
                    val mergedTrans = rawTransLines.joinToString(" ")
                    val recovered = currentJpLines.map { jp ->
                        com.viitor.pipeline.core.GlobalSubtitleRelay.findTranscriptMatchByText(jp)?.tras2layer
                    }
                    
                    if (recovered.all { rec -> rec != null }) {
                        finalTransLines.addAll(recovered.filterNotNull())
                    } else {
                        val sentenceSplit = mergedTrans.split(Regex("(?<=[.!?｡])\\s+")).filter { s -> s.isNotBlank() }
                        if (sentenceSplit.size == currentJpLines.size) {
                            finalTransLines.addAll(sentenceSplit)
                        } else {
                            val totalJpLen = currentJpLines.sumOf { j -> j.length }.coerceAtLeast(1)
                            var currentPos = 0
                            currentJpLines.forEachIndexed { idx, jp ->
                                if (idx == currentJpLines.size - 1) {
                                    finalTransLines.add(mergedTrans.substring(currentPos).trim())
                                } else {
                                    val ratio = jp.length.toFloat() / totalJpLen.toFloat()
                                    val takeLen = (mergedTrans.length.toFloat() * ratio).toInt().coerceAtLeast(1)
                                    val endPos = (currentPos + takeLen).coerceAtMost(mergedTrans.length)
                                    finalTransLines.add(mergedTrans.substring(currentPos, endPos).trim())
                                    currentPos = endPos
                                }
                            }
                        }
                    }
                }

                Log.d("YoutubeViewModel", "[SMART-DECOMPOSE] Target: ${currentJpLines.size}, Result: ${finalTransLines.size}")
                
                batchToProcess.forEachIndexed { index, payload ->
                    val tLine = finalTransLines.getOrNull(index) ?: ""
                    val rLine = rawPron3layer.split("\n").getOrNull(index) ?: ""
                    
                    val pKo = if (com.viitor.pipeline.core.GlobalSubtitleRelay.isPron3LayerEnabled && rLine.isNotBlank()) {
                         com.viitor.pipeline.core.JapanesePronunciationAgent.convertRomajiToHangul(rLine)
                    } else ""

                    val data = com.viitor.pipeline.core.SubtitleData(
                        anchorTimeMs = (payload.anchorTimeMs - 400).coerceAtLeast(0),
                        origin1layer = payload.origin1layer,
                        tras2layer = tLine,
                        pron3layer = pKo
                    )
                    
                    fullTranslationCache[payload.anchorTimeMs] = data.copy(anchorTimeMs = payload.anchorTimeMs)
                    com.viitor.pipeline.core.GlobalSubtitleRelay.relay(data)
                }

                if (fullTranslationCache.size > 20) {
                    val oldestKey = fullTranslationCache.keys.first()
                    fullTranslationCache.remove(oldestKey)
                }

                val finalOverlay = if (finalTransLines.isNotEmpty()) {
                    val fullKoBatch = StringBuilder()
                    val fullPronBatch = StringBuilder()
                    
                    batchToProcess.forEachIndexed { index, payload ->
                        val tLine = finalTransLines.getOrNull(index) ?: ""
                        val pKo = if (com.viitor.pipeline.core.GlobalSubtitleRelay.isPron3LayerEnabled) {
                             val rLine = rawPron3layer.split("\n").getOrNull(index) ?: ""
                             if (rLine.isNotBlank()) com.viitor.pipeline.core.JapanesePronunciationAgent.convertRomajiToHangul(rLine) else ""
                        } else ""
                        
                        if (index == batchToProcess.size - 1) {
                            // 마지막 문장: 타이핑 진행률 계산 (0.0 ~ 1.0)
                            // 1레이어(일본어)가 늘어나는 비율에 맞춰 2레이어(한국어)도 한 글자씩 노출됨
                            val progress = (payload.origin1layer.length.toFloat() / currentJpLines.last().length.toFloat()).coerceIn(0f, 1f)
                            val visibleKoLen = (tLine.length * progress).toInt()
                            val visiblePronLen = (pKo.length * progress).toInt()
                            
                            fullKoBatch.append(tLine.substring(0, visibleKoLen))
                            fullPronBatch.append(pKo.substring(0, visiblePronLen).trim())
                        } else {
                            // 이전 문장들: 전량 노출
                            fullKoBatch.append(tLine).append("\n")
                            fullPronBatch.append(pKo).append("\n")
                        }
                    }
                    
                    OverlayModel(
                        payload = lastPayload.copy(
                            anchorTimeMs = uiSyncTime, 
                            origin1layer = combinedOrigin1L // 1레이어도 배치 전체 노출로 변경하여 싱크 맞춤
                        ),
                        tras2layer = fullKoBatch.toString().trim(),
                        pron3layer = fullPronBatch.toString().trim()
                    )
                } else {
                    // [FLICKER-GUARD] 번역 결과가 아직 없는 경우, 이전 번역문을 유지하여 깜빡임 방지
                    OverlayModel(
                        payload = lastPayload.copy(
                            anchorTimeMs = uiSyncTime,
                            origin1layer = combinedOrigin1L
                        ),
                        tras2layer = _overlayState.value.tras2layer,
                        pron3layer = _overlayState.value.pron3layer
                    )
                }

                withContext(Dispatchers.Main) {
                    // [SAME-DATA-GUARD] 불필요한 중복 사출 차단으로 CPU 및 깜빡임 방지
                    if (_overlayState.value.tras2layer != finalOverlay.tras2layer || 
                        _overlayState.value.payload.origin1layer != finalOverlay.payload.origin1layer) {
                        _overlayState.value = finalOverlay
                        Log.d("YoutubeViewModel", "[STREAMING-SYNC] Layer 2 Proportional Typing: ${finalOverlay.tras2layer}")
                    }
                }
            }
        }
    }

    fun shutdownEngine() {
        isEngineAlive = false
        batchJob?.cancel()
        _overlayState.value = OverlayModel.empty()
        lastOrigin1L = ""
    }

    fun reviveEngine() {
        isEngineAlive = true
        lastOrigin1L = ""
    }

    fun updateCurrentTime(timeMs: Long) {
        _currentTimeMs.value = timeMs
    }

    private var lastProcessedVideoId = ""

    fun onVideoChanged(videoId: String) {
        if (lastProcessedVideoId == videoId) {
            Log.d("YoutubeViewModel", "[SKIP-FLUSH] Video ID identical, ignoring redundant flush.")
            return
        }
        lastProcessedVideoId = videoId
        
        viewModelScope.launch {
            bufferMutex.withLock {
                subtitleBuffer.clear()
                fullTranslationCache.clear()
                lastOrigin1L = ""
                _overlayState.value = OverlayModel.empty()
                
                // [GLOBAL-SYNC] 진짜 영상이 바뀔 때만 전역 초기화 실행
                com.viitor.pipeline.core.GlobalSubtitleRelay.flush()
                com.viitor.pipeline.core.GlobalSubtitleRelay.requestRepair()
                onVideoDetected?.invoke(videoId)
                Log.i("YoutubeViewModel", "[VIDEO-SHIFT] Verified Video Shift. Global Flush & Repair executed.")
            }
        }
    }
}
