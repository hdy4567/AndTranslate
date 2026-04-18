package com.viitor.ui.tab1_browser.domain

import android.util.Log
import com.viitor.pipeline.core.GlobalSubtitleRelay
import com.viitor.pipeline.core.SubtitleData
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 실시간 번역용 Vod 랑 분리 
*/

/**
 * [YoutubeLiveSubtitleAgent] - 실시간 라이브 자막 추출 및 번역 엔진 (Source B)
 * - 역할: 브라우저로부터 들어오는 실시간 파편(SubtitlePayload)을 모아 배치 번역하고 릴레이함.
 * - 독립성: 유튜브 일반 영상의 정적 대본(Source A) 로직과 충돌하지 않도록 별도 모듈로 분리.
 */
class YoutubeLiveSubtitleAgent(
    private val scope: CoroutineScope,
    private val translationAgent: GoogleTranslationAgent
) {
    private val subtitleBuffer = mutableListOf<SubtitlePayload>()
    private val bufferMutex = Mutex()
    private var batchJob: Job? = null
    private var isAlive = true

    private var lastBatchText = ""
    private val JP_REGEX = Regex("[\u3040-\u309F\u30A0-\u30FF\u4E00-\u9FFF]")

    /** [EXTRACTOR] 실시간으로 들어오는 자막 파편을 버퍼에 적재 */
    fun feed(payload: SubtitlePayload) {
        if (!isAlive) return
        
        scope.launch(Dispatchers.IO) {
            bufferMutex.withLock {
                if (subtitleBuffer.size >= 5) subtitleBuffer.removeAt(0)
                subtitleBuffer.add(payload)
            }
            
            // 배치 루프가 작동 중이지 않다면 시작
            if (batchJob == null || !batchJob!!.isActive) {
                startBatchLoop()
            }
        }
    }

    private fun startBatchLoop() {
        batchJob = scope.launch(Dispatchers.IO) {
            while (isAlive) {
                delay(2000) // 2초 주기 배치 번역
                
                val batchToProcess = bufferMutex.withLock {
                    if (subtitleBuffer.isEmpty()) return@withLock null
                    subtitleBuffer.toList().also { subtitleBuffer.clear() }
                } ?: continue

                val combinedJpText = batchToProcess.joinToString("\n") { it.origin1layer }.trim()
                
                // 중복 번역 및 필터링 가드
                if (combinedJpText.isBlank() || !JP_REGEX.containsMatchIn(combinedJpText)) continue
                if (lastBatchText.contains(combinedJpText) || combinedJpText.contains(lastBatchText)) continue
                lastBatchText = combinedJpText

                // 번역 실행 (Source B: 실시간 문맥 기반)
                try {
                    val pastCtx = GlobalSubtitleRelay.findPastOriginals(batchToProcess.last().anchorTimeMs, 4)
                    val (translatedKo, rawRomaji) = translationAgent.fetchFullTranslation(combinedJpText, pastCtx)
                    
                    val koLines = translatedKo.split("\n").filter { it.isNotBlank() }
                    
                    // 조립 및 릴레이
                    batchToProcess.forEachIndexed { index, payload ->
                        val tLine = koLines.getOrNull(index) ?: ""
                        
                        val data = SubtitleData(
                            anchorTimeMs = payload.anchorTimeMs,
                            origin1layer = payload.origin1layer,
                            tras2layer = tLine
                        )
                        
                        // 대본 레지스트리에 업데이트 (하이재킹 결과 누적)
                        GlobalSubtitleRelay.upsertTranscriptFull(data)
                    }
                    Log.d("YoutubeLiveAgent", "[SOURCE-B] Batch Processed: $combinedJpText")
                } catch (e: Exception) {
                    Log.e("YoutubeLiveAgent", "Source B processing error", e)
                }
            }
        }
    }

    fun shutdown() {
        isAlive = false
        batchJob?.cancel()
    }
}
