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

    /** [TRANSCRIPT-REGISTRY] 하이재킹한 대본 전체 보관소 */
    private var transcriptRegistry: List<SubtitleData> = emptyList()

    fun updateTranscript(list: List<SubtitleData>) {
        this.transcriptRegistry = list
        Log.i(TAG, "[REGISTRY] Transcript loaded: ${list.size} lines.")
    }

    /** [GLOBAL-FLUSH] 전체 시스템 자막 상태 초기화 */
    fun flush() {
        bufferRegistry.clear()
        transcriptRegistry = emptyList()
        onNewData?.invoke(SubtitleData.empty()) // [UI-FLUSH] 즉각적으로 화면 소독 신호 발송
        Log.i(TAG, "[FLUSH] Global Subtitle State Cleared.")
    }

    /** 시간대(ms)를 기준으로 대본에서 가장 근접한 문장을 찾아냄 */
    fun findTranscriptMatch(timeMs: Long): SubtitleData? {
        if (transcriptRegistry.isEmpty()) return null
        return transcriptRegistry.find { Math.abs(it.anchorTimeMs - timeMs) < 1000 }
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

    @Synchronized
    fun relay(data: SubtitleData): SubtitleData {
        // [FLUSH-SIGNAL] anchorTimeMs가 0이면 데이터가 비어있어도 화면 소독을 위해 통과시킴
        if (data.origin1layer.isBlank() && data.anchorTimeMs != 0L) return data

        val matchedInRegistry = findTranscriptMatch(data.anchorTimeMs) ?: SubtitleData.empty()
        
        // 버퍼 정리 로직 (중복 및 유사 문장 제거)
        val iterator = bufferRegistry.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (Math.abs(entry.key - data.anchorTimeMs) < 1500 && transcriptRegistry.any { it.anchorTimeMs == data.anchorTimeMs }) {
                iterator.remove()
                continue
            }
            if (data.origin1layer.startsWith(entry.value.origin1layer) || entry.value.origin1layer.startsWith(data.origin1layer)) {
                iterator.remove()
            }
        }

        bufferRegistry[data.anchorTimeMs] = data
        if (bufferRegistry.size > MAX_BUFFER_SIZE) {
            bufferRegistry.remove(bufferRegistry.keys.first())
        }

        // 1레이어 (원문): 과거 2줄 + 현재 1줄 = 3줄
        val pastOriginalSamples = findPastSubtitleData(data.anchorTimeMs, 2)
        val currentL1Text = data.origin1layer.takeIf { it.isNotBlank() } ?: matchedInRegistry.origin1layer
        val finalOriginal = pastOriginalSamples.map { it.origin1layer }.plus(currentL1Text).filter { it.isNotBlank() }.takeLast(3).joinToString("\n")
        
        // 2레이어 (번역): [현재 5줄(과거 4 + 현재 1)] + [미래 1줄] = 총 6줄
        val pastTranslatedSamples = findPastSubtitleData(data.anchorTimeMs, 4)
        val futureRegistry1 = transcriptRegistry.filter { it.anchorTimeMs > data.anchorTimeMs }.take(1)
        
        val currentTranslated = data.tras2layer.takeIf { !it.isNullOrBlank() } 
            ?: matchedInRegistry.tras2layer 
            ?: (if(currentL1Text.isNotBlank()) "번역 불러오는 중..." else "")
            
        val finalTranslated = pastTranslatedSamples.map { it.tras2layer ?: "..." }
            .plus(currentTranslated)
            .plus(futureRegistry1.map { it.tras2layer ?: "[미래 문장]" })
            .takeLast(6)
            .joinToString("\n")
        
        // 3레이어 (발음): 과거 2줄 + 현재 1줄 = 3줄
        val pastPronunciationSamples = findPastSubtitleData(data.anchorTimeMs, 2)
        val currentPronunciation = data.pron3layer.takeIf { !it.isNullOrBlank() } 
            ?: matchedInRegistry.pron3layer ?: ""

        val finalPronunciation = pastPronunciationSamples.map { it.pron3layer ?: "" }
            .plus(currentPronunciation)
            .filter { it.isNotBlank() }
            .takeLast(3)
            .joinToString("\n")
        
        val combinedData = SubtitleData(
            anchorTimeMs = data.anchorTimeMs,
            origin1layer = finalOriginal,
            tras2layer = finalTranslated,
            pron3layer = finalPronunciation
        )

        onNewData?.invoke(combinedData)
        return combinedData
    }

    fun togglePlayback() {
        onTogglePlayback?.invoke()
    }

    fun requestRepair() {
        onRepairRequested?.invoke()
    }
}
