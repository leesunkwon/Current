package com.kotlinsun.current.browser

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI

data class PwaSite(val title: String, val startUrl: String, val scopeUrl: String)

/** Resolves public HTTPS manifests without passing browser cookies to another HTTP client. */
internal object PwaSupport {
    suspend fun inspect(pageUrl: String, manifestUrl: String): PwaSite? = withContext(Dispatchers.IO) {
        runCatching {
            if (!sameOrigin(pageUrl, manifestUrl)) return@runCatching null
            val connection = (URI(manifestUrl).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/manifest+json, application/json")
            }
            try {
                if (connection.responseCode != 200 || connection.contentLengthLong > 131072L)
                    return@runCatching null
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 131072) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                if (bytes.size > 131072) return@runCatching null
                val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
                if (manifest.optString("display") !in setOf("standalone", "fullscreen", "minimal-ui"))
                    return@runCatching null
                val base = URI(manifestUrl)
                val start = base.resolve(manifest.optString("start_url").ifBlank { pageUrl })
                    .normalize().toString()
                val defaultScope = URI(start).resolve("./").toString()
                val scope = base.resolve(manifest.optString("scope").ifBlank { defaultScope })
                    .normalize().toString()
                if (!sameOrigin(pageUrl, start) || !sameOrigin(pageUrl, scope) ||
                    !withinScope(start, scope)) return@runCatching null
                val title = manifest.optString("short_name").ifBlank {
                    manifest.optString("name")
                }.ifBlank { Uri.parse(pageUrl).host.orEmpty() }
                PwaSite(title.take(80), start, scope)
            } finally { connection.disconnect() }
        }.getOrNull()
    }

    fun withinScope(url: String, scope: String): Boolean {
        if (!sameOrigin(url, scope)) return false
        val target = Uri.parse(url).path.orEmpty()
        val prefix = Uri.parse(scope).path.orEmpty()
        return target.startsWith(prefix)
    }

    private fun sameOrigin(first: String, second: String): Boolean = runCatching {
        val a = Uri.parse(first)
        val b = Uri.parse(second)
        a.scheme == "https" && b.scheme == "https" &&
            a.host != null && a.host.equals(b.host, true) &&
            (if (a.port < 0) 443 else a.port) == (if (b.port < 0) 443 else b.port) &&
            a.userInfo == null && b.userInfo == null
    }.getOrDefault(false)
}
