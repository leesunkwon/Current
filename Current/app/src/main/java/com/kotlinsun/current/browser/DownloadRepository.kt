package com.kotlinsun.current.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import com.kotlinsun.current.R
import com.kotlinsun.current.data.BrowserStore
import com.kotlinsun.current.data.DownloadRecord
import com.kotlinsun.current.data.LocalDownloadRecord
import com.kotlinsun.current.engine.DownloadRequest
import com.kotlinsun.current.engine.TabMode
import java.util.UUID

internal data class DownloadSnapshot(
    val managed: List<DownloadItem>,
    val local: List<LocalDownloadRecord>,
    val missingLocalIds: Set<Long>,
    val inaccessibleLocalIds: Set<Long>,
)

/** System download queries and writes. The controller owns only UI state and prompts. */
internal class DownloadRepository(
    private val context: Context,
    private val store: BrowserStore,
    private val manager: DownloadManager,
) {
    suspend fun snapshot(): DownloadSnapshot {
        val managed = store.loadDownloads().map { record ->
            runCatching {
                val cursor = manager.query(DownloadManager.Query().setFilterById(record.id))
                    ?: error("DownloadManager 조회 실패")
                cursor.use {
                    if (!it.moveToFirst()) return@use DownloadItem(record, DOWNLOAD_STATUS_MISSING, 0, 0, -1)
                    val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val missing = if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        try {
                            manager.openDownloadedFile(record.id).use { }
                            false
                        } catch (_: java.io.FileNotFoundException) { true }
                        catch (_: IllegalArgumentException) { true }
                    } else false
                    DownloadItem(record, if (missing) DOWNLOAD_STATUS_MISSING else status,
                        it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                        it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                        it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
                }
            }.getOrElse { DownloadItem(record, DOWNLOAD_STATUS_QUERY_ERROR, 0, 0, -1) }
        }
        val local = store.loadLocalDownloads()
        val missingLocal = mutableSetOf<Long>()
        val inaccessibleLocal = mutableSetOf<Long>()
        local.forEach { item ->
            try {
                val readable = context.contentResolver.openAssetFileDescriptor(Uri.parse(item.contentUri), "r")
                    ?.use { true } == true
                if (!readable) missingLocal.add(item.id)
            } catch (_: java.io.FileNotFoundException) { missingLocal.add(item.id) }
            catch (_: IllegalArgumentException) { missingLocal.add(item.id) }
            catch (_: Exception) { inaccessibleLocal.add(item.id) }
        }
        return DownloadSnapshot(managed, local, missingLocal, inaccessibleLocal)
    }

    suspend fun enqueue(mode: TabMode, request: DownloadRequest, name: String,
                        retryOriginalId: Long?): Pair<Long, Boolean> {
        val uri = Uri.parse(request.url)
        if (uri.scheme !in listOf("http", "https"))
            error(context.getString(R.string.download_address_unsupported))
        val cookie = if (mode == TabMode.NORMAL)
            CookieManager.getInstance().getCookie(request.url) else null
        val item = DownloadManager.Request(uri)
            .setTitle(name)
            .setDescription(uri.host.orEmpty())
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        request.mimeType?.let(item::setMimeType)
        if (request.userAgent.isNotBlank()) item.addRequestHeader("User-Agent", request.userAgent)
        cookie?.let { item.addRequestHeader("Cookie", it) }
        val id = manager.enqueue(item)
        if (mode == TabMode.NORMAL) {
            try {
                store.addDownload(DownloadRecord(id, request.url, name, request.mimeType,
                    System.currentTimeMillis()))
            } catch (error: Exception) {
                val removed = runCatching { manager.remove(id) > 0 }.getOrDefault(false)
                throw IllegalStateException(if (removed)
                    context.getString(R.string.download_record_cancelled)
                else context.getString(R.string.download_record_failure), error)
            }
        }
        val cleanupFailed = retryOriginalId?.let { oldId ->
            runCatching {
                manager.remove(oldId)
                store.deleteDownload(oldId)
            }.isFailure
        } ?: false
        return id to cleanupFailed
    }

    fun safeFileName(request: DownloadRequest): String {
        val guessed = runCatching {
            URLUtil.guessFileName(request.url, request.contentDisposition, request.mimeType)
        }.getOrNull().orEmpty().ifBlank { "download" }
        return uniqueFileName(guessed)
    }

    fun uniqueFileName(original: String): String {
        val safe = original.map { if (it == '/' || it == '\\' || it.code < 32 ||
            Character.getType(it) == Character.FORMAT.toInt()) '_' else it }
            .joinToString("").trim('.').take(120).ifBlank { "download" }
        val dot = safe.lastIndexOf('.')
        // Random suffix prevents retries in the same millisecond from reusing an existing path.
        val suffix = "-" + UUID.randomUUID().toString().substring(0, 12)
        return if (dot > 0) safe.substring(0, dot).take(100) + suffix + safe.substring(dot)
        else safe.take(100) + suffix
    }
}
