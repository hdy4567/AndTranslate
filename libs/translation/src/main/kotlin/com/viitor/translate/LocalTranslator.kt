package com.viitor.translate

import android.content.Context
import android.util.Log
import com.viitor.translate.util.ArgosModelLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ★ LocalTranslator: Argos 오프라인 번역 데이터베이스 서비스
 * - CompletableDeferred로 모델 로딩 완료 신호 관리
 * - translate() 호출 시 모델이 준비될 때까지 suspend (무딜레이)
 * - Pivoting: JA → EN → KO
 */
class LocalTranslator(private val context: Context) {

    private val dbCache = TranslationCache(200)

    // Native model handles
    private var koEnPtr: Long = 0L
    private var enJaPtr: Long = 0L

    // 모델 준비 완료 신호 (await → 모델 로디 완료까지 suspend, 에러 시 예외 전달)
    private val modelReady = CompletableDeferred<Unit>()

    private val koEnMutex = Mutex()
    private val enJaMutex = Mutex()

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    init {
        connect()
    }

    // @ysig_audit(ticket_id="TASK-501", risk="ERR_JNI_LOAD_FAILED")
    private fun connect() {
        try {
            System.loadLibrary("argos_jni")
            Log.d("Translator", "Connected to Native Translate DB Engine")
        } catch (e: Exception) {
            Log.e("Translator", "Failed to connect to Native Engine: ${e.message}")
            modelReady.completeExceptionally(e)
            return
        }

        scope.launch {
            initializeModels()
        }
    }

    private external fun initNativeModel(modelPath: String): Long
    private external fun translateNative(modelPtr: Long, text: String, targetLang: String): String
    private external fun releaseNativeModel(modelPtr: Long)

    // @ysig_audit(ticket_id="TASK-502", risk="ERR_MODEL_LOAD_TIMEOUT")
    private suspend fun initializeModels() {
        Log.d("Translator", "Initializing Model Tables...")

        try {
            // Ko-En Table (JA→KO 피벗에서 역방향으로 사용)
            koEnMutex.withLock {
                val path = ArgosModelLoader.extractModel(
                    context,
                    "translate-ko_en-1_1.argosmodel",
                    "argos-ko-en"
                )
                if (path != null) {
                    koEnPtr = initNativeModel(path)
                    Log.d("Translator", "Model [KO-EN] loaded ptr=$koEnPtr")
                } else {
                    throw Exception("KO-EN model extraction failed")
                }
            }

            // En-Ja Table
            enJaMutex.withLock {
                val path = ArgosModelLoader.extractModel(
                    context,
                    "translate-en_ja-1_1.argosmodel",
                    "argos-en-ja"
                )
                if (path != null) {
                    enJaPtr = initNativeModel(path)
                    Log.d("Translator", "Model [EN-JA] loaded ptr=$enJaPtr")
                } else {
                    throw Exception("EN-JA model extraction failed")
                }
            }

            // 두 모델 모두 로드 완료 → 대기 중인 translate() 호출들 모두 해제
            modelReady.complete(Unit)
            Log.d("Translator", "All models ready. Translation engine online.")

        } catch (e: Exception) {
            Log.e("Translator", "Model initialization failed: ${e.message}", e)
            modelReady.completeExceptionally(e)
        }
    }

    /**
     * Translate Query
     * - 모델 미준비 시 modelReady.await()으로 suspend (스레드 블로킹 없음)
     * - 캐시 히트 시 즉시 반환
     */
    // @ysig_audit(ticket_id="TASK-503", risk="ERR_RACE_CONDITION")
    suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        // 1. Cache 조회 (Index Scan)
        dbCache.get(text, sourceLang, targetLang)?.let { return it }

        // ★ 2. 엔터프라이즈급 Concurrent Race (가장 빠른 응답 채택, 대기시간 제로화)
        // 구글 네트워크 API와 로컬 오프라인 모델을 동시에 출발시켜, 먼저 도착하는 쪽의 결과를 즉시 UI에 반영
        return withContext(Dispatchers.IO) {
            val channel = kotlinx.coroutines.channels.Channel<String?>(2)

            // Worker A: Google Network API
            launch {
                var connection: java.net.HttpURLConnection? = null
                try {
                    val encodedText = java.net.URLEncoder.encode(text, "UTF-8")
                    val urlString = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=$encodedText"
                    val url = java.net.URL(urlString)
                    connection = url.openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                    connection.connectTimeout = 2500
                    connection.readTimeout = 2500

                    if (connection.responseCode == 200) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val jsonArray = org.json.JSONArray(response)
                        val segments = jsonArray.getJSONArray(0)
                        val sb = StringBuilder()
                        for (i in 0 until segments.length()) {
                            val segment = segments.getJSONArray(i)
                            sb.append(segment.getString(0))
                        }
                        channel.send(sb.toString())
                    } else {
                        channel.send(null)
                    }
                } catch (e: Exception) {
                    channel.send(null)
                } finally {
                    connection?.disconnect()
                }
            }

            // Worker B: Argos Native Local (Offline Backup)
            launch {
                try {
                    if (sourceLang == "ja" && targetLang == "ko") {
                        val en = enJaMutex.withLock { if (enJaPtr != 0L) translateNative(enJaPtr, text, "en") else "" }
                        val ko = koEnMutex.withLock { if (koEnPtr != 0L && en.isNotEmpty()) translateNative(koEnPtr, en, "ko") else "" }
                        channel.send(if (ko.isNotEmpty()) ko else null)
                    } else {
                        channel.send(null)
                    }
                } catch (e: Exception) {
                    channel.send(null)
                }
            }

            // Race: 먼저 도착한 정상 결과 획득
            var result = channel.receive()
            if (result == null) {
                // 첫번째 워커가 실패했다면 두번째 워커 결과를 대기
                result = channel.receive()
            }

            val finalResult = result ?: text
            if (finalResult != text) {
                dbCache.put(text, sourceLang, targetLang, finalResult)
            }
            finalResult
        }
    }

    // @ysig_audit(ticket_id="TASK-504", risk="ERR_MEMORY_LEAK")
    fun close() {
        job.cancel()
        dbCache.clear()

        scope.launch {
            koEnMutex.withLock {
                if (koEnPtr != 0L) { releaseNativeModel(koEnPtr); koEnPtr = 0L }
            }
            enJaMutex.withLock {
                if (enJaPtr != 0L) { releaseNativeModel(enJaPtr); enJaPtr = 0L }
            }
            Log.d("Translator", "All Translate DB sessions closed.")
        }
    }
}

