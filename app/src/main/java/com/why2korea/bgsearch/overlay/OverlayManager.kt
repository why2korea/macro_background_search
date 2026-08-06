package com.why2korea.bgsearch.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.why2korea.bgsearch.R
import com.why2korea.bgsearch.util.Metrics
import kotlin.math.abs

private const val TAG = "BgSearchOverlay"

/**
 * 오버레이 윈도우 전체를 관리한다.
 *
 * 윈도우 구성 (모두 TYPE_APPLICATION_OVERLAY)
 *  1. webWindow    : WebView 전용. 확장/축소와 무관하게 항상 붙어 있고 크기도 바뀌지 않는다(기본값).
 *                    축소 시 alpha 를 거의 0 으로 낮추고 NOT_TOUCHABLE + NOT_FOCUSABLE 을 건다.
 *  2. controlWindow: 확장 상태의 하단 컨트롤 바.
 *  3. bubbleWindow : 축소 상태의 원형 버블. 드래그 이동 + 가장자리 스냅.
 *  4. bannerWindow : 발견 시 상단 배너.
 *  5. closeWindow  : 버블 드래그/롱프레스 시 하단 종료 영역.
 *
 * 컨트롤 바를 WebView 와 같은 윈도우에 넣지 않은 이유:
 * 같은 윈도우면 컨트롤 바를 숨길 때 WebView 높이가 바뀌어 페이지가 리플로우되고
 * 스크롤 위치가 흐트러진다. 윈도우를 분리하면 WebView 뷰포트가 절대 바뀌지 않는다.
 */
class OverlayManager(
    private val service: Context,
    private val actions: OverlayActions,
    private val onBubbleMoved: (Int, Int) -> Unit
) {

    private val themed: Context = android.view.ContextThemeWrapper(service, R.style.Theme_BgSearch)
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val owner = OverlayLifecycleOwner()
    private val main = Handler(Looper.getMainLooper())

    /** 물리 1cm (계산 불가 시 60dp 폴백) */
    val bubbleSizePx: Int = Metrics.oneCmPx(service)

    /** 축소 시 WebView 윈도우 크기를 유지할지 여부. 서비스가 설정값으로 갱신한다. */
    var keepFullSizeWhenCollapsed: Boolean = true

    private val overlayType =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    // ------------------------------------------------------------------ 뷰

    private var webRoot: FrameLayout? = null
    private var controlView: ComposeView? = null
    private var bannerView: ComposeView? = null
    private var bubbleView: BubbleView? = null
    private var closeView: ComposeView? = null

    private var webParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    private var webAdded = false
    private var controlAdded = false
    private var bannerAdded = false
    private var bubbleAdded = false
    private var closeAdded = false

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

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(service)

    /**
     * WebView 를 담을 윈도우를 만들고 즉시 붙인다.
     * WebView 는 여기 들어간 뒤 서비스가 살아있는 동안 절대 분리되지 않는다.
     */
    fun installWebView(webView: View): Boolean {
        if (webAdded) return true
        val root = FrameLayout(themed).apply {
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    actions.onCollapse()
                    true
                } else false
            }
        }
        (webView.parent as? ViewGroup)?.removeView(webView)
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        owner.attachTo(root)

        val p = baseParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        webRoot = root
        webParams = p
        webAdded = addSafely(root, p)
        if (webAdded) applyWebWindowMode(expandedNow = false)
        return webAdded
    }

    // ------------------------------------------------------------------ 확장 / 축소

    fun expand() {
        if (!webAdded) return
        expanded = true
        applyWebWindowMode(expandedNow = true)
        showControlBar()
        hideBubble()
        hideCloseZone()
        webRoot?.let {
            it.requestFocus()
        }
    }

    fun collapse() {
        if (!webAdded) return
        expanded = false
        applyWebWindowMode(expandedNow = false)
        hideControlBar()
        showBubble()
        hideCloseZone()
    }

    /**
     * WebView 윈도우의 표시 모드를 적용한다.
     *
     * 축소 상태에서도 WebView 를 파괴하지 않는다. 크기를 0 으로 만들면 렌더링과 JS 타이머가
     * 멈출 수 있으므로 최소한의 크기는 반드시 유지한다.
     *  - keepFullSizeWhenCollapsed = true  → 화면 전체 크기 유지 + alpha 0.01 (뷰포트 불변, 권장)
     *  - keepFullSizeWhenCollapsed = false → 버블과 같은 1cm 크기로 줄여 버블 바로 뒤에 배치
     */
    private fun applyWebWindowMode(expandedNow: Boolean) {
        val p = webParams ?: return
        val root = webRoot ?: return
        if (expandedNow) {
            p.width = WindowManager.LayoutParams.MATCH_PARENT
            p.height = WindowManager.LayoutParams.MATCH_PARENT
            p.x = 0
            p.y = 0
            p.flags = FLAGS_BASE
            p.alpha = 1f
        } else {
            p.flags = FLAGS_BASE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            p.alpha = 0.01f
            if (keepFullSizeWhenCollapsed) {
                p.width = WindowManager.LayoutParams.MATCH_PARENT
                p.height = WindowManager.LayoutParams.MATCH_PARENT
                p.x = 0
                p.y = 0
            } else {
                p.width = bubbleSizePx
                p.height = bubbleSizePx
                p.x = bubbleX
                p.y = bubbleY
            }
        }
        updateSafely(root, p)
    }

    // ------------------------------------------------------------------ 컨트롤 바

    private fun showControlBar() {
        if (controlAdded) return
        val v = newComposeView { ControlBar(actions) }
        controlView = v
        val p = baseParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            flags = FLAGS_BASE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        controlAdded = addSafely(v, p)
    }

    private fun hideControlBar() {
        val v = controlView ?: return
        if (!controlAdded) return
        removeSafely(v)
        controlAdded = false
        controlView = null
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
            flags = FLAGS_BASE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            x = bubbleX
            y = bubbleY
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

    fun setBubbleColor(color: Int) {
        bubbleView?.setBubbleColor(color)
    }

    fun setBubbleBadge(count: Int) {
        bubbleView?.setBadge(count)
    }

    private fun clampBubble(p: WindowManager.LayoutParams) {
        val w = Metrics.screenWidthPx(service)
        val h = Metrics.screenHeightPx(service)
        val size = (bubbleSizePx * 1.44f).toInt()
        p.x = p.x.coerceIn(0, (w - size).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (h - size).coerceAtLeast(0))
        bubbleX = p.x
        bubbleY = p.y
    }

    private fun createBubble(): BubbleView {
        val v = BubbleView(themed, bubbleSizePx)
        attachBubbleTouch(v)
        bubbleView = v
        // 최초 위치: 우측 상단에서 약간 아래
        if (bubbleX == 0 && bubbleY == 0) {
            bubbleX = Metrics.screenWidthPx(service) - (bubbleSizePx * 1.6f).toInt()
            bubbleY = (Metrics.screenHeightPx(service) * 0.28f).toInt()
        }
        return v
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

        val longPress = Runnable {
            if (!dragging) showCloseZone()
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
                        showCloseZone()
                    }
                    if (dragging) {
                        p.x = startX + dx
                        p.y = startY + dy
                        clampBubble(p)
                        updateSafely(v, p)
                        if (!keepFullSizeWhenCollapsed && !expanded) applyWebWindowMode(false)
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
                    } else if (System.currentTimeMillis() - downAt < 400) {
                        main.post { actions.onExpand() }
                    }
                    true
                }

                else -> false
            }
        }
    }

    /** 가장자리 스냅 애니메이션. */
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
            val anim = ValueAnimator.ofInt(from, targetX)
            anim.duration = 180
            anim.addUpdateListener { a ->
                p.x = a.animatedValue as Int
                updateSafely(v, p)
                if (!keepFullSizeWhenCollapsed && !expanded) applyWebWindowMode(false)
            }
            anim.start()
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
            flags = FLAGS_BASE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
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
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            flags = FLAGS_BASE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        bannerAdded = addSafely(v, p)
    }

    fun hideBanner() {
        val v = bannerView ?: return
        if (!bannerAdded) return
        removeSafely(v)
        bannerAdded = false
        bannerView = null
    }

    // ------------------------------------------------------------------ ComposeView 생성

    /**
     * 오버레이 윈도우에 붙일 ComposeView 를 매번 새로 만든다.
     *
     * 같은 ComposeView 인스턴스를 removeView 후 다시 addView 하면
     * 화면에는 그려지지만 포인터 입력이 라우팅되지 않아 버튼이 먹지 않는다.
     * (실기기 확인: 첫 확장에서는 동작, 축소 후 재확장하면 모든 버튼 무반응)
     * ComposeView 를 윈도우 사이에서 재사용하지 않는 것이 확실한 해법이라
     * 표시할 때마다 새로 만들고 숨길 때 참조를 버린다.
     */
    private fun newComposeView(content: @Composable () -> Unit): ComposeView {
        val cv = ComposeView(themed)
        owner.attachTo(cv)
        // 기본 전략(detach 시 컴포지션 폐기)을 그대로 쓴다. 뷰를 재사용하지 않으므로 유지할 이유가 없다.
        cv.setContent(content)
        return cv
    }

    // ------------------------------------------------------------------ 기타

    fun onConfigurationChanged() {
        bubbleParams?.let { p ->
            clampBubble(p)
            bubbleView?.let { v -> if (bubbleAdded) updateSafely(v, p) }
        }
        applyWebWindowMode(expanded)
    }

    fun destroy() {
        hideBanner()
        hideCloseZone()
        hideBubble()
        hideControlBar()
        webRoot?.let {
            if (webAdded) removeSafely(it)
        }
        webAdded = false
        owner.onDestroy()
        webRoot = null
        controlView = null
        bannerView = null
        bubbleView = null
        closeView = null
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

    private companion object {
        /** 모든 오버레이 윈도우 공통 플래그. */
        const val FLAGS_BASE = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
