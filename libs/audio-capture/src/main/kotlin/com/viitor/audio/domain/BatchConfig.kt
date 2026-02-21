package com.viitor.audio.domain

/**
 * ERR_INVALID_BATCH_CONFIG: @Range(min=1, max=4)
 */
data class BatchConfig(
    val maxBatchSize: Int = 4,         // @MaxBatchSize(4)
    val lookbackSec: Int = 6           // 6초 이내 청크만 배치 구성
)
