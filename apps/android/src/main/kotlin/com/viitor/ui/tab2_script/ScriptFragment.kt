package com.viitor.ui.tab2_script

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.Toast
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.viitor.MainActivity
import com.viitor.ui.SharedSyncViewModel
import com.viitor.util.ResourceBroker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ScriptFragment : Fragment() {

    private val sharedViewModel: SharedSyncViewModel by activityViewModels()
    
    private lateinit var rvScript: RecyclerView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvEmptyInfo: View
    private lateinit var adapter: ScriptAdapter

    private var scriptFetchJob: Job? = null
    
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(ResourceBroker.layoutScript, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        rvScript = view.findViewById(ResourceBroker.idScriptList)
        pbLoading = view.findViewById(ResourceBroker.idScriptLoading)
        tvEmptyInfo = view.findViewById(ResourceBroker.idScriptEmpty)

        adapter = ScriptAdapter(
            onLineClicked = { anchorTimeMs ->
                sharedViewModel.commandJumpToTime(anchorTimeMs)
                Toast.makeText(requireContext(), "영상 시간을 동기화합니다.", Toast.LENGTH_SHORT).show()
            },
            onLineLongClicked = { line ->
                showAbLoopDialog(line)
            },
            onWarpToVocabContext = { videoId, timeMs ->
                sharedViewModel.warpToWord(videoId, timeMs)
                (activity as? MainActivity)?.navigateToTab(0)
                Toast.makeText(requireContext(), "단어 저장 문맥으로 이동합니다.", Toast.LENGTH_SHORT).show()
            }
        )

        rvScript.layoutManager = LinearLayoutManager(requireContext())
        rvScript.adapter = adapter

        // [COLOR-SYNC] 1탭 오버레이에서 변경된 색상을 실시간으로 대본에 반영
        com.viitor.pipeline.core.GlobalSubtitleRelay.onColorChanged = { _ ->
            viewLifecycleOwner.lifecycleScope.launch {
                adapter.notifyDataSetChanged()
            }
        }

        observeSharedStreams()
    }

    private fun observeSharedStreams() {
        // [SSOT-REACTIVE-SYNC] 1탭 브라우저에서 가로챈 대본이 레지스트리에 적재되면 즉시 반영
        viewLifecycleOwner.lifecycleScope.launch {
            com.viitor.pipeline.core.GlobalSubtitleRelay.transcriptFlow.collectLatest { lines ->
                if (lines.isNotEmpty()) {
                    pbLoading.visibility = View.GONE
                    tvEmptyInfo.visibility = View.GONE
                    adapter.submitList(lines)
                    Log.d("ScriptFragment", "[V5-SYNC] SSOT Transcript Applied to UI: ${lines.size} lines.")
                } else {
                    tvEmptyInfo.visibility = View.VISIBLE
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            sharedViewModel.currentVideoId.collectLatest { videoId ->
                if (videoId.isNotBlank()) {
                    adapter.currentVideoId = videoId
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            sharedViewModel.liveScriptList.collectLatest { liveList ->
                if (liveList.isNotEmpty()) {
                    tvEmptyInfo.visibility = View.GONE
                    adapter.submitList(liveList)
                    rvScript.scrollToPosition(liveList.size - 1)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            sharedViewModel.currentPlayTimeMs.collectLatest { timeMs ->
                adapter.updateSyncTime(timeMs, rvScript.layoutManager as LinearLayoutManager)
            }
        }

        // [AB-LOOP-SYNC] 루프 상태 변화 시 대본 하이라이트 즉각 갱신
        viewLifecycleOwner.lifecycleScope.launch {
            com.viitor.pipeline.core.GlobalSubtitleRelay.loopConfig.collectLatest {
                adapter.notifyDataSetChanged()
            }
        }

        // [VOCAB-HIGHLIGHT-SYNC] 단어장 변경 시 대본 내 단어 형광 하이라이트 실시간 갱신
        viewLifecycleOwner.lifecycleScope.launch {
            sharedViewModel.savedWordsList.collectLatest { list ->
                adapter.setSavedWords(list)
            }
        }
    }

    private fun showAbLoopDialog(selectedLine: com.viitor.pipeline.core.SubtitleData) {
        val currentLoop = com.viitor.pipeline.core.GlobalSubtitleRelay.loopConfig.value
        val itemsList = mutableListOf<String>()
        itemsList.add("🔁 이 문장 구간 반복 (A-B 루프)")
        if (currentLoop.enabled) {
            val pauseStateStr = if (currentLoop.shadowPauseEnabled) "ON (1.5초)" else "OFF"
            itemsList.add("⏸ 따라 말하기 섀도잉 대기: $pauseStateStr (토글)")
            itemsList.add("❌ 구간 반복 해제")
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext(), com.google.android.material.R.style.Theme_Material3_Dark_Dialog_Alert)
            .setTitle("섀도잉 A-B 구간 반복 설정")
            .setItems(itemsList.toTypedArray()) { _, which ->
                val selectedItem = itemsList[which]
                when {
                    selectedItem.startsWith("🔁") -> {
                        val currentList = adapter.getItems()
                        val idx = currentList.indexOfFirst { it.anchorTimeMs == selectedLine.anchorTimeMs }
                        val nextTime = if (idx != -1 && idx + 1 < currentList.size) {
                            currentList[idx + 1].anchorTimeMs
                        } else {
                            selectedLine.anchorTimeMs + 4000L
                        }
                        sharedViewModel.setAbLoop(selectedLine.anchorTimeMs, nextTime)
                        adapter.notifyDataSetChanged()
                        Toast.makeText(requireContext(), "🔁 A-B 구간 반복 활성화: ${selectedLine.origin1layer.take(15)}...", Toast.LENGTH_SHORT).show()
                    }
                    selectedItem.startsWith("⏸") -> {
                        sharedViewModel.toggleShadowPause()
                        val newPause = com.viitor.pipeline.core.GlobalSubtitleRelay.loopConfig.value.shadowPauseEnabled
                        Toast.makeText(requireContext(), "섀도잉 따라말하기 대기: ${if (newPause) "ON (1.5초)" else "OFF"}", Toast.LENGTH_SHORT).show()
                    }
                    selectedItem.startsWith("❌") -> {
                        sharedViewModel.clearAbLoop()
                        adapter.notifyDataSetChanged()
                        Toast.makeText(requireContext(), "A-B 구간 반복이 해제되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }
}
