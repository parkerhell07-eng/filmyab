package com.filmyab.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Local history persisted in SharedPreferences as a JSON array (max 50 items, newest first).
 */
class HistoryStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "filmyab_history"
        private const val KEY_ITEMS = "items"
        private const val MAX_ITEMS = 50
    }

    private val gson = Gson()
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun add(item: LocalHistoryItem) {
        val list = getAll().toMutableList()
        list.add(0, item)
        while (list.size > MAX_ITEMS) {
            list.removeAt(list.size - 1)
        }
        save(list)
    }

    fun getAll(): List<LocalHistoryItem> {
        val json = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<LocalHistoryItem>>() {}.type
            gson.fromJson<List<LocalHistoryItem>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_ITEMS).apply()
    }

    private fun save(list: List<LocalHistoryItem>) {
        prefs.edit().putString(KEY_ITEMS, gson.toJson(list)).apply()
    }
}
