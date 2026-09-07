package com.example.awake.data.remote

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 用隐藏 WebView 发起暨大教务 API 请求。
 * 目的：让请求完全复用官方页面的 Cookie、Storage、同源上下文和浏览器头，
 * 避免从 WebView Cookie 手工重建 OkHttp 请求时被教务系统拒绝。
 */
class JnuWebApiBridge(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var ready = false
    private var pendingReady: ((WebView) -> Unit)? = null
    @Volatile
    private var preferredPageUrl: String? = APP_SHOW_URL
    @Volatile
    private var loadedPageUrl: String? = null

    /** 登录流程发现真正的课表页后，把该页上下文同步给隐藏 WebView。 */
    fun setPreferredPageUrl(url: String) {
        preferredPageUrl = url
        mainHandler.post {
            val view = webView
            if (view != null && loadedPageUrl != url) {
                Log.i(TAG, "web api switch page url=$url")
                ready = false
                loadedPageUrl = url
                view.loadUrl(url)
            }
        }
    }

    @Synchronized
    fun post(path: String, form: String): String {
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        var status = 0
        var body = ""

        mainHandler.post {
            ensureWebView { view ->
                val script = buildRequestScript(path, form)
                view.evaluateJavascript(script) { raw ->
                    try {
                        val result = JSONObject(raw.orEmpty().ifBlank { "{}" })
                        status = result.optInt("status", 0)
                        body = result.optString("body")
                        Log.d(
                            TAG,
                            "web api response path=$path code=$status bodyLength=${body.length} " +
                                "bodyPrefix=${body.take(300).replace(Regex("\\s+"), " ")}"
                        )
                    } catch (parseError: Throwable) {
                        error = parseError
                        Log.w(TAG, "web api response parse failed path=$path raw=${raw?.take(200)}")
                    } finally {
                        latch.countDown()
                    }
                }
            }
        }

        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw IllegalStateException("暨大教务请求超时")
        }
        error?.let { throw it }
        if (status !in 200..299) {
            throw ScutHttpException(
                if (status in 300..399 || status == 401 || status == 403) {
                    ScutHttpException.Kind.SESSION_EXPIRED
                } else {
                    ScutHttpException.Kind.SERVER
                },
                "教务系统请求失败（$status）"
            )
        }
        if (body.isBlank() || body.trimStart().startsWith("<")) {
            throw ScutHttpException(
                ScutHttpException.Kind.SESSION_EXPIRED,
                "教务会话已失效，请重新登录"
            )
        }
        return body
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(onReady: (WebView) -> Unit) {
        val existing = webView
        if (existing != null && ready) {
            onReady(existing)
            return
        }
        pendingReady = onReady
        if (existing == null) {
            val manager = CookieManager.getInstance()
            manager.setAcceptCookie(true)
            val view = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        Log.i(TAG, "web api page finished url=$url")
                        if (url?.startsWith(JW_ORIGIN) == true) {
                            loadedPageUrl = url
                            view.evaluateJavascript(
                                "JSON.stringify({title:document.title, body:(document.body?.innerText || '').slice(0, 200)})"
                            ) { page -> Log.d(TAG, "web api page context=$page") }
                            manager.flush()
                            ready = true
                            pendingReady?.invoke(view)
                            pendingReady = null
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            Log.w(TAG, "web api page error: ${error?.description}")
                        }
                    }
                }
            }
            webView = view
            view.loadUrl(preferredPageUrl ?: APP_SHOW_URL)
        }
    }

    private fun buildRequestScript(path: String, form: String): String {
        val pathLiteral = JSONObject.quote(path)
        val formLiteral = JSONObject.quote(form)
        return """
            (() => {
              try {
                const xhr = new XMLHttpRequest();
                xhr.open('POST', $pathLiteral, false);
                xhr.setRequestHeader('Accept', 'application/json, text/javascript, */*; q=0.01');
                xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8');
                xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
                xhr.send($formLiteral);
                return {status: xhr.status, body: xhr.responseText};
              } catch (e) {
                return {status: 0, body: '', error: String(e && e.message || e)};
              }
            })();
        """.trimIndent()
    }

    companion object {
        private const val TAG = "AwakeJnuWeb"
        private const val JW_ORIGIN = "https://jw.jnu.edu.cn"
        const val APP_SHOW_URL = "$JW_ORIGIN/appShow?appId=4770397878132218"
        private const val SCHEDULE_PAGE_URL = "$JW_ORIGIN/jwapp/sys/wdkb/*default/index.do?t_s=1788708192491&amp_sec_version_=1&gid_=QmhOSm9WL0dTSlc1SHgwTmw3eVBtWWFsalIvSDV2TlpORUtlWU1sMTVIMzZGaG5TRTRxNDB6QzZQSFlTSWc2WFcwUmFVZmNYV2tXMUg2dWxWWU1XeWc9PQ&EMAP_LANG=zh&THEME=cherry#/xskcb"
    }
}




