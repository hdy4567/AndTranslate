package com.viitor.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational

/**
 * ★ PiPController: PIP 진입/종료 관리
 * - enterPiP(aspectRatio=Rational(16,9))
 */
class PiPController(private val activity: Activity) {

    fun enterPiP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val isSupported = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            if (isSupported) {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                activity.enterPictureInPictureMode(params)
            }
        }
    }
}
