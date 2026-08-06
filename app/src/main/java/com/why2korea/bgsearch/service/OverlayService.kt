package com.why2korea.bgsearch.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.why2korea.bgsearch.MainActivity
import com.why2korea.bgsearch.data.HistoryItem
import com.why2korea.bgsearch.data.SearchConfig
import com.why2korea.bgsearch.data.SettingsStore
import com.why2korea.bgsearch.engine.EngineHost
import com.why2korea.bgsearch.engine.OverlayMode
import com.why2korea.bgsearch.engine.Phase
import com.why2korea.bgsearch.engine.ScannerHolder
import com.why2korea.bgsearch.engine.SearchBus
import com.why2korea.bgsearch.engine.SearchEngine
import com.why2korea.bgsearch.overlay.BubbleView
import com.why2korea.bgsearch.overlay.OverlayActions
import com.why2korea.bgsearch.overlay.OverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "BgSearchService"

/**
 * 플로팅 오버레이 + 탐색 루프를 소유하는 Foreground Service.
 *
 * 화면을 읽고 조작하는 실제 작업은 ScanService(접근성 서비스)가 하고,
 * 이 서비스는 오버레이 UI · 알림 · WakeLock · 루프 오케스트레이션을 담당한다.
 */
class OverlayService : Service(), EngineHost, OverlayActions {

    companion object {
        const val ACTION_SHOW_OVERLAY = "com.why2korea.bgsearch.SHOW_OVERLAY"
        const val ACTION_START_SEARCH = "com.why2korea.bgsearch.START_SEARCH"
        const val ACTION_STOP_SEARCH = "com.why2korea.bgsearch.STOP_SEARCH"
        const val ACTION_RESUME_SEARCH = "com.why2korea.bgsearch.RESUME_SEARCH"
        const val ACTION_EXPAND = "com.why2korea.bgsearch.EXPAND"
        const val ACTION_COLLAPSE = "com.why2korea.bgsearch.COLLAPSE"
        const val ACTION_RELOAD_CONFIG = "com.why2korea.bgsearch.RELOAD_CONFIG"
        const val ACTION_EXIT = "com.why2korea.bgsearch.EXIT"

        fun showOverlay(ctx: Context) = send(ctx, ACTION_SHOW_OVERLAY)
        fun startSearch(ctx: Context) = send(ctx, ACTION_START_SEARCH)
        fun stopSearch(ctx: Context) = send(ctx, ACTION_STOP_SEARCH)
        fun reloadConfig(ctx: Context) = send(ctx, ACTION_RELOAD_CONFIG)
        fun exit(ctx: Context) = send(ctx, ACTION_EXIT)

        /**
         * 서비스 스코프가 취소된 뒤에도 반드시 끝나야 하는 저장 작업용.
         * (예: 종료 시 was_running=false 기록. 실패하면 다음 실행 때 잘못 복원된다.)
         */
        private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun send(ctx: Context, action: String) {
            val i = Intent(ctx, OverlayService::class.java).apply { this.action = action }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Throwable) {
                Log.w(TAG, "startService failed", e)
            }
        }
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + job)

    private lateinit var store: SettingsStore
    private lateinit var notifier: Notifier
    private lateinit var engine: SearchEngine
    private var overlay: OverlayManager? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var isForeground = false
    private var lastStatus = "대기 중"
    private var bubbleWatcher: Job? = null

    @Volatile
    private var config: SearchConfig = SearchConfig()

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ 수명주기

    override fun onCreate() {
        super.onCreate()
        store = SettingsStore(applicationContext)
        notifier = Notifier(applicationContext)
        notifier.createChannels()
        ensureForeground(lastStatus)

        engine = SearchEngine(applicationContext, scope, this)
        SearchBus.update { it.copy(serviceAlive = true, scannerReady = ScannerHolder.isReady()) }

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted - overlay disabled")
            SearchBus.log("오버레이 권한이 없어 창을 띄우지 못했습니다.")
        }
        if (!ScanService.isEnabled(this)) {
            SearchBus.log("접근성 서비스가 꺼져 있습니다. 설정 > 접근성에서 켜주세요.")
        }

        scope.launch {
            config = runCatching { store.loadConfig() }.getOrDefault(SearchConfig())
            setupOverlay()
            overlay?.bubbleTapToggles = config.bubbleTapToggles
            restoreBubblePos()
            val resume = runCatching { store.wasRunning() }.getOrDefault(false)
            if (resume && config.isRunnable() && !engine.isRunning) {
                SearchBus.log("프로세스 재시작 감지 - 이전 탐색을 복원합니다.")
                doStartSearch(withCountdown = true)
            }
        }

        watchBubbleState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground(lastStatus)
        when (intent?.action) {
            // [버블만 띄우기] — 이름 그대로 버블만 띄운다. 패널을 열면 대상 앱을 가려 버린다.
            ACTION_SHOW_OVERLAY -> {
                setupOverlay()
                onCollapse()
            }

            ACTION_START_SEARCH -> {
                setupOverlay()
                onExpand()
                scope.launch {
                    config = runCatching { store.loadConfig() }.getOrDefault(config)
                    overlay?.bubbleTapToggles = config.bubbleTapToggles
                    doStartSearch(withCountdown = true)
                }
            }

            ACTION_STOP_SEARCH -> onStop()
            ACTION_RESUME_SEARCH -> onResumeSearch()
            ACTION_EXPAND -> onExpand()
            ACTION_COLLAPSE -> onCollapse()
            ACTION_EXIT -> onExit()

            ACTION_RELOAD_CONFIG -> scope.launch {
                config = runCatching { store.loadConfig() }.getOrDefault(config)
                overlay?.bubbleTapToggles = config.bubbleTapToggles
            }

            null -> Log.i(TAG, "restarted by system")
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    override fun onDestroy() {
        bubbleWatcher?.cancel()
        releaseWakeLock()
        try {
            overlay?.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "overlay destroy failed", e)
        }
        SearchBus.update {
            it.copy(serviceAlive = false, running = false, overlayMode = OverlayMode.HIDDEN)
        }
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 오버레이

    private fun setupOverlay() {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(this)) return
        val m = OverlayManager(this, this) { x, y ->
            persistScope.launch { runCatching { store.saveBubblePos(x, y) } }
        }
        m.onCreate()
        m.bubbleTapToggles = config.bubbleTapToggles
        overlay = m
        // 기본 표시 상태는 버블(축소). 패널이 필요하면 호출부가 expand() 한다.
        m.collapse()
        SearchBus.update { it.copy(overlayMode = OverlayMode.COLLAPSED) }
    }

    private fun restoreBubblePos() {
        scope.launch {
            val pos = runCatching { store.loadBubblePos() }.getOrNull() ?: return@launch
            overlay?.setBubblePosition(pos.first, pos.second)
        }
    }

    /** 상태에 따라 버블 색/뱃지/글자를 갱신한다. */
    private fun watchBubbleState() {
        bubbleWatcher?.cancel()
        bubbleWatcher = scope.launch {
            SearchBus.snapshot.collect { s ->
                val color = when {
                    s.phase == Phase.PAUSED_FOUND -> BubbleView.COLOR_FOUND
                    s.phase == Phase.NO_SERVICE || s.phase == Phase.ERROR -> BubbleView.COLOR_PAUSED
                    s.phase == Phase.COUNTDOWN -> BubbleView.COLOR_PAUSED
                    s.running -> BubbleView.COLOR_RUNNING
                    else -> BubbleView.COLOR_IDLE
                }
                overlay?.setBubbleColor(color)
                overlay?.setBubbleBadge(if (config.notifyBubble) s.foundCount else 0)
                overlay?.setBubbleLabel(
                    if (s.phase == Phase.COUNTDOWN && s.countdown > 0) s.countdown.toString() else "S"
                )
            }
        }
    }

    // ------------------------------------------------------------------ 탐색 제어

    private suspend fun doStartSearch(withCountdown: Boolean) {
        if (engine.isRunning) return
        if (!config.isRunnable()) {
            SearchBus.log("1차 문자열과 2차 문자열을 모두 입력하세요.")
            SearchBus.update { it.copy(status = "입력값이 부족합니다") }
            notifier.notifyOngoing("입력값이 부족합니다", running = false)
            return
        }
        if (!ScanService.isEnabled(this)) {
            SearchBus.log("접근성 서비스가 꺼져 있어 시작할 수 없습니다. 설정 > 접근성에서 켜주세요.")
            SearchBus.update { it.copy(status = "접근성 서비스 꺼짐", phase = Phase.NO_SERVICE) }
            notifier.notifyWarn("접근성 서비스가 꺼져 있습니다. 설정 > 접근성 > 설치된 앱에서 켜주세요.")
            return
        }
        runCatching {
            store.setRunning(true)
            store.addHistory(HistoryItem(config.primaryText, config.secondaryTexts))
        }
        acquireWakeLock()
        engine.start(config, withCountdown)
        notifier.notifyOngoing(if (withCountdown) "곧 시작합니다" else "탐색 시작", running = true)
    }

    // ------------------------------------------------------------------ OverlayActions

    override fun onStartWithCountdown() {
        scope.launch {
            config = runCatching { store.loadConfig() }.getOrDefault(config)
            overlay?.bubbleTapToggles = config.bubbleTapToggles
            doStartSearch(withCountdown = true)
        }
    }

    override fun onToggleFromBubble() {
        if (engine.isRunning) {
            onStop()
            return
        }
        scope.launch {
            config = runCatching { store.loadConfig() }.getOrDefault(config)
            // 버블 탭은 이미 대상 앱을 보고 있는 상태이므로 카운트다운 없이 바로 시작한다.
            doStartSearch(withCountdown = false)
        }
    }

    override fun onStop() {
        persistScope.launch { runCatching { store.setRunning(false) } }
        engine.stop("사용자 정지")
        releaseWakeLock()
    }

    override fun onCollapse() {
        setupOverlay()
        overlay?.collapse()
        SearchBus.update { it.copy(overlayMode = OverlayMode.COLLAPSED) }
    }

    override fun onExpand() {
        setupOverlay()
        val m = overlay
        if (m == null) {
            SearchBus.log("오버레이 권한이 없어 패널을 열 수 없습니다.")
            return
        }
        m.expand()
        SearchBus.update { it.copy(overlayMode = OverlayMode.EXPANDED) }
    }

    override fun onOpenSettings() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )
        } catch (e: Throwable) {
            Log.w(TAG, "open settings failed", e)
        }
        onCollapse()
    }

    override fun onExit() {
        persistScope.launch { runCatching { store.setRunning(false) } }
        engine.stop("종료")
        releaseWakeLock()
        stopForegroundCompat()
        stopSelf()
    }

    override fun onResumeSearch() {
        notifier.cancelFound()
        overlay?.hideBanner()
        engine.resume()
    }

    override fun onDismissBanner() {
        overlay?.hideBanner()
        notifier.cancelFound()
    }

    // ------------------------------------------------------------------ EngineHost

    override fun onStatus(message: String) {
        lastStatus = message
        notifier.notifyOngoing(message, running = SearchBus.snapshot.value.running)
    }

    override fun onFound(texts: List<String>, timeText: String, shotPath: String?) {
        val cfg = config
        if (cfg.notifySystem) notifier.notifyFound(texts, timeText, cfg.notifySound)
        if (cfg.notifyVibrate) notifier.vibrate()
        if (cfg.notifyBanner) overlay?.showBanner()
        notifier.notifyOngoing("발견됨 - 일시정지 (계속 누르면 재개)", running = true)
    }

    override fun onFoundCleared() {
        notifier.cancelFound()
        overlay?.hideBanner()
    }

    override fun onWarn(message: String) = notifier.notifyWarn(message)

    override fun onLoopFinished(reason: String) {
        notifier.notifyOngoing("정지됨 ($reason)", running = false)
        releaseWakeLock()
        persistScope.launch { runCatching { store.setRunning(false) } }
    }

    /** 카운트다운 시작 시 패널을 접어 대상 앱이 보이게 한다. */
    override fun onNeedCollapse() {
        onCollapse()
    }

    // ------------------------------------------------------------------ Foreground / WakeLock

    private fun ensureForeground(status: String) {
        if (isForeground) return
        try {
            val n = notifier.buildOngoing(status, running = SearchBus.snapshot.value.running)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    Notifier.NOTI_ONGOING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(Notifier.NOTI_ONGOING, n)
            }
            isForeground = true
        } catch (e: Throwable) {
            Log.w(TAG, "startForeground failed", e)
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "stopForeground failed", e)
        }
        isForeground = false
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BgSearch::SearchWakeLock")
            wl.setReferenceCounted(false)
            wl.acquire()
            wakeLock = wl
        } catch (e: Throwable) {
            Log.w(TAG, "wakelock acquire failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Throwable) {
            Log.w(TAG, "wakelock release failed", e)
        }
        wakeLock = null
    }
}
