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
import com.google.mlkit.vision.text.arabic.ArabicTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
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
 *               read on-screen text with on-device OCR (ML Kit, latin + arabic)
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
    private const val POLLINATIONS_URL = "https://text.pollinations.ai/openai"
    private const val MAX_VIDEO_BYTES = 120L * 1024 * 1024

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

        /* ---- 4. match: LLM candidates ---- */
        onStep(STEP_MATCH, "start")
        val ai = askLlmCandidates(post, ocrText)
        if (ai == null) {
            onStep(STEP_MATCH, "error")
            return@withContext Outcome.Fail("سرویس تحلیل هوشمند پاسخ نداد. چند لحظه بعد دوباره امتحان کن.")
        }
        if (ai.candidates.isEmpty()) {
            onStep(STEP_MATCH, "error")
            val extra = ai.sceneSummary?.let { " توضیح صحنه: $it" } ?: ""
            return@withContext Outcome.Fail(
                "با شواهد موجود (کپشن و متن روی تصویر) نتونستم فیلمی رو با اطمینان پیدا کنم.$extra"
            )
        }
        onStep(STEP_MATCH, "done")

        /* ---- 5. verify: Wikipedia ---- */
        onStep(STEP_VERIFY, "start")
        val verified = verifyCandidates(ai.candidates, post, ocrText)
        if (verified.isEmpty()) {
            onStep(STEP_VERIFY, "error")
            val names = ai.candidates.joinToString("، ") { it.name ?: "?" }
            return@withContext Outcome.Fail(
                "کاندیدها ($names) در پایگاه فیلم تأیید نشدند. احتمالاً کلیپ از فیلم شناخته‌شده‌ای نیست یا شواهد کم است."
            )
        }
        onStep(STEP_VERIFY, "done")

        /* ---- 6. final ---- */
        onStep(STEP_FINAL, "start")
        val best = verified.first()
        var result = buildFinal(best, verified.drop(1), ai, post, ocrText)
        if (best.score < 0.85) {
            val fused = askLlmFusion(verified, ai, post, ocrText)
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

    /** On-device OCR (latin + arabic models, bundled — no downloads). Returns merged unique lines. */
    private suspend fun runOcr(frames: List<Bitmap>): String = withContext(Dispatchers.IO) {
        val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val arabic = TextRecognition.getClient(ArabicTextRecognizerOptions.Builder().build())
        val lines = LinkedHashSet<String>()
        try {
            for (bmp in frames) {
                val recognizers = listOf(latin, arabic)
                for (rec in recognizers) {
                    try {
                        val text: Text = rec.process(InputImage.fromBitmap(bmp, 0)).await()
                        for (line in text.text.split("\n")) {
                            val t = line.trim()
                            if (t.length >= 2) {
                                lines.add(t)
                            }
                        }
                    } catch (e: Exception) {
                        // OCR of this frame/model failed — keep going
                    }
                }
            }
        } finally {
            try {
                latin.close()
            } catch (e: Exception) {
            }
            try {
                arabic.close()
            } catch (e: Exception) {
            }
            frames.forEach { it.recycle() }
        }
        lines.take(14).joinToString(" | ").take(1400)
    }

    /* ================= LLM channel (keyless) ================= */

    private fun callLlm(system: String, user: String): String? {
        var lastError: Exception? = null
        for (attempt in 0 until 2) {
            try {
                val payload = JsonObject()
                payload.addProperty("model", "openai")
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
                    .url(POLLINATIONS_URL)
                    .header("Accept", "application/json")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val bodyStr = resp.body?.string() ?: throw IOException("empty body")
                    val root = JsonParser.parseString(bodyStr).asJsonObject
                    val choices = root.getAsJsonArray("choices") ?: throw IOException("no choices")
                    if (choices.size() == 0) throw IOException("empty choices")
                    val message = choices.get(0).asJsonObject.getAsJsonObject("message")
                        ?: throw IOException("no message")
                    val content = message.get("content")
                    if (content == null || content.isJsonNull) throw IOException("no content")
                    return content.asString
                }
            } catch (e: IOException) {
                lastError = e
            } catch (e: Exception) {
                lastError = e
                break
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
            "\"candidates\":[{\"title\":\"original English title\",\"year\":\"\",\"reason_fa\":\"دلیل کوتاه فارسی\"}]," +
            "\"found\":true}. " +
            "Rules: at most 3 candidates ordered by likelihood; year=\"\" if unsure; " +
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

    /* ---- step 5: Wikipedia verification (keyless) ---- */

    private data class WikiPage(
        val key: String,
        val title: String,
        val description: String?
    )

    private fun wikiSearch(lang: String, query: String, limit: Int): List<WikiPage> {
        return try {
            val q = URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("https://$lang.wikipedia.org/w/rest.php/v1/search/title?q=$q&limit=$limit")
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "application/json")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val bodyStr = resp.body?.string() ?: return@use emptyList()
                val root = JsonParser.parseString(bodyStr).asJsonObject
                val pages = root.getAsJsonArray("pages") ?: return@use emptyList()
                val out = mutableListOf<WikiPage>()
                for (el in pages) {
                    val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                    val key = optString(o, "key") ?: continue
                    val title = optString(o, "title") ?: key
                    out.add(WikiPage(key, title, optString(o, "description")))
                }
                out
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun wikiSummary(lang: String, key: String): JsonObject? {
        return try {
            val encoded = URLEncoder.encode(key, "UTF-8").replace("+", "%20")
            val req = Request.Builder()
                .url("https://$lang.wikipedia.org/api/rest_v1/page/summary/$encoded")
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "application/json")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val bodyStr = resp.body?.string() ?: return@use null
                JsonParser.parseString(bodyStr).asJsonObject
            }
        } catch (e: Exception) {
            null
        }
    }

    private val filmDescRegex = Regex("""(?i)\b(film|movie|series|television|miniseries)\b""")
    private val yearRegex = Regex("""(19|20)\d{2}""")
    private val directorRegex = Regex("""\bby ([A-Z][^,.;()]{2,40})""")

    private fun buildWikiEvidence(page: WikiPage): WikiEvidence? {
        val summary = wikiSummary("en", page.key) ?: return null
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
        val faPages = wikiSearch("fa", page.title, 3)
        for (fp in faPages) {
            val d = fp.description ?: ""
            if (d.contains("فیلم") || d.contains("سریال")) {
                faTitle = fp.title
                break
            }
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
            if (out.size >= 3) break
            val title = c.name ?: continue
            val key = normalizedKey(title)
            if (key.isEmpty() || seen.contains(key)) continue
            seen.add(key)

            val pages = wikiSearch("en", title, 5)
            var chosen: WikiPage? = null
            for (p in pages) {
                val d = p.description ?: ""
                if (filmDescRegex.containsMatchIn(d) || d.contains("فیلم")) {
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
                    .firstOrNull { filmDescRegex.containsMatchIn(it.description ?: "") }
            }

            val wiki = chosen?.let { buildWikiEvidence(it) }
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
        val content = callLlm(FUSION_SYSTEM, sb.toString()) ?: return null
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
