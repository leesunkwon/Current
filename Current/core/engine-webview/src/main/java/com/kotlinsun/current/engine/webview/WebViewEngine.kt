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
import android.print.PrintDocumentAdapter
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
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
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat.WebMessageListener
import com.kotlinsun.current.engine.BlobReceiver
import com.kotlinsun.current.engine.BlobTransfer
import com.kotlinsun.current.engine.BLOB_TRANSFER_CANCELLED
import com.kotlinsun.current.engine.BrowserEngine
import com.kotlinsun.current.engine.DownloadRequest
import com.kotlinsun.current.engine.EngineCallbacks
import com.kotlinsun.current.engine.EngineSession
import com.kotlinsun.current.engine.EngineState
import com.kotlinsun.current.engine.FileSelectionRequest
import com.kotlinsun.current.engine.FullScreenRequest
import com.kotlinsun.current.engine.JavaScriptDialogKind
import com.kotlinsun.current.engine.JavaScriptDialogRequest
import com.kotlinsun.current.engine.LinkTarget
import com.kotlinsun.current.engine.PageError
import com.kotlinsun.current.engine.PopupRequest
import com.kotlinsun.current.engine.SessionConfig
import com.kotlinsun.current.engine.SiteInfo
import com.kotlinsun.current.engine.WebPermissionKind
import com.kotlinsun.current.engine.WebPermissionRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.json.JSONObject

class WebViewEngine : BrowserEngine {
    override fun createSession(context: Context, id: String, config: SessionConfig, callbacks: EngineCallbacks): EngineSession =
        WebViewSession(context, id, config, callbacks)

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
    private var closed = false
    private var rendererGone = false
    private val webView = WebView(context)
    private val pending = mutableSetOf<Pending>()
    private val permissionRequests = mutableMapOf<PermissionRequest, WebPermissionRequest>()
    private var geoRequest: WebPermissionRequest? = null
    private var activeFullScreen: FullScreenRequest? = null
    private var dialogsOnPage = 0
    private var approvedHttpUrl: String? = null
    private var activeBlob: BlobTransfer? = null
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
        applySettings(config.allowThirdPartyCookies, config.textZoom)
        setDesktopMode(config.desktopMode)
        webView.setFindListener { activeIndex, total, done ->
            if (!closed && done) callbacks.onFindResult(id, activeIndex, total)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // A browser cannot know the next site's origin before navigation. The bridge accepts
            // data only for an app-started HTTPS transfer from its current main frame and token.
            WebViewCompat.addWebMessageListener(webView, "CurrentBlobBridge", setOf("*"),
                WebMessageListener { _, message, origin, mainFrame, reply ->
                    if (mainFrame && origin.scheme == "https") {
                        val data = runCatching { message.data }.getOrNull()
                        (activeBlob as? WebBlobTransfer)?.receive(data, origin, mainFrame) { sequence, ok ->
                            runCatching { reply.postMessage("$sequence:${if (ok) "ok" else "stop"}") }
                        }
                    }
                })
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (closed) return true
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
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
                if (request.isForMainFrame) callbacks.onExternalNavigation(id, uri.toString(), request.hasGesture())
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (closed) return
                approvedHttpUrl = null
                dialogsOnPage = 0
                callbacks.onNavigationStarted(id)
                update { it.copy(url = url, isLoading = true, progress = 0, error = null) }
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
                if (closed || !isUserGesture || resultMsg.obj !is WebView.WebViewTransport) return false
                val request = object : PopupRequest {
                    override fun accept(session: EngineSession) {
                        val child = session as? WebViewSession ?: return
                        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return
                        transport.webView = child.webView
                        resultMsg.sendToTarget()
                    }
                }
                return callbacks.onPopupRequested(id, request)
            }

            override fun onCloseWindow(window: WebView) {
                if (!closed) callbacks.onCloseRequested(id)
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
        private val timeout = Runnable { fail(appContext.getString(R.string.engine_blob_timeout)) }
        private val handler = Handler(Looper.getMainLooper())

        init { handler.postDelayed(timeout, 120_000) }

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
                        receiver.onChunk(bytes) { success ->
                            Handler(Looper.getMainLooper()).post {
                                awaitingChunk = false
                                reply(sequence, success && !finished)
                                if (!success) fail(appContext.getString(R.string.engine_blob_save_error))
                            }
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
                        receiver.onComplete(message.optString("mime").takeIf { it.isNotBlank() })
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
            receiver.onError(message)
        }

        override fun cancel() = fail(BLOB_TRANSFER_CANCELLED)
    }

    override fun blobUnavailableReason(url: String): String? {
        if (closed) return appContext.getString(R.string.engine_blob_tab_closed)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            return appContext.getString(R.string.engine_blob_unsupported)
        val page = Uri.parse(webView.url.orEmpty())
        if (page.scheme != "https" || page.host.isNullOrBlank())
            return appContext.getString(R.string.engine_blob_https_only)
        val origin = "${page.scheme}://${page.host}${if (page.port >= 0) ":${page.port}" else ""}"
        if (!url.startsWith("blob:$origin/")) return appContext.getString(R.string.engine_blob_other_origin)
        return null
    }

    override fun downloadBlob(url: String, maxBytes: Long, receiver: BlobReceiver): BlobTransfer? {
        if (blobUnavailableReason(url) != null) return null
        val page = Uri.parse(webView.url.orEmpty())
        activeBlob?.cancel()
        val token = UUID.randomUUID().toString()
        val transfer = WebBlobTransfer(token, page, maxBytes, receiver)
        activeBlob = transfer
        val script = """(async()=>{
            const bridge=window.CurrentBlobBridge, token=${JSONObject.quote(token)}, url=${JSONObject.quote(url)};
            if(!bridge)return;
            const send=(payload)=>new Promise((resolve)=>{
                const sequence=payload.seq;
                const receive=(event)=>{if(event.data===sequence+':ok'||event.data===sequence+':stop'){
                    bridge.removeEventListener('message',receive);resolve(event.data.endsWith(':ok'));}};
                bridge.addEventListener('message',receive);
                bridge.postMessage(JSON.stringify({...payload,token}));
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
        })();""".trimIndent()
        try {
            webView.evaluateJavascript(script, null)
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

    override fun close() {
        if (closed) return
        closed = true
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
