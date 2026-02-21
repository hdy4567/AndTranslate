package com.viitor.permission

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.viitor.R
import com.viitor.ui.MainActivity

/**
 * ★ PermissionOnboardingActivity: 3단계 권한 가이드 (마이크→오버레이→PIP)
 */
class PermissionOnboardingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_onboarding)
        
        android.widget.Toast.makeText(this, "필수 권한 설정이 필요합니다.", android.widget.Toast.LENGTH_SHORT).show()
        android.util.Log.d("Onboarding", "PermissionOnboardingActivity created")

        findViewById<MaterialButton>(R.id.btn_grant_audio).apply {
            isEnabled = !PermissionManager.hasAudioPermission(this@PermissionOnboardingActivity)
            setOnClickListener {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            }
        }

        findViewById<MaterialButton>(R.id.btn_grant_overlay).apply {
            isEnabled = !PermissionManager.hasOverlayPermission(this@PermissionOnboardingActivity)
            setOnClickListener {
                PermissionManager.openOverlaySettings(this@PermissionOnboardingActivity)
            }
        }

        findViewById<MaterialButton>(R.id.btn_grant_pip).apply {
            // PIP 권한 상태에 따라 버튼 활성화 제어 (선택 사항)
            isEnabled = !PermissionManager.hasPipPermission(this@PermissionOnboardingActivity)
            setOnClickListener {
                PermissionManager.openPipSettings(this@PermissionOnboardingActivity)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 상태 갱신
        updateButtonStates()
        
        // 필수 권한(마이크, 오버레이)이 모두 허용되었는지 확인
        if (PermissionManager.hasAllPermissions(this)) {
            // 약간의 딜레이 후 이동 (사용자가 허용됨을 인지할 수 있도록)
            findViewById<MaterialButton>(R.id.btn_grant_audio).postDelayed({
                if (!isFinishing) {
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }, 500)
        }
    }

    private fun updateButtonStates() {
        findViewById<MaterialButton>(R.id.btn_grant_audio).isEnabled = 
            !PermissionManager.hasAudioPermission(this)
        findViewById<MaterialButton>(R.id.btn_grant_overlay).isEnabled = 
            !PermissionManager.hasOverlayPermission(this)
        findViewById<MaterialButton>(R.id.btn_grant_pip).isEnabled = 
            !PermissionManager.hasPipPermission(this)
    }

    private fun showPermissionDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("확인", null)
            .show()
    }
}
