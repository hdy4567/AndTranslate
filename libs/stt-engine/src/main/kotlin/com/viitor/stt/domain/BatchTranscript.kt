package com.viitor.stt.domain

data class BatchTranscript(
    val text: String,
    val translatedText: String? = null,  // 추가
    val chunkCount: Int                  // 배치에 포함된 청크 수
)

