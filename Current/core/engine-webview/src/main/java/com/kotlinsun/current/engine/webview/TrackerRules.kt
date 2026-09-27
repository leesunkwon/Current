package com.kotlinsun.current.engine.webview

/** Conservative, bundled host rules. Only third-party requests are eligible. */
object TrackerRules {
    private val domains = setOf(
        "google-analytics.com", "googletagmanager.com", "doubleclick.net",
        "facebook.net", "ads-twitter.com", "analytics.tiktok.com",
        "scorecardresearch.com", "quantserve.com", "hotjar.com",
        "fullstory.com", "mixpanel.com", "amplitude.com", "segment.io",
        "branch.io", "appsflyer.com", "adjust.com", "criteo.com",
        "taboola.com", "outbrain.com", "adsrvr.org", "demdex.net",
        "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com",
        "bidswitch.net", "advertising.com", "mathtag.com", "moatads.com",
    )

    fun shouldBlock(pageHost: String, resourceHost: String): Boolean {
        if (resourceHost == pageHost || resourceHost.endsWith(".$pageHost") ||
            pageHost.endsWith(".$resourceHost")) return false
        return domains.any { resourceHost == it || resourceHost.endsWith(".$it") }
    }
}
