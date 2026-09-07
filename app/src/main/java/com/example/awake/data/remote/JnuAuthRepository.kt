package com.example.awake.data.remote

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI
import org.json.JSONArray

/**
 * 暨南大学登录协调器：WebView 打开 https://jw.jnu.edu.cn/，用户在官方页面完成
 * 统一身份认证；登录成功后自动进入课表模块，再抓取 /jwapp 路径下的会话 Cookie。
 */
class JnuWebViewCoordinator(
    private val sessionStore: JnuSessionStore,
    private val webApi: JnuWebApiBridge? = null
) {
    private var authenticatedCallback: (() -> Unit)? = null
    private var scheduleNavigationStarted = false
    private var homeScanAttempt = 0
    private var homeScanGeneration = 0
    private var authenticatedNotified = false

    fun attach(
        webView: WebView,
        onStatus: (String) -> Unit = {},
        onFailure: (String) -> Unit = {},
        onAuthenticated: () -> Unit
    ) {
        authenticatedCallback = onAuthenticated
        scheduleNavigationStarted = false
        homeScanAttempt = 0
        homeScanGeneration = 0
        authenticatedNotified = false
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                val uri = runCatching { java.net.URI(url) }.getOrNull()
                val isJnuHttp = uri?.scheme?.equals("http", ignoreCase = true) == true &&
                    uri.host?.lowercase() == JnuSessionStore.JW_HOST
                if (isJnuHttp) {
                    // 教务登录后可能回跳 HTTP 首页；Android 默认禁止明文流量。
                    // 同一域名的 HTTP 回跳强制改走 HTTPS，避免 WebView 报 ERR_CLEARTEXT。
                    val secureUrl = url.replaceFirst("http://", "https://", ignoreCase = true)
                    Log.i(TAG, "rewrite jnu http redirect to https: $secureUrl")
                    view.loadUrl(secureUrl)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                when {
                    isSchedulePage(url) -> {
                        checkUrl(view, url, onStatus)
                        runSemesterApiProbe(view)
                    }
                    isHomePage(url.orEmpty()) -> openScheduleApp(view)
                    else -> checkUrl(view, url, onStatus)
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val path = request.url.path.orEmpty()
                if (request.url.host?.lowercase() == JnuSessionStore.JW_HOST &&
                    path.startsWith(APP_PATH_PREFIX)
                ) {
                    val manager = CookieManager.getInstance()
                    manager.setAcceptCookie(true)
                    sessionStore.captureFromCookieManager(
                        manager,
                        pathAncestors(path) + listOf(SCHEDULE_API_PATH)
                    )
                    if (path.endsWith("/xskcb.do")) {
                        Log.i(TAG, "jnu schedule api request detected")
                    }
                }
                return null
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // 仅主文档加载失败才算失败；子资源失败由页面自行处理。
                if (request?.isForMainFrame == true) {
                    Log.w(TAG, "jnu login page error: ${error?.description}")
                    onFailure("登录页面加载失败，请检查网络后重试")
                }
            }
        }
        val current = webView.url
        if (current.isNullOrBlank() || isEntryReloadNeeded(current)) {
            webView.loadUrl(ENTRY_URL)
        }
        onStatus("请在校方页面完成登录；成功进入教务首页后将自动继续")
    }

    /** 从全局 WebView CookieManager 重新刷新一次暨大会话，供登录后自动重试使用。 */
    fun refreshFromCookieManager() {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        manager.flush()
        sessionStore.captureFromCookieManager(manager, listOf(SCHEDULE_API_PATH))
        Log.i(TAG, "jnu session refresh requested")
    }

    /** 用户点击“重新检查”时调用：重新检查当前页面是否已经登录成功。 */
    fun confirmCurrentPage(
        webView: WebView,
        onAuthenticated: () -> Unit,
        onFailure: (String) -> Unit
    ): Boolean = checkUrl(
        webView,
        webView.url,
        { },
        onAuthenticated = onAuthenticated,
        onFailure = onFailure
    )

    fun cancel(webView: WebView) {
        authenticatedCallback = null
        webView.stopLoading()
    }

    /** 仅清空暨大会话（内存 Cookie），不影响华工的已保存会话。 */
    fun clear() {
        authenticatedNotified = false
        sessionStore.clear()
    }

    private fun isEntryReloadNeeded(url: String): Boolean = runCatching {
        val uri = URI(url)
        val path = uri.path.orEmpty()
        uri.host == JnuSessionStore.JW_HOST &&
            (isHomePage(path) || isSchedulePage(url))
    }.getOrDefault(false)

    private fun checkUrl(
        webView: WebView,
        rawUrl: String?,
        onStatus: (String) -> Unit,
        onAuthenticated: () -> Unit = { authenticatedCallback?.invoke() },
        onFailure: (String) -> Unit = { }
    ): Boolean {
        val uri = runCatching { URI(rawUrl.orEmpty()) }.getOrNull()
        val host = uri?.host?.lowercase()
        if (host != JnuSessionStore.JW_HOST) {
            onStatus("正在打开暨南大学统一身份认证…")
            return false
        }
        val path = uri.path.orEmpty()
        return when {
            isSchedulePage(path) -> {
                val manager = CookieManager.getInstance()
                manager.setAcceptCookie(true)
                manager.flush()
                sessionStore.captureFromCookieManager(manager, pathAncestors(path) + listOf(SCHEDULE_API_PATH))
                if (sessionStore.isAuthenticated()) {
                    Log.i(TAG, "jnu schedule session captured")
                    if (!authenticatedNotified) {
                        authenticatedNotified = true
                        onAuthenticated()
                    }
                    true
                } else {
                    onFailure("未能获取教务会话，请重新登录")
                    false
                }
            }
            isHomePage(path) -> {
                onStatus("已登录教务首页，正在自动打开课表模块…")
                openScheduleApp(webView)
                false
            }
            else -> {
                onStatus("请在官方页面完成登录；成功进入教务首页后将自动继续")
                false
            }
        }
    }

    /**
     * JNU 登录后的首页只说明统一身份认证成功；课表 API 所在的 /jwapp 路径
     * 可能还有独立的 JSESSIONID。这里参照 SCUT 的方案，在首页 DOM/内联脚本里
     * 找到课表模块地址并自动跳转一次，等课表页加载完成后再接管会话。
     */
        private fun openScheduleApp(view: WebView) {
        if (scheduleNavigationStarted) return
        scheduleNavigationStarted = true
        homeScanGeneration += 1
        val target = JnuWebApiBridge.APP_SHOW_URL
        Log.i(TAG, "jnu schedule app redirect requested url=$target")
        webApi?.setPreferredPageUrl(target)
        view.loadUrl(target)
    }

    private fun scanHomeForSchedule(view: WebView) {
        if (scheduleNavigationStarted || homeScanAttempt >= HOME_SCAN_MAX_ATTEMPTS) return
        val generation = ++homeScanGeneration
        homeScanAttempt += 1
        view.evaluateJavascript(HOME_LINK_SCAN_SCRIPT) { result ->
            if (generation != homeScanGeneration || scheduleNavigationStarted) return@evaluateJavascript
            val target = extractScheduleUrl(result)
            if (target != null) {
                Log.i(TAG, "jnu schedule module detected")
                scheduleNavigationStarted = true
                webApi?.setPreferredPageUrl(target)
                view.loadUrl(target)
            } else if (homeScanAttempt >= HOME_SCAN_MAX_ATTEMPTS) {
                Log.w(TAG, "jnu schedule module not found in home page")
                webApi?.setPreferredPageUrl(FALLBACK_SCHEDULE_URL)
                view.loadUrl(FALLBACK_SCHEDULE_URL)
                scheduleNavigationStarted = true
            } else {
                view.postDelayed({
                    if (generation == homeScanGeneration && !scheduleNavigationStarted) {
                        scanHomeForSchedule(view)
                    }
                }, HOME_SCAN_RETRY_DELAY_MS)
            }
        }
    }

    private fun runSemesterApiProbe(view: WebView) {
        view.evaluateJavascript(SEMESTER_API_PROBE_SCRIPT) { result ->
            Log.i(TAG, "jnu webview xnxq probe result=${result?.take(500)}")
        }
    }
    private fun extractScheduleUrl(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val candidates = runCatching { JSONArray(raw) }.getOrNull() ?: return null
        for (index in 0 until candidates.length()) {
            val candidate = candidates.optString(index).trim()
            if (candidate.isBlank()) continue
            val decoded = candidate
                .replace("\\/", "/")
                .replace("&amp;", "&")
            val fullUrl = if (decoded.startsWith("http", ignoreCase = true)) {
                decoded
            } else {
                "https://${JnuSessionStore.JW_HOST}$decoded"
            }
            if (isSchedulePage(fullUrl)) return fullUrl
        }
        return null
    }

    private fun pathAncestors(path: String): List<String> {
        val segments = path.split('/').filter(String::isNotBlank)
        return segments.indices.map { index -> "/" + segments.take(index + 1).joinToString("/") }
    }

    private fun isHomePage(path: String): Boolean =
        path.equals(HOME_PATH_PREFIX, ignoreCase = true) ||
            path.startsWith(HOME_PATH_PREFIX, ignoreCase = true)

    private fun isSchedulePage(rawUrl: String?): Boolean = runCatching {
        val path = URI(rawUrl.orEmpty()).path.orEmpty()
        path.startsWith(APP_PATH_PREFIX) && path.contains(SCHEDULE_PATH_MARKER, ignoreCase = true)
    }.getOrDefault(false)

    companion object {
        private const val TAG = "AwakeJnuAuth"
        const val ENTRY_URL = "https://jw.jnu.edu.cn/"
        const val APP_PATH_PREFIX = "/jwapp/"
        const val HOME_PATH_PREFIX = "/new/"
        private const val SCHEDULE_PATH_MARKER = "/jwapp/sys/wdkb/"
        private const val SCHEDULE_API_PATH = "/jwapp/sys/wdkb/modules/xskcb/xskcb.do"
        private const val HOME_SCAN_MAX_ATTEMPTS = 12
        private const val HOME_SCAN_RETRY_DELAY_MS = 800L

        /** 动态跳转失败时的兜底地址；正常情况下优先使用首页里发现的用户专属地址。 */
        private const val FALLBACK_SCHEDULE_URL = "https://jw.jnu.edu.cn/jwapp/sys/wdkb/*default/index.do?t_s=1788708192491&amp_sec_version_=1&gid_=QmhOSm9WL0dTSlc1SHgwTmw3eVBtWWFsalIvSDV2TlpORUtlWU1sMTVIMzZGaG5TRTRxNDB6QzZQSFlTSWc2WFcwUmFVZmNYV2tXMUg2dWxWWU1XeWc9PQ&EMAP_LANG=zh&THEME=cherry#/xskcb"

        private val SEMESTER_API_PROBE_SCRIPT = """
            (() => {
              try {
                const xhr = new XMLHttpRequest();
                xhr.open('POST', '/jwapp/sys/wdkb/modules/jshkcb/xnxqcx.do', false);
                xhr.setRequestHeader('Accept', 'application/json, text/javascript, */*; q=0.01');
                xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8');
                xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
                xhr.send('*order=-DM');
                return JSON.stringify({status: xhr.status, body: xhr.responseText.slice(0, 300)});
              } catch (e) {
                return JSON.stringify({status: 0, error: String(e && e.message || e)});
              }
            })();
        """.trimIndent()
        private val HOME_LINK_SCAN_SCRIPT = """
            (() => {
              const docs = [document];
              try {
                for (let i = 0; i < window.frames.length; i++) {
                  try { docs.push(window.frames[i].document); } catch (e) {}
                }
              } catch (e) {}
              const hits = new Set();
              docs.forEach(doc => {
                try {
                  const html = (doc.documentElement.outerHTML || '')
                    .replace(/\u002F/gi, '/')
                    .replace(/\\\//g, '/');
                  const matches = html.match(/(?:https?:\/\/jw\.jnu\.edu\.cn)?\/jwapp\/sys\/wdkb\/[^"'<>\s]+/g) || [];
                  matches.forEach(item => hits.add(item));
                } catch (e) {}
              });
              return JSON.stringify(Array.from(hits));
            })();
        """.trimIndent()
    }
}

/** 暨南大学登录会话门面：只暴露登录、取消和登出，凭证保存在内存 JnuSessionStore。 */
class JnuAuthRepository(
    private val coordinator: JnuWebViewCoordinator,
    val sessionStore: JnuSessionStore
) {
    fun isAuthenticated(): Boolean = sessionStore.isAuthenticated()

    fun attach(
        webView: WebView,
        onStatus: (String) -> Unit = {},
        onFailure: (String) -> Unit = {},
        onAuthenticated: () -> Unit
    ) = coordinator.attach(webView, onStatus, onFailure, onAuthenticated)

    fun refreshFromCookieManager() = coordinator.refreshFromCookieManager()

    fun confirmCurrentPage(
        webView: WebView,
        onAuthenticated: () -> Unit,
        onFailure: (String) -> Unit
    ) = coordinator.confirmCurrentPage(webView, onAuthenticated, onFailure)

    fun cancel(webView: WebView) = coordinator.cancel(webView)

    fun logout() = coordinator.clear()
}





