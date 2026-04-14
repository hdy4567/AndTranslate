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
    private const val MAX_BUFFER_SIZE = 10 // 최신 10개 세그먼트만 유지 (메모리 가드)

    /** [PRONUNCIATION-STATE] T 버튼의 활성화 상태 동기화 (Service -> ViewModel) - Default ACTIVE */
    var isPronunciationEnabled: Boolean = true

    /** [TRANSCRIPT-REGISTRY] 하이재킹한 7000자 대본 전체 보관소 (API 호출 없이 즉시 사출용) */
    private var transcriptRegistry: List<SubtitleData> = emptyList()

    fun updateTranscript(list: List<SubtitleData>) {
        this.transcriptRegistry = list
        Log.i(TAG, "[REGISTRY] Transcript loaded: ${list.size} lines. Ready for Instant-Lookup.")
    }

    /** [GLOBAL-FLUSH] 전체 시스템 자막 상태 초기화 (영상 변경/오동작 복구용) */
    fun flush() {
        bufferRegistry.clear()
        transcriptRegistry = emptyList()
        Log.i(TAG, "[FLUSH] Global Subtitle State Cleared Successfully.")
    }

    /** 시간대(ms)를 기준으로 대본에서 가장 근접한(±1s) 문장을 찾아냄 */
    fun findTranscriptMatch(timeMs: Long): SubtitleData? {
        if (transcriptRegistry.isEmpty()) return null
        return transcriptRegistry.find { Math.abs(it.anchorTimeMs - timeMs) < 1000 }
    }

    /** 텍스트 내용이 완전히 일치하는 대본을 찾아냄 (1:1 보정용) */
    fun findTranscriptMatchByText(text: String): SubtitleData? {
        if (transcriptRegistry.isEmpty()) return null
        return transcriptRegistry.find { it.original.trim() == text.trim() }
    }

    /** [LOOK-AHEAD] 특정 시간 이후의 미래 문장들을 가져옴 (번역 문맥 고도화용) */
    fun findFutureTranscripts(startTimeMs: Long, count: Int): List<String> {
        if (transcriptRegistry.isEmpty()) return emptyList()
        return transcriptRegistry
            .filter { it.anchorTimeMs > startTimeMs }
            .take(count)
            .map { it.original }
    }

    /** [COLOR-SYNC] 2번 레이어(번역문) 색상 전역 동기화 (Service -> ScriptAdapter) - Default WHITE */
    var onColorChanged: ((Int) -> Unit)? = null
    var currentTranslationColor: Int = android.graphics.Color.WHITE
        set(value) {
            field = value
            onColorChanged?.invoke(value)
        }

    @Synchronized
    fun relay(data: SubtitleData): SubtitleData {
        // [BLOCK-INVALID] 유효하지 않은 데이터 차단
        if (data.original.isBlank()) return data

        // [MASTER-PURGE] 대본 매칭 데이터가 들어오면, 주변 1.5초 내의 불안정한 캡처 데이터는 모두 추방 (품질 강화)
        val isTranscriptMaster = transcriptRegistry.any { it.anchorTimeMs == data.anchorTimeMs }
        
        val iterator = bufferRegistry.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            
            // 1. 대본 마스터 기준 주변 정리 (Ghosting 방제)
            if (isTranscriptMaster && Math.abs(entry.key - data.anchorTimeMs) < 1500) {
                iterator.remove()
                continue
            }

            // 2. [FUZZY-KEY-MIGRATION] 텍스트가 서로 포함관계라면(점진적 완성), 구형 데이터를 지우고 최신 시간 ID로 '자릿수'를 옮김
            if (data.original.startsWith(entry.value.original) || entry.value.original.startsWith(data.original)) {
                iterator.remove()
            }
        }

        // 항상 최신 영상을 추격하는 고유 시간 ID로 신규 등록 (100% 실시간 싱크 보장)
        bufferRegistry[data.anchorTimeMs] = data

        // [ROLLING-WINDOW] 최신 10개만 유지 (메모리 보호)
        if (bufferRegistry.size > MAX_BUFFER_SIZE) {
            val firstKey = bufferRegistry.keys.first()
            bufferRegistry.remove(firstKey)
        }

        // [SSOT] 최신 3문장 원문/번역/발음 통합 사출 (1레이어도 2레이어와 동일하게 3줄 한정)
        val refinedResult = bufferRegistry.values.toList().takeLast(3)
        
        val combinedData = SubtitleData(
            anchorTimeMs = data.anchorTimeMs,
            original = refinedResult.joinToString("\n") { it.original }.take(100), // 장황화 방지 가드
            translated = refinedResult.joinToString("\n") { it.translated ?: "" },
            pronunciation = refinedResult.joinToString("\n") { it.pronunciation ?: "" }
        )

        onNewData?.invoke(combinedData)
        return combinedData
    }

    fun togglePlayback() {
        Log.i(TAG, "[REMOTE] Playback toggle requested")
        onTogglePlayback?.invoke()
    }

    fun requestRepair() {
        Log.i(TAG, "[REPAIR] Request triggered")
        onRepairRequested?.invoke()
    }
}
