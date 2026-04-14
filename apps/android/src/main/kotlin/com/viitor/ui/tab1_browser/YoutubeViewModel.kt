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

    var isPronunciationEnabled: Boolean = false
    private val translationAgent = GoogleTranslationAgent()
    
    // [HIGH-PERF-CHECK] 일본어 포함 여부 고해상도 정규식 캐싱
    private val JP_REGEX = Regex("[\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FFF]")

    private var isEngineAlive = true
    private var batchJob: Job? = null
    private val subtitleBuffer = mutableListOf<SubtitlePayload>()
    private val bufferMutex = Mutex()
    private var lastExtractedText = ""
    private var lastAnchorTime = 0L 

    // [STREAM-CACHE] 타이핑 동기화를 위한 최신 번역 데이터 보관소
    private val fullTranslationCache = mutableMapOf<Long, com.viitor.pipeline.core.SubtitleData>()

    fun processExtractedSubtitle(payload: SubtitlePayload) {
        if (!isEngineAlive) return

        viewModelScope.launch(Dispatchers.IO) {
            bufferMutex.withLock {
                // [FLUSH-LOGIC] 시간 점프 감지 시 버퍼 즉시 비움 (문맥 오염 방지)
                val isTimeJump = Math.abs(payload.anchorTimeMs - lastAnchorTime) > 2000
                
                // [BLANKING-GUARD] 문맥 전환 또는 유효기간(2.5s) 경과 시 오버레이 잔상 즉시 소거
                val isNewContext = !payload.originJpText.startsWith(lastExtractedText) && 
                                   !lastExtractedText.startsWith(payload.originJpText)
                
                // [HEARTBEAT-STALE-CHECK] 마지막 캡처와 실제 영상 시간이 2.5초 이상 벌어지면 '말이 끝난 것'으로 간주
                val isStale = Math.abs(currentTimeMs.value - payload.anchorTimeMs) > 2500
                                   
                if (isTimeJump || isStale || (isNewContext && lastExtractedText.isNotBlank())) {
                    subtitleBuffer.clear()
                    _overlayState.value = OverlayModel.empty()
                    Log.i("YoutubeViewModel", "[STALE-PURGE] Context end or Time-out detected. UI Cleared.")
                    if (isStale) return@launch // 말이 끝났으면 처리 중단
                }

                if (lastExtractedText == payload.originJpText && !isTimeJump) return@launch
                
                lastExtractedText = payload.originJpText
                lastAnchorTime = payload.anchorTimeMs
                
                // [SYNC-FIRST] ID 고정 없이 원본 타임스탬프 유지
                if (subtitleBuffer.isNotEmpty() && payload.originJpText.startsWith(subtitleBuffer.last().originJpText)) {
                    subtitleBuffer[subtitleBuffer.size - 1] = payload
                } else if (subtitleBuffer.size < 5) {
                    subtitleBuffer.add(payload)
                }

                // [TRIPLE-SYNC-STREAMING] 오버레이(Relay) 스트리밍 강제 동기화
                // 배치 내 이전 문장들은 전량 노출하고, 현재 문장만 타이핑 진행도 적용
                val fullRelayJp = subtitleBuffer.joinToString("\n") { it.originJpText }
                val fullRelayKo = StringBuilder()
                val fullRelayPron = StringBuilder()

                subtitleBuffer.forEachIndexed { index, sb ->
                    val cached = fullTranslationCache[sb.anchorTimeMs]
                    if (cached != null) {
                        if (index == subtitleBuffer.size - 1) {
                            // 마지막 문장: 타이핑 진행률 계산
                            val progress = (sb.originJpText.length.toFloat() / cached.original.length.toFloat()).coerceIn(0f, 1f)
                            fullRelayKo.append(cached.translated?.let { it.substring(0, (it.length * progress).toInt()) } ?: "")
                            fullRelayPron.append(cached.pronunciation?.let { it.substring(0, (it.length * progress).toInt()) } ?: "")
                        } else {
                            // 이전 문장들: 전량
                            fullRelayKo.append(cached.translated).append("\n")
                            fullRelayPron.append(cached.pronunciation).append("\n")
                        }
                    }
                }

                val finalKo = fullRelayKo.toString().trim()
                val finalPron = fullRelayPron.toString().trim()

                com.viitor.pipeline.core.GlobalSubtitleRelay.relay(
                    com.viitor.pipeline.core.SubtitleData(
                        anchorTimeMs = (payload.anchorTimeMs - 400).coerceAtLeast(0),
                        original = fullRelayJp,
                        translated = if (finalKo.isNotEmpty()) finalKo else _overlayState.value.translateKo,
                        pronunciation = if (finalPron.isNotEmpty()) finalPron else _overlayState.value.pronunciationKo
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
                // [RHYTHM-SYNC] 2초 대기
                delay(2000)
                
                // [BATCH-JOIN] 버퍼에 쌓인 모든 문장을 하나로 합쳐서 번역 요청
                val batchToProcess = bufferMutex.withLock {
                    if (subtitleBuffer.isEmpty()) return@withLock null
                    val copy = subtitleBuffer.toList()
                    subtitleBuffer.clear()
                    copy
                } ?: continue

                // 모든 문장을 줄바꿈으로 합침 (번역 질 향상 및 유실 방지)
                val combinedJpText = batchToProcess.joinToString("\n") { it.originJpText }
                val lastPayload = batchToProcess.last()
                val masterTimeId = lastPayload.anchorTimeMs
                
                val hasJapanese = JP_REGEX.containsMatchIn(combinedJpText)
                if (!hasJapanese) continue

                // [TRANSCRIPT-FUSION] 대본 기반 즉시 사출 (Transcript Lookup)
                // 1. 대본 레지스트리에서 현재 시간과 매칭되는 데이터가 있는지 확인
                val transcriptMatch = com.viitor.pipeline.core.GlobalSubtitleRelay.findTranscriptMatch(lastPayload.anchorTimeMs)
                
                val translatedKo: String
                val rawRomaji: String
                
                if (transcriptMatch != null) {
                    // [HIGH-SPEED-HIT] 대본에서 이미 매칭된 경우, API 호출 없이 즉시 활용
                    Log.i("YoutubeViewModel", "[FUSION] Transcript Hit! Using pre-fetched data.")
                    // 원문은 대본 데이터 사용
                    val hitOriginalJp = transcriptMatch.original
                    
                    /* [LEGACY-API-CALL] 대본이 없을 경우에만 아래 로직이 활성화되도록 주석 처리 (로직 보존)
                    val (apiKo, apiRomaji) = translationAgent.fetchFullTranslation(combinedJpText)
                    translatedKo = apiKo
                    rawRomaji = apiRomaji
                    */
                    
                    // 대본에 번역이 이미 포함되어 있다면(향후 확장) 그것을 사용, 현재는 대본 기반 실시간 번역 호출로 최적화
                    val futureCtx = com.viitor.pipeline.core.GlobalSubtitleRelay.findFutureTranscripts(masterTimeId, 3)
                    val (apiKo, apiRomaji) = translationAgent.fetchFullTranslation(hitOriginalJp, futureCtx)
                    translatedKo = apiKo
                    rawRomaji = apiRomaji
                } else {
                    // [FALLBACK-API] 대본에 없는 경우(실시간 채팅 등) 기존 구글 번역 로직 가동
                    val futureCtx = com.viitor.pipeline.core.GlobalSubtitleRelay.findFutureTranscripts(masterTimeId, 3)
                    val res = translationAgent.fetchFullTranslation(combinedJpText, futureCtx)
                    translatedKo = res.first
                    rawRomaji = res.second
                }


                // [MASTER-CLOCK-SYNC] 2번 레이어 추적성 강화를 위한 시간 축 정밀 고정
                // 보정된(adjusted) 시간을 사용하여 영상 재생 시간과 오버레이가 한 몸처럼 움직이게 함
                val uiSyncTime = (masterTimeId - 400).coerceAtLeast(0)

                // [SMART-DECOMPOSE] 1:N 미스매치(원문 다수 : 번역 단수) 해결을 위한 고지능 분해 로직
                val currentJpLines = batchToProcess.map { it.originJpText }
                val rawTransLines = translatedKo.split("\n").filter { it.isNotBlank() }
                
                // [DECOMPOSE-RECOVERY] 원문 개수에 맞춰 번역문을 재생산하거나 재배치함
                val finalTransLines = mutableListOf<String>()
                
                if (rawTransLines.size >= currentJpLines.size) {
                    // 1:1 또는 초과 시: 뒤에서부터 개수에 맞춰 매칭 (Context 제거용)
                    finalTransLines.addAll(rawTransLines.takeLast(currentJpLines.size))
                } else {
                    // [1:N-CRISIS] 번역문이 원문보다 부족한 경우 (번역기가 문장을 합쳤을 때)
                    val mergedTrans = rawTransLines.joinToString(" ")
                    
                    // 전략 1: 대본 저장소에서 각 원문에 대응하는 개별 번역본이 있는지 먼저 확인 (최고 품질)
                    val recovered = currentJpLines.map { jp ->
                        com.viitor.pipeline.core.GlobalSubtitleRelay.findTranscriptMatchByText(jp)?.translated
                    }
                    
                    if (recovered.all { it != null }) {
                        finalTransLines.addAll(recovered.filterNotNull())
                    } else {
                        // 전략 2: 문장 부호 기반 분할 시도 (. ! ? ｡)
                        val sentenceSplit = mergedTrans.split(Regex("(?<=[.!?｡])\\s+")).filter { it.isNotBlank() }
                        if (sentenceSplit.size == currentJpLines.size) {
                            finalTransLines.addAll(sentenceSplit)
                        } else {
                            // 전략 3: 비중 기반 강제 분할 (Proportional Splitting)
                            // 원문의 글자 수 비율에 맞춰 번역문을 물리적으로 조각냄
                            val totalJpLen = currentJpLines.sumOf { it.length }.coerceAtLeast(1)
                            var currentPos = 0
                            currentJpLines.forEachIndexed { idx, jp ->
                                if (idx == currentJpLines.size - 1) {
                                    finalTransLines.add(mergedTrans.substring(currentPos).trim())
                                } else {
                                    val ratio = jp.length.toFloat() / totalJpLen
                                    val takeLen = (mergedTrans.length * ratio).toInt().coerceAtLeast(1)
                                    val endPos = (currentPos + takeLen).coerceAtMost(mergedTrans.length)
                                    finalTransLines.add(mergedTrans.substring(currentPos, endPos).trim())
                                    currentPos = endPos
                                }
                            }
                        }
                    }
                }

                Log.d("YoutubeViewModel", "[SMART-DECOMPOSE] Target: ${currentJpLines.size}, Result: ${finalTransLines.size}")
                
                var latestRelayResult: com.viitor.pipeline.core.SubtitleData? = null
                val romajiLines = rawRomaji.split("\n")
                
                batchToProcess.forEachIndexed { index, payload ->
                    val tLine = finalTransLines.getOrNull(index) ?: ""
                    val rLine = romajiLines.getOrNull(index) ?: ""
                    
                    val pKo = if (com.viitor.pipeline.core.GlobalSubtitleRelay.isPronunciationEnabled && rLine.isNotBlank()) {
                         com.viitor.pipeline.core.JapanesePronunciationAgent.convertRomajiToHangul(rLine)
                    } else ""

                    val data = com.viitor.pipeline.core.SubtitleData(
                        anchorTimeMs = (payload.anchorTimeMs - 400).coerceAtLeast(0),
                        original = payload.originJpText,
                        translated = tLine,
                        pronunciation = pKo
                    )
                    
                    // [CACHE-FOR-STREAMING] 실시간 스트리밍 소스로 등록 (실전 시간 ID 사용)
                    fullTranslationCache[payload.anchorTimeMs] = data.copy(anchorTimeMs = payload.anchorTimeMs)
                    
                    latestRelayResult = com.viitor.pipeline.core.GlobalSubtitleRelay.relay(data)
                }

                // 메모리 관리: 너무 오래된 캐시 정리 (최근 20개 유지)
                if (fullTranslationCache.size > 20) {
                    val oldestKey = fullTranslationCache.keys.first()
                    fullTranslationCache.remove(oldestKey)
                }

                // [ACCUMULATED-SYNC-TYPING] 배치 전체 문맥 노출 및 2레이어 스트리밍 시뮬레이션
                // 1~4번 문장은 이미 완료되었으므로 전량 노출하고, 5번(마지막) 문장만 타이핑 애니메이션 적용
                val finalOverlay = if (finalTransLines.isNotEmpty()) {
                    val fullKoBatch = StringBuilder()
                    val fullPronBatch = StringBuilder()
                    
                    batchToProcess.forEachIndexed { index, payload ->
                        val tLine = finalTransLines.getOrNull(index) ?: ""
                        val pKo = if (com.viitor.pipeline.core.GlobalSubtitleRelay.isPronunciationEnabled) {
                             val rLine = romajiLines.getOrNull(index) ?: ""
                             if (rLine.isNotBlank()) com.viitor.pipeline.core.JapanesePronunciationAgent.convertRomajiToHangul(rLine) else ""
                        } else ""
                        
                        if (index == batchToProcess.size - 1) {
                            // 마지막 문장: 타이핑 진행률 계산 (0.0 ~ 1.0)
                            // 1레이어(일본어)가 늘어나는 비율에 맞춰 2레이어(한국어)도 한 글자씩 노출됨
                            val progress = (payload.originJpText.length.toFloat() / currentJpLines.last().length.toFloat()).coerceIn(0f, 1f)
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
                            originJpText = combinedJpText // 1레이어도 배치 전체 노출로 변경하여 싱크 맞춤
                        ),
                        translateKo = fullKoBatch.toString().trim(),
                        pronunciationKo = fullPronBatch.toString().trim()
                    )
                } else {
                    // 번역 결과가 아직 없는 경우 (API 대기 중)
                    OverlayModel(
                        payload = lastPayload.copy(
                            anchorTimeMs = uiSyncTime,
                            originJpText = combinedJpText
                        ),
                        translateKo = "",
                        pronunciationKo = ""
                    )
                }

                withContext(Dispatchers.Main) {
                    // [SAME-DATA-GUARD] 불필요한 중복 사출 차단으로 CPU 및 깜빡임 방지
                    if (_overlayState.value.translateKo != finalOverlay.translateKo || 
                        _overlayState.value.payload.originJpText != finalOverlay.payload.originJpText) {
                        _overlayState.value = finalOverlay
                        Log.d("YoutubeViewModel", "[STREAMING-SYNC] Layer 2 Proportional Typing: ${finalOverlay.translateKo}")
                    }
                }
            }
        }
    }

    fun shutdownEngine() {
        isEngineAlive = false
        batchJob?.cancel()
        _overlayState.value = OverlayModel.empty()
        lastExtractedText = ""
    }

    fun reviveEngine() {
        isEngineAlive = true
        lastExtractedText = ""
    }

    fun updateCurrentTime(timeMs: Long) {
        _currentTimeMs.value = timeMs
    }
}
