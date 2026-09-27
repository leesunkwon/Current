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
internal fun TabletTabStrip(ui: BrowserUiState, controller: BrowserController) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ui.visibleTabs.forEach { tab ->
            Surface(shape = MaterialTheme.shapes.medium,
                color = if (tab.id == ui.selectedId) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.widthIn(min = 150.dp, max = 240.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(tab.title, modifier = Modifier.weight(1f).clickable(
                        onClickLabel = context.getString(R.string.tab_description,
                            context.getString(if (tab.mode == TabMode.PRIVATE)
                                R.string.private_mode else R.string.normal_mode),
                            tab.title, tab.url.orEmpty())) { controller.selectTab(tab.id) }
                        .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { controller.closeTab(tab.id) },
                        modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.Close,
                            contentDescription = stringResource(R.string.tab_close, tab.title))
                    }
                }
            }
        }
        IconButton(onClick = { controller.newTab() }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_tab))
        }
    }
}

@Composable
internal fun SettingsScreen(ui: BrowserUiState, controller: BrowserController) {
    SettingsScreenContent(ui, SettingsActions(
        saveHomePage = controller::saveHomePage,
        changeSearchEngine = controller::changeSearchEngine,
        changeTheme = controller::changeTheme,
        changeTextZoom = controller::changeTextZoom,
        changeThirdPartyCookies = controller::changeThirdPartyCookies,
        changeTrackingProtection = controller::changeTrackingProtection,
        requestDefaultBrowser = controller::requestDefaultBrowser,
        showPrivacy = { controller.showPage(BrowserPage.PRIVACY) },
        showSitePermissions = { controller.showPage(BrowserPage.SITE_PERMISSIONS) },
    ))
}

internal class SettingsActions(
    val saveHomePage: (String) -> Boolean = { true },
    val changeSearchEngine: (SearchEngine) -> Unit = {},
    val changeTheme: (ThemeChoice) -> Unit = {},
    val changeTextZoom: (Int) -> Unit = {},
    val changeThirdPartyCookies: (Boolean) -> Unit = {},
    val changeTrackingProtection: (Boolean) -> Unit = {},
    val requestDefaultBrowser: () -> Unit = {},
    val showPrivacy: () -> Unit = {},
    val showSitePermissions: () -> Unit = {},
)

@Composable
internal fun SettingsScreenContent(ui: BrowserUiState, actions: SettingsActions) {
    val context = LocalContext.current
    var homeEditorOpen by remember { mutableStateOf(false) }
    var homeInput by remember(ui.homePageUrl) { mutableStateOf(ui.homePageUrl.orEmpty()) }
    var homeInvalid by remember { mutableStateOf(false) }
    val contextSelected = stringResource(R.string.selected)
    val contextNotSelected = stringResource(R.string.not_selected)
    if (homeEditorOpen) AlertDialog(onDismissRequest = { homeEditorOpen = false },
        title = { Text(stringResource(R.string.home_page_setting)) },
        text = { Column {
            Text(stringResource(R.string.home_page_hint))
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(homeInput, { homeInput = it; homeInvalid = false },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                isError = homeInvalid, label = { Text(stringResource(R.string.address)) })
            if (homeInvalid) Text(stringResource(R.string.home_page_invalid),
                color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { Button(onClick = {
            if (actions.saveHomePage(homeInput)) homeEditorOpen = false else homeInvalid = true
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.save)) } },
        dismissButton = { BrowserTextButton(onClick = { homeEditorOpen = false },
            modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
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
                        actions.changeSearchEngine(engine)
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
                        { actions.changeTheme(choice) }, modifier = Modifier.widthIn(min = 84.dp))
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
                        { actions.changeTextZoom(amount) }, modifier = Modifier.widthIn(min = 76.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        BrowserPanel {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable {
                homeInput = ui.homePageUrl.orEmpty()
                homeEditorOpen = true
            }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.home_page_setting))
                    Text(ui.homePageUrl?.let(AddressResolver::displayUrl)
                        ?: stringResource(R.string.home_page_default),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("›")
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
                    onCheckedChange = actions.changeThirdPartyCookies)
            }
        }
        Spacer(Modifier.height(14.dp))
        BrowserPanel {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.tracking_protection))
                    Text(stringResource(R.string.tracking_protection_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = ui.trackingProtection,
                    onCheckedChange = actions.changeTrackingProtection,
                    modifier = Modifier.semantics {
                        contentDescription = context.getString(R.string.tracking_protection)
                    })
            }
        }
        if (!ui.privateLockAvailable) {
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.private_lock_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        BrowserPanel {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clickable(onClickLabel = context.getString(R.string.default_browser_action)) {
                    actions.requestDefaultBrowser()
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
                    actions.showPrivacy()
                }, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.privacy_clear), modifier = Modifier.weight(1f))
                Text("›", color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clickable { actions.showSitePermissions() },
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.site_permissions), modifier = Modifier.weight(1f))
                Text("›", color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun TabSwitcher(ui: BrowserUiState, controller: BrowserController) {
    TabSwitcherContent(ui, TabSwitcherActions(
        setTabGroup = controller::setTabGroup,
        ungroupTabs = controller::ungroupTabs,
        renameTabGroup = controller::renameTabGroup,
        switchMode = controller::switchMode,
        closeAllTabs = controller::closeAllTabs,
        newTab = { mode -> controller.newTab(mode = mode) },
        closeDuplicateTabs = controller::closeDuplicateTabs,
        selectTab = controller::selectTab,
        closeTab = controller::closeTab,
        toggleTabPinned = controller::toggleTabPinned,
        moveTab = controller::moveTab,
        reopenClosedTab = { id -> controller.reopenClosedTab(id) },
    ))
}

internal class TabSwitcherActions(
    val setTabGroup: (String, String) -> Unit = { _, _ -> },
    val ungroupTabs: (String) -> Unit = {},
    val renameTabGroup: (String, String) -> Unit = { _, _ -> },
    val switchMode: (TabMode) -> Unit = {},
    val closeAllTabs: () -> Unit = {},
    val newTab: (TabMode) -> Unit = {},
    val closeDuplicateTabs: () -> Unit = {},
    val selectTab: (String) -> Unit = {},
    val closeTab: (String) -> Unit = {},
    val toggleTabPinned: (String) -> Unit = {},
    val moveTab: (String, Int) -> Unit = { _, _ -> },
    val reopenClosedTab: (String) -> Unit = {},
)

@Composable
internal fun TabSwitcherContent(ui: BrowserUiState, actions: TabSwitcherActions) {
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
                    actions.setTabGroup(tab.id, "")
                    groupingTab = null
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.tab_group_remove))
                }
            } },
            confirmButton = { Button(onClick = {
                actions.setTabGroup(tab.id, groupInput)
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
                    actions.ungroupTabs(old)
                    groupFilter = null
                    renamingGroup = null
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.tab_group_remove_all))
                }
            } },
            confirmButton = { Button(onClick = {
                actions.renameTabGroup(old, groupInput)
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
                ui.activeMode == TabMode.NORMAL, { actions.switchMode(TabMode.NORMAL) },
                modifier = Modifier.weight(1f), pill = true)
            ChoicePill(stringResource(R.string.private_tab_label,
                ui.tabs.count { it.mode == TabMode.PRIVATE }),
                ui.activeMode == TabMode.PRIVATE, { actions.switchMode(TabMode.PRIVATE) },
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
            BrowserTextButton(onClick = actions.closeAllTabs,
                modifier = closeModifier.heightIn(min = 48.dp).semantics {
                    contentDescription = closeAllDescription
                }) {
                Text(stringResource(R.string.close_all_tabs))
            }
            BrowserTextButton(onClick = { actions.newTab(ui.activeMode) },
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
        if (duplicateCount > 0) BrowserTextButton(onClick = actions.closeDuplicateTabs,
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
                                actions.selectTab(tab.id)
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
                        IconButton(onClick = { actions.closeTab(tab.id) },
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
                        BrowserTextButton(onClick = { actions.toggleTabPinned(tab.id) },
                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                contentDescription = context.getString(if (tab.pinned)
                                    R.string.tab_unpin else R.string.tab_pin, tab.title)
                            }) { Text(stringResource(if (tab.pinned)
                                R.string.tab_unpin_short else R.string.tab_pin_short), maxLines = 1) }
                        BrowserTextButton(onClick = { actions.moveTab(tab.id, -1) }, enabled = canUp,
                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                contentDescription = context.getString(R.string.tab_move_up, tab.title)
                            }) { Text(stringResource(R.string.tab_move_up_short)) }
                        BrowserTextButton(onClick = { actions.moveTab(tab.id, 1) }, enabled = canDown,
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
                    Surface(onClick = { actions.reopenClosedTab(closed.id) },
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
