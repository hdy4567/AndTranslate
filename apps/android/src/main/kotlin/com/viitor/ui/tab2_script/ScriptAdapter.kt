package com.viitor.ui.tab2_script

import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.viitor.ui.tab1_browser.domain.GoogleTranslationAgent
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScriptAdapter(
    private val onLineClicked: (Long) -> Unit
) : RecyclerView.Adapter<ScriptAdapter.ScriptViewHolder>() {

    var currentVideoId: String? = null
    private var items = listOf<com.viitor.pipeline.core.SubtitleData>()
    private val translationAgent = GoogleTranslationAgent()

    // 최적화: 전체 갱신(Lag 발생 방지)을 피하기 위한 현재/과거 인덱스 추적기
    private var currentActiveIndex = -1

    fun submitList(newList: List<com.viitor.pipeline.core.SubtitleData>) {
        items = newList
        currentActiveIndex = -1
        notifyDataSetChanged()
    }

    fun updateSyncTime(timeMs: Long, layoutManager: androidx.recyclerview.widget.LinearLayoutManager? = null) {
        if (items.isEmpty()) return

        // 이진 탐색 수준의 빠른 인덱스 찾기 함수 호출 등 응용 가능
        // 가장 직관적인 성능 확보: 시간대에 맞는 새 인덱스 스캔
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

        // 인덱스가 바뀌었을 때만 UI를 그려줌 (초당 수십 번의 갱신 무시)
        if (newIndex != -1 && newIndex != currentActiveIndex) {
            val oldIndex = currentActiveIndex
            currentActiveIndex = newIndex

            // [메모리 최적화] 바뀐 단 두 개의 행만 부분 렌더링
            if (oldIndex != -1) notifyItemChanged(oldIndex)
            notifyItemChanged(currentActiveIndex)

            // UI 스크롤을 현재 말하는 문장으로 부드럽게 자동 점프시킴
            layoutManager?.scrollToPositionWithOffset(currentActiveIndex, 200)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScriptViewHolder {
        val ctx = parent.context
        val layoutId = ctx.resources.getIdentifier("item_script_line", "layout", ctx.packageName)
        val view = LayoutInflater.from(ctx).inflate(layoutId, parent, false)
        return ScriptViewHolder(view)
    }

    override fun onBindViewHolder(holder: ScriptViewHolder, position: Int) {
        val line = items[position]
        val isCurrentLine = (position == currentActiveIndex)
        holder.bind(line, isCurrentLine)
    }

    override fun getItemCount(): Int = items.size

    inner class ScriptViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val flexJp: com.google.android.flexbox.FlexboxLayout = view.findViewById(
            view.context.resources.getIdentifier("flex_item_jp", "id", view.context.packageName)
        )
        private val tvKo: TextView = view.findViewById(
            view.context.resources.getIdentifier("tv_item_ko", "id", view.context.packageName)
        )
        private val tvTime: TextView = view.findViewById(
            view.context.resources.getIdentifier("tv_item_time", "id", view.context.packageName)
        )

        private fun formatTime(ms: Long): String {
            val totalSec = ms / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            return String.format("%02d:%02d", min, sec)
        }

        fun bind(line: com.viitor.pipeline.core.SubtitleData, isCurrent: Boolean) {
            // [WORD-CHIP-DECOMPOSITION] 형태소 분석 엔진 연결
            flexJp.removeAllViews()
            val tokens = com.viitor.pipeline.core.MorphemeAgent.tokenize(line.origin1layer)
            
            tokens.forEach { token ->
                val chip = TextView(itemView.context).apply {
                    text = token
                    textSize = 16f
                    setPadding(12, 6, 12, 6)
                    setTextColor(if (isCurrent) Color.parseColor("#FF4081") else Color.WHITE)
                    
                    // [INTERACTION-GATEWAY] 단어 클릭 시 프리미엄 팝업 노출
                    setOnClickListener { 
                        showWordActionDialog(token, line.origin1layer, line.tras2layer, line.anchorTimeMs)
                    }
                }
                flexJp.addView(chip)
            }
            
            tvTime.text = formatTime(line.anchorTimeMs)
            
            // [OPTIMIZATION] 이미 번역/발음 정보가 있다면 즉시 표시
            if (line.tras2layer != null) {
                tvKo.text = line.tras2layer
                // [COLOR-SYNC] 오버레이 설정과 연동된 색상 적용
                tvKo.setTextColor(com.viitor.pipeline.core.GlobalSubtitleRelay.currentTras2LayerColor)
            } else {
                if (tvKo.text.isEmpty() || tvKo.text == "번역을 가져오는 중...") {
                    tvKo.text = "번역 불러오는 중..."
                    (itemView.context as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope?.launch {
                        try {
                            val agent = com.viitor.ui.tab1_browser.domain.GoogleTranslationAgent()
                            val res = agent.fetchFullTranslation(line.origin1layer)
                            tvKo.text = res.first
                            line.tras2layer = res.first
                        } catch (e: Exception) {
                            tvKo.text = "(!)"
                        }
                    }
                }
            }

            if (isCurrent) {
                itemView.setBackgroundColor(Color.parseColor("#33FF4081"))
            } else {
                itemView.setBackgroundColor(Color.TRANSPARENT)
            }

            itemView.setOnClickListener {
                onLineClicked(line.anchorTimeMs)
            }
        }

        /** [PREMIUM-ACTION] 단어별 사전/저장/닫기 팝업 UI (Deep Black & Blue design) */
        private fun showWordActionDialog(token: String, origin1layer: String, tras2layer: String?, anchorTimeMs: Long) {
            val ctx = itemView.context
            val builder = androidx.appcompat.app.AlertDialog.Builder(ctx, com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
            
            builder.setTitle(token)
            // [CONTEXT-MAPPING] 별도의 단어 번역 없이, 이미 존재하는 문장 해석본을 문맥으로 보여줌 (최적화)
            builder.setMessage("문맥: ${tras2layer ?: "번역 없음"}\n\n이 단어를 단어장에 보관하시겠습니까?")
            
            builder.setPositiveButton("네이버 사전") { _, _ ->
                com.viitor.util.DictActionUtil.openNaverDict(ctx, token)
            }
            builder.setNegativeButton("단어장에 저장") { _, _ ->
                com.viitor.util.DictActionUtil.saveToVault(
                    ctx, 
                    token, 
                    tras2layer, // 문장 해석본을 단어의 의미로 매핑
                    origin1layer, 
                    anchorTimeMs,
                    "Tab2_Script",
                    currentVideoId
                )
                android.widget.Toast.makeText(ctx, "단어장에 저장되었습니다.", android.widget.Toast.LENGTH_SHORT).show()
            }
            builder.setNeutralButton("닫기", null)
            
            val dialog = builder.create()
            dialog.show()
            
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#64B5F6"))
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.parseColor("#FF4081")) // Save (Aura Pink)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.parseColor("#A0A0A0"))
        }
    }
}
