package com.viitor.ui.tab2_script

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

import com.viitor.pipeline.core.SubtitleData

/**
 * 2탭 레거시 핵심 로직: Layer 1 (표준 정밀 추출)
 */
object SubtitleParserUtil {

    suspend fun parseTimedTextFormat(timedTextUrl: String): List<SubtitleData> = withContext(Dispatchers.IO) {
        val resultList = mutableListOf<SubtitleData>()
        try {
            val url = URL(timedTextUrl + "&fmt=json3") // JSON3 포맷 강제
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val inputStr = connection.inputStream.bufferedReader().use { it.readText() }
                
                // YouTube JSON3 Parsing
                val jsonResponse = JSONObject(inputStr)
                val eventsArray = jsonResponse.optJSONArray("events") ?: return@withContext emptyList()

                for (i in 0 until eventsArray.length()) {
                    val eventObj = eventsArray.getJSONObject(i)
                    val startTimeMs = eventObj.optLong("tStartMs", 0L)
                    
                    val segsArray = eventObj.optJSONArray("segs")
                    if (segsArray != null) {
                        val segBuilder = StringBuilder()
                        for (j in 0 until segsArray.length()) {
                            val segObj = segsArray.getJSONObject(j)
                            val textChunk = segObj.optString("utf8", "")
                            
                            // 레거시 노이즈 필터링 로직 (의미없는 음악 기호 등 삭제)
                            if (textChunk.isNotBlank() && textChunk != "\n" && !textChunk.contains("♪")) {
                                segBuilder.append(textChunk)
                            }
                        }
                        
                        val finalLine = segBuilder.toString().trim()
                        if (finalLine.isNotBlank()) {
                            // [PROFESSIONAL-PARSER] || 기호 포함 시 분절 처리 (군집 자막 해소)
                            val segments = if (finalLine.contains("||")) finalLine.split("||") else listOf(finalLine)
                            segments.forEachIndexed { index, s ->
                                val cleanS = s.trim()
                                if (cleanS.isNotBlank()) {
                                    resultList.add(SubtitleData(
                                        videoId = "UNKNOWN",
                                        anchorTimeMs = startTimeMs + (index * 100), // 미세 오프셋으로 순서 보장
                                        original = cleanS
                                    ))
                                }
                            }
                        }
                    }
                }
            } else {
                Log.e("SubtitleParserUtil", "Layer 1 Parsing Failed: HTTP ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Log.e("SubtitleParserUtil", "Layer 1 Error: Invalid URL or Format", e)
        }
        return@withContext resultList
    }
}
