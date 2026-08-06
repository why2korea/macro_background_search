package com.why2korea.bgsearch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bgsearch_settings")

/**
 * DataStore(Preferences) 기반 설정 저장소.
 * 프로세스가 죽었다 살아나도 복원할 수 있도록 "마지막에 실행 중이었는지"까지 저장한다.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val PRIMARY = stringPreferencesKey("primary_text")
        val SECONDARY = stringPreferencesKey("secondary_texts_json")
        val TERTIARY = stringPreferencesKey("tertiary_texts_json")
        val MATCH_ALL = booleanPreferencesKey("match_all")
        val CLICK_ROW = booleanPreferencesKey("click_found_row")
        val AFTER_CLICK_WAIT = longPreferencesKey("after_click_wait_ms")
        val PREFER_GESTURE = booleanPreferencesKey("prefer_gesture_tap")
        val STOP_WHEN_FOUND = booleanPreferencesKey("stop_when_found")
        val RATIO = floatPreferencesKey("scroll_ratio")
        val STEP_DELAY = longPreferencesKey("step_delay_ms")
        val START_DELAY = longPreferencesKey("start_delay_ms")
        val BUBBLE_TAP = booleanPreferencesKey("bubble_tap_toggles")
        val PRE_REFRESH_WAIT = longPreferencesKey("pre_refresh_wait_ms")
        val REFRESH_WAIT = longPreferencesKey("refresh_wait_ms")
        val CONTENT_WAIT = longPreferencesKey("content_wait_ms")
        val MAX_ROUNDS = intPreferencesKey("max_rounds")

        val N_SYSTEM = booleanPreferencesKey("notify_system")
        val N_VIBRATE = booleanPreferencesKey("notify_vibrate")
        val N_SOUND = booleanPreferencesKey("notify_sound")
        val N_BANNER = booleanPreferencesKey("notify_banner")
        val N_BUBBLE = booleanPreferencesKey("notify_bubble")
        val N_SHOT = booleanPreferencesKey("notify_screenshot")

        val HISTORY = stringPreferencesKey("history_json")
        val WAS_RUNNING = booleanPreferencesKey("was_running")
        val BUBBLE_X = intPreferencesKey("bubble_x")
        val BUBBLE_Y = intPreferencesKey("bubble_y")
    }

    private companion object {
        const val HISTORY_MAX = 5
    }

    private val prefsFlow: Flow<Preferences> = context.dataStore.data
        .catch { e ->
            // 저장소 손상 / IO 오류로 앱이 죽지 않게 한다.
            if (e is IOException) emit(emptyPreferences()) else throw e
        }

    val configFlow: Flow<SearchConfig> = prefsFlow.map { p ->
        val d = SearchConfig()
        SearchConfig(
            primaryText = p[Keys.PRIMARY] ?: d.primaryText,
            secondaryTexts = decodeStrings(p[Keys.SECONDARY] ?: ""),
            matchAll = p[Keys.MATCH_ALL] ?: d.matchAll,
            tertiaryTexts = decodeStrings(p[Keys.TERTIARY] ?: ""),
            clickFoundRow = p[Keys.CLICK_ROW] ?: d.clickFoundRow,
            afterClickWaitMs = p[Keys.AFTER_CLICK_WAIT] ?: d.afterClickWaitMs,
            preferGestureTap = p[Keys.PREFER_GESTURE] ?: d.preferGestureTap,
            stopWhenFound = p[Keys.STOP_WHEN_FOUND] ?: d.stopWhenFound,
            scrollRatio = p[Keys.RATIO] ?: d.scrollRatio,
            stepDelayMs = p[Keys.STEP_DELAY] ?: d.stepDelayMs,
            startDelayMs = p[Keys.START_DELAY] ?: d.startDelayMs,
            bubbleTapToggles = p[Keys.BUBBLE_TAP] ?: d.bubbleTapToggles,
            preRefreshWaitMs = p[Keys.PRE_REFRESH_WAIT] ?: d.preRefreshWaitMs,
            refreshWaitMs = p[Keys.REFRESH_WAIT] ?: d.refreshWaitMs,
            contentWaitMs = p[Keys.CONTENT_WAIT] ?: d.contentWaitMs,
            maxRounds = p[Keys.MAX_ROUNDS] ?: d.maxRounds,
            notifySystem = p[Keys.N_SYSTEM] ?: d.notifySystem,
            notifyVibrate = p[Keys.N_VIBRATE] ?: d.notifyVibrate,
            notifySound = p[Keys.N_SOUND] ?: d.notifySound,
            notifyBanner = p[Keys.N_BANNER] ?: d.notifyBanner,
            notifyBubble = p[Keys.N_BUBBLE] ?: d.notifyBubble,
            notifyScreenshot = p[Keys.N_SHOT] ?: d.notifyScreenshot
        )
    }

    val historyFlow: Flow<List<HistoryItem>> = prefsFlow.map { p ->
        decodeHistory(p[Keys.HISTORY] ?: "")
    }

    suspend fun loadConfig(): SearchConfig = configFlow.first()

    suspend fun wasRunning(): Boolean = prefsFlow.first()[Keys.WAS_RUNNING] ?: false

    suspend fun save(c: SearchConfig) {
        context.dataStore.edit { p ->
            p[Keys.PRIMARY] = c.primaryText
            p[Keys.SECONDARY] = encodeStrings(c.secondaryTexts)
            p[Keys.TERTIARY] = encodeStrings(c.tertiaryTexts)
            p[Keys.MATCH_ALL] = c.matchAll
            p[Keys.CLICK_ROW] = c.clickFoundRow
            p[Keys.AFTER_CLICK_WAIT] = c.afterClickWaitMs
            p[Keys.PREFER_GESTURE] = c.preferGestureTap
            p[Keys.STOP_WHEN_FOUND] = c.stopWhenFound
            p[Keys.RATIO] = c.scrollRatio
            p[Keys.STEP_DELAY] = c.stepDelayMs
            p[Keys.START_DELAY] = c.startDelayMs
            p[Keys.BUBBLE_TAP] = c.bubbleTapToggles
            p[Keys.PRE_REFRESH_WAIT] = c.preRefreshWaitMs
            p[Keys.REFRESH_WAIT] = c.refreshWaitMs
            p[Keys.CONTENT_WAIT] = c.contentWaitMs
            p[Keys.MAX_ROUNDS] = c.maxRounds
            p[Keys.N_SYSTEM] = c.notifySystem
            p[Keys.N_VIBRATE] = c.notifyVibrate
            p[Keys.N_SOUND] = c.notifySound
            p[Keys.N_BANNER] = c.notifyBanner
            p[Keys.N_BUBBLE] = c.notifyBubble
            p[Keys.N_SHOT] = c.notifyScreenshot
        }
    }

    suspend fun setRunning(running: Boolean) {
        context.dataStore.edit { p -> p[Keys.WAS_RUNNING] = running }
    }

    suspend fun saveBubblePos(x: Int, y: Int) {
        context.dataStore.edit { p ->
            p[Keys.BUBBLE_X] = x
            p[Keys.BUBBLE_Y] = y
        }
    }

    suspend fun loadBubblePos(): Pair<Int, Int>? {
        val p = prefsFlow.first()
        val x = p[Keys.BUBBLE_X] ?: return null
        val y = p[Keys.BUBBLE_Y] ?: return null
        return x to y
    }

    suspend fun addHistory(item: HistoryItem) {
        if (item.primaryText.isBlank()) return
        context.dataStore.edit { p ->
            val cur = decodeHistory(p[Keys.HISTORY] ?: "")
            val next = ArrayList<HistoryItem>()
            next.add(item)
            for (h in cur) {
                if (next.size >= HISTORY_MAX) break
                if (h.primaryText == item.primaryText &&
                    h.secondaryTexts == item.secondaryTexts &&
                    h.tertiaryTexts == item.tertiaryTexts
                ) continue
                next.add(h)
            }
            p[Keys.HISTORY] = encodeHistory(next)
        }
    }

    // ------------------------------------------------------------------ JSON

    private fun encodeStrings(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun decodeStrings(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "")
                if (s.isNotBlank()) out.add(s)
            }
            out
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private fun encodeHistory(list: List<HistoryItem>): String {
        val arr = JSONArray()
        for (h in list) {
            arr.put(
                JSONObject()
                    .put("p", h.primaryText)
                    .put("s", JSONArray(h.secondaryTexts))
                    .put("t", JSONArray(h.tertiaryTexts))
            )
        }
        return arr.toString()
    }

    private fun decodeHistory(raw: String): List<HistoryItem> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<HistoryItem>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sec = ArrayList<String>()
                o.optJSONArray("s")?.let { sa ->
                    for (j in 0 until sa.length()) {
                        val s = sa.optString(j, "")
                        if (s.isNotBlank()) sec.add(s)
                    }
                }
                val ter = ArrayList<String>()
                o.optJSONArray("t")?.let { ta ->
                    for (j in 0 until ta.length()) {
                        val s = ta.optString(j, "")
                        if (s.isNotBlank()) ter.add(s)
                    }
                }
                out.add(HistoryItem(o.optString("p", ""), sec, ter))
            }
            out
        } catch (e: Throwable) {
            emptyList()
        }
    }
}
