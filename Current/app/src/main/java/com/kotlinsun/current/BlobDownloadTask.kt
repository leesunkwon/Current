package com.kotlinsun.current

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.kotlinsun.current.browser.DownloadSafety
import com.kotlinsun.current.data.BrowserStore
import com.kotlinsun.current.data.LocalDownloadRecord
import com.kotlinsun.current.engine.BlobReceiver
import com.kotlinsun.current.engine.BlobTransfer
import com.kotlinsun.current.engine.DownloadRequest
import com.kotlinsun.current.engine.EngineSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** A confirmed blob transfer shared by browser tabs and standalone web apps. */
internal class BlobDownloadTask(
    private val context: Context,
    private val store: BrowserStore,
    private val session: EngineSession,
    private val request: DownloadRequest,
    private val name: String,
    private val acceptedDangerous: Boolean,
    private val recordDownload: Boolean = true,
    private val finished: (Outcome) -> Unit,
) {
    data class Outcome(val error: String? = null, val uri: Uri? = null,
        val mimeType: String? = null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cancelled = AtomicBoolean(false)
    private val delivered = AtomicBoolean(false)
    private val writeLock = Any()
    private val temporary = File.createTempFile("blob-", ".tmp",
        File(context.cacheDir, "blob_transfers").apply { mkdirs() })
    private var transfer: BlobTransfer? = null

    fun start(): Boolean {
        val reason = session.blobUnavailableReason(request.url)
        if (reason != null) { temporary.delete(); report(Outcome(error = reason)); return false }
        val receiver = object : BlobReceiver {
            override fun onChunk(bytes: ByteArray, acknowledge: (Boolean) -> Unit) {
                scope.launch(Dispatchers.IO) {
                    val saved = synchronized(writeLock) {
                        !cancelled.get() && runCatching {
                            FileOutputStream(temporary, true).use { it.write(bytes) }
                        }.isSuccess
                    }
                    withContext(Dispatchers.Main) { acknowledge(saved && !cancelled.get()) }
                }
            }
            override fun onComplete(mimeType: String?) {
                if (cancelled.get()) return
                scope.launch {
                    val result = withContext(Dispatchers.IO) { save(mimeType) }
                    temporary.delete()
                    if (!cancelled.get()) report(result)
                    scope.cancel()
                }
            }
            override fun onError(message: String) {
                if (cancelled.compareAndSet(false, true)) {
                    synchronized(writeLock) { temporary.delete() }
                    report(Outcome(error = message))
                    scope.cancel()
                }
            }
        }
        val started = runCatching { session.downloadBlob(request.url, 20L * 1024 * 1024, receiver) }
            .getOrNull()
        if (started == null) {
            cancel()
            report(Outcome(error = context.getString(R.string.blob_transfer_start_error)))
            return false
        }
        transfer = started
        return true
    }

    private suspend fun save(mimeType: String?): Outcome {
        val mime = mimeType?.takeIf { it.length <= 127 && it.contains('/') }
            ?: request.mimeType?.takeIf { it.length <= 127 && it.contains('/') }
            ?: if (name.endsWith(".pdf", true)) "application/pdf" else "application/octet-stream"
        if (!acceptedDangerous && DownloadSafety.isDangerous(name, mime))
            return Outcome(error = context.getString(R.string.blob_dangerous_blocked))
        var uri: Uri? = null
        var recordId: Long? = null
        var committed = false
        return try {
            if (cancelled.get()) return Outcome()
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val created = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error(context.getString(R.string.local_file_create_error))
            uri = created
            context.contentResolver.openOutputStream(created)?.use { output ->
                temporary.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled.get()) error("cancelled")
                        val size = input.read(buffer)
                        if (size < 0) break
                        output.write(buffer, 0, size)
                    }
                }
            } ?: error(context.getString(R.string.local_file_open_error))
            if (cancelled.get()) error("cancelled")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (context.contentResolver.update(created, values, null, null) <= 0)
                error(context.getString(R.string.local_file_save_incomplete))
            if (cancelled.get()) error("cancelled")
            if (recordDownload) recordId = store.addLocalDownload(LocalDownloadRecord(url = request.url,
                fileName = name, mimeType = mime, contentUri = created.toString(),
                createdAt = System.currentTimeMillis()))
            if (cancelled.get()) error("cancelled")
            committed = true
            Outcome(uri = created, mimeType = mime)
        } catch (error: Exception) {
            if (cancelled.get()) Outcome() else Outcome(error =
                context.getString(R.string.local_download_save_error,
                    error.message ?: context.getString(R.string.download_generic_error)))
        } finally {
            if (!committed) withContext(NonCancellable) {
                recordId?.let { runCatching { store.deleteLocalDownload(it) } }
                uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
        transfer?.cancel()
        synchronized(writeLock) { temporary.delete() }
        scope.cancel()
    }

    private fun report(outcome: Outcome) {
        if (delivered.compareAndSet(false, true)) finished(outcome)
    }
}
