package com.why2korea.bgsearch.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.why2korea.bgsearch.R
import com.why2korea.bgsearch.util.Metrics
import kotlin.math.abs

private const val TAG = "BgSearchOverlay"

/**
 * 오버레이 윈도우 관리.
 *
 * WebView 가 사라졌으므로 윈도우 구성이 단순해졌다.
 *  1. panelWindow  : 확장 상태의 컴팩트 패널 (상태 · 로그 · 버튼)
 *  2. bubbleWindow : 축소 상태의 원형 버블 (약 1cm)
 *  3. bannerWindow : 발견 시 상단 배너
 *  4. closeWindow  : 버블 드래그/롱프레스 시 하단 종료 영역
 *
 * 모든 창은 FLAG_NOT_FOCUSABLE 이다. 포커스를 뺏지 않아야
 * 접근성 서비스가 읽는 "활성 창"이 대상 앱으로 유지된다.
 *
 * 버블 조작
 *  - 탭      : 시작/정지 토글 (설정에서 끄면 패널 확장)
 *  - 더블탭  : 패널 확장
 *  - 드래그  : 이동 + 가장자리 스냅, 하단 종료 영역에 놓으면 종료
 *  - 롱프레스: 종료 영역 표시
 */
class OverlayManager(
    private val service: Context,
    private val actions: OverlayActions,
    private val onBubbleMoved: (Int, Int) -> Unit
) {

    companion object {
        private const val DOUBLE_TAP_MS = 280L
        private const val TAP_MAX_MS = 400L

        /** 모든 오버레이 윈도우 공통 플래그. */
        private const val FLAGS_BASE =
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    }

    private val themed: Context = ContextThemeWrapper(service, R.style.Theme_BgSearch)
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val owner = OverlayLifecycleOwner()
    private val main = Handler(Looper.getMainLooper())

    /** 물리 1cm (계산 불가 시 60dp 폴백) */
    val bubbleSizePx: Int = Metrics.oneCmPx(service)

    /** 버블 탭이 시작/정지를 토글할지. 서비스가 설정값으로 갱신한다. */
    var bubbleTapToggles: Boolean = true

    private val overlayType =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private var panelView: ComposeView? = null
    private var bannerView: ComposeView? = null
    private var closeView: ComposeView? = null
    private var bubbleView: BubbleView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var panelAdded = false
    private var bannerAdded = false
    private var closeAdded = false
    private var bubbleAdded = false

    private val closeActive = mutableStateOf(false)

    private var bubbleX = 0
    private var bubbleY = 0

    var expanded = false
        private set

    // ------------------------------------------------------------------ 수명주기

    fun onCreate() {
        owner.onCreate()
        owner.onResume()
    }

    fun canDrawOverlays(): Boolean = try {
        Settings.canDrawOverlays(service)
    } catch (e: Throwable) {
        false
    }

    fun destroy() {
        hideBanner()
        hideCloseZone()
        hideBubble()
        hidePanel()
        owner.onDestroy()
        panelView = null
        bannerView = null
        closeView = null
        bubbleView = null
    }

    // ------------------------------------------------------------------ 확장 / 축소

    fun expand() {
        expanded = true
        hideBubble()
        hideCloseZone()
        showPanel()
    }

    fun collapse() {
        expanded = false
        hidePanel()
        hideCloseZone()
        showBubble()
    }

    // ------------------------------------------------------------------ 패널

    private fun showPanel() {
        if (panelAdded) return
        val v = newComposeView { ControlPanel(actions) }
        panelView = v
        val p = baseParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.START }
        panelAdded = addSafely(v, p)
    }

    private fun hidePanel() {
        val v = panelView ?: return
        if (!panelAdded) return
        removeSafely(v)
        panelAdded = false
        panelView = null
    }

    // ------------------------------------------------------------------ 버블

    private fun showBubble() {
        val v = bubbleView ?: createBubble()
        if (bubbleAdded) return
        val p = bubbleParams ?: newBubbleParams().also { bubbleParams = it }
        clampBubble(p)
        bubbleAdded = addSafely(v, p)
    }

    private fun hideBubble() {
        val v = bubbleView ?: return
        if (!bubbleAdded) return
        removeSafely(v)
        bubbleAdded = false
    }

    private fun newBubbleParams(): WindowManager.LayoutParams =
        baseParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            flags = FLAGS_BASE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            x = bubbleX
            y = bubbleY
        }

    private fun createBubble(): BubbleView {
        val v = BubbleView(themed, bubbleSizePx)
        attachBubbleTouch(v)
        bubbleView = v
        if (bubbleX == 0 && bubbleY == 0) {
            bubbleX = Metrics.screenWidthPx(service) - (bubbleSizePx * 1.6f).toInt()
            bubbleY = (Metrics.screenHeightPx(service) * 0.28f).toInt()
        }
        return v
    }

    fun setBubblePosition(x: Int, y: Int) {
        bubbleX = x
        bubbleY = y
        bubbleParams?.let {
            it.x = x
            it.y = y
            clampBubble(it)
            bubbleView?.let { v -> if (bubbleAdded) updateSafely(v, it) }
        }
    }

    fun setBubbleColor(color: Int) = bubbleView?.setBubbleColor(color)

    fun setBubbleBadge(count: Int) = bubbleView?.setBadge(count)

    fun setBubbleLabel(text: String) = bubbleView?.setLabel(text)

    private fun clampBubble(p: WindowManager.LayoutParams) {
        val w = Metrics.screenWidthPx(service)
        val h = Metrics.screenHeightPx(service)
        val size = (bubbleSizePx * 1.44f).toInt()
        p.x = p.x.coerceIn(0, (w - size).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (h - size).coerceAtLeast(0))
        bubbleX = p.x
        bubbleY = p.y
    }

    private fun attachBubbleTouch(v: BubbleView) {
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var downAt = 0L
        var dragging = false
        var lastTapAt = 0L

        val longPress = Runnable { if (!dragging) showCloseZone() }
        val singleTap = Runnable {
            if (bubbleTapToggles) actions.onToggleFromBubble() else actions.onExpand()
        }

        v.setOnTouchListener { _, e ->
            val p = bubbleParams ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x
                    startY = p.y
                    downRawX = e.rawX
                    downRawY = e.rawY
                    downAt = System.currentTimeMillis()
                    dragging = false
                    main.postDelayed(longPress, longPressMs)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downRawX).toInt()
                    val dy = (e.rawY - downRawY).toInt()
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        main.removeCallbacks(longPress)
                        main.removeCallbacks(singleTap)
                        showCloseZone()
                    }
                    if (dragging) {
                        p.x = startX + dx
                        p.y = startY + dy
                        clampBubble(p)
                        updateSafely(v, p)
                        closeActive.value = isOverCloseZone(e.rawY)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    val over = dragging && isOverCloseZone(e.rawY)
                    val wasDragging = dragging
                    dragging = false
                    closeActive.value = false
                    hideCloseZone()

                    if (over) {
                        // 터치 디스패치 도중 윈도우를 떼면 불안정하므로 다음 루프로 미룬다.
                        main.post { actions.onExit() }
                        return@setOnTouchListener true
                    }
                    if (wasDragging) {
                        snapToEdge(v, p)
                        return@setOnTouchListener true
                    }
                    if (System.currentTimeMillis() - downAt < TAP_MAX_MS) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapAt < DOUBLE_TAP_MS) {
                            // 더블탭 = 패널 확장
                            main.removeCallbacks(singleTap)
                            lastTapAt = 0L
                            main.post { actions.onExpand() }
                        } else {
                            lastTapAt = now
                            main.postDelayed(singleTap, DOUBLE_TAP_MS)
                        }
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun snapToEdge(v: View, p: WindowManager.LayoutParams) {
        val screenW = Metrics.screenWidthPx(service)
        val size = (bubbleSizePx * 1.44f).toInt()
        val margin = Metrics.dp(service, 4f)
        val targetX = if (p.x + size / 2 < screenW / 2) margin else (screenW - size - margin)
        val from = p.x
        if (from == targetX) {
            onBubbleMoved(p.x, p.y)
            return
        }
        try {
            ValueAnimator.ofInt(from, targetX).apply {
                duration = 180
                addUpdateListener { a ->
                    p.x = a.animatedValue as Int
                    updateSafely(v, p)
                }
                start()
            }
        } catch (e: Throwable) {
            p.x = targetX
            updateSafely(v, p)
        }
        bubbleX = targetX
        onBubbleMoved(targetX, p.y)
    }

    // ------------------------------------------------------------------ 종료 영역

    private fun closeZoneTopPx(): Int =
        Metrics.screenHeightPx(service) - Metrics.dp(service, 88f)

    private fun isOverCloseZone(rawY: Float): Boolean = rawY >= closeZoneTopPx()

    private fun showCloseZone() {
        if (closeAdded) return
        val v = newComposeView { CloseZone(closeActive.value) }
        closeView = v
        val p = baseParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            flags = FLAGS_BASE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        closeAdded = addSafely(v, p)
    }

    private fun hideCloseZone() {
        val v = closeView ?: return
        if (!closeAdded) return
        removeSafely(v)
        closeAdded = false
        closeView = null
    }

    // ------------------------------------------------------------------ 배너

    fun showBanner() {
        if (bannerAdded) return
        val v = newComposeView { FoundBanner(actions) }
        bannerView = v
        val p = baseParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        bannerAdded = addSafely(v, p)
    }

    fun hideBanner() {
        val v = bannerView ?: return
        if (!bannerAdded) return
        removeSafely(v)
        bannerAdded = false
        bannerView = null
    }

    // ------------------------------------------------------------------ 기타

    fun onConfigurationChanged() {
        bubbleParams?.let { p ->
            clampBubble(p)
            bubbleView?.let { v -> if (bubbleAdded) updateSafely(v, p) }
        }
    }

    /**
     * 오버레이 윈도우에 붙일 ComposeView 를 매번 새로 만든다.
     *
     * 같은 ComposeView 인스턴스를 removeView 후 다시 addView 하면
     * 화면에는 그려지지만 포인터 입력이 라우팅되지 않아 버튼이 먹지 않는다.
     * (v1.0.0 실기기 확인: 첫 확장에서는 동작, 축소 후 재확장하면 전부 무반응)
     */
    private fun newComposeView(content: @Composable () -> Unit): ComposeView {
        val cv = ComposeView(themed)
        owner.attachTo(cv)
        cv.setContent(content)
        return cv
    }

    // ------------------------------------------------------------------ 윈도우 헬퍼

    private fun baseParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, overlayType, FLAGS_BASE, PixelFormat.TRANSLUCENT
    )

    private fun addSafely(v: View, p: WindowManager.LayoutParams): Boolean = try {
        if (v.parent != null) wm.removeViewImmediate(v)
        wm.addView(v, p)
        true
    } catch (e: Throwable) {
        // 오버레이 권한이 없거나 이미 붙어 있는 경우 등. 서비스가 죽지 않게 삼킨다.
        Log.w(TAG, "addView failed", e)
        false
    }

    private fun updateSafely(v: View, p: WindowManager.LayoutParams) {
        try {
            if (v.parent != null) wm.updateViewLayout(v, p)
        } catch (e: Throwable) {
            Log.w(TAG, "updateViewLayout failed", e)
        }
    }

    private fun removeSafely(v: View) {
        try {
            if (v.parent != null) wm.removeView(v)
        } catch (e: Throwable) {
            Log.w(TAG, "removeView failed", e)
        }
    }
}
