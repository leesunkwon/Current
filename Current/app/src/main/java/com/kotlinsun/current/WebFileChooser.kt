package com.kotlinsun.current

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import com.kotlinsun.current.engine.FileSelectionRequest
import java.util.Locale

/** The document picker contract shared by browser tabs and standalone web apps. */
internal object WebFileChooser {
    fun mimeTypes(request: FileSelectionRequest): List<String> = request.acceptTypes
        .flatMap { it.split(',') }
        .mapNotNull { raw ->
            val value = raw.trim().lowercase(Locale.ROOT)
            when {
                value.startsWith('.') -> MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(value.drop(1))
                value.contains('/') && !value.contains(' ') -> value
                else -> null
            }
        }.distinct()

    fun cameraEligible(request: FileSelectionRequest): Boolean = request.capture ||
        mimeTypes(request).any { it.startsWith("image/") || it.startsWith("video/") }

    fun picker(request: FileSelectionRequest, types: List<String>): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = types.singleOrNull() ?: "*/*"
            if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request.allowMultiple)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun selectedUris(context: Context, data: Intent?, cameraUri: Uri?,
        request: FileSelectionRequest?): Array<Uri>? {
        if (request == null) return null
        val selected = buildList {
            data?.data?.let(::add)
            data?.clipData?.let { clips ->
                for (index in 0 until clips.itemCount) add(clips.getItemAt(index).uri)
            }
            if (isEmpty()) cameraUri?.let(::add)
        }.distinct().filter { uri ->
            uri.scheme == "content" && runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true
            }.getOrDefault(false)
        }.take(if (request.allowMultiple) 20 else 1)
        return selected.takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}
