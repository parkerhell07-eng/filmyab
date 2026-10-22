package com.filmyab.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * FilmYab v3 on-device SMART ENGINE (no server, no keys, no settings).
 *
 * v3 — built around the two complaints that v2.1 had:
 *  A) "گیر می‌کند / پاسخ نداد": the free keyless LLM gateway enforces
 *     "1 reply every 30 seconds per IP". On shared VPN exit IPs almost every
 *     first call answers 402/429 and v2 waited 30-40s twice with a 150s budget,
 *     then aborted — that was the "smart analysis service did not respond" error.
 *  B) low accuracy + slow: v2 was text-only (never saw the clip), sampled just
 *     3 frames, verified candidates serially, and always ran a second LLM pass.
 *
 * v3 design:
 *  1. net     — parallel VPN/connectivity probes (2-6s).
 *  2. fetch   — unchanged parallel Instagram scraping (4 methods).
 *  3. analyze — video capped at 45MB (truncated files still usable), 7 sampled
 *               frames (title cards/credits are usually near start or end),
 *               on-device OCR over all frames, thumbnail OCR fallback.
 *  4. match   — THREE parallel channels:
 *               C1 deterministic: og-title/hashtags/caption/OCR → Wikipedia.
 *               C2 keyless web evidence: Bing/DDG HTML search of the strongest
 *                  signals (works with VPN; validated) → real page titles that
 *                  usually name the exact movie.
 *               C3 free online AI (Pollinations openai-fast, keyless): gets ALL
 *                  evidence including C2 snippets → ranked candidates.
 *               Rate-limit aware: honors Retry-After with ONE polite wait, then
 *               degrades to C1+C2 instead of hanging. A "cooling window" pre-wait
 *               avoids burning attempts when the previous LLM call just finished.
 *  5. verify  — candidates verified in PARALLEL (semaphore 3) against Wikipedia,
 *               then enriched from Wikidata (keyless): genres, cast, director,
 *               year, Persian title, Persian plot, poster.
 *  6. final   — self-built scoring; the second LLM pass (fusion) runs ONLY when
 *               the score is not decisive and shares the same cooling window.
 *
 * Every LLM/network loop checks coroutine cancellation, so the user can press
 * "لغو" (cancel) at any moment. The whole pipeline has a hard wall-clock budget.
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

    /** One parsed web-search hit used as evidence for the AI channel. */
    private data class WebHit(
        val title: String,
        val snippet: String?,
        val url: String?
    )

    /** Structured details resolved from Wikidata for one film. */
    private data class WikidataDetails(
        val director: String?,
        val genres: List<String>,
        val cast: List<String>
    )

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Wikimedia throttles generic browser UAs from datacenter/VPN IPs — a descriptive
     *  UA per their UA policy gets served reliably. */
    private const val WIKI_UA = "FilmYabEngine/3.0 (Android; movie identification; contact: filmyab.app)"

    private const val POLLINATIONS_URL = "https://text.pollinations.ai/openai"

    /** Free anonymous model on the Classic API (text-only). */
    private const val LLM_MODEL = "openai-fast"

    /** Identifies this app to the free AI gateway (raises anonymous quota). */
    private const val REFERRER = "filmyab"

    /** Anonymous quota of the free gateway: 1 fresh reply every ~30s per IP. */
    private const val RATE_WINDOW_MS = 30_500L

    /** Hard cap on the video download. Instagram reels are normally < 30MB; if the
     *  file is bigger we keep the first 45MB — MediaMetadataRetriever can still read
     *  the early frames of a truncated progressive MP4, so nothing is wasted. */
    private const val MAX_VIDEO_BYTES = 45L * 1024 * 1024

    /** Whole-pipeline wall-clock budget; guarantees the UI never hangs forever. */
    private const val TOTAL_BUDGET_MS = 200_000L

    @Volatile private var lastLlmTouchMs = 0L

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val quickHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /* ================= 1. connectivity ================= */

    suspend fun checkNetwork(): NetState = withContext(Dispatchers.IO) {
        var igOk = false
        var gstaticOk = false
        coroutineScope {
            val igJob = async { reachable("https://www.instagram.com/robots.txt") }
            val gsJob = async { reachable("https://www.gstatic.com/generate_204") }
            igOk = igJob.await()
            gstaticOk = gsJob.await()
        }
        val internet = igOk || gstaticOk ||
            reachable("https://en.wikipedia.org/w/rest.php/v1/search/title?q=a&limit=1")
        when {
            igOk -> NetState("ok", instagramOk = true, internetOk = true)
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
    ): Outcome {
        val outcome: Outcome? = withTimeoutOrNull(TOTAL_BUDGET_MS) {
            runPipeline(appContext, rawUrl, onStep)
        }
        return outcome ?: Outcome.Fail(
            "عملیات بیش از حد طول کشید و متوقف شد. اتصال را بررسی کن و دوباره تلاش کن — معمولاً بار دوم سریع‌تر و موفق‌تر است."
        )
    }

    private suspend fun runPipeline(
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
            val frames = extractFrames(appContext, videoUrl, 7)
            if (frames.isNotEmpty()) {
                ocrText = runOcr(frames)
                onStep(STEP_ANALYZE, "done")
            } else {
                onStep(STEP_ANALYZE, "skip")
            }
        } else {
            // Image post (or video URL missing) — read the cover/thumbnail instead.
            val thumb = post.thumbnailUrl?.let { downloadImage(it) }
            if (thumb != null) {
                ocrText = runOcr(listOf(thumb))
                onStep(STEP_ANALYZE, "done")
            } else {
                onStep(STEP_ANALYZE, "skip")
            }
        }

        /* ---- 4. match: three parallel channels ---- */
        onStep(STEP_MATCH, "start")

        var direct: List<Candidate> = emptyList()
        var web: List<WebHit> = emptyList()
        coroutineScope {
            val directJob = async { directEvidenceCandidates(post, ocrText) }
            val webJob = async {
                try {
                    webSearchEvidence(buildWebQueries(post, ocrText))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList<WebHit>()
                }
            }
            direct = directJob.await()
            web = webJob.await()
        }

        val ai = askLlmCandidates(post, ocrText, web)

        // If the free AI is rate-limited/unavailable, turn top web titles into
        // candidates directly — they still get hard verification in step 5.
        val webFallback: List<Candidate> = if (ai == null) webTitleCandidates(web) else emptyList()

        val merged = mergeCandidates(
            llm = ai?.candidates ?: emptyList(),
            direct = direct,
            web = webFallback
        )
        if (merged.isEmpty()) {
            onStep(STEP_MATCH, "error")
            if (ai == null) {
                return@withContext Outcome.Fail(
                    "سرویس هوش مصنوعی رایگان الان محدود است (حدود ۱ درخواست در ۳۰ ثانیه برای هر IP) و از شواهد مستقیم و جستجوی وب هم فیلمی پیدا نشد. حدود ۳۰ ثانیه صبر کن و دوباره بزن — بار دوم معمولاً جواب می‌دهد."
                )
            }
            val extra = ai.sceneSummary?.let { " توضیح صحنه: $it" } ?: ""
            return@withContext Outcome.Fail(
                "با شواهد موجود (کپشن، هشتگ، متن روی تصویر و وب) نتونستم فیلمی رو با اطمینان پیدا کنم.$extra"
            )
        }
        onStep(STEP_MATCH, "done")
        val effective = AiAnalysis(
            found = true,
            sceneSummary = ai?.sceneSummary,
            candidates = merged
        )

        /* ---- 5. verify: Wikipedia + Wikidata, parallel ---- */
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
        if (best.score < 0.75) {
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
            // Spread across the clip; title cards live at the start, credits at the end.
            val fracs = doubleArrayOf(0.05, 0.18, 0.32, 0.45, 0.58, 0.71, 0.86)
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

    /** Decodes an image URL into a bitmap scaled to max 1280px (thumbnail/cover). */
    private fun downloadImage(url: String): Bitmap? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://www.instagram.com/")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val bytes = resp.body?.bytes() ?: return@use null
            try {
                val opts = BitmapFactory.Options()
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var sample = 1
                val maxDim = maxOf(opts.outWidth, opts.outHeight)
                while (maxDim / (sample * 2) >= 1280) sample *= 2
                val decodeOpts = BitmapFactory.Options()
                decodeOpts.inSampleSize = sample
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            } catch (e: Exception) {
                null
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Streams the video into cache with a hard size cap. Returns the (possibly
     * truncated) file as long as enough bytes arrived; null only on hard failure.
     */
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
                val wrote = body.byteStream().use { input ->
                    f.outputStream().use { output -> copyCapped(input, output, MAX_VIDEO_BYTES) }
                }
                if (!wrote || f.length() < 512_000) {
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

    /**
     * Copies up to [cap] bytes. Returns true when at least 512KB was written.
     * When the stream exceeds the cap we stop cleanly and keep the partial file
     * (progressive MP4s keep their moov header at the front, so early frames of a
     * truncated file are usually still decodable).
     */
    private fun copyCapped(input: java.io.InputStream, output: java.io.OutputStream, cap: Long): Boolean {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                total += n
                if (total >= cap) break
            }
            return total >= 512_000L
        } catch (e: Exception) {
            return total >= 512_000L
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
        lines.take(18).joinToString(" | ").take(1600)
    }

    /* ================= LLM channel (keyless, rate-limit aware) ================= */

    /**
     * Bulletproof keyless LLM channel for the free gateway.
     *
     * The gateway enforces "1 fresh reply every 30 seconds per IP"; on shared VPN
     * exit IPs the first attempt usually answers 402/429, and Cloudflare sometimes
     * answers with an HTML challenge. v2 burned up to 150s here and then surfaced
     * "پاسخ نداد". v3:
     *  - pre-waits the remaining cooling window before the first attempt (no wasted calls);
     *  - honors Retry-After with at most [maxRateLimitWaits] polite waits;
     *  - treats HTML/error pages/parse failures as retryable, never fatal;
     *  - hard wall-clock [totalBudgetMs]; cancellation-aware at every iteration;
     *  - the caller degrades to the deterministic/web channels when this returns null.
     */
    private suspend fun callLlm(
        system: String,
        user: String,
        maxRateLimitWaits: Int = 1,
        totalBudgetMs: Long = 85_000L
    ): String? {
        val deadline = System.currentTimeMillis() + totalBudgetMs
        var rateWaits = 0
        var hardFails = 0

        // Cooling window: if a previous LLM call finished less than ~30s ago, wait
        // out the remainder instead of burning the first attempt on a guaranteed 402.
        val sinceLast = System.currentTimeMillis() - lastLlmTouchMs
        if (lastLlmTouchMs > 0 && sinceLast < RATE_WINDOW_MS) {
            delay(RATE_WINDOW_MS - sinceLast)
        }

        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            try {
                val payload = JsonObject()
                payload.addProperty("model", LLM_MODEL)
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
                lastLlmTouchMs = System.currentTimeMillis()
                http.newCall(request).execute().use { resp ->
                    when {
                        resp.code == 402 || resp.code == 429 -> {
                            if (rateWaits >= maxRateLimitWaits) return null
                            rateWaits++
                            val ra = resp.header("Retry-After")?.trim()?.toLongOrNull()
                            delay(((ra ?: 30L).coerceIn(5L, 40L)) * 1000L + 1500L)
                        }
                        resp.code >= 500 -> {
                            hardFails++
                            if (hardFails > 3) return null
                            delay(3000L)
                        }
                        !resp.isSuccessful -> {
                            hardFails++
                            if (hardFails > 3) return null
                            delay(1500L)
                        }
                        else -> {
                            val bodyStr = resp.body?.string() ?: ""
                            val content = extractLlmContent(bodyStr)
                            if (content != null && content.contains('{') && content.contains('}')) {
                                return content
                            }
                            hardFails++
                            if (hardFails > 4) return null
                            delay(1200L)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hardFails++
                if (hardFails > 4) return null
                delay(2500L)
            }
        }
        return null
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

    /* ---- step 4: channels ---- */

    private const val CANDIDATES_SYSTEM =
        "You are FilmYab, a movie identification engine. You receive evidence about an Instagram reel/post: " +
            "caption, hashtags, uploader, page og-title, text read from video frames by on-device OCR " +
            "(may contain noise, watermarks, subtitle lines or title cards), and REAL web-search results " +
            "fetched live for the strongest signals of the post. " +
            "Task: name the exact movie or series the clip is from. The og-title, a title card in OCR, or a " +
            "web-result title often contains the answer — prefer the most specific and consistent evidence " +
            "across sources. Reply ONLY with minified JSON, no markdown, exactly this schema: " +
            "{\"scene_summary_fa\":\"یک جمله کوتاه فارسی درباره صحنه\"," +
            "\"candidates\":[{\"title\":\"original English title\",\"year\":\"\",\"confidence\":0.0,\"reason_fa\":\"دلیل کوتاه فارسی\"}]," +
            "\"found\":true}. " +
            "Rules: at most 3 candidates ordered by likelihood; confidence is 0..1 and reflects your " +
            "certainty for each candidate; year=\"\" if unsure; " +
            "if the evidence is too weak to name any movie, set found=false and candidates=[]."

    private suspend fun askLlmCandidates(
        post: InstagramPost,
        ocrText: String,
        web: List<WebHit>
    ): AiAnalysis? {
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
        if (web.isNotEmpty()) {
            sb.append("\nLive web results for the strongest signals of this post:\n")
            for (hit in web.take(6)) {
                sb.append("- ").append(hit.title.take(120))
                hit.snippet?.let { sb.append(" — ").append(it.take(160)) }
                sb.append("\n")
            }
        }
        val content = callLlm(
            CANDIDATES_SYSTEM, sb.toString(),
            maxRateLimitWaits = 1,
            totalBudgetMs = 85_000L
        ) ?: return null
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

    private val persianRegex = Regex("""[\u0600-\u06FF]""")

    private fun looksMeaningful(t: String, minRatio: Double): Boolean {
        val letters = t.count { it.isLetter() }
        return t.length >= 4 && letters >= 4 && letters >= t.length * minRatio
    }

    /**
     * Deterministic, keyless candidate finder that never depends on the AI service:
     * it turns the strongest signals of the post (page title, hashtags, caption lines,
     * OCR title cards) into Wikipedia film searches. Wiki searches run in parallel.
     * Results are verified again later by [verifyCandidates], so false hits get
     * filtered with hard evidence.
     */
    private suspend fun directEvidenceCandidates(post: InstagramPost, ocrText: String): List<Candidate> =
        withContext(Dispatchers.IO) {
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
                if (t.length in 4..60 && looksMeaningful(t, 0.6)) {
                    queries.add(t)
                }
            }

            if (ocrText.isNotBlank()) {
                ocrText.split(" | ").forEach { raw ->
                    val t = raw.trim()
                    if (t.length in 4..70 && looksMeaningful(t, 0.5)) {
                        queries.add(t)
                    }
                }
            }

            val perQuery: List<List<Candidate>> = coroutineScope {
                val sem = Semaphore(3)
                queries.take(6).map { q ->
                    async {
                        sem.withPermit {
                            searchWikiForCandidates(q)
                        }
                    }
                }.awaitAll()
            }

            val out = mutableListOf<Candidate>()
            val seenTitles = mutableSetOf<String>()
            for (list in perQuery) {
                for (c in list) {
                    val key = normalizedKey(c.name ?: continue)
                    if (key.isEmpty() || seenTitles.contains(key)) continue
                    seenTitles.add(key)
                    out.add(c)
                    if (out.size >= 4) return@withContext out
                }
            }
            out
        }

    /** One Wikipedia search → up to 2 film-looking candidates. */
    private fun searchWikiForCandidates(q: String): List<Candidate> {
        val lang = if (persianRegex.containsMatchIn(q)) "fa" else "en"
        val pages: List<WikiPage> = try {
            wikiSearch(lang, q, 5)
        } catch (e: Exception) {
            emptyList()
        }
        if (pages.isEmpty()) return emptyList()
        val filmPages = pages.filter { looksLikeFilmDescription(it.description) }
        val exact = pages.firstOrNull { normalizedKey(it.title) == normalizedKey(q) }
        val chosen = (filmPages + listOfNotNull(exact)).distinctBy { it.key }.take(2)
        return chosen.map { p ->
            Candidate(
                name = p.title,
                year = p.description?.let { yearRegex.find(it)?.value },
                confidence = 0.5,
                reason = "از نشانه‌های خود پست (هشتگ/عنوان/متن تصویر) در پایگاه فیلم پیدا شد"
            )
        }
    }

    /* ---- step 4c: keyless web evidence (Bing, DDG fallback) ---- */

    /** Strongest 2-3 textual signals of the post, ordered. */
    private fun buildWebQueries(post: InstagramPost, ocrText: String): List<String> {
        val queries = LinkedHashSet<String>()
        post.ogTitle?.let { t -> cleanOgTitleForQuery(t)?.let { queries.add(it) } }
        if (ocrText.isNotBlank()) {
            ocrText.split(" | ")
                .filter { looksMeaningful(it.trim(), 0.5) }
                .take(2)
                .forEach { queries.add(it.trim()) }
        }
        for (tag in post.hashtags.take(6)) {
            val base = tag.removePrefix("#").trim()
            if (base.length < 3 || base.lowercase(Locale.US) in GENERIC_TAGS) continue
            val spaced = splitCamelCase(base.replace('_', ' ').replace('-', ' ').trim())
            queries.add(spaced)
            if (queries.size >= 3) break
        }
        post.caption?.take(400)?.split("\n")?.forEach { raw ->
            val t = raw.trim()
            if (queries.size < 3 && t.length in 8..60 && looksMeaningful(t, 0.6)) {
                queries.add(t)
            }
        }
        return queries.take(3).toList()
    }

    /** Runs up to 2 Bing searches in parallel and merges distinct hits. */
    private suspend fun webSearchEvidence(queries: List<String>): List<WebHit> =
        withContext(Dispatchers.IO) {
            if (queries.isEmpty()) return@withContext emptyList()
            val perQuery: List<List<WebHit>> = coroutineScope {
                queries.take(2).map { q ->
                    async {
                        val bing = try {
                            bingSearch(q)
                        } catch (e: Exception) {
                            emptyList()
                        }
                        if (bing.isNotEmpty()) bing else try {
                            ddgSearch(q)
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                }.awaitAll()
            }
            val out = mutableListOf<WebHit>()
            val seen = mutableSetOf<String>()
            for (list in perQuery) {
                for (hit in list) {
                    val key = normalizedKey(hit.title)
                    if (key.isEmpty() || seen.contains(key)) continue
                    seen.add(key)
                    out.add(hit)
                    if (out.size >= 8) return@withContext out
                }
            }
            out
        }

    /** Scrapes Bing web results (validated: <li class="b_algo"> with h2>a title + p snippet). */
    private fun bingSearch(query: String): List<WebHit> {
        val q = URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url("https://www.bing.com/search?q=$q&count=12&setlang=en")
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val html = resp.body?.string() ?: return emptyList()
            val out = mutableListOf<WebHit>()
            val chunkRegex = Regex("""<li class="b_algo"[\s\S]*?</li>""")
            for (m in chunkRegex.findAll(html).take(10)) {
                val chunk = m.value
                val title = stripTags(
                    Regex("""<h2[^>]*>\s*<a[^>]*>([\s\S]*?)</a>""").find(chunk)?.groupValues?.get(1) ?: ""
                ).trim()
                if (title.length < 3) continue
                val snippet = stripTags(
                    Regex("""<p[^>]*>([\s\S]*?)</p>""").find(chunk)?.groupValues?.get(1) ?: ""
                ).trim().take(220)
                val href = Regex("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"").find(chunk)?.groupValues?.get(1)
                out.add(WebHit(title, snippet.ifBlank { null }, href))
                if (out.size >= 6) break
            }
            return out
        }
    }

    /** DuckDuckGo HTML fallback (sometimes 403s from VPN IPs — best effort). */
    private fun ddgSearch(query: String): List<WebHit> {
        val q = URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url("https://html.duckduckgo.com/html/?q=$q")
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val html = resp.body?.string() ?: return emptyList()
            val out = mutableListOf<WebHit>()
            val titleRegex = Regex("""<a[^>]*class="result__a"[^>]*>([\s\S]*?)</a>""")
            val snippetRegex = Regex("""<a[^>]*class="result__snippet"[^>]*>([\s\S]*?)</a>""")
            val titles = titleRegex.findAll(html).toList()
            val snippets = snippetRegex.findAll(html).toList()
            for (i in titles.indices) {
                val title = stripTags(titles[i].groupValues[1]).trim()
                if (title.length < 3) continue
                val snippet = if (i < snippets.size) {
                    stripTags(snippets[i].groupValues[1]).trim().take(220)
                } else {
                    ""
                }
                out.add(WebHit(title, snippet.ifBlank { null }, null))
                if (out.size >= 6) break
            }
            return out
        }
    }

    private fun stripTags(html: String): String = html
        .replace(Regex("<[^>]+>"), "")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Junk tokens that litter streaming/piracy page titles — dropped whole-token. */
    private val webJunkTokens = setOf(
        "watch", "online", "free", "full", "stream", "streaming", "download",
        "putlocker", "soap2day", "123movies", "fmovies", "solarmovie", "hd", "4k",
        "bluray", "dvd", "sub", "subs", "subtitle", "subtitles", "dub", "dubbed",
        "film", "movie", "movies", "cinema", "scene", "scenes", "clip", "clips",
        "official", "trailer", "teaser", "video", "en", "the", "a", "an", "of",
        "دانلود", "فیلم", "زیرنویس", "آنلاین", "رایگان", "کامل", "دوبله", "سریال"
    )

    /**
     * Last-resort candidates mined from web result titles when the AI service is
     * rate-limited. These still pass through hard Wikipedia verification, so junk
     * streaming-site titles get filtered with evidence.
     */
    private fun webTitleCandidates(web: List<WebHit>): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (hit in web) {
            if (out.size >= 2) break
            var t = hit.title
            // drop the trailing site/page segment after common separators
            t = t.replace(Regex("""\s*[|\-–—•]\s*[^|\-–—•]{0,40}$"""), "").trim()
            t = t.split(Regex("[\\s:,()\\[\\]]+"))
                .filter { it.isNotBlank() && it.lowercase(Locale.US) !in webJunkTokens }
                .joinToString(" ").trim()
            if (t.length < 3 || t.length > 60) continue
            if (!looksMeaningful(t, 0.5)) continue
            val year = yearRegex.find(hit.title)?.value
            out.add(
                Candidate(
                    name = t,
                    year = year,
                    confidence = 0.45,
                    reason = "از نتایج جستجوی وب برای شواهد این پست"
                )
            )
        }
        return out
    }

    /** LLM candidates first, then evidence-path and web-fallback candidates, capped at 5. */
    private fun mergeCandidates(
        llm: List<Candidate>,
        direct: List<Candidate>,
        web: List<Candidate>
    ): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val seen = mutableSetOf<String>()
        for (c in llm + direct + web) {
            val name = c.name ?: continue
            val key = normalizedKey(name)
            if (key.isEmpty() || seen.contains(key)) continue
            seen.add(key)
            out.add(c)
            if (out.size >= 5) break
        }
        return out
    }

    /* ---- step 5: Wikipedia + Wikidata verification (keyless, parallel) ---- */

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
                            Thread.sleep(2000L)
                            return@use null // retry once on throttle
                        }
                        return@use emptyList<WikiPage>()
                    }
                    val bodyStr = resp.body?.string() ?: return@use emptyList<WikiPage>()
                    val root = JsonParser.parseString(bodyStr).asJsonObject
                    val pages = root.getAsJsonArray("pages") ?: return@use emptyList<WikiPage>()
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
                Thread.sleep(2000L)
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
                            Thread.sleep(2000L)
                            return@use null // retry once on throttle
                        }
                        return@use null
                    }
                    val bodyStr = resp.body?.string() ?: return@use null
                    return JsonParser.parseString(bodyStr).asJsonObject
                }
            } catch (e: Exception) {
                if (attempt == 1) return null
                Thread.sleep(2000L)
            }
        }
        return null
    }

    private val filmDescRegex = Regex("""(?i)\b(film|movie|series|television|miniseries)\b""")
    private val yearRegex = Regex("""(19|20)\d{2}""")
    private val directorRegex = Regex("""\bby ([A-Z][^,.;()]{2,40})""")

    private fun looksLikeFilmDescription(description: String?): Boolean {
        val d = description ?: return false
        return filmDescRegex.containsMatchIn(d) || d.contains("فیلم") || d.contains("سریال")
    }

    private suspend fun verifyCandidates(
        candidates: List<Candidate>,
        post: InstagramPost,
        ocrText: String
    ): List<VerifiedCandidate> = withContext(Dispatchers.IO) {
        // Deduplicate first (cheap, sequential) so parallel jobs never repeat work.
        val deduped = mutableListOf<Candidate>()
        val seen = mutableSetOf<String>()
        for (c in candidates) {
            val title = c.name ?: continue
            val key = normalizedKey(title)
            if (key.isEmpty() || seen.contains(key)) continue
            seen.add(key)
            deduped.add(c)
            if (deduped.size >= 4) break
        }

        val results: List<VerifiedCandidate?> = coroutineScope {
            val sem = Semaphore(3)
            deduped.map { c ->
                async {
                    sem.withPermit {
                        try {
                            verifyOne(c, post, ocrText)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }.awaitAll()
        }
        results.filterNotNull().sortedByDescending { it.score }
    }

    private suspend fun verifyOne(
        c: Candidate,
        post: InstagramPost,
        ocrText: String
    ): VerifiedCandidate? {
        val title = c.name ?: return null
        val key = normalizedKey(title)
        val wiki: WikiEvidence? = if (persianRegex.containsMatchIn(title)) {
            // Persian candidate (from fa hashtags/caption) — verify on fa-wiki directly
            val pages = wikiSearch("fa", title, 5)
            val chosen = pages.firstOrNull { looksLikeFilmDescription(it.description) }
                ?: pages.firstOrNull { normalizedKey(it.title) == key }
            chosen?.let { buildWikiEvidence(it) }
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
            chosen?.let { buildWikiEvidence(it) }
        }
        val score = scoreCandidate(c, post, ocrText, wiki)
        return if (wiki != null || score >= 0.5) VerifiedCandidate(c, wiki, score) else null
    }

    /* ================= Wikidata enrichment (keyless) ================= */

    /** Extracts the wikibase item id from a Wikipedia summary payload. */
    private fun wikibaseIdOf(summary: JsonObject): String? = optString(summary, "wikibase_item")

    /**
     * Resolves director (P57), genres (P136) and cast (P161) labels from Wikidata
     * with two keyless calls: claims for the film entity, then one batched label
     * lookup for all referenced items.
     */
    private fun fetchWikidataDetails(qid: String): WikidataDetails? {
        try {
            val claimsUrl = "https://www.wikidata.org/w/api.php?action=wbgetentities" +
                "&ids=" + URLEncoder.encode(qid, "UTF-8") +
                "&props=claims&format=json&formatversion=2"
            val claimsRoot: JsonObject = getJson(claimsUrl, WIKI_UA) ?: return null
            val entity = claimsRoot.getAsJsonObject("entities")
                ?.takeIf { it.isJsonObject }
                ?.getAsJsonObject(qid) ?: return null
            val claims = entity.getAsJsonObject("claims") ?: return null

            val claimIds = { prop: String, max: Int ->
                val ids = mutableListOf<String>()
                val arr = claims.getAsJsonArray(prop)
                if (arr != null && arr.isJsonArray) {
                    for (el in arr) {
                        if (ids.size >= max) break
                        val snak = el.takeIf { it.isJsonObject }?.asJsonObject
                            ?.getAsJsonObject("mainsnak") ?: continue
                        val dv = snak.getAsJsonObject("datavalue") ?: continue
                        val value = dv.get("value") ?: continue
                        if (value.isJsonObject) {
                            val id = value.asJsonObject.get("id")
                            if (id != null && !id.isJsonNull) {
                                val s = id.asString
                                if (s.isNotBlank()) ids.add(s)
                            }
                        }
                    }
                }
                ids
            }

            val directorIds = claimIds("P57", 2)
            val genreIds = claimIds("P136", 3)
            val castIds = claimIds("P161", 5)
            val all = (directorIds + genreIds + castIds).distinct()
            if (all.isEmpty()) return WikidataDetails(null, emptyList(), emptyList())

            val labelsUrl = "https://www.wikidata.org/w/api.php?action=wbgetentities" +
                "&ids=" + URLEncoder.encode(all.joinToString("|"), "UTF-8") +
                "&props=labels&languages=fa|en&format=json&formatversion=2"
            val labelsRoot: JsonObject = getJson(labelsUrl, WIKI_UA) ?: return null
            val labelEntities = labelsRoot.getAsJsonObject("entities") ?: return null

            fun labelOf(id: String): String? {
                val ent = labelEntities.getAsJsonObject(id) ?: return null
                val labels = ent.getAsJsonObject("labels") ?: return null
                val fa = labels.getAsJsonObject("fa")?.optStringSafe("value")
                return fa ?: labels.getAsJsonObject("en")?.optStringSafe("value")
            }

            val director = directorIds.firstNotNullOfOrNull { id -> labelOf(id) }
            val genres = genreIds.mapNotNull { id -> labelOf(id) }
            val cast = castIds.mapNotNull { id -> labelOf(id) }
            return WikidataDetails(director, genres, cast)
        } catch (e: Exception) {
            return null
        }
    }

    /** Plain GET + JSON parse helper (no retries — enrichment is best-effort). */
    private fun getJson(url: String, ua: String): JsonObject? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else {
                val bodyStr = resp.body?.string() ?: null
                if (bodyStr.isNullOrBlank()) null else {
                    try {
                        JsonParser.parseString(bodyStr).asJsonObject
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Short plot text from a summary extract (first 2 sentences). */
    private fun plotFromExtract(extract: String?): String? {
        if (extract.isNullOrBlank()) return null
        val clean = extract.replace(Regex("\\s+"), " ").trim()
        val sentences = clean.split(". ")
        val plot = if (sentences.size >= 2) {
            sentences.take(2).joinToString(". ").let { if (!it.endsWith(".")) "$it." else it }
        } else {
            clean
        }
        return plot.take(400).takeIf { it.length >= 40 }
    }

    private suspend fun buildWikiEvidence(page: WikiPage): WikiEvidence? {
        val summary = wikiSummary(page.lang, page.key) ?: return null
        val description = optString(summary, "description")
        val extract = optString(summary, "extract") ?: ""
        val hay = listOfNotNull(description, extract.take(400)).joinToString(" ")
        val isFilm = filmDescRegex.containsMatchIn(hay) ||
            hay.contains("فیلم") || hay.contains("سریال")
        if (!isFilm) return null

        val year = description?.let { yearRegex.find(it)?.value }
            ?: yearRegex.find(extract.take(200))?.value
        val directorFromDesc = description?.let { directorRegex.find(it)?.groupValues?.get(1)?.trim() }
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
        val enPlot = plotFromExtract(extract)

        // Parallel: Persian title (+ Persian plot) and Wikidata details.
        var faTitle: String? = null
        var faPlot: String? = null
        var details: WikidataDetails? = null
        coroutineScope {
            val faJob = async {
                if (page.lang == "en") {
                    resolveFaTitleAndPlot(page.title)
                } else {
                    null
                }
            }
            val detailsJob = async {
                val qid = wikibaseIdOf(summary)
                if (qid != null) {
                    try {
                        fetchWikidataDetails(qid)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
            }
            val fa = faJob.await()
            faTitle = fa?.first
            faPlot = fa?.second
            details = detailsJob.await()
        }
        if (page.lang != "en") {
            faTitle = page.title
        }

        return WikiEvidence(
            pageTitle = page.title,
            pageDescription = description,
            year = year,
            director = directorFromDesc ?: details?.director,
            genres = details?.genres ?: emptyList(),
            cast = details?.cast ?: emptyList(),
            plot = faPlot ?: enPlot,
            pageUrl = pageUrl,
            posterUrl = poster,
            faTitle = faTitle,
            isFilm = true
        )
    }

    /** fa-wiki search for the film's Persian title, plus its Persian plot when found. */
    private fun resolveFaTitleAndPlot(enTitle: String): Pair<String, String?>? {
        val pages = try {
            wikiSearch("fa", enTitle, 3)
        } catch (e: Exception) {
            return null
        }
        for (fp in pages) {
            val d = fp.description ?: ""
            if (d.contains("فیلم") || d.contains("سریال")) {
                val faSummary = wikiSummary("fa", fp.key)
                val plot = plotFromExtract(faSummary?.optStringSafe("extract"))
                return Pair(fp.title, plot)
            }
        }
        return null
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
        if (w != null) evidenceBits.add("ویکی‌پدیا و ویکی‌داده")

        return SmartResult(
            status = if (conf >= 0.55) "success" else "low_confidence",
            titleEn = titleEn,
            titleFa = w?.faTitle,
            year = year,
            director = w?.director,
            genres = w?.genres ?: emptyList(),
            cast = w?.cast ?: emptyList(),
            plot = w?.plot,
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

    private suspend fun askLlmFusion(
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
                genres = (base.genres + fused.genres).distinct().take(4),
                reasoningFa = listOfNotNull(base.reasoningFa, fused.reasoningFa)
                    .joinToString(" — "),
                status = if (conf >= 0.55) "success" else "low_confidence"
            )
        } else if (fused.confidence > base.confidence) {
            // The fusion picked a DIFFERENT movie — poster/plot/cast of the old pick
            // no longer belong to the answer.
            val conf = (fused.confidence * 0.85).coerceIn(0.0, 0.9)
            SmartResult(
                status = if (conf >= 0.55) "success" else "low_confidence",
                titleEn = fused.titleEn,
                titleFa = fused.titleFa,
                year = fused.year ?: base.year,
                director = fused.director,
                genres = fused.genres,
                cast = emptyList(),
                plot = null,
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
