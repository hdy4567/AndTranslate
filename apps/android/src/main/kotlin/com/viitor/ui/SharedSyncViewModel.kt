package com.viitor.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viitor.data.local.ViitorLocalDatabase
import com.viitor.data.local.VocabularyEntity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 1탭(Browser)과 2탭(Script) 사이의 크로스-탭 앵커링 스위치보드
 * Activity 범위에서 단일 인스턴스로 생존하며 두 탭의 심장 역할을 수행합니다.
 */
class SharedSyncViewModel(application: Application) : AndroidViewModel(application) {

    private val vocabularyDao = ViitorLocalDatabase.getDatabase(application).vocabularyDao()

    // 현재 플레이중인 영상 ID
    private val _currentVideoId = MutableStateFlow("")
    val currentVideoId: StateFlow<String> = _currentVideoId

    // 1탭에서 자바스크립트가 탈취하여 코틀린으로 보낸 실시간 시간 (ms)
    private val _currentPlayTimeMs = MutableStateFlow(0L)
    val currentPlayTimeMs: StateFlow<Long> = _currentPlayTimeMs

    // 2탭(Script)에서 쌓여가는 전체 대본 리스트
    private val _liveScriptList = MutableStateFlow<List<com.viitor.pipeline.core.SubtitleData>>(emptyList())
    val liveScriptList: StateFlow<List<com.viitor.pipeline.core.SubtitleData>> = _liveScriptList

    // 3탭(Words) 단어 저장소 - [Room DB 관찰형 Flow]
    val savedWordsList: StateFlow<List<VocabularyEntity>> = vocabularyDao.getAllWords()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // 2탭에서 특정 대본을 눌렀을 때, 1탭 웹뷰로 명령을 내리는 단발성 점프 트리거
    private val _jumpCommandEvent = MutableSharedFlow<Long>()
    val jumpCommandEvent: SharedFlow<Long> = _jumpCommandEvent

    // 2번 레이어(번역문) 전역 색상 상태 (1탭 설정과 연동)
    private val _translationColor = MutableStateFlow(android.graphics.Color.WHITE)
    val translationColor: StateFlow<Int> = _translationColor

    fun updateTranslationColor(color: Int) {
        _translationColor.value = color
        com.viitor.pipeline.core.GlobalSubtitleRelay.currentTranslationColor = color
    }

    /** [SCRIPT-ACCRUAL] 새로운 자막 데이터 누적 및 원자적(Line-by-line) 중복 필터링 */
    fun appendScript(data: com.viitor.pipeline.core.SubtitleData) {
        if (data.original.isBlank()) return
        
        val current = _liveScriptList.value.toMutableList()
        
        // [LINE-ATOMIZATION] 통째로 들어온 자막을 실제 문장 단위로 분해
        val incomingLines = data.original.split("\n").filter { it.isNotBlank() }
        val incomingTranslatedLines = data.translated?.split("\n") ?: emptyList()
        
        var modified = false
        
        incomingLines.forEachIndexed { index, lineText ->
            if (current.isEmpty()) {
                current.add(com.viitor.pipeline.core.SubtitleData(
                    original = lineText,
                    translated = incomingTranslatedLines.getOrNull(index),
                    pronunciation = null,
                    anchorTimeMs = data.anchorTimeMs
                ))
                modified = true
            } else {
                val last = current.last()
                
                // 1. 완전 일치 또는 포함 관계 (업데이트/무시)
                if (lineText == last.original) return@forEachIndexed
                
                if (lineText.startsWith(last.original) || (last.original.length > 3 && lineText.contains(last.original))) {
                    // 더 정보가 많은(긴) 문장으로 덮어쓰기
                    current[current.size - 1] = last.copy(
                        original = lineText,
                        translated = incomingTranslatedLines.getOrNull(index) ?: last.translated
                    )
                    modified = true
                } else if (!last.original.startsWith(lineText)) {
                    // 2. 완전히 새로운 문장 (단, 기존 문장의 과거 조각이 아닐 때만 추가)
                    current.add(com.viitor.pipeline.core.SubtitleData(
                        original = lineText,
                        translated = incomingTranslatedLines.getOrNull(index),
                        pronunciation = null,
                        anchorTimeMs = data.anchorTimeMs
                    ))
                    modified = true
                }
            }
        }
        
        if (modified) {
            _liveScriptList.value = current
        }
    }

    /** [WORD-PERSISTENCE] 단어 영구 저장 (Room DB) */
    fun saveWord(word: String, translated: String? = null, anchorTimeMs: Long = 0L) {
        if (word.isBlank()) return
        viewModelScope.launch {
            vocabularyDao.insertWord(VocabularyEntity(word = word, translated = translated, contextText = null, anchorTimeMs = anchorTimeMs))
        }
    }

    /** [WORD-INTERACTION] 단어 삭제 */
    fun deleteWord(id: Int) {
        viewModelScope.launch {
            vocabularyDao.deleteWord(id)
        }
    }

    /** [WORD-WARP] 해당 단어가 캡처된 시간대로 점프 */
    fun warpToWord(word: String) {
        // 단어 저장 시 "원문 : 뜻 : 시간" 형식으로 저장되어 있다면 시간 추출
        val parts = word.split(" : ")
        if (parts.size >= 3) {
            val timeMs = parts[2].toLongOrNull() ?: 0L
            commandJumpToTime(timeMs)
        }
    }

    fun clearScript() {
        _liveScriptList.value = emptyList()
    }

    fun broadcastVideoId(videoId: String) {
        if (_currentVideoId.value != videoId) {
            _currentVideoId.value = videoId
            // [URL-FLUSH] 영상 변경 감지 즉시 모든 자막 유산(Legacy) 제거 및 초기화
            clearScript()
            com.viitor.pipeline.core.GlobalSubtitleRelay.flush()
            Log.i("SharedSyncViewModel", "[URL-FLUSH] New Video Detected: $videoId. All buffers cleared.")
        }
    }

    fun broadcastRealtimeSync(timeMs: Long) {
        _currentPlayTimeMs.value = timeMs
    }

    /** [WORD-WARP] 해당 단어가 캡처된 영상과 시간대로 정밀 점프 */
    fun warpToWord(videoId: String?, targetTimeMs: Long) {
        if (videoId != null && _currentVideoId.value != videoId) {
            broadcastVideoId(videoId)
        }
        commandJumpToTime(targetTimeMs)
    }

    fun commandJumpToTime(targetTimeMs: Long) {
        viewModelScope.launch {
            _jumpCommandEvent.emit(targetTimeMs)
        }
    }
}
