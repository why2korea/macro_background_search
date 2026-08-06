package com.why2korea.bgsearch.engine

import android.content.Context
import android.util.Log
import com.why2korea.bgsearch.data.SearchConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "BgSearchEngine"

/** 엔진이 바깥(서비스)에 알리는 이벤트. */
interface EngineHost {
    /** 상시 알림 문구 갱신 */
    fun onStatus(message: String)

    /** 2차 문자열 발견 → 알림/배너/버블 처리는 호스트가 담당한다. */
    fun onFound(texts: List<String>, url: String, timeText: String, shotPath: String?)

    /** 발견 상태 해제(재개/정지) 시 배너·뱃지 정리 */
    fun onFoundCleared()

    /** 1차 문자열을 계속 못 찾는 등 경고 상황 */
    fun onWarn(message: String)

    /** 루프 종료 (사용자 정지, 최대 라운드 도달 등) */
    fun onLoopFinished(reason: String)
}

/**
 * 탐색 루프.
 *
 * 참고 프로젝트 WatcherViewModel 의 runLoop 를 서비스 소유 엔진으로 옮기고
 * 2차 문자열 다중 매칭(OR/AND)과 "발견 시 정지"가 아닌 "발견 시 일시정지"로 바꿨다.
 *
 * 루프 (사용자가 중지할 때까지 무한 반복)
 *   1. URL 로드
 *   2. 1차 문자열 탐색(없으면 스크롤하며 계속) → 발견 시 클릭
 *   3. 클릭 후 스크롤하며 2차 문자열 목록 탐색
 *   4. 발견 시 즉시 알림 → 일시정지 (resume() 호출 시 재개)
 *   5. 바닥까지 미발견 → 새로고침 → 대기 → 2번으로 복귀
 */
class SearchEngine(
    private val appCtx: Context,
    private val scope: CoroutineScope,
    private val web: WebController,
    private val host: EngineHost
) {

    companion object {
        /** 서버 부담 방지를 위한 새로고침 최소 간격 */
        const val MIN_REFRESH_MS = 5_000L
        const val MAX_STEPS_PER_PHASE = 800
        const val CLICK_MIN_WAIT_MS = 1_500L
        const val CLICK_MAX_WAIT_MS = 4_000L
        const val AFTER_LOAD_WAIT_MS = 1_000L
        const val PRIMARY_FAIL_WARN_AT = 5
        const val BOTTOM_STABLE_COUNT = 3
        const val SHOT_RENDER_WAIT_MS = 600L
        const val LOOP_ERROR_BACKOFF_MS = 5_000L
    }

    @Volatile
    var config: SearchConfig = SearchConfig()
        private set

    private var loopJob: Job? = null
    private var tickerJob: Job? = null
    private var startedAtMs: Long = 0L
    private var elapsedBaseMs: Long = 0L

    private var primaryFailStreak = 0

    /** 발견 후 일시정지 해제 신호 */
    @Volatile
    private var resumeSignal: CompletableDeferred<Boolean>? = null

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val human = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    val isRunning: Boolean get() = loopJob?.isActive == true

    // ------------------------------------------------------------------ 제어

    fun start(cfg: SearchConfig) {
        if (isRunning) return
        config = cfg
        primaryFailStreak = 0
        startedAtMs = System.currentTimeMillis()
        elapsedBaseMs = 0L

        SearchBus.update {
            it.copy(
                running = true,
                phase = Phase.LOADING,
                round = 0,
                step = 0,
                scrollInfo = "-",
                status = "시작",
                foundTexts = emptyList(),
                foundShotPath = null,
                logs = emptyList()
            )
        }
        startTicker()
        loopJob?.cancel()
        loopJob = scope.launch { runLoop(cfg.normalizedUrl()) }
    }

    fun stop(reason: String) {
        resumeSignal?.let { if (!it.isCompleted) it.complete(false) }
        resumeSignal = null
        loopJob?.cancel()
        loopJob = null
        tickerJob?.cancel()
        tickerJob = null
        SearchBus.update {
            it.copy(running = false, phase = Phase.IDLE, status = "정지됨 ($reason)")
        }
        log("탐색 정지: $reason")
        try {
            web.cleanup()
        } catch (e: Throwable) {
            Log.w(TAG, "cleanup failed", e)
        }
        host.onFoundCleared()
        host.onLoopFinished(reason)
    }

    /** 발견 일시정지 상태에서 [계속] */
    fun resume() {
        val d = resumeSignal ?: return
        if (!d.isCompleted) d.complete(true)
    }

    // ------------------------------------------------------------------ 로그 / 상태

    private fun log(msg: String) {
        SearchBus.log(clock.format(Date()) + "  " + msg)
        Log.d(TAG, msg)
    }

    private fun status(msg: String) {
        SearchBus.update { it.copy(status = msg) }
        host.onStatus(msg)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val ms = System.currentTimeMillis() - startedAtMs
                elapsedBaseMs = ms
                SearchBus.update { it.copy(elapsedText = formatElapsed(ms)) }
                delay(1000)
            }
        }
    }

    private fun formatElapsed(ms: Long): String {
        val total = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }

    // ------------------------------------------------------------------ 메인 루프

    private suspend fun runLoop(url: String) {
        var round = SearchBus.snapshot.value.round
        try {
            while (scope.isActive) {
                val cfg = config
                if (cfg.maxRounds > 0 && round >= cfg.maxRounds) {
                    log("최대 라운드(${cfg.maxRounds}) 도달")
                    stop("최대 라운드 도달")
                    return
                }
                round++
                SearchBus.update { it.copy(round = round) }

                if (web.webView == null) {
                    log("WebView 가 아직 없습니다. 5초 후 재시도")
                    delay(5_000)
                    continue
                }

                // ---------------- 1. 페이지 로드
                SearchBus.update { it.copy(phase = Phase.LOADING) }
                status("라운드 $round · 페이지 로드 중")
                val loaded = if (round == 1 || web.currentUrl.isBlank() ||
                    web.currentUrl == "about:blank"
                ) web.load(url) else web.reload()
                if (!loaded) {
                    log("페이지 로드 실패 (${web.lastError ?: "unknown"}) - 다음 라운드로")
                    waitRefresh()
                    continue
                }
                SearchBus.update { it.copy(currentUrl = web.currentUrl) }
                delay(AFTER_LOAD_WAIT_MS)

                // ---------------- 2. 1차 문자열 탐색 + 클릭
                SearchBus.update { it.copy(phase = Phase.FIND_PRIMARY) }
                if (!findAndClickPrimary(round, cfg)) {
                    waitRefresh()
                    continue
                }

                // ---------------- 2-1. 클릭 후 변화 대기
                SearchBus.update { it.copy(phase = Phase.AFTER_CLICK) }
                waitForChange()

                // ---------------- 3. 스크롤하며 2차 문자열 목록 탐색
                SearchBus.update { it.copy(phase = Phase.SCAN_SECONDARY) }
                val matched = scanSecondaryWhileScrolling(round, cfg)

                if (matched.isNotEmpty()) {
                    // ---------------- 4. 발견 → 알림 후 일시정지
                    val cont = onFound(matched, web.currentUrl.ifBlank { url }, cfg)
                    if (!cont) return
                    // 재개: 다음 라운드부터 다시 시작
                    continue
                }

                // ---------------- 5. 미발견 → 새로고침 대기 후 2번으로
                log("라운드 $round: 2차 문자열 미발견 - 새로고침 대기")
                waitRefresh()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 어떤 예외도 앱을 죽이지 않는다. 백오프 후 루프를 다시 세운다.
            Log.e(TAG, "loop error", e)
            SearchBus.update { it.copy(phase = Phase.ERROR) }
            log("루프 오류: ${e.message ?: e.javaClass.simpleName} - ${LOOP_ERROR_BACKOFF_MS / 1000}초 후 재시작")
            delay(LOOP_ERROR_BACKOFF_MS)
            if (SearchBus.snapshot.value.running) {
                loopJob = scope.launch { runLoop(url) }
            }
        }
    }

    /**
     * 1차 문자열을 찾을 때까지 스크롤하며 탐색하고, 찾으면 클릭한다.
     * @return 클릭 성공 여부
     */
    private suspend fun findAndClickPrimary(round: Int, cfg: SearchConfig): Boolean {
        var step = 0
        var stableAtBottom = 0
        var lastHeight = -1

        while (scope.isActive && step < MAX_STEPS_PER_PHASE) {
            status("라운드 $round · 1차 문자열 탐색 (스크롤 $step)")
            val r = web.evalJson(InjectScripts.clickPrimary(cfg.primaryText))
            if (r.optBoolean("found", false)) {
                primaryFailStreak = 0
                val skipped = r.optInt("skipped", 0)
                log("1차 클릭 성공: <${r.optString("tag")}> ${r.optString("snippet")}")
                if (skipped > 0) log("cross-origin iframe ${skipped}개는 접근 불가로 건너뜀")
                return true
            }

            val sc = web.evalJson(InjectScripts.scrollStep(cfg.scrollRatio))
            step++
            val height = sc.optInt("height", 0)
            val atBottom = sc.optBoolean("atBottom", false)
            SearchBus.update { it.copy(step = step, scrollInfo = scrollLabel(sc, step)) }
            delay(cfg.stepDelayMs)

            if (atBottom) {
                if (height == lastHeight) stableAtBottom++ else stableAtBottom = 0
                lastHeight = height
                if (stableAtBottom >= BOTTOM_STABLE_COUNT) break
            } else {
                stableAtBottom = 0
                lastHeight = height
            }
        }

        primaryFailStreak++
        log("1차 문자열 '${cfg.primaryText}' 찾기 실패 · 연속 $primaryFailStreak 회")
        if (primaryFailStreak >= PRIMARY_FAIL_WARN_AT) {
            host.onWarn("1차 문자열 '${cfg.primaryText}' 를 ${primaryFailStreak}회 연속 찾지 못했습니다. 탐색은 계속됩니다.")
            log("경고 알림 발송 (루프는 계속)")
            primaryFailStreak = 0
        }
        return false
    }

    /**
     * 클릭 이후 화면에서 스크롤을 한 스텝씩 내리며 2차 문자열 목록을 탐색한다.
     *
     * innerText 는 화면 밖 콘텐츠도 포함하지만, 지연 로딩/무한스크롤 페이지는
     * 스크롤을 내려야 DOM 에 붙는다. 그래서 매 스텝마다 스캔하고 결과를 누적한다.
     * (AND 매칭이 서로 다른 스크롤 위치에서 걸려도 판정되게 하기 위함)
     *
     * @return 알림 조건을 만족한 문자열 목록. 미발견이면 빈 목록.
     */
    private suspend fun scanSecondaryWhileScrolling(round: Int, cfg: SearchConfig): List<String> {
        val wanted = cfg.secondaryTexts.filter { it.isNotBlank() }
        if (wanted.isEmpty()) return emptyList()

        val hits = LinkedHashSet<String>()
        var step = 0
        var stableAtBottom = 0
        var lastHeight = -1

        fun satisfied(): Boolean =
            if (cfg.matchAll) hits.size >= wanted.size else hits.isNotEmpty()

        while (scope.isActive && step < MAX_STEPS_PER_PHASE) {
            val hit = web.evalJson(InjectScripts.scanSecondary(wanted))
            collectMatched(hit, hits)
            if (satisfied()) return hits.toList()

            val sc = web.evalJson(InjectScripts.scrollStep(cfg.scrollRatio))
            step++
            val height = sc.optInt("height", 0)
            val atBottom = sc.optBoolean("atBottom", false)
            SearchBus.update { it.copy(step = step, scrollInfo = scrollLabel(sc, step)) }
            status("라운드 $round · 2차 스캔 (스크롤 $step, 매칭 ${hits.size}/${wanted.size})")
            delay(cfg.stepDelayMs)

            if (atBottom) {
                if (height == lastHeight) stableAtBottom++ else stableAtBottom = 0
                lastHeight = height
                if (stableAtBottom >= BOTTOM_STABLE_COUNT) {
                    log("바닥 도달 (높이 변화 ${BOTTOM_STABLE_COUNT}회 없음)")
                    break
                }
            } else {
                stableAtBottom = 0
                lastHeight = height
            }
        }

        // 마지막으로 한 번 더 확인
        val last = web.evalJson(InjectScripts.scanSecondary(wanted))
        collectMatched(last, hits)
        return if (satisfied()) hits.toList() else emptyList()
    }

    private fun collectMatched(res: JSONObject, into: MutableSet<String>) {
        val arr = res.optJSONArray("matched") ?: return
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotBlank()) into.add(s)
        }
    }

    private fun scrollLabel(sc: JSONObject, step: Int): String {
        val top = sc.optInt("top", 0)
        val height = sc.optInt("height", 0)
        val client = sc.optInt("client", 0)
        return "$top / ${(height - client).coerceAtLeast(0)} (step $step)"
    }

    /** 클릭 후 DOM/URL 변화를 최대 CLICK_MAX_WAIT_MS 까지 기다린다. 최소 CLICK_MIN_WAIT_MS 는 무조건 대기. */
    private suspend fun waitForChange() {
        val start = System.currentTimeMillis()
        var changed = false
        while (System.currentTimeMillis() - start < CLICK_MAX_WAIT_MS) {
            delay(250)
            val r = web.evalJson(InjectScripts.checkChanged())
            if (r.optBoolean("changed", false) || r.optBoolean("moved", false)) {
                changed = true
                if (System.currentTimeMillis() - start >= CLICK_MIN_WAIT_MS) break
            }
        }
        val waited = System.currentTimeMillis() - start
        if (waited < CLICK_MIN_WAIT_MS) delay(CLICK_MIN_WAIT_MS - waited)
        log(if (changed) "클릭 후 화면 변화 감지" else "클릭 후 변화 미감지 - 그대로 진행")
        try {
            web.eval(InjectScripts.scrollTop())
        } catch (e: Throwable) {
            Log.w(TAG, "scrollTop failed", e)
        }
    }

    /** 새로고침 대기. 서버 부담 방지를 위해 최소 5초. */
    private suspend fun waitRefresh() {
        SearchBus.update { it.copy(phase = Phase.WAIT_REFRESH) }
        val wait = config.refreshDelayMs.coerceAtLeast(MIN_REFRESH_MS)
        status("새로고침 대기 ${wait / 1000}초")
        delay(wait)
    }

    // ------------------------------------------------------------------ 발견 처리

    /**
     * 발견 시: 알림 채널별 처리 후 루프를 일시정지한다.
     * @return true 면 계속(재개), false 면 루프 종료
     */
    private suspend fun onFound(texts: List<String>, url: String, cfg: SearchConfig): Boolean {
        val now = Date()
        val timeText = human.format(now)
        log("발견! ${texts.joinToString(", ")}")
        status("발견됨 - 일시정지")

        // 1) WebView 안쪽 하이라이트 + 배너 (오버레이 배너와 별개)
        if (cfg.notifyBanner) {
            try {
                web.evalJson(InjectScripts.highlightAndBanner(texts, timeText))
            } catch (e: Throwable) {
                Log.w(TAG, "in-page banner failed", e)
            }
        }

        // 2) 스크린샷 (배너·하이라이트가 합성될 시간을 준 뒤 캡처)
        var shotPath: String? = null
        if (cfg.notifyScreenshot) {
            delay(SHOT_RENDER_WAIT_MS)
            try {
                val f = File(File(appCtx.filesDir, "shots"), "found_" + stamp.format(now) + ".png")
                if (web.screenshot(f)) shotPath = f.absolutePath
                log(if (shotPath != null) "스크린샷 저장: ${f.name}" else "스크린샷 저장 실패")
            } catch (e: Throwable) {
                Log.w(TAG, "screenshot failed", e)
            }
        }

        // 3) 로그 파일
        try {
            withContext(Dispatchers.IO) {
                File(appCtx.filesDir, "found_log.txt")
                    .appendText(timeText + "\t" + url + "\t" + texts.joinToString("|") + "\n")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "log write failed", e)
        }

        // 4) 상태 반영 + 호스트 알림 (시스템 알림 / 진동 / 사운드 / 오버레이 배너 / 버블)
        SearchBus.update {
            it.copy(
                phase = Phase.PAUSED_FOUND,
                foundTexts = texts,
                foundTimeText = timeText,
                foundShotPath = shotPath,
                foundCount = it.foundCount + 1
            )
        }
        host.onFound(texts, url, timeText, shotPath)

        // 5) 사용자가 [계속] 을 누를 때까지 대기
        val d = CompletableDeferred<Boolean>()
        resumeSignal = d
        val go = try {
            d.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }
        resumeSignal = null
        if (!go) return false

        host.onFoundCleared()
        SearchBus.update {
            it.copy(phase = Phase.IDLE, foundTexts = emptyList(), foundShotPath = null)
        }
        try {
            web.eval(InjectScripts.clearBanner())
        } catch (_: Throwable) {
        }
        log("재개")
        status("재개")
        return true
    }
}
