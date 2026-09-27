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
            next.selection.end == next.text.length) controller.inlineCompletion(next.text) else null
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
private fun BrowserPanel(
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
private fun BrowserTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled,
        shape = MaterialTheme.shapes.medium, content = content)
}

@Composable
private fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit,
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

@Composable
private fun SettingsScreen(ui: BrowserUiState, controller: BrowserController) {
    val context = LocalContext.current
    val contextSelected = stringResource(R.string.selected)
    val contextNotSelected = stringResource(R.string.not_selected)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.browser_environment),
            style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(18.dp))
        BrowserPanel {
            Text(stringResource(R.string.search_engine), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            SearchEngine.entries.forEach { engine ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(onClickLabel = context.getString(R.string.search_engine_select,
                        engine.label)) {
                        controller.changeSearchEngine(engine)
                    }.semantics { stateDescription = if (ui.searchEngine == engine)
                        contextSelected else contextNotSelected },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(engine.label, modifier = Modifier.weight(1f))
                    if (ui.searchEngine == engine) Text("✓", color = currentAccentTextColor())
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        BrowserPanel {
            Text(stringResource(R.string.screen_theme), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChoice.entries.forEach { choice ->
                    ChoicePill(stringResource(choice.labelRes), ui.themeChoice == choice,
                        { controller.changeTheme(choice) }, modifier = Modifier.widthIn(min = 84.dp))
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(stringResource(R.string.web_text_size, ui.textZoom),
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(100, 125, 150, 200).forEach { amount ->
                    ChoicePill("$amount%", ui.textZoom == amount,
                        { controller.changeTextZoom(amount) }, modifier = Modifier.widthIn(min = 76.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        BrowserPanel {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.third_party_cookies),
                        style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.private_cookies_blocked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = ui.allowThirdPartyCookies,
                    modifier = Modifier.semantics {
                        contentDescription = context.getString(R.string.third_party_cookies)
                    },
                    enabled = ui.activeMode == TabMode.NORMAL,
                    onCheckedChange = controller::changeThirdPartyCookies)
            }
        }
        Spacer(Modifier.height(14.dp))
        BrowserPanel {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clickable(onClickLabel = context.getString(R.string.default_browser_action)) {
                    controller.requestDefaultBrowser()
                },
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (ui.defaultBrowser == true)
                    R.string.default_browser_set else R.string.default_browser_action),
                    modifier = Modifier.weight(1f))
                Text("›", color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clickable(onClickLabel = context.getString(R.string.open_privacy_clear)) {
                    controller.showPage(BrowserPage.PRIVACY)
                }, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.privacy_clear), modifier = Modifier.weight(1f))
                Text("›", color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clickable { controller.showPage(BrowserPage.SITE_PERMISSIONS) },
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.site_permissions), modifier = Modifier.weight(1f))
                Text("›", color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TabSwitcher(ui: BrowserUiState, controller: BrowserController) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var groupFilter by remember(ui.activeMode) { mutableStateOf<String?>(null) }
    var groupingTab by remember { mutableStateOf<BrowserTab?>(null) }
    var renamingGroup by remember { mutableStateOf<String?>(null) }
    var groupInput by remember { mutableStateOf("") }
    val ordered = ui.visibleTabs
    val groups = ordered.mapNotNull { it.groupName }.distinct()
    val activeGroupFilter = groupFilter?.takeIf { it in groups }
    val filtered = ordered.filter { (activeGroupFilter == null || it.groupName == activeGroupFilter) &&
        (query.isBlank() || it.title.contains(query, true) || it.url?.contains(query, true) == true) }
    val duplicateCount = ordered.mapNotNull { it.url?.takeIf { url ->
        url.startsWith("http://", true) || url.startsWith("https://", true) } }
        .groupingBy { it }.eachCount().values.sumOf { (it - 1).coerceAtLeast(0) }
    val compact = LocalConfiguration.current.let { it.screenWidthDp < 360 || it.fontScale >= 1.4f }
    val previewWidth = if (compact) 64.dp else 92.dp
    val previewHeight = if (compact) 64.dp else 78.dp
    val closeAllDescription = stringResource(if (ui.activeMode == TabMode.PRIVATE)
        R.string.close_all_private_tabs else R.string.close_all_normal_tabs)
    groupingTab?.let { tab ->
        AlertDialog(onDismissRequest = { groupingTab = null },
            title = { Text(stringResource(R.string.tab_group_title)) },
            text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(groupInput, { groupInput = it }, singleLine = true,
                    label = { Text(stringResource(R.string.tab_group_name)) },
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                groups.forEach { name ->
                    ChoicePill(name, groupInput == name, { groupInput = name },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                }
                if (tab.groupName != null) BrowserTextButton(onClick = {
                    controller.setTabGroup(tab.id, "")
                    groupingTab = null
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.tab_group_remove))
                }
            } },
            confirmButton = { Button(onClick = {
                controller.setTabGroup(tab.id, groupInput)
                groupingTab = null
            }, enabled = groupInput.isNotBlank(), shape = MaterialTheme.shapes.medium,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.save))
            } },
            dismissButton = { BrowserTextButton(onClick = { groupingTab = null },
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cancel))
            } })
    }
    renamingGroup?.let { old ->
        AlertDialog(onDismissRequest = { renamingGroup = null },
            title = { Text(stringResource(R.string.tab_group_rename)) },
            text = { Column {
                OutlinedTextField(groupInput, { groupInput = it }, singleLine = true,
                    label = { Text(stringResource(R.string.tab_group_name)) },
                    modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
                BrowserTextButton(onClick = {
                    controller.ungroupTabs(old)
                    groupFilter = null
                    renamingGroup = null
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.tab_group_remove_all))
                }
            } },
            confirmButton = { Button(onClick = {
                controller.renameTabGroup(old, groupInput)
                groupFilter = groupInput.trim().take(32)
                renamingGroup = null
            }, enabled = groupInput.isNotBlank(), shape = MaterialTheme.shapes.medium,
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.save))
            } },
            dismissButton = { BrowserTextButton(onClick = { renamingGroup = null },
                modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.cancel))
            } })
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoicePill(stringResource(R.string.normal_tab_label,
                ui.tabs.count { it.mode == TabMode.NORMAL }),
                ui.activeMode == TabMode.NORMAL, { controller.switchMode(TabMode.NORMAL) },
                modifier = Modifier.weight(1f), pill = true)
            ChoicePill(stringResource(R.string.private_tab_label,
                ui.tabs.count { it.mode == TabMode.PRIVATE }),
                ui.activeMode == TabMode.PRIVATE, { controller.switchMode(TabMode.PRIVATE) },
                modifier = Modifier.weight(1f), enabled = ui.privateAvailable, pill = true)
        }
        ui.privateUnavailableReason?.let {
            Text(it, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(query, { query = it },
            placeholder = { Text(stringResource(R.string.tab_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                .semantics { contentDescription = context.getString(R.string.tab_search) })
        if (groups.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoicePill(stringResource(R.string.all), activeGroupFilter == null,
                { groupFilter = null }, modifier = Modifier.heightIn(min = 48.dp))
            groups.forEach { name ->
                ChoicePill(name, activeGroupFilter == name, { groupFilter = name },
                    modifier = Modifier.heightIn(min = 48.dp))
            }
            if (activeGroupFilter != null) BrowserTextButton(onClick = {
                renamingGroup = activeGroupFilter
                groupInput = activeGroupFilter
            }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.edit))
            }
        }
        val tabActions: @Composable (Modifier, Modifier) -> Unit = { closeModifier, newModifier ->
            BrowserTextButton(onClick = controller::closeAllTabs,
                modifier = closeModifier.heightIn(min = 48.dp).semantics {
                    contentDescription = closeAllDescription
                }) {
                Text(stringResource(R.string.close_all_tabs))
            }
            BrowserTextButton(onClick = { controller.newTab(mode = ui.activeMode) },
                modifier = newModifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.new_tab))
            }
        }
        if (compact) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                tabActions(Modifier.fillMaxWidth(), Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                tabActions(Modifier, Modifier)
            }
        }
        if (duplicateCount > 0) BrowserTextButton(onClick = controller::closeDuplicateTabs,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.tab_close_duplicates, duplicateCount))
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (filtered.isEmpty()) item { BrowserPanel(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.search_empty))
            } }
            items(filtered, key = { it.id }) { tab ->
                val tabMode = stringResource(if (tab.mode == TabMode.PRIVATE)
                    R.string.private_mode else R.string.normal_mode)
                val tabUrl = tab.url ?: stringResource(R.string.new_tab)
                val image = remember(tab.preview) {
                    tab.preview?.let { bytes -> runCatching {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull() }
                }
                Surface(shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).heightIn(min = 80.dp)
                            .clickable(onClickLabel = context.getString(R.string.tab_open, tab.title)) {
                                controller.selectTab(tab.id)
                            }.semantics {
                                contentDescription = context.getString(R.string.tab_description,
                                    tabMode, tab.title, tabUrl)
                            },
                            verticalAlignment = Alignment.CenterVertically) {
                            if (image != null) Image(image, contentDescription = null,
                                modifier = Modifier.size(previewWidth, previewHeight)
                                    .clip(MaterialTheme.shapes.medium))
                            else Box(Modifier.size(previewWidth, previewHeight)
                                .background(MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                                Icon(if (tab.mode == TabMode.PRIVATE) Icons.Filled.Lock else
                                    Icons.Filled.Language, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(tab.title, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                tab.groupName?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary, maxLines = 1) }
                                Spacer(Modifier.height(4.dp))
                                Text(tabUrl, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        IconButton(onClick = { controller.closeTab(tab.id) },
                            modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Close,
                                contentDescription = stringResource(R.string.tab_close, tab.title))
                        }
                    }
                    val index = ordered.indexOfFirst { it.id == tab.id }
                    val canUp = index > 0 && ordered[index - 1].pinned == tab.pinned
                    val canDown = index >= 0 && index < ordered.lastIndex &&
                        ordered[index + 1].pinned == tab.pinned
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        BrowserTextButton(onClick = {
                            groupingTab = tab
                            groupInput = tab.groupName.orEmpty()
                        }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.tab_group_short))
                        }
                        BrowserTextButton(onClick = { controller.toggleTabPinned(tab.id) },
                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                contentDescription = context.getString(if (tab.pinned)
                                    R.string.tab_unpin else R.string.tab_pin, tab.title)
                            }) { Text(stringResource(if (tab.pinned)
                                R.string.tab_unpin_short else R.string.tab_pin_short), maxLines = 1) }
                        BrowserTextButton(onClick = { controller.moveTab(tab.id, -1) }, enabled = canUp,
                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                contentDescription = context.getString(R.string.tab_move_up, tab.title)
                            }) { Text(stringResource(R.string.tab_move_up_short)) }
                        BrowserTextButton(onClick = { controller.moveTab(tab.id, 1) }, enabled = canDown,
                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                contentDescription = context.getString(R.string.tab_move_down, tab.title)
                            }) { Text(stringResource(R.string.tab_move_down_short)) }
                    }
                    }
                }
            }
            if (query.isBlank() && ui.closedTabs.any { it.url != null }) {
                item {
                    Text(stringResource(R.string.recently_closed_tabs),
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                            .semantics { heading() },
                        style = MaterialTheme.typography.titleMedium)
                }
                items(ui.closedTabs.filter { it.url != null }, key = { "closed-" + it.id }) { closed ->
                    Surface(onClick = { controller.reopenClosedTab(closed.id) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            contentAlignment = Alignment.CenterStart) {
                            Text(closed.title.ifBlank { closed.url.orEmpty() }, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(ui: BrowserUiState, controller: BrowserController) {
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
private fun BookmarkScreen(ui: BrowserUiState, controller: BrowserController) {
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
private fun DownloadScreen(ui: BrowserUiState, controller: BrowserController) {
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

@Composable
private fun PrivacyScreen(controller: BrowserController) {
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
private fun SiteInfoScreen(ui: BrowserUiState, controller: BrowserController) {
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
    }
}

@Composable
private fun SitePermissionsScreen(ui: BrowserUiState, controller: BrowserController) {
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
private fun BrowserDialogView(dialog: BrowserDialog, controller: BrowserController) {
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
