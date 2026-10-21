package com.filmyab.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.filmyab.app.data.ApiClient
import com.filmyab.app.data.FallbackEngine
import com.filmyab.app.data.HistoryStore
import com.filmyab.app.data.InstagramScraper
import com.filmyab.app.data.LocalHistoryItem
import com.filmyab.app.data.PipelineResult
import com.filmyab.app.data.QuickResult
import com.filmyab.app.data.StepEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

/** One pipeline step rendered in the running card. status: pending|start|done|skip|error */
data class StepUi(
    val key: String,
    val label: String,
    val status: String
)

sealed interface UiState {
    data object Idle : UiState
    data class Running(
        val steps: List<StepUi>,
        val currentLabel: String,
        val isFallback: Boolean
    ) : UiState

    data class Success(
        val result: PipelineResult,
        val quick: QuickResult?,
        val isFallback: Boolean
    ) : UiState

    data class Error(
        val message: String,
        val hint: String?,
        val canTryScreenshot: Boolean
    ) : UiState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "filmyab_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val DEFAULT_SERVER_URL = "http://10.0.2.2:3000"

        private const val STEP_FETCH = "fetch"
        private const val STEP_ANALYZE = "analyze"
        private const val STEP_SEARCH = "search"
        private const val STEP_MATCH = "match"
        private const val STEP_POSTER = "poster"
        private const val STEP_QUICK = "quick"

        private const val LABEL_FETCH = "دریافت پست از اینستاگرام"
        private const val LABEL_ANALYZE = "تحلیل صحنه با هوش مصنوعی"
        private const val LABEL_SEARCH = "جستجو در وب"
        private const val LABEL_MATCH = "تطبیق هوشمند"
        private const val LABEL_POSTER = "یافتن پوستر"
        private const val LABEL_QUICK = "تحلیل سریع روی دستگاه"

        private const val MSG_EMPTY_INPUT = "لینک اینستاگرام را وارد کنید یا یک اسکرین‌شات انتخاب کنید."
        private const val MSG_NOT_IG_LINK =
            "این یک لینک معتبر پست/ریلز اینستاگرام نیست. لینک را از دکمه «کپی لینک» خود اینستاگرام بگیرید."
        private const val MSG_SERVER_REQUIRED = "برای حالت اسکرین‌شات باید سرور در تنظیمات در دسترس باشد."
        private const val MSG_SERVER_UNREACHABLE =
            "اتصال به سرور ممکن نشد. آدرس سرور و اتصال اینترنت را بررسی کنید."
        private const val MSG_FALLBACK_NOTE = "اتصال به سرور ممکن نشد؛ تحلیل سریع روی دستگاه…"
        private const val MSG_IG_BLOCKED =
            "اینستاگرام اجازه خواندن این پست را نداد. برای تحلیل کامل، سرور را در تنظیمات وارد کنید."
        private const val MSG_QUICK_FAILED =
            "نتوانستم فیلم را تشخیص دهم. راهنمای دقیق‌تری اضافه کنید یا از حالت سرور استفاده کنید."
        private const val MSG_INCOMPLETE_SERVER =
            "پاسخ سرور ناقص بود. لطفاً دوباره تلاش کنید."
        private const val MSG_UNEXPECTED = "خطای غیرمنتظره رخ داد. لطفاً دوباره تلاش کنید."
    }

    private val historyStore = HistoryStore(application)

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _serverUrl = MutableStateFlow(loadServerUrl(application))
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _history = MutableStateFlow<List<LocalHistoryItem>>(historyStore.getAll())
    val history: StateFlow<List<LocalHistoryItem>> = _history.asStateFlow()

    private val _imagePicked = MutableStateFlow(false)
    val imagePicked: StateFlow<Boolean> = _imagePicked.asStateFlow()

    /** Hoisted so the text survives tab switches and resetToIdle. */
    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    private val _noteInput = MutableStateFlow("")
    val noteInput: StateFlow<String> = _noteInput.asStateFlow()

    data class ConnTestState(
        val testing: Boolean = false,
        val ok: Boolean? = null
    )

    private val _connTest = MutableStateFlow(ConnTestState())
    val connTest: StateFlow<ConnTestState> = _connTest.asStateFlow()

    private var imageBase64: String? = null
    private var pendingScreenshotRun = false
    private var pendingScreenshotNote = ""

    /* ---------------- Server URL ---------------- */

    private fun loadServerUrl(app: Application): String {
        return try {
            val raw = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
            ApiClient.normalizeBaseUrl(raw)
        } catch (e: Exception) {
            DEFAULT_SERVER_URL
        }
    }

    fun setServerUrl(raw: String) {
        val normalized = ApiClient.normalizeBaseUrl(raw)
        _serverUrl.value = normalized
        try {
            getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SERVER_URL, normalized).apply()
        } catch (e: Exception) {
            // persistence is best-effort
        }
    }

    fun testConnection() {
        _connTest.value = ConnTestState(testing = true, ok = null)
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                ApiClient.testServer(ApiClient.normalizeBaseUrl(_serverUrl.value))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            _connTest.value = ConnTestState(testing = false, ok = ok)
        }
    }

    /* ---------------- Inputs ---------------- */

    fun setUrlInput(value: String) {
        _urlInput.value = value
    }

    fun setNoteInput(value: String) {
        _noteInput.value = value
    }

    fun onImagePicked(uri: Uri?) {
        if (uri == null) {
            pendingScreenshotRun = false
            _imagePicked.value = false
            imageBase64 = null
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = getApplication<Application>().contentResolver
                    .openInputStream(uri)?.use { stream -> stream.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    imageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    _imagePicked.value = true
                    if (pendingScreenshotRun) {
                        pendingScreenshotRun = false
                        identify("", pendingScreenshotNote)
                    }
                } else {
                    imageBase64 = null
                    _imagePicked.value = false
                    pendingScreenshotRun = false
                }
            } catch (e: Exception) {
                imageBase64 = null
                _imagePicked.value = false
                pendingScreenshotRun = false
            }
        }
    }

    fun clearImage() {
        imageBase64 = null
        _imagePicked.value = false
    }

    /** Arm the auto-run: the next successfully picked image triggers screenshot identify. */
    fun requestScreenshotIdentify(note: String) {
        pendingScreenshotNote = note
        pendingScreenshotRun = true
    }

    fun resetToIdle() {
        _uiState.value = UiState.Idle
    }

    /* ---------------- Identify orchestration ---------------- */

    fun identify(rawUrl: String, note: String) {
        val url = rawUrl.trim()
        val image = imageBase64
        if (url.isEmpty() && image == null) {
            _uiState.value = UiState.Error(MSG_EMPTY_INPUT, null, false)
            return
        }
        if (url.isNotEmpty() && !InstagramScraper.looksLikeInstagramUrl(url)) {
            _uiState.value = UiState.Error(MSG_NOT_IG_LINK, null, false)
            return
        }
        pendingScreenshotRun = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val base = ApiClient.normalizeBaseUrl(_serverUrl.value)
                if (image != null) {
                    // Screenshot mode: server is mandatory.
                    if (base.isBlank()) {
                        _uiState.value = UiState.Error(MSG_SERVER_REQUIRED, null, false)
                        return@launch
                    }
                    _uiState.value = UiState.Running(initialSteps(), "بررسی اتصال به سرور…", false)
                    val reachable = try {
                        ApiClient.testServer(base)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                    if (!reachable) {
                        _uiState.value = UiState.Error(MSG_SERVER_REQUIRED, null, false)
                        return@launch
                    }
                    runServerIdentify(base, null, image, note.takeIf { it.isNotBlank() }, false)
                } else {
                    // Link mode: try server, fall back to quick mode on transport failure.
                    if (base.isBlank()) {
                        startQuickFallback(url, note.takeIf { it.isNotBlank() })
                        return@launch
                    }
                    val steps = initialSteps()
                    _uiState.value = UiState.Running(steps, steps.first().label, false)
                    runServerIdentify(base, url, null, note.takeIf { it.isNotBlank() }, true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = UiState.Error(MSG_UNEXPECTED, null, false)
            }
        }
    }

    private suspend fun runServerIdentify(
        baseUrl: String,
        url: String?,
        imageB64: String?,
        note: String?,
        allowQuickFallback: Boolean
    ) {
        var receivedResult: PipelineResult? = null
        var errorMessage: String? = null
        var errorHint: String? = null
        var transportFailed = false
        try {
            ApiClient.identifySse(
                baseUrl = baseUrl,
                url = url,
                imageBase64 = imageB64,
                note = note,
                onStep = { event ->
                    val current = _uiState.value
                    if (current is UiState.Running && !current.isFallback) {
                        val updated = applyStepEvent(current.steps, event)
                        _uiState.value = current.copy(
                            steps = updated,
                            currentLabel = currentLabelFor(updated)
                        )
                    }
                },
                onResult = { r -> receivedResult = r },
                onError = { message, hint ->
                    errorMessage = message
                    errorHint = hint
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            transportFailed = true
        }

        val result = receivedResult
        val message = errorMessage
        when {
            result != null -> {
                _uiState.value = UiState.Success(result = result, quick = null, isFallback = false)
                saveResult(result)
            }
            message != null -> {
                _uiState.value = UiState.Error(message = message, hint = errorHint, canTryScreenshot = true)
            }
            transportFailed -> {
                if (allowQuickFallback && url != null) {
                    startQuickFallback(url, note)
                } else {
                    _uiState.value = UiState.Error(MSG_SERVER_UNREACHABLE, null, false)
                }
            }
            else -> {
                if (allowQuickFallback && url != null) {
                    startQuickFallback(url, note)
                } else {
                    _uiState.value = UiState.Error(MSG_INCOMPLETE_SERVER, null, false)
                }
            }
        }
    }

    private suspend fun startQuickFallback(url: String, note: String?) {
        _uiState.value = UiState.Running(
            steps = listOf(
                StepUi(STEP_FETCH, LABEL_FETCH, "start"),
                StepUi(STEP_QUICK, LABEL_QUICK, "pending")
            ),
            currentLabel = MSG_FALLBACK_NOTE,
            isFallback = true
        )

        val post = try {
            InstagramScraper.fetchPost(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (post == null) {
            _uiState.value = UiState.Error(MSG_IG_BLOCKED, null, false)
            return
        }

        _uiState.value = UiState.Running(
            steps = listOf(
                StepUi(STEP_FETCH, LABEL_FETCH, "done"),
                StepUi(STEP_QUICK, LABEL_QUICK, "start")
            ),
            currentLabel = LABEL_QUICK,
            isFallback = true
        )

        val quick = try {
            FallbackEngine.quickIdentify(post, note)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (quick == null || !quick.found) {
            _uiState.value = UiState.Error(quick?.reasoningFa ?: MSG_QUICK_FAILED, null, false)
            return
        }

        val pseudoResult = PipelineResult(
            status = "success",
            movieName = quick.titleFa?.takeIf { it.isNotBlank() } ?: quick.title,
            originalName = quick.title,
            year = quick.year,
            confidence = quick.confidence,
            reasoning = quick.reasoningFa,
            instagramUrl = url,
            sourceMode = "link"
        )
        _uiState.value = UiState.Success(result = pseudoResult, quick = quick, isFallback = true)
        historyStore.add(
            LocalHistoryItem(
                status = "success",
                movieName = pseudoResult.movieName,
                originalName = quick.title,
                year = quick.year,
                confidence = quick.confidence,
                posterUrl = null,
                instagramUrl = url,
                source = "quick",
                createdAtMs = System.currentTimeMillis(),
                genres = emptyList()
            )
        )
        refreshHistory()
    }

    /* ---------------- Steps ---------------- */

    private fun initialSteps(): List<StepUi> = listOf(
        StepUi(STEP_FETCH, LABEL_FETCH, "pending"),
        StepUi(STEP_ANALYZE, LABEL_ANALYZE, "pending"),
        StepUi(STEP_SEARCH, LABEL_SEARCH, "pending"),
        StepUi(STEP_MATCH, LABEL_MATCH, "pending"),
        StepUi(STEP_POSTER, LABEL_POSTER, "pending")
    )

    private fun applyStepEvent(steps: List<StepUi>, event: StepEvent): List<StepUi> {
        val key = event.step ?: return steps
        val status = event.status ?: return steps
        if (steps.none { it.key == key }) return steps
        return steps.map { if (it.key == key) it.copy(status = status) else it }
    }

    private fun currentLabelFor(steps: List<StepUi>): String {
        return steps.lastOrNull { it.status == "start" }?.label
            ?: steps.lastOrNull { it.status != "pending" }?.label
            ?: steps.firstOrNull()?.label
            ?: ""
    }

    /* ---------------- History ---------------- */

    private fun saveResult(r: PipelineResult) {
        historyStore.add(
            LocalHistoryItem(
                status = r.status ?: "success",
                movieName = r.movieName,
                originalName = r.originalName,
                year = r.year,
                confidence = r.confidence,
                posterUrl = r.posterUrl,
                instagramUrl = r.instagramUrl,
                source = r.sourceMode ?: "link",
                createdAtMs = System.currentTimeMillis(),
                genres = r.genres
            )
        )
        refreshHistory()
    }

    private fun refreshHistory() {
        _history.value = historyStore.getAll()
    }

    fun clearHistory() {
        historyStore.clear()
        _history.value = emptyList()
    }
}
