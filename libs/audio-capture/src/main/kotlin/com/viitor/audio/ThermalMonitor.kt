package com.viitor.audio

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ★ ThermalMonitor: 디바이스 발열 감지 및 품질 자동 조절
 *
 * Android API 29+의 PowerManager.OnThermalStatusChangedListener 사용.
 * - THROTTLING 이상 감지 시 STT 품질을 자동으로 서버(WebSocket) 모드로 전환
 * - CPU ThermalStatus → 청크 크기 자동 조절로 발열 억제
 * - Viitor 대비 핵심 차별화: 장시간 사용 시 안정성 유지
 */
class ThermalMonitor(private val context: Context) {
    
    companion object {
        private const val TAG = "ThermalMonitor"
    }

    enum class ThermalLevel { COOL, WARM, HOT, CRITICAL }

    private val _thermalLevel = MutableStateFlow(ThermalLevel.COOL)
    val thermalLevel: StateFlow<ThermalLevel> = _thermalLevel
    
    // 발열 수준에 따른 청크 크기 배율 (COOL=1.0, HOT=0.5)
    val chunkSizeMultiplier: Float 
        get() = when (_thermalLevel.value) {
            ThermalLevel.COOL     -> 1.0f
            ThermalLevel.WARM     -> 0.8f
            ThermalLevel.HOT      -> 0.5f
            ThermalLevel.CRITICAL -> 0.3f
        }
    
    // 발열 시 로컬 STT 비활성화, 서버로 위임 여부
    val shouldUseServerSTT: Boolean
        get() = _thermalLevel.value == ThermalLevel.CRITICAL

    private val powerManager by lazy { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        _thermalLevel.value = when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT      -> ThermalLevel.COOL
            PowerManager.THERMAL_STATUS_MODERATE   -> ThermalLevel.WARM
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL   -> ThermalLevel.HOT
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN   -> ThermalLevel.CRITICAL
            else                                   -> ThermalLevel.WARM
        }
        Log.w(TAG, "Thermal status changed: ${_thermalLevel.value} (raw=$status, chunkMult=$chunkSizeMultiplier, useServer=$shouldUseServerSTT)")
    }
    
    fun getThrottlingLevel(): Int = when (_thermalLevel.value) {
        ThermalLevel.COOL -> 0
        ThermalLevel.WARM -> 1
        ThermalLevel.HOT -> 2
        ThermalLevel.CRITICAL -> 3
    }

    fun start() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            powerManager.addThermalStatusListener(thermalListener)
            Log.d(TAG, "ThermalMonitor started")
        } else {
            Log.d(TAG, "ThermalMonitor: API < 29, thermal monitoring disabled")
        }
    }
    
    fun stop() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            powerManager.removeThermalStatusListener(thermalListener)
        }
    }
}
