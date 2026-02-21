package com.viitor.stt.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * ★ VoskModelLoader: assets/ 내 폴더를 외부 저장소로 복사
 * language: "ja" → vosk-model-small-ja-0.22
 *           "ko" → vosk-model-small-ko-0.22
 */
object VoskModelLoader {

    private const val TAG = "VoskModelLoader"

    private fun modelName(language: String): String = when (language) {
        "ja" -> "vosk-model-small-ja-0.22"
        else -> "vosk-model-small-ko-0.22"
    }

    /**
     * 모델 경로를 반환. 없으면 assets에서 복사 후 반환.
     */
    fun loadModel(context: Context, language: String = "ko"): String? {
        val name = modelName(language)
        val modelDir = File(context.getExternalFilesDir(null), name)

        if (modelDir.exists() && modelDir.list()?.isNotEmpty() == true) {
            Log.d(TAG, "Model already extracted [$language]: ${modelDir.absolutePath}")
            return modelDir.absolutePath
        }

        // assets 에 해당 언어 모델이 없을 경우 fallback: ko
        val assetList = context.assets.list("") ?: emptyArray()
        val assetDir = if (assetList.contains(name)) name else modelName("ko")
        Log.d(TAG, "Extracting model from assets/$assetDir ...")

        return try {
            copyAssetFolder(context, assetDir, modelDir)
            if (modelDir.exists() && modelDir.list()?.isNotEmpty() == true) {
                Log.d(TAG, "Extraction complete: ${modelDir.absolutePath}")
                modelDir.absolutePath
            } else {
                Log.e(TAG, "Extraction succeeded but directory is empty")
                null
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to extract model: ${e.message}", e)
            null
        }
    }

    private fun copyAssetFolder(context: Context, assetPath: String, destDir: File) {
        if (!destDir.exists()) destDir.mkdirs()

        val children = context.assets.list(assetPath)
            ?: throw IOException("assets.list() returned null for: $assetPath")

        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val childDest = File(destDir, child)
            val subChildren = context.assets.list(childAssetPath)
            if (!subChildren.isNullOrEmpty()) {
                copyAssetFolder(context, childAssetPath, childDest)
            } else {
                context.assets.open(childAssetPath).use { input ->
                    FileOutputStream(childDest).use { output ->
                        input.copyTo(output, bufferSize = 16 * 1024)
                    }
                }
                Log.v(TAG, "Copied: $childAssetPath")
            }
        }
    }
}

