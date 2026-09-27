package com.kotlinsun.current

import android.app.Activity
import android.content.Intent
import android.net.Uri

/** Opens only a sanitized ACTION_VIEW after the PWA user accepts the origin prompt. */
internal object PwaExternalOpener {
    fun open(activity: Activity, url: String, fallback: (String) -> Unit): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme in setOf("http", "https", "file", "content", "javascript", "data",
                "blob", "about")) return false
        var fallbackUrl: String? = null
        val intent = if (scheme == "intent") {
            val parsed = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }
                .getOrNull() ?: return false
            if (parsed.`package` == activity.packageName) return false
            fallbackUrl = parsed.getStringExtra("browser_fallback_url")?.takeIf(::isWebUrl)
            val target = parsed.data ?: return fallbackUrl?.let { fallback(it); true } ?: false
            if (target.scheme?.lowercase() in setOf("file", "content", "javascript", "data",
                    "blob", "about", "intent")) return false
            if (target.scheme?.lowercase() in setOf("http", "https") && parsed.`package`.isNullOrBlank()) {
                fallback(target.toString())
                return true
            }
            Intent(Intent.ACTION_VIEW, target).apply { `package` = parsed.`package` }
        } else Intent(Intent.ACTION_VIEW, uri)
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.clipData = null
        intent.flags = 0
        return runCatching { activity.startActivity(intent); true }.getOrElse {
            if (fallbackUrl != null) {
                fallback(fallbackUrl)
                true
            } else {
                val packageId = intent.`package`?.takeIf(::isPackageName)
                    ?: if (scheme == "market") uri.getQueryParameter("id")
                        ?.takeIf(::isPackageName) else null
                if (packageId == null) false else {
                    val market = Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=$packageId"))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                    runCatching { activity.startActivity(market); true }.getOrElse {
                        fallback("https://play.google.com/store/apps/details?id=$packageId")
                        true
                    }
                }
            }
        }
    }

    private fun isWebUrl(url: String): Boolean = Uri.parse(url).let {
        it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank()
    }

    private fun isPackageName(value: String): Boolean = value.matches(
        Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+"))
}
