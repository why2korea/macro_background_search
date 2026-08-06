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
import com.why2korea.bgsearch.engine.RefreshResult
import com.why2korea.bgsearch.engine.RowHit
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

        /** "줄" 후보를 찾을 때 올라갈 조상 최대 깊이 */
        private const val MAX_ROW_ANCESTOR_DEPTH = 10

        /**
         * 조상 노드를 "줄"로 인정하는 최대 높이 (화면 높이 대비).
         * 이걸 두지 않으면 3차 문자열이 화면 아무 데나 있어도
         * 최상위 컨테이너가 둘 다 포함한다는 이유로 같은 줄로 오인한다.
         */
        private const val ROW_MAX_HEIGHT_RATIO = 0.30f

        /** 이 횟수 이상 서브트리가 통째로 바뀌면 새로고침이 일어난 것으로 본다. */
        private const val RELOAD_EVENT_THRESHOLD = 2

        /** 클릭이 먹었는지 확인하는 시간 */
        private const val CLICK_VERIFY_MS = 1_200L

        /** 클릭 판정용 서브트리 교체 이벤트 임계치 (새로고침보다 느슨하게) */
        private const val CLICK_EVENT_THRESHOLD = 1

        /** 새로고침 컨트롤로 인정할 텍스트 (정규화 후 부분 일치) */
        private val REFRESH_LABELS = listOf(
            "새로고침", "새로 고침", "refresh", "reload", "다시 시도", "다시시도", "재시도"
        )

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

    /** 화면 서브트리가 통째로 바뀐 횟수. 새로고침 판정에 쓴다. */
    @Volatile
    private var subtreeEventCount = 0

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

    /**
     * 탐색 자체는 폴링 방식이지만, "새로고침이 실제로 일어났는지" 판정에는 이벤트가 필요하다.
     *
     * 내용이 같은 페이지를 새로고침하면 전후 텍스트가 동일해서 화면 비교로는 잡히지 않는다.
     * 반면 새로고침은 화면 서브트리를 통째로 갈아끼우므로 SUBTREE 변경 이벤트가 몰려서 들어온다.
     * 그 이벤트 수를 세어 판정에 쓴다.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        // 우리 오버레이가 만든 이벤트는 무시한다.
        if (e.packageName?.toString() == packageName) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> subtreeEventCount++
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val types = try {
                    e.contentChangeTypes
                } catch (t: Throwable) {
                    0
                }
                if (types and AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE != 0) {
                    subtreeEventCount++
                }
            }
        }
    }

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
        val candidates = ArrayList<AccessibilityNodeInfo>()
        for (root in rootNodes()) {
            candidates.addAll(innermostNodesContainingAny(root, listOf(needleNorm)))
        }
        if (candidates.isEmpty()) return null

        /*
         * 후보가 여러 개면 "가장 근접한" 것을 고른다 = 자기 텍스트가 가장 짧은 노드.
         *
         * 크롬에서 확인한 실제 오작동: 페이지 본문의 링크 대신 주소창
         * ("ko.wikipedia.org/wiki/평택시") 을 클릭했다. 주소창 텍스트는 링크 라벨보다 훨씬 길다.
         * 화면에 보이는 노드를 우선하고, 그중 텍스트가 짧은 것을 고르면 이런 오선택이 걸러진다.
         */
        fun visible(n: AccessibilityNodeInfo): Boolean = try {
            n.isVisibleToUser
        } catch (e: Throwable) {
            true
        }

        return candidates.minByOrNull { n ->
            val len = TextNorm.of(nodeText(n)).length
            // 보이는 노드에 큰 가산점 (짧더라도 안 보이는 노드보다 우선)
            if (visible(n)) len else len + 100_000
        }
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

    /**
     * 읽을 수 있는 대상 화면이 있는지.
     *
     * 우리 앱(설정 화면·오버레이)이 앞에 나와 있으면 rootNodes() 가 비어 있다.
     * 이때 탭·스와이프를 주입하면 **우리 설정 화면을 조작해 버린다.**
     * (실기기에서 확인: 설정 화면의 한글 키보드가 눌려 입력값이 오염됐다)
     * 그래서 화면을 조작하는 모든 동작은 이 검사를 먼저 통과해야 한다.
     */
    private fun hasTarget(): Boolean = rootNodes().isNotEmpty()

    override suspend fun clickText(text: String, preferGesture: Boolean): ClickResult =
        withContext(Dispatchers.Main) {
            if (!hasTarget()) return@withContext ClickResult(false, false, "no-target")
            val needle = TextNorm.of(text)
            if (needle.isEmpty()) {
                return@withContext ClickResult(false, false, "none", error = "empty")
            }
            val node = findNode(needle)
                ?: return@withContext ClickResult(false, false, "none")

            val snippet = nodeText(node).replace("\n", " ").take(60)

            // 화면 밖에 있으면 좌표 탭이 헛나가므로 먼저 화면 안으로 끌어온다.
            try {
                node.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
                )
                delay(250)
            } catch (e: Throwable) {
                Log.w(TAG, "ACTION_SHOW_ON_SCREEN failed", e)
            }

            val methods = if (preferGesture) listOf("gesture-tap", "ACTION_CLICK")
            else listOf("ACTION_CLICK", "gesture-tap")

            var attempted = false
            for (m in methods) {
                val before = snapshotInfo()
                val baseEv = subtreeEventCount
                val fired = when (m) {
                    "ACTION_CLICK" -> performNodeClick(node)
                    else -> performGestureTap(node)
                }
                if (!fired) continue
                attempted = true
                // 클릭이 실제로 먹었는지 화면 변화로 확인한다.
                if (awaitScreenChange(before, baseEv, CLICK_VERIFY_MS, CLICK_EVENT_THRESHOLD)) {
                    return@withContext ClickResult(true, true, m, snippet)
                }
                Log.i(TAG, "click via $m produced no screen change - trying next")
            }

            ClickResult(
                found = true,
                clicked = false,
                method = "none",
                snippet = snippet,
                error = if (attempted) "화면 변화 없음" else "클릭 수단 없음"
            )
        }

    /** 클릭 가능한 조상에 ACTION_CLICK. 실제로 액션을 쐈으면 true */
    private fun performNodeClick(node: AccessibilityNodeInfo): Boolean {
        val target = clickableAncestor(node) ?: return false
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Throwable) {
            Log.w(TAG, "ACTION_CLICK failed", e)
            false
        }
    }

    /** 노드 한가운데를 좌표 탭. 실제로 제스처를 쐈으면 true */
    private suspend fun performGestureTap(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        try {
            node.getBoundsInScreen(r)
        } catch (e: Throwable) {
            return false
        }
        if (r.width() <= 0 || r.height() <= 0) return false
        // 화면 밖 좌표는 무시된다.
        val cx = r.exactCenterX()
        val cy = r.exactCenterY()
        if (cx < 0 || cy < 0 || cx > screenWidth() || cy > screenHeight()) return false
        return tap(cx, cy)
    }

    // ------------------------------------------------------------------ 줄(row) 매칭

    /** 노드 아래 전체 텍스트를 정규화해 합친다. */
    private fun allTextUnder(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        forEachNode(node) { n ->
            val t = nodeText(n)
            if (t.isNotEmpty()) {
                sb.append(t).append(' ')
            }
            false
        }
        return TextNorm.of(sb)
    }

    /** 2차 문자열 중 하나라도 포함하는 가장 안쪽 노드들. */
    private fun innermostNodesContainingAny(
        root: AccessibilityNodeInfo,
        needles: List<String>
    ): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        forEachNode(root) { n ->
            val t = TextNorm.of(nodeText(n))
            if (t.isEmpty()) return@forEachNode false
            if (needles.none { t.contains(it) }) return@forEachNode false
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
                val ct = TextNorm.of(nodeText(c))
                if (needles.any { ct.contains(it) }) {
                    deeper = true
                    break
                }
            }
            if (!deeper) out.add(n)
            false
        }
        return out
    }

    override suspend fun findRowAndClick(
        secondaries: List<String>,
        matchAllSecondary: Boolean,
        tertiaries: List<String>,
        doClick: Boolean
    ): RowHit? = withContext(Dispatchers.Main) {
        if (!hasTarget()) return@withContext null
        val sec = secondaries.map { TextNorm.of(it) }.filter { it.isNotEmpty() }
        if (sec.isEmpty()) return@withContext null
        val ter = tertiaries.map { TextNorm.of(it) }.filter { it.isNotEmpty() }
        val maxRowHeight = screenHeight() * ROW_MAX_HEIGHT_RATIO

        for (root in rootNodes()) {
            for (seed in innermostNodesContainingAny(root, sec)) {
                var cur: AccessibilityNodeInfo? = seed
                var depth = 0
                while (cur != null && depth <= MAX_ROW_ANCESTOR_DEPTH) {
                    val r = Rect()
                    try {
                        cur.getBoundsInScreen(r)
                    } catch (e: Throwable) {
                    }
                    // 화면 대부분을 차지하는 컨테이너는 "줄"이 아니다.
                    if (r.height() in 1..maxRowHeight.toInt()) {
                        val rowText = allTextUnder(cur)
                        val secHit = sec.filter { rowText.contains(it) }
                        val secOk =
                            if (matchAllSecondary) secHit.size == sec.size else secHit.isNotEmpty()
                        val terHit = ter.filter { rowText.contains(it) }
                        val terOk = ter.isEmpty() || terHit.isNotEmpty()

                        if (secOk && terOk) {
                            val row = cur
                            var clicked = false
                            var method = "skipped"
                            if (doClick) {
                                val res = clickNodeOrGesture(row)
                                clicked = res.first
                                method = res.second
                            }
                            return@withContext RowHit(
                                secondaryMatched = mapBack(secondaries, secHit),
                                tertiaryMatched = mapBack(tertiaries, terHit),
                                rowText = rowText.take(120),
                                clicked = clicked,
                                clickMethod = method
                            )
                        }
                    }
                    cur = try {
                        cur.parent
                    } catch (e: Throwable) {
                        null
                    }
                    depth++
                }
            }
        }
        null
    }

    /** 정규화된 매칭 결과를 사용자가 입력한 원본 문자열로 되돌린다. */
    private fun mapBack(originals: List<String>, matchedNorm: List<String>): List<String> =
        originals.filter { o ->
            val n = TextNorm.of(o)
            n.isNotEmpty() && matchedNorm.contains(n)
        }

    /** 노드(또는 클릭 가능한 조상)를 클릭. 실패하면 좌표 탭 제스처. */
    private suspend fun clickNodeOrGesture(node: AccessibilityNodeInfo): Pair<Boolean, String> {
        val target = clickableAncestor(node)
        if (target != null) {
            val ok = try {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (e: Throwable) {
                Log.w(TAG, "ACTION_CLICK failed", e)
                false
            }
            if (ok) return true to "ACTION_CLICK"
        }
        val r = Rect()
        try {
            node.getBoundsInScreen(r)
        } catch (e: Throwable) {
            return false to "none"
        }
        if (r.width() <= 0 || r.height() <= 0) return false to "none"
        val ok = tap(r.exactCenterX(), r.exactCenterY())
        return ok to if (ok) "gesture-tap" else "none"
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
        if (!hasTarget()) return@withContext false
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
        if (!hasTarget()) return@withContext false
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

    // ------------------------------------------------------------------ 새로고침

    /**
     * 페이지를 실제로 새로고침한다.
     *
     * 제스처를 보낸 것만으로 성공으로 치지 않는다. 새로고침이 일어나면 화면이 잠깐
     * 비거나 다시 그려지므로, 트리거 직후 화면을 계속 관찰해 그 변화를 확인한다.
     * 확인되지 않으면 다음 방법으로 넘어간다.
     *
     *  1) 화면에 있는 명시적 새로고침 컨트롤 클릭
     *  2) 당겨서 새로고침 제스처 (크롬·삼성인터넷은 기본 지원)
     *
     * 둘 다 실패하면 ok=false 를 돌려주고, 뒤로가기 복귀는 호출자(엔진)가 판단한다.
     */
    override suspend fun refreshPage(waitMs: Long): RefreshResult = withContext(Dispatchers.Main) {
        if (!hasTarget()) {
            return@withContext RefreshResult(false, "no-target", "대상 화면이 없어 새로고침하지 않음")
        }
        scrollToTop(30)
        delay(300)

        // 1) 명시적 새로고침 컨트롤
        val control = findRefreshControl()
        if (control != null) {
            val before = snapshotInfo()
            val base = subtreeEventCount
            clickNodeOrGesture(control)
            if (awaitReload(before, base, 4_000)) {
                delay(waitMs)
                return@withContext RefreshResult(true, "refresh-control", "새로고침 버튼 클릭")
            }
            Log.i(TAG, "refresh control clicked but no reload detected")
        }

        // 2) 당겨서 새로고침 (느리게 끌어야 스크롤이 아니라 새로고침으로 인식된다)
        val before = snapshotInfo()
        val base = subtreeEventCount
        val h = screenHeight()
        val w = screenWidth()
        swipe(w * 0.5f, h * 0.18f, w * 0.5f, h * 0.82f, 700)
        if (awaitReload(before, base, 5_000)) {
            delay(waitMs)
            return@withContext RefreshResult(
                true, "pull-to-refresh", "당겨서 새로고침 (이벤트 ${subtreeEventCount - base}건)"
            )
        }

        RefreshResult(false, "none", "새로고침이 확인되지 않음")
    }

    /**
     * 새로고침이 실제로 일어났는지 관찰한다.
     *
     * 세 가지 신호 중 하나라도 잡히면 새로고침으로 본다.
     *  1) 서브트리 통째 교체 이벤트가 임계치 이상 (가장 신뢰도 높음)
     *  2) 내용 해시가 달라짐
     *  3) 노드 수가 절반 이하로 떨어짐 (로딩 중 빈 화면)
     *
     * 2·3번만으로는 **내용이 똑같은 페이지를 새로고침했을 때 잡히지 않는다.**
     * (example.com 처럼 정적이고 캐시된 페이지에서 실제로 오판이 발생했다)
     * 그래서 1번을 주 신호로 쓴다.
     *
     * @param baseEventCount 트리거 직전의 subtreeEventCount
     */
    private suspend fun awaitReload(
        before: ScreenSnapshotInfo,
        baseEventCount: Int,
        timeoutMs: Long
    ): Boolean = awaitScreenChange(before, baseEventCount, timeoutMs, RELOAD_EVENT_THRESHOLD)

    /**
     * 화면이 실제로 바뀌었는지 관찰한다. 클릭 검증과 새로고침 검증이 함께 쓴다.
     *
     * @param eventThreshold 서브트리 교체 이벤트가 이 횟수 이상이면 변화로 본다
     */
    private suspend fun awaitScreenChange(
        before: ScreenSnapshotInfo,
        baseEventCount: Int,
        timeoutMs: Long,
        eventThreshold: Int
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(120)
            if (subtreeEventCount - baseEventCount >= eventThreshold) return true
            val now = snapshotInfo()
            if (now.contentHash != before.contentHash) return true
            if (before.nodeCount > 4 && now.nodeCount <= before.nodeCount / 2) return true
        }
        return false
    }

    /** 화면에서 새로고침 버튼처럼 보이는 노드를 찾는다. */
    private fun findRefreshControl(): AccessibilityNodeInfo? {
        for (root in rootNodes()) {
            var hit: AccessibilityNodeInfo? = null
            forEachNode(root) { n ->
                val t = TextNorm.of(nodeText(n))
                if (t.isEmpty() || t.length > 20) return@forEachNode false
                if (REFRESH_LABELS.none { t.contains(TextNorm.of(it)) }) return@forEachNode false
                val visible = try {
                    n.isVisibleToUser
                } catch (e: Throwable) {
                    true
                }
                if (visible && clickableAncestor(n) != null) {
                    hit = n
                    true
                } else {
                    false
                }
            }
            val found = hit
            if (found != null) return found
        }
        return null
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
