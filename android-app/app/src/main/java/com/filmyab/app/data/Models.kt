package com.filmyab.app.data

/**
 * Data models of FilmYab (fully on-device smart engine — no server).
 * All JSON-facing fields are nullable (or defaulted) so Gson never crashes.
 */

/** One candidate movie suggested by the engine. */
data class Candidate(
    val name: String? = null,
    val year: String? = null,
    val confidence: Double = 0.0,
    val reason: String? = null
)

/** Final result of the on-device smart engine. */
data class SmartResult(
    val status: String = "success", // success | low_confidence
    val titleEn: String? = null,
    val titleFa: String? = null,
    val year: String? = null,
    val director: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val plot: String? = null,
    val confidence: Double = 0.0,
    val reasoningFa: String? = null,
    val posterUrl: String? = null,
    val wikiUrl: String? = null,
    val googleQuery: String? = null,
    val candidates: List<Candidate> = emptyList(),
    val sceneSummary: String? = null,
    val evidenceSummary: String? = null,
    val instagramUrl: String? = null,
    val sourceMode: String = "smart",
    /* ---- v3.1 full-spec fields ---- */
    val duration: String? = null,
    val country: String? = null,
    val imdbUrl: String? = null
)

/**
 * Connectivity state detected automatically.
 * kind: "checking" | "ok" (instagram reachable) | "vpn_needed" (internet ok, IG blocked) | "offline"
 */
data class NetState(
    val kind: String = "checking",
    val instagramOk: Boolean = false,
    val internetOk: Boolean = false
)

/** Instagram post scraped entirely on device. */
data class InstagramPost(
    val shortcode: String,
    val type: String,
    val caption: String? = null,
    val ogTitle: String? = null,
    val hashtags: List<String> = emptyList(),
    val videoUrl: String? = null,
    val thumbnailUrl: String? = null,
    val ownerUsername: String? = null,
    val method: String = ""
)

/** Wikipedia/Wikidata evidence attached to a verified candidate. */
data class WikiEvidence(
    val pageTitle: String? = null,
    val pageDescription: String? = null,
    val year: String? = null,
    val director: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val plot: String? = null,
    val pageUrl: String? = null,
    val posterUrl: String? = null,
    val faTitle: String? = null,
    val isFilm: Boolean = false,
    /* ---- v3.1 full-spec fields (winner enrichment only) ---- */
    val duration: String? = null,
    val country: String? = null,
    val imdbUrl: String? = null
)

/** A candidate after verification + scoring. */
data class VerifiedCandidate(
    val candidate: Candidate,
    val wiki: WikiEvidence?,
    val score: Double
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
    val source: String = "smart",
    val createdAtMs: Long = 0L,
    val genres: List<String> = emptyList()
)
