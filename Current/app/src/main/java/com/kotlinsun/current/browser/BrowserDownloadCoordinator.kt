package com.kotlinsun.current.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.webkit.WebSettings
import com.kotlinsun.current.BlobDownloadTask
import com.kotlinsun.current.R
import com.kotlinsun.current.data.BrowserStore
import com.kotlinsun.current.data.LocalDownloadRecord
import com.kotlinsun.current.engine.DownloadRequest
import com.kotlinsun.current.engine.EngineSession
import com.kotlinsun.current.engine.TabMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages system and local downloads, PDF queries, and download-related UI state/dialogs.
 */
internal class BrowserDownloadCoordinator(
    private val context: Context,
    private val store: BrowserStore,
    private val scope: CoroutineScope,
    private val mutableUi: MutableStateFlow<BrowserUiState>,
    private val getHost: () -> BrowserHost?,
    private val isForeground: () -> Boolean,
    private val notice: (String) -> Unit,
    private val getTab: (String) -> BrowserTab?,
    private val getSession: (String) -> EngineSession?,
) {
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val downloadRepository = DownloadRepository(context, store, downloadManager)

    private var downloadRefreshVersion = 0
    private val retryingDownloadIds = mutableSetOf<Long>()
    private val pendingPdf = mutableMapOf<Long, Boolean>()
    private var pendingDownload: Pair<String, DownloadRequest>? = null
    private var activeBlob: Pair<String, BlobDownloadTask>? = null

    fun refreshDownloads() {
        val version = ++downloadRefreshVersion
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { downloadRepository.snapshot() } }
            if (version != downloadRefreshVersion) return@launch
            result.onSuccess { snapshot ->
                mutableUi.value = mutableUi.value.copy(
                    downloads = snapshot.managed,
                    localDownloads = snapshot.local,
                    missingLocalDownloadIds = snapshot.missingLocalIds,
                    inaccessibleLocalDownloadIds = snapshot.inaccessibleLocalIds,
                    downloadError = if (snapshot.managed.any { it.status == DOWNLOAD_STATUS_QUERY_ERROR } ||
                        snapshot.inaccessibleLocalIds.isNotEmpty())
                        context.getString(R.string.download_list_partial_error) else null
                )
            }.onFailure {
                mutableUi.value = mutableUi.value.copy(
                    downloadError = context.getString(R.string.download_list_error)
                )
            }
        }
    }

    fun openDownloadsFolder() {
        getHost()?.openDownloadsFolder()
    }

    fun requestDeleteDownload(item: DownloadItem) {
        mutableUi.value = mutableUi.value.copy(
            dialog = BrowserDialog.DeleteDownload(item.record.id, item.record.fileName)
        )
    }

    fun retryDownload(item: DownloadItem) {
        if (item.status != DownloadManager.STATUS_FAILED && item.status != DOWNLOAD_STATUS_MISSING) return
        if (DownloadSafety.isDangerous(item.record.fileName, item.record.mimeType)) {
            mutableUi.value = mutableUi.value.copy(
                dialog = BrowserDialog.RetryDangerousDownload(item.record.id, item.record.fileName)
            )
            return
        }
        retryDownloadApproved(item)
    }

    fun retryDownloadApproved(item: DownloadItem) {
        if (item.status != DownloadManager.STATUS_FAILED && item.status != DOWNLOAD_STATUS_MISSING) return
        val record = item.record
        if (!retryingDownloadIds.add(record.id)) return
        mutableUi.value = mutableUi.value.copy(retryingDownloadIds = retryingDownloadIds.toSet())
        val request = DownloadRequest(
            record.url,
            WebSettings.getDefaultUserAgent(context),
            null,
            record.mimeType,
            -1
        )
        enqueueDownload(
            TabMode.NORMAL,
            request,
            downloadRepository.uniqueFileName(record.fileName),
            retryOriginalId = record.id
        )
    }

    fun clearMissingDownloadRecords() {
        val ids = mutableUi.value.downloads.filter { it.status == DOWNLOAD_STATUS_MISSING }.map { it.record.id }
        val localIds = mutableUi.value.missingLocalDownloadIds
        if (ids.isEmpty() && localIds.isEmpty()) return
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    ids.forEach {
                        runCatching { downloadManager.remove(it) }
                        store.deleteDownload(it)
                    }
                    localIds.forEach { store.deleteLocalDownload(it) }
                }
            }
                .onSuccess { refreshDownloads() }
                .onFailure { notice(context.getString(R.string.download_missing_cleanup_error)) }
        }
    }

    fun requestDeleteLocalDownload(item: LocalDownloadRecord) {
        mutableUi.value = mutableUi.value.copy(
            dialog = BrowserDialog.DeleteLocalDownload(item.id, item.fileName)
        )
    }

    fun openDownload(id: Long) {
        val item = mutableUi.value.downloads.firstOrNull { it.record.id == id }
        if (item?.record?.mimeType?.substringBefore(';')?.equals("application/pdf", true) == true ||
            item?.record?.fileName?.endsWith(".pdf", true) == true) {
            getHost()?.openPdf(id, false)
        } else {
            getHost()?.openDownload(id)
        }
    }

    fun shareDownload(item: DownloadItem) {
        if (item.status != DownloadManager.STATUS_SUCCESSFUL) return
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    downloadManager.openDownloadedFile(item.record.id).use { }
                    downloadManager.getUriForDownloadedFile(item.record.id)
                }.getOrNull()
            }
            if (uri == null) notice(context.getString(R.string.download_file_not_found))
            else getHost()?.shareFile(uri, item.record.mimeType, item.record.fileName)
        }
    }

    fun shareLocalDownload(item: LocalDownloadRecord) {
        if (item.id in mutableUi.value.missingLocalDownloadIds) {
            notice(context.getString(R.string.download_file_not_found))
            return
        }
        if (item.id in mutableUi.value.inaccessibleLocalDownloadIds) {
            notice(context.getString(R.string.download_file_access_error))
            return
        }
        val uri = Uri.parse(item.contentUri)
        scope.launch {
            val exists = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true
                }.getOrDefault(false)
            }
            if (!exists) notice(context.getString(R.string.download_file_not_found))
            else getHost()?.shareFile(uri, item.mimeType, item.fileName)
        }
    }

    fun openLocalDownload(item: LocalDownloadRecord) {
        if (item.id in mutableUi.value.missingLocalDownloadIds) {
            notice(context.getString(R.string.download_file_not_found))
            return
        }
        if (item.id in mutableUi.value.inaccessibleLocalDownloadIds) {
            notice(context.getString(R.string.download_file_access_error))
            return
        }
        val mimeType = item.mimeType ?: if (item.fileName.endsWith(".pdf", true))
            "application/pdf" else null
        getHost()?.openLocalFile(Uri.parse(item.contentUri), mimeType, false)
    }

    fun onDownloadComplete(id: Long) {
        if (id < 0) return
        refreshDownloads()
        checkPendingPdf(id, finalEvent = true)
    }

    private fun checkPendingPdf(id: Long, finalEvent: Boolean = false) {
        if (id !in pendingPdf) return
        scope.launch {
            val status = withContext(Dispatchers.IO) {
                runCatching {
                    downloadManager.query(DownloadManager.Query().setFilterById(id))?.use {
                        if (it.moveToFirst()) it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        else null
                    }
                }.getOrNull()
            }
            if (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING ||
                status == DownloadManager.STATUS_PAUSED) return@launch
            if (status == null && !finalEvent) {
                delay(500)
                checkPendingPdf(id, finalEvent = true)
                return@launch
            }
            val privateMode = pendingPdf.remove(id) ?: return@launch
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                if (isForeground()) getHost()?.openPdf(id, privateMode)
            } else notice(context.getString(R.string.pdf_download_incomplete))
        }
    }

    private fun deleteDownload(id: Long) {
        val missingFile = mutableUi.value.downloads.firstOrNull { it.record.id == id }?.status == DOWNLOAD_STATUS_MISSING
        pendingPdf.remove(id)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (downloadManager.remove(id) <= 0 && !missingFile)
                        error("다운로드 파일을 삭제하지 못했습니다.")
                    store.deleteDownload(id)
                }
            }.onSuccess { refreshDownloads() }
                .onFailure {
                    refreshDownloads()
                    notice(context.getString(R.string.download_delete_error))
                }
        }
    }

    private fun deleteLocalDownload(id: Long) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val record = store.loadLocalDownloads().firstOrNull { it.id == id } ?: return@withContext
                    val uri = Uri.parse(record.contentUri)
                    val deleted = context.contentResolver.delete(uri, null, null)
                    if (deleted <= 0) {
                        val exists = try {
                            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true
                        } catch (_: java.io.FileNotFoundException) { false }
                        if (exists) error("파일을 삭제하지 못했습니다.")
                    }
                    store.deleteLocalDownload(id)
                }
            }.onSuccess { refreshDownloads() }
                .onFailure { notice(context.getString(R.string.local_download_delete_error)) }
        }
    }

    fun enqueueDownload(
        mode: TabMode,
        request: DownloadRequest,
        name: String,
        retryOriginalId: Long? = null
    ) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    downloadRepository.enqueue(mode, request, name, retryOriginalId)
                }
            }.onSuccess { (downloadId, cleanupFailed) ->
                retryOriginalId?.let { retryingDownloadIds.remove(it) }
                mutableUi.value = mutableUi.value.copy(retryingDownloadIds = retryingDownloadIds.toSet())
                if (cleanupFailed) notice(context.getString(R.string.download_retry_cleanup_error))
                if (request.mimeType?.substringBefore(';')?.equals("application/pdf", true) == true ||
                    name.endsWith(".pdf", true)) {
                    pendingPdf[downloadId] = mode == TabMode.PRIVATE
                    checkPendingPdf(downloadId)
                }
                refreshDownloads()
            }.onFailure {
                retryOriginalId?.let { id -> retryingDownloadIds.remove(id) }
                mutableUi.value = mutableUi.value.copy(retryingDownloadIds = retryingDownloadIds.toSet())
                notice(context.getString(
                    R.string.download_start_error,
                    it.message ?: context.getString(R.string.download_generic_error)
                ))
            }
        }
    }

    fun beginBlobDownload(
        id: String,
        request: DownloadRequest,
        name: String,
        dangerousAccepted: Boolean
    ) {
        val session = getSession(id) ?: return
        if (activeBlob != null) {
            notice(context.getString(R.string.blob_transfer_start_error))
            return
        }
        val privateMode = getTab(id)?.mode == TabMode.PRIVATE
        lateinit var transfer: BlobDownloadTask
        transfer = runCatching {
            BlobDownloadTask(
                context,
                store,
                session,
                request,
                name,
                dangerousAccepted,
                recordDownload = !privateMode
            ) { outcome ->
                if (activeBlob?.second === transfer) activeBlob = null
                when {
                    outcome.error != null -> notice(outcome.error)
                    outcome.uri != null -> {
                        refreshDownloads()
                        if (outcome.mimeType?.substringBefore(';')?.equals("application/pdf", true) == true &&
                            isForeground()) {
                            getHost()?.openLocalFile(outcome.uri, outcome.mimeType, privateMode)
                        } else {
                            notice(context.getString(R.string.local_download_saved))
                        }
                    }
                }
            }
        }.getOrElse {
            notice(context.getString(R.string.temp_file_create_error))
            return
        }
        activeBlob = id to transfer
        runCatching { transfer.start() }.onFailure {
            transfer.cancel()
            if (activeBlob?.second === transfer) activeBlob = null
            notice(context.getString(R.string.blob_transfer_start_error))
        }
    }

    fun handleDownloadRequest(sessionId: String, request: DownloadRequest): Boolean {
        if (!request.url.startsWith("http://", true) &&
            !request.url.startsWith("https://", true) &&
            !request.url.startsWith("blob:", true)) {
            notice(context.getString(R.string.download_type_unsupported))
            return false
        }
        val selected = getTab(sessionId) ?: return false
        pendingDownload = sessionId to request
        val name = downloadRepository.safeFileName(request)
        mutableUi.value = mutableUi.value.copy(
            dialog = BrowserDialog.Download(
                name,
                request.url,
                selected.mode == TabMode.PRIVATE,
                DownloadSafety.isDangerous(name, request.mimeType)
            )
        )
        return true
    }

    fun confirmDownloadDialog(
        dialog: BrowserDialog,
        dangerousAccepted: Boolean
    ): Boolean {
        when (dialog) {
            is BrowserDialog.Download -> {
                pendingDownload?.let { (id, request) ->
                    if (request.url.startsWith("blob:", true)) {
                        beginBlobDownload(id, request, dialog.fileName, dangerousAccepted)
                    } else {
                        getTab(id)?.let { enqueueDownload(it.mode, request, dialog.fileName) }
                    }
                }
                pendingDownload = null
                return true
            }
            is BrowserDialog.DeleteDownload -> {
                deleteDownload(dialog.id)
                return true
            }
            is BrowserDialog.RetryDangerousDownload -> {
                mutableUi.value.downloads
                    .firstOrNull { it.record.id == dialog.id }?.let(::retryDownloadApproved)
                return true
            }
            is BrowserDialog.DeleteLocalDownload -> {
                deleteLocalDownload(dialog.id)
                return true
            }
            else -> return false
        }
    }

    fun cancelDialog() {
        pendingDownload = null
    }

    fun cancelActiveBlob(forSessionId: String? = null) {
        if (forSessionId == null || activeBlob?.first == forSessionId) {
            activeBlob?.second?.cancel()
            activeBlob = null
        }
    }

    fun hasPendingDownload(sessionId: String): Boolean = pendingDownload?.first == sessionId

    fun clearPrivatePendingPdf() {
        pendingPdf.entries.removeAll { it.value }
    }
}
