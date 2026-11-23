package com.viitor.pipeline.core

import android.util.Log

/**
 * [GlobalSubtitleRelay] (Data Bridge Singleton)
 * 
 * "Adhesive Bonding": Connects Browser/ViewModel data to the Foreground Overlay Service.
 */
object GlobalSubtitleRelay {
    private const val TAG = "GlobalSubtitleRelay"
    
    // Callback for SubtitleCaptureService
    var onNewData: ((SubtitleData) -> Unit)? = null
    var onRepairRequested: (() -> Unit)? = null
    var onTogglePlayback: (() -> Unit)? = null // ⏯ [PLAY-PAUSE] 외부 제어 콜백
    
    // [ID-REGISTRY] 문맥 버퍼용 ID(시간) 기반 레지스트리 (무한 생성 방지 및 자동 정리)
    private val bufferRegistry = LinkedHashMap<Long, SubtitleData>()
    private const val MAX_BUFFER_SIZE = 10 

    /** [PRON3LAYER-STATE] T 버튼의 활성화 상태 동기화 */
    var isPron3LayerEnabled: Boolean = true

    /** [TRANSLATION-MODE] 지원 번역 모드: 한국어 -> 영어, 한국어 -> 일본어 */
    enum class TranslationMode(
        val sourceLang: String,
        val targetLang: String,
        val code: String,
        val title: String,
        val shortLabel: String
    ) {
        KO_TO_EN("ko", "en", "KO_EN", "🇰🇷 한국어 ➔ 🇺🇸 영어", "KO ➔ EN"),
        KO_TO_JA("ko", "ja", "KO_JA", "🇰🇷 한국어 ➔ 🇯🇵 일본어", "KO ➔ JA");

        companion object {
            fun fromCode(code: String): TranslationMode {
                return values().find { it.code == code } ?: KO_TO_EN
            }
        }
    }

    var translationMode: TranslationMode = TranslationMode.KO_TO_EN
        set(value) {
            field = value
            sourceLanguage = value.sourceLang
            targetLanguage = value.targetLang
            onLanguageChanged?.invoke(value.targetLang)
        }

    var sourceLanguage: String = "ko"
    var targetLanguage: String = "en"
    var onLanguageChanged: ((String) -> Unit)? = null

    private val KO_REGEX = Regex("[\uAC00-\uD7AF\u1100-\u11FF]")
    private val JP_REGEX = Regex("[\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FFF]")

    fun isValidSourceText(text: String): Boolean {
        if (text.isBlank()) return false
        return when (sourceLanguage) {
            "ko" -> KO_REGEX.containsMatchIn(text) || text.any { it.isLetter() }
            "ja" -> JP_REGEX.containsMatchIn(text) || text.any { it.isLetter() }
            else -> text.any { it.isLetter() }
        }
    }

    /** [TRANSCRIPT-REGISTRY-SSOT] 하이재킹한 대본 전체 보관소 (Reactive Stream) */
    private var _transcriptRegistry = kotlinx.coroutines.flow.MutableStateFlow<List<SubtitleData>>(emptyList())
    val transcriptFlow: kotlinx.coroutines.flow.StateFlow<List<SubtitleData>> = _transcriptRegistry

    private val transcriptRegistry: List<SubtitleData> get() = _transcriptRegistry.value

    /** [V5-PATCH-LOGIC] 전체 대본을 레지스트리에 일괄 업데이트 (Global Atomic Dedupe) */
    @Synchronized
    fun updateTranscript(newList: List<SubtitleData>) {
        val deduped = mutableListOf<SubtitleData>()
        val sortedInput = newList.sortedBy { it.anchorTimeMs }
        
        for (item in sortedInput) {
            val text = item.origin1layer.trim().replace(Regex("\\s+"), " ")
            if (text.isEmpty()) continue
            
            var isRedundant = false
            var i = 0
            while (i < deduped.size) {
                val existingNorm = deduped[i].origin1layer.trim().replace(Regex("\\s+"), " ")
                if (text.contains(existingNorm) || existingNorm.contains(text)) {
                    if (text.length > existingNorm.length) {
                        deduped.removeAt(i) // 기존 조각 제거
                        continue
                    } else {
                        isRedundant = true // 더 긴 게 이미 있음
                        break
                    }
                }
                i++
            }
            if (!isRedundant) {
                deduped.add(item.copy(origin1layer = text))
            }
        }
        _transcriptRegistry.value = deduped.sortedBy { it.anchorTimeMs }
        Log.i(TAG, "[REGISTRY-SSOT] Global Atomic Clean completed: ${deduped.size} lines.")
    }

    /** [V5-PROACTIVE-SYNC] 번역이 완료된 데이터를 레지스트리에 통합 및 전파 */
    @Synchronized
    fun upsertTranscriptFull(data: SubtitleData) {
        val currentList = transcriptRegistry.toMutableList()
        val index = currentList.indexOfFirst { it.anchorTimeMs == data.anchorTimeMs }
        if (index != -1) {
            currentList[index] = data
        } else {
            currentList.add(data)
        }
        _transcriptRegistry.value = currentList.sortedBy { it.anchorTimeMs }
    }

    /** [V5-PATCH-LOGIC] 실시간 추출 원문을 레지스트리에 병합 (Global Atomic Dedupe) */
    @Synchronized
    fun upsertTranscriptWithDedupe(data: SubtitleData) {
        val currentList = transcriptRegistry.toMutableList()
        val rawInput = data.origin1layer.trim()
        if (rawInput.isEmpty()) return
        
        val newText = rawInput.replace(Regex("\\s+"), " ")

        // [V5-GLOBAL-ATOMIC-DEDUPE] 시간과 내용의 상관관계를 분석하여 정밀 병합
        var i = 0
        while (i < currentList.size) {
            val existing = currentList[i]
            val existingNorm = existing.origin1layer.trim().replace(Regex("\\s+"), " ")
            
            if (newText.contains(existingNorm) || existingNorm.contains(newText)) {
                // 내용이 포함관계이고 시간 차이가 500ms 이내일 때만 병합 처리 (손실 방지)
                if (Math.abs(data.anchorTimeMs - existing.anchorTimeMs) < 500) {
                    if (newText.length >= existing.origin1layer.length) {
                        currentList.removeAt(i)
                        continue 
                    } else {
                        return
                    }
                }
            }
            i++
        }

        currentList.add(data.copy(origin1layer = newText))
        _transcriptRegistry.value = currentList.sortedBy { it.anchorTimeMs }
    }

    /** [V5-BATCH-SOURCE] 미번역 구간 문장을 추출 */
    fun getUntranslatedSegment(currentTimeMs: Long, targetSentenceParity: Int): List<SubtitleData> {
        val safeSegmentCount = targetSentenceParity * 3
        return transcriptRegistry
            .filter { it.anchorTimeMs >= currentTimeMs && it.tras2layer.isNullOrBlank() }
            .take(safeSegmentCount)
    }

    /** [GLOBAL-FLUSH] 전체 시스템 자막 상태 초기화 (영상 변경 시 필수) */
    @Synchronized
    fun flush() {
        bufferRegistry.clear()
        _transcriptRegistry.value = emptyList()
        onNewData?.invoke(SubtitleData.empty())
        Log.i(TAG, "[FLUSH-SSOT] Global Subtitle State Reset.")
    }

    /** 시간대(ms)를 기준으로 대본에서 가장 근접한 문장을 찾아냄 */
    fun findTranscriptMatch(timeMs: Long): SubtitleData? {
        // [V5-MATCH-STABILIZER] 1000ms로 윈도우를 확장하여 싱크 유실 방지
        return transcriptRegistry.find { 
            Math.abs(it.anchorTimeMs - timeMs) < 1000 
        }
    }

    /** 텍스트 내용이 완전히 일치하는 대본을 찾아냄 */
    fun findTranscriptMatchByText(text: String): SubtitleData {
        if (transcriptRegistry.isEmpty()) return SubtitleData.empty()
        return transcriptRegistry.find { it.origin1layer.trim() == text.trim() } ?: SubtitleData.empty()
    }

    /** [LOOK-AHEAD] 특정 시간 이후의 미래 문장들을 가져옴 (번역 문맥용) */
    fun findFutureTranscripts(startTimeMs: Long, count: Int): List<String> {
        return transcriptRegistry
            .filter { it.anchorTimeMs > startTimeMs }
            .take(count)
            .map { it.origin1layer }
    }

    /** [HISTORY-LOOKUP] 특정 시간 이전의 과거 문장들을 가져옴 (번역 문맥용) */
    fun findPastOriginals(currentTimeMs: Long, count: Int): List<String> {
        return findPastSubtitleData(currentTimeMs, count).map { it.origin1layer }
    }

    /** [LIVE-CONTEXT-ENGINE] 대본 레지스트리 또는 실시간 버퍼에서 유동적으로 과거 데이터 추출 */
    private fun findPastSubtitleData(currentTimeMs: Long, count: Int): List<SubtitleData> {
        // 1순위: 대본 레지스트리 (수동 자막이 있을 때 우선권)
        val fromRegistry = transcriptRegistry.filter { it.anchorTimeMs < currentTimeMs }
        if (fromRegistry.isNotEmpty()) return fromRegistry.takeLast(count)
        
        // 2순위: 실시간 버퍼 (라이브 추출 시 사용)
        return bufferRegistry.values
            .filter { it.anchorTimeMs < currentTimeMs }
            .sortedBy { it.anchorTimeMs }
            .takeLast(count)
    }

    /** [COLOR-SYNC] 2번 레이어(tras2layer) 색상 전역 동기화 */
    var onColorChanged: ((Int) -> Unit)? = null
    var currentTras2LayerColor: Int = android.graphics.Color.WHITE
        set(value) {
            field = value
            onColorChanged?.invoke(value)
        }

    /** [V5-EMISSION-ENGINE] 통합 사출 통제소 (파일 통합 버전) */
    object Emitter {
        private val _overlayState = kotlinx.coroutines.flow.MutableStateFlow(com.viitor.ui.tab1_browser.domain.OverlayModel.empty())
        val overlayState: kotlinx.coroutines.flow.StateFlow<com.viitor.ui.tab1_browser.domain.OverlayModel> = _overlayState

        private var lastRelayedText: String = ""
        private var lastRelayedTimeMs: Long = -1L

        /** [CORE-EMISSION-GATE] 모든 상태 변화를 감지하여 최종 결과물을 사격함 */
        fun emit(timeMs: Long, registry: List<SubtitleData>) {
            val matchL1 = findTranscriptMatch(timeMs)
            
            if (matchL1 == null) {
                // [V5-DIAG] 매칭 실패 시 5초 이상 공백이면 화면 클리어 (VOD 점프 대응)
                if (Math.abs(timeMs - lastRelayedTimeMs) > 5000) {
                    _overlayState.value = com.viitor.ui.tab1_browser.domain.OverlayModel.empty()
                }
                return
            }
            
            // [V5-PRELOAD] 2~3번째 레이어는 400ms 빠르게 나타나도록 시간 오프셋 적용
            val matchL2L3 = findTranscriptMatch(timeMs + 400) ?: matchL1
            
            // 1. 조립 (1+2, 1+6 파리티 집합)
            val combinedData = assembleContextVOD(matchL1, matchL2L3, registry)

            // 2. 가드 (시각적 변화가 없으면 사출 차단)
            val currentL2Text = combinedData.tras2layer.orEmpty()
            if (currentL2Text == lastRelayedText && Math.abs(combinedData.anchorTimeMs - lastRelayedTimeMs) < 1200) {
                return
            }

            // 3. 상태 갱신 및 사출
            lastRelayedText = currentL2Text
            lastRelayedTimeMs = combinedData.anchorTimeMs

            Log.i("GlobalSubtitleRelay", "[EMISSION-GATE] Target Pulse: $timeMs, Match: ${matchL1.anchorTimeMs}, L2: ${currentL2Text.take(20)}...")

            // [V5-RECONNECT-UI] 오버레이 서비스로 콜백 전송 (UI 갱신 트리거)
            onNewData?.invoke(combinedData)

            _overlayState.value = com.viitor.ui.tab1_browser.domain.OverlayModel(
                payload = com.viitor.ui.tab1_browser.domain.SubtitlePayload(combinedData.anchorTimeMs, combinedData.origin1layer),
                tras2layer = combinedData.tras2layer ?: "",
                pron3layer = combinedData.pron3layer ?: ""
            )
        }

        private fun assembleContextVOD(dataL1: SubtitleData, dataL2L3: SubtitleData, registry: List<SubtitleData>): SubtitleData {
            val futureRegistryL1 = registry.filter { it.anchorTimeMs > dataL1.anchorTimeMs }
            val futureRegistryL2L3 = registry.filter { it.anchorTimeMs > dataL2L3.anchorTimeMs }

            fun getUniqueFuture(count: Int, startData: SubtitleData, futureReg: List<SubtitleData>, layerSelector: (SubtitleData) -> String?): List<String> {
                val uniqueList = mutableListOf<String>()
                val uniqueNorms = mutableSetOf<String>()
                val currentText = layerSelector(startData).orEmpty()
                val currentNorm = currentText.replace(Regex("[^\\p{L}\\p{N}]"), "").trim()
                if (currentNorm.isNotBlank()) uniqueNorms.add(currentNorm)

                for (entry in futureReg) {
                    val rawText = layerSelector(entry).orEmpty()
                    // [V5-SENTENCE-INTEGRITY] 불필요한 분절 없이 문장 흐름 보존
                    val segments = rawText.split("\n").filter { it.isNotBlank() }
                    for (s in segments) {
                        val norm = s.replace(Regex("[^\\p{L}\\p{N}]"), "").trim()
                        if (norm.isNotBlank() && !uniqueNorms.contains(norm)) {
                            uniqueList.add(s.trim())
                            uniqueNorms.add(norm)
                            if (uniqueList.size >= count) return uniqueList
                        }
                    }
                }
                return uniqueList
            }

            // [L1-Parity] 원문 문맥 보존
            val currentL1 = dataL1.origin1layer.split("\n").filter { it.isNotBlank() }
            val nextL1 = getUniqueFuture(2, dataL1, futureRegistryL1) { it.origin1layer }
            val finalL1 = (currentL1 + nextL1).joinToString("\n")

            // [L2-Parity] 번역 문맥 보존 (문장 단위, 0.4초 먼저 노출)
            val currentL2 = (dataL2L3.tras2layer ?: "").split("\n").filter { it.isNotBlank() }
            val nextL2 = getUniqueFuture(6, dataL2L3, futureRegistryL2L3) { it.tras2layer ?: "" }
            val finalL2 = if (currentL2.isEmpty() && nextL2.isEmpty()) {
                if (dataL1.origin1layer.isNotBlank()) "[번역 대기 중...]" else ""
            } else {
                (currentL2 + nextL2).joinToString("\n")
            }

            // [L3-Parity] 발음 문맥 보존 (0.4초 먼저 노출)
            val nextL3 = getUniqueFuture(3, dataL2L3, futureRegistryL2L3) { it.pron3layer ?: "" }
            val finalL3 = nextL3.joinToString(" ")

            return dataL1.copy(
                origin1layer = finalL1,
                tras2layer = finalL2,
                pron3layer = finalL3
            )
        }

        fun clear() {
            _overlayState.value = com.viitor.ui.tab1_browser.domain.OverlayModel.empty()
            lastRelayedText = ""
            lastRelayedTimeMs = -1L
        }
    }

    fun togglePlayback() {
        onTogglePlayback?.invoke()
    }

    fun requestRepair() {
        onRepairRequested?.invoke()
    }
}
