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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.viitor.ui.SharedSyncViewModel
import com.viitor.ui.tab1_browser.bridge.YoutubeJsBridgeImpl
import com.viitor.ui.tab1_browser.domain.OverlayModel
import com.viitor.hijack.HijackDispatcher
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

    // 5초 셧다운 데스 타이머 예약 객체 (Side Bubble 또는 전역 셧다운 발생시 사용)
    private var shutdownTimerJob: Job? = null
    
    // [FULLSCREEN-STABILIZATION] 전체화면 대응용 뷰 핸들러
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[YoutubeViewModel::class.java]
        
        // [REPAIR-ENGINE] 오버레이의 수리 요청을 수신하여 JS 엔진 재주입 실행
        com.viitor.pipeline.core.GlobalSubtitleRelay.onRepairRequested = {
            lifecycleScope.launch {
                Log.i("BrowserFragment", "[REPAIR-TRIGGER] Injecting hijack script due to overlay signal")
                injectHijackScript()
            }
        }

        // [REMOTE-PLAYBACK] 오버레이의 재생/정지 버튼 연동 (⏯)
        com.viitor.pipeline.core.GlobalSubtitleRelay.onTogglePlayback = {
            lifecycleScope.launch {
                Log.i("BrowserFragment", "[REMOTE-PLAYBACK] Toggling video state")
                webView?.evaluateJavascript("if(window.andTransShadow) window.andTransShadow.toggle();", null)
            }
        }

        memoryManager = Tab1MemoryManager(
            onDestroyCallback = {
                Log.d("BrowserFragment", "Tab1 memory manager trigger onDestroyCallback")
            }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val root = inflater.inflate(ResourceBroker.layoutBrowser, container, false)
        bindViews(root)
        return root
    }

    private fun bindViews(view: View) {
        webView = view.findViewById(ResourceBroker.idWebView)
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

            // [NATIVE-DARK-MODE] 구글/유튜브 다크모드 강제 적용 (Force Dark)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                it.settings.forceDark = WebSettings.FORCE_DARK_ON
            }
            
            val jsBridge = YoutubeJsBridgeImpl(viewModel)
            it.addJavascriptInterface(jsBridge, "AndTransBridge")

            it.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectHijackScript()
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    // [SPA-HYDRATION] URL 변화(pushState) 감지하여 엔진 재주입
                    Log.d("BrowserFragment", "[SPA-SYNC] URL Change detected to: $url")
                    injectHijackScript()
                }
            }

            // [FULLSCREEN-STABILIZATION] 전체화면 대응을 위한 WebChromeClient 적용
            it.webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    if (customView != null) {
                        callback?.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customViewCallback = callback

                    val root = activity?.window?.decorView as? FrameLayout
                    root?.addView(view, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ))
                    webView?.visibility = View.GONE
                    
                    // [IMMERSIVE-MODE] 유저의 피드백을 반영하여 정교한 시스템 UI 상수 적용
                    activity?.window?.decorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN 
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION 
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                }

                override fun onHideCustomView() {
                    val root = activity?.window?.decorView as? FrameLayout
                    root?.removeView(customView)
                    customView = null
                    customViewCallback?.onCustomViewHidden()
                    
                    webView?.visibility = View.VISIBLE
                    // 시스템 UI 복구
                    activity?.window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                }
            }

            it.loadUrl("https://www.youtube.com")
        }
    }

    private fun injectHijackScript() {
        val currentUrl = webView?.url
        val engine = HijackDispatcher.resolve(currentUrl)
        if (engine == null) {
            Log.w("BrowserFragment", "[2.JS-HIJACK] No engine for URL=$currentUrl, skipping inject.")
            return
        }
        Log.i("BrowserFragment", "[2.JS-HIJACK] Injecting ${engine::class.simpleName} for URL=$currentUrl")
        webView?.evaluateJavascript(engine.injectScript, null)
    }

    private fun setupOverlayToggleBtn(view: View) {
        val toggleBtn = view.findViewById<FloatingActionButton>(ResourceBroker.idOverlayToggle)

        // [SINGLE-CLICK] 오버레이 자막창 즉시 부활/노출
        toggleBtn?.setOnClickListener {
            val serviceIntent = Intent(requireContext(), com.viitor.service.SubtitleCaptureService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(serviceIntent)
            } else {
                requireContext().startService(serviceIntent)
            }
            Toast.makeText(requireContext(), "오버레이 자막창 부활", Toast.LENGTH_SHORT).show()
        }

        // [LONG-CLICK] 엔진 셧다운/재점화 (기존 로직 유지)
        toggleBtn?.setOnLongClickListener {
            isOverlayActive = !isOverlayActive
            shutdownTimerJob?.cancel()

            val serviceIntent = Intent(requireContext(), com.viitor.service.SubtitleCaptureService::class.java)

            if (isOverlayActive) {
                // 부활 시퀀스: 엔진 재점화 및 서비스 기동
                viewModel.reviveEngine()
                injectHijackScript()
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    requireContext().startForegroundService(serviceIntent)
                } else {
                    requireContext().startService(serviceIntent)
                }
                
                Toast.makeText(requireContext(), "실시간 오버레이 엔진 재점화", Toast.LENGTH_SHORT).show()
            } else {
                // 종료 시퀀스: 서비스 중단 및 5초 후 완전 셧다운 예약
                requireContext().stopService(serviceIntent)
                Toast.makeText(requireContext(), "오버레이 엔진 셧다운 예약", Toast.LENGTH_SHORT).show()
                
                shutdownTimerJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(5000)
                    if (!isOverlayActive) {
                        executeFullShutdown()
                    }
                }
            }
            true
        }
    }

    private fun executeFullShutdown() {
        val serviceIntent = Intent(requireContext(), com.viitor.service.SubtitleCaptureService::class.java)
        requireContext().stopService(serviceIntent)

        val engine = HijackDispatcher.resolve(webView?.url)
        engine?.let { webView?.evaluateJavascript(it.shutdownScript, null) }
        viewModel.shutdownEngine()
        Toast.makeText(requireContext(), "엔진 완전 셧다운 완료", Toast.LENGTH_LONG).show()
    }

    private fun observeViewModel() {
        val sharedViewModel: SharedSyncViewModel by activityViewModels() // [CROSS-TAB]
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.overlayState.collectLatest { model ->
                if (model.payload.originJpText.isBlank()) return@collectLatest
                
                // [SCRIPT-ACCRUAL] 번역된 데이터를 공유 뷰모델 리스트에 실시간 누적 (2탭 동화)
                sharedViewModel.appendScript(
                    com.viitor.pipeline.core.SubtitleData(
                        original = model.payload.originJpText,
                        translated = model.translateKo,
                        pronunciation = model.pronunciationKo,
                        anchorTimeMs = model.payload.anchorTimeMs // 보정된 시간 전달
                    )
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            sharedViewModel.jumpCommandEvent.collectLatest { targetTimeMs ->
                Log.i("BrowserFragment", "[WARP] Jumping to $targetTimeMs ms")
                webView?.evaluateJavascript("if(window.andTransShadow) window.andTransShadow.seekTo($targetTimeMs);", null)
            }
        }
    }
}
