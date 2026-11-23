package com.viitor.ui.tab3_words

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.viitor.R

/**
 * [WordsAdapter] - 3탭 단어 리스트 어댑터 (Premium Interaction)
 */
class WordsAdapter(
    private val onDelete: (Int) -> Unit,
    private val onDict: (String) -> Unit,
    private val onWarp: (String?, Long) -> Unit
) : RecyclerView.Adapter<WordsAdapter.WordViewHolder>() {

    private var words = listOf<com.viitor.data.local.VocabularyEntity>()

    private val expandedPositions = mutableSetOf<Int>()

    fun submitList(newList: List<com.viitor.data.local.VocabularyEntity>) {
        words = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WordViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_word_container, parent, false)
        return WordViewHolder(view)
    }

    override fun onBindViewHolder(holder: WordViewHolder, position: Int) {
        holder.bind(words[position], position)
    }

    override fun getItemCount(): Int = words.size

    inner class WordViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvWord: TextView = view.findViewById(R.id.tv_word_main)
        private val tvMeaning: TextView = view.findViewById(R.id.tv_word_meaning)
        private val tvContext: TextView = view.findViewById(R.id.tv_word_context)
        private val containerContext: View = view.findViewById(R.id.container_context)
        private val llToggle: View = view.findViewById(R.id.ll_context_toggle)
        private val ivChevron: android.widget.ImageView = view.findViewById(R.id.iv_context_chevron)
        
        private val ivWarp: View = view.findViewById(R.id.iv_warp_word)
        private val ivDict: View = view.findViewById(R.id.iv_dict_word)
        private val ivDelete: View = view.findViewById(R.id.iv_delete_word)

        fun bind(entity: com.viitor.data.local.VocabularyEntity, position: Int) {
            tvWord.text = entity.word
            tvMeaning.text = entity.tras2layer ?: "의미를 확인하려면 클릭하세요"
            
            val hasContext = !entity.origin1layer.isNullOrEmpty()
            llToggle.visibility = if (hasContext) View.VISIBLE else View.GONE
            
            // [ACCORDION-LOGIC] 확장 상태에 따른 UI 분기
            val isExpanded = expandedPositions.contains(position)
            containerContext.visibility = if (isExpanded && hasContext) View.VISIBLE else View.GONE
            ivChevron.rotation = if (isExpanded) 180f else 0f
            
            if (hasContext) {
                tvContext.text = entity.origin1layer
                
                llToggle.setOnClickListener {
                    if (expandedPositions.contains(position)) {
                        expandedPositions.remove(position)
                    } else {
                        expandedPositions.add(position)
                    }
                    notifyItemChanged(position)
                }
                
                // 문맥 클릭 시 워프 연동 (Smart Warp)
                containerContext.setOnClickListener { onWarp(entity.videoId, entity.anchorTimeMs) }
            }

            // 💡 [WARP] 아이콘 클릭 시 즉시 워프
            ivWarp.setOnClickListener { onWarp(entity.videoId, entity.anchorTimeMs) }
            
            // 📖 [DICT] 네이버 사전 열기
            ivDict.setOnClickListener { onDict(entity.word) }
            
            // 🗑 [DELETE] DB에서 삭제
            ivDelete.setOnClickListener { onDelete(entity.id) }

            // 아이템 헤더 클릭 시 사전 연동 로직 제거 (유저 요청: 돋보기 아이콘으로만 동작)
            itemView.findViewById<View>(R.id.ll_word_group)?.setOnClickListener(null)
        }
    }
}
