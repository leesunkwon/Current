package com.kotlinsun.current.browser

import android.net.Uri
import java.net.IDN

enum class SearchEngine(val label: String, private val baseUrl: String, private val parameter: String) {
    GOOGLE("Google", "https://www.google.com/search", "q"),
    NAVER("Naver", "https://search.naver.com/search.naver", "query"),
    DAUM("Daum", "https://search.daum.net/search", "q"),
    BING("Bing", "https://www.bing.com/search", "q"),
    DUCK_DUCK_GO("DuckDuckGo", "https://duckduckgo.com/", "q");

    fun searchUrl(query: String): String = Uri.parse(baseUrl).buildUpon()
        .appendQueryParameter(parameter, query).build().toString()

    companion object {
        fun fromStored(value: String): SearchEngine = entries.firstOrNull { it.name == value } ?: GOOGLE
    }
}

object AddressResolver {
    fun displayUrl(value: String): String = runCatching {
        val uri = Uri.parse(value)
        val host = uri.host ?: return@runCatching value
        if (uri.userInfo != null) return@runCatching value
        val unicode = IDN.toUnicode(host)
        if (unicode.codePoints().toArray().any {
            !Character.isLetterOrDigit(it) && it != '-'.code && it != '.'.code
        }) return@runCatching value
        val scripts = unicode.split('.').map { label ->
            label.codePoints().toArray().filter { Character.isLetter(it) }.map { point ->
                if (point < 128) Character.UnicodeScript.LATIN
                else Character.UnicodeScript.of(point)
            }.toSet()
        }
        val allowed = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.HANGUL,
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.CYRILLIC,
            Character.UnicodeScript.GREEK)
        val safe = scripts.all { it.size <= 1 && it.all(allowed::contains) } &&
            scripts.flatten().filter { it != Character.UnicodeScript.LATIN }.toSet().size <= 1
        if (!safe) value else uri.buildUpon().encodedAuthority(
            unicode + if (uri.port >= 0) ":${uri.port}" else "").build().toString()
    }.getOrDefault(value)

    fun resolve(input: String, engine: SearchEngine): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val explicit = text.startsWith("https://", true) || text.startsWith("http://", true)
        if (explicit) return validWebUrl(text)
        if (!text.contains(' ') && !text.contains('\n') && looksLikeHost(text)) {
            return validWebUrl("https://$text")
        }
        return engine.searchUrl(text)
    }

    private fun validWebUrl(text: String): String? = runCatching {
        if (text.any { it.isWhitespace() }) return@runCatching null
        val uri = Uri.parse(text)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host ?: return@runCatching null
        if ((scheme != "http" && scheme != "https") || host.isBlank() || uri.userInfo != null) return@runCatching null
        if (uri.port > 65535) return@runCatching null
        val asciiHost = IDN.toASCII(host)
        if (asciiHost.isBlank() || asciiHost.any { it.isWhitespace() }) return@runCatching null
        uri.buildUpon().encodedAuthority(
            if (uri.port == -1) asciiHost else "$asciiHost:${uri.port}"
        ).build().toString()
    }.getOrNull()

    private fun looksLikeHost(text: String): Boolean {
        val authority = text.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringBefore(':')
        if (host.equals("localhost", true)) return true
        if (host.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}"))) return true
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) return false
        return runCatching {
            val ascii = IDN.toASCII(host)
            ascii.split('.').all { it.isNotEmpty() && it.length <= 63 && !it.startsWith('-') && !it.endsWith('-') } &&
                ascii.substringAfterLast('.').length >= 2
        }.getOrDefault(false)
    }
}
