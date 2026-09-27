package com.kotlinsun.current.engine

import android.content.Context
import android.net.Uri
import android.print.PrintDocumentAdapter
import android.view.View
import kotlinx.coroutines.flow.StateFlow

enum class TabMode { NORMAL, PRIVATE }

const val BLOB_TRANSFER_CANCELLED = "blob_cancelled"

data class SessionConfig(
    val profileName: String? = null,
    val allowThirdPartyCookies: Boolean = false,
    val textZoom: Int = 100,
    val desktopMode: Boolean = false,
    val trackingProtection: Boolean = false,
    val trackingExceptions: Set<String> = emptySet(),
)

data class NavigationEntry(val title: String, val url: String, val offset: Int)

interface BlobTransfer {
    fun cancel()
}

interface BlobReceiver {
    fun onChunk(bytes: ByteArray, acknowledge: (Boolean) -> Unit)
    fun onComplete(mimeType: String?)
    fun onError(message: String)
}

data class EngineState(
    val url: String? = null,
    val title: String = "",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val error: PageError? = null,
)

data class PageError(val message: String, val failedUrl: String?)

data class SiteInfo(
    val url: String?,
    val subject: String?,
    val issuer: String?,
    val validFrom: Long?,
    val validTo: Long?,
)

data class DownloadRequest(
    val url: String,
    val userAgent: String,
    val contentDisposition: String?,
    val mimeType: String?,
    val contentLength: Long,
)

data class LinkTarget(val url: String, val imageUrl: String? = null)

enum class JavaScriptDialogKind { ALERT, CONFIRM, PROMPT, BEFORE_UNLOAD }

interface JavaScriptDialogRequest {
    val kind: JavaScriptDialogKind
    val origin: String
    val message: String
    val defaultValue: String?
    fun confirm(value: String? = null)
    fun cancel()
}

interface FileSelectionRequest {
    val acceptTypes: Array<String>
    val allowMultiple: Boolean
    val capture: Boolean
    fun complete(uris: Array<Uri>?)
}

enum class WebPermissionKind { CAMERA, MICROPHONE, LOCATION, PROTECTED_MEDIA }

interface WebPermissionRequest {
    val origin: String
    val kinds: Set<WebPermissionKind>
    fun grant()
    fun deny()
}

interface HttpAuthenticationRequest {
    val host: String
    val realm: String
    fun proceed(username: String, password: String)
    fun cancel()
}

interface ClientCertificateRequest {
    val host: String
    val port: Int
    val keyTypes: Array<String>
    val principals: Array<java.security.Principal>?
    fun proceed(privateKey: java.security.PrivateKey, chain: Array<java.security.cert.X509Certificate>)
    fun cancel()
}

interface FullScreenRequest {
    val view: View
    fun close()
}

interface EngineSession {
    val id: String
    val view: View
    val state: StateFlow<EngineState>
    val userAgent: String
    fun load(url: String)
    fun backUrl(): String?
    fun backHistory(): List<NavigationEntry>
    fun goBackOrForward(offset: Int)
    fun forwardUrl(): String?
    fun goBack()
    fun goForward()
    fun reload()
    fun stop()
    fun pause()
    fun resume()
    fun pauseTimers()
    fun resumeTimers()
    fun applySettings(allowThirdPartyCookies: Boolean, textZoom: Int)
    fun applyTrackingProtection(enabled: Boolean, exceptions: Set<String>)
    fun setDesktopMode(enabled: Boolean)
    fun find(text: String)
    fun findNext(forward: Boolean)
    fun clearFind()
    fun createPrintAdapter(jobName: String): PrintDocumentAdapter?
    fun blobUnavailableReason(url: String): String?
    fun downloadBlob(url: String, maxBytes: Long, receiver: BlobReceiver): BlobTransfer?
    fun allowHttpOnce(url: String)
    fun saveState(maxBytes: Int): ByteArray?
    fun restoreState(bytes: ByteArray, expectedUrl: String?): Boolean
    fun capturePreview(): ByteArray?
    fun siteInfo(): SiteInfo
    fun close()
}

interface PopupRequest {
    fun accept(session: EngineSession)
}

interface EngineCallbacks {
    fun onNavigationStarted(sessionId: String)
    fun onFindResult(sessionId: String, activeIndex: Int, total: Int)
    fun onPageFinished(sessionId: String)
    fun onVisited(sessionId: String, url: String, isReload: Boolean)
    fun onFavicon(sessionId: String, url: String, icon: ByteArray)
    fun onExternalNavigation(sessionId: String, url: String, hasGesture: Boolean)
    fun onHttpNavigation(sessionId: String, url: String)
    fun onPopupRequested(parentId: String, request: PopupRequest): Boolean
    fun onPopupBlocked(parentId: String)
    fun onCloseRequested(sessionId: String)
    fun onRendererGone(sessionId: String, session: EngineSession)
    fun onFileSelection(sessionId: String, request: FileSelectionRequest)
    fun onJavaScriptDialog(sessionId: String, request: JavaScriptDialogRequest)
    fun onFullScreen(sessionId: String, request: FullScreenRequest)
    fun onFullScreenClosed(sessionId: String)
    fun onLinkLongPress(sessionId: String, target: LinkTarget)
    fun onDownload(sessionId: String, request: DownloadRequest)
    fun onPermission(sessionId: String, request: WebPermissionRequest)
    fun onPermissionCanceled(sessionId: String, request: WebPermissionRequest)
    fun onHttpAuthentication(sessionId: String, request: HttpAuthenticationRequest)
    fun onClientCertificate(sessionId: String, request: ClientCertificateRequest)
    fun onPullToRefresh(sessionId: String)
}

interface BrowserEngine {
    fun createSession(context: Context, id: String, config: SessionConfig, callbacks: EngineCallbacks): EngineSession
    fun supportsPrivateMode(): Boolean
    fun privateProfileNames(prefix: String): List<String>
    fun deletePrivateProfile(name: String): Boolean
    fun clearDefaultSiteData(context: Context, onComplete: (Boolean) -> Unit)
}
