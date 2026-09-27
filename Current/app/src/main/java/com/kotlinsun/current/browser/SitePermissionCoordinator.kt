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
        val origin = runCatching { Uri.parse(request.origin) }.getOrNull()
        val permittedOrigin = origin?.host != null && (origin.scheme == "https" ||
            (origin.scheme == "http" && origin.host == "localhost"))
        if (!permittedOrigin || pending != null) {
            request.deny()
            return false
        }
        pending = request
        return true
    }

    fun approve(host: BrowserHost?) {
        val request = pending ?: return
        val permissions = request.kinds.mapNotNull {
            when (it) {
                WebPermissionKind.CAMERA -> Manifest.permission.CAMERA
                WebPermissionKind.MICROPHONE -> Manifest.permission.RECORD_AUDIO
                WebPermissionKind.LOCATION -> Manifest.permission.ACCESS_COARSE_LOCATION
                WebPermissionKind.PROTECTED_MEDIA -> null
            }
        }.distinct().toTypedArray()
        if (permissions.isEmpty()) {
            pending = null
            request.grant()
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
}
