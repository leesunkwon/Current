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
fun BrowserScreen(controller: BrowserController) {
    val ui by controller.ui.collectAsState()
    val selected = ui.selectedTab
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var address by remember { mutableStateOf(TextFieldValue("")) }
    var editing by remember { mutableStateOf(false) }
    var suggestionsVisible by remember { mutableStateOf(false) }
    var invalidAddress by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val tabClosedMessage = stringResource(R.string.tab_closed)
    val undoLabel = stringResource(R.string.undo)

    LaunchedEffect(ui.page, ui.selectedId, ui.activeMode, ui.showOnboarding) {
        menu = false
        suggestionsVisible = false
    }

    LaunchedEffect(selected?.id, selected?.url, editing) {
        if (!editing) address = TextFieldValue(selected?.url?.let(AddressResolver::displayUrl).orEmpty())
    }

    LaunchedEffect(ui.lastClosedTabId) {
        val latestId = ui.lastClosedTabId ?: return@LaunchedEffect
        if (snackbar.showSnackbar(tabClosedMessage, undoLabel, duration = SnackbarDuration.Short) ==
            androidx.compose.material3.SnackbarResult.ActionPerformed) controller.reopenClosedTab(latestId)
    }

    if (ui.showOnboarding) {
        WelcomeScreen(controller::completeOnboarding)
        return
    }

    if (ui.activeMode == TabMode.PRIVATE && ui.privateLocked) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.private_unlock_title),
                style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.private_unlock_message), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = controller::requestPrivateUnlock,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.private_unlock_retry))
            }
            BrowserTextButton(onClick = controller::leaveLockedPrivate,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.private_unlock_leave))
            }
        }
        return
    }

    val dismissSuggestions: () -> Unit = {
        suggestionsVisible = false
        controller.clearSuggestions()
    }
    val onAddressChange: (TextFieldValue) -> Unit = { next ->
        val textChanged = next.text != address.text
        val compositionFinished = address.composition != null && next.composition == null
        val typedOneCharacter = next.text.length == address.text.length + 1 &&
            next.text.startsWith(address.text) && address.selection.collapsed
        val completed = if (textChanged && typedOneCharacter && !compositionFinished &&
            address.composition == null && next.composition == null &&
            next.selection.collapsed &&
            next.selection.end == next.text.length && ui.activeMode == TabMode.NORMAL)
            controller.inlineCompletion(next.text) else null
        address = if (completed != null) TextFieldValue(completed,
            selection = TextRange(next.text.length, completed.length)) else next
        invalidAddress = false
        if (next.composition != null) {
            dismissSuggestions()
        } else if (textChanged || compositionFinished) {
            suggestionsVisible = next.text.trim().length >= 2
            if (suggestionsVisible) controller.updateSuggestions(next.text)
            else controller.clearSuggestions()
        }
    }
    val onAddressFocus: (Boolean) -> Unit = { focused ->
        if (focused && !editing) {
            dismissSuggestions()
            val raw = selected?.url.orEmpty()
            address = TextFieldValue(raw, selection = TextRange(0, raw.length))
        }
        if (!focused) dismissSuggestions()
        editing = focused
    }
    val submitAddress: () -> Unit = {
        invalidAddress = !controller.submitAddress(address.text)
        dismissSuggestions()
        if (!invalidAddress) {
            focus.clearFocus()
            editing = false
        }
    }
    val useSuggestion: (AddressSuggestion) -> Unit = { suggestion ->
        suggestionsVisible = false
        controller.useSuggestion(suggestion)
        focus.clearFocus()
        editing = false
    }
    BackHandler(enabled = editing && ui.page == BrowserPage.WEB && ui.dialog == null &&
        ui.linkTarget == null && ui.pendingExternalUrl == null) {
        if (imeVisible) {
            keyboard?.hide()
            dismissSuggestions()
        } else {
            focus.clearFocus()
            dismissSuggestions()
        }
    }

    ui.dialog?.let { BrowserDialogView(it, controller) }
    ui.linkTarget?.let { target ->
        AlertDialog(onDismissRequest = controller::dismissLinkMenu,
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            title = { Text(stringResource(if (target.imageUrl != null)
                R.string.image_and_link else R.string.link)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(target.url, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    BrowserTextButton(onClick = { controller.openLink(true) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.open_new_tab))
                    }
                    BrowserTextButton(onClick = { controller.openLink(false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.open_background_tab))
                    }
                    if (ui.activeMode == TabMode.NORMAL && ui.privateAvailable) {
                        BrowserTextButton(onClick = { controller.openLink(true, true) },
                            modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.open_private_tab))
                        }
                    }
                    BrowserTextButton(onClick = controller::copyLink, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.copy_link))
                    }
                    BrowserTextButton(onClick = controller::shareLink, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.share_link))
                    }
                    if (target.imageUrl != null) BrowserTextButton(onClick = controller::saveImage,
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.save_image))
                    }
                }
            },
            confirmButton = { BrowserTextButton(onClick = controller::dismissLinkMenu,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.close))
            } })
    }
    ui.pendingExternalUrl?.let { url ->
        AlertDialog(onDismissRequest = controller::dismissExternal,
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            title = { Text(stringResource(R.string.open_external_app)) },
            text = { Text(url, maxLines = 6, overflow = TextOverflow.Ellipsis) },
            confirmButton = { Button(onClick = controller::confirmExternal,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.open)) } },
            dismissButton = { BrowserTextButton(onClick = controller::dismissExternal,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
    }

    ui.fullScreenView?.let { fullView ->
        AndroidView(factory = {
            (fullView.parent as? ViewGroup)?.removeView(fullView)
            fullView
        }, modifier = Modifier.fillMaxSize())
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                if (ui.page == BrowserPage.WEB) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (selected?.url == null) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                if (ui.activeMode == TabMode.PRIVATE) {
                                    Icon(Icons.Filled.Lock, contentDescription = null,
                                        modifier = Modifier.size(28.dp))
                                } else CurrentBrandIcon(Modifier.size(28.dp), MaterialTheme.shapes.extraSmall)
                                Text(stringResource(if (ui.activeMode == TabMode.PRIVATE)
                                    R.string.private_tab else R.string.app_name),
                                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            if (ui.activeMode == TabMode.PRIVATE) {
                                Icon(Icons.Filled.Lock,
                                    contentDescription = stringResource(R.string.private_tab),
                                    modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            BrowserAddressField(ui, address, editing, invalidAddress,
                                onAddressChange, onAddressFocus, submitAddress, useSuggestion,
                                dismissSuggestions,
                                modifier = Modifier.weight(1f), flat = true,
                                suggestionsVisible = suggestionsVisible)
                        }
                        Spacer(Modifier.width(8.dp))
                        BrowserMenu(ui, controller, menu, onExpandedChange = { expanded ->
                            if (expanded) {
                                focus.clearFocus()
                                editing = false
                                dismissSuggestions()
                            }
                            menu = expanded
                        })
                    }
                    if (ui.findVisible && selected?.url != null) BrowserFindBar(ui, controller)
                    if (LocalConfiguration.current.let {
                        it.screenWidthDp >= 700 && it.fontScale < 1.6f
                    }) TabletTabStrip(ui, controller)
                    if (selected?.engine?.isLoading == true) LinearProgressIndicator(
                        progress = { selected.engine.progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant)
                } else {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp,
                        top = 12.dp, bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { controller.showPage(BrowserPage.WEB) },
                            modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.return_to_web))
                        }
                        Text(ui.page.label(), modifier = Modifier.weight(1f).padding(start = 8.dp)
                            .semantics { heading() },
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        bottomBar = {
            if (ui.page == BrowserPage.WEB && !editing && !imeVisible) BrowserBottomBar(ui, controller)
        },
    ) { padding ->
        // The top toolbar has its own space; only the floating bottom bar overlays web content.
        val contentModifier = if (ui.page == BrowserPage.WEB)
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())
            else Modifier.fillMaxSize().padding(padding)
        Box(contentModifier, contentAlignment = Alignment.TopCenter) {
          Box(if (ui.page == BrowserPage.WEB) Modifier.fillMaxSize()
              else Modifier.widthIn(max = 900.dp).fillMaxSize()) {
            when {
                !ui.ready -> Column(Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ui.startupError ?: stringResource(R.string.startup_loading))
                    if (ui.startupError != null) Button(onClick = controller::retryStart,
                        shape = MaterialTheme.shapes.medium) {
                        Text(stringResource(R.string.startup_retry))
                    }
                }
                ui.page == BrowserPage.TABS -> TabSwitcher(ui, controller)
                ui.page == BrowserPage.HISTORY -> HistoryScreen(ui, controller)
                ui.page == BrowserPage.BOOKMARKS -> BookmarkScreen(ui, controller)
                ui.page == BrowserPage.DOWNLOADS -> DownloadScreen(ui, controller)
                ui.page == BrowserPage.PRIVACY -> PrivacyScreen(controller)
                ui.page == BrowserPage.SITE_INFO -> SiteInfoScreen(ui, controller)
                ui.page == BrowserPage.SITE_PERMISSIONS -> SitePermissionsScreen(ui, controller)
                ui.page == BrowserPage.SETTINGS -> SettingsScreen(ui, controller)
                selected?.url == null && controller.sessionForSelectedTab() == null -> NewTabPage(
                    ui, controller, chromePadding = padding, addressField = {
                        BrowserAddressField(ui, address, editing, invalidAddress,
                            onAddressChange, onAddressFocus, submitAddress, useSuggestion,
                            dismissSuggestions,
                            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                            suggestionsVisible = suggestionsVisible)
                    })
                selected != null -> {
                    val session = controller.sessionForSelectedTab()
                    if (session != null) key(selected.id, session) {
                        AndroidView(factory = {
                            (session.view.parent as? ViewGroup)?.removeView(session.view)
                            session.view
                        }, modifier = Modifier.fillMaxSize())
                    }
                    selected.engine.error?.let { error ->
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.fillMaxSize().padding(24.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(shape = MaterialTheme.shapes.large,
                                    color = MaterialTheme.colorScheme.primaryContainer) {
                                    Icon(Icons.Filled.Language, contentDescription = null,
                                        modifier = Modifier.padding(18.dp).size(32.dp),
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.height(20.dp))
                                Text(stringResource(R.string.page_load_error),
                                    style = MaterialTheme.typography.headlineSmall)
                                Spacer(Modifier.height(12.dp))
                                Text(error.message, textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = controller::reloadOrStop,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.startup_retry))
                                }
                                if (ui.webViewUnavailable) BrowserTextButton(
                                    onClick = controller::openWebViewSettings,
                                    modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.webview_settings))
                                }
                            }
                        }
                    }
                }
            }
          }
        }
    }
}

@Composable
private fun BrowserPage.label(): String = when (this) {
    BrowserPage.WEB -> stringResource(R.string.web_page)
    BrowserPage.TABS -> stringResource(R.string.tabs)
    BrowserPage.HISTORY -> stringResource(R.string.history)
    BrowserPage.BOOKMARKS -> stringResource(R.string.bookmarks)
    BrowserPage.DOWNLOADS -> stringResource(R.string.downloads)
    BrowserPage.PRIVACY -> stringResource(R.string.privacy)
    BrowserPage.SITE_INFO -> stringResource(R.string.site_info)
    BrowserPage.SITE_PERMISSIONS -> stringResource(R.string.site_permissions)
    BrowserPage.SETTINGS -> stringResource(R.string.settings)
}

@Composable
private fun NewTabPage(
    ui: BrowserUiState,
    controller: BrowserController,
    chromePadding: PaddingValues,
    addressField: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val singleQuickLinkColumn = LocalConfiguration.current.let {
        it.screenWidthDp < 360 || it.fontScale >= 1.4f
    }
    val compactHeight = LocalConfiguration.current.screenHeightDp < 480 ||
        WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = 22.dp, end = 22.dp,
            top = 20.dp,
            bottom = chromePadding.calculateBottomPadding() + 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(if (compactHeight) 12.dp else 40.dp))
        if (ui.activeMode == TabMode.PRIVATE) {
            Surface(Modifier.size(68.dp), shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        } else CurrentBrandIcon(Modifier.size(68.dp), MaterialTheme.shapes.large)
        Spacer(Modifier.height(if (compactHeight) 12.dp else 24.dp))
        Text(stringResource(if (ui.activeMode == TabMode.PRIVATE)
            R.string.private_intro_title else R.string.new_tab_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(if (ui.activeMode == TabMode.PRIVATE)
            R.string.private_intro_detail else R.string.new_tab_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        if (ui.activeMode == TabMode.PRIVATE && !ui.privateLockAvailable) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.private_lock_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(if (compactHeight) 14.dp else 30.dp))
        addressField()
        if (ui.activeMode == TabMode.NORMAL && ui.quickLinks.isNotEmpty()) {
            Spacer(Modifier.height(if (compactHeight) 18.dp else 38.dp))
            Text(stringResource(R.string.quick_access), modifier = Modifier.fillMaxWidth()
                .semantics { heading() },
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            ui.quickLinks.take(8).chunked(if (singleQuickLinkColumn) 1 else 2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { link ->
                        Surface(onClick = { controller.openSavedUrl(link.url) },
                            modifier = Modifier.weight(1f).heightIn(min = 86.dp).semantics {
                                contentDescription = context.getString(R.string.quick_link_open,
                                    context.getString(link.sourceRes), link.title, link.url)
                            },
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.padding(14.dp)) {
                                Surface(shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(stringResource(link.sourceRes),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = currentAccentTextColor(),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.height(7.dp))
                                Text(link.title, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (pair.size == 1 && !singleQuickLinkColumn) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun BrowserPanel(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp), content = content)
    }
}

@Composable
internal fun BrowserTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled,
        shape = MaterialTheme.shapes.medium, content = content)
}

@Composable
internal fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, pill: Boolean = false) {
    val state = stringResource(if (selected) R.string.selected else R.string.not_selected)
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)
        .semantics { stateDescription = state },
        shape = if (pill) CircleShape else MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primary else
            MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else
            MaterialTheme.colorScheme.outline)) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else
                    MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
