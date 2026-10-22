package com.filmyab.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One newer release advertised by GitHub; produced by [UpdateChecker.checkLatest].
 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val notes: String?
)

/**
 * In-app update: checks the app's public GitHub Releases (keyless) and, when a
 * newer build exists, downloads the APK into app-private storage and hands it to
 * the Android package installer through a FileProvider.
 *
 * The release body must contain a `versionCode: <N>` marker (kept in sync by the
 * CI workflow). If the marker is missing, the tag `vMAJOR.MINOR[.x]` is compared
 * against the installed versionName as a fallback.
 */
object UpdateChecker {

    private const val REPO = "parkerhell07-eng/filmyab"

    private const val UA = "FilmYab-Android/3.0"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun JsonObject.optStringSafe(key: String): String? {
        val el = this.get(key) ?: return null
        if (el.isJsonNull || !el.isJsonPrimitive) return null
        return try {
            el.asString.trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns [UpdateInfo] when a newer release exists, otherwise null.
     * Never throws — update checking must stay invisible to the user on failure.
     */
    suspend fun checkLatest(currentVersionCode: Long, currentVersionName: String): UpdateInfo? =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/$REPO/releases/latest")
                    .header("User-Agent", UA)
                    .header("Accept", "application/vnd.github+json")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val bodyStr = resp.body?.string() ?: return@withContext null
                    val root = try {
                        JsonParser.parseString(bodyStr).asJsonObject
                    } catch (e: Exception) {
                        return@withContext null
                    }
                    val tag = root.optStringSafe("tag_name") ?: return@withContext null

                    var anyApk: String? = null
                    var debugApk: String? = null
                    val assets = root.getAsJsonArray("assets")
                    if (assets != null && assets.isJsonArray) {
                        for (el in assets) {
                            val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                            val name = o.optStringSafe("name") ?: continue
                            val url = o.optStringSafe("browser_download_url") ?: continue
                            if (name.endsWith(".apk")) {
                                if (anyApk == null) anyApk = url
                                if (name.endsWith("debug.apk") && debugApk == null) debugApk = url
                            }
                        }
                    }
                    val apkUrl = debugApk ?: anyApk ?: return@withContext null
                    val notes = root.optStringSafe("body")

                    val marker = Regex("""versionCode:\s*(\d+)""").find(notes ?: "")
                    val releaseCode = marker?.groupValues?.get(1)?.toLongOrNull()

                    val isNewer: Boolean = if (releaseCode != null) {
                        releaseCode > currentVersionCode
                    } else {
                        // Fallback: compare vMAJOR.MINOR against the installed versionName.
                        val tagMatch = Regex("""v?(\d+)\.(\d+)""").find(tag)
                        val curMatch = Regex("""(\d+)\.(\d+)""").find(currentVersionName)
                        if (tagMatch != null && curMatch != null) {
                            val tm = tagMatch.groupValues[1].toInt()
                            val tn = tagMatch.groupValues[2].toInt()
                            val cm = curMatch.groupValues[1].toInt()
                            val cn = curMatch.groupValues[2].toInt()
                            tm > cm || (tm == cm && tn > cn)
                        } else {
                            false
                        }
                    }
                    if (!isNewer) return@withContext null

                    val tagMatch = Regex("""v?(\d+\.\d+(?:\.\d+)?)""").find(tag)
                    UpdateInfo(
                        versionName = tagMatch?.groupValues?.get(1) ?: tag,
                        versionCode = releaseCode ?: (currentVersionCode + 1),
                        apkUrl = apkUrl,
                        notes = notes
                    )
                }
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Downloads the APK into app-private storage with progress callbacks
     * (0..100). Returns null on failure; never throws.
     */
    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.getExternalFilesDir(null) ?: context.cacheDir, "updates")
            dir.mkdirs()
            val target = File(dir, "filmyab-update.apk")
            val tmp = File(dir, "filmyab-update.tmp")
            if (tmp.exists()) tmp.delete()
            if (target.exists()) target.delete()

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                val total = body.contentLength()
                var written = 0L
                var lastPct = -1
                tmp.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (total > 0) {
                                val pct = ((written * 100) / total).toInt().coerceIn(0, 99)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
                if (tmp.length() < 100_000L) {
                    tmp.delete()
                    return@withContext null
                }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                target
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Hands the downloaded APK to the Android installer (FileProvider URI). */
    fun installApk(context: Context, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                apk
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            // Installer not reachable — silently ignore.
        }
    }
}
