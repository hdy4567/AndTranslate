package com.viitor.ui

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.viitor.databinding.ActivityMainBinding
import com.viitor.permission.PermissionManager
import com.viitor.permission.PermissionOnboardingActivity
import com.viitor.service.SubtitleCaptureService

/**
 * ★ MainActivity: 진입점: FAB 토글 + 권한 체크
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startSubtitleService(result.resultCode, result.data!!)
            updateServiceState(true)
        }
    }

    private var isServiceRunning = false

    private val subtitleReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.viitor.ACTION_UPDATE_SUBTITLE") {
                val originalBy = intent.getStringExtra("original") ?: ""
                val translatedBy = intent.getStringExtra("translated") ?: ""
                
                binding.tvPipSubtitle.text = originalBy
                binding.tvPipTranslated.text = translatedBy
                binding.tvStatus.text = "인식 중: $originalBy"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Broadcast Receiver 등록
        val filter = android.content.IntentFilter("com.viitor.ACTION_UPDATE_SUBTITLE")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            registerReceiver(subtitleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(subtitleReceiver, filter)
        }
        
        if (!PermissionManager.hasAllPermissions(this)) {
            android.util.Log.d("MainActivity", "Permissions missing, going to onboarding...")
            startActivity(Intent(this, com.viitor.permission.PermissionOnboardingActivity::class.java))
            finish()
            return
        }
        
        binding.fabToggle.setOnClickListener {
            android.util.Log.d("MainActivity", "FAB clicked. isServiceRunning=$isServiceRunning")
            android.widget.Toast.makeText(this, "캡처 요청 시도...", android.widget.Toast.LENGTH_SHORT).show()
            
            if (isServiceRunning) {
                stopSubtitleService()
            } else {
                requestMediaProjection() // 권한 요청
            }
        }
        
        checkServiceStatus()
    }
    
    private fun checkServiceStatus() {
        if (isMyServiceRunning(SubtitleCaptureService::class.java)) {
             updateServiceState(true)
             // 이미 실행 중이면 PIP 모드 힌트 또는 상태 복구
        }
    }

    @Suppress("DEPRECATION")
    private fun isMyServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(subtitleReceiver)
        } catch (e: Exception) {}
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            binding.groupMainUi.visibility = android.view.View.GONE
            binding.containerPipUi.visibility = android.view.View.VISIBLE
        } else {
            binding.groupMainUi.visibility = android.view.View.VISIBLE
            binding.containerPipUi.visibility = android.view.View.GONE
        }
    }

    private fun updateServiceState(running: Boolean) {
        isServiceRunning = running
        if (running) {
            binding.fabToggle.text = "캡처 중지"
            binding.fabToggle.setIconResource(android.R.drawable.ic_media_pause)
            binding.tvStatus.text = "자막 캡처 중..."
            binding.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            binding.fabToggle.text = "캡처 시작"
            binding.fabToggle.setIconResource(android.R.drawable.ic_media_play)
            binding.tvStatus.text = "서비스 대기 중"
            binding.tvStatus.setTextColor(getColor(android.R.color.black))
        }
    }

    private fun stopSubtitleService() {
        stopService(Intent(this, SubtitleCaptureService::class.java))
        updateServiceState(false)
    }

    private fun requestMediaProjection() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startSubtitleService(resultCode: Int, data: Intent) {
        val intent = Intent(this, SubtitleCaptureService::class.java).apply {
            putExtra("RESULT_CODE", resultCode)
            putExtra("RESULT_DATA", data)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
            
            // PIP 모드 진입 (약간의 지연 후)
            binding.root.postDelayed({
                try {
                    val params = android.app.PictureInPictureParams.Builder()
                        .setAspectRatio(android.util.Rational(16, 9))
                        .build()
                    enterPictureInPictureMode(params)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, 500)
        } else {
            startService(intent)
        }
    }

}
