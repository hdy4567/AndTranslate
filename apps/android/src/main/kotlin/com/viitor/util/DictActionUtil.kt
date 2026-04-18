package com.viitor.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.launch

/**
 * ★ [DictActionUtil] (Legacy Reusable Logic)
 * - 저장: 3번 탭(단어장)으로 전송 (DB or ViewModel)
 * - 사전: 네이버 사전 웹 뷰 로드
 */
object DictActionUtil {
    
    private const val NAVER_DICT_URL = "https://ja.dict.naver.com/#/search?query="

    /** 🎯 네이버 사전으로 즉시 점프 */
    fun openNaverDict(context: Context, word: String) {
        try {
            val encodedWord = Uri.encode(word)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$NAVER_DICT_URL$encodedWord"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("DictActionUtil", "Naver Dict Load Failed", e)
        }
    }

    /** 💾 3번 탭(단어장) 저장 로직 (Global Sync) */
    fun saveToVault(context: Context, word: String, tras2layer: String? = null, origin1layer: String? = null, anchorTimeMs: Long = 0L, source: String = "Tab2_Script", videoId: String? = null) {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val db = com.viitor.data.local.ViitorLocalDatabase.getDatabase(context)
                val entity = com.viitor.data.local.VocabularyEntity(
                    word = word,
                    tras2layer = tras2layer,
                    origin1layer = origin1layer, 
                    anchorTimeMs = anchorTimeMs,
                    source = source,
                    videoId = videoId
                )
                db.vocabularyDao().insertWord(entity)
                Log.i("DictActionUtil", "Successfully saved to Database: $word | VideoId: $videoId")
            } catch (e: Exception) {
                Log.e("DictActionUtil", "Database Save Failed", e)
            }
        }
    }

}
