package com.kotlinsun.current.browser

import java.util.Locale

internal object DownloadSafety {
    private val dangerousExtensions = setOf(
        "apk", "apks", "xapk", "aab", "dex", "jar", "exe", "msi", "bat", "cmd", "ps1",
        "vbs", "js", "jse", "sh", "com", "scr",
    )
    private val dangerousMimeTypes = setOf(
        "application/vnd.android.package-archive",
        "application/x-msdownload",
        "application/x-msdos-program",
        "application/x-sh",
        "application/javascript",
        "text/javascript",
        "application/java-archive",
    )

    fun isDangerous(fileName: String, mimeType: String?): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        return extension in dangerousExtensions || mime in dangerousMimeTypes
    }
}
