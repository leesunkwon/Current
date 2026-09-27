package com.kotlinsun.current.browser

import android.app.DownloadManager
import android.graphics.BitmapFactory
import android.text.format.Formatter
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.viewinterop.AndroidView
import com.kotlinsun.current.data.BookmarkRecord
import com.kotlinsun.current.R
import com.kotlinsun.current.engine.JavaScriptDialogKind
import com.kotlinsun.current.engine.TabMode
import com.kotlinsun.current.ui.theme.currentSuccessColor
import com.kotlinsun.current.ui.theme.currentWarningColor
import com.kotlinsun.current.ui.theme.currentAccentTextColor
import com.kotlinsun.current.ui.theme.currentErrorTextColor
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HistoryScreen(ui: BrowserUiState, controller: BrowserController) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val searchDescription = stringResource(R.string.search_history)
    val filtered = ui.history.filter { query.isBlank() ||
        it.url.contains(query, true) || it.title.contains(query, true) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it },
            placeholder = { Text(stringResource(R.string.search_history)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true, shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = searchDescription })
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (filtered.isEmpty()) item {
                BrowserPanel(Modifier.fillMaxWidth()) {
                    Text(stringResource(if (query.isBlank())
                        R.string.history_empty else R.string.search_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            var previousDay = ""
            filtered.forEach { visit ->
                val day = DateFormat.getDateInstance().format(Date(visit.visitedAt))
                if (day != previousDay) {
                    item(key = "day-" + day) {
                        Text(day, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                            style = MaterialTheme.typography.titleMedium)
                    }
                    previousDay = day
                }
                item(key = visit.id) {
                    val favicon = remember(visit.favicon) {
                        visit.favicon?.let { bytes -> runCatching {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }.getOrNull() }
                    }
                    Surface(shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (favicon != null) Image(favicon, contentDescription = null,
                                modifier = Modifier.size(28.dp).clip(MaterialTheme.shapes.extraSmall))
                            else Icon(Icons.Filled.Language, contentDescription = null,
                                modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).heightIn(min = 56.dp)
                                .clickable(onClickLabel = context.getString(R.string.visit_open,
                                    visit.title)) {
                                    controller.openSavedUrl(visit.url)
                                }.padding(start = 12.dp),
                                verticalArrangement = Arrangement.Center) {
                                Text(visit.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(AddressResolver.displayUrl(visit.url), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { controller.deleteVisit(visit.id) },
                                modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.visit_delete,
                                        visit.title))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BookmarkScreen(ui: BrowserUiState, controller: BrowserController) {
    val context = LocalContext.current
    var currentFolderId by remember { mutableStateOf<String?>(null) }
    var folderDialogId by remember { mutableStateOf<String?>(null) }
    var folderDialogOpen by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<BookmarkRecord?>(null) }
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var editFolderId by remember { mutableStateOf<String?>(null) }
    val currentFolder = ui.bookmarkFolders.firstOrNull { it.id == currentFolderId }
    LaunchedEffect(currentFolderId, ui.bookmarkFolders) {
        if (currentFolderId != null && currentFolder == null) currentFolderId = null
    }
    val visibleFolders = ui.bookmarkFolders.filter { it.parentId == currentFolderId }
    val visibleBookmarks = ui.bookmarks.filter { it.folderId == currentFolderId }
    fun folderPath(id: String): String {
        val names = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var cursor: String? = id
        while (cursor != null && seen.add(cursor) && names.size < 16) {
            val folder = ui.bookmarkFolders.firstOrNull { it.id == cursor } ?: break
            names.add(folder.name)
            cursor = folder.parentId
        }
        return names.asReversed().joinToString(" / ")
    }
    if (folderDialogOpen) AlertDialog(onDismissRequest = { folderDialogOpen = false },
        title = { Text(stringResource(if (folderDialogId == null)
            R.string.bookmark_folder_new else R.string.bookmark_folder_rename)) },
        text = { OutlinedTextField(folderName, { folderName = it }, singleLine = true,
            label = { Text(stringResource(R.string.bookmark_folder_name)) },
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) },
        confirmButton = { Button(onClick = {
            val id = folderDialogId
            if (id == null) controller.createBookmarkFolder(folderName, currentFolderId)
            else controller.renameBookmarkFolder(id, folderName)
            folderDialogOpen = false
        }, enabled = folderName.isNotBlank(), shape = MaterialTheme.shapes.medium,
            modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.save))
        } },
        dismissButton = { BrowserTextButton(onClick = { folderDialogOpen = false },
            modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.cancel))
        } })
    ui.bookmarkImportPreview?.let { preview ->
        AlertDialog(onDismissRequest = controller::cancelBookmarkImport,
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.bookmark_import_preview)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.bookmark_import_counts, preview.total,
                    preview.newCount, preview.duplicateCount))
                if (preview.folderCount > 0)
                    Text(stringResource(R.string.bookmark_import_folder_count, preview.folderCount))
                preview.sample.forEach { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            } },
            confirmButton = { Button(onClick = controller::confirmBookmarkImport,
                enabled = preview.newCount > 0 || preview.folderCount > 0,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.bookmark_import))
            } },
            dismissButton = { BrowserTextButton(onClick = controller::cancelBookmarkImport,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cancel))
            } })
    }
    editing?.let { item ->
        AlertDialog(onDismissRequest = { editing = null },
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            title = { Text(stringResource(R.string.edit_bookmark)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it },
                    label = { Text(stringResource(R.string.title)) },
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                OutlinedTextField(url, { url = it },
                    label = { Text(stringResource(R.string.address)) },
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                Text(stringResource(R.string.bookmark_folder_name))
                ChoicePill(stringResource(R.string.bookmark_root_folder), editFolderId == null,
                    { editFolderId = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                ui.bookmarkFolders.forEach { folder ->
                    ChoicePill(folderPath(folder.id), editFolderId == folder.id,
                        { editFolderId = folder.id },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                }
            } },
            confirmButton = { Button(onClick = {
                controller.updateBookmark(item.id, title, url, editFolderId)
                editing = null
            }, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.save)) } },
            dismissButton = { BrowserTextButton(onClick = { editing = null },
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
    }
    Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BrowserTextButton(onClick = controller::beginBookmarkImport,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.bookmark_import))
        }
        BrowserTextButton(onClick = controller::beginBookmarkExport,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text(stringResource(R.string.bookmark_export))
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (currentFolder != null) BrowserTextButton(onClick = {
            currentFolderId = currentFolder.parentId
        }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.bookmark_folder_back))
        }
        Text(currentFolder?.name ?: stringResource(R.string.bookmark_root_folder),
            modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        BrowserTextButton(onClick = {
            folderDialogId = null
            folderName = ""
            folderDialogOpen = true
        }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.bookmark_folder_new))
        }
    }
    if (currentFolder != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End) {
        BrowserTextButton(onClick = {
            folderDialogId = currentFolder.id
            folderName = currentFolder.name
            folderDialogOpen = true
        }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.edit))
        }
        BrowserTextButton(onClick = {
            controller.deleteBookmarkFolder(currentFolder.id)
            currentFolderId = currentFolder.parentId
        }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.bookmark_folder_delete))
        }
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (visibleBookmarks.isEmpty() && visibleFolders.isEmpty()) item {
            BrowserPanel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bookmarks_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(visibleFolders, key = { "folder-" + it.id }) { folder ->
            Surface(onClick = { currentFolderId = folder.id },
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                    Text(folder.name, modifier = Modifier.padding(start = 12.dp),
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        items(visibleBookmarks, key = { it.id }) { bookmark ->
            Surface(shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.BookmarkBorder, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f).heightIn(min = 58.dp)
                            .clickable(onClickLabel = context.getString(R.string.bookmark_open,
                                bookmark.title)) {
                                controller.openSavedUrl(bookmark.url)
                            }.padding(start = 12.dp), verticalArrangement = Arrangement.Center) {
                            Text(bookmark.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(AddressResolver.displayUrl(bookmark.url), maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.End) {
                        BrowserTextButton(onClick = {
                            editing = bookmark
                            title = bookmark.title
                            url = bookmark.url
                            editFolderId = bookmark.folderId
                        }, modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = context.getString(R.string.bookmark_edit,
                                bookmark.title)
                        }) { Text(stringResource(R.string.edit)) }
                        BrowserTextButton(onClick = { controller.toggleBookmarkHomePin(bookmark.id) },
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(if (bookmark.pinnedToHome)
                                R.string.bookmark_home_unpin else R.string.bookmark_home_pin))
                        }
                        IconButton(onClick = { controller.deleteBookmark(bookmark.id) },
                            modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Close,
                                contentDescription = stringResource(R.string.bookmark_delete,
                                    bookmark.title))
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
internal fun DownloadScreen(ui: BrowserUiState, controller: BrowserController) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(DownloadFilter.ALL) }
    val matchingDownloads = ui.downloads.filter { item ->
        (query.isBlank() || item.record.fileName.contains(query, true) ||
            item.record.url.contains(query, true)) && when (filter) {
            DownloadFilter.ALL -> true
            DownloadFilter.ACTIVE -> item.status == DownloadManager.STATUS_PENDING ||
                item.status == DownloadManager.STATUS_RUNNING || item.status == DownloadManager.STATUS_PAUSED
            DownloadFilter.COMPLETE -> item.status == DownloadManager.STATUS_SUCCESSFUL
            DownloadFilter.FAILED -> item.status == DownloadManager.STATUS_FAILED ||
                item.status == DOWNLOAD_STATUS_MISSING || item.status == DOWNLOAD_STATUS_QUERY_ERROR
        }
    }
    val matchingLocal = ui.localDownloads.filter { item ->
        (query.isBlank() || item.fileName.contains(query, true) || item.url.contains(query, true)) &&
            when (filter) {
                DownloadFilter.ALL -> true
                DownloadFilter.ACTIVE -> false
                DownloadFilter.COMPLETE -> item.id !in ui.missingLocalDownloadIds &&
                    item.id !in ui.inaccessibleLocalDownloadIds
                DownloadFilter.FAILED -> item.id in ui.missingLocalDownloadIds ||
                    item.id in ui.inaccessibleLocalDownloadIds
            }
    }
    val compactActions = LocalConfiguration.current.let {
        it.screenWidthDp < 360 || it.fontScale >= 1.4f
    }
    val actionModifier = if (compactActions) Modifier.fillMaxWidth() else Modifier
    val hasActiveDownload = ui.downloads.any { it.status == DownloadManager.STATUS_PENDING ||
        it.status == DownloadManager.STATUS_RUNNING || it.status == DownloadManager.STATUS_PAUSED }
    LaunchedEffect(hasActiveDownload) {
        controller.refreshDownloads()
        while (hasActiveDownload) {
            delay(2000)
            controller.refreshDownloads()
        }
    }
    Column(Modifier.fillMaxSize()) {
    OutlinedTextField(query, { query = it },
        placeholder = { Text(stringResource(R.string.download_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { contentDescription = context.getString(R.string.download_search) })
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DownloadFilter.entries.forEach { choice ->
            ChoicePill(stringResource(choice.labelRes), filter == choice,
                { filter = choice }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
    BrowserTextButton(onClick = controller::openDownloadsFolder,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.downloads_open_folder))
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ui.downloadError?.let { error -> item {
            BrowserPanel(Modifier.fillMaxWidth()) {
                Text(error, color = currentErrorTextColor())
                BrowserTextButton(onClick = controller::refreshDownloads,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.download_retry))
                }
            }
        } }
        if (ui.downloads.any { it.status == DOWNLOAD_STATUS_MISSING } ||
            ui.missingLocalDownloadIds.isNotEmpty()) item {
            BrowserTextButton(onClick = controller::clearMissingDownloadRecords,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.download_missing_cleanup))
            }
        }
        if (ui.downloadError == null && matchingDownloads.isEmpty() && matchingLocal.isEmpty()) {
            item { BrowserPanel(Modifier.fillMaxWidth()) {
                Text(stringResource(if (query.isBlank() && filter == DownloadFilter.ALL)
                    R.string.download_history_empty else R.string.search_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
        items(matchingDownloads, key = { it.record.id }) { item ->
            val label = when (item.status) {
                DownloadManager.STATUS_PENDING -> stringResource(R.string.download_pending)
                DownloadManager.STATUS_RUNNING -> stringResource(R.string.download_running)
                DownloadManager.STATUS_PAUSED -> stringResource(R.string.download_paused,
                    downloadPauseReason(item.reason))
                DownloadManager.STATUS_SUCCESSFUL -> stringResource(R.string.download_complete)
                DownloadManager.STATUS_FAILED -> stringResource(R.string.download_failed,
                    downloadFailureReason(item.reason))
                DOWNLOAD_STATUS_QUERY_ERROR -> stringResource(R.string.download_status_unavailable)
                else -> stringResource(R.string.download_missing)
            }
            BrowserPanel(Modifier.fillMaxWidth()) {
                Text(item.record.fileName, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                val progress = if (item.totalBytes > 0)
                    (item.downloadedBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f) else null
                Text(label + (progress?.let { " · ${(it * 100).toInt()}%" } ?: ""),
                    color = when (item.status) {
                        DownloadManager.STATUS_SUCCESSFUL -> currentSuccessColor()
                        DownloadManager.STATUS_FAILED, DOWNLOAD_STATUS_MISSING ->
                            currentErrorTextColor()
                        DownloadManager.STATUS_PAUSED -> currentWarningColor()
                        DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING ->
                            currentAccentTextColor()
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.bodySmall)
                if (item.status == DownloadManager.STATUS_FAILED || item.status == DOWNLOAD_STATUS_MISSING) {
                    Text(downloadRetryGuidance(item), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.status == DownloadManager.STATUS_RUNNING ||
                    item.status == DownloadManager.STATUS_PAUSED) {
                    Text(Formatter.formatShortFileSize(context, item.downloadedBytes.coerceAtLeast(0)) +
                        if (item.totalBytes > 0) " / " + Formatter.formatShortFileSize(context, item.totalBytes)
                        else "",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
                if (progress != null && item.status == DownloadManager.STATUS_RUNNING) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant)
                }
                DownloadActionContainer(compactActions) {
                    if (item.status == DownloadManager.STATUS_FAILED || item.status == DOWNLOAD_STATUS_MISSING) {
                        BrowserTextButton(onClick = { controller.retryDownload(item) },
                            enabled = item.record.id !in ui.retryingDownloadIds,
                            modifier = actionModifier.heightIn(min = 48.dp).semantics {
                                contentDescription = context.getString(R.string.download_retry_item,
                                    item.record.fileName)
                            }) { Text(stringResource(R.string.download_retry)) }
                    }
                    if (item.status == DownloadManager.STATUS_SUCCESSFUL) {
                        BrowserTextButton(onClick = { controller.openDownload(item.record.id) },
                            modifier = actionModifier.heightIn(min = 48.dp).semantics {
                                contentDescription = context.getString(R.string.file_open,
                                    item.record.fileName)
                            }) { Text(stringResource(R.string.open)) }
                        BrowserTextButton(onClick = { controller.shareDownload(item) },
                            modifier = actionModifier.heightIn(min = 48.dp).semantics {
                                contentDescription = context.getString(R.string.file_share, item.record.fileName)
                            }) { Text(stringResource(R.string.share)) }
                    }
                    BrowserTextButton(onClick = { controller.requestDeleteDownload(item) },
                        modifier = actionModifier.heightIn(min = 48.dp).semantics {
                            contentDescription = context.getString(
                                if (item.status == DownloadManager.STATUS_PENDING ||
                                    item.status == DownloadManager.STATUS_RUNNING ||
                                    item.status == DownloadManager.STATUS_PAUSED)
                                    R.string.file_download_cancel else R.string.file_delete,
                                item.record.fileName)
                        }) {
                        Text(stringResource(if (item.status == DownloadManager.STATUS_PENDING ||
                            item.status == DownloadManager.STATUS_RUNNING ||
                            item.status == DownloadManager.STATUS_PAUSED)
                            R.string.cancel else R.string.delete))
                    }
                }
            }
        }
        items(matchingLocal, key = { "local-" + it.id }) { item ->
            val missing = item.id in ui.missingLocalDownloadIds
            val inaccessible = item.id in ui.inaccessibleLocalDownloadIds
            BrowserPanel(Modifier.fillMaxWidth()) {
                Text(item.fileName, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text(stringResource(when {
                    missing -> R.string.download_missing
                    inaccessible -> R.string.download_file_access_error
                    else -> R.string.download_complete
                }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                DownloadActionContainer(compactActions) {
                    if (!missing && !inaccessible) BrowserTextButton(onClick = { controller.openLocalDownload(item) },
                        modifier = actionModifier.heightIn(min = 48.dp).semantics {
                            contentDescription = context.getString(R.string.file_open, item.fileName)
                        }) { Text(stringResource(R.string.open)) }
                    if (!missing && !inaccessible) BrowserTextButton(onClick = { controller.shareLocalDownload(item) },
                        modifier = actionModifier.heightIn(min = 48.dp).semantics {
                            contentDescription = context.getString(R.string.file_share, item.fileName)
                        }) { Text(stringResource(R.string.share)) }
                    BrowserTextButton(onClick = { controller.requestDeleteLocalDownload(item) },
                        modifier = actionModifier.heightIn(min = 48.dp).semantics {
                            contentDescription = context.getString(R.string.file_delete, item.fileName)
                        }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }
    }
}

private enum class DownloadFilter(val labelRes: Int) {
    ALL(R.string.download_filter_all), ACTIVE(R.string.download_filter_active),
    COMPLETE(R.string.download_filter_complete), FAILED(R.string.download_filter_failed),
}

@Composable
private fun DownloadActionContainer(compact: Boolean, content: @Composable () -> Unit) {
    if (compact) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    } else {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}

@Composable
private fun downloadFailureReason(reason: Int): String = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> stringResource(R.string.download_missing_space)
    DownloadManager.ERROR_FILE_ERROR -> stringResource(R.string.download_file_error)
    DownloadManager.ERROR_HTTP_DATA_ERROR -> stringResource(R.string.download_network_data_error)
    DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> stringResource(R.string.download_http_error)
    DownloadManager.ERROR_CANNOT_RESUME -> stringResource(R.string.download_cannot_resume)
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> stringResource(R.string.download_device_missing)
    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> stringResource(R.string.download_redirect_error)
    DownloadManager.ERROR_FILE_ALREADY_EXISTS -> stringResource(R.string.download_file_exists)
    else -> stringResource(R.string.download_unknown_error, reason)
}

@Composable
private fun downloadRetryGuidance(item: DownloadItem): String = when {
    item.status == DOWNLOAD_STATUS_MISSING -> stringResource(R.string.download_missing_guidance)
    item.reason == DownloadManager.ERROR_INSUFFICIENT_SPACE ->
        stringResource(R.string.download_space_guidance)
    item.reason == DownloadManager.ERROR_FILE_ERROR ||
        item.reason == DownloadManager.ERROR_DEVICE_NOT_FOUND ->
        stringResource(R.string.download_storage_guidance)
    item.reason == DownloadManager.ERROR_HTTP_DATA_ERROR ||
        item.reason == DownloadManager.ERROR_CANNOT_RESUME ->
        stringResource(R.string.download_network_guidance)
    item.reason == DownloadManager.ERROR_UNHANDLED_HTTP_CODE ||
        item.reason == DownloadManager.ERROR_TOO_MANY_REDIRECTS ->
        stringResource(R.string.download_server_guidance)
    else -> stringResource(R.string.download_general_guidance)
}

@Composable
private fun downloadPauseReason(reason: Int): String = when (reason) {
    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> stringResource(R.string.download_wait_network)
    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> stringResource(R.string.download_wait_wifi)
    DownloadManager.PAUSED_WAITING_TO_RETRY -> stringResource(R.string.download_wait_retry)
    else -> stringResource(R.string.download_wait_other)
}
