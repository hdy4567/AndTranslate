package com.viitor.stt

import android.content.Context
import android.util.Log
import java.util.HashMap
import java.nio.charset.Charset

object WhisperVocabulary {
    private const val TAG = "WhisperVocab"
    private val vocabMap = HashMap<Int, String>(5100)
    private var isLoaded = false

    fun init(context: Context) {
        if (isLoaded) return
        try {
            context.assets.open("whisper_vocab.txt").bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        try {
                            vocabMap[parts[0].toInt()] = parts[1]
                        } catch (e: Exception) {}
                    }
                }
            }
            isLoaded = true
        } catch (e: Exception) {
            Log.e(TAG, "Fail: ${e.message}")
        }
    }

    private val byteEncoder: Map<Char, Int> by lazy {
        val bs = ((33..126).toList() + (161..172).toList() + (174..255).toList()).toMutableList()
        val cs = bs.toMutableList()
        var n = 0
        for (b in 0..255) {
            if (b !in bs) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        cs.mapIndexed { index, charCode -> charCode.toChar() to bs[index] }.toMap()
    }

    fun decode(tokens: IntArray): String {
        if (!isLoaded) return ""
        val byteList = mutableListOf<Byte>()
        for (token in tokens) {
            if (token >= 50257) continue
            val s = vocabMap[token]
            if (s != null) {
                for (c in s) {
                    byteEncoder[c]?.let { byteList.add(it.toByte()) }
                }
            } else if (token < 256) {
                byteList.add(token.toByte())
            }
        }
        
        val resultBytes = ByteArray(byteList.size)
        for (i in byteList.indices) {
            resultBytes[i] = byteList[i]
        }
        
        return try {
            String(resultBytes, Charset.forName("UTF-8"))
        } catch (e: Exception) {
            ""
        }
    }
}
