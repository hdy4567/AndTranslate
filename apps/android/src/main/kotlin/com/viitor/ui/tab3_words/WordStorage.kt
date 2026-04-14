package com.viitor.ui.tab3_words

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * [WordStorage] (KzmIndexedDB Style Persistence)
 * - 3탭(Words) 전용 저장소
 * - SharedPreferences를 사용하여 단어를 영구 저장합니다.
 */
class WordStorage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("andtrans_words_prefs", Context.MODE_PRIVATE)

    fun saveWords(words: List<String>) {
        val jsonArray = JSONArray()
        words.forEach { jsonArray.put(it) }
        prefs.edit().putString("saved_words_json", jsonArray.toString()).apply()
    }

    fun loadWords(): List<String> {
        val json = prefs.getString("saved_words_json", "[]") ?: "[]"
        val jsonArray = JSONArray(json)
        val list = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            list.add(jsonArray.getString(i))
        }
        return list
    }
}
