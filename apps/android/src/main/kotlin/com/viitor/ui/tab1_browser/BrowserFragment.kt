package com.viitor.ui.tab1_browser

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.viitor.hijack.HijackDispatcher
import com.viitor.hijack.YoutubeLiveHijackEngine
import com.viitor.pipeline.core.GlobalSubtitleRelay
import com.viitor.ui.SharedSyncViewModel
import com.viitor.ui.tab1_browser.bridge.YoutubeJsBridgeImpl
import com.viitor.ui.tab1_browser.memory.IMemoryManager
import com.viitor.ui.tab1_browser.memory.Tab1MemoryManager
import com.viitor.util.ResourceBroker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BrowserFragment : Fragment() {

    private lateinit var viewModel: YoutubeViewModel
    private lateinit var memoryManager: IMemoryManager
    private var webView: WebView? = null
    private var isOverlayActive = true
    private var shutdownTimerJob: Job? = null
    private var isShadowPausing = false

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[YoutubeViewModel::class.java]

        GlobalSubtitleRelay.onRepairRequested = {
            lifecycleScope.launch { injectHijackScript() }
        }
        GlobalSubtitleRelay.onTogglePlayback = {
            lifecycleScope.launch {
                webView?.evaluateJavascript("if(window.andTransShadow) window.andTransShadow.toggle(); else { var v=document.querySelector('video'); if(v) { if(v.paused) v.play(); else v.pause(); } }", null)
            }
        }
        memoryManager = Tab1MemoryManager(onDestroyCallback = {})
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val root = inflater.inflate(ResourceBroker.layoutBrowser, container, false)
        webView = root.findViewById(ResourceBroker.idWebView)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupWebView()
        setupOverlayToggleBtn(view)
        observeViewModel()
        memoryManager.startIdleTimer()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView?.let {
            it.settings.javaScriptEnabled = true
            it.settings.cacheMode = WebSettings.LOAD_DEFAULT
            it.settings.domStorageEnabled = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.settings.forceDark = WebSettings.FORCE_DARK_ON
            }
            it.addJavascriptInterface(YoutubeJsBridgeImpl(viewModel), "AndTransBridge")
            it.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectHijackScript()
                }
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    injectHijackScript()
                }
            }
            it.webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (customView != null) { callback?.onCustomViewHidden(); return }
                    customView = view
                    customViewCallback = callback
                    val root = activity?.window?.decorView as? FrameLayout
                    root?.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    webView?.visibility = View.GONE
                }
                override fun onHideCustomView() {
                    val root = activity?.window?.decorView as? FrameLayout
                    root?.removeView(customView)
                    customView = null
                    customViewCallback?.onCustomViewHidden()
                    webView?.visibility = View.VISIBLE
                }
            }
            it.loadUrl("https://www.youtube.com")
        }
    }

    private fun injectHijackScript() {
        val currentUrl = webView?.url
        val engine = HijackDispatcher.resolve(currentUrl) ?: return
        val script = if (engine is YoutubeLiveHijackEngine) {
            "(function(){ function t(){ if(window.AndTransBridge){ ${engine.injectScript} } else { setTimeout(t,500); } } t(); })();"
        } else engine.injectScript
        webView?.evaluateJavascript(script, null)
    }

    private fun setupOverlayToggleBtn(view: View) {
        val toggleBtn = view.findViewById<FloatingActionButton>(ResourceBroker.idOverlayToggle)
        toggleBtn?.setOnClickListener {
            val serviceIntent = Intent(requireContext(), com.viitor.service.SubtitleCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) requireContext().startForegroundService(serviceIntent)
            else requireContext().startService(serviceIntent)
            Toast.makeText(requireContext(), "오버레이 자막창 부활", Toast.LENGTH_SHORT).show()
        }
        toggleBtn?.setOnLongClickListener {
            isOverlayActive = !isOverlayActive
            shutdownTimerJob?.cancel()
            val serviceIntent = Intent(requireContext(), com.viitor.service.SubtitleCaptureService::class.java)
            if (isOverlayActive) {
                viewModel.reviveEngine()
                injectHijackScript()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) requireContext().startForegroundService(serviceIntent)
                else requireContext().startService(serviceIntent)
            } else {
                requireContext().stopService(serviceIntent)
                shutdownTimerJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(5000)
                    if (!isOverlayActive) executeFullShutdown()
                }
            }
            true
        }
    }

    private fun executeFullShutdown() {
        requireContext().stopService(Intent(requireContext(), com.viitor.service.SubtitleCaptureService::class.java))
        HijackDispatcher.resolve(webView?.url)?.let { webView?.evaluateJavascript(it.shutdownScript, null) }
        viewModel.shutdownEngine()
    }

    private fun observeViewModel() {
        val sharedViewModel: SharedSyncViewModel by activityViewModels()
        viewModel.onVideoDetected = { videoId -> sharedViewModel.broadcastVideoId(videoId) }
        viewModel.onLiveFallbackListener = {
            lifecycleScope.launch { webView?.evaluateJavascript(YoutubeLiveHijackEngine.injectScript, null) }
        }

        // [AB-LOOP & SHADOW-PAUSE]
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentTimeMs.collectLatest { timeMs ->
                sharedViewModel.broadcastRealtimeSync(timeMs)
                val loop = GlobalSubtitleRelay.loopConfig.value
                if (loop.enabled && timeMs >= loop.endMs && !isShadowPausing) {
                    val startSec = loop.startMs / 1000.0
                    if (loop.shadowPauseEnabled) {
                        isShadowPausing = true
                        webView?.evaluateJavascript("var v=document.querySelector('video'); if(v) v.pause();", null)
                        delay(loop.shadowPauseMs)
                        webView?.evaluateJavascript("if(window.andTransShadow) window.andTransShadow.seekTo(${loop.startMs}); else { var v=document.querySelector('video'); if(v) v.currentTime = $startSec; }", null)
                        isShadowPausing = false
                    } else {
                        webView?.evaluateJavascript("if(window.andTransShadow) window.andTransShadow.seekTo(${loop.startMs}); else { var v=document.querySelector('video'); if(v) v.currentTime = $startSec; }", null)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.overlayState.collectLatest { model ->
                if (model.payload.origin1layer.isBlank()) return@collectLatest
                sharedViewModel.appendScript(
                    com.viitor.pipeline.core.SubtitleData(
                        origin1layer = model.payload.origin1layer,
                        tras2layer = model.tras2layer,
                        pron3layer = model.pron3layer,
                        anchorTimeMs = model.payload.anchorTimeMs
                    )
                )
            }
        }

        // [WARP-JUMP-ROBUST] JS 브릿지 및 HTML5 video.currentTime 듀얼 점프
        viewLifecycleOwner.lifecycleScope.launch {
            sharedViewModel.jumpCommandEvent.collectLatest { targetTimeMs ->
                Log.i("BrowserFragment", "[WARP] Jumping to $targetTimeMs ms")
                val sec = targetTimeMs / 1000.0
                val script = """
                    if (window.andTransShadow) {
                        window.andTransShadow.seekTo($targetTimeMs);
                    } else {
                        var v = document.querySelector('video');
                        if (v) v.currentTime = $sec;
                    }
                """.trimIndent()
                webView?.evaluateJavascript(script, null)
            }
        }
    }
}
