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
