package com.viitor.ui.tab2_script

import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.viitor.R
import com.viitor.data.local.VocabularyEntity
import com.viitor.pipeline.core.GlobalSubtitleRelay
import com.viitor.pipeline.core.JapanesePronunciationAgent
import com.viitor.pipeline.core.MorphemeAgent
import com.viitor.pipeline.core.SubtitleData
import com.viitor.ui.tab1_browser.domain.GoogleTranslationAgent
import com.viitor.util.DictActionUtil
import kotlinx.coroutines.launch

class ScriptAdapter(
    private val onLineClicked: (Long) -> Unit,
    private val onLineLongClicked: ((SubtitleData) -> Unit)? = null,
    private val onWarpToVocabContext: ((String?, Long) -> Unit)? = null
) : RecyclerView.Adapter<ScriptAdapter.ScriptViewHolder>() {

    var currentVideoId: String? = null
    var isAutoScrollEnabled: Boolean = true // [USER-CONTROL] 자동 스크롤 추적 On/Off 플래그

    private var items = listOf<SubtitleData>()
    private val translationAgent = GoogleTranslationAgent()
    private val inFlightTranslations = mutableSetOf<String>()

    private var savedVocabList = listOf<VocabularyEntity>()
    private var savedVocabWords = setOf<String>()

    fun getItems(): List<SubtitleData> = items

    private var currentActiveIndex = -1

    fun submitList(newList: List<SubtitleData>) {
        items = newList
        currentActiveIndex = -1
        notifyDataSetChanged()
    }

    fun setSavedWords(list: List<VocabularyEntity>) {
        savedVocabList = list
        savedVocabWords = list.map { it.word.trim() }.filter { it.isNotBlank() }.toSet()
        notifyDataSetChanged()
    }

    fun updateSyncTime(timeMs: Long, layoutManager: LinearLayoutManager? = null) {
        if (items.isEmpty()) return

        var newIndex = -1
        for (i in items.indices) {
            val isCurrent = if (i < items.size - 1) {
                timeMs in items[i].anchorTimeMs until items[i+1].anchorTimeMs
            } else {
                timeMs >= items[i].anchorTimeMs
            }
            
            if (isCurrent) {
                newIndex = i
                break
            }
        }

        if (newIndex != -1 && newIndex != currentActiveIndex) {
            val oldIndex = currentActiveIndex
            currentActiveIndex = newIndex

            if (oldIndex != -1) notifyItemChanged(oldIndex)
            notifyItemChanged(currentActiveIndex)

            // [AUTO-SCROLL-GUARD] 사용자가 자동 스크롤을 켰을 때만 화면 이동
            if (isAutoScrollEnabled) {
                layoutManager?.scrollToPositionWithOffset(currentActiveIndex, 200)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScriptViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_script_line, parent, false)
        return ScriptViewHolder(view)
    }

    override fun onBindViewHolder(holder: ScriptViewHolder, position: Int) {
        val line = items[position]
        val isCurrentLine = (position == currentActiveIndex)
        holder.bind(line, isCurrentLine)
    }

    override fun getItemCount(): Int = items.size

    inner class ScriptViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val flexJp: com.google.android.flexbox.FlexboxLayout = view.findViewById(R.id.flex_item_jp)
        private val tvKo: TextView = view.findViewById(R.id.tv_item_ko)
        private val tvPron: TextView = view.findViewById(R.id.tv_item_pronunciation)
        private val tvTime: TextView = view.findViewById(R.id.tv_item_time)

        private fun formatTime(ms: Long): String {
            val totalSec = ms / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            return String.format("%02d:%02d", min, sec)
        }

        fun bind(line: SubtitleData, isCurrent: Boolean) {
            flexJp.removeAllViews()
            val tokens = MorphemeAgent.tokenize(line.origin1layer)
            
            tokens.forEach { token ->
                val trimmedToken = token.trim()
                // [VOCAB-GUARD] 1글자 조사 오탐 방지: 2글자 이상 매칭 또는 완전 일치
                val isSavedVocab = savedVocabWords.contains(trimmedToken) || 
                        (trimmedToken.length >= 2 && savedVocabWords.any { it.length >= 2 && trimmedToken.contains(it, ignoreCase = true) })

                val chip = TextView(itemView.context).apply {
                    text = token
                    textSize = 16f
                    setPadding(12, 6, 12, 6)
                    
                    if (isSavedVocab) {
                        // [VOCAB-HIGHLIGHT] 내가 저장한 단어는 형광 하이라이트
                        setBackgroundColor(Color.parseColor("#FFE57F"))
                        setTextColor(Color.parseColor("#1B1B1B"))
                        typeface = Typeface.DEFAULT_BOLD
                    } else {
                        setBackgroundColor(Color.TRANSPARENT)
                        setTextColor(if (isCurrent) Color.parseColor("#FF4081") else Color.WHITE)
                        typeface = Typeface.DEFAULT
                    }
                    
                    setOnClickListener { 
                        showWordActionDialog(token, line.origin1layer, line.tras2layer, line.anchorTimeMs)
                    }
                }
                flexJp.addView(chip)
            }
            
            tvTime.text = formatTime(line.anchorTimeMs)
            
            if (line.tras2layer != null) {
                tvKo.text = line.tras2layer
                tvKo.setTextColor(GlobalSubtitleRelay.currentTras2LayerColor)
            } else {
                val originKey = line.origin1layer
                if (!inFlightTranslations.contains(originKey)) {
                    inFlightTranslations.add(originKey)
                    tvKo.text = "번역 불러오는 중..."
                    (itemView.context as? LifecycleOwner)?.lifecycleScope?.launch {
                        try {
                            val res = translationAgent.fetchFullTranslation(originKey)
                            if (res.first.isNotBlank()) {
                                tvKo.text = res.first
                                line.tras2layer = res.first
                                val hangulPron = if (res.second.isNotBlank()) {
                                    JapanesePronunciationAgent.convertRomajiToHangul(res.second)
                                } else ""
                                line.pron3layer = hangulPron
                                if (hangulPron.isNotBlank()) {
                                    tvPron.visibility = View.VISIBLE
                                    tvPron.text = hangulPron
                                }
                            } else {
                                tvKo.text = ""
                            }
                        } catch (e: Exception) {
                            tvKo.text = ""
                        } finally {
                            inFlightTranslations.remove(originKey)
                        }
                    }
                }
            }

            val pronText = line.pron3layer.orEmpty()
            if (pronText.isNotBlank()) {
                tvPron.visibility = View.VISIBLE
                tvPron.text = pronText
            } else {
                tvPron.visibility = View.GONE
            }

            val loop = GlobalSubtitleRelay.loopConfig.value
            val isInLoop = loop.enabled && line.anchorTimeMs >= loop.startMs && line.anchorTimeMs <= loop.endMs

            if (isCurrent) {
                itemView.setBackgroundColor(Color.parseColor("#33FF4081"))
            } else if (isInLoop) {
                itemView.setBackgroundColor(Color.parseColor("#2664B5F6"))
            } else {
                itemView.setBackgroundColor(Color.TRANSPARENT)
            }

            itemView.setOnClickListener {
                onLineClicked(line.anchorTimeMs)
            }

            itemView.setOnLongClickListener {
                onLineLongClicked?.invoke(line)
                true
            }
        }

        private fun showWordActionDialog(token: String, origin1layer: String, tras2layer: String?, anchorTimeMs: Long) {
            val ctx = itemView.context
            val builder = androidx.appcompat.app.AlertDialog.Builder(ctx, com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
            
            val trimmed = token.trim()
            val matchedEntity = savedVocabList.find { 
                it.word.equals(trimmed, ignoreCase = true) || (it.word.length >= 2 && trimmed.contains(it.word, ignoreCase = true))
            }

            if (matchedEntity != null) {
                builder.setTitle("★ [보관된 단어] $token")
                builder.setMessage(
                    "현재 번역: ${tras2layer ?: "번역 없음"}\n\n" +
                    "📌 [단어장 저장 당시 문맥]\n${matchedEntity.origin1layer ?: "저장된 문맥이 없습니다."}\n\n" +
                    "📌 [단어장 저장 당시 번역]\n${matchedEntity.tras2layer ?: "해석 없음"}"
                )
                if (matchedEntity.anchorTimeMs > 0L) {
                    builder.setNeutralButton("저장 문맥으로 이동 (${formatTime(matchedEntity.anchorTimeMs)})") { _, _ ->
                        onWarpToVocabContext?.invoke(matchedEntity.videoId, matchedEntity.anchorTimeMs)
                    }
                } else {
                    builder.setNeutralButton("닫기", null)
                }
            } else {
                builder.setTitle(token)
                builder.setMessage("문맥: ${tras2layer ?: "번역 없음"}\n\n이 단어를 단어장에 보관하시겠습니까?")
                builder.setNeutralButton("닫기", null)
            }
            
            builder.setPositiveButton("네이버 사전") { _, _ ->
                DictActionUtil.openNaverDict(ctx, token)
            }
            builder.setNegativeButton(if (matchedEntity != null) "단어장 갱신" else "단어장에 저장") { _, _ ->
                DictActionUtil.saveToVault(
                    ctx, 
                    token, 
                    tras2layer,
                    origin1layer, 
                    anchorTimeMs,
                    "Tab2_Script",
                    currentVideoId
                )
                android.widget.Toast.makeText(ctx, "단어장에 저장되었습니다.", android.widget.Toast.LENGTH_SHORT).show()
            }
            
            val dialog = builder.create()
            dialog.show()
            
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#64B5F6"))
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.parseColor("#FF4081"))
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.parseColor("#FFD54F"))
        }
    }
}
