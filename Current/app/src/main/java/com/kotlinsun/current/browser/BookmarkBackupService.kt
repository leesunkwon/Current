package com.kotlinsun.current.browser

import android.content.Context
import android.net.Uri
import com.kotlinsun.current.data.BookmarkFolderRecord
import com.kotlinsun.current.data.BookmarkRecord
import com.kotlinsun.current.data.BrowserStore
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** File I/O and duplicate-aware folder import, separate from screen state. */
internal class BookmarkBackupService(private val context: Context, private val store: BrowserStore) {
    suspend fun read(uri: Uri): ParsedBookmarkBackup = withContext(Dispatchers.IO) {
        val bytes = ByteArrayOutputStream()
        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open bookmark file")
        input.use { stream ->
            val chunk = ByteArray(8192)
            while (true) {
                val count = stream.read(chunk)
                if (count < 0) break
                if (bytes.size() + count > BookmarkBackup.MAX_BYTES) error("Bookmark file too large")
                bytes.write(chunk, 0, count)
            }
        }
        BookmarkBackup.parseDocument(bytes.toString(Charsets.UTF_8.name()))
    }

    suspend fun preview(parsed: ParsedBookmarkBackup): BookmarkImportPreview =
        withContext(Dispatchers.IO) {
            val existing = store.loadBookmarks().mapTo(HashSet()) { BookmarkBackup.key(it.url) }
            BookmarkImportPreview(parsed.entries.size,
                parsed.entries.count { BookmarkBackup.key(it.url) !in existing },
                parsed.entries.count { BookmarkBackup.key(it.url) in existing },
                parsed.folderPaths.size, parsed.entries.take(5).map { it.title })
        }

    suspend fun importBackup(parsed: ParsedBookmarkBackup): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val known = store.loadBookmarks().mapTo(HashSet()) { BookmarkBackup.key(it.url) }
        val folderIndex = store.loadBookmarkFolders().associateBy {
            it.parentId to it.name.lowercase(Locale.ROOT)
        }.toMutableMap()
        val now = System.currentTimeMillis()
        val additions = mutableListOf<BookmarkRecord>()
        val folders = mutableListOf<BookmarkFolderRecord>()
        fun resolveFolder(path: List<String>): String? {
            var parentId: String? = null
            path.forEach { name ->
                val key = parentId to name.lowercase(Locale.ROOT)
                val folder = folderIndex[key] ?: BookmarkFolderRecord(
                    UUID.randomUUID().toString(), name, parentId, now + folders.size).also {
                    folders.add(it)
                    folderIndex[key] = it
                }
                parentId = folder.id
            }
            return parentId
        }
        parsed.folderPaths.forEach { resolveFolder(it) }
        parsed.entries.forEach { entry ->
            if (known.add(BookmarkBackup.key(entry.url))) {
                additions.add(BookmarkRecord(UUID.randomUUID().toString(), entry.url,
                    entry.title, now + additions.size, folderId = resolveFolder(entry.folderPath)))
            }
        }
        store.importBookmarks(additions, folders)
        additions.size to folders.size
    }

    suspend fun write(uri: Uri) = withContext(Dispatchers.IO) {
        val records = store.loadBookmarks()
        val folders = store.loadBookmarkFolders()
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("Cannot create bookmark file")
        output.use { it.write(BookmarkBackup.export(records, folders).toByteArray(Charsets.UTF_8)) }
    }
}
