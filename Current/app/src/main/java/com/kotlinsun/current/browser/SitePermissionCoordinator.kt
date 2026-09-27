package com.kotlinsun.current.browser

import android.Manifest
import android.net.Uri
import com.kotlinsun.current.engine.WebPermissionKind
import com.kotlinsun.current.engine.WebPermissionRequest

/** An origin-bound, single-use site permission request. */
internal class SitePermissionCoordinator {
    var pending: WebPermissionRequest? = null
        private set

    fun begin(request: WebPermissionRequest): Boolean {
        if (canonicalOrigin(request.origin) == null || pending != null) {
            request.deny()
            return false
        }
        pending = request
        return true
    }

    fun approve(host: BrowserHost?, onResult: (Boolean) -> Unit = {}) {
        val request = pending ?: return
        val permissions = androidPermissions(request.kinds).toTypedArray()
        if (permissions.isEmpty()) {
            pending = null
            request.grant()
            onResult(true)
            return
        }
        if (host == null) {
            cancel()
            return
        }
        host.requestPermissions(permissions) { granted ->
            if (pending === request) {
                pending = null
                if (granted) request.grant() else request.deny()
                onResult(granted)
            }
        }
    }

    fun cancel() {
        val request = pending ?: return
        pending = null
        request.deny()
    }

    fun cancelIfSame(request: WebPermissionRequest): Boolean {
        if (pending !== request) return false
        cancel()
        return true
    }

    companion object {
        fun androidPermissions(kinds: Collection<WebPermissionKind>): List<String> = kinds.mapNotNull {
            when (it) {
                WebPermissionKind.CAMERA -> Manifest.permission.CAMERA
                WebPermissionKind.MICROPHONE -> Manifest.permission.RECORD_AUDIO
                WebPermissionKind.LOCATION -> Manifest.permission.ACCESS_COARSE_LOCATION
                WebPermissionKind.PROTECTED_MEDIA -> null
            }
        }.distinct()

        fun canonicalOrigin(value: String): String? = runCatching {
            val uri = Uri.parse(value)
            val scheme = uri.scheme?.lowercase() ?: return@runCatching null
            val host = uri.host?.lowercase() ?: return@runCatching null
            if (uri.userInfo != null || (scheme != "https" &&
                    !(scheme == "http" && host == "localhost"))) return@runCatching null
            val port = uri.port
            "$scheme://$host" + if (port >= 0 && !(scheme == "https" && port == 443) &&
                !(scheme == "http" && port == 80)) ":$port" else ""
        }.getOrNull()
    }
}
