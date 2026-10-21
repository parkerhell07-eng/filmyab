package com.filmyab.app.data

/**
 * Data models shared across the app. All JSON-facing fields are nullable (or defaulted)
 * so Gson deserialization never crashes on missing keys.
 */

/** Progress event from the server SSE pipeline. */
data class StepEvent(
    val step: String? = null,
    val status: String? = null,
    val data: Map<String, Any?>? = null
)

/** Terminal success payload of POST /api/identify. */
data class PipelineResult(
    val status: String? = null,
    val movieName: String? = null,
    val originalName: String? = null,
    val year: String? = null,
    val director: String? = null,
    val genres: List<String> = emptyList(),
    val confidence: Double = 0.0,
    val reasoning: String? = null,
    val candidates: List<Candidate> = emptyList(),
    val posterUrl: String? = null,
    val sources: List<WebSource> = emptyList(),
    val instagramUrl: String? = null,
    val caption: String? = null,
    val sceneDescription: String? = null,
    val sourceMode: String? = null
)

data class Candidate(
    val name: String? = null,
    val year: String? = null,
    val confidence: Double = 0.0
)

data class WebSource(
    val name: String? = null,
    val url: String? = null
)

/** Item shape of GET /api/history (server-side history). */
data class HistoryItem(
    val id: String? = null,
    val instagramUrl: String? = null,
    val status: String? = null,
    val movieName: String? = null,
    val originalName: String? = null,
    val year: String? = null,
    val confidence: Double = 0.0,
    val posterUrl: String? = null,
    val source: String? = null,
    val genres: List<String> = emptyList(),
    val createdAt: String? = null
)

/** On-device quick-mode result (Pollinations text LLM). */
data class QuickResult(
    val found: Boolean = false,
    val title: String? = null,
    val titleFa: String? = null,
    val year: String? = null,
    val confidence: Double = 0.0,
    val reasoningFa: String? = null,
    val alternatives: List<Candidate> = emptyList(),
    val googleQuery: String? = null
)

/** Instagram post scraped on-device by [InstagramScraper]. */
data class InstagramPost(
    val shortcode: String,
    val type: String,
    val caption: String? = null,
    val hashtags: List<String> = emptyList(),
    val videoUrl: String? = null,
    val thumbnailUrl: String? = null,
    val ownerUsername: String? = null,
    val method: String = ""
)

/** Local (on-device) history entry persisted in SharedPreferences. */
data class LocalHistoryItem(
    val status: String = "",
    val movieName: String? = null,
    val originalName: String? = null,
    val year: String? = null,
    val confidence: Double = 0.0,
    val posterUrl: String? = null,
    val instagramUrl: String? = null,
    val source: String = "link",
    val createdAtMs: Long = 0L,
    val genres: List<String> = emptyList()
)
