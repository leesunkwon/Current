package com.kotlinsun.current.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kotlinsun.current.engine.TabMode
import com.kotlinsun.current.R
import com.kotlinsun.current.ui.theme.BrowserNavigation

@Composable
internal fun BrowserMenu(
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
    val menuTitle = stringResource(R.string.browser_menu)
    fun perform(action: () -> Unit) {
        onExpandedChange(false)
        action()
    }
    Box {
        IconButton(onClick = { onExpandedChange(true) }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = menuTitle)
        }
        if (expanded) Popup(
            alignment = Alignment.TopEnd,
            offset = IntOffset(0, popupOffset),
            onDismissRequest = { onExpandedChange(false) },
            properties = PopupProperties(focusable = true,
                dismissOnBackPress = true, dismissOnClickOutside = true),
        ) {
            Box(Modifier.padding(10.dp)) {
                Surface(Modifier.width(menuWidth).heightIn(max = menuHeight)
                    .semantics { paneTitle = menuTitle },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shadowElevation = 12.dp,
                    tonalElevation = 0.dp) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(stringResource(R.string.browser_brand_label), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(menuTitle, style = MaterialTheme.typography.titleMedium)
                            }
                            IconButton(onClick = { onExpandedChange(false) }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close_menu))
                            }
                        }
                        ui.selectedTab?.url?.let { url ->
                            val bookmarked = ui.bookmarks.any { it.url == url }
                            Text(AddressResolver.displayUrl(url),
                                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            BrowserMenuSection(stringResource(R.string.current_page))
                            BrowserMenuItem(if (bookmarked) stringResource(R.string.remove_bookmark)
                                else stringResource(R.string.add_bookmark),
                                Icons.Filled.BookmarkBorder) {
                                perform(controller::saveCurrentBookmark)
                            }
                            BrowserMenuItem(stringResource(R.string.copy_page_address), Icons.Filled.ContentCopy) {
                                perform(controller::copyCurrentPage)
                            }
                            BrowserMenuItem(stringResource(R.string.share_page), Icons.Filled.Share) {
                                perform(controller::shareCurrentPage)
                            }
                            BrowserMenuItem(stringResource(R.string.print_page), Icons.Filled.Print) {
                                perform(controller::printCurrentPage)
                            }
                            BrowserMenuItem(stringResource(R.string.find_in_page), Icons.Filled.Search) {
                                perform(controller::showFind)
                            }
                            BrowserMenuItem(if (ui.selectedTab?.desktopMode == true)
                                stringResource(R.string.view_mobile_site) else
                                stringResource(R.string.view_desktop_site), Icons.Filled.Info) {
                                perform(controller::toggleDesktopMode)
                            }
                        }
                        BrowserMenuSection(stringResource(R.string.library))
                        if (ui.activeMode == TabMode.NORMAL) {
                            BrowserMenuItem(stringResource(R.string.history), Icons.Filled.History) {
                                perform { controller.showPage(BrowserPage.HISTORY) }
                            }
                        }
                        BrowserMenuItem(stringResource(R.string.bookmarks), Icons.Filled.BookmarkBorder) {
                            perform { controller.showPage(BrowserPage.BOOKMARKS) }
                        }
                        if (ui.activeMode == TabMode.NORMAL) {
                            BrowserMenuItem(stringResource(R.string.downloads), Icons.Filled.FileDownload) {
                                perform { controller.showPage(BrowserPage.DOWNLOADS) }
                            }
                        }
                        if (ui.selectedTab?.url != null) {
                            BrowserMenuItem(stringResource(R.string.site_info), Icons.Filled.Info) {
                                perform { controller.showPage(BrowserPage.SITE_INFO) }
                            }
                        }
                        BrowserMenuSection(stringResource(R.string.browser_section))
                        BrowserMenuItem(stringResource(R.string.privacy), Icons.Filled.Security) {
                            perform { controller.showPage(BrowserPage.PRIVACY) }
                        }
                        BrowserMenuItem(stringResource(R.string.settings), Icons.Filled.Settings) {
                            perform { controller.showPage(BrowserPage.SETTINGS) }
                        }
                        if (ui.privateAvailable) {
                            BrowserMenuItem(stringResource(R.string.new_private_tab), Icons.Filled.Lock) {
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
internal fun BrowserFindBar(ui: BrowserUiState, controller: BrowserController) {
    val focusRequester = remember { FocusRequester() }
    val findLabel = stringResource(R.string.find_in_page)
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val searchField: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(ui.findQuery, controller::updateFindQuery,
            modifier = modifier.focusRequester(focusRequester).semantics {
                contentDescription = findLabel
            },
            singleLine = true,
            placeholder = { Text(findLabel) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { controller.findNext(true) }))
    }
    val navigation: @Composable () -> Unit = {
        Text("${ui.findActive}/${ui.findTotal}",
            modifier = Modifier.padding(horizontal = 6.dp),
            style = MaterialTheme.typography.labelMedium)
        IconButton(onClick = { controller.findNext(false) },
            enabled = ui.findTotal > 0, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.previous_find_result))
        }
        IconButton(onClick = { controller.findNext(true) },
            enabled = ui.findTotal > 0, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.next_find_result))
        }
    }
    val close: @Composable () -> Unit = {
        IconButton(onClick = controller::closeFind, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close_find))
        }
    }
    if (LocalConfiguration.current.screenWidthDp < 480) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                searchField(Modifier.weight(1f))
                close()
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) { navigation() }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            searchField(Modifier.weight(1f))
            navigation()
            close()
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
    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(8.dp))
        .clickable(onClickLabel = label, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primaryContainer,
            RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
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
internal fun BrowserBottomBar(ui: BrowserUiState, controller: BrowserController) {
    val selected = ui.selectedTab
    val compact = LocalConfiguration.current.screenWidthDp < 320
    val horizontalPadding = if (compact) 8.dp else 16.dp
    val tabCountDescription = stringResource(if (ui.activeMode == TabMode.PRIVATE)
        R.string.private_tab_count else R.string.normal_tab_count, ui.visibleTabs.size)
    Box(Modifier.fillMaxWidth().navigationBarsPadding()
        .padding(start = horizontalPadding, top = 12.dp,
            end = horizontalPadding, bottom = 12.dp),
        contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape,
            color = if (ui.activeMode == TabMode.PRIVATE) Color(0xFF363636) else BrowserNavigation,
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            shadowElevation = 16.dp) {
            Row(Modifier.horizontalScroll(rememberScrollState())
                .padding(if (compact) 6.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = controller::goBack,
                    enabled = selected?.engine?.canGoBack == true, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back))
                }
                IconButton(onClick = controller::goForward,
                    enabled = selected?.engine?.canGoForward == true, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.forward))
                }
                IconButton(onClick = controller::reloadOrStop,
                    enabled = selected?.url != null, modifier = Modifier.size(48.dp)) {
                    Icon(if (selected?.engine?.isLoading == true) Icons.Filled.Stop else Icons.Filled.Refresh,
                        contentDescription = stringResource(if (selected?.engine?.isLoading == true)
                            R.string.stop_loading else R.string.reload))
                }
                IconButton(onClick = { controller.showPage(BrowserPage.TABS) },
                    modifier = Modifier.size(48.dp).semantics { contentDescription = tabCountDescription }) {
                    Box(Modifier.size(27.dp).border(1.5.dp, Color.White,
                        RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                        Text(ui.visibleTabs.size.toString(), style = MaterialTheme.typography.bodySmall,
                            color = Color.White, maxLines = 1)
                    }
                }
                IconButton(onClick = { controller.newTab() },
                    modifier = Modifier.size(48.dp).background(Color.White, CircleShape)) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.new_tab),
                        tint = BrowserNavigation)
                }
            }
        }
    }
}
