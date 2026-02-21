package com.viitor.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * PermissionManager: 앱에 필요한 주요 권한 상태 체크 및 요청 유틸리티
 */
object PermissionManager {

    /**
     * 오버레이 권한 (다른 앱 위에 표시) 체크
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * 마이크 권한 체크
     */
    fun hasAudioPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 알림 권한 체크 (Android 13+)
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * PIP (Picture-in-Picture) 권한 체크
     */
    fun hasPipPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        } else {
            true
        }
    }

    /**
     * 모든 필수 권한 허용 여부
     */
    fun hasAllPermissions(context: Context): Boolean {
        // 필수 권한: 오버레이, 오디오
        // 알림과 PIP는 부가적인 기능이므로 필수에서 제외하거나 사용자 선택에 따름
        return hasOverlayPermission(context) && hasAudioPermission(context)
    }

    /**
     * 설정 화면으로 이동 (오버레이)
     */
    fun openOverlaySettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
    }

    /**
     * 설정 화면으로 이동 (PIP)
     */
    fun openPipSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                "android.settings.PICTURE_IN_PICTURE_SETTINGS",
                Uri.parse("package:${context.packageName}")
            )
            context.startActivity(intent)
        }
    }
}
