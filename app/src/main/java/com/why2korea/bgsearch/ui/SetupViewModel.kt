package com.why2korea.bgsearch.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.why2korea.bgsearch.data.HistoryItem
import com.why2korea.bgsearch.data.SearchConfig
import com.why2korea.bgsearch.data.SettingsStore
import com.why2korea.bgsearch.engine.SearchEngine
import com.why2korea.bgsearch.service.OverlayService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "BgSearchSetupVM"

data class SetupUiState(
    val config: SearchConfig = SearchConfig(),
    val newSecondary: String = "",
    val newTertiary: String = "",
    val history: List<HistoryItem> = emptyList(),
    val advancedOpen: Boolean = false,
    val notifyOpen: Boolean = false,
    val loaded: Boolean = false,
    val message: String = ""
)

/**
 * 설정 화면 전용 ViewModel.
 * 탐색 루프는 OverlayService 가 소유하므로 여기서는 설정 편집과 명령 전송만 한다.
 */
class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)

    private val _ui = MutableStateFlow(SetupUiState())
    val ui: StateFlow<SetupUiState> = _ui.asStateFlow()

    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                _ui.update { it.copy(config = store.configFlow.first(), loaded = true) }
            } catch (e: Throwable) {
                Log.w(TAG, "config load failed", e)
                _ui.update { it.copy(loaded = true) }
            }
        }
        viewModelScope.launch {
            try {
                store.historyFlow.collect { h -> _ui.update { it.copy(history = h) } }
            } catch (e: Throwable) {
                Log.w(TAG, "history collect failed", e)
            }
        }
    }

    // ------------------------------------------------------------------ 편집

    private fun mutate(block: (SearchConfig) -> SearchConfig) {
        _ui.update { it.copy(config = block(it.config)) }
        scheduleSave()
    }

    /** 입력이 멈춘 뒤 400ms 후에 저장한다. (DataStore 쓰기 폭주 방지) */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            saveNow()
        }
    }

    suspend fun saveNow() {
        try {
            store.save(_ui.value.config)
            OverlayService.reloadConfig(getApplication())
        } catch (e: Throwable) {
            Log.w(TAG, "save failed", e)
        }
    }

    fun onPrimaryChange(v: String) = mutate { it.copy(primaryText = v) }
    fun onNewSecondaryChange(v: String) = _ui.update { it.copy(newSecondary = v) }

    fun addSecondary() {
        val text = _ui.value.newSecondary.trim()
        if (text.isBlank()) return
        if (_ui.value.config.secondaryTexts.any { it.equals(text, ignoreCase = true) }) {
            _ui.update { it.copy(newSecondary = "", message = "이미 있는 문자열입니다") }
            return
        }
        _ui.update {
            it.copy(
                config = it.config.copy(secondaryTexts = it.config.secondaryTexts + text),
                newSecondary = "",
                message = ""
            )
        }
        scheduleSave()
    }

    fun removeSecondary(index: Int) = mutate {
        val list = it.secondaryTexts.toMutableList()
        if (index in list.indices) list.removeAt(index)
        it.copy(secondaryTexts = list)
    }

    fun onNewTertiaryChange(v: String) = _ui.update { it.copy(newTertiary = v) }

    fun addTertiary() {
        val text = _ui.value.newTertiary.trim()
        if (text.isBlank()) return
        if (_ui.value.config.tertiaryTexts.any { it.equals(text, ignoreCase = true) }) {
            _ui.update { it.copy(newTertiary = "", message = "이미 있는 문자열입니다") }
            return
        }
        _ui.update {
            it.copy(
                config = it.config.copy(tertiaryTexts = it.config.tertiaryTexts + text),
                newTertiary = "",
                message = ""
            )
        }
        scheduleSave()
    }

    fun removeTertiary(index: Int) = mutate {
        val list = it.tertiaryTexts.toMutableList()
        if (index in list.indices) list.removeAt(index)
        it.copy(tertiaryTexts = list)
    }

    fun setMatchAll(v: Boolean) = mutate { it.copy(matchAll = v) }
    fun setClickFoundRow(v: Boolean) = mutate { it.copy(clickFoundRow = v) }
    fun setAfterClickWait(v: Long) = mutate { it.copy(afterClickWaitMs = v.coerceIn(0L, 60_000L)) }
    fun setPreferGestureTap(v: Boolean) = mutate { it.copy(preferGestureTap = v) }
    fun setStopWhenFound(v: Boolean) = mutate { it.copy(stopWhenFound = v) }
    fun setBackOnRefreshFail(v: Boolean) = mutate { it.copy(backOnRefreshFail = v) }
    fun setRatio(v: Float) = mutate { it.copy(scrollRatio = v.coerceIn(0.1f, 1.2f)) }
    fun setStepDelay(v: Long) = mutate { it.copy(stepDelayMs = v.coerceIn(200L, 30_000L)) }
    fun setStartDelay(v: Long) = mutate { it.copy(startDelayMs = v.coerceIn(1_000L, 60_000L)) }
    fun setBubbleTapToggles(v: Boolean) = mutate { it.copy(bubbleTapToggles = v) }
    fun setRefreshWait(v: Long) =
        mutate { it.copy(refreshWaitMs = v.coerceAtLeast(SearchEngine.MIN_REFRESH_MS)) }

    fun setMaxRounds(v: Int) = mutate { it.copy(maxRounds = v.coerceAtLeast(0)) }

    fun setNotifySystem(v: Boolean) = mutate { it.copy(notifySystem = v) }
    fun setNotifyVibrate(v: Boolean) = mutate { it.copy(notifyVibrate = v) }
    fun setNotifySound(v: Boolean) = mutate { it.copy(notifySound = v) }
    fun setNotifyBanner(v: Boolean) = mutate { it.copy(notifyBanner = v) }
    fun setNotifyBubble(v: Boolean) = mutate { it.copy(notifyBubble = v) }
    fun setNotifyScreenshot(v: Boolean) = mutate { it.copy(notifyScreenshot = v) }

    fun toggleAdvanced() = _ui.update { it.copy(advancedOpen = !it.advancedOpen) }
    fun toggleNotify() = _ui.update { it.copy(notifyOpen = !it.notifyOpen) }

    fun applyHistory(h: HistoryItem) = mutate {
        it.copy(
            primaryText = h.primaryText,
            secondaryTexts = h.secondaryTexts,
            tertiaryTexts = h.tertiaryTexts
        )
    }

    fun clearMessage() = _ui.update { it.copy(message = "") }

    // ------------------------------------------------------------------ 명령

    fun startSearch(onStarted: () -> Unit) {
        val cfg = _ui.value.config
        if (!cfg.isRunnable()) {
            _ui.update { it.copy(message = "1차 문자열과 2차 문자열을 모두 입력하세요.") }
            return
        }
        viewModelScope.launch {
            saveNow()
            OverlayService.startSearch(getApplication())
            onStarted()
        }
    }

    fun openOverlay(onOpened: () -> Unit) {
        viewModelScope.launch {
            saveNow()
            OverlayService.showOverlay(getApplication())
            onOpened()
        }
    }

    fun stopSearch() = OverlayService.stopSearch(getApplication())

    fun exitService() = OverlayService.exit(getApplication())
}
