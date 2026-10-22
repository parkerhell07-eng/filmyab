package com.filmyab.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.filmyab.app.data.HistoryStore
import com.filmyab.app.data.InstagramScraper
import com.filmyab.app.data.LocalHistoryItem
import com.filmyab.app.data.NetState
import com.filmyab.app.data.SmartEngine
import com.filmyab.app.data.SmartResult
import com.filmyab.app.data.UpdateChecker
import com.filmyab.app.data.UpdateInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** One engine step rendered in the running card. status: pending|start|done|skip|error */
data class StepUi(
    val key: String,
    val label: String,
    val status: String
)

/** In-app update state machine surfaced on the identify screen. */
sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data class Available(val info: UpdateInfo) : UpdatePhase
    data class Downloading(val progress: Int) : UpdatePhase
    data class Ready(val info: UpdateInfo, val apk: File) : UpdatePhase
    data class Failed(val message: String) : UpdatePhase
}

sealed interface UiState {
    data object Idle : UiState
    data class Running(
        val steps: List<StepUi>,
        val currentLabel: String
    ) : UiState

    data class Success(val result: SmartResult) : UiState
    data class Error(
        val message: String,
        val needVpn: Boolean = false
    ) : UiState
}

/**
 * FilmYab v2 ViewModel — 100% on-device smart engine. There is no server to configure:
 * the app checks connectivity (VPN) automatically, can auto-read an Instagram link from
 * the clipboard and starts identification by itself.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "filmyab_prefs"
        private const val KEY_LAST_AUTO = "last_auto_url"

        private const val STEP_NET = SmartEngine.STEP_NET
        private const val STEP_FETCH = SmartEngine.STEP_FETCH
        private const val STEP_ANALYZE = SmartEngine.STEP_ANALYZE
        private const val STEP_MATCH = SmartEngine.STEP_MATCH
        private const val STEP_VERIFY = SmartEngine.STEP_VERIFY
        private const val STEP_FINAL = SmartEngine.STEP_FINAL

        private const val LABEL_NET = "بررسی اتصال و فیلترشکن"
        private const val LABEL_FETCH = "خواندن پست از اینستاگرام"
        private const val LABEL_ANALYZE = "استخراج فریم و متن روی تصویر"
        private const val LABEL_MATCH = "تحلیل هوشمند شواهد"
        private const val LABEL_VERIFY = "تأیید با پایگاه فیلم"
        private const val LABEL_FINAL = "تدوین نتیجه نهایی"

        private const val MSG_EMPTY_INPUT = "لینک اینستاگرام در کلیپ‌بورد نبود؛ لینک را بچسبانید یا کپی کنید."
        private const val MSG_NOT_IG_LINK =
            "این یک لینک معتبر پست/ریلز اینستاگرام نیست. لینک را از دکمه «کپی لینک» خود اینستاگرام بگیرید."
        private const val MSG_UNEXPECTED = "خطای غیرمنتظره رخ داد. لطفاً دوباره تلاش کنید."
    }

    private val historyStore = HistoryStore(application)

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _netState = MutableStateFlow(NetState(kind = "checking"))
    val netState: StateFlow<NetState> = _netState.asStateFlow()

    private val _history = MutableStateFlow<List<LocalHistoryItem>>(historyStore.getAll())
    val history: StateFlow<List<LocalHistoryItem>> = _history.asStateFlow()

    private val _updatePhase = MutableStateFlow<UpdatePhase>(UpdatePhase.Idle)
    val updatePhase: StateFlow<UpdatePhase> = _updatePhase.asStateFlow()

    private var identifyJob: Job? = null
    private var lastUpdateInfo: UpdateInfo? = null

    private var lastAutoUrl: String = try {
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_AUTO, "") ?: ""
    } catch (e: Exception) {
        ""
    }

    /** Hoisted so the text survives tab switches and resetToIdle. */
    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput.asStateFlow()

    init {
        refreshNetwork()
        checkForAppUpdate()
    }

    /* ---------------- in-app update ---------------- */

    /** Silent, one-shot check against the public GitHub Releases (keyless). */
    private fun checkForAppUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                delay(2500) // let the UI settle first
                val app = getApplication<Application>()
                val (code, name) = try {
                    val info = app.packageManager.getPackageInfo(app.packageName, 0)
                    Pair(info.longVersionCode, info.versionName ?: "")
                } catch (e: Exception) {
                    Pair(0L, "")
                }
                val update = UpdateChecker.checkLatest(code, name)
                if (update != null && _updatePhase.value is UpdatePhase.Idle) {
                    lastUpdateInfo = update
                    _updatePhase.value = UpdatePhase.Available(update)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // update check is best-effort and silent
            }
        }
    }

    fun downloadUpdate() {
        val info = (_updatePhase.value as? UpdatePhase.Available)?.info
            ?: lastUpdateInfo
            ?: return
        lastUpdateInfo = info
        _updatePhase.value = UpdatePhase.Downloading(0)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apk = UpdateChecker.downloadApk(getApplication(), info.apkUrl) { pct ->
                    _updatePhase.value = UpdatePhase.Downloading(pct)
                }
                _updatePhase.value = if (apk != null) {
                    UpdatePhase.Ready(info, apk)
                } else {
                    UpdatePhase.Failed("دانلود به‌روزرسانی ناموفق بود — دوباره تلاش کن.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _updatePhase.value = UpdatePhase.Failed("دانلود به‌روزرسانی ناموفق بود — دوباره تلاش کن.")
            }
        }
    }

    fun installUpdate() {
        val ready = _updatePhase.value as? UpdatePhase.Ready ?: return
        UpdateChecker.installApk(getApplication(), ready.apk)
    }

    fun dismissUpdate() {
        _updatePhase.value = UpdatePhase.Idle
    }

    /* ---------------- connectivity ---------------- */

    fun refreshNetwork() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = try {
                SmartEngine.checkNetwork()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                NetState(kind = "offline", instagramOk = false, internetOk = false)
            }
            _netState.value = state
        }
    }

    /* ---------------- inputs ---------------- */

    fun setUrlInput(value: String) {
        _urlInput.value = value
    }

    fun resetToIdle() {
        _uiState.value = UiState.Idle
    }

    /**
     * Called from MainActivity.onResume. Refreshes the VPN/connectivity status and —
     * the zero-input magic — if the clipboard holds an Instagram link, fills the field
     * and starts identification automatically (once per link).
     */
    fun onAppResumed(clipboardText: String?) {
        refreshNetwork()
        val clip = clipboardText?.trim() ?: return
        if (!InstagramScraper.looksLikeInstagramUrl(clip)) return
        if (_urlInput.value != clip) {
            _urlInput.value = clip
        }
        maybeAutoStart(clip)
    }

    private fun maybeAutoStart(url: String) {
        if (url == lastAutoUrl) return
        when (_uiState.value) {
            is UiState.Running -> return
            else -> Unit
        }
        lastAutoUrl = url
        try {
            getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_LAST_AUTO, url).apply()
        } catch (e: Exception) {
            // best-effort persistence
        }
        identify(url)
    }

    /* ---------------- identify orchestration ---------------- */

    fun identifyFromUi() {
        identify(_urlInput.value)
    }

    /** Stops the running engine at once (the user is never stuck waiting). */
    fun cancelIdentify() {
        identifyJob?.cancel()
        identifyJob = null
        _uiState.value = UiState.Idle
    }

    private fun identify(rawUrl: String) {
        val url = rawUrl.trim()
        if (url.isEmpty()) {
            _uiState.value = UiState.Error(MSG_EMPTY_INPUT)
            return
        }
        if (!InstagramScraper.looksLikeInstagramUrl(url)) {
            _uiState.value = UiState.Error(MSG_NOT_IG_LINK)
            return
        }
        val steps = initialSteps()
        _uiState.value = UiState.Running(steps, steps.first().label)

        identifyJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val outcome = SmartEngine.identify(
                    appContext = getApplication(),
                    rawUrl = url,
                    onStep = { key, status -> applyStep(key, status) }
                )) {
                    is SmartEngine.Outcome.Done -> {
                        val result = outcome.result.copy(instagramUrl = url)
                        _uiState.value = UiState.Success(result)
                        saveHistory(result)
                    }
                    is SmartEngine.Outcome.Fail -> {
                        _uiState.value = UiState.Error(outcome.message, outcome.needVpn)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = UiState.Error(MSG_UNEXPECTED)
            }
        }
    }

    private fun applyStep(key: String, status: String) {
        val current = _uiState.value
        if (current !is UiState.Running) return
        val updated = current.steps.map { if (it.key == key) it.copy(status = status) else it }
        if (status == "start") {
            val label = updated.firstOrNull { it.key == key }?.label ?: ""
            _uiState.value = current.copy(steps = updated, currentLabel = label)
        } else {
            val next = updated.firstOrNull { it.status == "pending" }?.label ?: ""
            _uiState.value = current.copy(
                steps = updated,
                currentLabel = if (updated.any { it.status == "start" }) {
                    updated.first { it.status == "start" }.label
                } else {
                    next
                }
            )
        }
    }

    private fun initialSteps(): List<StepUi> = listOf(
        StepUi(STEP_NET, LABEL_NET, "pending"),
        StepUi(STEP_FETCH, LABEL_FETCH, "pending"),
        StepUi(STEP_ANALYZE, LABEL_ANALYZE, "pending"),
        StepUi(STEP_MATCH, LABEL_MATCH, "pending"),
        StepUi(STEP_VERIFY, LABEL_VERIFY, "pending"),
        StepUi(STEP_FINAL, LABEL_FINAL, "pending")
    )

    /* ---------------- history ---------------- */

    private fun saveHistory(r: SmartResult) {
        historyStore.add(
            LocalHistoryItem(
                status = r.status,
                movieName = r.titleFa?.takeIf { it.isNotBlank() },
                originalName = r.titleEn,
                year = r.year,
                confidence = r.confidence,
                posterUrl = r.posterUrl,
                instagramUrl = r.instagramUrl,
                source = "smart",
                createdAtMs = System.currentTimeMillis(),
                genres = r.genres
            )
        )
        _history.value = historyStore.getAll()
    }

    fun clearHistory() {
        historyStore.clear()
        _history.value = emptyList()
    }
}
