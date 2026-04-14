package com.viitor.ui.tab2_script

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

        adapter = ScriptAdapter { anchorTimeMs ->
            sharedViewModel.commandJumpToTime(anchorTimeMs)
            Toast.makeText(requireContext(), "영상 시간을 동기화합니다.", Toast.LENGTH_SHORT).show()
        }

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
        viewLifecycleOwner.lifecycleScope.launch {
            sharedViewModel.currentVideoId.collectLatest { videoId ->
                if (videoId.isNotBlank()) {
                    adapter.currentVideoId = videoId // [PLATINUM-SYNC] 어댑터에 현재 영상 ID 주입
                    fetchAndDrawScript(videoId)
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
    }

    private fun fetchAndDrawScript(videoId: String) {
        scriptFetchJob?.cancel()
        pbLoading.visibility = View.VISIBLE
        
        scriptFetchJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val lines = WideSubtitleAgent.aggressiveExtract(videoId)
                if (lines.isNotEmpty()) {
                    // [SSOT-FUSION] 가로챈 7000자 대본을 시스템 전역 레지스트리에 공유 (1탭 오버레이 즉시 대응용)
                    com.viitor.pipeline.core.GlobalSubtitleRelay.updateTranscript(lines)
                    
                    tvEmptyInfo.visibility = View.GONE
                    adapter.submitList(lines)
                } else {
                    tvEmptyInfo.visibility = View.VISIBLE
                }
            } finally {
                pbLoading.visibility = View.GONE
            }
        }
    }
}
