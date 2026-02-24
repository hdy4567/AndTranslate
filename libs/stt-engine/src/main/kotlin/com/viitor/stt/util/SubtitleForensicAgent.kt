package com.viitor.stt.util

import android.util.Log
import com.viitor.stt.domain.TranscriptionMode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

object SubtitleForensicAgent {
    private const val TAG = "PipelineForensic"
    
    val audioFramesAtService = AtomicInteger(0)
    val audioFramesPushed = AtomicInteger(0)
    private const val ZERO_THRESHOLD = 15
    val sttSuccessCount = AtomicInteger(0)
    val sttZeroResultCount = AtomicInteger(0)
    private val consecutiveZeros = AtomicInteger(0)
    
    var isPunished: Boolean = false
    var punishmentType: String = "NONE"
    
    var lastRms = 0.0f
    val lastTokens = mutableListOf<Int>()
    var lastError: String? = null
    var currentState: String = "IDLE"
    var engineReady: Boolean = false
    var latestTopTokens: String = "" 
    
    fun recordError(msg: String) {
        lastError = msg
        Log.e(TAG, "[Forensic-Error] $msg")
    }
    
    fun recordAudioDrive(rms: Float) {
        audioFramesPushed.incrementAndGet()
        lastRms = rms
        if (audioFramesPushed.get() % 50 == 0) {
            Log.v(TAG, "[Forensic-1] Audio Flowing: RMS=" + rms)
        }
    }
    
    fun recordInference(mode: TranscriptionMode, tokens: IntArray, text: String) {
        if (tokens.isEmpty() || text.isBlank()) {
            sttZeroResultCount.incrementAndGet()
            val cZeros = consecutiveZeros.incrementAndGet()
            
            if (cZeros % 5 == 0) {
                Log.w(TAG, "[Forensic-Zero] Consecutive zeros: $cZeros")
            }
            
            if (lastRms > 500.0f && cZeros >= ZERO_THRESHOLD) {
                triggerPunishment("ENGINE-STALL")
            }
            return
        }
        
        sttSuccessCount.incrementAndGet()
        consecutiveZeros.set(0)
        if (isPunished) {
            Log.i(TAG, "[Liar-Detector] Engine recovered. Lifting punishment.")
            isPunished = false
            punishmentType = "NONE"
        }
        
        synchronized(lastTokens) {
            lastTokens.clear()
            for (i in 0 until minOf(tokens.size, 10)) {
                lastTokens.add(tokens[i])
            }
        }
        
        Log.d(TAG, "[Forensic-2] Engine Active: mode=$mode, tokens=${tokens.size}")
    }
    
    private fun triggerPunishment(type: String) {
        if (isPunished) return 
        isPunished = true
        punishmentType = type
        Log.e(TAG, "[LIAR-DETECTOR] Consecutive Failures: ${consecutiveZeros.get()}. SUSPENDING TRUST.")
        currentState = "PUNISHED"
    }
    
    fun getSummary(): String {
        val stateIcon = if (isPunished) "P" else if (currentState == "RUNNING") "R" else "I"
        return "STT:${sttSuccessCount.get()}(Z:${sttZeroResultCount.get()}) state:$stateIcon punishment:$punishmentType"
    }
}
