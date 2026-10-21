package com.filmyab.app.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * On-device Instagram scraper for the smart engine (no server involved).
 *
 * Methods (run in parallel, merged):
 *  1. crawler — post page with a search-engine User-Agent (Googlebot). Instagram serves
 *     og:title / og:description / og:image and signed .mp4 URLs to crawlers without login.
 *  2. embed — /p/{code}/embed/captioned/ HTML with a browser UA (plain JSON inside).
 *  3. api — ?__a=1&__d=dis JSON with X-IG-App-ID (works on some networks).
 *  4. og — plain post page with a browser UA, og: meta tags only.
 *
 * NOTE: [parseInstagramUrl] is intentionally pure (no network). The `/share/...`
 * redirect resolution is a network operation and therefore lives inside [fetchPost],
 * which always runs on Dispatchers.IO — calling it from the main thread would throw
 * NetworkOnMainThreadException.
 */
object InstagramScraper {

    data class ParsedIgUrl(val shortcode: String, val type: String)

    private val gson = Gson()

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val CRAWLER_UA =
        "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
    private const val IG_APP_ID = "936619743392459"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /* ---------------- URL parsing ---------------- */

    /** Pure parse of a direct post/reel/tv URL. Returns null for share links or foreign hosts. */
    fun parseInstagramUrl(rawUrl: String): ParsedIgUrl? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        val withoutScheme = if (trimmed.contains("://")) trimmed.substringAfter("://") else trimmed
        val hostFull = withoutScheme.substringBefore('/').substringBefore('?').lowercase(Locale.US)
        val host = hostFull.substringBefore(':')
        if (host != "instagram.com" && host != "www.instagram.com" &&
            host != "instagr.am" && host != "ig.me"
        ) {
            return null
        }
        val pathPart = withoutScheme
            .substringAfter('/', "")
            .substringBefore('?')
            .substringBefore('#')
        val segments = pathPart.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        val kind = segments[0].lowercase(Locale.US)
        val code = segments[1]
        if (kind == "p" || kind == "reel" || kind == "reels" || kind == "tv") {
            if (Regex("^[A-Za-z0-9_-]{5,}$").matches(code)) {
                val type = when (kind) {
                    "p" -> "post"
                    "tv" -> "tv"
                    else -> "reel"
                }
                return ParsedIgUrl(shortcode = code, type = type)
            }
        }
        return null
    }

    /** Cheap pure validation used by the ViewModel (no network). */
    fun looksLikeInstagramUrl(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty()) return false
        if (parseInstagramUrl(s) != null) return true
        val lower = s.lowercase(Locale.US)
        return lower.contains("instagram.com/share/") ||
            lower.contains("instagr.am/share/") ||
            lower.contains("ig.me/share/")
    }

    /* ---------------- Fetch ---------------- */

    /**
     * Scrapes the post with several parallel attempts and merges the first non-null fields.
     * Returns null unless at least one of caption/videoUrl/thumbnailUrl exists.
     */
    suspend fun fetchPost(rawUrl: String): InstagramPost? = withContext(Dispatchers.IO) {
        try {
            var parsed = parseInstagramUrl(rawUrl)
            if (parsed == null) {
                val trimmed = rawUrl.trim()
                val lower = trimmed.lowercase(Locale.US)
                val isShare = lower.contains("instagram.com/share/") ||
                    lower.contains("instagr.am/share/") ||
                    lower.contains("ig.me/share/")
                if (isShare) {
                    parsed = resolveShareUrl(trimmed)?.let { parseInstagramUrl(it) }
                }
            }
            val shortcode = parsed?.shortcode ?: return@withContext null

            val attempts: List<Pair<String, InstagramPost?>> = coroutineScope {
                listOf(
                    async { "crawler" to fetchViaCrawler(shortcode) },
                    async { "embed" to fetchViaEmbed(shortcode) },
                    async { "api" to fetchViaApi(shortcode) },
                    async { "og" to fetchViaOg(shortcode) }
                ).awaitAll()
            }

            var caption: String? = null
            var ogTitle: String? = null
            var videoUrl: String? = null
            var thumbnailUrl: String? = null
            var ownerUsername: String? = null
            val methods = mutableListOf<String>()
            for ((method, post) in attempts) {
                val p = post ?: continue
                if (p.caption != null && caption == null) caption = p.caption
                if (p.ogTitle != null && ogTitle == null) ogTitle = p.ogTitle
                if (p.videoUrl != null && videoUrl == null) videoUrl = p.videoUrl
                if (p.thumbnailUrl != null && thumbnailUrl == null) thumbnailUrl = p.thumbnailUrl
                if (p.ownerUsername != null && ownerUsername == null) ownerUsername = p.ownerUsername
                methods.add(method)
            }

            val finalCaption = caption?.let { cleanCaption(it) }?.takeIf { it.isNotBlank() }
            if (finalCaption == null && videoUrl == null && thumbnailUrl == null) {
                return@withContext null
            }

            InstagramPost(
                shortcode = shortcode,
                type = if (videoUrl != null) "video" else "image",
                caption = finalCaption,
                ogTitle = ogTitle,
                hashtags = extractHashtags(finalCaption ?: ""),
                videoUrl = videoUrl,
                thumbnailUrl = thumbnailUrl,
                ownerUsername = ownerUsername,
                method = if (methods.isEmpty()) "none" else methods.joinToString("+")
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Follows an instagram.com/share/... redirect chain and returns the final URL. */
    private fun resolveShareUrl(rawUrl: String): String? = try {
        val target = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            "https://$rawUrl"
        }
        val req = Request.Builder()
            .url(target)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
        client.newCall(req).execute().use { resp -> resp.request.url.toString() }
    } catch (e: Exception) {
        null
    }

    /* ---------------- Method 1: crawler post page (Googlebot UA) ---------------- */

    /** Signed .mp4 URLs inside the page (JSON-escaped or HTML-escaped variants). */
    private val mp4Regex = Regex("""https:(?:\\/\\/|//)[^"\s<>]+?\.mp4[^"\s<>]*""")

    private fun fetchViaCrawler(shortcode: String): InstagramPost? = try {
        val req = Request.Builder()
            .url("https://www.instagram.com/p/$shortcode/")
            .header("User-Agent", CRAWLER_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val html = resp.body?.string() ?: return@use null

            val og = parseOgTags(html)
            if (og.isEmpty()) return@use null

            val rawDesc = og["description"]
            val rawTitle = og["title"]
            val caption = rawDesc?.let { captionFromOgDescription(it) }
                ?: rawTitle?.let { captionFromOgTitle(it) }
            val owner = rawDesc?.let { ownerFromOgDescription(it) }
            val videoUrl = extractBestVideoUrl(html)
            val thumbnailUrl = og["image"]

            val cleanedCaption = caption?.let { cleanCaption(it) }?.takeIf { it.isNotBlank() }
            if (cleanedCaption == null && videoUrl == null && thumbnailUrl == null) {
                return@use null
            }
            InstagramPost(
                shortcode = shortcode,
                type = if (videoUrl != null) "video" else "image",
                caption = cleanedCaption,
                ogTitle = rawTitle,
                hashtags = extractHashtags(cleanedCaption ?: ""),
                videoUrl = videoUrl,
                thumbnailUrl = thumbnailUrl,
                ownerUsername = owner,
                method = "crawler"
            )
        }
    } catch (e: Exception) {
        null
    }

    /** og: meta parsing supporting both attribute orders. */
    private val ogRegexPropertyFirst =
        Regex("""<meta[^>]+property=["']og:(title|description|image|video:secure_url|video:url|video)["'][^>]+content=["']([^"']*)["']""")
    private val ogRegexContentFirst =
        Regex("""<meta[^>]+content=["']([^"']*)["'][^>]+property=["']og:(title|description|image|video:secure_url|video:url|video)["']""")

    private fun parseOgTags(html: String): Map<String, String> {
        val og = mutableMapOf<String, String>()
        for (m in ogRegexPropertyFirst.findAll(html)) {
            val value = htmlDecode(m.groupValues[2])
            if (value.isNotBlank() && !og.containsKey(m.groupValues[1])) og[m.groupValues[1]] = value
        }
        for (m in ogRegexContentFirst.findAll(html)) {
            val value = htmlDecode(m.groupValues[1])
            if (value.isNotBlank() && !og.containsKey(m.groupValues[2])) og[m.groupValues[2]] = value
        }
        return og
    }

    /** og:description looks like: "username on February 9, 2025: caption text" (sometimes
     *  prefixed with "27K likes, 37 comments - " on popular posts). */
    private val ogDescPrefixRegex =
        Regex("""^.*?on [A-Za-z]+\s+\d{1,2},\s+\d{4}:\s*""")
    private val ogOwnerRegex =
        Regex("""([A-Za-z0-9._]+) on [A-Za-z]+\s+\d{1,2},\s+\d{4}:""")

    private fun captionFromOgDescription(desc: String): String? {
        val stripped = ogDescPrefixRegex.find(desc)?.let { desc.substring(it.value.length).trim() } ?: desc.trim()
        return stripped.takeIf { it.isNotEmpty() }
    }

    private fun ownerFromOgDescription(desc: String): String? =
        ogOwnerRegex.find(desc.take(100))?.groupValues?.get(1)
            ?.takeIf { it.isNotBlank() && it != "Instagram" }

    /** og:title looks like: "Account Name on Instagram: caption..." — used only as caption fallback. */
    private val ogTitlePrefixRegex = Regex("""^[^:]*on Instagram:\s*""")

    private fun captionFromOgTitle(title: String): String? =
        ogTitlePrefixRegex.find(title)?.let { title.substring(it.value.length).trim().takeIf { s -> s.isNotEmpty() } }

    /** Picks the working progressive video URL: prefer the one carrying `vs=` (full rendition). */
    private fun extractBestVideoUrl(html: String): String? {
        val urls = LinkedHashSet<String>()
        for (m in mp4Regex.findAll(html)) {
            val u = unescapeUrl(m.value)
            if (u.startsWith("http")) urls.add(u)
        }
        if (urls.isEmpty()) return null
        return urls.firstOrNull { it.contains("vs=") || it.contains("_nc_vs") }
            ?: urls.maxByOrNull { it.length }
    }

    private fun unescapeUrl(raw: String): String = raw
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")

    /* ---------------- Method 2: embed/captioned HTML (browser UA) ---------------- */

    private val videoUrlRegex = Regex("""\"video_url\":\"((?:[^"\\]|\\.)*)""")
    private val displayUrlRegex = Regex("""\"display_url\":\"((?:[^"\\]|\\.)*)""")
    private val usernameRegex = Regex("""class=\"UsernameText\">([^<]+)<""")
    private val edgeCaptionRegex =
        Regex("""\"edge_media_to_caption\":\{\"edges\":\[\{\"node\":\{\"text\":\"((?:[^"\\]|\\.)*)""")
    private val captionDivRegex = Regex("""<div class=\"Caption\"[^>]*>([\s\S]*?)</div>""")

    private fun fetchViaEmbed(shortcode: String): InstagramPost? = try {
        val req = Request.Builder()
            .url("https://www.instagram.com/p/$shortcode/embed/captioned/")
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "https://www.instagram.com/")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val html = resp.body?.string() ?: return@use null

            var videoUrl: String? = null
            var thumbnailUrl: String? = null
            var username: String? = null
            var caption: String? = null

            videoUrlRegex.find(html)?.groupValues?.get(1)?.let { videoUrl = unescapeJsonShort(it) }
            displayUrlRegex.find(html)?.groupValues?.get(1)?.let {
                thumbnailUrl = unescapeJsonShort(it)
            }
            usernameRegex.find(html)?.groupValues?.get(1)?.let { username = it.trim() }

            val edgeMatch = edgeCaptionRegex.find(html)
            if (edgeMatch != null) {
                caption = jsonUnescape(edgeMatch.groupValues[1])
            }
            if (caption.isNullOrBlank()) {
                val divMatch = captionDivRegex.find(html)
                if (divMatch != null) {
                    caption = htmlToText(divMatch.groupValues[1])
                }
            }

            val cleanedCaption = caption?.let { cleanCaption(it) }?.takeIf { it.isNotBlank() }
            if (videoUrl == null && thumbnailUrl == null && cleanedCaption == null) {
                return@use null
            }
            InstagramPost(
                shortcode = shortcode,
                type = if (videoUrl != null) "video" else "image",
                caption = cleanedCaption,
                hashtags = emptyList(),
                videoUrl = videoUrl,
                thumbnailUrl = thumbnailUrl,
                ownerUsername = username,
                method = "embed"
            )
        }
    } catch (e: Exception) {
        null
    }

    /* ---------------- Method 3: __a=1 JSON ---------------- */

    private fun fetchViaApi(shortcode: String): InstagramPost? = try {
        val req = Request.Builder()
            .url("https://www.instagram.com/p/$shortcode/?__a=1&__d=dis")
            .header("User-Agent", BROWSER_UA)
            .header("X-IG-App-ID", IG_APP_ID)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val bodyStr = resp.body?.string() ?: return@use null
            val root = try {
                JsonParser.parseString(bodyStr).asJsonObject
            } catch (e: Exception) {
                return@use null
            }

            var item: JsonObject? = null
            val itemsArr = root.get("items")
            if (itemsArr != null && itemsArr.isJsonArray && itemsArr.asJsonArray.size() > 0) {
                val first = itemsArr.asJsonArray.get(0)
                if (first.isJsonObject) item = first.asJsonObject
            }
            if (item == null) {
                val graphql = root.get("graphql")
                if (graphql != null && graphql.isJsonObject) {
                    val scm = graphql.asJsonObject.get("shortcode_media")
                    if (scm != null && scm.isJsonObject) item = scm.asJsonObject
                }
            }
            if (item == null) {
                val dataEl = root.get("data")
                if (dataEl != null && dataEl.isJsonObject) {
                    val scm = dataEl.asJsonObject.get("shortcode_media")
                    if (scm != null && scm.isJsonObject) item = scm.asJsonObject
                }
            }
            val media = item ?: return@use null

            val capEl = media.get("caption")
            var caption: String? = null
            if (capEl != null && capEl.isJsonObject) {
                caption = capEl.asJsonObject.optStringSafe("text")
            }

            var videoUrl: String? = media.optStringSafe("video_url")
            if (videoUrl == null) {
                val vv = media.get("video_versions")
                if (vv != null && vv.isJsonArray && vv.asJsonArray.size() > 0) {
                    val v0 = vv.asJsonArray.get(0)
                    if (v0.isJsonObject) {
                        videoUrl = v0.asJsonObject.optStringSafe("url")
                    }
                }
            }

            var thumbnailUrl: String? = null
            val iv2 = media.get("image_versions2")
            if (iv2 != null && iv2.isJsonObject) {
                val cands = iv2.asJsonObject.get("candidates")
                if (cands != null && cands.isJsonArray && cands.asJsonArray.size() > 0) {
                    val c0 = cands.asJsonArray.get(0)
                    if (c0.isJsonObject) {
                        thumbnailUrl = c0.asJsonObject.optStringSafe("url")
                    }
                }
            }
            if (thumbnailUrl == null) {
                thumbnailUrl = media.optStringSafe("display_url")
            }

            var username: String? = null
            val ownerEl = media.get("owner")
            if (ownerEl != null && ownerEl.isJsonObject) {
                username = ownerEl.asJsonObject.optStringSafe("username")
            }

            val cleanedCaption = caption?.takeIf { it.isNotBlank() }
            if (cleanedCaption == null && videoUrl == null && thumbnailUrl == null) {
                return@use null
            }
            InstagramPost(
                shortcode = shortcode,
                type = if (videoUrl != null) "video" else "image",
                caption = cleanedCaption,
                hashtags = emptyList(),
                videoUrl = videoUrl,
                thumbnailUrl = thumbnailUrl,
                ownerUsername = username,
                method = "api"
            )
        }
    } catch (e: Exception) {
        null
    }

    /* ---------------- Method 4: og: meta tags (browser UA) ---------------- */

    private fun fetchViaOg(shortcode: String): InstagramPost? = try {
        val req = Request.Builder()
            .url("https://www.instagram.com/p/$shortcode/")
            .header("User-Agent", BROWSER_UA)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val html = resp.body?.string() ?: return@use null

            val og = parseOgTags(html)
            if (og.isEmpty()) return@use null

            val rawDescription = og["description"] ?: og["title"]
            val caption = rawDescription?.let { cleanCaption(it) }?.takeIf { it.isNotBlank() }
            val videoUrl = og["video:secure_url"] ?: og["video:url"] ?: og["video"]
            val thumbnailUrl = og["image"]

            if (caption == null && videoUrl == null && thumbnailUrl == null) {
                return@use null
            }
            InstagramPost(
                shortcode = shortcode,
                type = if (videoUrl != null) "video" else "image",
                caption = caption,
                ogTitle = og["title"],
                hashtags = emptyList(),
                videoUrl = videoUrl,
                thumbnailUrl = thumbnailUrl,
                ownerUsername = null,
                method = "og"
            )
        }
    } catch (e: Exception) {
        null
    }

    /* ---------------- Helpers ---------------- */

    /** Extract hashtags `#[\p{L}\p{N}_]+`, deduped, capped at 20. */
    fun extractHashtags(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val regex = Regex("#[\\p{L}\\p{N}_]+")
        val out = mutableListOf<String>()
        for (m in regex.findAll(text)) {
            val tag = m.value
            if (!out.contains(tag)) out.add(tag)
            if (out.size >= 20) break
        }
        return out
    }

    /** Mirrors smart-engine caption cleaning. */
    private fun cleanCaption(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("^[\\w.\\-]+ on Instagram:\\s*"), "")
        s = s.replace(Regex("See more on Instagram[\\s\\S]*$"), "")
        return s.trim()
    }

    /** `\u0026` → `&`, `\/` → `/`, `\"` → `"` (for URLs extracted from JSON-in-HTML). */
    private fun unescapeJsonShort(raw: String): String = raw
        .replace("\\u0026", "&")
        .replace("\\/", "/")
        .replace("\\\"", "\"")

    /** Full JSON-string unescape via Gson, with a manual fallback. */
    private fun jsonUnescape(raw: String): String = try {
        gson.fromJson("\"$raw\"", String::class.java) ?: unescapeJsonShort(raw)
    } catch (e: Exception) {
        unescapeJsonShort(raw)
    }

    private fun htmlToText(html: String): String {
        val withoutBreaks = html.replace(Regex("<br\\s*/?>"), "\n")
        val withoutTags = withoutBreaks.replace(Regex("<[^>]+>"), "")
        return htmlDecode(withoutTags).trim()
    }

    private fun htmlDecode(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&#x27;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#x2F;", "/")

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
