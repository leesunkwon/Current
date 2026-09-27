package com.kotlinsun.current.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kotlinsun.current.R
import com.kotlinsun.current.engine.ReadableBlock
import com.kotlinsun.current.engine.ReadablePage
import com.kotlinsun.current.engine.TabMode
import com.kotlinsun.current.ui.theme.CurrentTheme

// 미리보기는 WebView와 BrowserController를 만들지 않고 실제 화면 컴포넌트를 렌더링합니다.
private val previewQuickLinks = listOf(
    AddressSuggestion("https://www.google.com", "Google", R.string.bookmarks),
    AddressSuggestion("https://developer.android.com", "Android Developers", R.string.history),
)

private fun previewTab(mode: TabMode) = BrowserTab(
    id = "preview-tab",
    mode = mode,
    title = "새 탭",
)

@Composable
private fun PreviewSurface(darkTheme: Boolean, content: @Composable () -> Unit) {
    CurrentTheme(darkTheme = darkTheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}

@Composable
private fun PreviewNewTab(darkTheme: Boolean, mode: TabMode) {
    val ui = BrowserUiState(
        tabs = listOf(previewTab(mode)),
        selectedNormalId = if (mode == TabMode.NORMAL) "preview-tab" else null,
        selectedPrivateId = if (mode == TabMode.PRIVATE) "preview-tab" else null,
        activeMode = mode,
        privateAvailable = true,
        privateLockAvailable = true,
        quickLinks = if (mode == TabMode.NORMAL) previewQuickLinks else emptyList(),
        ready = true,
    )
    CurrentTheme(darkTheme = darkTheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                NewTabPage(ui, onOpenQuickLink = {},
                    chromePadding = PaddingValues(bottom = 88.dp),
                    addressField = {
                        BrowserAddressField(ui, TextFieldValue(), editing = false, isError = false,
                            onValueChange = {}, onFocusChange = {}, onSubmit = {},
                            onSuggestion = {}, onDismissSuggestions = {},
                            modifier = Modifier.fillMaxWidth())
                    })
                Box(Modifier.align(Alignment.BottomCenter)) {
                    BrowserBottomBar(ui, onBack = {}, onForward = {}, onReloadOrStop = {},
                        onShowTabs = {}, onNewTab = {})
                }
            }
        }
    }
}

@Preview(name = "시작 화면 · 밝음", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun WelcomeLightPreview() {
    CurrentTheme(darkTheme = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            WelcomeScreen(onStart = {})
        }
    }
}

@Preview(name = "시작 화면 · 어두움", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun WelcomeDarkPreview() {
    CurrentTheme(darkTheme = true) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            WelcomeScreen(onStart = {})
        }
    }
}

@Preview(name = "새 탭 · 밝음", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun NewTabLightPreview() = PreviewNewTab(darkTheme = false, mode = TabMode.NORMAL)

@Preview(name = "새 탭 · 어두움", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun NewTabDarkPreview() = PreviewNewTab(darkTheme = true, mode = TabMode.NORMAL)

@Preview(name = "시크릿 새 탭", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun PrivateNewTabPreview() = PreviewNewTab(darkTheme = true, mode = TabMode.PRIVATE)

@Preview(name = "주소 입력", showBackground = true, widthDp = 393, heightDp = 100)
@Composable
private fun AddressFieldPreview() {
    val ui = BrowserUiState(tabs = listOf(previewTab(TabMode.NORMAL)),
        selectedNormalId = "preview-tab")
    CurrentTheme(darkTheme = false) {
        Surface(color = MaterialTheme.colorScheme.background) {
            BrowserAddressField(ui, TextFieldValue("example.com"), editing = true,
                isError = false, onValueChange = {}, onFocusChange = {}, onSubmit = {},
                onSuggestion = {}, onDismissSuggestions = {},
                modifier = Modifier.fillMaxWidth().padding(12.dp))
        }
    }
}

private val previewOpenTabs = listOf(
    BrowserTab("preview-current", url = "https://developer.android.com",
        title = "Android Developers", pinned = true),
    BrowserTab("preview-guide", url = "https://www.example.com/guide",
        title = "읽을거리", groupName = "참고 자료"),
    previewTab(TabMode.NORMAL).copy(id = "preview-empty"),
)

@Composable
private fun PreviewTabs(darkTheme: Boolean) {
    val ui = BrowserUiState(tabs = previewOpenTabs, selectedNormalId = "preview-current",
        privateAvailable = true,
        closedTabs = listOf(ClosedTabSummary("preview-closed", "이전에 닫은 페이지",
            "https://www.example.com/closed")))
    PreviewSurface(darkTheme) { TabSwitcherContent(ui, TabSwitcherActions()) }
}

@Preview(name = "탭 목록 · 밝음", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun TabsLightPreview() = PreviewTabs(darkTheme = false)

@Preview(name = "탭 목록 · 어두움", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun TabsDarkPreview() = PreviewTabs(darkTheme = true)

@Preview(name = "탭 목록 · 큰 글꼴", showBackground = true, widthDp = 360,
    heightDp = 720, fontScale = 1.5f)
@Composable
private fun TabsLargeTextPreview() = PreviewTabs(darkTheme = false)

@Composable
private fun PreviewSettings(darkTheme: Boolean) {
    val ui = BrowserUiState(themeChoice = if (darkTheme) ThemeChoice.DARK else ThemeChoice.LIGHT,
        textZoom = 125, privateLockAvailable = true, defaultBrowser = false)
    PreviewSurface(darkTheme) { SettingsScreenContent(ui, SettingsActions()) }
}

@Preview(name = "설정 · 밝음", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun SettingsLightPreview() = PreviewSettings(darkTheme = false)

@Preview(name = "설정 · 어두움", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun SettingsDarkPreview() = PreviewSettings(darkTheme = true)

@Preview(name = "설정 · 큰 글꼴", showBackground = true, widthDp = 360,
    heightDp = 720, fontScale = 1.5f)
@Composable
private fun SettingsLargeTextPreview() = PreviewSettings(darkTheme = false)

@Composable
private fun PreviewReader(darkTheme: Boolean) {
    val page = ReadablePage(url = "https://www.example.com/article",
        title = "읽기 화면의 예시 글",
        text = "브라우저에서 긴 글을 읽는 예시입니다.",
        blocks = listOf(
            ReadableBlock("좋아하는 글을 편안하게 읽어 보세요.", heading = true),
            ReadableBlock("읽기 모드는 페이지 본문에 집중할 수 있도록 내용을 정리합니다. " +
                "글꼴을 크게 설정해도 문단이 화면 너비에 맞춰 표시됩니다."),
            ReadableBlock("음성 읽기와 기기 내 번역은 사용자가 직접 시작할 수 있습니다."),
        ), language = "ko")
    PreviewSurface(darkTheme) {
        ReaderScreenContent(BrowserUiState(readerPage = page), ReaderActions())
    }
}

@Preview(name = "읽기 · 밝음", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun ReaderLightPreview() = PreviewReader(darkTheme = false)

@Preview(name = "읽기 · 어두움", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun ReaderDarkPreview() = PreviewReader(darkTheme = true)

@Preview(name = "읽기 · 큰 글꼴", showBackground = true, widthDp = 360,
    heightDp = 720, fontScale = 1.5f)
@Composable
private fun ReaderLargeTextPreview() = PreviewReader(darkTheme = false)
