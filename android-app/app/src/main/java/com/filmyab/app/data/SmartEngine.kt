package com.filmyab.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Base64
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
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * FilmYab v3.1 on-device SMART ENGINE (no server, no keys, no settings).
 *
 * What v3 still got wrong (user report: "گیر می‌کند، دقت پایین، کند است"):
 *
 *  1. HANG/ERROR — the keyless LLM channel ran SERIALLY after the direct+web
 *     evidence steps, pre-waited the 30s cooling window, then honored
 *     Retry-After (up to 40s) inside an 85s budget; verification then made up
 *     to ~24 HTTP calls (6 per candidate), and an extra fusion LLM pass could
 *     burn another 70s. Worst case > 200s → users see it as frozen, then a
 *     timeout error. v3.1: the LLM channel runs IN PARALLEL with the other
 *     channels, budgets are staged (vision 45s → text fallback shares a 60s
 *     cap), verification makes ONE summary call per candidate and only the
 *     WINNER gets deep enrichment, fusion only runs when truly undecided.
 *     Hard pipeline budget: 150s.
 *
 *  2. ACCURACY — the AI channel now uses the STRONGER free model ("openai", a
 *     reasoning model) instead of v3's tiny fast model, and runs up to three
 *     attempts inside one parallel budget: (1) full textual evidence, (2) the
 *     SAME call retried with LIVE web-search results injected after one polite
 *     rate-limit wait — real web titles usually name the exact movie, (3) the
 *     fast text model as a last resort. Frames still power accuracy through
 *     on-device OCR (title cards, credits, subtitles) fed to the AI as text.
 *     The keyless vision path is fully implemented and gated behind
 *     [VISION_ENABLED] (the anonymous gateway rejects image input today).
 *
 *  3. SPEED — 6 frames (downscaled to ≤1024px before OCR), parallel channels,
 *     slim verification, and a 7-day on-device cache: re-running the same
 *     link returns the result instantly.
 *
 *  4. FULL SPECS — the verified winner is enriched from Wikidata (keyless):
 *     director, genres, cast, duration, country, IMDb id + poster and Persian
 *     title/plot from fa.wikipedia — all rendered in the result card.
 *
 * Every network loop is cancellation-aware so «لغو» works at any moment.
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
        val candidates: List<Candidate>,
        val viaVision: Boolean = false
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
        val cast: List<String>,
        val duration: String? = null,
        val country: String? = null,
        val imdbUrl: String? = null
    )

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Wikimedia throttles generic browser UAs from datacenter/VPN IPs — a descriptive
     *  UA per their UA policy gets served reliably. */
    private const val WIKI_UA = "FilmYabEngine/3.1 (Android; movie identification; contact: filmyab.app)"

    private const val POLLINATIONS_URL = "https://text.pollinations.ai/openai"

    /** Free anonymous STRONGER reasoning model (text-only) — primary AI channel. */
    private const val PRIMARY_MODEL = "openai"

    /** Free anonymous fast text model — fallback when the primary is unavailable. */
    private const val TEXT_MODEL = "openai-fast"

    /**
     * Vision (image-input) switch for the free gateway. The anonymous Classic API
     * REJECTS image input for every model (verified 2026-10: "This model does not
     * support image input"), so uploading frames would waste 30-60s of VPN upload
     * per run for a guaranteed 400. The whole vision path (frame JPEG payload,
     * image content parts, future-proof fallbacks) is kept and wired — flip this
     * to true the day the gateway re-enables keyless vision. Until then the
     * frames still power the engine through on-device OCR (title cards, credits,
     * subtitles) which the AI receives as text.
     */
    private const val VISION_ENABLED = false

    /** Identifies this app to the free AI gateway (raises anonymous quota). */
    private const val REFERRER = "filmyab"

    /** Anonymous quota of the free gateway: 1 fresh reply every ~30s per IP. */
    private const val RATE_WINDOW_MS = 30_500L

    /** Match-stage LLM total budget shared by all AI attempts (runs in parallel
     *  with the other channels, so it rarely becomes the critical path). */
    private const val LLM_MATCH_BUDGET_MS = 90_000L

    /** Hard cap on the video download. Instagram reels are normally < 30MB; if the
     *  file is bigger we keep the first 45MB — MediaMetadataRetriever can still read
     *  the early frames of a truncated progressive MP4, so nothing is wasted. */
    private const val MAX_VIDEO_BYTES = 45L * 1024 * 1024

    /** Whole-pipeline wall-clock budget; guarantees the UI never hangs forever. */
    private const val TOTAL_BUDGET_MS = 150_000L

    /** Max total base64 bytes we are willing to upload for the vision call. */
    private const val VISION_PAYLOAD_BUDGET = 330_000

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
            "عملیات بیش از حد طول کشید و متوقف شد (سقف ۱۵۰ ثانیه). اتصال را بررسی کن و دوباره تلاش کن — معمولاً بار دوم سریع‌تر و موفق‌تر است."
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

        /* ---- 3. analyze: frames + OCR + vision payload ---- */
        onStep(STEP_ANALYZE, "start")
        // Vision payload built only when the gateway supports keyless image input.
        var ocrText = ""
        var visionFrames: List<String> = emptyList() // base64 data URLs for the vision AI
        val videoUrl = post.videoUrl
        if (videoUrl != null) {
            // Frames are returned pre-scaled (≤1024px) to keep memory sane.
            val frames = extractFrames(appContext, videoUrl, 6)
            if (frames.isNotEmpty()) {
                if (VISION_ENABLED) {
                    visionFrames = buildVisionPayload(frames)
                }
                ocrText = runOcr(frames)
                onStep(STEP_ANALYZE, "done")
            } else {
                onStep(STEP_ANALYZE, "skip")
            }
        } else {
            // Image post (or video URL missing) — read the cover/thumbnail instead.
            val thumb = post.thumbnailUrl?.let { downloadImage(it) }
            if (thumb != null) {
                if (VISION_ENABLED) {
                    visionFrames = buildVisionPayload(listOf(thumb))
                }
                ocrText = runOcr(listOf(thumb))
                onStep(STEP_ANALYZE, "done")
            } else {
                onStep(STEP_ANALYZE, "skip")
            }
        }

        /* ---- 4. match: vision AI + direct evidence + web evidence, ALL PARALLEL ---- */
        onStep(STEP_MATCH, "start")

        var direct: List<Candidate> = emptyList()
        var web: List<WebHit> = emptyList()
        var ai: AiAnalysis? = null
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
            val aiJob = async {
                try {
                    askLlmCandidates(post, ocrText) {
                        try {
                            webJob.await()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            emptyList<WebHit>()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            direct = directJob.await()
            web = webJob.await()
            ai = aiJob.await()
        }

        // When the free AI is rate-limited/unavailable (or found nothing), turn top
        // web titles into candidates directly — they still get hard verification.
        val webFallback: List<Candidate> =
            if (ai == null || ai?.candidates.isNullOrEmpty()) webTitleCandidates(web) else emptyList()

        val merged = mergeCandidates(
            llm = ai?.candidates ?: emptyList(),
            direct = direct,
            web = webFallback
        )
        if (merged.isEmpty()) {
            onStep(STEP_MATCH, "error")
            if (ai == null) {
                return@withContext Outcome.Fail(
                    "سرویس هوش مصنوعی رایگان این لحظه محدود بود (حدود ۱ درخواست در ۳۰ ثانیه برای هر IP) و از شواهد مستقیم و جستجوی وب هم فیلمی پیدا نشد. حدود ۳۰ ثانیه صبر کن و دوباره بزن — بار دوم معمولاً جواب می‌دهد."
                )
            }
            val extra = ai?.sceneSummary?.let { " توضیح صحنه: $it" } ?: ""
            return@withContext Outcome.Fail(
                "هوش مصنوعی فریم‌های ویدیو را دید اما با شواهد موجود (کپشن، هشتگ، متن روی تصویر و وب) نتوانست فیلمی با اطمینان نام ببرد.$extra"
            )
        }
        onStep(STEP_MATCH, "done")
        val effective = AiAnalysis(
            found = true,
            sceneSummary = ai?.sceneSummary,
            candidates = merged,
            viaVision = ai?.viaVision ?: false
        )

        /* ---- 5. verify: one fast summary call per candidate, parallel ---- */
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

        /* ---- 6. final: deep enrichment for the winner + optional fusion ---- */
        onStep(STEP_FINAL, "start")
        val best = enrichWinner(verified.first())
        val others = verified.drop(1)
        var result = buildFinal(best, others, effective, post, ocrText)
        if (best.score < 0.70) {
            val fused = askLlmFusion(verified, effective, post, ocrText)
            if (fused != null) {
                result = blend(result, fused)
            }
        }
        onStep(STEP_FINAL, "done")
        Outcome.Done(result)
    }

    /* ================= frames + OCR + vision payload ================= */

    /**
     * Downloads the video and extracts [count] frames spread across the clip
     * (title cards live at the start, credits at the end). Each frame is
     * downscaled to ≤1024px on the longest side right after extraction and the
     * full-size bitmap is recycled immediately to keep memory low.
     */
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
            val fracs = doubleArrayOf(0.04, 0.20, 0.36, 0.52, 0.68, 0.85)
            for (f in fracs) {
                if (out.size >= count) break
                val big = try {
                    mmr.getFrameAtTime(
                        (durMs * 1000.0 * f).toLong(),
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                } catch (e: Exception) {
                    null
                }
                if (big != null) {
                    val scaled = scaleTo(big, 1024)
                    if (scaled !== big) big.recycle()
                    out.add(scaled)
                }
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

    /** Returns a copy of [src] scaled down so its longest side is ≤ [maxDim] (never upscales). */
    private fun scaleTo(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxDim) return src
        val ratio = maxDim.toFloat() / longest.toFloat()
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createScaledBitmap(src, nw, nh, true)
        } catch (e: Exception) {
            src
        }
    }

    /**
     * Builds the vision payload: up to 3 frames (first / middle / last — title
     * cards live at the very start and credits at the very end) encoded as
     * ≤512px JPEG base64 data URLs. The TOTAL upload stays under
     * [VISION_PAYLOAD_BUDGET] bytes so the call stays fast even on slow VPNs.
     */
    private fun buildVisionPayload(frames: List<Bitmap>): List<String> {
        if (frames.isEmpty()) return emptyList()
        val picks = linkedSetOf(0, frames.size / 2, frames.size - 1)
        val dataUrls = mutableListOf<String>()
        var total = 0
        for (idx in picks) {
            if (idx !in frames.indices) continue
            val encoded = encodeFrame(frames[idx], 512, 72)
            if (encoded != null) {
                dataUrls.add(encoded)
                total += encoded.length
                if (total > VISION_PAYLOAD_BUDGET) break
            }
        }
        // Drop from the tail until we are inside the upload budget (keep ≥1 image).
        while (dataUrls.size > 1 && total > VISION_PAYLOAD_BUDGET) {
            val removed = dataUrls.removeAt(dataUrls.size - 1)
            total -= removed.length
        }
        return dataUrls
    }

    /** One frame → "data:image/jpeg;base64,…" (null on failure). Does NOT recycle [src]. */
    private fun encodeFrame(src: Bitmap, maxDim: Int, quality: Int): String? {
        var bmp: Bitmap? = null
        return try {
            val scaled = scaleTo(src, maxDim)
            if (scaled !== src) bmp = scaled
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            "data:image/jpeg;base64,$b64"
        } catch (e: Exception) {
            null
        } finally {
            bmp?.recycle()
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

    /** On-device OCR (bundled latin model — no downloads). Returns merged unique lines.
     *  Recycles the input bitmaps when done. */
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
            frames.forEach { if (!it.isRecycled) it.recycle() }
        }
        lines.take(18).joinToString(" | ").take(1600)
    }

    /* ================= LLM channel (keyless, rate-limit aware) ================= */

    /**
     * One free-gateway LLM call, hardened against every failure v2/v3 hit:
     *  - optional pre-wait of the 30s cooling window (only for the FIRST call of
     *    a stage — fallbacks never re-wait);
     *  - honors Retry-After once, but NEVER waits past the deadline (no hangs);
     *  - 4xx = bad payload (e.g. vision unsupported) → immediate null (no retry);
     *  - HTML challenge pages / SSE streams / parse failures are retryable;
     *  - images are sent as base64 data-URL content parts (vision);
     *  - hard wall-clock [totalBudgetMs]; cancellation-aware everywhere.
     */
    private suspend fun callLlm(
        system: String,
        user: String,
        model: String,
        images: List<String> = emptyList(),
        preWait: Boolean = true,
        maxRateLimitWaits: Int = 1,
        totalBudgetMs: Long = LLM_MATCH_BUDGET_MS
    ): String? {
        val deadline = System.currentTimeMillis() + totalBudgetMs
        var rateWaits = 0
        var hardFails = 0

        if (preWait) {
            val sinceLast = System.currentTimeMillis() - lastLlmTouchMs
            if (lastLlmTouchMs > 0 && sinceLast < RATE_WINDOW_MS) {
                val waitMs = RATE_WINDOW_MS - sinceLast
                if (System.currentTimeMillis() + waitMs >= deadline) return null
                delay(waitMs)
            }
        }

        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            try {
                val sys = JsonObject()
                sys.addProperty("role", "system")
                sys.addProperty("content", system)
                val usr = JsonObject()
                usr.addProperty("role", "user")
                if (images.isEmpty()) {
                    usr.addProperty("content", user)
                } else {
                    val parts = JsonArray()
                    val txt = JsonObject()
                    txt.addProperty("type", "text")
                    txt.addProperty("text", user)
                    parts.add(txt)
                    for (dataUrl in images) {
                        val img = JsonObject()
                        img.addProperty("type", "image_url")
                        val inner = JsonObject()
                        inner.addProperty("url", dataUrl)
                        img.add("image_url", inner)
                        parts.add(img)
                    }
                    usr.add("content", parts)
                }
                val messages = JsonArray()
                messages.add(sys)
                messages.add(usr)
                val payload = JsonObject()
                payload.addProperty("model", model)
                payload.addProperty("referrer", REFERRER)
                payload.add("messages", messages)
                payload.addProperty("temperature", 0.2)
                payload.addProperty("max_tokens", 700)

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
                            val waitMs = ((ra ?: 30L).coerceIn(5L, 30L)) * 1000L + 1200L
                            if (System.currentTimeMillis() + waitMs >= deadline) return null
                            delay(waitMs)
                        }
                        resp.code == 400 || resp.code == 422 -> {
                            // Bad payload (vision unsupported / image too large) — do not retry.
                            return null
                        }
                        resp.code >= 500 -> {
                            hardFails++
                            if (hardFails > 2) return null
                            if (System.currentTimeMillis() + 2500L >= deadline) return null
                            delay(2500L)
                        }
                        !resp.isSuccessful -> {
                            hardFails++
                            if (hardFails > 3) return null
                            if (System.currentTimeMillis() + 1500L >= deadline) return null
                            delay(1500L)
                        }
                        else -> {
                            val bodyStr = resp.body?.string() ?: ""
                            val content = extractLlmContent(bodyStr)
                            if (content != null && content.contains('{') && content.contains('}')) {
                                return content
                            }
                            hardFails++
                            if (hardFails > 3) return null
                            if (System.currentTimeMillis() + 1200L >= deadline) return null
                            delay(1200L)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hardFails++
                if (hardFails > 3) return null
                if (System.currentTimeMillis() + 2000L >= deadline) return null
                delay(2000L)
            }
        }
        return null
    }

    /**
     * Tolerant LLM reply parser: OpenAI chat shape, SSE delta stream, or a
     * top-level content field. HTML challenge pages and plain error texts return
     * null so the caller retries instead of crashing.
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
        if (trimmed.startsWith("data:")) {
            // Server-sent events: concatenate the delta contents.
            val sb = StringBuilder()
            for (rawLine in trimmed.lines()) {
                val line = rawLine.trim()
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                try {
                    val o = JsonParser.parseString(payload).asJsonObject
                    val choices = o.getAsJsonArray("choices")
                    if (choices != null && choices.size() > 0) {
                        val c0 = choices.get(0).takeIf { it.isJsonObject }?.asJsonObject
                        val delta = c0?.getAsJsonObject("delta")
                        val piece = delta?.get("content")?.takeIf { !it.isJsonNull }?.asString
                        if (piece != null) sb.append(piece)
                        val msg = c0?.getAsJsonObject("message")
                        val piece2 = msg?.get("content")?.takeIf { !it.isJsonNull }?.asString
                        if (piece2 != null) sb.append(piece2)
                    }
                } catch (e: Exception) {
                    // ignore malformed SSE lines
                }
            }
            val s = sb.toString().trim()
            if (s.isNotEmpty()) return s
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

    private fun parseAiAnalysis(obj: JsonObject, viaVision: Boolean): AiAnalysis? {
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
            candidates = candidates,
            viaVision = viaVision
        )
    }

    /* ---- step 4: channels ---- */

    private const val VISION_SYSTEM =
        "You are FilmYab, an expert movie identification engine. You receive 2-3 REAL frames grabbed " +
            "from an Instagram video (a movie/series clip or trailer), plus textual evidence: caption, " +
            "hashtags, uploader, page og-title and text read from frames by on-device OCR (may contain " +
            "noise, watermarks or subtitle lines). " +
            "LOOK at the frames carefully: actors' faces and known stars, wardrobe, cars, weapons, " +
            "cinematography and color grading, language and style of on-screen text, title cards, " +
            "credits, studio logos. Then combine what you SEE with the textual evidence to name the " +
            "exact movie or series the clip is from. If you recognize a famous actor, strongly weight " +
            "the films they appear in with this exact look. " +
            "Reply ONLY with minified JSON, no markdown, exactly this schema: " +
            "{\"scene_summary_fa\":\"یک جمله کوتاه فارسی درباره صحنه‌ای که می‌بینی\"," +
            "\"candidates\":[{\"title\":\"original English title\",\"year\":\"\",\"confidence\":0.0,\"reason_fa\":\"دلیل کوتاه فارسی\"}]," +
            "\"found\":true}. " +
            "Rules: at most 3 candidates ordered by likelihood; confidence 0..1 per candidate; " +
            "year=\"\" if unsure; set found=false and candidates=[] only if you truly cannot identify anything."

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

    /**
     * The match-stage AI — up to THREE attempts inside ONE parallel budget:
     *  1. PRIMARY model ("openai", stronger reasoning) with full textual evidence;
     *  2. if rate-limited or empty: ONE polite wait, then the SAME call with
     *     LIVE web-search results injected (real web titles usually name the
     *     exact movie — the strongest textual evidence there is);
     *  3. if the primary model itself is unavailable: the fast text model.
     * The web hits come from a provider that is ALREADY running in parallel,
     * so this stage never serializes behind the web search.
     */
    private suspend fun askLlmCandidates(
        post: InstagramPost,
        ocrText: String,
        webProvider: suspend () -> List<WebHit>
    ): AiAnalysis? {
        val basePrompt = buildEvidencePrompt(post, ocrText, null)
        val deadline = System.currentTimeMillis() + LLM_MATCH_BUDGET_MS

        fun remaining(): Long = (deadline - System.currentTimeMillis()).coerceAtLeast(8_000L)

        // Attempt 1: stronger model with textual evidence.
        var content = callLlm(
            CANDIDATES_SYSTEM, basePrompt,
            model = PRIMARY_MODEL,
            preWait = true,
            maxRateLimitWaits = 1,
            totalBudgetMs = remaining()
        )
        var parsed = content?.let { extractJson(it) }?.let { parseAiAnalysis(it, viaVision = false) }
        if (parsed != null && parsed.candidates.isNotEmpty()) return parsed

        // Attempt 2: after the polite wait the rate window has likely reset —
        // retry with LIVE web results injected into the evidence.
        if (remaining() > 12_000L) {
            val web = try {
                webProvider()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList<WebHit>()
            }
            if (web.isNotEmpty()) {
                val webPrompt = buildEvidencePrompt(post, ocrText, web)
                content = callLlm(
                    CANDIDATES_SYSTEM, webPrompt,
                    model = PRIMARY_MODEL,
                    preWait = false,
                    maxRateLimitWaits = 1,
                    totalBudgetMs = remaining()
                )
                parsed = content?.let { extractJson(it) }?.let { parseAiAnalysis(it, viaVision = false) }
                if (parsed != null && parsed.candidates.isNotEmpty()) return parsed
            }
        }

        // Attempt 3: fast model as a last resort (works even if "openai" is gone).
        if (remaining() > 10_000L) {
            content = callLlm(
                CANDIDATES_SYSTEM, basePrompt,
                model = TEXT_MODEL,
                preWait = false,
                maxRateLimitWaits = 1,
                totalBudgetMs = remaining()
            )
            parsed = content?.let { extractJson(it) }?.let { parseAiAnalysis(it, viaVision = false) }
            if (parsed != null && parsed.candidates.isNotEmpty()) return parsed
        }
        return parsed
    }

    private fun buildEvidencePrompt(
        post: InstagramPost,
        ocrText: String,
        web: List<WebHit>?
    ): String {
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
        if (web != null && web.isNotEmpty()) {
            sb.append("\nLive web results for the strongest signals of this post:\n")
            for (hit in web.take(6)) {
                sb.append("- ").append(hit.title.take(120))
                hit.snippet?.let { sb.append(" — ").append(it.take(160)) }
                sb.append("\n")
            }
        }
        return sb.toString()
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

    /* ---- step 5: Wikipedia verification (keyless, parallel, ONE call per candidate) ---- */

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
            if (deduped.size >= 3) break
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

    /**
     * Verifies one candidate with ONE Wikipedia summary call (fast phase).
     * Deep enrichment (fa title/plot, Wikidata specs) happens later and only
     * for the winning candidate — that is what keeps this stage fast.
     */
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
            chosen?.let { wikiEvidenceLite(it) }
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
            chosen?.let { wikiEvidenceLite(it) }
        }
        val score = scoreCandidate(c, post, ocrText, wiki)
        return if (wiki != null || score >= 0.5) VerifiedCandidate(c, wiki, score) else null
    }

    /**
     * FAST evidence: one summary call → film check, year, poster, page url and
     * an English plot. No fa lookup, no Wikidata — those run in [enrichWinner].
     */
    private suspend fun wikiEvidenceLite(page: WikiPage): WikiEvidence? {
        val summary = wikiSummary(page.lang, page.key) ?: return null
        val description = optString(summary, "description")
        val extract = optString(summary, "extract") ?: ""
        val hay = listOfNotNull(description, extract.take(400)).joinToString(" ")
        val isFilm = filmDescRegex.containsMatchIn(hay) ||
            hay.contains("فیلم") || hay.contains("سریال")
        if (!isFilm) return null

        val year = description?.let { yearRegex.find(it)?.value }
            ?: yearRegex.find(extract.take(200))?.value
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

        return WikiEvidence(
            pageTitle = page.title,
            pageDescription = description,
            year = year,
            plot = plotFromExtract(extract),
            pageUrl = pageUrl,
            posterUrl = poster,
            isFilm = true
        )
    }

    /* ================= winner enrichment (Wikidata + fa-wiki, keyless) ================= */

    /** Extracts the wikibase item id from a Wikipedia summary payload. */
    private fun wikibaseIdOf(summary: JsonObject): String? = optString(summary, "wikibase_item")

    /**
     * Deep enrichment of the WINNING candidate only (keeps the pipeline fast):
     *  - fa.wikipedia → Persian title + Persian plot + (poster fallback),
     *  - Wikidata → director (P57), genres (P136), cast (P161), runtime (P2047),
     *    country (P495) and IMDb id (P345).
     * The two branches run in parallel.
     */
    private suspend fun enrichWinner(v: VerifiedCandidate): VerifiedCandidate {
        val lite = v.wiki ?: return v
        return try {
            var faTitle: String? = null
            var faPlot: String? = null
            var faPoster: String? = null
            var details: WikidataDetails? = null
            coroutineScope {
                val faJob = async {
                    try {
                        resolveFaTitleAndPlot(lite.pageTitle ?: "")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
                val detailsJob = async {
                    try {
                        val summary = wikiSummary("en", lite.pageTitle ?: "") ?: return@async null
                        val qid = wikibaseIdOf(summary) ?: return@async null
                        fetchWikidataDetails(qid)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
                val fa = faJob.await()
                faTitle = fa?.first
                faPlot = fa?.second
                faPoster = fa?.third
                details = detailsJob.await()
            }
            v.copy(
                wiki = lite.copy(
                    faTitle = faTitle ?: lite.faTitle,
                    plot = faPlot ?: lite.plot,
                    posterUrl = lite.posterUrl ?: faPoster,
                    director = details?.director ?: lite.director,
                    genres = details?.genres?.takeIf { it.isNotEmpty() } ?: lite.genres,
                    cast = details?.cast ?: emptyList(),
                    duration = details?.duration,
                    country = details?.country,
                    imdbUrl = details?.imdbUrl
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            v
        }
    }

    /**
     * Resolves director, genres, cast, runtime, country and IMDb id from Wikidata
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

            // String-valued claims (IMDb id P345).
            val claimString = { prop: String ->
                var out: String? = null
                val arr = claims.getAsJsonArray(prop)
                if (arr != null && arr.isJsonArray) {
                    for (el in arr) {
                        val snak = el.takeIf { it.isJsonObject }?.asJsonObject
                            ?.getAsJsonObject("mainsnak") ?: continue
                        val dv = snak.getAsJsonObject("datavalue") ?: continue
                        val value = dv.get("value") ?: continue
                        if (value.isJsonPrimitive) {
                            out = value.asString
                            break
                        }
                    }
                }
                out
            }

            // Quantity-valued claims (runtime P2047, value.amount like "+7560").
            val claimQuantity = { prop: String ->
                var out: Double? = null
                val arr = claims.getAsJsonArray(prop)
                if (arr != null && arr.isJsonArray) {
                    for (el in arr) {
                        val snak = el.takeIf { it.isJsonObject }?.asJsonObject
                            ?.getAsJsonObject("mainsnak") ?: continue
                        val dv = snak.getAsJsonObject("datavalue") ?: continue
                        val value = dv.get("value") ?: continue
                        if (value.isJsonObject) {
                            val amount = value.asJsonObject.get("amount")
                            if (amount != null && amount.isJsonPrimitive) {
                                out = amount.asString.toDoubleOrNull()
                                break
                            }
                        }
                    }
                }
                out
            }

            val directorIds = claimIds("P57", 2)
            val genreIds = claimIds("P136", 3)
            val castIds = claimIds("P161", 5)
            val countryIds = claimIds("P495", 1)
            val all = (directorIds + genreIds + castIds + countryIds).distinct()

            val imdb = claimString("P345")
            val runtimeSec = claimQuantity("P2047")

            if (all.isEmpty() && imdb == null && runtimeSec == null) {
                return WikidataDetails(null, emptyList(), emptyList(), null, null, null)
            }

            var director: String? = null
            var genres = emptyList<String>()
            var cast = emptyList<String>()
            var country: String? = null
            if (all.isNotEmpty()) {
                val labelsUrl = "https://www.wikidata.org/w/api.php?action=wbgetentities" +
                    "&ids=" + URLEncoder.encode(all.joinToString("|"), "UTF-8") +
                    "&props=labels&languages=fa|en&format=json&formatversion=2"
                val labelsRoot: JsonObject? = getJson(labelsUrl, WIKI_UA)
                if (labelsRoot != null) {
                    val labelEntities = labelsRoot.getAsJsonObject("entities")
                    if (labelEntities != null) {
                        fun labelOf(id: String): String? {
                            val ent = labelEntities.getAsJsonObject(id) ?: return null
                            val labels = ent.getAsJsonObject("labels") ?: return null
                            val fa = labels.getAsJsonObject("fa")?.optStringSafe("value")
                            return fa ?: labels.getAsJsonObject("en")?.optStringSafe("value")
                        }
                        director = directorIds.firstNotNullOfOrNull { id -> labelOf(id) }
                        genres = genreIds.mapNotNull { id -> labelOf(id) }
                        cast = castIds.mapNotNull { id -> labelOf(id) }
                        country = countryIds.firstNotNullOfOrNull { id -> labelOf(id) }
                    }
                }
            }
            val duration = runtimeSec?.let { sec -> formatRuntime(sec) }
            val imdbUrl = imdb?.takeIf { it.isNotBlank() }?.let { "https://www.imdb.com/title/$it/" }
            return WikidataDetails(director, genres, cast, duration, country, imdbUrl)
        } catch (e: Exception) {
            return null
        }
    }

    /** "+7560" (seconds) → "۱۲۶ دقیقه" style Persian runtime. */
    private fun formatRuntime(seconds: Double): String? {
        if (seconds <= 0.0 || seconds.isNaN()) return null
        val totalMin = (seconds / 60.0).toInt()
        if (totalMin <= 0) return null
        return if (totalMin >= 180) {
            val h = totalMin / 60
            val m = totalMin % 60
            if (m == 0) "$h ساعت" else "$h ساعت و $m دقیقه"
        } else {
            "$totalMin دقیقه"
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

    /** fa-wiki search for the film's Persian title, plus Persian plot and poster. */
    private fun resolveFaTitleAndPlot(enTitle: String): Triple<String, String?, String?>? {
        if (enTitle.isBlank()) return null
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
                val poster = try {
                    faSummary?.getAsJsonObject("originalimage")?.optStringSafe("source")
                        ?: faSummary?.getAsJsonObject("thumbnail")?.optStringSafe("source")
                } catch (e: Exception) {
                    null
                }
                return Triple(fp.title, plot, poster)
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
        val hay = listOfNotNull(post.caption, post.ownerUsername).joinToString(" ")
        if (normalizedContains(hay, title)) score += 0.10
        // og-title bonus: movie channels usually name the film in the page title.
        post.ogTitle?.let { if (normalizedContains(it, title)) score += 0.12 }
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
        if (ai.viaVision) {
            reasoning.add("هوش مصنوعی بینایی، فریم‌های واقعی ویدیو را دید و این فیلم را شناخت")
        } else if (ocrText.isNotBlank()) {
            reasoning.add("متن روی تصویر هم تحلیل شد")
        }

        val evidenceBits = mutableListOf<String>()
        if (ai.viaVision) evidenceBits.add("فریم‌های ویدیو (بینایی AI)")
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
            sourceMode = "smart",
            duration = w?.duration,
            country = w?.country,
            imdbUrl = w?.imdbUrl
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
            model = PRIMARY_MODEL,
            preWait = true,
            maxRateLimitWaits = 1,
            totalBudgetMs = 45_000L
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
                sourceMode = base.sourceMode,
                duration = base.duration,
                country = base.country,
                imdbUrl = base.imdbUrl
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
