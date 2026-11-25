package com.viitor.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viitor.data.local.ViitorLocalDatabase
import com.viitor.data.local.VocabularyEntity
import com.viitor.pipeline.core.GlobalSubtitleRelay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SharedSyncViewModel(application: Application) : AndroidViewModel(application) {

    private val vocabularyDao = ViitorLocalDatabase.getDatabase(application).vocabularyDao()

    private val _currentVideoId = MutableStateFlow("")
    val currentVideoId: StateFlow<String> = _currentVideoId

    private val _currentPlayTimeMs = MutableStateFlow(0L)
    val currentPlayTimeMs: StateFlow<Long> = _currentPlayTimeMs

    private val _liveScriptList = MutableStateFlow<List<com.viitor.pipeline.core.SubtitleData>>(emptyList())
    val liveScriptList: StateFlow<List<com.viitor.pipeline.core.SubtitleData>> = _liveScriptList

    val savedWordsList: StateFlow<List<VocabularyEntity>> = vocabularyDao.getAllWords()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val reviewWordsList: StateFlow<List<VocabularyEntity>> = vocabularyDao.getWordsForReview()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val loopConfig: StateFlow<GlobalSubtitleRelay.LoopConfig> = GlobalSubtitleRelay.loopConfig

    private val _jumpCommandEvent = MutableSharedFlow<Long>()
    val jumpCommandEvent: SharedFlow<Long> = _jumpCommandEvent

    private val _translationColor = MutableStateFlow(android.graphics.Color.WHITE)
    val translationColor: StateFlow<Int> = _translationColor

    fun recordReviewResult(entity: VocabularyEntity, isKnown: Boolean) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val oneDayMs = 24L * 60 * 60 * 1000
            if (isKnown) {
                val nextInterval = (entity.reviewIntervalDays * 2).coerceAtMost(60).coerceAtLeast(2)
                val nextReview = now + (nextInterval * oneDayMs)
                vocabularyDao.updateReviewProgress(entity.id, nextReview, entity.wrongCount, nextInterval)
            } else {
                val nextWrong = entity.wrongCount + 1
                val nextInterval = 1
                val nextReview = now + oneDayMs
                vocabularyDao.updateReviewProgress(entity.id, nextReview, nextWrong, nextInterval)
            }
        }
    }

    fun setAbLoop(startMs: Long, endMs: Long) {
        GlobalSubtitleRelay.setAbLoop(startMs, endMs)
        commandJumpToTime(startMs)
    }

    fun clearAbLoop() {
        GlobalSubtitleRelay.clearAbLoop()
    }

    fun toggleShadowPause() {
        GlobalSubtitleRelay.toggleShadowPause()
    }

    fun updateTranslationColor(color: Int) {
        _translationColor.value = color
        GlobalSubtitleRelay.currentTras2LayerColor = color
    }

    fun appendScript(data: com.viitor.pipeline.core.SubtitleData) {
        if (data.origin1layer.isBlank()) return
        val current = _liveScriptList.value.toMutableList()
        val incomingLines = data.origin1layer.split("\n").filter { it.isNotBlank() }
        val incomingTranslatedLines = data.tras2layer?.split("\n") ?: emptyList()
        var modified = false

        incomingLines.forEachIndexed { index, lineText ->
            if (current.isEmpty()) {
                current.add(com.viitor.pipeline.core.SubtitleData(
                    origin1layer = lineText,
                    tras2layer = incomingTranslatedLines.getOrNull(index),
                    pron3layer = null,
                    anchorTimeMs = data.anchorTimeMs
                ))
                modified = true
            } else {
                val last = current.last()
                if (lineText == last.origin1layer) return@forEachIndexed
                if (lineText.startsWith(last.origin1layer) || (last.origin1layer.length > 3 && lineText.contains(last.origin1layer))) {
                    current[current.size - 1] = last.copy(
                        origin1layer = lineText,
                        tras2layer = incomingTranslatedLines.getOrNull(index) ?: last.tras2layer
                    )
                    modified = true
                } else if (!last.origin1layer.startsWith(lineText)) {
                    current.add(com.viitor.pipeline.core.SubtitleData(
                        origin1layer = lineText,
                        tras2layer = incomingTranslatedLines.getOrNull(index),
                        pron3layer = null,
                        anchorTimeMs = data.anchorTimeMs
                    ))
                    modified = true
                }
            }
        }
        if (modified) _liveScriptList.value = current
    }

    fun saveWord(word: String, tras2layer: String? = null, anchorTimeMs: Long = 0L) {
        if (word.isBlank()) return
        viewModelScope.launch {
            vocabularyDao.insertWord(VocabularyEntity(word = word, tras2layer = tras2layer, origin1layer = null, anchorTimeMs = anchorTimeMs))
        }
    }

    fun deleteWord(id: Int) {
        viewModelScope.launch { vocabularyDao.deleteWord(id) }
    }

    fun warpToWord(word: String) {
        val parts = word.split(" : ")
        if (parts.size >= 3) {
            commandJumpToTime(parts[2].toLongOrNull() ?: 0L)
        }
    }

    fun clearScript() { _liveScriptList.value = emptyList() }

    fun broadcastVideoId(videoId: String) {
        if (_currentVideoId.value != videoId) {
            _currentVideoId.value = videoId
            clearScript()
            GlobalSubtitleRelay.flush()
            Log.i("SharedSyncViewModel", "[URL-FLUSH] New Video Detected: $videoId.")
        }
    }

    fun broadcastRealtimeSync(timeMs: Long) { _currentPlayTimeMs.value = timeMs }

    fun warpToWord(videoId: String?, targetTimeMs: Long) {
        if (videoId != null && _currentVideoId.value != videoId) broadcastVideoId(videoId)
        commandJumpToTime(targetTimeMs)
    }

    fun commandJumpToTime(targetTimeMs: Long) {
        viewModelScope.launch { _jumpCommandEvent.emit(targetTimeMs) }
    }
}
