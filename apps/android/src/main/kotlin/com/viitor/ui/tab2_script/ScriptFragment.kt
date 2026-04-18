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
    }
}
