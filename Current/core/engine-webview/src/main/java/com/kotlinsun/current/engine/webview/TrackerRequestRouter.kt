package com.kotlinsun.current.engine.webview

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.ServiceWorkerClient
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The default-profile worker has no tab identifier; counts are shared by site origin. */
object TrackerRequestRouter {
    private data class Client(
        val origin: String?,
        val enabled: Boolean,
        val exceptions: Set<String>,
        val onWorkerCount: (Int) -> Unit,
    )

    private val clients = ConcurrentHashMap<String, Client>()
    private val siteCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var installed = false

    fun register(id: String, onWorkerCount: (Int) -> Unit) {
        installIfSupported()
        clients[id] = Client(null, false, emptySet(), onWorkerCount)
    }

    fun update(id: String, origin: String?, enabled: Boolean, exceptions: Set<String>) {
        val old = clients[id] ?: return
        val canonical = origin?.let(::canonicalOrigin)
        clients[id] = old.copy(origin = canonical, enabled = enabled,
            exceptions = exceptions.toSet())
        old.onWorkerCount(canonical?.let { siteCounts[it]?.get() } ?: 0)
        if (old.origin != canonical) prune(old.origin)
    }

    fun unregister(id: String) { prune(clients.remove(id)?.origin) }

    private fun prune(origin: String?) {
        if (origin != null && clients.values.none { it.origin == origin }) siteCounts.remove(origin)
    }

    @Synchronized
    private fun installIfSupported() {
        if (installed) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) return
        installed = runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                ProfileStore.getInstance().getOrCreateProfile(Profile.DEFAULT_PROFILE_NAME)
                    .serviceWorkerController.setServiceWorkerClient(object : ServiceWorkerClient() {
                        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                            interceptWorker(request)
                    })
            } else {
                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
                    object : ServiceWorkerClientCompat() {
                        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                            interceptWorker(request)
                    })
            }
        }.isSuccess
    }

    private fun interceptWorker(request: WebResourceRequest): WebResourceResponse? {
        val source = request.requestHeaders.entries.firstOrNull {
            it.key.equals("Origin", true)
        }?.value ?: request.requestHeaders.entries.firstOrNull {
            it.key.equals("Referer", true)
        }?.value ?: return null
        val origin = canonicalOrigin(source) ?: return null
        val matching = clients.values.filter { it.origin == origin }
        if (matching.isEmpty() || matching.any { !it.enabled || origin in it.exceptions }) return null
        val pageHost = Uri.parse(origin).host ?: return null
        val resource = request.url
        if (resource.scheme !in setOf("https", "http") ||
            !TrackerRules.shouldBlock(pageHost, resource.host?.lowercase() ?: return null)) return null
        val count = siteCounts.computeIfAbsent(origin) { AtomicInteger() }.incrementAndGet()
        main.post {
            clients.values.filter { it.origin == origin }.forEach { it.onWorkerCount(count) }
        }
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    private fun canonicalOrigin(value: String): String? = runCatching {
        val uri = Uri.parse(value)
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "https" || it == "http" }
            ?: return@runCatching null
        val host = uri.host?.lowercase() ?: return@runCatching null
        "$scheme://$host" + if (uri.port >= 0) ":${uri.port}" else ""
    }.getOrNull()
}
