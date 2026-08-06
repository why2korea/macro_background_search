package com.why2korea.bgsearch.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.why2korea.bgsearch.engine.ClickResult
import com.why2korea.bgsearch.engine.ScannerHolder
import com.why2korea.bgsearch.engine.ScreenScanner
import com.why2korea.bgsearch.engine.ScreenSnapshotInfo
import com.why2korea.bgsearch.util.TextNorm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

private const val TAG = "BgSearchScan"

/**
 * 다른 앱 화면을 읽고 조작하는 접근성 서비스.
 *
 * 하는 일
 *  - 현재 화면(대상 앱)의 접근성 노드 트리를 순회해 텍스트를 수집
 *  - 특정 문자열을 가진 노드를 찾아 클릭 (ACTION_CLICK → 실패 시 좌표 탭 제스처)
 *  - 스크롤 (ACTION_SCROLL_FORWARD → 실패 시 스와이프 제스처)
 *  - 당겨서 새로고침 제스처
 *  - 화면 캡처 (API 30+)
 *
 * 자기 자신(오버레이 버블·패널)의 텍스트는 반드시 제외한다.
 * 그러지 않으면 컨트롤 바의 "시작/정지" 같은 글자를 대상 화면 텍스트로 오인한다.
 */
class ScanService : AccessibilityService(), ScreenScanner {

    companion object {
        /** 노드 트리 순회 상한. 비정상적으로 큰 화면에서 폭주하지 않게 한다. */
        private const val MAX_NODES = 4000
        private const val MAX_CLICK_ANCESTOR_DEPTH = 12

        /** 접근성 서비스가 설정에서 켜져 있는지 확인. */
        fun isEnabled(ctx: Context): Boolean {
            val expected = ComponentName(ctx, ScanService::class.java)
            val raw = try {
                Settings.Secure.getString(
                    ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            } catch (e: Throwable) {
                null
            } ?: return false
            return raw.split(':').any {
                val c = ComponentName.unflattenFromString(it.trim())
                c != null && c.packageName == expected.packageName &&
                    c.className == expected.className
            }
        }
    }

    @Volatile
    private var connected = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        ScannerHolder.attach(this)
        Log.i(TAG, "accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        ScannerHolder.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = false
        ScannerHolder.detach(this)
        super.onDestroy()
    }

    /** 이벤트 구동이 아니라 엔진이 필요할 때 화면을 읽는 폴링 방식이므로 여기서 할 일은 없다. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ------------------------------------------------------------------ 노드 트리

    override fun isReady(): Boolean = connected

    /**
     * 읽을 대상이 되는 루트 노드들.
     * 우리 앱 자신의 오버레이 창과 시스템 UI(상태바·내비게이션바)는 제외한다.
     */
    private fun rootNodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            val ws = windows
            if (ws != null) {
                for (w in ws) {
                    if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                    val r = try {
                        w.root
                    } catch (e: Throwable) {
                        null
                    } ?: continue
                    if (r.packageName?.toString() == packageName) continue
                    out.add(r)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "windows() failed", e)
        }
        if (out.isEmpty()) {
            try {
                rootInActiveWindow?.let {
                    if (it.packageName?.toString() != packageName) out.add(it)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "rootInActiveWindow failed", e)
            }
        }
        return out
    }

    /** 너비 우선으로 노드를 순회한다. block 이 true 를 돌려주면 중단. */
    private inline fun forEachNode(root: AccessibilityNodeInfo, block: (AccessibilityNodeInfo) -> Boolean) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val n = queue.removeFirst()
            visited++
            if (block(n)) return
            val count = try {
                n.childCount
            } catch (e: Throwable) {
                0
            }
            for (i in 0 until count) {
                val c = try {
                    n.getChild(i)
                } catch (e: Throwable) {
                    null
                }
                if (c != null) queue.add(c)
            }
        }
    }

    private fun nodeText(n: AccessibilityNodeInfo): String {
        val t = try {
            n.text?.toString()
        } catch (e: Throwable) {
            null
        }
        if (!t.isNullOrBlank()) return t
        val d = try {
            n.contentDescription?.toString()
        } catch (e: Throwable) {
            null
        }
        return d ?: ""
    }

    // ------------------------------------------------------------------ 읽기

    override fun readScreenTexts(): List<String> {
        val out = ArrayList<String>()
        for (root in rootNodes()) {
            forEachNode(root) { n ->
                val t = TextNorm.of(nodeText(n))
                if (t.isNotEmpty()) out.add(t)
                false
            }
        }
        return out
    }

    override fun snapshotInfo(): ScreenSnapshotInfo {
        val texts = readScreenTexts()
        val pkg = try {
            rootNodes().firstOrNull()?.packageName?.toString() ?: ""
        } catch (e: Throwable) {
            ""
        }
        return ScreenSnapshotInfo(
            nodeCount = texts.size,
            contentHash = texts.joinToString("").hashCode(),
            packageName = pkg
        )
    }

    override fun matchOnScreen(targets: List<String>): List<String> {
        if (targets.isEmpty()) return emptyList()
        val joined = readScreenTexts().joinToString(" ")
        val hit = ArrayList<String>()
        for (t in targets) {
            val n = TextNorm.of(t)
            if (n.isNotEmpty() && joined.contains(n)) hit.add(t)
        }
        return hit
    }

    /**
     * 문자열을 포함하는 노드 중 가장 안쪽(자식이 같은 문자열을 갖지 않는) 것을 고른다.
     * 화면에 보이는 노드를 우선한다.
     */
    private fun findNode(needleNorm: String): AccessibilityNodeInfo? {
        var fallback: AccessibilityNodeInfo? = null
        for (root in rootNodes()) {
            var hit: AccessibilityNodeInfo? = null
            forEachNode(root) { n ->
                val t = TextNorm.of(nodeText(n))
                if (t.isEmpty() || !t.contains(needleNorm)) return@forEachNode false
                // 자식이 같은 문자열을 가지면 더 안쪽이 있으므로 건너뛴다
                var deeper = false
                val count = try {
                    n.childCount
                } catch (e: Throwable) {
                    0
                }
                for (i in 0 until count) {
                    val c = try {
                        n.getChild(i)
                    } catch (e: Throwable) {
                        null
                    } ?: continue
                    if (TextNorm.of(nodeText(c)).contains(needleNorm)) {
                        deeper = true
                        break
                    }
                }
                if (deeper) return@forEachNode false
                val visible = try {
                    n.isVisibleToUser
                } catch (e: Throwable) {
                    true
                }
                if (visible) {
                    hit = n
                    true
                } else {
                    if (fallback == null) fallback = n
                    false
                }
            }
            val found = hit
            if (found != null) return found
        }
        return fallback
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < MAX_CLICK_ANCESTOR_DEPTH) {
            val ok = try {
                cur.isClickable && cur.isEnabled
            } catch (e: Throwable) {
                false
            }
            if (ok) return cur
            cur = try {
                cur.parent
            } catch (e: Throwable) {
                null
            }
            depth++
        }
        return null
    }

    // ------------------------------------------------------------------ 클릭

    override suspend fun clickText(text: String): ClickResult = withContext(Dispatchers.Main) {
        val needle = TextNorm.of(text)
        if (needle.isEmpty()) return@withContext ClickResult(false, false, "none", error = "empty")
        val node = findNode(needle)
            ?: return@withContext ClickResult(false, false, "none")

        val snippet = nodeText(node).replace("\n", " ").take(60)

        // 1순위: 클릭 가능한 조상에 ACTION_CLICK
        val target = clickableAncestor(node)
        if (target != null) {
            val ok = try {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (e: Throwable) {
                Log.w(TAG, "ACTION_CLICK failed", e)
                false
            }
            if (ok) return@withContext ClickResult(true, true, "ACTION_CLICK", snippet)
        }

        // 2순위: 노드 좌표 한가운데를 탭하는 제스처
        val r = Rect()
        try {
            node.getBoundsInScreen(r)
        } catch (e: Throwable) {
            return@withContext ClickResult(true, false, "none", snippet, "bounds failed")
        }
        if (r.width() <= 0 || r.height() <= 0) {
            return@withContext ClickResult(true, false, "none", snippet, "empty bounds")
        }
        val ok = tap(r.exactCenterX(), r.exactCenterY())
        ClickResult(true, ok, if (ok) "gesture-tap" else "none", snippet)
    }

    // ------------------------------------------------------------------ 스크롤

    /** 스크롤 가능한 노드 중 화면에서 가장 큰 것 */
    private fun scrollableNode(): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        for (root in rootNodes()) {
            forEachNode(root) { n ->
                val ok = try {
                    n.isScrollable
                } catch (e: Throwable) {
                    false
                }
                if (ok) {
                    val r = Rect()
                    try {
                        n.getBoundsInScreen(r)
                    } catch (e: Throwable) {
                    }
                    val area = r.width() * r.height()
                    if (area > bestArea) {
                        bestArea = area
                        best = n
                    }
                }
                false
            }
        }
        return best
    }

    override suspend fun scrollDown(ratio: Float): Boolean = withContext(Dispatchers.Main) {
        val before = snapshotInfo().contentHash

        val node = scrollableNode()
        if (node != null) {
            val ok = try {
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            } catch (e: Throwable) {
                false
            }
            if (ok) {
                delay(350)
                return@withContext snapshotInfo().contentHash != before
            }
        }

        // 스크롤 가능한 노드를 못 찾거나 액션이 거부되면 스와이프 제스처로 대체한다.
        val h = screenHeight()
        val w = screenWidth()
        val step = (h * ratio.coerceIn(0.1f, 1.2f)).toInt().coerceIn(120, (h * 0.7f).toInt())
        val startY = h * 0.72f
        val endY = (startY - step).coerceAtLeast(h * 0.08f)
        val ok = swipe(w * 0.5f, startY, w * 0.5f, endY, 320)
        if (!ok) return@withContext false
        delay(350)
        snapshotInfo().contentHash != before
    }

    override suspend fun scrollToTop(maxSteps: Int): Boolean = withContext(Dispatchers.Main) {
        val h = screenHeight()
        val w = screenWidth()
        var unchanged = 0
        for (i in 0 until maxSteps) {
            val before = snapshotInfo().contentHash
            val node = scrollableNode()
            var moved = false
            if (node != null) {
                moved = try {
                    node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                } catch (e: Throwable) {
                    false
                }
            }
            if (!moved) {
                swipe(w * 0.5f, h * 0.25f, w * 0.5f, h * 0.80f, 280)
            }
            delay(320)
            if (snapshotInfo().contentHash == before) {
                unchanged++
                if (unchanged >= 2) return@withContext true
            } else {
                unchanged = 0
            }
        }
        true
    }

    /**
     * 당겨서 새로고침.
     * 화면 위쪽에서 아래로 천천히 길게 끌어내린다. (일반적인 pull-to-refresh 동작)
     */
    override suspend fun pullToRefresh(): Boolean = withContext(Dispatchers.Main) {
        val h = screenHeight()
        val w = screenWidth()
        // 느리게 끌어야 스크롤이 아니라 새로고침으로 인식된다.
        swipe(w * 0.5f, h * 0.22f, w * 0.5f, h * 0.78f, 700)
    }

    override suspend fun pressBack(): Boolean = withContext(Dispatchers.Main) {
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (e: Throwable) {
            Log.w(TAG, "back failed", e)
            false
        }
    }

    // ------------------------------------------------------------------ 제스처

    private fun screenWidth(): Float = resources.displayMetrics.widthPixels.toFloat()
    private fun screenHeight(): Float = resources.displayMetrics.heightPixels.toFloat()

    private suspend fun tap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return runGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
                .build()
        )
    }

    private suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val p = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return runGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, durationMs))
                .build()
        )
    }

    private suspend fun runGesture(g: GestureDescription): Boolean =
        withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                val cb = object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val posted = try {
                    dispatchGesture(g, cb, null)
                } catch (e: Throwable) {
                    Log.w(TAG, "dispatchGesture failed", e)
                    false
                }
                if (!posted && cont.isActive) cont.resume(false)
            }
        } ?: false

    // ------------------------------------------------------------------ 스크린샷

    override suspend fun takeScreenshot(target: File): Boolean {
        // AccessibilityService.takeScreenshot 은 API 30 부터 제공된다.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i(TAG, "takeScreenshot requires API 30+")
            return false
        }
        return captureApi30(target)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureApi30(target: File): Boolean {
        val bitmap = withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine<Bitmap?> { cont ->
                try {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        ContextCompat.getMainExecutor(this@ScanService),
                        object : AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                                val bmp = try {
                                    val hw = Bitmap.wrapHardwareBuffer(
                                        result.hardwareBuffer, result.colorSpace
                                    )
                                    // 하드웨어 비트맵은 그대로 압축이 안 되는 기기가 있어 소프트웨어로 복사한다.
                                    val copy = hw?.copy(Bitmap.Config.ARGB_8888, false)
                                    hw?.recycle()
                                    copy
                                } catch (e: Throwable) {
                                    Log.w(TAG, "wrapHardwareBuffer failed", e)
                                    null
                                } finally {
                                    try {
                                        result.hardwareBuffer.close()
                                    } catch (_: Throwable) {
                                    }
                                }
                                if (cont.isActive) cont.resume(bmp)
                            }

                            override fun onFailure(errorCode: Int) {
                                Log.w(TAG, "takeScreenshot failed: $errorCode")
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                } catch (e: Throwable) {
                    Log.w(TAG, "takeScreenshot threw", e)
                    if (cont.isActive) cont.resume(null)
                }
            }
        } ?: return false   // 타임아웃이거나 캡처 실패

        return withContext(Dispatchers.IO) {
            try {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                true
            } catch (e: Throwable) {
                Log.w(TAG, "screenshot save failed", e)
                false
            } finally {
                bitmap.recycle()
            }
        }
    }
}
