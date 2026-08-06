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
 *
 * 프로세스가 죽었다 살아나도 루프를 복원할 수 있도록
 * "마지막에 실행 중이었는지"(wasRunning)까지 함께 저장한다.
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val URL = stringPreferencesKey("url")
        val PRIMARY = stringPreferencesKey("primary_text")
        val SECONDARY = stringPreferencesKey("secondary_texts_json")
        val MATCH_ALL = booleanPreferencesKey("match_all")
        val RATIO = floatPreferencesKey("scroll_ratio")
        val STEP_DELAY = longPreferencesKey("step_delay_ms")
        val REFRESH_DELAY = longPreferencesKey("refresh_delay_ms")
        val MAX_ROUNDS = intPreferencesKey("max_rounds")

        val N_SYSTEM = booleanPreferencesKey("notify_system")
        val N_VIBRATE = booleanPreferencesKey("notify_vibrate")
        val N_SOUND = booleanPreferencesKey("notify_sound")
        val N_BANNER = booleanPreferencesKey("notify_banner")
        val N_BUBBLE = booleanPreferencesKey("notify_bubble")
        val N_SHOT = booleanPreferencesKey("notify_screenshot")

        val KEEP_FULL = booleanPreferencesKey("keep_full_size_collapsed")

        val HISTORY = stringPreferencesKey("history_json")

        /** 프로세스 재시작 후 루프 복원용 */
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
            url = p[Keys.URL] ?: d.url,
            primaryText = p[Keys.PRIMARY] ?: d.primaryText,
            secondaryTexts = decodeStrings(p[Keys.SECONDARY] ?: ""),
            matchAll = p[Keys.MATCH_ALL] ?: d.matchAll,
            scrollRatio = p[Keys.RATIO] ?: d.scrollRatio,
            stepDelayMs = p[Keys.STEP_DELAY] ?: d.stepDelayMs,
            refreshDelayMs = p[Keys.REFRESH_DELAY] ?: d.refreshDelayMs,
            maxRounds = p[Keys.MAX_ROUNDS] ?: d.maxRounds,
            notifySystem = p[Keys.N_SYSTEM] ?: d.notifySystem,
            notifyVibrate = p[Keys.N_VIBRATE] ?: d.notifyVibrate,
            notifySound = p[Keys.N_SOUND] ?: d.notifySound,
            notifyBanner = p[Keys.N_BANNER] ?: d.notifyBanner,
            notifyBubble = p[Keys.N_BUBBLE] ?: d.notifyBubble,
            notifyScreenshot = p[Keys.N_SHOT] ?: d.notifyScreenshot,
            keepFullSizeWhenCollapsed = p[Keys.KEEP_FULL] ?: d.keepFullSizeWhenCollapsed
        )
    }

    val historyFlow: Flow<List<HistoryItem>> = prefsFlow.map { p ->
        decodeHistory(p[Keys.HISTORY] ?: "")
    }

    val wasRunningFlow: Flow<Boolean> = prefsFlow.map { p -> p[Keys.WAS_RUNNING] ?: false }

    suspend fun loadConfig(): SearchConfig = configFlow.first()

    suspend fun wasRunning(): Boolean = wasRunningFlow.first()

    suspend fun save(c: SearchConfig) {
        context.dataStore.edit { p ->
            p[Keys.URL] = c.url
            p[Keys.PRIMARY] = c.primaryText
            p[Keys.SECONDARY] = encodeStrings(c.secondaryTexts)
            p[Keys.MATCH_ALL] = c.matchAll
            p[Keys.RATIO] = c.scrollRatio
            p[Keys.STEP_DELAY] = c.stepDelayMs
            p[Keys.REFRESH_DELAY] = c.refreshDelayMs
            p[Keys.MAX_ROUNDS] = c.maxRounds
            p[Keys.N_SYSTEM] = c.notifySystem
            p[Keys.N_VIBRATE] = c.notifyVibrate
            p[Keys.N_SOUND] = c.notifySound
            p[Keys.N_BANNER] = c.notifyBanner
            p[Keys.N_BUBBLE] = c.notifyBubble
            p[Keys.N_SHOT] = c.notifyScreenshot
            p[Keys.KEEP_FULL] = c.keepFullSizeWhenCollapsed
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

    /** 최근 입력을 맨 앞에 넣고 중복 제거 후 5개까지만 유지. */
    suspend fun addHistory(item: HistoryItem) {
        if (item.url.isBlank()) return
        context.dataStore.edit { p ->
            val cur = decodeHistory(p[Keys.HISTORY] ?: "")
            val next = ArrayList<HistoryItem>()
            next.add(item)
            for (h in cur) {
                if (next.size >= HISTORY_MAX) break
                val same = h.url == item.url &&
                    h.primaryText == item.primaryText &&
                    h.secondaryTexts == item.secondaryTexts
                if (same) continue
                next.add(h)
            }
            p[Keys.HISTORY] = encodeHistory(next)
        }
    }

    // ------------------------------------------------------------------ JSON 인코딩

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
            val o = JSONObject()
            o.put("u", h.url)
            o.put("p", h.primaryText)
            o.put("s", JSONArray(h.secondaryTexts))
            arr.put(o)
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
                val sa = o.optJSONArray("s")
                if (sa != null) {
                    for (j in 0 until sa.length()) {
                        val s = sa.optString(j, "")
                        if (s.isNotBlank()) sec.add(s)
                    }
                }
                out.add(
                    HistoryItem(
                        url = o.optString("u", ""),
                        primaryText = o.optString("p", ""),
                        secondaryTexts = sec
                    )
                )
            }
            out
        } catch (e: Throwable) {
            emptyList()
        }
    }
}
