package com.filmyab.app.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * FilmYab v2 on-device SMART ENGINE (no server, no keys, no settings).
 *
 * Pipeline:
 *  1. net     — automatic VPN/connectivity check (Instagram + internet reachability)
 *  2. fetch   — scrape the Instagram post on device (crawler-UA page, embed, __a=1, og)
 *  3. analyze — download the video to cache, sample 3 frames (MediaMetadataRetriever),
 *               read on-screen text with on-device OCR (ML Kit, bundled latin model)
 *  4. match   — keyless text LLM (Pollinations) turns caption/hashtags/OCR/og-title
 *               into ranked movie candidates
 *  5. verify  — each candidate is verified against Wikipedia REST (keyless): title,
 *               year, director, description, poster
 *  6. final   — self-built scoring blends LLM confidence with concrete evidence
 *               (wiki confirmation, og-title hit, hashtag hit, OCR hit); a second
 *               LLM pass fuses everything when the score is not decisive.
 */
object SmartEngine {

    /* ---------------- step keys ---------------- */
    const val STEP_NET = "net"
    const val STEP_FETCH = "fetch"
    const val STEP_ANALYZE = "analyze"
    const val STEP_MATCH = "match"
    const val STEP_VERIFY = "verify"
    const val STEP_FINAL = "final"

    sealed class Outcome {
        data class Done(val result: SmartResult) : Outcome()
        data class Fail(val message: String, val needVpn: Boolean = false) : Outcome()
    }

    private data class AiAnalysis(
        val found: Boolean,
        val sceneSummary: String?,
        val candidates: List<Candidate>
    )

    private data class Fused(
        val found: Boolean,
        val titleEn: String?,
        val titleFa: String?,
        val year: String?,
        val director: String?,
        val genres: List<String>,
        val confidence: Double,
        val reasoningFa: String?
    )

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Wikimedia throttles generic browser UAs from datacenter/VPN IPs — a descriptive
     *  UA per their UA policy gets served reliably. */
    private const val WIKI_UA = "FilmYabEngine/2.1 (Android; movie identification; contact: filmyab.app)"
    private const val POLLINATIONS_URL = "https://text.pollinations.ai/openai"
    private const val MAX_VIDEO_BYTES = 120L * 1024 * 1024

    /** Identifies this app to the free AI gateway (raises anonymous quota). */
    private const val REFERRER = "filmyab"

    /** Free text models on the Classic API; first is the fastest/cheapest. */
    private val LLM_MODELS = listOf("openai-fast", "openai")

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val quickHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /* ================= 1. connectivity ================= */

    suspend fun checkNetwork(): NetState = withContext(Dispatchers.IO) {
        val instagram = reachable("https://www.instagram.com/robots.txt")
        val internet = instagram ||
            reachable("https://www.gstatic.com/generate_204") ||
            reachable("https://en.wikipedia.org/w/rest.php/v1/search/title?q=a&limit=1")
        when {
            instagram -> NetState("ok", instagramOk = true, internetOk = true)
            internet -> NetState("vpn_needed", instagramOk = false, internetOk = true)
            else -> NetState("offline", instagramOk = false, internetOk = false)
        }
    }

    private fun reachable(url: String): Boolean = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .build()
        quickHttp.newCall(req).execute().use { it.isSuccessful || it.code in listOf(301, 302, 403, 429) }
    } catch (e: Exception) {
        false
    }

    /* ================= 2..6 pipeline ================= */

    suspend fun identify(
        appContext: Context,
        rawUrl: String,
        onStep: (String, String) -> Unit
    ): Outcome = withContext(Dispatchers.IO) {

        /* ---- 1. net ---- */
        onStep(STEP_NET, "start")
        val net = checkNetwork()
        onStep(STEP_NET, if (net.internetOk || net.instagramOk) "done" else "error")
        if (!net.internetOk) {
            return@withContext Outcome.Fail("اینترنت در دسترس نیست. اتصال شبکه یا فیلترشکن را بررسی کنید.")
        }

        /* ---- 2. fetch ---- */
        onStep(STEP_FETCH, "start")
        val post = InstagramScraper.fetchPost(rawUrl)
        if (post == null) {
            onStep(STEP_FETCH, "error")
            return@withContext Outcome.Fail(
                "اینستاگرام اجازه خواندن این پست را نداد. اگر فیلترشکن داری روشنش کن، چند لحظه صبر کن و دوباره تلاش کن. (پست باید عمومی باشد)",
                needVpn = !net.instagramOk
            )
        }
        onStep(STEP_FETCH, "done")

        /* ---- 3. analyze: frames + OCR ---- */
        onStep(STEP_ANALYZE, "start")
        var ocrText = ""
        val videoUrl = post.videoUrl
        if (videoUrl != null) {
            val frames = extractFrames(appContext, videoUrl, 3)
            if (frames.isNotEmpty()) {
                ocrText = runOcr(frames)
                onStep(STEP_ANALYZE, "done")
            } else {
                onStep(STEP_ANALYZE, "skip")
            }
        } else {
            onStep(STEP_ANALYZE, "skip")
        }

        /* ---- 4. match: smart candidates (AI + deterministic evidence path) ---- */
        onStep(STEP_MATCH, "start")
        val ai = askLlmCandidates(post, ocrText)
        val direct = directEvidenceCandidates(post, ocrText)
        val merged = mergeCandidates(ai?.candidates ?: emptyList(), direct)
        if (merged.isEmpty()) {
            onStep(STEP_MATCH, "error")
            if (ai != null) {
                val extra = ai.sceneSummary?.let { " توضیح صحنه: $it" } ?: ""
                return@withContext Outcome.Fail(
                    "با شواهد موجود (کپشن و متن روی تصویر) نتونستم فیلمی رو با اطمینان پیدا کنم.$extra"
                )
            }
            return@withContext Outcome.Fail(
                "سرویس تحلیل هوشمند موقتاً محدود شده و از هشتگ‌ها و متن تصویر هم کاندیدی در پایگاه فیلم پیدا نشد. چند لحظه بعد دوباره امتحان کن."
            )
        }
        onStep(STEP_MATCH, "done")
        val effective = AiAnalysis(
            found = merged.isNotEmpty(),
            sceneSummary = ai?.sceneSummary,
            candidates = merged
        )

        /* ---- 5. verify: Wikipedia ---- */
        onStep(STEP_VERIFY, "start")
        val verified = verifyCandidates(effective.candidates, post, ocrText)
        if (verified.isEmpty()) {
            onStep(STEP_VERIFY, "error")
            val names = effective.candidates.joinToString("، ") { it.name ?: "?" }
            return@withContext Outcome.Fail(
                "کاندیدها ($names) در پایگاه فیلم تأیید نشدند. احتمالاً کلیپ از فیلم شناخته‌شده‌ای نیست یا شواهد کم است."
            )
        }
        onStep(STEP_VERIFY, "done")

        /* ---- 6. final ---- */
        onStep(STEP_FINAL, "start")
        val best = verified.first()
        var result = buildFinal(best, verified.drop(1), effective, post, ocrText)
        if (best.score < 0.85) {
            val fused = askLlmFusion(verified, effective, post, ocrText)
            if (fused != null) {
                result = blend(result, fused)
            }
        }
        onStep(STEP_FINAL, "done")
        Outcome.Done(result)
    }

    /* ================= frames + OCR ================= */

    private fun extractFrames(context: Context, videoUrl: String, count: Int): List<Bitmap> {
        var file: File? = null
        var mmr: MediaMetadataRetriever? = null
        try {
            file = downloadToCache(context, videoUrl) ?: return emptyList()
            mmr = MediaMetadataRetriever()
            mmr.setDataSource(file.absolutePath)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durMs <= 0) return emptyList()
            val out = mutableListOf<Bitmap>()
            val fracs = doubleArrayOf(0.12, 0.45, 0.78)
            for (f in fracs) {
                if (out.size >= count) break
                val bmp = try {
                    mmr.getFrameAtTime(
                        (durMs * 1000.0 * f).toLong(),
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                } catch (e: Exception) {
                    null
                }
                if (bmp != null) out.add(bmp)
            }
            return out
        } catch (e: Exception) {
            return emptyList()
        } finally {
            try {
                mmr?.release()
            } catch (e: Exception) {
            }
            file?.delete()
        }
    }

    /** Streams the video into cache with a hard size cap. Returns null on any failure. */
    private fun downloadToCache(context: Context, url: String): File? {
        var file: File? = null
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Referer", "https://www.instagram.com/")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                val f = File(context.cacheDir, "filmyab_video_${System.currentTimeMillis()}.mp4")
                file = f
                val ok = body.byteStream().use { input ->
                    f.outputStream().use { output -> copyCapped(input, output, MAX_VIDEO_BYTES) }
                }
                if (!ok || f.length() < 10_000) {
                    f.delete()
                    file = null
                    return null
                }
                return f
            }
        } catch (e: Exception) {
            file?.delete()
            return null
        }
    }

    /** Copies with a byte cap; false when the stream exceeded the cap or failed. */
    private fun copyCapped(input: java.io.InputStream, output: java.io.OutputStream, cap: Long): Boolean {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > cap) return false
                output.write(buf, 0, n)
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /** On-device OCR (bundled latin model — no downloads). Returns merged unique lines. */
    private suspend fun runOcr(frames: List<Bitmap>): String = withContext(Dispatchers.IO) {
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val lines = LinkedHashSet<String>()
        try {
            for (bmp in frames) {
                try {
                    val text: Text = latin.process(InputImage.fromBitmap(bmp, 0)).await()
                    for (line in text.text.split("\n")) {
                        val t = line.trim()
                        if (t.length >= 2) {
                            lines.add(t)
                        }
                    }
                } catch (e: Exception) {
                    // OCR of this frame failed — keep going
                }
            }
        } finally {
            try {
                latin.close()
            } catch (e: Exception) {
            }
            frames.forEach { it.recycle() }
        }
        lines.take(14).joinToString(" | ").take(1400)
    }

    /* ================= LLM channel (keyless) ================= */

    /**
     * Bulletproof keyless LLM channel.
     *
     * Why this exists: the free gateway enforces "1 fresh reply every 30 seconds per
     * IP" — on a shared VPN exit IP nearly every first call returns 402/429, and
     * Cloudflare may answer with an HTML challenge. The old code aborted on any
     * non-IO exception (parse errors!) after 3 short retries, which surfaced as
     * "سرویس تحلیل هوشمند پاسخ نداد".
     *
     * Fixes:
     *  - 402/429 → wait politely (Retry-After honored, default 31s) up to
     *    [maxRateLimitWaits] times, then retry the SAME payload (server cache hits).
     *  - tolerant parsing via [extractLlmContent]; HTML/error pages are retries, not
     *    fatal; parse errors never break the loop.
     *  - model rotation on hard failures (openai-fast → openai).
     *  - `referrer` sent for higher anonymous quota.
     *  - hard wall-clock [totalBudgetMs] so the pipeline always terminates.
     */
    private fun callLlm(
        system: String,
        user: String,
        maxRateLimitWaits: Int = 2,
        totalBudgetMs: Long = 150_000L
    ): String? {
        val deadline = System.currentTimeMillis() + totalBudgetMs
        var rateWaits = 0
        var hardFails = 0
        while (System.currentTimeMillis() < deadline) {
            val model = LLM_MODELS[hardFails % LLM_MODELS.size]
            try {
                val payload = JsonObject()
                payload.addProperty("model", model)
                payload.addProperty("referrer", REFERRER)
                val sys = JsonObject()
                sys.addProperty("role", "system")
                sys.addProperty("content", system)
                val usr = JsonObject()
                usr.addProperty("role", "user")
                usr.addProperty("content", user)
                val messages = JsonArray()
                messages.add(sys)
                messages.add(usr)
                payload.add("messages", messages)
                payload.addProperty("temperature", 0.2)

                val request = Request.Builder()
                    .url("$POLLINATIONS_URL?referrer=$REFERRER")
                    .header("Accept", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(request).execute().use { resp ->
                    when {
                        resp.code == 402 || resp.code == 429 -> {
                            if (rateWaits >= maxRateLimitWaits) return null
                            rateWaits++
                            val ra = resp.header("Retry-After")?.trim()?.toLongOrNull()
                            sleepQuiet((ra ?: 30L).coerceIn(5L, 40L) * 1000L + 1500L)
                        }
                        resp.code >= 500 -> {
                            hardFails++
                            sleepQuiet(4000L)
                        }
                        !resp.isSuccessful -> {
                            hardFails++
                            sleepQuiet(1200L)
                        }
                        else -> {
                            val bodyStr = resp.body?.string() ?: ""
                            val content = extractLlmContent(bodyStr)
                            if (content != null && content.contains('{') && content.contains('}')) {
                                return content
                            }
                            hardFails++
                            sleepQuiet(1200L)
                        }
                    }
                }
            } catch (e: Exception) {
                hardFails++
                sleepQuiet(2500L)
            }
        }
        return null
    }

    private fun sleepQuiet(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Tolerant LLM reply parser: OpenAI chat shape, legacy completions shape, or a
     * top-level content field. HTML challenge pages and plain error texts return null
     * so the caller retries instead of crashing.
     */
    private fun extractLlmContent(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.length < 2) return null
        val lower = trimmed.lowercase(Locale.US)
        if (lower.startsWith("<!doctype") || lower.startsWith("<html") ||
            lower.contains("just a moment") || lower.contains("cloudflare")
        ) {
            return null
        }
        if (trimmed.startsWith("{")) {
            return try {
                val root = JsonParser.parseString(trimmed).asJsonObject
                val choices = root.getAsJsonArray("choices")
                if (choices != null && choices.size() > 0) {
                    val c0 = choices.get(0).takeIf { it.isJsonObject }?.asJsonObject
                    val msg = c0?.getAsJsonObject("message")
                    (msg?.get("content")?.takeIf { !it.isJsonNull }?.asString
                        ?: c0?.get("text")?.takeIf { !it.isJsonNull }?.asString)
                        ?.trim()?.takeIf { it.isNotEmpty() }
                } else {
                    root.get("content")?.takeIf { !it.isJsonNull }?.asString
                        ?.trim()?.takeIf { it.isNotEmpty() }
                }
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    private fun extractJson(content: String): JsonObject? {
        var cleaned = content.trim()
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.removePrefix("```json").removePrefix("```").trim()
            cleaned = cleaned.removeSuffix("```").trim()
        }
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            JsonParser.parseString(cleaned.substring(start, end + 1)).asJsonObject
        } catch (e: Exception) {
            null
        }
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

    private fun optDouble(obj: JsonObject, key: String): Double {
        val el = obj.get(key) ?: return 0.0
        if (!el.isJsonPrimitive) return 0.0
        return try {
            val d = el.asDouble
            if (d.isNaN()) 0.0 else d
        } catch (e: Exception) {
            0.0
        }
    }

    private fun optBool(obj: JsonObject, key: String): Boolean {
        val el = obj.get(key) ?: return false
        if (!el.isJsonPrimitive) return false
        return try {
            el.asBoolean
        } catch (e: Exception) {
            false
        }
    }

    /* ---- step 4: candidates ---- */

    private const val CANDIDATES_SYSTEM =
        "You are FilmYab, an on-device movie identification engine. You receive evidence scraped from an " +
            "Instagram reel or post: caption, hashtags, uploader, page og-title, and text read from video " +
            "frames by on-device OCR (may contain noise, watermarks, subtitle lines or title cards). " +
            "Task: name the exact movie or series the clip is from. Pay special attention to title cards, " +
            "credits, character names and subtitle phrases found by OCR. " +
            "Reply ONLY with minified JSON, no markdown, exactly this schema: " +
            "{\"scene_summary_fa\":\"یک جمله کوتاه فارسی درباره صحنه\"," +
            "\"candidates\":[{\"title\":\"original English title\",\"year\":\"\",\"confidence\":0.0,\"reason_fa\":\"دلیل کوتاه فارسی\"}]," +
            "\"found\":true}. " +
            "Rules: at most 3 candidates ordered by likelihood; confidence is 0..1 and reflects your " +
            "certainty for each candidate; year=\"\" if unsure; " +
            "if the evidence is too weak to name any movie, set found=false and candidates=[]."

    private fun askLlmCandidates(post: InstagramPost, ocrText: String): AiAnalysis? {
        val sb = StringBuilder()
        sb.append("Identify the movie from this Instagram post.\n")
        post.ownerUsername?.let { sb.append("Uploader: @").append(it).append("\n") }
        post.ogTitle?.let { sb.append("Page title: ").append(it.take(200)).append("\n") }
        sb.append("Caption: ").append(post.caption?.take(900) ?: "(none)").append("\n")
        if (post.hashtags.isNotEmpty()) {
            sb.append("Hashtags: ").append(post.hashtags.joinToString(" ")).append("\n")
        }
        if (ocrText.isNotBlank()) {
            sb.append("On-screen text (OCR): ").append(ocrText).append("\n")
        }
        val content = callLlm(CANDIDATES_SYSTEM, sb.toString()) ?: return null
        val obj = extractJson(content) ?: return null

        val candidates = mutableListOf<Candidate>()
        val arr = obj.get("candidates")
        if (arr != null && arr.isJsonArray) {
            for (el in arr.asJsonArray) {
                if (candidates.size >= 3) break
                val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val title = optString(o, "title") ?: continue
                candidates.add(
                    Candidate(
                        name = title,
                        year = optString(o, "year"),
                        confidence = optDouble(o, "confidence").coerceIn(0.0, 1.0),
                        reason = optString(o, "reason_fa")
                    )
                )
            }
        }
        return AiAnalysis(
            found = optBool(obj, "found") && candidates.isNotEmpty(),
            sceneSummary = optString(obj, "scene_summary_fa"),
            candidates = candidates
        )
    }

    /* ---- step 4b: deterministic evidence path (works even without the AI service) ---- */

    /** Hashtags that describe the platform/format, not a movie — never used as queries. */
    private val GENERIC_TAGS = setOf(
        "movie", "movies", "film", "films", "cinema", "reel", "reels", "instagram",
        "viral", "explore", "trending", "hollywood", "bollywood", "trailer", "clip",
        "scene", "scenes", "edit", "edits", "shorts", "video", "videos", "fyp", "foryou",
        "actor", "actress", "love", "follow", "like", "dubbed", "netflix", "series",
        "movieclip", "movieclips", "filmclip", "newmovie", "fullmovie", "hd", "4k",
        "subscribe", "share", "comment", "explorepage", "instagood", "reelsvideo",
        "reelsinstagram", "trendingreels", "viralreels", "capcut", "status", "whatsapp"
    )

    private val ogTitleNoiseRegex =
        Regex("""^\s*(?:[\d.,]+[KkMm]?\s+likes?,\s*[\d.,]+\s+comments?\s*-\s*)?[^:\n]{0,60}on Instagram:\s*""")

    /** "TheShawshankRedemption" → "The Shawshank Redemption". */
    private fun splitCamelCase(s: String): String =
        s.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Za-z])(\\d)"), "$1 $2")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** og:title → most query-worthy fragment ("Name on Instagram: caption…" → "caption…"). */
    private fun cleanOgTitleForQuery(t: String): String? {
        var s = t.replace(Regex("""\s*[•|]\s*Instagram\s*$"""), "").trim()
        val m = ogTitleNoiseRegex.find(s)
        if (m != null) s = s.substring(m.value.length).trim()
        if (s.length > 80) s = s.take(80).trim()
        return s.takeIf { it.length >= 4 }
    }

    /**
     * Deterministic, keyless candidate finder that never depends on the AI service:
     * it turns the strongest signals of the post (page title, hashtags, caption lines,
     * OCR title cards) into Wikipedia film searches. Results are verified again later
     * by [verifyCandidates], so false hits get filtered with hard evidence.
     */
    private fun directEvidenceCandidates(post: InstagramPost, ocrText: String): List<Candidate> {
        val queries = LinkedHashSet<String>()

        post.ogTitle?.let { t -> cleanOgTitleForQuery(t)?.let { queries.add(it) } }

        for (tag in post.hashtags.take(10)) {
            val base = tag.removePrefix("#").trim()
            if (base.length < 3 || base.lowercase(Locale.US) in GENERIC_TAGS) continue
            val spaced = base.replace('_', ' ').replace('-', ' ').replace('.', ' ')
                .replace(Regex("\\s+"), " ").trim()
            queries.add(splitCamelCase(spaced))
        }

        post.caption?.take(400)?.split("\n")?.forEach { raw ->
            val t = raw.trim()
            val letters = t.count { it.isLetter() }
            if (t.length in 4..60 && letters >= 4 && letters >= t.length * 0.6) {
                queries.add(t)
            }
        }

        if (ocrText.isNotBlank()) {
            ocrText.split(" | ").forEach { raw ->
                val t = raw.trim()
                val letters = t.count { it.isLetter() }
                if (t.length in 4..70 && letters >= 4 && letters >= t.length * 0.5) {
                    queries.add(t)
                }
            }
        }

        val out = mutableListOf<Candidate>()
        val seenTitles = mutableSetOf<String>()
        for (q in queries.take(8)) {
            if (out.size >= 4) break
            val lang = if (persianRegex.containsMatchIn(q)) "fa" else "en"
            val pages = try {
                wikiSearch(lang, q, 5)
            } catch (e: Exception) {
                emptyList()
            }
            if (pages.isEmpty()) continue
            val filmPages = pages.filter { looksLikeFilmDescription(it.description) }
            val exact = pages.firstOrNull { normalizedKey(it.title) == normalizedKey(q) }
            val chosen = (filmPages + listOfNotNull(exact)).distinctBy { it.key }.take(2)
            for (p in chosen) {
                val key = normalizedKey(p.title)
                if (key.isEmpty() || seenTitles.contains(key)) continue
                seenTitles.add(key)
                out.add(
                    Candidate(
                        name = p.title,
                        year = p.description?.let { yearRegex.find(it)?.value },
                        confidence = 0.5,
                        reason = "از نشانه‌های خود پست (هشتگ/عنوان/متن تصویر) در پایگاه فیلم پیدا شد"
                    )
                )
            }
        }
        return out
    }

    /** LLM candidates first, then unique evidence-path candidates, capped at 5. */
    private fun mergeCandidates(llm: List<Candidate>, direct: List<Candidate>): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val seen = mutableSetOf<String>()
        for (c in llm + direct) {
            val name = c.name ?: continue
            val key = normalizedKey(name)
            if (key.isEmpty() || seen.contains(key)) continue
            seen.add(key)
            out.add(c)
            if (out.size >= 5) break
        }
        return out
    }

    /* ---- step 5: Wikipedia verification (keyless) ---- */

    private data class WikiPage(
        val lang: String,
        val key: String,
        val title: String,
        val description: String?
    )

    private fun wikiSearch(lang: String, query: String, limit: Int): List<WikiPage> {
        val q = URLEncoder.encode(query, "UTF-8")
        repeat(2) { attempt ->
            try {
                val req = Request.Builder()
                    .url("https://$lang.wikipedia.org/w/rest.php/v1/search/title?q=$q&limit=$limit")
                    .header("User-Agent", WIKI_UA)
                    .header("Accept", "application/json")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (attempt == 0 && resp.code == 429) {
                            sleepQuiet(2000L)
                            return@use null // retry once on throttle
                        }
                        return@use emptyList()
                    }
                    val bodyStr = resp.body?.string() ?: return@use emptyList()
                    val root = JsonParser.parseString(bodyStr).asJsonObject
                    val pages = root.getAsJsonArray("pages") ?: return@use emptyList()
                    val out = mutableListOf<WikiPage>()
                    for (el in pages) {
                        val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                        val key = optString(o, "key") ?: continue
                        val title = optString(o, "title") ?: key
                        out.add(WikiPage(lang, key, title, optString(o, "description")))
                    }
                    return out
                }
            } catch (e: Exception) {
                if (attempt == 1) return emptyList()
                sleepQuiet(2000L)
            }
        }
        return emptyList()
    }

    private fun wikiSummary(lang: String, key: String): JsonObject? {
        val encoded = URLEncoder.encode(key, "UTF-8").replace("+", "%20")
        repeat(2) { attempt ->
            try {
                val req = Request.Builder()
                    .url("https://$lang.wikipedia.org/api/rest_v1/page/summary/$encoded")
                    .header("User-Agent", WIKI_UA)
                    .header("Accept", "application/json")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (attempt == 0 && resp.code == 429) {
                            sleepQuiet(2000L)
                            return@use null // retry once on throttle
                        }
                        return@use null
                    }
                    val bodyStr = resp.body?.string() ?: return@use null
                    return JsonParser.parseString(bodyStr).asJsonObject
                }
            } catch (e: Exception) {
                if (attempt == 1) return null
                sleepQuiet(2000L)
            }
        }
        return null
    }

    private val filmDescRegex = Regex("""(?i)\b(film|movie|series|television|miniseries)\b""")
    private val yearRegex = Regex("""(19|20)\d{2}""")
    private val directorRegex = Regex("""\bby ([A-Z][^,.;()]{2,40})""")
    private val persianRegex = Regex("""[\u0600-\u06FF]""")

    private fun looksLikeFilmDescription(description: String?): Boolean {
        val d = description ?: return false
        return filmDescRegex.containsMatchIn(d) || d.contains("فیلم") || d.contains("سریال")
    }

    private fun buildWikiEvidence(page: WikiPage): WikiEvidence? {
        val summary = wikiSummary(page.lang, page.key) ?: return null
        val description = optString(summary, "description")
        val extract = optString(summary, "extract") ?: ""
        val hay = listOfNotNull(description, extract.take(400)).joinToString(" ")
        val isFilm = filmDescRegex.containsMatchIn(hay) ||
            hay.contains("فیلم") || hay.contains("سریال")
        if (!isFilm) return null

        val year = description?.let { yearRegex.find(it)?.value }
            ?: yearRegex.find(extract.take(200))?.value
        val director = description?.let { directorRegex.find(it)?.groupValues?.get(1)?.trim() }
        val originalImage = summary.getAsJsonObject("originalimage")
        val thumbnailImage = summary.getAsJsonObject("thumbnail")
        val poster = originalImage?.optStringSafe("source")
            ?: thumbnailImage?.optStringSafe("source")
        val pageUrl = try {
            summary.getAsJsonObject("content_urls")
                ?.getAsJsonObject("desktop")?.optStringSafe("page")
        } catch (e: Exception) {
            null
        }

        // Persian title: search fa-wiki with the english title, accept entries about films
        var faTitle: String? = null
        if (page.lang == "en") {
            val faPages = wikiSearch("fa", page.title, 3)
            for (fp in faPages) {
                val d = fp.description ?: ""
                if (d.contains("فیلم") || d.contains("سریال")) {
                    faTitle = fp.title
                    break
                }
            }
        } else {
            faTitle = page.title
        }

        return WikiEvidence(
            pageTitle = page.title,
            pageDescription = description,
            year = year,
            director = director,
            pageUrl = pageUrl,
            posterUrl = poster,
            faTitle = faTitle,
            isFilm = true
        )
    }

    private fun verifyCandidates(
        candidates: List<Candidate>,
        post: InstagramPost,
        ocrText: String
    ): List<VerifiedCandidate> {
        val out = mutableListOf<VerifiedCandidate>()
        val seen = mutableSetOf<String>()
        for (c in candidates) {
            if (out.size >= 4) break
            val title = c.name ?: continue
            val key = normalizedKey(title)
            if (key.isEmpty() || seen.contains(key)) continue
            seen.add(key)

            val wiki: WikiEvidence?
            if (persianRegex.containsMatchIn(title)) {
                // Persian candidate (from fa hashtags/caption) — verify on fa-wiki directly
                val pages = wikiSearch("fa", title, 5)
                val chosen = pages.firstOrNull { looksLikeFilmDescription(it.description) }
                    ?: pages.firstOrNull { normalizedKey(it.title) == key }
                wiki = chosen?.let { buildWikiEvidence(it) }
            } else {
                val pages = wikiSearch("en", title, 5)
                var chosen: WikiPage? = null
                for (p in pages) {
                    if (looksLikeFilmDescription(p.description)) {
                        chosen = p
                        break
                    }
                }
                if (chosen == null) {
                    chosen = pages.firstOrNull { normalizedKey(it.title) == key }
                }
                if (chosen == null) {
                    // try a sharper query "title film"
                    chosen = wikiSearch("en", "$title film", 4)
                        .firstOrNull { looksLikeFilmDescription(it.description) }
                }
                wiki = chosen?.let { buildWikiEvidence(it) }
            }
            val score = scoreCandidate(c, post, ocrText, wiki)
            if (wiki != null || score >= 0.5) {
                out.add(VerifiedCandidate(c, wiki, score))
            }
        }
        return out.sortedByDescending { it.score }
    }

    /* ================= self-built scoring ================= */

    private fun normalizedKey(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9\\p{L}\\p{N}]"), "")

    private fun normalizedContains(haystack: String, needle: String): Boolean {
        val n = normalizedKey(needle)
        if (n.length < 3) return false
        return normalizedKey(haystack).contains(n)
    }

    private fun scoreCandidate(
        c: Candidate,
        post: InstagramPost,
        ocrText: String,
        wiki: WikiEvidence?
    ): Double {
        var score = c.confidence.takeIf { it > 0.0 } ?: 0.5
        if (wiki != null && wiki.isFilm) score += 0.25

        val title = c.name ?: return score.coerceIn(0.05, 0.97)
        val hay = listOfNotNull(post.caption, post.ogTitle, post.ownerUsername).joinToString(" ")
        if (normalizedContains(hay, title)) score += 0.10
        if (ocrText.isNotBlank() && normalizedContains(ocrText, title)) score += 0.15
        val tagHay = post.hashtags.joinToString("").lowercase(Locale.US)
        val titleKey = normalizedKey(title)
        if (titleKey.length >= 3 && tagHay.contains(titleKey)) score += 0.10

        return score.coerceAtMost(0.97)
    }

    /* ================= final assembly ================= */

    private fun googleQueryFor(titleEn: String?, year: String?): String? =
        listOfNotNull(titleEn, year, "movie").joinToString(" ").trim().takeIf { it.isNotBlank() }

    private fun buildFinal(
        best: VerifiedCandidate,
        others: List<VerifiedCandidate>,
        ai: AiAnalysis,
        post: InstagramPost,
        ocrText: String
    ): SmartResult {
        val w = best.wiki
        val titleEn = best.candidate.name ?: w?.pageTitle
        val year = w?.year ?: best.candidate.year
        val conf = best.score

        val reasoning = mutableListOf<String>()
        best.candidate.reason?.let { reasoning.add(it) }
        w?.let {
            val d = it.pageDescription
            reasoning.add(
                "در ویکی‌پدیا تأیید شد: ${it.pageTitle}" + (d?.let { x -> " ($x)" } ?: "")
            )
        }
        if (ocrText.isNotBlank()) reasoning.add("متن روی تصویر هم تحلیل شد")

        val evidenceBits = mutableListOf<String>()
        if (!post.caption.isNullOrBlank()) evidenceBits.add("کپشن")
        if (post.hashtags.isNotEmpty()) evidenceBits.add("هشتگ")
        if (!post.ogTitle.isNullOrBlank()) evidenceBits.add("عنوان صفحه")
        if (ocrText.isNotBlank()) evidenceBits.add("متن روی تصویر (OCR)")
        if (w != null) evidenceBits.add("ویکی‌پدیا")

        return SmartResult(
            status = if (conf >= 0.55) "success" else "low_confidence",
            titleEn = titleEn,
            titleFa = w?.faTitle,
            year = year,
            director = w?.director,
            genres = emptyList(),
            confidence = conf,
            reasoningFa = reasoning.joinToString(" — ").takeIf { it.isNotBlank() },
            posterUrl = w?.posterUrl,
            wikiUrl = w?.pageUrl,
            googleQuery = googleQueryFor(titleEn, year),
            candidates = others.take(3).map {
                Candidate(
                    name = it.candidate.name ?: it.wiki?.pageTitle,
                    year = it.wiki?.year ?: it.candidate.year,
                    confidence = it.score
                )
            },
            sceneSummary = ai.sceneSummary,
            evidenceSummary = "شواهد: " + evidenceBits.joinToString(" + "),
            instagramUrl = null,
            sourceMode = "smart"
        )
    }

    /* ---- step 6b: fusion LLM (only when score is not decisive) ---- */

    private const val FUSION_SYSTEM =
        "You are FilmYab's fusion engine. You get the scraped Instagram evidence, candidate movies from a " +
            "previous pass, and Wikipedia evidence for each candidate. Choose the single best matching movie " +
            "and produce the final answer. Reply ONLY with minified JSON, no markdown, exactly: " +
            "{\"found\":true,\"title_en\":\"\",\"title_fa\":\"\",\"year\":\"\",\"director\":\"\"," +
            "\"genres\":[\"\"],\"confidence\":0.0,\"reasoning_fa\":\"\"}. " +
            "confidence is 0..1 and must reflect how certain the combined evidence really is. " +
            "If the evidence contradicts all candidates set found=false."

    private fun askLlmFusion(
        verified: List<VerifiedCandidate>,
        ai: AiAnalysis,
        post: InstagramPost,
        ocrText: String
    ): Fused? {
        val sb = StringBuilder()
        sb.append("Instagram evidence:\n")
        post.ogTitle?.let { sb.append("Page title: ").append(it.take(160)).append("\n") }
        sb.append("Caption: ").append(post.caption?.take(500) ?: "(none)").append("\n")
        if (ocrText.isNotBlank()) sb.append("OCR: ").append(ocrText.take(500)).append("\n")
        sb.append("\nAI candidates:\n")
        ai.candidates.forEachIndexed { i, c ->
            sb.append(i + 1).append(". ").append(c.name ?: "?")
                .append(" (").append(c.year ?: "?").append(") — ")
                .append(c.reason ?: "").append("\n")
        }
        sb.append("\nWikipedia evidence:\n")
        verified.forEachIndexed { i, v ->
            sb.append(i + 1).append(". ").append(v.wiki?.pageTitle ?: "(not verified)")
            v.wiki?.pageDescription?.let { sb.append(" | ").append(it) }
            sb.append(" | engine score=").append(String.format(Locale.US, "%.2f", v.score))
            sb.append("\n")
        }
        val content = callLlm(
            FUSION_SYSTEM, sb.toString(),
            maxRateLimitWaits = 1,
            totalBudgetMs = 70_000L
        ) ?: return null
        val obj = extractJson(content) ?: return null
        val genres = mutableListOf<String>()
        val g = obj.get("genres")
        if (g != null && g.isJsonArray) {
            for (el in g.asJsonArray) {
                val s = el.takeIf { it.isJsonPrimitive }?.asString?.trim()
                if (!s.isNullOrBlank() && genres.size < 5) genres.add(s)
            }
        }
        return Fused(
            found = optBool(obj, "found"),
            titleEn = optString(obj, "title_en"),
            titleFa = optString(obj, "title_fa"),
            year = optString(obj, "year"),
            director = optString(obj, "director"),
            genres = genres,
            confidence = optDouble(obj, "confidence").coerceIn(0.0, 1.0),
            reasoningFa = optString(obj, "reasoning_fa")
        )
    }

    private fun blend(base: SmartResult, fused: Fused): SmartResult {
        if (!fused.found) {
            val capped = minOf(base.confidence, 0.55)
            return base.copy(
                confidence = capped,
                status = if (capped >= 0.55) "success" else "low_confidence"
            )
        }
        val sameTitle = normalizedKey(fused.titleEn ?: "").isNotEmpty() &&
            normalizedKey(fused.titleEn ?: "") == normalizedKey(base.titleEn ?: "")
        return if (sameTitle) {
            val conf = (0.6 * base.confidence + 0.4 * fused.confidence).coerceIn(0.0, 0.97)
            base.copy(
                confidence = conf,
                titleFa = base.titleFa ?: fused.titleFa,
                year = base.year ?: fused.year,
                director = base.director ?: fused.director,
                genres = if (fused.genres.isNotEmpty()) fused.genres else base.genres,
                reasoningFa = listOfNotNull(base.reasoningFa, fused.reasoningFa)
                    .joinToString(" — "),
                status = if (conf >= 0.55) "success" else "low_confidence"
            )
        } else if (fused.confidence > base.confidence) {
            val conf = (fused.confidence * 0.85).coerceIn(0.0, 0.9)
            SmartResult(
                status = if (conf >= 0.55) "success" else "low_confidence",
                titleEn = fused.titleEn,
                titleFa = fused.titleFa,
                year = fused.year ?: base.year,
                director = fused.director,
                genres = fused.genres,
                confidence = conf,
                reasoningFa = fused.reasoningFa,
                posterUrl = base.posterUrl,
                wikiUrl = base.wikiUrl,
                googleQuery = googleQueryFor(fused.titleEn, fused.year),
                candidates = base.candidates,
                sceneSummary = base.sceneSummary,
                evidenceSummary = base.evidenceSummary,
                instagramUrl = base.instagramUrl,
                sourceMode = base.sourceMode
            )
        } else {
            val capped = minOf(base.confidence, 0.7)
            base.copy(
                confidence = capped,
                status = if (capped >= 0.55) "success" else "low_confidence"
            )
        }
    }

    private fun JsonObject.optStringSafe(key: String): String? {
        val el = this.get(key) ?: return null
        if (el.isJsonNull || !el.isJsonPrimitive) return null
        return try {
            el.asString.trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}
