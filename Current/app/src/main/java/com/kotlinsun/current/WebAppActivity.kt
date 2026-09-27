package com.kotlinsun.current

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kotlinsun.current.browser.PwaSupport
import com.kotlinsun.current.data.BrowserStore
import com.kotlinsun.current.engine.webview.TrackerRules
import com.kotlinsun.current.ui.theme.CurrentTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/** A separate, addressless WebView surface for verified web app scopes. */
class WebAppActivity : ComponentActivity() {
    companion object {
        const val EXTRA_START = "pwa_start"
        const val EXTRA_SCOPE = "pwa_scope"
        const val EXTRA_TITLE = "pwa_title"
    }

    private var webView: WebView? = null
    private var generation by mutableIntStateOf(0)
    private var title by mutableStateOf("")
    private var progress by mutableIntStateOf(0)
    private var error by mutableStateOf<String?>(null)
    private var settingsReady by mutableStateOf(false)
    private var themeChoice by mutableStateOf("SYSTEM")
    private var textZoom = 100
    private var thirdPartyCookies = false
    @Volatile private var trackingEnabled = false
    @Volatile private var trackingExceptions = emptySet<String>()
    private var blockedCount by mutableIntStateOf(0)
    @Volatile private var pageGeneration = 0
    private val main = Handler(Looper.getMainLooper())
    private lateinit var scopeUrl: String
    @Volatile private lateinit var currentUrl: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra(EXTRA_START)
        val scope = intent.getStringExtra(EXTRA_SCOPE)
        if (start == null || scope == null || !PwaSupport.withinScope(start, scope)) {
            finish()
            return
        }
        scopeUrl = scope
        currentUrl = savedInstanceState?.getString("current_url")
            ?.takeIf { PwaSupport.withinScope(it, scope) } ?: start
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        lifecycleScope.launch {
            runCatching { BrowserStore.get(this@WebAppActivity).loadPreferences() }.getOrNull()?.let {
                themeChoice = it.theme
                textZoom = it.textZoom
                thirdPartyCookies = it.thirdPartyCookies
                trackingEnabled = it.trackingProtection
                trackingExceptions = it.trackingExceptions
            }
            settingsReady = true
        }
        setContent {
            CurrentTheme(darkTheme = when (themeChoice) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemInDarkTheme()
            }) {
                BackHandler { if (webView?.canGoBack() == true) webView?.goBack() else finish() }
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { if (webView?.canGoBack() == true) webView?.goBack() else finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = getString(R.string.back))
                        }
                        Text(title, Modifier.weight(1f).padding(top = 12.dp), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        if (blockedCount > 0) Text(blockedCount.toString(),
                            Modifier.padding(top = 14.dp), style = MaterialTheme.typography.labelSmall)
                        IconButton(onClick = { webView?.reload() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = getString(R.string.reload))
                        }
                        IconButton(onClick = ::finish) {
                            Icon(Icons.Filled.Close, contentDescription = getString(R.string.close))
                        }
                    }
                    if (progress in 1..99) LinearProgressIndicator(
                        progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    error?.let { Text(it, Modifier.padding(20.dp)) }
                    if (settingsReady) key(generation) {
                        AndroidView(factory = { createWebView() }, modifier = Modifier.weight(1f).fillMaxWidth())
                    }
                }
            }
        }
    }

    private fun createWebView(): WebView = WebView(this).also { view ->
        webView = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            textZoom = (this@WebAppActivity.textZoom * resources.configuration.fontScale).toInt()
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, thirdPartyCookies)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION))
            runCatching { WebSettingsCompat.setWebAuthenticationSupport(view.settings,
                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER) }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PAYMENT_REQUEST))
            runCatching { WebSettingsCompat.setPaymentRequestEnabled(view.settings, true) }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView,
                request: WebResourceRequest): WebResourceResponse? {
                if (!trackingEnabled || request.isForMainFrame) return null
                val page = Uri.parse(currentUrl)
                val host = page.host?.lowercase() ?: return null
                val origin = "${page.scheme}://$host" + if (page.port >= 0) ":${page.port}" else ""
                val resource = request.url
                if (origin in trackingExceptions || resource.scheme !in setOf("http", "https") ||
                    !TrackerRules.shouldBlock(host, resource.host?.lowercase() ?: return null)) return null
                val generation = pageGeneration
                main.post {
                    val current = Uri.parse(currentUrl)
                    val currentOrigin = "${current.scheme}://${current.host?.lowercase()}" +
                        if (current.port >= 0) ":${current.port}" else ""
                    if (currentOrigin == origin && pageGeneration == generation) blockedCount++
                }
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val url = request.url.toString()
                if (PwaSupport.withinScope(url, scopeUrl)) return false
                if (url.startsWith("https://") || url.startsWith("http://"))
                    startActivity(Intent(this@WebAppActivity, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW).setData(request.url))
                return true
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!PwaSupport.withinScope(url, scopeUrl)) {
                    view.stopLoading()
                    if (url.startsWith("https://") || url.startsWith("http://"))
                        startActivity(Intent(this@WebAppActivity, MainActivity::class.java)
                            .setAction(Intent.ACTION_VIEW).setData(Uri.parse(url)))
                    return
                }
                currentUrl = url
                pageGeneration++
                blockedCount = 0
                error = null
            }
            override fun onPageFinished(view: WebView, url: String) {
                title = view.title?.takeIf { it.isNotBlank() } ?: title
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, sslError: SslError) {
                handler.cancel()
                error = getString(R.string.pwa_ssl_error)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, resourceError: WebResourceError) {
                if (request.isForMainFrame) error = resourceError.description.toString()
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                if (webView === view) webView = null
                error = getString(R.string.pwa_renderer_error)
                generation++
                return true
            }
        }
        view.loadUrl(currentUrl)
    }

    override fun onPause() { super.onPause(); webView?.onPause() }
    override fun onResume() { super.onResume(); webView?.onResume() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("current_url", currentUrl)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
