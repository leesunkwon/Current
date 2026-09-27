package com.kotlinsun.current.browser

import android.net.Uri
import android.text.Html
import com.kotlinsun.current.data.BookmarkRecord

internal data class ImportedBookmark(val title: String, val url: String)

/** Reads the common Netscape bookmark HTML format without loading unbounded picker content. */
internal object BookmarkBackup {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_LINKS = 2_000
    private val anchor = Regex("<a\\b([^>]*)>(.*?)</a\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val href = Regex("\\bhref\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)

    fun parse(html: String): List<ImportedBookmark> {
        if (anchor.findAll(html).take(MAX_LINKS + 1).count() > MAX_LINKS)
            error("Too many bookmarks")
        val seen = HashSet<String>()
        return anchor.findAll(html).mapNotNull { match ->
            val rawUrl = href.find(match.groupValues[1])?.groupValues?.get(2) ?: return@mapNotNull null
            val url = decode(rawUrl).trim().takeIf(::validUrl) ?: return@mapNotNull null
            val key = key(url)
            if (!seen.add(key)) return@mapNotNull null
            val title = decode(match.groupValues[2]).trim().take(300).ifBlank { url }
            ImportedBookmark(title, url)
        }.toList()
    }

    fun export(records: List<BookmarkRecord>): String = buildString {
        append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n<TITLE>Current Bookmarks</TITLE>\n<H1>Current Bookmarks</H1>\n<DL><p>\n")
        records.filter { validUrl(it.url) }.forEach { record ->
            append("<DT><A HREF=\"")
            append(escape(record.url))
            append("\" ADD_DATE=\"")
            append(record.createdAt / 1000)
            append("\">")
            append(escape(record.title))
            append("</A>\n")
        }
        append("</DL><p>\n")
    }

    fun key(url: String): String {
        val parsed = Uri.parse(url)
        return parsed.scheme.orEmpty().lowercase() + "://" + parsed.host.orEmpty().lowercase() +
            (if (parsed.port >= 0) ":${parsed.port}" else "") +
            parsed.encodedPath.orEmpty() +
            (parsed.encodedQuery?.let { "?$it" } ?: "") +
            (parsed.encodedFragment?.let { "#$it" } ?: "")
    }

    private fun validUrl(value: String): Boolean = runCatching {
        val uri = Uri.parse(value)
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank() && uri.encodedAuthority?.contains('@') != true &&
            value.none { it.isWhitespace() || it.code < 0x20 } &&
            value.length <= 4096
    }.getOrDefault(false)

    private fun decode(value: String): String = Html.fromHtml(value,
        Html.FROM_HTML_MODE_LEGACY).toString()

    private fun escape(value: String): String = value.replace("&", "&amp;")
        .replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
