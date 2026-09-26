package com.kotlinsun.current.browser

import android.app.DownloadManager
import android.graphics.BitmapFactory
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kotlinsun.current.data.BookmarkRecord
import com.kotlinsun.current.engine.JavaScriptDialogKind
import com.kotlinsun.current.engine.TabMode
import com.kotlinsun.current.ui.theme.BrowserNavigation
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun BrowserScreen(controller: BrowserController) {
    val ui by controller.ui.collectAsState()
    val selected = ui.selectedTab
    val focus = LocalFocusManager.current
    var address by remember { mutableStateOf(TextFieldValue("")) }
    var editing by remember { mutableStateOf(false) }
    var invalidAddress by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.page, ui.selectedId, ui.activeMode, ui.showOnboarding) {
        menu = false
    }

    LaunchedEffect(selected?.id, selected?.url, editing) {
        if (!editing) address = TextFieldValue(selected?.url?.let(AddressResolver::displayUrl).orEmpty())
    }

    LaunchedEffect(ui.lastClosedTabId) {
        val latestId = ui.lastClosedTabId ?: return@LaunchedEffect
        if (snackbar.showSnackbar("탭을 닫았습니다.", "되돌리기", duration = SnackbarDuration.Short) ==
            androidx.compose.material3.SnackbarResult.ActionPerformed) controller.reopenClosedTab(latestId)
    }

    if (ui.showOnboarding) {
        WelcomeScreen(controller::completeOnboarding)
        return
    }

    val onAddressChange: (TextFieldValue) -> Unit = { next ->
        val completed = if (next.composition == null && next.selection.collapsed &&
            next.selection.end == next.text.length) controller.inlineCompletion(next.text) else null
        address = if (completed != null) TextFieldValue(completed,
            selection = TextRange(next.text.length, completed.length)) else next
        invalidAddress = false
        controller.updateSuggestions(next.text)
    }
    val onAddressFocus: (Boolean) -> Unit = { focused ->
        if (focused && !editing) {
            val raw = selected?.url.orEmpty()
            address = TextFieldValue(raw, selection = TextRange(0, raw.length))
        }
        editing = focused
    }
    val submitAddress: () -> Unit = {
        invalidAddress = !controller.submitAddress(address.text)
        if (!invalidAddress) {
            focus.clearFocus()
            editing = false
        }
    }
    val useSuggestion: (AddressSuggestion) -> Unit = { suggestion ->
        controller.useSuggestion(suggestion)
        focus.clearFocus()
        editing = false
    }

    ui.dialog?.let { BrowserDialogView(it, controller) }
    ui.linkTarget?.let { target ->
        AlertDialog(onDismissRequest = controller::dismissLinkMenu,
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            title = { Text(if (target.imageUrl != null) "이미지·링크" else "링크") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(target.url, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { controller.openLink(true) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("새 탭에서 열기")
                    }
                    TextButton(onClick = { controller.openLink(false) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("백그라운드 탭에서 열기")
                    }
                    if (ui.activeMode == TabMode.NORMAL && ui.privateAvailable) {
                        TextButton(onClick = { controller.openLink(true, true) },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("시크릿 탭에서 열기") }
                    }
                    TextButton(onClick = controller::copyLink, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("링크 복사")
                    }
                    TextButton(onClick = controller::shareLink, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("링크 공유")
                    }
                    if (target.imageUrl != null) TextButton(onClick = controller::saveImage,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("이미지 저장") }
                }
            },
            confirmButton = { TextButton(onClick = controller::dismissLinkMenu,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") } })
    }
    ui.pendingExternalUrl?.let { url ->
        AlertDialog(onDismissRequest = controller::dismissExternal,
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            title = { Text("외부 앱 열기") },
            text = { Text(url) },
            confirmButton = { TextButton(onClick = controller::confirmExternal,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("열기") } },
            dismissButton = { TextButton(onClick = controller::dismissExternal,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } })
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
            Column(Modifier.background(if (ui.page == BrowserPage.WEB) Color.Transparent
                else MaterialTheme.colorScheme.background).statusBarsPadding()) {
                if (ui.page == BrowserPage.WEB) {
                    val tabPosition = ui.visibleTabs.indexOfFirst { it.id == selected?.id } + 1
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(26.dp),
                        color = if (ui.activeMode == TabMode.PRIVATE)
                            MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        shadowElevation = 14.dp,
                        tonalElevation = 0.dp,
                    ) {
                        Column {
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(onClickLabel = "탭 목록 열기") {
                                    controller.showPage(BrowserPage.TABS)
                                }
                                .padding(start = 16.dp, end = 14.dp, top = 8.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (ui.activeMode == TabMode.PRIVATE) {
                                    Icon(Icons.Filled.Lock, contentDescription = null,
                                        modifier = Modifier.size(20.dp))
                                } else CurrentBrandIcon(Modifier.size(20.dp), RoundedCornerShape(6.dp))
                                Text(selected?.title?.takeIf { it.isNotBlank() } ?: "새 탭",
                                    modifier = Modifier.weight(1f).padding(start = 10.dp, end = 8.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Surface(shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Text(if (ui.activeMode == TabMode.PRIVATE) "시크릿" else
                                        "탭 ${tabPosition.coerceAtLeast(1)}/${ui.visibleTabs.size.coerceAtLeast(1)}",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f))
                            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp,
                                top = 8.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (selected?.url == null) {
                                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        CurrentBrandIcon(Modifier.size(36.dp))
                                        Text("Current", modifier = Modifier.weight(1f).padding(start = 10.dp),
                                            style = MaterialTheme.typography.titleLarge,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                } else {
                                    BrowserAddressField(ui, address, editing, invalidAddress,
                                        onAddressChange, onAddressFocus, submitAddress, useSuggestion,
                                        controller::clearSuggestions,
                                        modifier = Modifier.weight(1f))
                                }
                                Spacer(Modifier.width(10.dp))
                                BrowserMenu(ui, controller, menu, onExpandedChange = { expanded ->
                                    if (expanded) {
                                        focus.clearFocus()
                                        editing = false
                                        controller.clearSuggestions()
                                    }
                                    menu = expanded
                                })
                            }
                            if (selected?.engine?.isLoading == true) LinearProgressIndicator(
                                progress = { selected.engine.progress / 100f },
                                modifier = Modifier.fillMaxWidth().height(2.dp))
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp,
                        top = 12.dp, bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { controller.showPage(BrowserPage.WEB) },
                            modifier = Modifier.size(48.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "웹으로 돌아가기")
                        }
                        Text(ui.page.label(), modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        },
        bottomBar = {
            if (ui.page == BrowserPage.WEB && !editing) BrowserBottomBar(ui, controller)
        },
    ) { padding ->
        val contentModifier = if (ui.page == BrowserPage.WEB) Modifier.fillMaxSize()
            else Modifier.fillMaxSize().padding(padding)
        Box(contentModifier) {
            when {
                !ui.ready -> Column(Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ui.startupError ?: "브라우저를 준비하는 중…")
                    if (ui.startupError != null) Button(onClick = controller::retryStart) { Text("다시 시도") }
                }
                ui.page == BrowserPage.TABS -> TabSwitcher(ui, controller)
                ui.page == BrowserPage.HISTORY -> HistoryScreen(ui, controller)
                ui.page == BrowserPage.BOOKMARKS -> BookmarkScreen(ui, controller)
                ui.page == BrowserPage.DOWNLOADS -> DownloadScreen(ui, controller)
                ui.page == BrowserPage.PRIVACY -> PrivacyScreen(controller)
                ui.page == BrowserPage.SITE_INFO -> SiteInfoScreen(ui)
                ui.page == BrowserPage.SETTINGS -> SettingsScreen(ui, controller)
                selected?.url == null && controller.sessionForSelectedTab() == null -> NewTabPage(
                    ui, controller, chromePadding = padding, addressField = {
                        BrowserAddressField(ui, address, editing, invalidAddress,
                            onAddressChange, onAddressFocus, submitAddress, useSuggestion,
                            controller::clearSuggestions,
                            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth())
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
                                Surface(shape = RoundedCornerShape(22.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer) {
                                    Icon(Icons.Filled.Language, contentDescription = null,
                                        modifier = Modifier.padding(18.dp).size(32.dp),
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.height(20.dp))
                                Text("페이지를 열 수 없습니다", style = MaterialTheme.typography.headlineSmall)
                                Spacer(Modifier.height(12.dp))
                                Text(error.message, textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = controller::reloadOrStop, shape = CircleShape,
                                    modifier = Modifier.heightIn(min = 48.dp)) { Text("다시 시도") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserAddressField(
    ui: BrowserUiState,
    value: TextFieldValue,
    editing: Boolean,
    isError: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onSuggestion: (AddressSuggestion) -> Unit,
    onDismissSuggestions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isHome = ui.selectedTab?.url == null
    Box(modifier) {
        BrowserGlassSurface(Modifier.fillMaxWidth(), shape = CircleShape,
            focused = editing, error = isError) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChange(it.isFocused) }
                    .semantics { contentDescription = "주소 또는 검색어 입력" },
                singleLine = true,
                isError = isError,
                placeholder = { Text("주소 또는 검색어") },
                leadingIcon = {
                    Icon(if (isError) Icons.Filled.ErrorOutline else if (isHome || editing) Icons.Filled.Search else if (
                        ui.selectedTab?.url?.startsWith("https://", true) == true) Icons.Filled.Lock
                    else Icons.Filled.Language, contentDescription = if (isError) "주소 입력 오류" else null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingIcon = {
                    if (isHome || editing) IconButton(onClick = onSubmit,
                        modifier = Modifier.size(48.dp)) {
                        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "주소 또는 검색어 열기",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                },
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    errorBorderColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onSubmit() }),
            )
        }
        DropdownMenu(expanded = editing && ui.suggestions.isNotEmpty(),
            onDismissRequest = onDismissSuggestions,
            offset = DpOffset(0.dp, 8.dp),
            modifier = Modifier.widthIn(max = 360.dp),
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            ui.suggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(suggestion.title, maxLines = 1)
                            Text(suggestion.source, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                            Text(suggestion.url, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "${suggestion.source}, ${suggestion.title}, ${suggestion.url}"
                    },
                    onClick = { onSuggestion(suggestion) },
                )
            }
        }
    }
}

@Composable
private fun BrowserMenu(
    ui: BrowserUiState,
    controller: BrowserController,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    BackHandler(enabled = expanded) { onExpandedChange(false) }
    val configuration = LocalConfiguration.current
    val menuWidth = (configuration.screenWidthDp - 48).coerceIn(1, 336).dp
    val menuHeight = (configuration.screenHeightDp - 150).coerceIn(1, 600).dp
    val popupOffset = with(LocalDensity.current) { 56.dp.roundToPx() }
    fun perform(action: () -> Unit) {
        onExpandedChange(false)
        action()
    }
    Box {
        BrowserGlassSurface(Modifier.size(48.dp), shape = CircleShape) {
            IconButton(onClick = { onExpandedChange(true) }, modifier = Modifier.fillMaxSize()) {
                Icon(Icons.Filled.MoreVert, contentDescription = "브라우저 메뉴")
            }
        }
        if (expanded) Popup(
            alignment = Alignment.TopEnd,
            offset = IntOffset(0, popupOffset),
            onDismissRequest = { onExpandedChange(false) },
            properties = PopupProperties(focusable = true,
                dismissOnBackPress = true, dismissOnClickOutside = true),
        ) {
            Box(Modifier.padding(10.dp)) {
                BrowserGlassSurface(Modifier.width(menuWidth).heightIn(max = menuHeight)
                    .semantics { paneTitle = "브라우저 메뉴" },
                    shape = RoundedCornerShape(28.dp), strong = true) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text("CURRENT", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                                Text("브라우저 메뉴", style = MaterialTheme.typography.titleMedium)
                            }
                            IconButton(onClick = { onExpandedChange(false) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = "메뉴 닫기")
                            }
                        }
                        ui.selectedTab?.url?.let { url ->
                            val bookmarked = ui.bookmarks.any { it.url == url }
                            Text(AddressResolver.displayUrl(url),
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            BrowserMenuSection("현재 페이지")
                            BrowserMenuItem(if (bookmarked) "북마크에서 삭제" else "북마크에 저장",
                                Icons.Filled.BookmarkBorder) {
                                perform(controller::saveCurrentBookmark)
                            }
                            BrowserMenuItem("현재 주소 복사", Icons.Filled.ContentCopy) {
                                perform(controller::copyCurrentPage)
                            }
                            BrowserMenuItem("현재 페이지 공유", Icons.Filled.Share) {
                                perform(controller::shareCurrentPage)
                            }
                            BrowserMenuItem("현재 페이지 인쇄", Icons.Filled.Print) {
                                perform(controller::printCurrentPage)
                            }
                        }
                        BrowserMenuSection("보관함")
                        if (ui.activeMode == TabMode.NORMAL) {
                            BrowserMenuItem("방문 기록", Icons.Filled.History) {
                                perform { controller.showPage(BrowserPage.HISTORY) }
                            }
                        }
                        BrowserMenuItem("북마크", Icons.Filled.BookmarkBorder) {
                            perform { controller.showPage(BrowserPage.BOOKMARKS) }
                        }
                        if (ui.activeMode == TabMode.NORMAL) {
                            BrowserMenuItem("다운로드", Icons.Filled.FileDownload) {
                                perform { controller.showPage(BrowserPage.DOWNLOADS) }
                            }
                        }
                        if (ui.selectedTab?.url != null) {
                            BrowserMenuItem("사이트 정보", Icons.Filled.Info) {
                                perform { controller.showPage(BrowserPage.SITE_INFO) }
                            }
                        }
                        BrowserMenuSection("브라우저")
                        BrowserMenuItem("개인정보", Icons.Filled.Security) {
                            perform { controller.showPage(BrowserPage.PRIVACY) }
                        }
                        BrowserMenuItem("설정", Icons.Filled.Settings) {
                            perform { controller.showPage(BrowserPage.SETTINGS) }
                        }
                        if (ui.privateAvailable) {
                            BrowserMenuItem("새 시크릿 탭", Icons.Filled.Lock) {
                                perform { controller.newTab(mode = TabMode.PRIVATE) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserMenuSection(title: String) {
    Text(title, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun BrowserMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(16.dp))
        .clickable(onClickLabel = label, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primaryContainer,
            RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
        Text(label, modifier = Modifier.weight(1f).padding(start = 12.dp),
            style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
            modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BrowserBottomBar(ui: BrowserUiState, controller: BrowserController) {
    val selected = ui.selectedTab
    val compact = LocalConfiguration.current.screenWidthDp < 320
    val horizontalPadding = if (compact) 8.dp else 16.dp
    Box(Modifier.fillMaxWidth().navigationBarsPadding()
        .padding(start = horizontalPadding, top = 12.dp,
            end = horizontalPadding, bottom = 12.dp),
        contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape,
            color = if (ui.activeMode == TabMode.PRIVATE) Color(0xFF363636) else BrowserNavigation,
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            shadowElevation = 16.dp) {
            Row(Modifier.padding(if (compact) 6.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = controller::goBack,
                    enabled = selected?.engine?.canGoBack == true, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
                IconButton(onClick = controller::goForward,
                    enabled = selected?.engine?.canGoForward == true, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "앞으로")
                }
                IconButton(onClick = controller::reloadOrStop,
                    enabled = selected?.url != null, modifier = Modifier.size(48.dp)) {
                    Icon(if (selected?.engine?.isLoading == true) Icons.Filled.Stop else Icons.Filled.Refresh,
                        contentDescription = if (selected?.engine?.isLoading == true) "로딩 중지" else "새로고침")
                }
                IconButton(onClick = { controller.showPage(BrowserPage.TABS) },
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription = if (ui.activeMode == TabMode.PRIVATE)
                            "시크릿 탭 ${ui.visibleTabs.size}개, 탭 목록" else
                            "일반 탭 ${ui.visibleTabs.size}개, 탭 목록"
                    }) {
                    Box(Modifier.size(27.dp).border(1.5.dp, Color.White,
                        RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                        Text(ui.visibleTabs.size.toString(), style = MaterialTheme.typography.bodySmall,
                            color = Color.White, maxLines = 1)
                    }
                }
                IconButton(onClick = { controller.newTab() },
                    modifier = Modifier.size(48.dp).background(Color.White, CircleShape)) {
                    Icon(Icons.Filled.Add, contentDescription = "새 탭", tint = BrowserNavigation)
                }
            }
        }
    }
}

private fun BrowserPage.label(): String = when (this) {
    BrowserPage.WEB -> "웹"
    BrowserPage.TABS -> "탭"
    BrowserPage.HISTORY -> "방문 기록"
    BrowserPage.BOOKMARKS -> "북마크"
    BrowserPage.DOWNLOADS -> "다운로드"
    BrowserPage.PRIVACY -> "개인정보"
    BrowserPage.SITE_INFO -> "사이트 정보"
    BrowserPage.SETTINGS -> "설정"
}

@Composable
private fun NewTabPage(
    ui: BrowserUiState,
    controller: BrowserController,
    chromePadding: PaddingValues,
    addressField: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = 22.dp, end = 22.dp,
            top = chromePadding.calculateTopPadding() + 20.dp,
            bottom = chromePadding.calculateBottomPadding() + 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        if (ui.activeMode == TabMode.PRIVATE) {
            Surface(Modifier.size(68.dp), shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
            }
        } else CurrentBrandIcon(Modifier.size(68.dp), RoundedCornerShape(24.dp))
        Spacer(Modifier.height(24.dp))
        Text(if (ui.activeMode == TabMode.PRIVATE) "시크릿으로 탐색하세요" else "무엇을 찾으세요?",
            style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(if (ui.activeMode == TabMode.PRIVATE)
            "이 탭의 방문 기록은 기기에 저장하지 않습니다."
            else "주소를 입력하거나 검색해서 새로운 곳으로 이동하세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(30.dp))
        addressField()
        if (ui.activeMode == TabMode.NORMAL && ui.quickLinks.isNotEmpty()) {
            Spacer(Modifier.height(38.dp))
            Text("빠른 실행", modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            ui.quickLinks.take(4).chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { link ->
                        Surface(onClick = { controller.openSavedUrl(link.url) },
                            modifier = Modifier.weight(1f).heightIn(min = 86.dp).semantics {
                                contentDescription = "${link.source}, ${link.title}, ${link.url} 열기"
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                            Column(Modifier.padding(14.dp)) {
                                Surface(shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer) {
                                    Text(link.source,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.height(7.dp))
                                Text(link.title, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
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
    Surface(modifier = modifier, shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun ChoicePill(label: String, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)
        .semantics { stateDescription = if (selected) "선택됨" else "선택되지 않음" },
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else
            MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else
            MaterialTheme.colorScheme.outline)) {
        Box(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else
                    MaterialTheme.colorScheme.onSurface, maxLines = 1)
        }
    }
}

@Composable
private fun SettingsScreen(ui: BrowserUiState, controller: BrowserController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("탐색 환경", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(18.dp))
        BrowserPanel {
            Text("검색엔진", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            SearchEngine.entries.forEach { engine ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(onClickLabel = "${engine.label} 검색엔진 선택") {
                        controller.changeSearchEngine(engine)
                    }.semantics { stateDescription = if (ui.searchEngine == engine) "선택됨" else "선택되지 않음" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(engine.label, modifier = Modifier.weight(1f))
                    if (ui.searchEngine == engine) Text("✓", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        BrowserPanel {
            Text("화면 테마", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChoice.entries.forEach { choice ->
                    ChoicePill(choice.label, ui.themeChoice == choice,
                        { controller.changeTheme(choice) }, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("웹 글꼴 크기 · ${ui.textZoom}%", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(100, 125, 150, 200).forEach { amount ->
                    ChoicePill("$amount%", ui.textZoom == amount,
                        { controller.changeTextZoom(amount) }, modifier = Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        BrowserPanel {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("서드파티 쿠키 허용", style = MaterialTheme.typography.bodyLarge)
                    Text("시크릿 탭에서는 항상 차단합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = ui.allowThirdPartyCookies,
                    modifier = Modifier.semantics { contentDescription = "서드파티 쿠키 허용" },
                    enabled = ui.activeMode == TabMode.NORMAL,
                    onCheckedChange = controller::changeThirdPartyCookies)
            }
        }
        Spacer(Modifier.height(14.dp))
        BrowserPanel {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clickable(onClickLabel = "기본 브라우저 설정") { controller.requestDefaultBrowser() },
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (ui.defaultBrowser == true) "기본 브라우저로 설정됨" else "기본 브라우저로 설정",
                    modifier = Modifier.weight(1f))
                Text("›", color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .clickable(onClickLabel = "개인정보 삭제 화면 열기") {
                    controller.showPage(BrowserPage.PRIVACY)
                }, verticalAlignment = Alignment.CenterVertically) {
                Text("개인정보 삭제", modifier = Modifier.weight(1f))
                Text("›", color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TabSwitcher(ui: BrowserUiState, controller: BrowserController) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoicePill("일반 ${ui.tabs.count { it.mode == TabMode.NORMAL }}",
                ui.activeMode == TabMode.NORMAL, { controller.switchMode(TabMode.NORMAL) },
                modifier = Modifier.weight(1f))
            ChoicePill("시크릿 ${ui.tabs.count { it.mode == TabMode.PRIVATE }}",
                ui.activeMode == TabMode.PRIVATE, { controller.switchMode(TabMode.PRIVATE) },
                modifier = Modifier.weight(1f), enabled = ui.privateAvailable)
        }
        ui.privateUnavailableReason?.let {
            Text(it, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = controller::closeAllTabs, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("모두 닫기")
            }
            TextButton(onClick = { controller.newTab(mode = ui.activeMode) },
                modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("새 탭")
            }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(ui.visibleTabs, key = { it.id }) { tab ->
                val image = remember(tab.preview) {
                    tab.preview?.let { bytes -> runCatching {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull() }
                }
                Surface(shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                    Row(Modifier.fillMaxWidth().padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).heightIn(min = 80.dp)
                            .clickable(onClickLabel = "${tab.title} 탭 열기") {
                                controller.selectTab(tab.id)
                            }.semantics { contentDescription = "${tab.title}, ${tab.url ?: "새 탭"}" },
                            verticalAlignment = Alignment.CenterVertically) {
                            if (image != null) Image(image, contentDescription = null,
                                modifier = Modifier.size(92.dp, 78.dp).clip(RoundedCornerShape(15.dp)))
                            else Box(Modifier.size(92.dp, 78.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                                Icon(if (tab.mode == TabMode.PRIVATE) Icons.Filled.Lock else
                                    Icons.Filled.Language, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(tab.title, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Text(tab.url ?: "새 탭", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        IconButton(onClick = { controller.closeTab(tab.id) },
                            modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "${tab.title} 탭 닫기")
                        }
                    }
                }
            }
            if (ui.closedTabs.any { it.url != null }) {
                item {
                    Text("최근 닫은 탭", modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        style = MaterialTheme.typography.titleMedium)
                }
                items(ui.closedTabs.filter { it.url != null }, key = { "closed-" + it.id }) { closed ->
                    Surface(onClick = { controller.reopenClosedTab(closed.id) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
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
    var query by remember { mutableStateOf("") }
    val filtered = ui.history.filter { query.isBlank() ||
        it.url.contains(query, true) || it.title.contains(query, true) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it },
            placeholder = { Text("방문 기록 검색") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true, shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { contentDescription = "방문 기록 검색" })
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (filtered.isEmpty()) item {
                BrowserPanel(Modifier.fillMaxWidth()) {
                    Text(if (query.isBlank()) "방문 기록이 없습니다." else "검색 결과가 없습니다.",
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
                    Surface(shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (favicon != null) Image(favicon, contentDescription = null,
                                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)))
                            else Icon(Icons.Filled.Language, contentDescription = null,
                                modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).heightIn(min = 56.dp)
                                .clickable(onClickLabel = "${visit.title} 열기") {
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
                                Icon(Icons.Filled.Close, contentDescription = "${visit.title} 기록 삭제")
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
    var editing by remember { mutableStateOf<BookmarkRecord?>(null) }
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    editing?.let { item ->
        AlertDialog(onDismissRequest = { editing = null },
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            title = { Text("북마크 수정") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("제목") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp))
                OutlinedTextField(url, { url = it }, label = { Text("주소") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp))
            } },
            confirmButton = { TextButton(onClick = {
                controller.updateBookmark(item.id, title, url)
                editing = null
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("저장") } },
            dismissButton = { TextButton(onClick = { editing = null },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } })
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (ui.bookmarks.isEmpty()) item {
            BrowserPanel(Modifier.fillMaxWidth()) {
                Text("저장한 북마크가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(ui.bookmarks, key = { it.id }) { bookmark ->
            Surface(shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.BookmarkBorder, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Column(Modifier.weight(1f).heightIn(min = 58.dp)
                            .clickable(onClickLabel = "${bookmark.title} 북마크 열기") {
                                controller.openSavedUrl(bookmark.url)
                            }.padding(start = 12.dp), verticalArrangement = Arrangement.Center) {
                            Text(bookmark.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(AddressResolver.displayUrl(bookmark.url), maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            editing = bookmark
                            title = bookmark.title
                            url = bookmark.url
                        }, modifier = Modifier.heightIn(min = 48.dp)) { Text("수정") }
                        IconButton(onClick = { controller.deleteBookmark(bookmark.id) },
                            modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "${bookmark.title} 북마크 삭제")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadScreen(ui: BrowserUiState, controller: BrowserController) {
    LaunchedEffect(ui.page) {
        while (true) {
            controller.refreshDownloads()
            delay(2000)
        }
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ui.downloadError?.let { error -> item {
            BrowserPanel(Modifier.fillMaxWidth()) {
                Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = controller::refreshDownloads,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("다시 시도") }
            }
        } }
        if (ui.downloadError == null && ui.downloads.isEmpty() && ui.localDownloads.isEmpty()) {
            item { BrowserPanel(Modifier.fillMaxWidth()) {
                Text("다운로드 기록이 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
        items(ui.downloads, key = { it.record.id }) { item ->
            val label = when (item.status) {
                DownloadManager.STATUS_PENDING -> "대기 중"
                DownloadManager.STATUS_RUNNING -> "다운로드 중"
                DownloadManager.STATUS_PAUSED -> "일시 중지"
                DownloadManager.STATUS_SUCCESSFUL -> "완료"
                DownloadManager.STATUS_FAILED -> "실패 (${item.reason})"
                else -> "파일 없음"
            }
            BrowserPanel(Modifier.fillMaxWidth()) {
                Text(item.record.fileName, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                val progress = if (item.totalBytes > 0)
                    (item.downloadedBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f) else null
                Text(label + (progress?.let { " · ${(it * 100).toInt()}%" } ?: ""),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                if (progress != null && item.status == DownloadManager.STATUS_RUNNING) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (item.status == DownloadManager.STATUS_SUCCESSFUL) {
                        TextButton(onClick = { controller.openDownload(item.record.id) },
                            modifier = Modifier.heightIn(min = 48.dp)) { Text("열기") }
                    }
                    TextButton(onClick = { controller.requestDeleteDownload(item) },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("삭제") }
                }
            }
        }
        items(ui.localDownloads, key = { "local-" + it.id }) { item ->
            BrowserPanel(Modifier.fillMaxWidth()) {
                Text(item.fileName, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(5.dp))
                Text("저장 완료", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { controller.openLocalDownload(item) },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("열기") }
                    TextButton(onClick = { controller.requestDeleteLocalDownload(item) },
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("삭제") }
                }
            }
        }
    }
}

@Composable
private fun PrivacyScreen(controller: BrowserController) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 12.dp)) {
        BrowserPanel(Modifier.fillMaxWidth()) {
            Text("방문 기록 삭제", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("삭제할 기간을 선택하세요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            HistoryRange.entries.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { range ->
                        Surface(onClick = { controller.deleteHistory(range) },
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.background,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(range.label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        BrowserPanel(Modifier.fillMaxWidth()) {
            Text("쿠키·캐시·사이트 데이터", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("전체 삭제 시 시크릿 탭도 닫힙니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Button(onClick = controller::requestClearSiteData,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = CircleShape) {
                Text("사이트 데이터 전체 삭제")
            }
        }
    }
}

@Composable
private fun SiteInfoScreen(ui: BrowserUiState) {
    val info = ui.siteInfo
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 12.dp)) {
        BrowserPanel(Modifier.fillMaxWidth()) {
            Text(info?.url?.let(AddressResolver::displayUrl) ?: "페이지 정보 없음",
                style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text(if (info?.url?.startsWith("https://") == true) "HTTPS 주소" else "HTTP 또는 기타 주소",
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text("WebView는 현재 연결의 완전한 보안 상태를 제공하지 않습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(14.dp))
        BrowserPanel(Modifier.fillMaxWidth()) {
            Text("인증서 정보", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text("대상: ${info?.subject ?: "정보 없음"}")
            Spacer(Modifier.height(8.dp))
            Text("발급 기관: ${info?.issuer ?: "정보 없음"}")
            info?.validFrom?.let {
                Spacer(Modifier.height(8.dp))
                Text("유효 기간 시작: ${DateFormat.getDateTimeInstance().format(Date(it))}")
            }
            info?.validTo?.let {
                Spacer(Modifier.height(8.dp))
                Text("유효 기간 종료: ${DateFormat.getDateTimeInstance().format(Date(it))}")
            }
        }
        Spacer(Modifier.height(14.dp))
        BrowserPanel(Modifier.fillMaxWidth()) {
            Text("사이트 권한", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("카메라·마이크·위치는 요청할 때마다 확인합니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BrowserDialogView(dialog: BrowserDialog, controller: BrowserController) {
    var prompt by remember(dialog) {
        mutableStateOf((dialog as? BrowserDialog.JavaScript)?.defaultValue.orEmpty())
    }
    val title = when (dialog) {
        is BrowserDialog.Notice -> "알림"
        is BrowserDialog.JavaScript -> dialog.origin
        is BrowserDialog.Permission -> "사이트 권한 요청"
        is BrowserDialog.Download -> "파일 다운로드"
        is BrowserDialog.DeleteDownload -> "다운로드 삭제"
        is BrowserDialog.DeleteLocalDownload -> "다운로드 삭제"
        is BrowserDialog.HttpNavigation -> "안전하지 않은 연결"
        BrowserDialog.ClearSiteData -> "사이트 데이터 삭제"
    }
    AlertDialog(onDismissRequest = controller::cancelDialog,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        title = { Text(title) },
        text = {
            when (dialog) {
                is BrowserDialog.Notice -> Text(dialog.text)
                is BrowserDialog.JavaScript -> Column {
                    Text(dialog.message)
                    if (dialog.kind == JavaScriptDialogKind.PROMPT) OutlinedTextField(
                        prompt, { prompt = it }, label = { Text("응답") },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp))
                }
                is BrowserDialog.Permission -> Text(dialog.origin + "에서 " +
                    dialog.kinds.joinToString {
                        when (it) {
                            com.kotlinsun.current.engine.WebPermissionKind.CAMERA -> "카메라"
                            com.kotlinsun.current.engine.WebPermissionKind.MICROPHONE -> "마이크"
                            com.kotlinsun.current.engine.WebPermissionKind.LOCATION -> "위치"
                            com.kotlinsun.current.engine.WebPermissionKind.PROTECTED_MEDIA -> "보호 미디어"
                        }
                    } + " 사용을 요청합니다.")
                is BrowserDialog.Download -> Text(dialog.fileName + "\n" + dialog.url +
                    "\n파일은 기기의 Downloads 폴더에 남습니다." +
                    if (dialog.privateMode) "\n시크릿 다운로드에는 로그인 쿠키를 전달하지 않습니다." else "")
                is BrowserDialog.DeleteDownload -> Text(dialog.fileName + " 파일과 목록 기록을 삭제합니다.")
                is BrowserDialog.DeleteLocalDownload -> Text(dialog.fileName + " 파일과 목록 기록을 삭제합니다.")
                is BrowserDialog.HttpNavigation -> Text(AddressResolver.displayUrl(dialog.url) +
                    "\nHTTP 연결은 암호화되지 않습니다. 이 주소를 계속 열까요?")
                BrowserDialog.ClearSiteData -> Text("모든 쿠키·캐시·사이트 데이터를 삭제하고 시크릿 탭을 닫습니다.")
            }
        },
        confirmButton = { TextButton(onClick = {
            controller.confirmDialog(if (dialog is BrowserDialog.JavaScript &&
                dialog.kind == JavaScriptDialogKind.PROMPT) prompt else null)
        }, modifier = Modifier.heightIn(min = 48.dp)) { Text(when (dialog) {
            is BrowserDialog.Notice -> "확인"
            is BrowserDialog.HttpNavigation -> "계속 열기"
            else -> "허용·실행"
        }) } },
        dismissButton = {
            if (dialog !is BrowserDialog.Notice && !(dialog is BrowserDialog.JavaScript &&
                    dialog.kind == JavaScriptDialogKind.ALERT))
                TextButton(onClick = controller::cancelDialog,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") }
        })
}
