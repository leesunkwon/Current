package com.kotlinsun.current.browser

import android.net.Uri
import android.text.Html
import com.kotlinsun.current.data.BookmarkRecord
import com.kotlinsun.current.data.BookmarkFolderRecord

internal data class ImportedBookmark(val title: String, val url: String,
                                     val folderPath: List<String> = emptyList())
internal data class ParsedBookmarkBackup(val entries: List<ImportedBookmark>,
                                         val folderPaths: List<List<String>>)

/** Reads the common Netscape bookmark HTML format without loading unbounded picker content. */
internal object BookmarkBackup {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_LINKS = 2_000
    private val anchor = Regex("<a\\b([^>]*)>(.*?)</a\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val href = Regex("\\bhref\\s*=\\s*([\"'])(.*?)\\1", RegexOption.IGNORE_CASE)
    private val folder = Regex("<h3\\b[^>]*>(.*?)</h3\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val token = Regex("</?dl\\b[^>]*>|<h3\\b[^>]*>.*?</h3\\s*>|<a\\b[^>]*>.*?</a\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun parse(html: String): List<ImportedBookmark> = parseDocument(html).entries

    fun parseDocument(html: String): ParsedBookmarkBackup {
        val seen = HashSet<String>()
        val result = mutableListOf<ImportedBookmark>()
        val folderPaths = mutableListOf<List<String>>()
        val path = mutableListOf<String>()
        val levels = mutableListOf<Boolean>()
        var pendingFolder: String? = null
        for (match in token.findAll(html)) {
            val value = match.value
            when {
                value.startsWith("</dl", true) -> {
                    if (levels.removeLastOrNull() == true && path.isNotEmpty()) path.removeAt(path.lastIndex)
                    pendingFolder = null
                }
                value.startsWith("<dl", true) -> {
                    val name = pendingFolder
                    if (name != null) {
                        if (path.size >= 16) error("Bookmark folders too deep")
                        path.add(name)
                        if (folderPaths.size >= MAX_LINKS) error("Too many bookmark folders")
                        folderPaths.add(path.toList())
                    }
                    levels.add(name != null)
                    pendingFolder = null
                }
                value.startsWith("<h3", true) -> {
                    pendingFolder = folder.find(value)?.groupValues?.get(1)?.let(::decode)
                        ?.trim()?.take(60)?.takeIf { it.isNotBlank() }
                }
                value.startsWith("<a", true) -> {
                    val link = anchor.find(value) ?: continue
                    val rawUrl = href.find(link.groupValues[1])?.groupValues?.get(2) ?: continue
                    val url = decode(rawUrl).trim().takeIf(::validUrl) ?: continue
                    if (!seen.add(key(url))) continue
                    if (result.size >= MAX_LINKS) error("Too many bookmarks")
                    val title = decode(link.groupValues[2]).trim().take(300).ifBlank { url }
                    result.add(ImportedBookmark(title, url, path.toList()))
                    pendingFolder = null
                }
            }
        }
        return ParsedBookmarkBackup(result, folderPaths)
    }

    fun export(records: List<BookmarkRecord>, folders: List<BookmarkFolderRecord> = emptyList()): String = buildString {
        append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n<TITLE>Current Bookmarks</TITLE>\n<H1>Current Bookmarks</H1>\n<DL><p>\n")
        fun writeFolder(parentId: String?, visited: MutableSet<String>) {
            records.filter { it.folderId == parentId && validUrl(it.url) }.forEach { record ->
                append("<DT><A HREF=\"")
                append(escape(record.url))
                append("\" ADD_DATE=\"")
                append(record.createdAt / 1000)
                append("\">")
                append(escape(record.title))
                append("</A>\n")
            }
            folders.filter { it.parentId == parentId && visited.add(it.id) }.forEach { item ->
                append("<DT><H3>")
                append(escape(item.name))
                append("</H3>\n<DL><p>\n")
                writeFolder(item.id, visited)
                append("</DL><p>\n")
            }
        }
        writeFolder(null, mutableSetOf())
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
