package com.kotlinsun.current.browser

import android.content.Context
import android.view.Window
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kotlinsun.current.engine.BrowserEngine
import com.kotlinsun.current.engine.EngineCallbacks
import com.kotlinsun.current.engine.EngineSession
import com.kotlinsun.current.engine.FullScreenRequest
import com.kotlinsun.current.engine.PopupRequest
import com.kotlinsun.current.engine.SessionConfig
import com.kotlinsun.current.engine.webview.WebViewEngine
import java.util.UUID

internal data class Owned<T>(val owner: String, val value: T)

internal enum class PwaRendererRecovery {
    IGNORED,
    POPUP_CLOSED,
    MAIN_CRASHED,
}

/**
 * Coordinates PWA browser sessions (main and popup), full screen requests, and navigation scope.
 */
internal class PwaSessionCoordinator(
    private val engine: BrowserEngine = WebViewEngine()
) {
    var mainSession by mutableStateOf<EngineSession?>(null)
        private set
    var popupSession by mutableStateOf<EngineSession?>(null)
        private set
    var fullScreen by mutableStateOf<Owned<FullScreenRequest>?>(null)
        private set

    var scopeUrl: String = ""
    var currentUrl: String = ""

    val activeSession: EngineSession?
        get() = popupSession ?: mainSession

    fun session(id: String): EngineSession? = when (id) {
        mainSession?.id -> mainSession
        popupSession?.id -> popupSession
        else -> null
    }

    fun isVisible(sessionId: String): Boolean =
        sessionId == (popupSession ?: mainSession)?.id

    fun openMain(
        context: Context,
        url: String,
        config: SessionConfig,
        callbacks: EngineCallbacks
    ): Boolean {
        val created = runCatching {
            engine.createSession(context, UUID.randomUUID().toString(), config, callbacks)
        }.getOrNull() ?: return false

        mainSession = created
        return runCatching {
            created.load(url)
            true
        }.getOrElse {
            mainSession = null
            created.close()
            false
        }
    }

    fun onPopupRequested(
        context: Context,
        parentId: String,
        request: PopupRequest,
        config: SessionConfig,
        callbacks: EngineCallbacks
    ): Boolean {
        if (popupSession != null || parentId != mainSession?.id) return false
        val child = runCatching {
            engine.createSession(context, UUID.randomUUID().toString(), config, callbacks)
        }.getOrNull() ?: return false

        popupSession = child
        return runCatching {
            request.accept(child)
            true
        }.getOrElse {
            popupSession = null
            child.close()
            false
        }
    }

    fun closePopup(onCancelRequests: (String) -> Unit) {
        val popup = popupSession ?: return
        onCancelRequests(popup.id)
        if (fullScreen?.owner == popup.id) closeFullscreen(null)
        popupSession = null
        popup.close()
    }

    fun setFullscreen(sessionId: String, request: FullScreenRequest, window: Window?) {
        closeFullscreen(window)
        fullScreen = Owned(sessionId, request)
        window?.let {
            WindowInsetsControllerCompat(it, it.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun closeFullscreen(window: Window?) {
        val request = fullScreen
        fullScreen = null
        request?.value?.close()
        window?.let {
            WindowInsetsControllerCompat(it, it.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun onPageFinished(sessionId: String) {
        if (sessionId == mainSession?.id) {
            mainSession?.state?.value?.url?.let { url ->
                if (PwaSupport.withinScope(url, scopeUrl)) currentUrl = url
            }
        }
    }

    fun handleRendererGone(
        sessionId: String,
        session: EngineSession,
        onCancelRequests: (String) -> Unit
    ): PwaRendererRecovery {
        onCancelRequests(sessionId)
        if (popupSession === session) {
            closePopup(onCancelRequests)
            return PwaRendererRecovery.POPUP_CLOSED
        }
        if (mainSession !== session) {
            session.close()
            return PwaRendererRecovery.IGNORED
        }
        session.close()
        mainSession = null
        closePopup(onCancelRequests)
        return PwaRendererRecovery.MAIN_CRASHED
    }

    fun onPause() {
        popupSession?.pause()
        mainSession?.pause()
    }

    fun onResume() {
        mainSession?.resume()
        popupSession?.resume()
    }

    fun destroy(onCancelRequests: (String) -> Unit, window: Window?) {
        closeFullscreen(window)
        closePopup(onCancelRequests)
        mainSession?.close()
        mainSession = null
    }
}
