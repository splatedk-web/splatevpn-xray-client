package com.danieldk.splatevpn.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.webkit.*
import androidx.webkit.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.Executor
import kotlin.coroutines.resume

internal class GeminiWebViewCheck(private val context: Context, private val profileSuffix: String = "ai_dns_verifier") {
    companion object { private var profileReady = false }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun check(port: Int, fixture: String? = null): AiDnsTargetResult = withContext(Dispatchers.Main.immediate) {
        val started = System.nanoTime()
        fun result(outcome: AiDnsOutcome, detail: AiDnsDetail) = AiDnsTargetResult("Gemini", outcome, (System.nanoTime() - started) / 1_000_000, detail)
        var browser: WebView? = null
        var proxyInstalled = false
        try {
            require(port in 1..65535)
            if (!profileReady) {
                if (Build.VERSION.SDK_INT >= 28) WebView.setDataDirectorySuffix(profileSuffix)
                else return@withContext result(AiDnsOutcome.LIMITED, AiDnsDetail.JS_UNAVAILABLE)
                profileReady = true
            }
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return@withContext result(AiDnsOutcome.LIMITED, AiDnsDetail.JS_UNAVAILABLE)
            // No direct fallback. Set the route before creating/loading the browser.
            suspendCancellableCoroutine<Unit> { cont ->
                ProxyController.getInstance().setProxyOverride(ProxyConfig.Builder().addProxyRule("socks://127.0.0.1:$port").removeImplicitRules().build(),
                    Executor { it.run() }) { if (cont.isActive) cont.resume(Unit) }
                proxyInstalled = true
            }
            suspendCancellableCoroutine<Unit> { cont -> CookieManager.getInstance().removeAllCookies { if (cont.isActive) cont.resume(Unit) } }
            WebStorage.getInstance().deleteAllData()
            var loaded = false
            var failed = false
            val view = WebView(context).also { browser = it }
            view.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                allowFileAccess = false; allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false); mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
            view.clearCache(true)
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(1600, android.view.View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1080, 1600)
            view.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
                override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean { result.cancel(); return true }
            }
            view.setDownloadListener { _, _, _, _, _ -> }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val allowed = request.url.scheme == "https" && request.url.host in setOf("gemini.google.com", "accounts.google.com", "consent.google.com", "www.google.com")
                    if (request.isForMainFrame && !allowed) failed = true
                    return !allowed
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) { loaded = false }
                override fun onPageFinished(view: WebView?, url: String?) { loaded = true }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError?) { if (request.isForMainFrame) {
                    android.util.Log.w("AiDnsProbe", "Gemini WebView main-frame error ${error?.errorCode}: ${error?.description}")
                    failed = true
                } }
                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest, errorResponse: WebResourceResponse?) { if (request.isForMainFrame) failed = true }
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                    handler.cancel()
                    // This callback also covers fonts/images/other subresources. Their TLS
                    // failures do not establish that the main Gemini page failed to load.
                    if (error?.url == view?.url) failed = true
                    android.util.Log.w("AiDnsProbe", "Gemini WebView TLS error ${error?.primaryError}, mainFrame=${error?.url == view?.url}")
                }
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean { failed = true; return true }
            }
            if (fixture == null) view.loadUrl("https://gemini.google.com/app?hl=en")
            else view.loadDataWithBaseURL("https://gemini.google.com/", fixture, "text/html", "UTF-8", null)
            var previous = ""
            var stable = 0
            repeat(24) { tick ->
                delay(1000)
                if (failed) return@withContext result(AiDnsOutcome.FAILED, AiDnsDetail.JS_FAILED)
                if (!loaded) return@repeat
                val json = suspendCancellableCoroutine<String> { cont ->
                    view.evaluateJavascript("JSON.stringify({text:(document.body?.innerText||'').slice(0,100000),url:location.href})") { if (cont.isActive) cont.resume(it) }
                }
                val decoded = JSONTokener(json).nextValue() as? String ?: return@repeat
                val page = JSONObject(decoded)
                val text = page.optString("text")
                val outcome = classifyAiDnsRendered(text, page.optString("url"))
                if (outcome == AiDnsOutcome.GEO_BLOCKED) return@withContext result(outcome, AiDnsDetail.JS_GEO)
                stable = if (text == previous && text.length > 20) stable + 1 else 0
                previous = text
                if (tick >= 11 && stable >= 4) return@withContext result(outcome, if (outcome == AiDnsOutcome.ACCESSIBLE) AiDnsDetail.JS_LOADED else AiDnsDetail.JS_LIMITED)
            }
            result(AiDnsOutcome.FAILED, AiDnsDetail.JS_TIMEOUT)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            android.util.Log.w("AiDnsProbe", "Gemini WebView: ${e.javaClass.simpleName}: ${e.message}")
            result(AiDnsOutcome.FAILED, AiDnsDetail.JS_FAILED)
        }
        finally {
            browser?.apply { stopLoading(); clearHistory(); clearCache(true); destroy() }
            if (proxyInstalled) {
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                // Remain fail-closed between requests, including a cancelled check.
                runCatching { ProxyController.getInstance().setProxyOverride(ProxyConfig.Builder().addProxyRule("socks://127.0.0.1:1").removeImplicitRules().build(), Executor { it.run() }) {} }
            }
        }
    }
}
