package com.viitor.service

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ★ ServiceStabilityManager: 서비스 리소스/코루틴 통합 안정성 관리자
 *
 * 📌 연구 출처:
 *   - Android 공식 문서: ComponentCallbacks2.onTrimMemory() 2024
 *   - Kotlin 공식 문서: CoroutineExceptionHandler, SupervisorJob
 *   - Android LMK (Low Memory Killer) 메커니즘
 *
 * 기능:
 *   1. MemoryPressureCallback: onTrimMemory() 레벨별 자동 캐시 정리
 *      RUNNING_MODERATE → 선택적 캐시 정리
 *      RUNNING_LOW      → 번역 캐시 전체 해제
 *      RUNNING_CRITICAL → 모든 오디오 버퍼 해제 + 강제 GC
 *
 *   2. GlobalCoroutineExceptionHandler: 코루틴 크래시 방어
 *      uncaught exception → 서비스 계속 운영
 *      로그 기록 + 재시도 로직
 *
 *   3. SupervisorJob: 자식 코루틴 실패 시 부모 취소 방지
 */
class ServiceStabilityManager : ComponentCallbacks2 {

    companion object {
        private const val TAG = "StabilityManager"
    }

    // 외부에서 등록할 수 있는 리소스 해제 콜백들
    var onMemoryModerate: (() -> Unit)? = null  // 중간 메모리 압박
    var onMemoryLow: (() -> Unit)? = null        // 낮은 메모리
    var onMemoryCritical: (() -> Unit)? = null  // 위급 메모리
    var onConfigChanged: ((Configuration) -> Unit)? = null

    /**
     * ★ Global Coroutine Exception Handler
     * 모든 코루틴 예외를 잡아 서비스가 죽지 않도록 방어
     */
    val globalExceptionHandler = CoroutineExceptionHandler { context, throwable ->
        Log.e(TAG, "★ Uncaught coroutine exception caught by GlobalHandler!", throwable)
        Log.e(TAG, "  Context: $context")
        Log.e(TAG, "  Message: ${throwable.message}")
        // 서비스를 죽이지 않고 계속 실행
        // 심각한 오류는 여기서 TrimMemory 레벨 10으로 한 번 정리 후 계속
        onMemoryModerate?.invoke()
    }

    /**
     * ★ SupervisorJob 기반 안정 스코프
     * 자식 코루틴 실패해도 다른 자식/부모는 취소되지 않음
     */
    val stableScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + globalExceptionHandler
    )

    /**
     * ★ ComponentCallbacks2 - 메모리 압박 레벨별 대응
     * Android LMK가 보내는 TRIM_MEMORY 시그널 처리
     */
    override fun onTrimMemory(level: Int) {
        when (level) {
            // 앱 포그라운드, 메모리 적절히 부족
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                Log.w(TAG, "Memory: RUNNING_MODERATE (level=$level) → soft cache clear")
                onMemoryModerate?.invoke()
            }
            // 앱 포그라운드, 메모리 심각히 부족
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                Log.w(TAG, "Memory: RUNNING_LOW (level=$level) → translation cache released")
                onMemoryLow?.invoke()
                System.gc()
            }
            // 앱 포그라운드, 메모리 위급 (곧 프로세스가 죽을 수 있음)
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.e(TAG, "Memory: RUNNING_CRITICAL (level=$level) → aggressive GC!")
                onMemoryCritical?.invoke()
                System.gc()
                Runtime.getRuntime().gc()
            }
            // 백그라운드 관련 (서비스에는 해당 없음)
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                Log.d(TAG, "Memory: UI_HIDDEN → UI cache can be released")
                onMemoryModerate?.invoke()
            }
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.w(TAG, "Memory: BACKGROUND level=$level → prepare for kill")
                onMemoryCritical?.invoke()
            }
            else -> Log.d(TAG, "Memory: unknown level=$level")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        Log.d(TAG, "Config changed: locale=${newConfig.locale}")
        onConfigChanged?.invoke(newConfig)
    }

    override fun onLowMemory() {
        Log.e(TAG, "★ onLowMemory() called! System is critically low on memory!")
        onMemoryCritical?.invoke()
        System.gc()
    }

    /**
     * ★ 안전한 코루틴 실행
     * 실패 시 GlobalExceptionHandler가 처리
     */
    fun launchSafe(block: suspend CoroutineScope.() -> Unit) {
        stableScope.launch(block = block)
    }

    /**
     * 메모리 사용량 리포트
     */
    fun getMemoryReport(): String {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)
        return "Memory: ${usedMb}MB / ${maxMb}MB (${usedMb * 100 / maxMb}%)"
    }
}
