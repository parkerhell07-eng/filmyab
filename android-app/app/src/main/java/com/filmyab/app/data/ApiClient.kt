package com.filmyab.app.data

import com.google.gson.Gson
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for the FilmYab backend (POST /api/identify SSE stream + GET /api/history).
 */
object ApiClient {

    val gson = Gson()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Trim, prepend http:// when missing, strip trailing slashes. */
    fun normalizeBaseUrl(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "http://$s"
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length - 1)
        }
        return s
    }

    /**
     * Runs the identify pipeline against `<base>/api/identify` and streams events.
     * Terminal events (result / error) end the read loop.
     * Throws [IOException] on transport-level failure (caller decides to fall back).
     */
    suspend fun identifySse(
        baseUrl: String,
        url: String?,
        imageBase64: String?,
        note: String?,
        onStep: (StepEvent) -> Unit,
        onResult: (PipelineResult) -> Unit,
        onError: (String, String?) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val payload = JsonObject()
        if (url != null) payload.addProperty("url", url) else payload.add("url", JsonNull.INSTANCE)
        if (imageBase64 != null) {
            payload.addProperty("imageBase64", imageBase64)
        } else {
            payload.add("imageBase64", JsonNull.INSTANCE)
        }
        if (note != null) payload.addProperty("note", note) else payload.add("note", JsonNull.INSTANCE)

        val request = Request.Builder()
            .url("$baseUrl/api/identify")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty response body")
            val source = body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data: ")) continue
                val jsonText = line.substring(6).trim()
                if (jsonText.isEmpty()) continue
                val obj = try {
                    JsonParser.parseString(jsonText).asJsonObject
                } catch (e: Exception) {
                    continue
                }
                val typeEl = obj.get("type")
                val type = if (typeEl != null && typeEl.isJsonPrimitive) typeEl.asString else null
                when (type) {
                    "result" -> {
                        val resultEl = obj.get("result")
                        if (resultEl != null && resultEl.isJsonObject) {
                            val result = gson.fromJson(resultEl, PipelineResult::class.java)
                            onResult(result)
                            return@use
                        }
                    }
                    "error" -> {
                        val msgEl = obj.get("message")
                        val message = if (msgEl != null && msgEl.isJsonPrimitive) {
                            msgEl.asString
                        } else {
                            "خطای ناشناخته از سرور"
                        }
                        val hintEl = obj.get("hint")
                        val hint = if (hintEl != null && hintEl.isJsonPrimitive) hintEl.asString else null
                        onError(message, hint)
                        return@use
                    }
                    else -> {
                        if (obj.has("step")) {
                            onStep(gson.fromJson(obj, StepEvent::class.java))
                        }
                    }
                }
            }
        }
    }

    /** Cheap reachability probe: GET `<base>/api/history`. */
    suspend fun testServer(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$baseUrl/api/history").get().build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}
