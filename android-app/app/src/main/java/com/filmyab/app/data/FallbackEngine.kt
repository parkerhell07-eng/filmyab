package com.filmyab.app.data

import com.google.gson.JsonArray
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
 * On-device quick identification using a text-only LLM (no vision available on device).
 * Requires a successfully scraped [InstagramPost] (caption or hashtags).
 */
object FallbackEngine {

    const val POLLINATIONS_URL = "https://text.pollinations.ai/openai"

    private const val GENERIC_FAIL =
        "نتوانستم فیلم را با اطمینان کافی تشخیص دهم. لینک پست یا راهنمای دقیق‌تری امتحان کنید."

    private const val SYSTEM_MESSAGE =
        "You are a film identification expert. Given an Instagram post caption/hashtags about a movie clip or teaser, " +
            "deduce the exact movie. Reply ONLY with minified JSON, no markdown fences, in this exact schema: " +
            "{\"found\": boolean, \"title\": \"original English title or empty\", \"title_fa\": \"Persian title or empty\", " +
            "\"year\": \"\", \"confidence\": 0.0, \"reasoning_fa\": \"short Persian explanation\", " +
            "\"alternatives\": [{\"title\": \"\", \"year\": \"\"}]}. " +
            "confidence 0..1; if the caption/hashtags do not clearly point to one movie, set found=false " +
            "and list up to 3 plausible alternatives."

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Asks the text LLM to identify the movie from caption/hashtags/uploader/note.
     * Retries once on network failure; parse failures return a not-found result.
     */
    suspend fun quickIdentify(post: InstagramPost, note: String?): QuickResult =
        withContext(Dispatchers.IO) {
            val userMessage = buildUserMessage(post, note)
            for (attempt in 0 until 2) {
                try {
                    val content = callApi(userMessage)
                    return@withContext parseQuickResult(content)
                } catch (e: IOException) {
                    // network failure — retry once
                } catch (e: Exception) {
                    return@withContext QuickResult(
                        found = false,
                        confidence = 0.0,
                        reasoningFa = "پاسخ تحلیل‌گر قابل خواندن نبود. لطفاً دوباره تلاش کنید.",
                        alternatives = emptyList(),
                        googleQuery = null
                    )
                }
            }
            QuickResult(
                found = false,
                confidence = 0.0,
                reasoningFa = "اتصال به سرویس تحلیل سریع ممکن نشد. اتصال اینترنت را بررسی کنید و دوباره تلاش کنید.",
                alternatives = emptyList(),
                googleQuery = null
            )
        }

    private fun buildUserMessage(post: InstagramPost, note: String?): String {
        val sb = StringBuilder()
        sb.append("Identify the movie from this Instagram post.\n")
        post.ownerUsername?.let { sb.append("Uploader: @").append(it).append("\n") }
        val caption = post.caption?.take(700)
        sb.append("Caption: ").append(caption ?: "(no caption)").append("\n")
        if (post.hashtags.isNotEmpty()) {
            sb.append("Hashtags: ").append(post.hashtags.joinToString(" ")).append("\n")
        }
        if (!note.isNullOrBlank()) {
            sb.append("User hint: ").append(note.trim()).append("\n")
        }
        return sb.toString()
    }

    /** POST to Pollinations OpenAI-compatible endpoint, returns choices[0].message.content. */
    private fun callApi(userMessage: String): String {
        val payload = JsonObject()
        payload.addProperty("model", "openai")

        val sys = JsonObject()
        sys.addProperty("role", "system")
        sys.addProperty("content", SYSTEM_MESSAGE)
        val usr = JsonObject()
        usr.addProperty("role", "user")
        usr.addProperty("content", userMessage)
        val messages = JsonArray()
        messages.add(sys)
        messages.add(usr)
        payload.add("messages", messages)
        payload.addProperty("temperature", 0.2)

        val request = Request.Builder()
            .url(POLLINATIONS_URL)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val bodyStr = resp.body?.string() ?: throw IOException("empty response body")
            val root = JsonParser.parseString(bodyStr).asJsonObject
            val choices = root.getAsJsonArray("choices") ?: throw IOException("invalid response")
            if (choices.size() == 0) throw IOException("invalid response")
            val first = choices.get(0).asJsonObject
            val message = first.getAsJsonObject("message") ?: throw IOException("invalid response")
            val content = message.get("content")
            if (content == null || content.isJsonNull) throw IOException("invalid response")
            return content.asString
        }
    }

    private fun parseQuickResult(content: String): QuickResult {
        var cleaned = content.trim()
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.removePrefix("```json").removePrefix("```").trim()
            cleaned = cleaned.removeSuffix("```").trim()
        }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return QuickResult(found = false, confidence = 0.0, reasoningFa = GENERIC_FAIL)
        }
        val obj = try {
            JsonParser.parseString(cleaned.substring(start, end + 1)).asJsonObject
        } catch (e: Exception) {
            return QuickResult(found = false, confidence = 0.0, reasoningFa = GENERIC_FAIL)
        }

        val reasoning = optString(obj, "reasoning_fa")

        val alternatives = mutableListOf<Candidate>()
        val altsEl = obj.get("alternatives")
        if (altsEl != null && altsEl.isJsonArray) {
            for (el in altsEl.asJsonArray) {
                if (alternatives.size >= 3) break
                val al = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val title = optString(al, "title") ?: continue
                alternatives.add(Candidate(name = title, year = optString(al, "year"), confidence = 0.0))
            }
        }

        val foundEl = obj.get("found")
        val found = foundEl?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        val title = optString(obj, "title")
        val titleFa = optString(obj, "title_fa")
        val year = optString(obj, "year")
        val confEl = obj.get("confidence")
        val rawConfidence = confEl?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
        val confidence = if (rawConfidence.isNaN()) 0.0 else rawConfidence.coerceIn(0.0, 0.65)

        if (!found || title == null) {
            return QuickResult(
                found = false,
                title = title,
                titleFa = titleFa,
                year = year,
                confidence = 0.0,
                reasoningFa = reasoning ?: GENERIC_FAIL,
                alternatives = alternatives,
                googleQuery = null
            )
        }
        return QuickResult(
            found = true,
            title = title,
            titleFa = titleFa,
            year = year,
            confidence = confidence,
            reasoningFa = reasoning,
            alternatives = alternatives,
            googleQuery = listOfNotNull(title, year, "movie").joinToString(" ").trim()
        )
    }

    private fun optString(obj: JsonObject, key: String): String? {
        val el = obj.get(key) ?: return null
        if (el.isJsonNull || !el.isJsonPrimitive) return null
        return try {
            el.asString.trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}
