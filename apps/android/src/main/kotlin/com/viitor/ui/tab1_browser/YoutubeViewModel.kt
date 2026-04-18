package com.viitor.ui.tab1_browser

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.viitor.ui.tab1_browser.domain.GoogleTranslationAgent
import com.viitor.ui.tab1_browser.domain.OverlayModel
import com.viitor.ui.tab1_browser.domain.SubtitlePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class YoutubeViewModel : ViewModel() {

    private val _currentTimeMs = MutableStateFlow(0L)
    val currentTimeMs: StateFlow<Long> = _currentTimeMs

    // [V5-INTEGRATION] 사출 엔진의 상태를 UI에 그대로 연결
    val overlayState: StateFlow<OverlayModel> = com.viitor.pipeline.core.GlobalSubtitleRelay.Emitter.overlayState

    var isPron3LayerEnabled: Boolean = false
    private val translationAgent = GoogleTranslationAgent()
    
    private val JP_REGEX = Regex("[\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FFF]")

    private var isEngineAlive = true
    private var batchJob: Job? = null
    private val subtitleBuffer = mutableListOf<SubtitlePayload>()
    private val bufferMutex = Mutex()
    private var lastOrigin1L = ""
    private var lastAnchorTime = 0L 

    private val fullTranslationCache = mutableMapOf<Long, com.viitor.pipeline.core.SubtitleData>()
    var onVideoDetected: ((String) -> Unit)? = null
    var onLiveFallbackListener: (() -> Unit)? = null

    init {
        // [V5-SOLO-GATE] 시간과 레지스트리 변화를 감지하여 엔진에 사출 위임
        viewModelScope.launch {
            combine(
                _currentTimeMs,
                com.viitor.pipeline.core.GlobalSubtitleRelay.transcriptFlow
            ) { time, registry ->
                com.viitor.pipeline.core.GlobalSubtitleRelay.Emitter.emit(time, registry)
            }.collect { }
        }
    }

    /** [SUPPLIER-CAPTURE] 실시간 캡처는 오직 레지스트리 기록에만 집중 (사출 금지) */
    fun processExtractedSubtitle(payload: SubtitlePayload) {
        if (!isEngineAlive) return
        val cleanedRaw = payload.origin1layer.replace("||", " ").trim()
        
        if (cleanedRaw.contains("(자동 생성됨)") || cleanedRaw.contains("(auto-generated)") || 
            cleanedRaw.contains("하려면을 클릭하세요") || cleanedRaw.contains("하려면을 클릭")) return

        val payloadCleaned = payload.copy(origin1layer = cleanedRaw)

        viewModelScope.launch(Dispatchers.IO) {
            bufferMutex.withLock {
                val isTimeJump = Math.abs(payloadCleaned.anchorTimeMs - lastAnchorTime) > 2000
                if (isTimeJump) {
                    subtitleBuffer.clear()
                    com.viitor.pipeline.core.GlobalSubtitleRelay.Emitter.clear()
                    Log.d("YoutubeViewModel", "[V5-SUPPLIER] Time jump detected, buffer flushed.")
                }
                
                if (subtitleBuffer.isNotEmpty() && cleanedRaw.startsWith(subtitleBuffer.last().origin1layer)) {
                    subtitleBuffer[subtitleBuffer.size - 1] = payloadCleaned
                } else if (subtitleBuffer.size < 8) {
                    subtitleBuffer.add(payloadCleaned)
                }
                
                lastAnchorTime = payloadCleaned.anchorTimeMs
                lastOrigin1L = cleanedRaw

                com.viitor.pipeline.core.GlobalSubtitleRelay.upsertTranscriptWithDedupe(
                    com.viitor.pipeline.core.SubtitleData(
                        anchorTimeMs = payloadCleaned.anchorTimeMs,
                        origin1layer = cleanedRaw
                    )
                )
            }
            if (batchJob == null || !batchJob!!.isActive) startBatchLoop()
        }
    }

    // [V5-PARAMS] 사출 엔진의 파리티 요구량과 API 호출 주기를 파라미터화하여 통일
    private fun startBatchLoop(targetSentenceParity: Int = 7, targetApiCycleMs: Long = 2500L) {
        batchJob = viewModelScope.launch(Dispatchers.IO) {
            while (isEngineAlive) {
                // [V5-CYCLE-SYNC] 사이클 시작 시간 기록
                val cycleStartTime = System.currentTimeMillis()
                
                var combinedOrigin1L = ""
                val batchToProcess = mutableListOf<SubtitlePayload>()

                bufferMutex.withLock {
                    if (subtitleBuffer.isNotEmpty()) {
                        batchToProcess.addAll(subtitleBuffer)
                        subtitleBuffer.clear()
                        combinedOrigin1L = batchToProcess.joinToString("\n") { it.origin1layer }
                    }
                }

                if (batchToProcess.isEmpty()) {
                    val currentTime = currentTimeMs.value
                    // [V5-PREFETCH-BOOST] 파라미터로 받은 문장 숫자를 반영하여 충분한 세그먼트 스캔
                    val untranslated = com.viitor.pipeline.core.GlobalSubtitleRelay.getUntranslatedSegment(currentTime, targetSentenceParity)
                    if (untranslated.isNotEmpty()) {
                        combinedOrigin1L = untranslated.joinToString("\n") { it.origin1layer }
                        batchToProcess.addAll(untranslated.map { SubtitlePayload(it.anchorTimeMs, it.origin1layer) })
                    }
                }

                if (batchToProcess.isNotEmpty() && combinedOrigin1L.isNotBlank() && JP_REGEX.containsMatchIn(combinedOrigin1L)) {
                    try {
                        val res = translationAgent.fetchFullTranslation(combinedOrigin1L, emptyList())
                        val trasRaw = res.first
                        val pronRaw = res.second

                        val currentJpLines = batchToProcess.map { it.origin1layer }
                        val rawTransLines = trasRaw.split("\n").filter { it.isNotBlank() }
                        
                        val finalTransLines = mutableListOf<String>()
                        if (rawTransLines.size >= currentJpLines.size) {
                            finalTransLines.addAll(rawTransLines.take(currentJpLines.size))
                        } else if (rawTransLines.isNotEmpty()) {
                            // [V5-SENTENCE-GUARD] 번역본이 부족할 경우 억지로 쪼개지 않고 문장 통째로 유지
                            repeat(currentJpLines.size - 1) { finalTransLines.add("") }
                            finalTransLines.add(rawTransLines.joinToString(" "))
                        } else {
                            repeat(currentJpLines.size) { finalTransLines.add("") }
                        }

                        batchToProcess.forEachIndexed { index, payload ->
                            val tLine = finalTransLines.getOrNull(index) ?: ""
                            val rLine = pronRaw.split("\n").getOrNull(index) ?: ""
                            val pKo = if (com.viitor.pipeline.core.GlobalSubtitleRelay.isPron3LayerEnabled && rLine.isNotBlank()) {
                                 com.viitor.pipeline.core.JapanesePronunciationAgent.convertRomajiToHangul(rLine)
                            } else ""

                            com.viitor.pipeline.core.GlobalSubtitleRelay.upsertTranscriptFull(
                                com.viitor.pipeline.core.SubtitleData(
                                    anchorTimeMs = payload.anchorTimeMs,
                                    origin1layer = payload.origin1layer,
                                    tras2layer = tLine,
                                    pron3layer = pKo
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("YoutubeViewModel", "Batch error", e)
                    }
                }

                // [V5-CYCLE-UNIFY] API 실제 소요 시간을 뺀 나머지 시간만큼만 최소한으로 Delay하여 2.5초 통일
                val elapsed = System.currentTimeMillis() - cycleStartTime
                var delayTime = targetApiCycleMs - elapsed
                if (batchToProcess.isEmpty()) delayTime = targetApiCycleMs // 처리할 거 없으면 전체 사이클 수면
                
                delay(delayTime.coerceAtLeast(100L))
            }
        }
    }

    private var lastRelayedText: String = ""
    private var lastRelayedTimeMs: Long = -1L

    fun updateCurrentTime(timeMs: Long) {
        _currentTimeMs.value = timeMs
    }

    fun shutdownEngine() {
        isEngineAlive = false
        batchJob?.cancel()
        com.viitor.pipeline.core.GlobalSubtitleRelay.Emitter.clear()
        lastOrigin1L = ""
    }

    fun reviveEngine() {
        isEngineAlive = true
        lastOrigin1L = ""
    }

    fun updateManualTranscript(list: List<com.viitor.pipeline.core.SubtitleData>) {
        val totalChars = list.sumOf { it.origin1layer.length }
        if (list.size > 50) {
            Log.i("YoutubeViewModel", "🔥 [REGISTRY-SSOT] Bulk Script Mode Active: ${list.size} lines, $totalChars chars loaded.")
        } else {
            Log.i("YoutubeViewModel", "[REGISTRY-SSOT] Partial Transcript Received: ${list.size} lines.")
        }
        com.viitor.pipeline.core.GlobalSubtitleRelay.updateTranscript(list)
        
        // [FORCE-IGNITION] 대본이 들어오자마자 미래 문장 번역 엔진 가동
        if (batchJob == null || !batchJob!!.isActive) startBatchLoop()
    }

    /** [V5-PROTOCOL-SINK] 네트워크에서 획득한 모든 생패킷(Raw Packet)의 종착지 */
    fun onFullTranscriptCaptured(data: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = if (data.contains("events")) {
                com.viitor.ui.tab2_script.SubtitleParserUtil.parseJson3Content(data)
            } else if (data.contains("actions") || data.contains("continuation")) {
                Log.w("YoutubeViewModel", "🎯 [LIVE-RADAR-HIT] Capture Success! Analyzing...")
                emptyList()
            } else {
                emptyList()
            }

            if (list.isNotEmpty()) {
                updateManualTranscript(list)
            }
        }
    }

    /** [LIVE-QUEUE-INGRESS] 실시간 STT 큐에서 하이재킹한 텍스트의 입구 */
    fun onSubtitleCaptured(text: String) {
        if (text.isBlank()) return
        Log.i("YoutubeViewModel", "🌊 [LIVE-STREAM] Ingress: $text")
        
        // 라이브의 경우 anchorTimeMs를 현재 시간 기반으로 가상 생성하여 파이프라인에 태움
        val virtualAnchor = currentTimeMs.value
        processExtractedSubtitle(SubtitlePayload(virtualAnchor, text))
    }

    private var lastProcessedVideoId: String? = null

    fun onVideoChanged(videoId: String) {
        if (lastProcessedVideoId == videoId) return
        lastProcessedVideoId = videoId
        lastRelayedTimeMs = -1L
        
        viewModelScope.launch {
            bufferMutex.withLock {
                subtitleBuffer.clear()
                lastOrigin1L = ""
                com.viitor.pipeline.core.GlobalSubtitleRelay.Emitter.clear()
                com.viitor.pipeline.core.GlobalSubtitleRelay.flush()
                com.viitor.pipeline.core.GlobalSubtitleRelay.requestRepair()
                onVideoDetected?.invoke(videoId)
                Log.i("YoutubeViewModel", "[V5-UNIFIED-SYNC] Video Changed: $videoId, Global Stream Reset.")
            }
        }
    }

    fun onRequestLiveFallback() {
        onLiveFallbackListener?.invoke()
    }
}
