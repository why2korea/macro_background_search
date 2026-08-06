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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "BgSearchEngine"

/** 엔진이 바깥(서비스)에 알리는 이벤트. */
interface EngineHost {
    fun onStatus(message: String)
    fun onFound(texts: List<String>, timeText: String, shotPath: String?)
    fun onFoundCleared()
    fun onWarn(message: String)
    fun onLoopFinished(reason: String)
    /** 카운트다운이 시작될 때 오버레이를 버블로 접기 위해 호출 */
    fun onNeedCollapse()
}

/**
 * 탐색 루프.
 *
 * 대상은 "지금 화면에 떠 있는 다른 앱"이다. WebView 도 URL 도 없다.
 *
 * 루프 (사용자가 중지할 때까지 무한 반복)
 *   0. [시작] → 버블로 축소 + 카운트다운 (대상 앱으로 이동할 시간)
 *   1. 현재 화면에서 1차 문자열 탐색 → 없으면 스크롤하며 계속 → 발견 시 클릭
 *   2. 클릭 후 전환된 화면에서 스크롤 내리며 2차 문자열 목록 탐색
 *   3. 발견 → 즉시 알림 → 일시정지 (resume() 시 재개)
 *   4. 바닥까지 미발견 → 맨 위로 → 당겨서 새로고침 → 대기 → 1번으로 복귀
 */
class SearchEngine(
    private val appCtx: Context,
    private val scope: CoroutineScope,
    private val host: EngineHost
) {

    companion object {
        const val MIN_REFRESH_MS = 3_000L
        const val MAX_STEPS_PER_PHASE = 400
        const val AFTER_CLICK_WAIT_MS = 1_500L
        const val PRIMARY_FAIL_WARN_AT = 5
        const val BOTTOM_STABLE_COUNT = 3
        const val SCROLL_TO_TOP_MAX = 40
        const val SHOT_DELAY_MS = 400L
        const val LOOP_ERROR_BACKOFF_MS = 5_000L
        /** 접근성 서비스가 꺼져 있을 때 재확인 간격 */
        const val NO_SERVICE_RETRY_MS = 3_000L
    }

    @Volatile
    var config: SearchConfig = SearchConfig()
        private set

    private var loopJob: Job? = null
    private var tickerJob: Job? = null
    private var startedAtMs: Long = 0L
    private var primaryFailStreak = 0

    @Volatile
    private var resumeSignal: CompletableDeferred<Boolean>? = null

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val human = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    val isRunning: Boolean get() = loopJob?.isActive == true

    // ------------------------------------------------------------------ 제어

    /**
     * @param withCountdown true 면 버블로 접고 설정된 시간만큼 카운트다운한 뒤 시작한다.
     *                      (패널의 [시작] 버튼용. 버블 탭으로 시작할 때는 false)
     */
    fun start(cfg: SearchConfig, withCountdown: Boolean) {
        if (isRunning) return
        config = cfg
        primaryFailStreak = 0
        startedAtMs = System.currentTimeMillis()

        SearchBus.update {
            it.copy(
                running = true,
                phase = if (withCountdown) Phase.COUNTDOWN else Phase.FIND_PRIMARY,
                round = 0,
                step = 0,
                countdown = 0,
                status = if (withCountdown) "대상 앱으로 이동하세요" else "시작",
                foundTexts = emptyList(),
                foundShotPath = null,
                logs = emptyList()
            )
        }
        startTicker()
        loopJob?.cancel()
        loopJob = scope.launch {
            if (withCountdown) {
                host.onNeedCollapse()
                if (!countdown()) return@launch
            }
            runLoop()
        }
    }

    fun stop(reason: String) {
        resumeSignal?.let { if (!it.isCompleted) it.complete(false) }
        resumeSignal = null
        loopJob?.cancel()
        loopJob = null
        tickerJob?.cancel()
        tickerJob = null
        SearchBus.update {
            it.copy(running = false, phase = Phase.IDLE, countdown = 0, status = "정지됨 ($reason)")
        }
        log("탐색 정지: $reason")
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
                SearchBus.update {
                    it.copy(
                        elapsedText = formatElapsed(ms),
                        scannerReady = ScannerHolder.isReady()
                    )
                }
                delay(1000)
            }
        }
    }

    private fun formatElapsed(ms: Long): String {
        val t = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
    }

    // ------------------------------------------------------------------ 카운트다운

    /** @return 계속 진행해도 되면 true */
    private suspend fun countdown(): Boolean {
        val total = (config.startDelayMs / 1000).toInt().coerceAtLeast(1)
        for (left in total downTo 1) {
            if (!scope.isActive) return false
            SearchBus.update { it.copy(phase = Phase.COUNTDOWN, countdown = left) }
            status("${left}초 후 시작 — 대상 앱으로 이동하세요")
            delay(1000)
        }
        SearchBus.update { it.copy(countdown = 0) }
        return true
    }

    // ------------------------------------------------------------------ 메인 루프

    private suspend fun runLoop() {
        var round = 0
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

                val scanner = awaitScanner() ?: continue

                // ---------------- 1. 1차 문자열 탐색 + 클릭
                SearchBus.update { it.copy(phase = Phase.FIND_PRIMARY) }
                if (!findAndClickPrimary(scanner, round, cfg)) {
                    refreshAndRestart(scanner, "1차 문자열 미발견")
                    continue
                }

                // ---------------- 2. 클릭 후 화면 전환 대기
                SearchBus.update { it.copy(phase = Phase.AFTER_CLICK) }
                delay(AFTER_CLICK_WAIT_MS)

                // ---------------- 3. 스크롤하며 2차 문자열 탐색
                SearchBus.update { it.copy(phase = Phase.SCAN_SECONDARY) }
                val matched = scanSecondaryWhileScrolling(scanner, round, cfg)

                if (matched.isNotEmpty()) {
                    // ---------------- 4. 발견 → 알림 후 일시정지
                    if (!onFound(scanner, matched, cfg)) return
                    continue
                }

                // ---------------- 5. 미발견 → 맨 위로 + 당겨서 새로고침 → 1차부터 재시작
                refreshAndRestart(scanner, "2차 문자열 미발견")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "loop error", e)
            SearchBus.update { it.copy(phase = Phase.ERROR) }
            log("루프 오류: ${e.message ?: e.javaClass.simpleName} - ${LOOP_ERROR_BACKOFF_MS / 1000}초 후 재시작")
            delay(LOOP_ERROR_BACKOFF_MS)
            if (SearchBus.snapshot.value.running) {
                loopJob = scope.launch { runLoop() }
            }
        }
    }

    /** 접근성 서비스가 연결될 때까지 기다린다. 루프를 죽이지 않고 계속 재시도한다. */
    private suspend fun awaitScanner(): ScreenScanner? {
        val s = ScannerHolder.scanner
        if (s != null && s.isReady()) return s
        SearchBus.update { it.copy(phase = Phase.NO_SERVICE, scannerReady = false) }
        status("접근성 서비스가 꺼져 있습니다 — 설정에서 켜주세요")
        log("접근성 서비스 미연결 - ${NO_SERVICE_RETRY_MS / 1000}초 후 재확인")
        delay(NO_SERVICE_RETRY_MS)
        return null
    }

    /**
     * 1차 문자열을 찾을 때까지 스크롤하며 탐색하고, 찾으면 클릭한다.
     * @return 클릭 성공 여부
     */
    private suspend fun findAndClickPrimary(
        scanner: ScreenScanner,
        round: Int,
        cfg: SearchConfig
    ): Boolean {
        var step = 0
        var stable = 0
        var lastHash = Int.MIN_VALUE

        while (scope.isActive && step < MAX_STEPS_PER_PHASE) {
            val info = scanner.snapshotInfo()
            SearchBus.update { it.copy(step = step, targetPackage = info.packageName) }
            status("라운드 $round · 1차 문자열 탐색 (스크롤 $step)")

            val r = scanner.clickText(cfg.primaryText)
            if (r.found && r.clicked) {
                primaryFailStreak = 0
                log("1차 클릭 성공 [${r.method}] \"${r.snippet}\"")
                return true
            }
            if (r.found && !r.clicked) {
                log("1차 문자열은 찾았으나 클릭 실패 (${r.error ?: "?"})")
            }

            val moved = scanner.scrollDown(cfg.scrollRatio)
            step++
            delay(cfg.stepDelayMs)

            val h = scanner.snapshotInfo().contentHash
            if (!moved || h == lastHash) {
                stable++
                if (stable >= BOTTOM_STABLE_COUNT) {
                    log("바닥 도달 (화면 변화 ${BOTTOM_STABLE_COUNT}회 없음)")
                    break
                }
            } else {
                stable = 0
            }
            lastHash = h
        }

        primaryFailStreak++
        log("1차 문자열 '${cfg.primaryText}' 찾기 실패 · 연속 $primaryFailStreak 회")
        if (primaryFailStreak >= PRIMARY_FAIL_WARN_AT) {
            host.onWarn("1차 문자열 '${cfg.primaryText}' 를 ${primaryFailStreak}회 연속 찾지 못했습니다. 탐색은 계속됩니다.")
            primaryFailStreak = 0
        }
        return false
    }

    /**
     * 스크롤을 한 스텝씩 내리며 2차 문자열 목록을 탐색한다.
     * 접근성 노드는 화면에 보이는 것만 읽히므로, 매 스텝의 매칭 결과를 누적해
     * AND 매칭이 서로 다른 스크롤 위치에서 걸려도 판정되게 한다.
     */
    private suspend fun scanSecondaryWhileScrolling(
        scanner: ScreenScanner,
        round: Int,
        cfg: SearchConfig
    ): List<String> {
        val wanted = cfg.secondaries()
        if (wanted.isEmpty()) return emptyList()

        val hits = LinkedHashSet<String>()
        var step = 0
        var stable = 0
        var lastHash = Int.MIN_VALUE

        fun satisfied(): Boolean =
            if (cfg.matchAll) hits.size >= wanted.size else hits.isNotEmpty()

        while (scope.isActive && step < MAX_STEPS_PER_PHASE) {
            hits.addAll(scanner.matchOnScreen(wanted))
            if (satisfied()) return hits.toList()

            val moved = scanner.scrollDown(cfg.scrollRatio)
            step++
            SearchBus.update { it.copy(step = step) }
            status("라운드 $round · 2차 스캔 (스크롤 $step, 매칭 ${hits.size}/${wanted.size})")
            delay(cfg.stepDelayMs)

            val h = scanner.snapshotInfo().contentHash
            if (!moved || h == lastHash) {
                stable++
                if (stable >= BOTTOM_STABLE_COUNT) {
                    log("바닥 도달 (화면 변화 ${BOTTOM_STABLE_COUNT}회 없음)")
                    break
                }
            } else {
                stable = 0
            }
            lastHash = h
        }

        hits.addAll(scanner.matchOnScreen(wanted))
        return if (satisfied()) hits.toList() else emptyList()
    }

    /**
     * 미발견 처리: 화면을 맨 위로 올린 뒤 당겨서 새로고침하고, 대기 후 1차 탐색부터 다시 시작한다.
     * (URL 이 없어 페이지 리로드를 할 수 없으므로 사용자가 손으로 하듯 제스처로 새로고침한다)
     */
    private suspend fun refreshAndRestart(scanner: ScreenScanner, reason: String) {
        SearchBus.update { it.copy(phase = Phase.REFRESHING) }
        log("$reason - 맨 위로 이동 후 당겨서 새로고침")

        status("맨 위로 이동 중")
        try {
            scanner.scrollToTop(SCROLL_TO_TOP_MAX)
        } catch (e: Throwable) {
            Log.w(TAG, "scrollToTop failed", e)
        }
        delay(400)

        status("당겨서 새로고침")
        val ok = try {
            scanner.pullToRefresh()
        } catch (e: Throwable) {
            Log.w(TAG, "pullToRefresh failed", e)
            false
        }
        log(if (ok) "새로고침 제스처 전송됨" else "새로고침 제스처 실패 - 그대로 진행")

        val wait = config.refreshWaitMs.coerceAtLeast(MIN_REFRESH_MS)
        status("새로고침 대기 ${wait / 1000}초")
        delay(wait)
    }

    // ------------------------------------------------------------------ 발견 처리

    /** @return true 면 계속(재개), false 면 루프 종료 */
    private suspend fun onFound(
        scanner: ScreenScanner,
        texts: List<String>,
        cfg: SearchConfig
    ): Boolean {
        val now = Date()
        val timeText = human.format(now)
        log("발견! ${texts.joinToString(", ")}")
        status("발견됨 - 일시정지")

        var shotPath: String? = null
        if (cfg.notifyScreenshot) {
            delay(SHOT_DELAY_MS)
            try {
                val f = File(File(appCtx.filesDir, "shots"), "found_" + stamp.format(now) + ".png")
                if (scanner.takeScreenshot(f)) shotPath = f.absolutePath
                log(if (shotPath != null) "스크린샷 저장: ${f.name}" else "스크린샷 저장 실패/미지원")
            } catch (e: Throwable) {
                Log.w(TAG, "screenshot failed", e)
            }
        }

        try {
            withContext(Dispatchers.IO) {
                File(appCtx.filesDir, "found_log.txt")
                    .appendText(timeText + "\t" + texts.joinToString("|") + "\n")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "log write failed", e)
        }

        SearchBus.update {
            it.copy(
                phase = Phase.PAUSED_FOUND,
                foundTexts = texts,
                foundTimeText = timeText,
                foundShotPath = shotPath,
                foundCount = it.foundCount + 1
            )
        }
        host.onFound(texts, timeText, shotPath)

        // 사용자가 [계속] 을 누를 때까지 대기
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
        log("재개")
        status("재개")
        return true
    }
}
