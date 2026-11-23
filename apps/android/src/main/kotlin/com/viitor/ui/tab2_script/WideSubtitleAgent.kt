package com.viitor.ui.tab2_script

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.regex.Pattern
import com.viitor.pipeline.core.SubtitleData

/**
 * 2탭 레거시 파괴적 로직: Layer 2 (광범위 공격 탐색)
 * - Layer 1이 실패할 경우, HTML 원문에서 정규식(Regex)을 이용해 비정형 구조라도 끝까지 추격해 긁어옴
 */
object WideSubtitleAgent {

    suspend fun aggressiveExtract(videoId: String): List<SubtitleData> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://www.youtube.com/watch?v=$videoId")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0") 
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val htmlContent = connection.inputStream.bufferedReader().use { it.readText() }
                
                // [JAPANESE-HUNT] ja(일본어) 트랙 우선 추출 및 데이터 테이블 로그캣 반영
                val captionRegex = "\"captionTracks\":(\\[.*?\\])".toRegex()
                val matchResult = captionRegex.find(htmlContent)
                
                if (matchResult != null) {
                    val tracksJson = matchResult.groupValues[1]
                    Log.d("WideSubtitleAgent", "[STREAM] Layer 2 Captured Caption Tracks Table.")
                    
                    val jaTrackRegex = "\\{\"baseUrl\":\"(.*?)\".*?\"languageCode\":\"ja\"".toRegex()
                    val jaMatch = jaTrackRegex.find(tracksJson) ?: matchResult 

                    val rawUrl = jaMatch.groupValues[1]
                    val cleanUrl = URLDecoder.decode(rawUrl.replace("\\u0026", "&"), "UTF-8")
                    
                    Log.i("WideSubtitleAgent", "[STREAM] Success: Japanese (ja) Track Targeted -> $cleanUrl")
                    return@withContext SubtitleParserUtil.parseTimedTextFormat(cleanUrl)
                } else {
                    Log.w("WideSubtitleAgent", "[STREAM] Layer 2 Failed: No Caption Table Found.")
                }
            }
        } catch (e: Exception) {
            Log.e("WideSubtitleAgent", "Layer 2 Critical Failure", e)
        }
        
        // 최종 실패 시 Empty Return (크래시 방지 규칙)
        return@withContext emptyList()
    }
}
