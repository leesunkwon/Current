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
internal fun PrivacyScreen(controller: BrowserController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 12.dp)) {
        BrowserPanel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.delete_history), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.choose_delete_range),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            HistoryRange.entries.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { range ->
                        Surface(onClick = { controller.deleteHistory(range) },
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.background,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(stringResource(range.labelRes), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        BrowserPanel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.site_data_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.site_data_private_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Button(onClick = controller::requestClearSiteData,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = MaterialTheme.shapes.medium) {
                Text(stringResource(R.string.clear_all_site_data))
            }
        }
    }
}

@Composable
internal fun SiteInfoScreen(ui: BrowserUiState, controller: BrowserController) {
    val info = ui.siteInfo
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 12.dp)) {
        BrowserPanel(Modifier.fillMaxWidth()) {
            Text(info?.url?.let(AddressResolver::displayUrl) ?: stringResource(R.string.page_info_missing),
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(if (info?.url?.startsWith("https://") == true)
                R.string.https_address else R.string.http_other_address),
                color = if (info?.url?.startsWith("https://") == true)
                    currentSuccessColor() else currentErrorTextColor())
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.site_security_limited),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        BrowserPanel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.certificate_info),
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.certificate_subject,
                info?.subject ?: stringResource(R.string.certificate_unknown)))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.certificate_issuer,
                info?.issuer ?: stringResource(R.string.certificate_unknown)))
            info?.validFrom?.let {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.certificate_valid_from,
                    DateFormat.getDateTimeInstance().format(Date(it))))
            }
            info?.validTo?.let {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.certificate_valid_to,
                    DateFormat.getDateTimeInstance().format(Date(it))))
            }
        }
        Spacer(Modifier.height(14.dp))
        BrowserPanel(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.site_permissions), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.site_permissions_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val origin = info?.url?.let(SitePermissionCoordinator::canonicalOrigin)
            ui.savedSitePermissions.filter { ui.activeMode == TabMode.NORMAL && it.origin == origin }
                .forEach { record ->
                Spacer(Modifier.height(8.dp))
                Text(record.origin + " · " + permissionKindLabel(record.kind) + " · " +
                    stringResource(if (record.allowed) R.string.permission_saved_allow
                        else R.string.permission_saved_deny))
            }
            BrowserTextButton(onClick = { controller.showPage(BrowserPage.SITE_PERMISSIONS) },
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.site_permissions_manage))
            }
        }
        if (ui.trackingProtection && ui.activeMode == TabMode.NORMAL && info?.url != null) {
            Spacer(Modifier.height(14.dp))
            BrowserPanel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tracking_protection),
                    style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.tracker_blocked_count,
                    ui.selectedTab?.engine?.blockedTrackers ?: 0),
                    style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.tracker_worker_blocked_count,
                    ui.selectedTab?.engine?.blockedServiceWorkers ?: 0),
                    style = MaterialTheme.typography.bodyMedium)
                BrowserTextButton(onClick = controller::toggleTrackingException,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(if (controller.isCurrentSiteTrackingException())
                        R.string.tracking_exception_remove else R.string.tracking_exception_allow))
                }
            }
        }
    }
}

@Composable
internal fun SitePermissionsScreen(ui: BrowserUiState, controller: BrowserController) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (ui.savedSitePermissions.isEmpty()) item {
            BrowserPanel(Modifier.fillMaxWidth()) { Text(stringResource(R.string.site_permissions_empty)) }
        }
        items(ui.savedSitePermissions, key = { it.origin + "|" + it.kind }) { record ->
            val kindLabel = permissionKindLabel(record.kind)
            BrowserPanel(Modifier.fillMaxWidth()) {
                Text(record.origin, style = MaterialTheme.typography.titleSmall)
                Text(kindLabel + " · " + stringResource(if (record.allowed)
                    R.string.permission_saved_allow else R.string.permission_saved_deny),
                    style = MaterialTheme.typography.bodySmall)
                BrowserTextButton(onClick = { controller.deleteSitePermission(record) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = context.getString(R.string.site_permission_delete_label,
                            record.origin, kindLabel)
                    }) {
                    Text(stringResource(R.string.permission_forget))
                }
            }
        }
    }
}

@Composable
private fun permissionKindLabel(kind: String): String = stringResource(when (kind) {
    "CAMERA" -> R.string.permission_camera
    "MICROPHONE" -> R.string.permission_microphone
    "LOCATION" -> R.string.permission_location
    "PROTECTED_MEDIA" -> R.string.permission_protected_media
    else -> R.string.site_permissions
})

@Composable
internal fun BrowserDialogView(dialog: BrowserDialog, controller: BrowserController) {
    var prompt by remember(dialog) {
        mutableStateOf((dialog as? BrowserDialog.JavaScript)?.defaultValue.orEmpty())
    }
    var password by remember(dialog) { mutableStateOf("") }
    var dangerousAccepted by remember(dialog) { mutableStateOf(false) }
    val permissionNames = mapOf(
        com.kotlinsun.current.engine.WebPermissionKind.CAMERA to stringResource(R.string.permission_camera),
        com.kotlinsun.current.engine.WebPermissionKind.MICROPHONE to stringResource(R.string.permission_microphone),
        com.kotlinsun.current.engine.WebPermissionKind.LOCATION to stringResource(R.string.permission_location),
        com.kotlinsun.current.engine.WebPermissionKind.PROTECTED_MEDIA to
            stringResource(R.string.permission_protected_media),
    )
    val title = when (dialog) {
        is BrowserDialog.Notice -> stringResource(R.string.dialog_notice)
        is BrowserDialog.JavaScript -> dialog.origin
        is BrowserDialog.Permission -> stringResource(R.string.dialog_permission)
        is BrowserDialog.HttpAuthentication -> stringResource(R.string.http_auth_title)
        is BrowserDialog.Download -> stringResource(R.string.dialog_download)
        is BrowserDialog.DeleteDownload, is BrowserDialog.DeleteLocalDownload ->
            stringResource(R.string.dialog_download_delete)
        is BrowserDialog.RetryDangerousDownload -> stringResource(R.string.download_dangerous_retry_title)
        is BrowserDialog.BackHistory -> stringResource(R.string.back_history)
        is BrowserDialog.HttpNavigation -> stringResource(R.string.dialog_http)
        BrowserDialog.ClearSiteData -> stringResource(R.string.dialog_site_data)
    }
    AlertDialog(onDismissRequest = controller::cancelDialog,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = { Text(title) },
        text = {
            when (dialog) {
                is BrowserDialog.Notice -> Text(dialog.text)
                is BrowserDialog.JavaScript -> Column {
                    Text(dialog.message)
                    if (dialog.kind == JavaScriptDialogKind.PROMPT) OutlinedTextField(
                        prompt, { prompt = it },
                        label = { Text(stringResource(R.string.dialog_response)) },
                        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                }
                is BrowserDialog.Permission -> Column {
                    Text(stringResource(R.string.permission_request,
                        dialog.origin, dialog.kinds.joinToString { permissionNames[it].orEmpty() }))
                    if (dialog.canRemember) {
                        Spacer(Modifier.height(12.dp))
                        BrowserTextButton(onClick = { controller.approveSitePermission(true) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.permission_always_allow))
                        }
                        BrowserTextButton(onClick = { controller.denySitePermission(true) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.permission_always_deny))
                        }
                    }
                }
                is BrowserDialog.HttpAuthentication -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.http_auth_request, dialog.host, dialog.realm))
                    OutlinedTextField(prompt, { prompt = it }, singleLine = true,
                        label = { Text(stringResource(R.string.http_auth_username)) },
                        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                    OutlinedTextField(password, { password = it }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text(stringResource(R.string.http_auth_password)) },
                        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                }
                is BrowserDialog.Download -> Column {
                    Text(dialog.fileName + "\n" + dialog.url +
                        "\n" + stringResource(R.string.download_location_notice) +
                        if (dialog.privateMode) "\n" + stringResource(R.string.private_download_notice) else "")
                    if (dialog.dangerous) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dangerousAccepted, onCheckedChange = { dangerousAccepted = it })
                        Text(stringResource(R.string.download_dangerous_warning),
                            color = currentErrorTextColor())
                    }
                }
                is BrowserDialog.DeleteDownload -> Text(stringResource(R.string.delete_download_notice,
                    dialog.fileName))
                is BrowserDialog.RetryDangerousDownload -> Column {
                    Text(dialog.fileName)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dangerousAccepted, onCheckedChange = { dangerousAccepted = it })
                        Text(stringResource(R.string.download_dangerous_warning),
                            color = currentErrorTextColor())
                    }
                }
                is BrowserDialog.DeleteLocalDownload -> Text(stringResource(
                    R.string.delete_local_download_notice, dialog.fileName))
                is BrowserDialog.HttpNavigation -> Text(stringResource(R.string.http_navigation_notice,
                    AddressResolver.displayUrl(dialog.url)))
                is BrowserDialog.BackHistory -> Column(Modifier.heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())) {
                    dialog.entries.forEach { entry ->
                        BrowserTextButton(onClick = {
                            controller.navigateBackHistory(entry.offset)
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                BrowserDialog.ClearSiteData -> Text(stringResource(R.string.clear_site_data_notice))
            }
        },
        confirmButton = { Button(onClick = {
            if (dialog is BrowserDialog.Permission) controller.approveSitePermission(false)
            else controller.confirmDialog(if ((dialog is BrowserDialog.JavaScript &&
                dialog.kind == JavaScriptDialogKind.PROMPT) || dialog is BrowserDialog.HttpAuthentication)
                prompt else null, if (dialog is BrowserDialog.HttpAuthentication) password else null,
                dangerousAccepted)
        }, enabled = when (dialog) {
            is BrowserDialog.Download -> !dialog.dangerous || dangerousAccepted
            is BrowserDialog.RetryDangerousDownload -> dangerousAccepted
            is BrowserDialog.HttpAuthentication -> prompt.isNotBlank()
            else -> true
        },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.heightIn(min = 48.dp)) { Text(when (dialog) {
            is BrowserDialog.Notice -> stringResource(R.string.dialog_confirm)
            is BrowserDialog.Permission -> stringResource(R.string.permission_allow_once)
            is BrowserDialog.HttpAuthentication -> stringResource(R.string.http_auth_sign_in)
            is BrowserDialog.Download -> stringResource(R.string.downloads)
            is BrowserDialog.DeleteDownload, is BrowserDialog.DeleteLocalDownload ->
                stringResource(R.string.delete)
            is BrowserDialog.RetryDangerousDownload -> stringResource(R.string.download_retry)
            is BrowserDialog.BackHistory -> stringResource(R.string.close)
            BrowserDialog.ClearSiteData -> stringResource(R.string.dialog_delete_all)
            is BrowserDialog.HttpNavigation -> stringResource(R.string.dialog_continue)
            is BrowserDialog.JavaScript -> stringResource(R.string.dialog_confirm)
        }) } },
        dismissButton = {
            if (dialog !is BrowserDialog.Notice && !(dialog is BrowserDialog.JavaScript &&
                    dialog.kind == JavaScriptDialogKind.ALERT))
                BrowserTextButton(onClick = {
                    if (dialog is BrowserDialog.Permission) controller.denySitePermission(false)
                    else controller.cancelDialog()
                },
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.cancel))
                }
        })
}
