package com.kotlinsun.current.engine.webview

/** Small built-in list for optional third-party blocking; no downloaded filter list or page script. */
internal object TrackerRules {
    private val domains = setOf(
        "google-analytics.com", "googletagmanager.com", "doubleclick.net",
        "facebook.net", "ads-twitter.com", "analytics.tiktok.com",
    )

    fun shouldBlock(pageHost: String, resourceHost: String): Boolean {
        if (resourceHost == pageHost || resourceHost.endsWith(".$pageHost") ||
            pageHost.endsWith(".$resourceHost")) return false
        return domains.any { resourceHost == it || resourceHost.endsWith(".$it") }
    }
}
