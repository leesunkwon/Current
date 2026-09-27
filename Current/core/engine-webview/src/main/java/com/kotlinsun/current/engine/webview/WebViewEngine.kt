package com.kotlinsun.current.engine.webview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Parcel
import android.os.SystemClock
import android.print.PrintDocumentAdapter
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.webkit.ClientCertRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import android.net.http.SslError
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.ProfileStore
import com.kotlinsun.current.engine.BlobReceiver
import com.kotlinsun.current.engine.BlobTransfer
import com.kotlinsun.current.engine.BLOB_TRANSFER_CANCELLED
import com.kotlinsun.current.engine.BrowserEngine
import com.kotlinsun.current.engine.ClientCertificateRequest
import com.kotlinsun.current.engine.DownloadRequest
import com.kotlinsun.current.engine.EngineCallbacks
import com.kotlinsun.current.engine.EngineSession
import com.kotlinsun.current.engine.EngineState
import com.kotlinsun.current.engine.FileSelectionRequest
import com.kotlinsun.current.engine.FullScreenRequest
import com.kotlinsun.current.engine.HttpAuthenticationRequest
import com.kotlinsun.current.engine.JavaScriptDialogKind
import com.kotlinsun.current.engine.JavaScriptDialogRequest
import com.kotlinsun.current.engine.LinkTarget
import com.kotlinsun.current.engine.NavigationEntry
import com.kotlinsun.current.engine.PageError
import com.kotlinsun.current.engine.PopupRequest
import com.kotlinsun.current.engine.SessionConfig
import com.kotlinsun.current.engine.ReadablePage
import com.kotlinsun.current.engine.ReadableBlock
import com.kotlinsun.current.engine.SiteInfo
import com.kotlinsun.current.engine.WebPermissionKind
import com.kotlinsun.current.engine.WebPermissionRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class WebViewEngine : BrowserEngine {
    override fun createSession(context: Context, id: String, config: SessionConfig,
        callbacks: EngineCallbacks): EngineSession = WebViewSession(context, id, config, callbacks)

    override fun supportsPrivateMode(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    override fun privateProfileNames(prefix: String): List<String> = if (supportsPrivateMode()) {
        ProfileStore.getInstance().allProfileNames.filter { it.startsWith(prefix) }
    } else emptyList()

    override fun deletePrivateProfile(name: String): Boolean =
        supportsPrivateMode() && ProfileStore.getInstance().deleteProfile(name)

    override fun clearDefaultSiteData(context: Context, onComplete: (Boolean) -> Unit) {
        CookieManager.getInstance().removeAllCookies {
            val cleared = runCatching {
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                GeolocationPermissions.getInstance().clearAll()
                WebViewDatabase.getInstance(context).clearHttpAuthUsernamePassword()
                WebViewDatabase.getInstance(context).clearFormData()
                WebView(context).also { temporary ->
                    try { temporary.clearCache(true) } finally { temporary.destroy() }
                }
            }.isSuccess
            onComplete(cleared)
        }
    }
}

private class WebViewSession(
    context: Context,
    override val id: String,
    config: SessionConfig,
    private val callbacks: EngineCallbacks,
) : EngineSession {
    private val appContext = context.applicationContext
    private val mutableState = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = mutableState
    @Volatile private var closed = false
    private var rendererGone = false
    private val webView = WebView(context)
    private val pending = mutableSetOf<Pending>()
    private val permissionRequests = mutableMapOf<PermissionRequest, WebPermissionRequest>()
    private var geoRequest: WebPermissionRequest? = null
    private var activeFullScreen: FullScreenRequest? = null
    private var dialogsOnPage = 0
    private var approvedHttpUrl: String? = null
    private var initialPopupGestureExpiresAt = 0L
    private var activeBlob: BlobTransfer? = null
    @Volatile private var trackingEnabled = config.trackingProtection
    @Volatile private var trackingExceptions = config.trackingExceptions
    @Volatile private var topLevelOrigin: String? = null
    @Volatile private var topLevelHost: String? = null
    private val normalProfile = config.profileName == null
    private val navigationScope = config.navigationScope?.let(Uri::parse)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val blockedCount = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var navigationGeneration = 0
    private val mobileUserAgent = webView.settings.userAgentString.orEmpty()
    override val view get() = webView
    override val userAgent get() = webView.settings.userAgentString.orEmpty()

    private inner class Pending(private val onCancel: () -> Unit) {
        private var finished = false
        init { pending.add(this) }
        fun finish(action: () -> Unit) {
            if (finished) return
            finished = true
            pending.remove(this)
            action()
        }
        fun cancel() = finish(onCancel)
    }

    init {
        config.profileName?.let {
            try {
                check(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
                WebViewCompat.setProfile(webView, it)
            } catch (error: RuntimeException) {
                webView.destroy()
                throw error
            }
            webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            setGeolocationEnabled(true)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
            runCatching {
                WebSettingsCompat.setWebAuthenticationSupport(webView.settings,
                    WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER)
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PAYMENT_REQUEST)) {
            runCatching { WebSettingsCompat.setPaymentRequestEnabled(webView.settings, true) }
        }
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        applySettings(config.allowThirdPartyCookies, config.textZoom)
        setDesktopMode(config.desktopMode)
        webView.setFindListener { activeIndex, total, done ->
            if (!closed && done) callbacks.onFindResult(id, activeIndex, total)
        }
        // Blob transfers use a temporary MessagePort addressed to the active main-frame origin.
        // No JavaScript bridge is injected into unrelated pages or subframes.
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView,
                request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame) return null
                return interceptTracker(request.url, topLevelOrigin ?: return null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (closed) return true
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                val popupGesture = request.isForMainFrame && initialPopupGestureExpiresAt > 0 &&
                    SystemClock.elapsedRealtime() <= initialPopupGestureExpiresAt
                if (request.isForMainFrame && scheme !in setOf("http", "https"))
                    initialPopupGestureExpiresAt = 0
                if (request.isForMainFrame && scheme in setOf("http", "https") &&
                    navigationScope != null && !withinNavigationScope(uri, navigationScope)) {
                    callbacks.onScopeExit(id, uri.toString())
                    return true
                }
                if (scheme == "http" && request.isForMainFrame) {
                    if (approvedHttpUrl == uri.toString()) {
                        approvedHttpUrl = null
                        return false
                    }
                    callbacks.onHttpNavigation(id, uri.toString())
                    return true
                }
                if (scheme == "http" || scheme == "https") return false
                if (!request.isForMainFrame && scheme in setOf("about", "data", "blob")) return false
                if (scheme in setOf("file", "content", "about", "data", "blob", "javascript")) return true
                if (request.isForMainFrame) callbacks.onExternalNavigation(id, uri.toString(),
                    request.hasGesture() || popupGesture)
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (closed) return
                val parsed = Uri.parse(url)
                topLevelHost = parsed.host?.lowercase()
                topLevelOrigin = parsed.scheme?.lowercase()?.let { scheme ->
                    parsed.host?.lowercase()?.let { host ->
                        "$scheme://$host" + if (parsed.port >= 0) ":${parsed.port}" else ""
                    }
                }
                if (normalProfile) TrackerRequestRouter.update(id, topLevelOrigin,
                    trackingEnabled, trackingExceptions)
                navigationGeneration++
                blockedCount.set(0)
                approvedHttpUrl = null
                dialogsOnPage = 0
                callbacks.onNavigationStarted(id)
                update { it.copy(url = url, isLoading = true, progress = 0, error = null,
                    blockedTrackers = 0) }
            }

            override fun onPageFinished(view: WebView, url: String) {
                update { it.copy(url = url, title = view.title.orEmpty(), isLoading = false, progress = 100,
                    canGoBack = view.canGoBack(), canGoForward = view.canGoForward()) }
                if (!closed && mutableState.value.error == null) callbacks.onPageFinished(id)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (!closed && (url.startsWith("https://") || url.startsWith("http://"))) {
                    callbacks.onVisited(id, url, isReload)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    update { it.copy(isLoading = false, error = PageError(error.description.toString(), request.url.toString())) }
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                    update { it.copy(isLoading = false, error = PageError(
                        appContext.getString(R.string.engine_server_error, errorResponse.statusCode),
                        request.url.toString())) }
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                if (error.url == mutableState.value.url || error.url == view.url) {
                    update { it.copy(isLoading = false,
                        error = PageError(appContext.getString(R.string.engine_ssl_error), error.url)) }
                }
            }

            override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) {
                callback.backToSafety(true)
                if (request.isForMainFrame) update { it.copy(isLoading = false,
                    error = PageError(appContext.getString(R.string.engine_unsafe_site), request.url.toString())) }
            }

            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler,
                                                   requestHost: String, requestRealm: String) {
                if (closed) { handler.cancel(); return }
                val completion = Pending { handler.cancel() }
                callbacks.onHttpAuthentication(id, object : HttpAuthenticationRequest {
                    override val host = requestHost
                    override val realm = requestRealm
                    override fun proceed(username: String, password: String) = completion.finish {
                        handler.proceed(username, password)
                    }
                    override fun cancel() = completion.cancel()
                })
            }

            override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
                if (closed) { request.ignore(); return }
                val completion = Pending { request.ignore() }
                callbacks.onClientCertificate(id, object : ClientCertificateRequest {
                    override val host = request.host
                    override val port = request.port
                    override val keyTypes = request.keyTypes ?: emptyArray()
                    override val principals = request.principals
                    override fun proceed(privateKey: java.security.PrivateKey,
                        chain: Array<java.security.cert.X509Certificate>) = completion.finish {
                        request.proceed(privateKey, chain)
                    }
                    override fun cancel() = completion.cancel()
                })
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                rendererGone = true
                runCatching { callbacks.onRendererGone(id, this@WebViewSession) }
                    .onFailure { runCatching { close() } }
                return true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                update { it.copy(progress = newProgress.coerceIn(0, 100), isLoading = newProgress < 100 && it.error == null,
                    canGoBack = view.canGoBack(), canGoForward = view.canGoForward()) }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                update { it.copy(title = title.orEmpty()) }
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap) {
                if (closed) return
                val scaled = Bitmap.createScaledBitmap(icon, 48, 48, true)
                val output = ByteArrayOutputStream()
                val url = view.url
                if (url != null && scaled.compress(Bitmap.CompressFormat.PNG, 100, output) &&
                    output.size() <= 32 * 1024) {
                    callbacks.onFavicon(id, url, output.toByteArray())
                }
                if (scaled !== icon) scaled.recycle()
            }

            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                if (closed) {
                    filePathCallback.onReceiveValue(null)
                    return true
                }
                val completion = Pending { filePathCallback.onReceiveValue(null) }
                val request = object : FileSelectionRequest {
                    override val acceptTypes: Array<String> = fileChooserParams.acceptTypes ?: emptyArray()
                    override val allowMultiple = fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    override val capture = fileChooserParams.isCaptureEnabled
                    override fun complete(uris: Array<Uri>?) {
                        completion.finish { filePathCallback.onReceiveValue(uris) }
                    }
                }
                callbacks.onFileSelection(id, request)
                return true
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean =
                showJavaScriptDialog(JavaScriptDialogKind.ALERT, url, message, null, result)

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean =
                showJavaScriptDialog(JavaScriptDialogKind.CONFIRM, url, message, null, result)

            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean =
                showJavaScriptDialog(JavaScriptDialogKind.PROMPT, url, message, defaultValue, result)

            override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: JsResult): Boolean =
                showJavaScriptDialog(JavaScriptDialogKind.BEFORE_UNLOAD, url, message, null, result)

            override fun onShowCustomView(customView: android.view.View, callback: CustomViewCallback) {
                if (closed) { callback.onCustomViewHidden(); return }
                activeFullScreen?.close()
                val request = object : FullScreenRequest {
                    private var finished = false
                    override val view = customView
                    override fun close() {
                        if (finished) return
                        finished = true
                        if (activeFullScreen === this) activeFullScreen = null
                        callback.onCustomViewHidden()
                        callbacks.onFullScreenClosed(id)
                    }
                }
                activeFullScreen = request
                callbacks.onFullScreen(id, request)
            }

            override fun onHideCustomView() {
                activeFullScreen?.close()
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                if (closed) { request.deny(); return }
                val requestedKinds = request.resources.mapNotNull {
                    when (it) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> WebPermissionKind.CAMERA
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> WebPermissionKind.MICROPHONE
                        PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> WebPermissionKind.PROTECTED_MEDIA
                        else -> null
                    }
                }.toSet()
                if (requestedKinds.isEmpty() || requestedKinds.size != request.resources.size) { request.deny(); return }
                val completion = Pending { request.deny() }
                val wrapped = object : WebPermissionRequest {
                    override val origin = request.origin.toString()
                    override val kinds = requestedKinds
                    override fun grant() = completion.finish {
                        permissionRequests.remove(request)
                        request.grant(request.resources)
                    }
                    override fun deny() {
                        permissionRequests.remove(request)
                        completion.cancel()
                    }
                }
                permissionRequests[request] = wrapped
                callbacks.onPermission(id, wrapped)
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                permissionRequests.remove(request)?.let {
                    callbacks.onPermissionCanceled(id, it)
                    it.deny()
                }
            }

            override fun onGeolocationPermissionsShowPrompt(requestedOrigin: String, callback: GeolocationPermissions.Callback) {
                if (closed) { callback.invoke(requestedOrigin, false, false); return }
                geoRequest?.let { callbacks.onPermissionCanceled(id, it); it.deny() }
                val completion = Pending { callback.invoke(requestedOrigin, false, false) }
                val wrapped = object : WebPermissionRequest {
                    override val origin = requestedOrigin
                    override val kinds = setOf(WebPermissionKind.LOCATION)
                    override fun grant() = completion.finish {
                        geoRequest = null
                        callback.invoke(requestedOrigin, true, false)
                    }
                    override fun deny() {
                        geoRequest = null
                        completion.cancel()
                    }
                }
                geoRequest = wrapped
                callbacks.onPermission(id, wrapped)
            }

            override fun onGeolocationPermissionsHidePrompt() {
                geoRequest?.let { callbacks.onPermissionCanceled(id, it); it.deny() }
                geoRequest = null
            }

            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (closed) return false
                if (!isUserGesture || resultMsg.obj !is WebView.WebViewTransport) {
                    callbacks.onPopupBlocked(id)
                    return false
                }
                val request = object : PopupRequest {
                    override fun accept(session: EngineSession) {
                        val child = session as? WebViewSession
                            ?: throw IllegalArgumentException("Popup requires a WebView session")
                        val transport = resultMsg.obj as? WebView.WebViewTransport
                            ?: throw IllegalStateException("Popup transport is unavailable")
                        if (child.closed) throw IllegalStateException("Popup session is closed")
                        child.initialPopupGestureExpiresAt = SystemClock.elapsedRealtime() + 5_000
                        transport.webView = child.webView
                        resultMsg.sendToTarget()
                    }
                }
                val accepted = callbacks.onPopupRequested(id, request)
                if (!accepted) callbacks.onPopupBlocked(id)
                return accepted
            }

            override fun onCloseWindow(window: WebView) {
                if (!closed && window === webView) callbacks.onCloseRequested(id)
            }
        }
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, length ->
            if (!closed) callbacks.onDownload(id, DownloadRequest(url, userAgent, contentDisposition, mimeType, length))
        }
        webView.setOnLongClickListener {
            val result = webView.hitTestResult
            when (result.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val handler = Handler(Looper.getMainLooper()) { message ->
                        val href = message.data.getString("url")
                        val src = message.data.getString("src")
                        if (!closed && href != null && (href.startsWith("https://") || href.startsWith("http://"))) {
                            callbacks.onLinkLongPress(id, LinkTarget(href, src?.takeIf {
                                it.startsWith("https://") || it.startsWith("http://")
                            }))
                        }
                        true
                    }
                    webView.requestFocusNodeHref(handler.obtainMessage())
                    true
                }
                WebView.HitTestResult.IMAGE_TYPE -> {
                    val url = result.extra?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    if (url == null) false else {
                        callbacks.onLinkLongPress(id, LinkTarget(url, url))
                        true
                    }
                }
                else -> false
            }
        }
        val pullDistance = 96f * context.resources.displayMetrics.density
        var downX = 0f
        var downY = 0f
        var pullCandidate = false
        webView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    pullCandidate = view.scrollY == 0 && !closed
                }
                MotionEvent.ACTION_UP -> {
                    val dx = kotlin.math.abs(event.x - downX)
                    val dy = event.y - downY
                    if (pullCandidate && view.scrollY == 0 && dy > pullDistance &&
                        dx < dy / 2f) callbacks.onPullToRefresh(id)
                    pullCandidate = false
                }
                MotionEvent.ACTION_CANCEL -> pullCandidate = false
            }
            false
        }
        if (normalProfile) TrackerRequestRouter.register(id) { count ->
            update { it.copy(blockedServiceWorkers = count) }
        }
    }

    private fun showJavaScriptDialog(
        dialogKind: JavaScriptDialogKind,
        url: String,
        dialogMessage: String,
        initialValue: String?,
        result: JsResult,
    ): Boolean {
        if (closed || dialogsOnPage >= 3) { result.cancel(); return true }
        dialogsOnPage++
        val completion = Pending { result.cancel() }
        callbacks.onJavaScriptDialog(id, object : JavaScriptDialogRequest {
            override val kind = dialogKind
            override val origin = runCatching {
                Uri.parse(url).let { uri ->
                    if (uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank())
                        uri.scheme + "://" + uri.host else appContext.getString(R.string.engine_web_page)
                }
            }.getOrDefault(appContext.getString(R.string.engine_web_page))
            override val message = dialogMessage
            override val defaultValue = initialValue
            override fun confirm(value: String?) = completion.finish {
                if (result is JsPromptResult) result.confirm(value.orEmpty()) else result.confirm()
            }
            override fun cancel() = completion.cancel()
        })
        return true
    }

    private inline fun update(block: (EngineState) -> EngineState) {
        if (!closed) mutableState.value = block(mutableState.value)
    }

    private fun withinNavigationScope(url: Uri, scope: Uri): Boolean {
        if (url.scheme != "https" || scope.scheme != "https" ||
            url.host.isNullOrBlank() || scope.host.isNullOrBlank() ||
            url.userInfo != null || scope.userInfo != null ||
            !url.host.equals(scope.host, true) ||
            (if (url.port < 0) 443 else url.port) !=
            (if (scope.port < 0) 443 else scope.port)) return false
        return url.encodedPath.orEmpty().startsWith(scope.encodedPath.orEmpty())
    }

    fun interceptTracker(resource: Uri, pageOrigin: String): WebResourceResponse? {
        if (closed || !trackingEnabled || pageOrigin != topLevelOrigin ||
            pageOrigin in trackingExceptions) return null
        val pageHost = topLevelHost ?: return null
        val resourceHost = resource.host?.lowercase() ?: return null
        if (resource.scheme !in setOf("http", "https") ||
            !TrackerRules.shouldBlock(pageHost, resourceHost)) return null
        val generation = navigationGeneration
        val count = blockedCount.incrementAndGet()
        mainHandler.post {
            if (pageOrigin == topLevelOrigin && generation == navigationGeneration)
                update { it.copy(blockedTrackers = maxOf(it.blockedTrackers, count)) }
        }
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    private inner class WebBlobTransfer(
        private val token: String,
        private val source: Uri,
        private val limit: Long,
        private val receiver: BlobReceiver,
    ) : BlobTransfer {
        private var finished = false
        private var expectedSize = -1L
        private var received = 0L
        private var nextSequence = 0
        private var awaitingChunk = false
        private var appPort: WebMessagePortCompat? = null
        private var untransferredPort: WebMessagePortCompat? = null
        private val timeout = Runnable { fail(appContext.getString(R.string.engine_blob_timeout)) }
        private val handler = Handler(Looper.getMainLooper())

        init { handler.postDelayed(timeout, 120_000) }

        fun bindPorts(app: WebMessagePortCompat, outgoing: WebMessagePortCompat) {
            appPort = app
            untransferredPort = outgoing
        }

        fun transferred() { untransferredPort = null }

        private fun closePorts() {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_CLOSE)) {
                runCatching { appPort?.close() }
                runCatching { untransferredPort?.close() }
            }
            appPort = null
            untransferredPort = null
        }

        fun receive(data: String?, origin: Uri, mainFrame: Boolean, reply: (Int, Boolean) -> Unit) {
            val page = Uri.parse(webView.url.orEmpty())
            if (finished || closed || !mainFrame || origin.scheme != source.scheme ||
                origin.host != source.host || origin.port != source.port) return
            if (page.scheme != source.scheme || page.host != source.host || page.port != source.port) {
                fail(appContext.getString(R.string.engine_blob_page_changed))
                return
            }
            if (data == null || data.length > 90_000) {
                fail(appContext.getString(R.string.engine_blob_data_error))
                return
            }
            val message = runCatching { JSONObject(data) }.getOrNull() ?: return
            if (message.optString("token") != token) return
            val sequence = message.optInt("seq", -2)
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, 120_000)
            when (message.optString("type")) {
                "start" -> {
                    val size = message.optLong("size", -1)
                    if (sequence != -1 || expectedSize >= 0 || size < 0 || size > limit) {
                        reply(sequence, false)
                        fail(appContext.getString(R.string.engine_blob_size_error))
                    } else {
                        expectedSize = size
                        reply(sequence, true)
                    }
                }
                "chunk" -> {
                    val bytes = runCatching { Base64.decode(message.getString("data"), Base64.DEFAULT) }.getOrNull()
                    if (expectedSize < 0 || awaitingChunk || sequence != nextSequence || bytes == null ||
                        bytes.size > 48 * 1024 || received + bytes.size > expectedSize) {
                        reply(sequence, false)
                        fail(appContext.getString(R.string.engine_blob_data_error))
                    } else {
                        awaitingChunk = true
                        received += bytes.size
                        nextSequence++
                        runCatching { receiver.onChunk(bytes) { success ->
                            handler.post {
                                if (finished) return@post
                                awaitingChunk = false
                                reply(sequence, success && !finished)
                                if (!success) fail(appContext.getString(R.string.engine_blob_save_error))
                            }
                        } }.onFailure {
                            reply(sequence, false)
                            fail(appContext.getString(R.string.engine_blob_save_error))
                        }
                    }
                }
                "complete" -> {
                    if (awaitingChunk || sequence != nextSequence || received != expectedSize) {
                        reply(sequence, false)
                        fail(appContext.getString(R.string.engine_blob_incomplete))
                    } else {
                        finished = true
                        activeBlob = null
                        handler.removeCallbacks(timeout)
                        reply(sequence, true)
                        try {
                            receiver.onComplete(message.optString("mime").takeIf { it.isNotBlank() })
                        } finally { closePorts() }
                    }
                }
                "error" -> {
                    reply(sequence, false)
                    fail(if (message.optString("reason") == "size")
                        appContext.getString(R.string.engine_blob_20mib_limit)
                    else appContext.getString(R.string.engine_blob_page_read_error))
                }
            }
        }

        private fun fail(message: String) {
            if (finished) return
            finished = true
            handler.removeCallbacks(timeout)
            if (activeBlob === this) activeBlob = null
            try { receiver.onError(message) } finally { closePorts() }
        }

        fun abort(message: String) = fail(message)

        override fun cancel() = fail(BLOB_TRANSFER_CANCELLED)
    }

    override fun blobUnavailableReason(url: String): String? {
        if (closed) return appContext.getString(R.string.engine_blob_tab_closed)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.CREATE_WEB_MESSAGE_CHANNEL) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.POST_WEB_MESSAGE) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_POST_MESSAGE) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_CALLBACK_ON_MESSAGE) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_CLOSE))
            return appContext.getString(R.string.engine_blob_unsupported)
        val page = Uri.parse(webView.url.orEmpty())
        if (page.scheme != "https" || page.host.isNullOrBlank())
            return appContext.getString(R.string.engine_blob_https_only)
        val origin = mainFrameOrigin(page)
        if (!url.startsWith("blob:$origin/")) return appContext.getString(R.string.engine_blob_other_origin)
        return null
    }

    private fun mainFrameOrigin(page: Uri): String {
        val host = page.host.orEmpty().let { if (':' in it && !it.startsWith("[")) "[$it]" else it }
        return "${page.scheme}://$host${if (page.port >= 0) ":${page.port}" else ""}"
    }

    override fun downloadBlob(url: String, maxBytes: Long, receiver: BlobReceiver): BlobTransfer? {
        if (blobUnavailableReason(url) != null) return null
        val page = Uri.parse(webView.url.orEmpty())
        activeBlob?.cancel()
        val token = UUID.randomUUID().toString()
        val transfer = WebBlobTransfer(token, page, maxBytes, receiver)
        activeBlob = transfer
        val ports = try { WebViewCompat.createWebMessageChannel(webView) } catch (error: RuntimeException) {
            transfer.cancel()
            throw error
        }
        transfer.bindPorts(ports[0], ports[1])
        try {
            ports[0].setWebMessageCallback(object : WebMessagePortCompat.WebMessageCallbackCompat() {
                override fun onMessage(port: WebMessagePortCompat, message: WebMessageCompat?) {
                    if (activeBlob !== transfer) return
                    transfer.receive(message?.data, page, true) { sequence, ok ->
                        runCatching { port.postMessage(WebMessageCompat("$sequence:${if (ok) "ok" else "stop"}")) }
                    }
                }
            })
        } catch (error: RuntimeException) {
            transfer.cancel()
            throw error
        }
        val script = """(()=>{
            const token=${JSONObject.quote(token)}, url=${JSONObject.quote(url)};
            const setup=(event)=>{
                if(event.data!==token||!event.ports||!event.ports[0])return;
                clearTimeout(waiting);
                window.removeEventListener('message',setup);
                const port=event.ports[0];port.start();
                (async()=>{
            const send=(payload)=>new Promise((resolve)=>{
                const sequence=payload.seq;
                const timeout=setTimeout(()=>{port.removeEventListener('message',receive);resolve(false);},120000);
                const receive=(event)=>{if(event.data===sequence+':ok'||event.data===sequence+':stop'){
                    clearTimeout(timeout);port.removeEventListener('message',receive);
                    resolve(event.data.endsWith(':ok'));}};
                port.addEventListener('message',receive);
                try{port.postMessage(JSON.stringify({...payload,token}));}
                catch(_){clearTimeout(timeout);port.removeEventListener('message',receive);resolve(false);}
            });
            try {
                const response=await fetch(url), blob=await response.blob();
                if(blob.size>$maxBytes){await send({type:'error',seq:-1,reason:'size'});return;}
                if(!await send({type:'start',seq:-1,size:blob.size,mime:blob.type}))return;
                let sequence=0;
                for(let offset=0;offset<blob.size;offset+=49152){
                    const array=new Uint8Array(await blob.slice(offset,offset+49152).arrayBuffer());
                    let binary='';for(const byte of array)binary+=String.fromCharCode(byte);
                    if(!await send({type:'chunk',seq:sequence,data:btoa(binary)}))return;
                    sequence++;
                }
                await send({type:'complete',seq:sequence,mime:blob.type});
            } catch (_) {await send({type:'error',seq:-1});}
                })().finally(()=>port.close());
            };
            const waiting=setTimeout(()=>window.removeEventListener('message',setup),120000);
            window.addEventListener('message',setup);
            return 'ready';
        })();""".trimIndent()
        try {
            webView.evaluateJavascript(script) { result ->
                if (activeBlob !== transfer) return@evaluateJavascript
                val current = Uri.parse(webView.url.orEmpty())
                if (result != "\"ready\"" || current.scheme != page.scheme ||
                    current.host != page.host || current.port != page.port) {
                    transfer.abort(appContext.getString(R.string.engine_blob_page_changed))
                    return@evaluateJavascript
                }
                try {
                    val origin = Uri.parse(mainFrameOrigin(page))
                    WebViewCompat.postWebMessage(webView,
                        WebMessageCompat(token, arrayOf(ports[1])), origin)
                    transfer.transferred()
                } catch (_: RuntimeException) {
                    transfer.abort(appContext.getString(R.string.engine_blob_page_read_error))
                }
            }
        } catch (error: RuntimeException) {
            transfer.cancel()
            throw error
        }
        return transfer
    }

    override fun allowHttpOnce(url: String) { approvedHttpUrl = url }

    override fun applySettings(allowThirdPartyCookies: Boolean, textZoom: Int) {
        if (closed) return
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, allowThirdPartyCookies)
        webView.settings.textZoom = textZoom.coerceIn(75, 300)
    }

    override fun setDesktopMode(enabled: Boolean) {
        if (closed) return
        webView.settings.apply {
            userAgentString = if (enabled) mobileUserAgent.replace("; wv", "")
                .replace(" Mobile", "") else mobileUserAgent
            useWideViewPort = enabled
            loadWithOverviewMode = enabled
        }
    }

    override fun find(text: String) {
        if (!closed) {
            webView.clearMatches()
            if (text.isNotBlank()) webView.findAllAsync(text)
            else callbacks.onFindResult(id, 0, 0)
        }
    }

    override fun findNext(forward: Boolean) { if (!closed) webView.findNext(forward) }
    override fun clearFind() { if (!closed) webView.clearMatches() }

    override fun createPrintAdapter(jobName: String): PrintDocumentAdapter? =
        if (closed) null else webView.createPrintDocumentAdapter(jobName)

    override fun load(url: String) {
        if (!closed) webView.loadUrl(url)
    }
    override fun backUrl(): String? = if (closed) null else webView.copyBackForwardList().let {
        it.getItemAtIndex(it.currentIndex - 1)?.url
    }
    override fun backHistory(): List<NavigationEntry> {
        if (closed) return emptyList()
        val history = webView.copyBackForwardList()
        return ((history.currentIndex - 1) downTo 0).take(20).mapNotNull { index ->
            history.getItemAtIndex(index)?.let { item ->
                item.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                    ?.let { NavigationEntry(item.title.orEmpty().ifBlank { it }, it,
                        index - history.currentIndex) }
            }
        }
    }
    override fun goBackOrForward(offset: Int) {
        if (!closed && offset < 0 && webView.canGoBackOrForward(offset))
            webView.goBackOrForward(offset)
    }
    override fun forwardUrl(): String? = if (closed) null else webView.copyBackForwardList().let {
        it.getItemAtIndex(it.currentIndex + 1)?.url
    }
    override fun goBack() { if (!closed && webView.canGoBack()) webView.goBack() }
    override fun goForward() { if (!closed && webView.canGoForward()) webView.goForward() }
    override fun reload() { if (!closed) webView.reload() }
    override fun stop() {
        if (!closed) {
            webView.stopLoading()
            update { it.copy(isLoading = false) }
        }
    }
    override fun pause() { if (!closed) webView.onPause() }
    override fun resume() { if (!closed) webView.onResume() }
    override fun pauseTimers() { if (!closed) webView.pauseTimers() }
    override fun resumeTimers() { if (!closed) webView.resumeTimers() }
    override fun applyTrackingProtection(enabled: Boolean, exceptions: Set<String>) {
        trackingExceptions = exceptions.toSet()
        trackingEnabled = enabled
        if (normalProfile) TrackerRequestRouter.update(id, topLevelOrigin,
            trackingEnabled, trackingExceptions)
    }

    override fun saveState(maxBytes: Int): ByteArray? {
        if (closed || !WebViewFeature.isFeatureSupported(WebViewFeature.SAVE_STATE)) return null
        return runCatching {
            val bundle = Bundle()
            WebViewCompat.saveState(webView, bundle, maxBytes, true)
            val parcel = Parcel.obtain()
            try {
                bundle.writeToParcel(parcel, 0)
                parcel.marshall().takeIf { it.size <= maxBytes }
            } finally {
                parcel.recycle()
            }
        }.getOrNull()
    }

    override fun restoreState(bytes: ByteArray, expectedUrl: String?): Boolean {
        if (closed) return false
        return runCatching {
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                val bundle = Bundle.CREATOR.createFromParcel(parcel)
                bundle.classLoader = WebView::class.java.classLoader
                val history = webView.restoreState(bundle)
                val restoredUrl = history?.currentItem?.url
                if (history == null || (expectedUrl != null && restoredUrl != expectedUrl)) {
                    webView.clearHistory()
                    false
                } else {
                    update { it.copy(url = restoredUrl, title = webView.title.orEmpty(),
                        canGoBack = webView.canGoBack(), canGoForward = webView.canGoForward()) }
                    true
                }
            } finally {
                parcel.recycle()
            }
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    override fun capturePreview(): ByteArray? {
        if (closed || webView.width <= 0 || webView.height <= 0) return null
        return runCatching {
            val width = 360
            val height = (webView.height.toFloat() * width / webView.width).toInt().coerceIn(1, 640)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.scale(width.toFloat() / webView.width, height.toFloat() / webView.height)
                webView.draw(canvas)
                val output = ByteArrayOutputStream()
                if (bitmap.compress(Bitmap.CompressFormat.WEBP, 65, output) && output.size() <= 128 * 1024) {
                    output.toByteArray()
                } else null
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }

    override fun siteInfo(): SiteInfo {
        val currentUrl = mutableState.value.url
        val certificate = if (closed || mutableState.value.error != null ||
            !currentUrl.orEmpty().startsWith("https://") || currentUrl != webView.url) null
        else webView.certificate
        return SiteInfo(
            currentUrl,
            certificate?.issuedTo?.dName,
            certificate?.issuedBy?.dName,
            certificate?.validNotBeforeDate?.time,
            certificate?.validNotAfterDate?.time,
        )
    }

    override fun extractReadablePage(callback: (ReadablePage?) -> Unit) {
        val expected = mutableState.value.url
        if (closed || !expected.orEmpty().startsWith("https://") &&
            !expected.orEmpty().startsWith("http://")) { callback(null); return }
        val script = """(() => {
            const root = document.querySelector('article') || document.querySelector('main') || document.body;
            if (!root) return null;
            const copy = root.cloneNode(true);
            copy.querySelectorAll('script,style,nav,aside,footer,header,form,button,svg').forEach(e => e.remove());
            let total = 0;
            const blocks = [];
            copy.querySelectorAll('h1,h2,h3,h4,h5,h6,p,li,blockquote').forEach(e => {
                if (blocks.length >= 500 || total >= 100000) return;
                const text = (e.innerText || e.textContent || '').replace(/\s+/g,' ').trim();
                if (!text || text.length < 3) return;
                const limited = text.slice(0, 100000 - total);
                blocks.push({text:limited,heading:/^H[1-6]$/.test(e.tagName)});
                total += limited.length;
            });
            const text = blocks.length ? blocks.map(b => b.text).join('\n\n') :
                (copy.innerText || copy.textContent || '').replace(/[ \t]+/g,' ').trim();
            if (text.length < 80) return null;
            return JSON.stringify({url:location.href,title:document.title,
                text:blocks.length ? '' : text.slice(0,100000),
                blocks:blocks,language:document.documentElement.lang || ''});
        })()""".trimIndent()
        webView.evaluateJavascript(script) { raw ->
            val page = runCatching {
                if (raw.length > 1000000 || raw == "null") return@runCatching null
                val value = JSONArray("[$raw]").getString(0)
                val json = JSONObject(value)
                val blocks = json.optJSONArray("blocks")?.let { entries ->
                    (0 until entries.length()).mapNotNull { index ->
                        entries.optJSONObject(index)?.let {
                            ReadableBlock(it.optString("text"), it.optBoolean("heading"))
                        }
                    }
                }.orEmpty()
                ReadablePage(json.getString("url"), json.optString("title"),
                    if (blocks.isEmpty()) json.getString("text") else
                        blocks.joinToString("\n\n") { it.text }, blocks,
                    json.optString("language").take(40).ifBlank { null })
            }.getOrNull()
            callback(page?.takeIf { !closed && it.url == expected &&
                mutableState.value.url == expected })
        }
    }

    override fun manifestLink(callback: (String?) -> Unit) {
        val expected = mutableState.value.url
        if (closed || !expected.orEmpty().startsWith("https://")) { callback(null); return }
        webView.evaluateJavascript("document.querySelector('link[rel~=manifest]')?.href || ''") { raw ->
            val link = runCatching { JSONArray("[$raw]").getString(0) }.getOrNull()
            callback(link?.takeIf { !closed && mutableState.value.url == expected &&
                it.startsWith("https://") })
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (normalProfile) TrackerRequestRouter.unregister(id)
        activeBlob?.cancel()
        runCatching { activeFullScreen?.close() }
        pending.toList().forEach { runCatching { it.cancel() } }
        permissionRequests.clear()
        geoRequest = null
        runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        if (!rendererGone) {
            runCatching { webView.stopLoading() }
            runCatching { webView.webChromeClient = null }
            runCatching { webView.webViewClient = WebViewClient() }
        }
        runCatching { webView.destroy() }
    }
}
