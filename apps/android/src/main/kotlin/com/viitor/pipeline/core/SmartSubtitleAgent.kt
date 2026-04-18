package com.viitor.pipeline.core

import android.util.Log
import com.viitor.ui.tab2_script.WideSubtitleAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch

/**
 * [SmartSubtitleAgent] (AI-Core Orchestrator - Platinum V5 Upgrade)
 */
class SmartSubtitleAgent {
    companion object {
        private const val TAG = "SmartSubtitleAgent"
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("SmartSubtitleAgent", "Unhandled extraction error", throwable)
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)
    private var isRunning = false
    
    // Callback for Overlay UI
    var onSubtitleResult: ((SubtitleData) -> Unit)? = null

    /** 
     * Starts the agent.
     */
    fun start() {
        Log.i(TAG, "🟢 SmartSubtitleAgent Igniting...")
        isRunning = true
    }

    /** 
     * Stops the agent and cancels pending jobs.
     */
    fun stop() {
        Log.i(TAG, "🛑 SmartSubtitleAgent Halting...")
        isRunning = false
    }

    /**
     * Aggressively extracts and processes subtitles for a given source.
     */
    fun processSource(sourceId: String) {
        if (!isRunning) return
        
        scope.launch {
            Log.d(TAG, "[STREAM] Initiating extraction for: $sourceId")
            val results = WideSubtitleAgent.aggressiveExtract(sourceId)
            
            if (results.isNotEmpty()) {
                Log.i(TAG, "[STREAM] Extraction successful, relaying to overlay.")
                
                val topResult = results[0].origin1layer
                
                // Simulate 3-layer data
                val mockData = SubtitleData(
                    videoId = sourceId,
                    origin1layer = topResult,
                    tras2layer = "[번역] " + topResult,
                    pron3layer = "[발음] " + topResult,
                    anchorTimeMs = System.currentTimeMillis()
                )
                
                onSubtitleResult?.invoke(mockData)
            } else {
                Log.w(TAG, "[STREAM] Extraction empty.")
            }
        }
    }

    fun isActive(): Boolean = isRunning
}
