package com.why2korea.bgsearch.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

private const val TAG = "BgSearchWeb"

/**
 * WebView 를 감싸고 코루틴 친화적인 API 를 제공한다.
 *
 * 참고 프로젝트의 WebController 를 이식했으나, 이 앱에서는 WebView 가 Activity 가 아니라
 * 오버레이 윈도우(Service 소유) 안에 있으므로
 *  - PixelCopy(Activity Window 필요) 대신 소프트웨어 레이어 + Canvas draw 로 캡처한다.
 *  - WebView 생성도 Service context 로 직접 수행한다.
 */
class WebController {

    var webView: WebView? = null
        private set

    private var pageDeferred: CompletableDeferred<Boolean>? = null

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var currentUrl: String = ""
        private set

    @Volatile
    var progress: Int = 0
        private set

    // ------------------------------------------------------------------ 생성

    /** 오버레이 윈도우에 넣을 WebView 를 만든다. 반드시 메인 스레드에서 호출. */
    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(ctx: Context): WebView {
        val view = WebView(ctx)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true          // 로그인 세션 유지
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, true)

        view.isVerticalScrollBarEnabled = true
        // 콘텐츠를 한 번도 커밋하지 않은 WebView 는 드로우 펑터가 초기화되지 않아 흰 화면으로 남는다.
        // 이 로드의 콜백이 루프의 load()/reload() 대기와 섞이지 않도록 client 를 붙이기 전에 호출한다.
        view.loadUrl("about:blank")
        view.webViewClient = buildWebViewClient()
        view.webChromeClient = buildChromeClient()
        webView = view
        return view
    }

    fun detach() {
        webView = null
    }

    // ------------------------------------------------------------------ 클라이언트

    private fun buildWebViewClient(): WebViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            currentUrl = url ?: currentUrl
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            currentUrl = url ?: currentUrl
            pageDeferred?.let { if (!it.isCompleted) it.complete(true) }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            // 메인 프레임 오류만 실패로 취급한다. (광고/서브리소스 오류로 루프가 깨지면 안 됨)
            if (request?.isForMainFrame == true) {
                lastError = "load error: " + (error?.description ?: "unknown")
                pageDeferred?.let { if (!it.isCompleted) it.complete(false) }
            }
        }
    }

    private fun buildChromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progress = newProgress
        }

        // 페이지가 띄우는 blocking 다이얼로그는 전부 자동 처리한다. (탐색 루프 정지 방지)
        override fun onJsAlert(
            view: WebView?, url: String?, message: String?, result: JsResult?
        ): Boolean {
            result?.confirm(); return true
        }

        override fun onJsConfirm(
            view: WebView?, url: String?, message: String?, result: JsResult?
        ): Boolean {
            result?.confirm(); return true
        }

        override fun onJsBeforeUnload(
            view: WebView?, url: String?, message: String?, result: JsResult?
        ): Boolean {
            result?.confirm(); return true
        }
    }

    // ------------------------------------------------------------------ 동작

    /** URL 로드 후 onPageFinished 까지 대기. */
    suspend fun load(url: String, timeoutMs: Long = 30_000): Boolean = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext false
        lastError = null
        val d = CompletableDeferred<Boolean>()
        pageDeferred = d
        try {
            view.stopLoading()
            view.loadUrl(url)
        } catch (e: Throwable) {
            lastError = e.message
            return@withContext false
        }
        val ok = withTimeoutOrNull(timeoutMs) { d.await() }
        pageDeferred = null
        ok ?: run { lastError = "timeout"; false }
    }

    /** 새로고침 후 onPageFinished 까지 대기. */
    suspend fun reload(timeoutMs: Long = 30_000): Boolean = withContext(Dispatchers.Main) {
        val view = webView ?: return@withContext false
        lastError = null
        val d = CompletableDeferred<Boolean>()
        pageDeferred = d
        try {
            view.reload()
        } catch (e: Throwable) {
            lastError = e.message
            return@withContext false
        }
        val ok = withTimeoutOrNull(timeoutMs) { d.await() }
        pageDeferred = null
        ok ?: run { lastError = "timeout"; false }
    }

    /** JS 실행 후 raw 결과 문자열을 반환. 실패 시 null. */
    suspend fun eval(js: String, timeoutMs: Long = 15_000): String? =
        withContext(Dispatchers.Main) {
            val view = webView ?: return@withContext null
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<String?> { cont ->
                    try {
                        view.evaluateJavascript(js) { value ->
                            if (cont.isActive) cont.resume(value)
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "evaluateJavascript failed", e)
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }
        }

    /**
     * 스크립트가 JSON.stringify(...) 로 돌려준 값을 JSONObject 로 변환.
     * 어떤 이유로든 실패하면 ok=false 인 객체를 돌려주고 예외를 던지지 않는다.
     */
    suspend fun evalJson(js: String, timeoutMs: Long = 15_000): JSONObject {
        val raw = eval(js, timeoutMs) ?: return fail("eval-null")
        if (raw == "null" || raw.isBlank()) return fail("eval-null")
        return try {
            when (val v = JSONTokener(raw).nextValue()) {
                is String -> JSONObject(v)
                is JSONObject -> v
                else -> fail("bad-result")
            }
        } catch (e: Throwable) {
            fail("parse:" + (e.message ?: "?"))
        }
    }

    private fun fail(reason: String) = JSONObject().put("ok", false).put("err", reason)

    /**
     * 현재 WebView 화면을 PNG 로 저장.
     *
     * 오버레이 윈도우에는 Activity Window 가 없어 PixelCopy 를 쓸 수 없다.
     * 하드웨어 가속 WebView 는 view.draw(Canvas) 로 비어 나오는 경우가 있으므로
     * 캡처 직전에만 소프트웨어 레이어로 전환한 뒤 원복한다.
     */
    suspend fun screenshot(target: File): Boolean {
        val view = withContext(Dispatchers.Main) { webView } ?: return false

        val bitmap = withContext(Dispatchers.Main) {
            val w = view.width
            val h = view.height
            if (w <= 0 || h <= 0) return@withContext null
            try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (e: Throwable) {
                Log.w(TAG, "bitmap alloc failed", e)
                null
            }
        } ?: return false

        val prevLayer = withContext(Dispatchers.Main) {
            val p = view.layerType
            try {
                view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                view.invalidate()
            } catch (e: Throwable) {
                Log.w(TAG, "software layer failed", e)
            }
            p
        }
        // 소프트웨어 레이어로 한 프레임 그려질 시간을 준다.
        delay(350)

        val captured = withContext(Dispatchers.Main) {
            try {
                view.draw(Canvas(bitmap))
                true
            } catch (e: Throwable) {
                Log.w(TAG, "view.draw failed", e)
                false
            } finally {
                try {
                    view.setLayerType(prevLayer, null)
                } catch (_: Throwable) {
                }
            }
        }

        if (!captured) {
            bitmap.recycle()
            return false
        }

        val ok = withContext(Dispatchers.IO) {
            try {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                true
            } catch (e: Throwable) {
                Log.w(TAG, "screenshot save failed", e)
                false
            }
        }
        bitmap.recycle()
        return ok
    }

    /** 탐색 종료 시 정리. (WebView 자체는 유지) */
    fun cleanup() {
        val view = webView ?: return
        try {
            view.stopLoading()
            view.evaluateJavascript(InjectScripts.clearBanner(), null)
        } catch (_: Throwable) {
        }
        try {
            CookieManager.getInstance().flush()
        } catch (_: Throwable) {
        }
    }

    /** 서비스 종료 시 완전 파기. */
    fun destroy() {
        val view = webView ?: return
        try {
            view.stopLoading()
            (view.parent as? ViewGroup)?.removeView(view)
            view.removeAllViews()
            view.destroy()
        } catch (_: Throwable) {
        }
        webView = null
    }
}
