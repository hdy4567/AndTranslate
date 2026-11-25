package com.viitor.ui.tab3_words

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.viitor.MainActivity
import com.viitor.data.local.VocabularyEntity
import com.viitor.ui.SharedSyncViewModel
import com.viitor.util.DictActionUtil
import com.viitor.util.ResourceBroker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class WordsFragment : Fragment() {

    private val sharedViewModel: SharedSyncViewModel by activityViewModels()
    private lateinit var recyclerView: RecyclerView
    private var tts: TextToSpeech? = null

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
                DictActionUtil.openNaverDict(requireContext(), jp) 
            },
            onWarp = { videoId, timeMs -> 
                sharedViewModel.warpToWord(videoId, timeMs)
                (activity as? MainActivity)?.navigateToTab(0)
            }
        )
        recyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            sharedViewModel.savedWordsList.collectLatest { list ->
                adapter.submitList(list)
                // [COUNT-SYNC] 저장된 단어 개수 실시간 반영 (Aura Capsule V5 Sync)
                val tvCount = view.findViewById<TextView>(ResourceBroker.idWordCount)
                tvCount?.text = "저장된 단어: ${list.size}개"
            }
        }

        initTts()

        val btnQuiz = view.findViewById<View>(ResourceBroker.idQuizBtn)
        btnQuiz.setOnClickListener {
            val words = sharedViewModel.savedWordsList.value
            if (words.isNotEmpty()) {
                showPremiumQuiz(words)
            } else {
                Toast.makeText(requireContext(), "저장된 단어가 없습니다. 단어를 먼저 추가해주세요!", Toast.LENGTH_SHORT).show()
            }
        }

        return view
    }

    private fun initTts() {
        if (tts == null) {
            tts = TextToSpeech(requireContext().applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.JAPANESE
                }
            }
        }
    }

    private fun speakWord(text: String) {
        if (text.isBlank()) return
        val isJapanese = text.any { c ->
            (c in '\u3040'..'\u309F') || (c in '\u30A0'..'\u30FF') || (c in '\u4E00'..'\u9FFF')
        }
        val locale = if (isJapanese) Locale.JAPANESE else Locale.US
        tts?.language = locale
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "QUIZ_WORD_TTS")
    }

    private fun showPremiumQuiz(words: List<VocabularyEntity>) {
        val dialog = Dialog(requireContext(), android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val quizView = LayoutInflater.from(requireContext()).inflate(ResourceBroker.layoutPremiumQuiz, null)
        dialog.setContentView(quizView)

        val tvWord = quizView.findViewById<TextView>(ResourceBroker.idQuizWord)
        val btnTts = quizView.findViewById<ImageView>(ResourceBroker.idQuizTts)
        val tvContext = quizView.findViewById<TextView>(ResourceBroker.idQuizContext)
        val tvMeaning = quizView.findViewById<TextView>(ResourceBroker.idQuizMeaning)
        val canvas = quizView.findViewById<HandwritingCanvasView>(ResourceBroker.idQuizCanvas)
        val btnNext = quizView.findViewById<Button>(ResourceBroker.idQuizNext)
        val btnClear = quizView.findViewById<View>(ResourceBroker.idQuizClearCanvas)
        val cardRoot = quizView.findViewById<View>(ResourceBroker.idQuizRoot)
        val layoutGrade = quizView.findViewById<View>(ResourceBroker.idQuizGradeLayout)
        val btnKnow = quizView.findViewById<Button>(ResourceBroker.idQuizKnow)
        val btnRetry = quizView.findViewById<Button>(ResourceBroker.idQuizRetry)

        // SRS Priority Ordering: Due words first, then earliest review, then most wrong answers
        val now = System.currentTimeMillis()
        val sortedWords = words.sortedWith(
            compareBy<VocabularyEntity> { if (it.nextReviewAt <= now) 0 else 1 }
                .thenBy { it.nextReviewAt }
                .thenByDescending { it.wrongCount }
        ).toMutableList()

        var currentIndex = 0
        var isRevealed = false

        fun loadWord(idx: Int) {
            val word = sortedWords[idx]
            tvWord.text = word.word
            tvContext.text = word.origin1layer ?: "이 단어가 포함된 문맥이 없습니다."
            tvMeaning.text = "[ ??? ]"
            tvMeaning.setTextColor(Color.parseColor("#44FFFFFF"))
            layoutGrade?.visibility = View.GONE
            canvas.clear()
            speakWord(word.word)
        }

        loadWord(currentIndex)

        btnTts?.setOnClickListener {
            val word = sortedWords[currentIndex]
            speakWord(word.word)
        }

        cardRoot.setOnClickListener {
            val word = sortedWords[currentIndex]
            isRevealed = !isRevealed
            if (isRevealed) {
                tvMeaning.text = word.tras2layer ?: "의미 없음"
                tvMeaning.setTextColor(Color.WHITE)
                layoutGrade?.visibility = View.VISIBLE
            } else {
                tvMeaning.text = "[ ??? ]"
                tvMeaning.setTextColor(Color.parseColor("#44FFFFFF"))
                layoutGrade?.visibility = View.GONE
            }
        }

        btnKnow?.setOnClickListener {
            val word = sortedWords[currentIndex]
            sharedViewModel.recordReviewResult(word, true)
            isRevealed = false
            currentIndex = (currentIndex + 1) % sortedWords.size
            loadWord(currentIndex)
        }

        btnRetry?.setOnClickListener {
            val word = sortedWords[currentIndex]
            sharedViewModel.recordReviewResult(word, false)
            isRevealed = false
            currentIndex = (currentIndex + 1) % sortedWords.size
            loadWord(currentIndex)
        }

        btnNext.setOnClickListener {
            isRevealed = false
            currentIndex = (currentIndex + 1) % sortedWords.size
            loadWord(currentIndex)
        }

        btnClear.setOnClickListener {
            canvas.clear()
        }

        dialog.show()
    }

    override fun onDestroyView() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroyView()
    }
}
