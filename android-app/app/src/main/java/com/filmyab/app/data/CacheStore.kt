package com.filmyab.app.data

import android.content.Context
import com.google.gson.Gson

/**
 * Tiny on-device result cache keyed by the exact Instagram URL.
 *
 * Speed win: re-running the SAME link within 7 days returns the previous
 * successful result instantly (no network, no waiting). Failed runs are never
 * cached, so a retry always executes the full engine.
 */
object ResultCache {

    private const val PREFS_NAME = "filmyab_result_cache"
    private const val MAX_ENTRIES = 20
    private const val FRESH_MS = 7L * 24 * 60 * 60 * 1000 // one week

    private data class Entry(val url: String, val atMs: Long, val json: String)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the cached success for [url] when it exists and is still fresh. */
    fun get(context: Context, url: String): SmartResult? {
        return try {
            val gson = Gson()
            val type = object : com.google.gson.reflect.TypeToken<List<Entry>>() {}.type
            val list: List<Entry> = gson.fromJson(prefs(context).getString("entries", null), type)
                ?: return null
            val now = System.currentTimeMillis()
            val hit = list.firstOrNull {
                it.url == url && now - it.atMs < FRESH_MS
            } ?: return null
            val result = try {
                gson.fromJson(hit.json, SmartResult::class.java)
            } catch (e: Exception) {
                null
            }
            result?.takeIf { it.status == "success" }
        } catch (e: Exception) {
            null
        }
    }

    /** Stores a successful result for [url] (keeps the newest [MAX_ENTRIES]). */
    fun put(context: Context, url: String, result: SmartResult) {
        if (result.status != "success") return
        try {
            val gson = Gson()
            val type = object : com.google.gson.reflect.TypeToken<MutableList<Entry>>() {}.type
            val list: MutableList<Entry> =
                gson.fromJson(prefs(context).getString("entries", null), type)
                    ?: mutableListOf()
            list.removeAll { it.url == url }
            list.add(0, Entry(url, System.currentTimeMillis(), gson.toJson(result)))
            while (list.size > MAX_ENTRIES) {
                list.removeAt(list.size - 1)
            }
            prefs(context).edit().putString("entries", gson.toJson(list)).apply()
        } catch (e: Exception) {
            // cache is best-effort — never break the pipeline
        }
    }
}
