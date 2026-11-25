package com.viitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.viitor.ui.tab1_browser.BrowserFragment
import com.viitor.ui.tab2_script.ScriptFragment
import com.viitor.ui.tab3_words.WordsFragment
import com.viitor.ui.tab4_lens.LensFragment
import com.viitor.ui.tab5_settings.SettingsFragment
import com.viitor.util.ResourceBroker

/**
 * App Shell: 4개의 탭을 통합 관리하는 컨트롤타워 메인 액티비티 (Aura Capsule V5)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 2000
    }

    private var activeFragment: Fragment? = null
    private lateinit var browserFragment: BrowserFragment
    private lateinit var scriptFragment: ScriptFragment
    private lateinit var wordsFragment: WordsFragment
    private lateinit var lensFragment: LensFragment
    private lateinit var settingsFragment: SettingsFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        checkAndRequestPermissions()
        setContentView(ResourceBroker.layoutMain)
        startCoreService()

        val bottomNav = findViewById<BottomNavigationView>(ResourceBroker.idBottomNav)

        // [STATE-RESTORE] 기존 프래그먼트가 있다면 찾아와서 연결 (인스턴스 중복 방지)
        browserFragment = (supportFragmentManager.findFragmentByTag("BROWSER") as? BrowserFragment) ?: BrowserFragment()
        scriptFragment = (supportFragmentManager.findFragmentByTag("SCRIPT") as? ScriptFragment) ?: ScriptFragment()
        wordsFragment = (supportFragmentManager.findFragmentByTag("WORDS") as? WordsFragment) ?: WordsFragment()
        lensFragment = (supportFragmentManager.findFragmentByTag("LENS") as? LensFragment) ?: LensFragment()
        settingsFragment = (supportFragmentManager.findFragmentByTag("SETTINGS") as? SettingsFragment) ?: SettingsFragment()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(ResourceBroker.idMainContainer, browserFragment, "BROWSER")
                .commit()
            activeFragment = browserFragment
        } else {
            val activeTag = savedInstanceState.getString("ACTIVE_TAG", "BROWSER")
            activeFragment = supportFragmentManager.findFragmentByTag(activeTag)
        }

        bottomNav.setOnItemSelectedListener { item ->
            val nextFragment = when (item.itemId) {
                ResourceBroker.navBrowser -> browserFragment
                ResourceBroker.navScript -> scriptFragment
                ResourceBroker.navWords -> wordsFragment
                ResourceBroker.navLens -> lensFragment
                ResourceBroker.navSettings -> settingsFragment
                else -> browserFragment
            }
            switchFragment(nextFragment)
            true
        }
    }

    override fun onBackPressed() {
        if (activeFragment is BrowserFragment) {
            val webView = activeFragment?.view?.findViewById<android.webkit.WebView>(ResourceBroker.idWebView)
            if (webView?.canGoBack() == true) {
                webView.goBack()
                return
            }
        }
        
        if (activeFragment != browserFragment) {
            navigateToTab(0)
            return
        }
        
        super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val tag = when (activeFragment) {
            is BrowserFragment -> "BROWSER"
            is ScriptFragment -> "SCRIPT"
            is WordsFragment -> "WORDS"
            is LensFragment -> "LENS"
            is SettingsFragment -> "SETTINGS"
            else -> "BROWSER"
        }
        outState.putString("ACTIVE_TAG", tag)
    }

    fun navigateToTab(index: Int) {
        val bottomNav = findViewById<BottomNavigationView>(ResourceBroker.idBottomNav)
        val menuId = when (index) {
            0 -> ResourceBroker.navBrowser
            1 -> ResourceBroker.navScript
            2 -> ResourceBroker.navWords
            3 -> ResourceBroker.navLens
            4 -> ResourceBroker.navSettings
            else -> ResourceBroker.navBrowser
        }
        bottomNav.selectedItemId = menuId
    }

    private fun switchFragment(nextFragment: Fragment) {
        if (activeFragment == nextFragment) return
        
        supportFragmentManager.beginTransaction().apply {
            activeFragment?.let { hide(it) }
            
            if (!nextFragment.isAdded) {
                val tag = when (nextFragment) {
                    is BrowserFragment -> "BROWSER"
                    is ScriptFragment -> "SCRIPT"
                    is WordsFragment -> "WORDS"
                    is LensFragment -> "LENS"
                    is SettingsFragment -> "SETTINGS"
                    else -> null
                }
                add(ResourceBroker.idMainContainer, nextFragment, tag)
            }
            
            show(nextFragment)
            commit()
        }
        activeFragment = nextFragment
    }

    private fun startCoreService() {
        val intent = Intent(this, com.viitor.service.SubtitleCaptureService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
        checkOverlayPermission()
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "오버레이 권한이 필요합니다.", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        }
    }
}
