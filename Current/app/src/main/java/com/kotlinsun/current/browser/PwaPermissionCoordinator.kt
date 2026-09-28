package com.kotlinsun.current.browser

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.kotlinsun.current.data.SitePermissionRecord
import com.kotlinsun.current.engine.WebPermissionRequest

internal enum class PwaPermissionDecision {
    DENIED,
    PROMPT_NEEDED,
    DIRECT_REQUEST,
}

/**
 * Coordinates Web and Android runtime permissions for PWA instances.
 */
internal class PwaPermissionCoordinator {
    var savedPermissions: Map<Pair<String, String>, SitePermissionRecord> = emptyMap()
        private set

    var runtimeInFlight: Boolean = false
        internal set

    var cameraPermissionInFlight: Boolean = false
        internal set

    private var pendingRuntime: Owned<WebPermissionRequest>? = null
    private var runtimePermissions: Array<String> = emptyArray()

    fun updateSavedPermissions(records: List<SitePermissionRecord>) {
        savedPermissions = records.associateBy { it.origin to it.kind }
    }

    fun evaluatePermission(
        sessionId: String,
        request: WebPermissionRequest,
        isVisible: Boolean,
        hasActivePrompt: Boolean
    ): PwaPermissionDecision {
        val origin = SitePermissionCoordinator.canonicalOrigin(request.origin)
        if (!isVisible || runtimeInFlight || hasActivePrompt || origin == null) {
            request.deny()
            return PwaPermissionDecision.DENIED
        }
        val decisions = request.kinds.map { savedPermissions[origin to it.name] }
        if (decisions.any { it?.allowed == false }) {
            request.deny()
            return PwaPermissionDecision.DENIED
        }
        if (decisions.all { it?.allowed == true }) {
            return PwaPermissionDecision.DIRECT_REQUEST
        }
        return PwaPermissionDecision.PROMPT_NEEDED
    }

    fun requestSitePermission(
        context: Context,
        owner: String,
        request: WebPermissionRequest,
        isOwnerAlive: Boolean,
        launchLauncher: (Array<String>) -> Unit
    ) {
        if (!isOwnerAlive || runtimeInFlight) {
            request.deny()
            return
        }
        val permissions = SitePermissionCoordinator.androidPermissions(request.kinds)
            .filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isEmpty()) {
            request.grant()
            return
        }
        pendingRuntime = Owned(owner, request)
        runtimeInFlight = true
        runtimePermissions = permissions.toTypedArray()
        runCatching {
            launchLauncher(permissions.toTypedArray())
        }.onFailure {
            runtimeInFlight = false
            pendingRuntime = null
            runtimePermissions = emptyArray()
            request.deny()
        }
    }

    fun onRuntimePermissionResult(
        result: Map<String, Boolean>,
        isOwnerAlive: (String) -> Boolean
    ) {
        runtimeInFlight = false
        val pending = pendingRuntime
        pendingRuntime = null
        val requested = runtimePermissions
        runtimePermissions = emptyArray()
        if (pending != null) {
            if (isOwnerAlive(pending.owner) && requested.isNotEmpty() && requested.all { result[it] == true }) {
                pending.value.grant()
            } else {
                pending.value.deny()
            }
        }
    }

    fun onPermissionCanceled(request: WebPermissionRequest): Boolean {
        if (pendingRuntime?.value === request) {
            val pending = pendingRuntime
            pendingRuntime = null
            pending?.value?.deny()
            return true
        }
        return false
    }

    fun cancelForOwner(owner: String) {
        if (pendingRuntime?.owner == owner) {
            val pending = pendingRuntime
            pendingRuntime = null
            pending?.value?.deny()
        }
    }

    fun clear() {
        pendingRuntime?.value?.deny()
        pendingRuntime = null
        runtimeInFlight = false
        cameraPermissionInFlight = false
        runtimePermissions = emptyArray()
    }
}
