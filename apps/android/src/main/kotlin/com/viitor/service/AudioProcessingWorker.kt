package com.viitor.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * ★ AudioProcessingWorker: STT 백그라운드 처리 (WorkManager)
 * - CoroutineScope(Dispatchers.Default)
 * - ProvisionalUseCase (단일 청크)
 * - FinalUseCase (배치 4개)
 */
class AudioProcessingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // ERR_THREAD_LEAK: @WorkerThread + CoroutineScope(Dispatchers.Default)
        return Result.success()
    }
}
