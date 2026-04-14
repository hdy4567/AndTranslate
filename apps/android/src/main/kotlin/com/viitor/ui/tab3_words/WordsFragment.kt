package com.viitor.ui.tab3_words

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.viitor.ui.SharedSyncViewModel
import com.viitor.util.ResourceBroker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class WordsFragment : Fragment() {

    private val sharedViewModel: SharedSyncViewModel by activityViewModels()
    private lateinit var recyclerView: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(ResourceBroker.layoutWords, container, false)
        
        recyclerView = view.findViewById(ResourceBroker.idWordsList)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        
        val adapter = WordsAdapter(
            onDelete = { id -> sharedViewModel.deleteWord(id) },
            onDict = { jp -> 
                com.viitor.util.DictActionUtil.openNaverDict(requireContext(), jp) 
            },
            onWarp = { videoId, timeMs -> 
                sharedViewModel.warpToWord(videoId, timeMs)
                (activity as? com.viitor.MainActivity)?.navigateToTab(0)
            }
        )
        recyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            sharedViewModel.savedWordsList.collectLatest { list ->
                adapter.submitList(list)
                // [COUNT-SYNC] 저장된 단어 개수 실시간 반영 (Aura Capsule V5 Sync)
                val tvCount = view.findViewById<android.widget.TextView>(ResourceBroker.idWordCount)
                tvCount?.text = "저장된 단어: ${list.size}개"
            }
        }

        val btnQuiz = view.findViewById<View>(ResourceBroker.idQuizBtn)
        btnQuiz.setOnClickListener {
            val words = sharedViewModel.savedWordsList.value
            if (words.isNotEmpty()) {
                showPremiumQuiz(words)
            } else {
                android.widget.Toast.makeText(requireContext(), "저장된 단어가 없습니다. 단어를 먼저 추가해주세요!", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        return view
    }

    private fun showPremiumQuiz(words: List<com.viitor.data.local.VocabularyEntity>) {
        val dialog = android.app.Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val quizView = LayoutInflater.from(requireContext()).inflate(ResourceBroker.layoutPremiumQuiz, null)
        dialog.setContentView(quizView)

        val tvWord = quizView.findViewById<android.widget.TextView>(ResourceBroker.idQuizWord)
        val tvContext = quizView.findViewById<android.widget.TextView>(ResourceBroker.idQuizContext)
        val tvMeaning = quizView.findViewById<android.widget.TextView>(ResourceBroker.idQuizMeaning)
        val canvas = quizView.findViewById<HandwritingCanvasView>(ResourceBroker.idQuizCanvas)
        val btnNext = quizView.findViewById<android.widget.Button>(ResourceBroker.idQuizNext)
        val btnClear = quizView.findViewById<View>(ResourceBroker.idQuizClearCanvas)
        val cardRoot = quizView.findViewById<View>(ResourceBroker.idQuizRoot)

        var currentIndex = (0 until words.size).random()
        
        fun loadWord(idx: Int) {
            val word = words[idx]
            tvWord.text = word.word
            tvContext.text = word.contextText ?: "이 단어가 포함된 문맥이 없습니다."
            tvMeaning.text = "[ ??? ]"
            tvMeaning.setTextColor(android.graphics.Color.parseColor("#44FFFFFF"))
            canvas.clear()
        }

        loadWord(currentIndex)

        var isRevealed = false
        
        // [INTERACTION-FIX] 카드 전체 터치 시 정답 노출 (단, 캔버스 드로잉 중일 때는 캔버스가 이벤트를 소모하므로 영향 없음)
        cardRoot.setOnClickListener {
            val word = words[currentIndex]
            isRevealed = !isRevealed
            
            if (isRevealed) {
                tvMeaning.text = word.translated ?: "의미 없음"
                tvMeaning.setTextColor(android.graphics.Color.WHITE)
            } else {
                tvMeaning.text = "[ ??? ]"
                tvMeaning.setTextColor(android.graphics.Color.parseColor("#44FFFFFF"))
            }
        }

        btnNext.setOnClickListener {
            currentIndex = (0 until words.size).random()
            isRevealed = false
            loadWord(currentIndex)
        }

        btnClear.setOnClickListener {
            canvas.clear()
        }

        dialog.show()
    }
}
