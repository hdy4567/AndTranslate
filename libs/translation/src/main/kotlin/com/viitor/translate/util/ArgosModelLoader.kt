package com.viitor.translate.util

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object ArgosModelLoader {
    private const val TAG = "ArgosModelLoader"

    // @ysig_audit(ticket_id="TASK-201")
    fun extractModel(context: Context, assetFileName: String, targetFolderName: String): String? {
        val modelDir = File(context.getExternalFilesDir(null), targetFolderName)

        if (modelDir.exists() && modelDir.listFiles()?.isNotEmpty() == true) {
            Log.d(TAG, "Argos model already extracted: ${modelDir.absolutePath}")
            // 압축 해제는 완료됐으나 실제 model.bin 위치를 찾아야 함
            return findModelBinDir(modelDir)
        }

        Log.d(TAG, "Extracting Argos model from $assetFileName ...")

        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }

        return try {
            context.assets.open(assetFileName).use { inputStream ->
                ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val outFile = File(modelDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                zis.copyTo(fos, bufferSize = 16 * 1024)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            val modelBinDir = findModelBinDir(modelDir)
            if (modelBinDir != null) {
                Log.d(TAG, "Argos extraction complete. model.bin at: $modelBinDir")
                modelBinDir
            } else {
                Log.e(TAG, "Extraction done but model.bin not found in: ${modelDir.absolutePath}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract Argos model: ${e.message}", e)
            null
        }
    }

    /**
     * model.bin 파일이 있는 디렉토리를 재귀 탐색해 반환.
     * Argos 모델 구조: {root}/{lang_code}/model/model.bin
     */
    private fun findModelBinDir(root: File): String? {
        if (File(root, "model.bin").exists()) return root.absolutePath
        val children = root.listFiles() ?: return null
        for (child in children) {
            if (child.isDirectory) {
                val result = findModelBinDir(child)
                if (result != null) return result
            }
        }
        return null
    }

}
