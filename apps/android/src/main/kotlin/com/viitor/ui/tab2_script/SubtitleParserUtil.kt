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
        try {
            val url = URL(timedTextUrl + "&fmt=json3") // JSON3 포맷 강제
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val inputStr = connection.inputStream.bufferedReader().use { it.readText() }
                return@withContext parseJson3Content(inputStr)
            }
        } catch (e: Exception) {
            Log.e("SubtitleParserUtil", "Layer 1 Error: Invalid URL or Format", e)
        }
        return@withContext emptyList()
    }

    /** [CORE-PARSER] JS 엔진(Bulk Fetch) 또는 네트워크에서 온 JSON3 데이터를 공통 파싱 */
    fun parseJson3Content(jsonStr: String): List<SubtitleData> {
        val resultList = mutableListOf<SubtitleData>()
        try {
            val jsonResponse = JSONObject(jsonStr)
            val eventsArray = jsonResponse.optJSONArray("events") ?: return emptyList()

            for (i in 0 until eventsArray.length()) {
                val eventObj = eventsArray.getJSONObject(i)
                val startTimeMs = eventObj.optLong("tStartMs", 0L)
                val segsArray = eventObj.optJSONArray("segs")

                if (segsArray != null) {
                    val segBuilder = StringBuilder()
                    for (j in 0 until segsArray.length()) {
                        val segObj = segsArray.getJSONObject(j)
                        val textChunk = segObj.optString("utf8", "")
                        
                        // [V5-LINE-RECOVERY] \n 기호를 || 구분자로 치환하여 줄바꿈 유지
                        if (textChunk == "\n") {
                            segBuilder.append(" || ")
                        } else if (textChunk.isNotBlank() && !textChunk.contains("♪")) {
                            segBuilder.append(textChunk)
                        }
                    }
                    
                    val finalLine = segBuilder.toString().trim()
                    if (finalLine.isNotBlank()) {
                        // || 기호를 기준으로 실제 개별 자막(1레이어 단위)으로 분절
                        val segments = if (finalLine.contains("||")) finalLine.split("||") else listOf(finalLine)
                        segments.forEachIndexed { index, s ->
                            val cleanS = s.trim()
                            if (cleanS.isNotBlank()) {
                                resultList.add(SubtitleData(
                                    videoId = "BULK_FETCH",
                                    anchorTimeMs = startTimeMs + (index * 50),
                                    origin1layer = cleanS
                                ))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SubtitleParserUtil", "JSON3 Parse Error", e)
        }
        return resultList
    }
}
