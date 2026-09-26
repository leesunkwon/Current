package com.kotlinsun.current.browser

import android.view.View
import com.kotlinsun.current.data.BookmarkRecord
import com.kotlinsun.current.data.DownloadRecord
import com.kotlinsun.current.data.HistoryRecord
import com.kotlinsun.current.data.LocalDownloadRecord
import com.kotlinsun.current.engine.EngineState
import com.kotlinsun.current.engine.JavaScriptDialogKind
import com.kotlinsun.current.engine.LinkTarget
import com.kotlinsun.current.engine.SiteInfo
import com.kotlinsun.current.engine.TabMode
import com.kotlinsun.current.engine.WebPermissionKind

enum class BrowserPage { WEB, TABS, HISTORY, BOOKMARKS, DOWNLOADS, PRIVACY, SITE_INFO, SETTINGS }
enum class ThemeChoice(val label: String) { SYSTEM("시스템"), LIGHT("밝음"), DARK("어두움") }
enum class HistoryRange(val label: String, val durationMillis: Long?) {
    HOUR("지난 1시간", 60L * 60 * 1000),
    DAY("지난 24시간", 24L * 60 * 60 * 1000),
    WEEK("지난 7일", 7L * 24 * 60 * 60 * 1000),
    ALL("전체", null),
}

data class BrowserTab(
    val id: String,
    val mode: TabMode = TabMode.NORMAL,
    val url: String? = null,
    val title: String = "새 탭",
    val engine: EngineState = EngineState(),
    val preview: ByteArray? = null,
    val favicon: ByteArray? = null,
)

data class AddressSuggestion(
    val url: String,
    val title: String,
    val source: String,
    val tabId: String? = null,
)

data class ClosedTabSummary(val id: String, val title: String, val url: String?)

data class DownloadItem(
    val record: DownloadRecord,
    val status: Int,
    val reason: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
)

sealed interface BrowserDialog {
    data class Notice(val text: String) : BrowserDialog
    data class JavaScript(
        val origin: String,
        val message: String,
        val kind: JavaScriptDialogKind,
        val defaultValue: String?,
    ) : BrowserDialog
    data class Permission(val origin: String, val kinds: Set<WebPermissionKind>) : BrowserDialog
    data class Download(val fileName: String, val url: String, val privateMode: Boolean) : BrowserDialog
    data class DeleteDownload(val id: Long, val fileName: String) : BrowserDialog
    data class DeleteLocalDownload(val id: Long, val fileName: String) : BrowserDialog
    data class HttpNavigation(val url: String) : BrowserDialog
    data object ClearSiteData : BrowserDialog
}

data class BrowserUiState(
    val tabs: List<BrowserTab> = emptyList(),
    val selectedNormalId: String? = null,
    val selectedPrivateId: String? = null,
    val activeMode: TabMode = TabMode.NORMAL,
    val page: BrowserPage = BrowserPage.WEB,
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val themeChoice: ThemeChoice = ThemeChoice.SYSTEM,
    val textZoom: Int = 100,
    val allowThirdPartyCookies: Boolean = false,
    val defaultBrowser: Boolean? = null,
    val showOnboarding: Boolean = false,
    val privateAvailable: Boolean = false,
    val privateUnavailableReason: String? = null,
    val ready: Boolean = false,
    val startupError: String? = null,
    val pendingExternalUrl: String? = null,
    val dialog: BrowserDialog? = null,
    val linkTarget: LinkTarget? = null,
    val fullScreenView: View? = null,
    val suggestions: List<AddressSuggestion> = emptyList(),
    val quickLinks: List<AddressSuggestion> = emptyList(),
    val closedTabs: List<ClosedTabSummary> = emptyList(),
    val lastClosedTabId: String? = null,
    val history: List<HistoryRecord> = emptyList(),
    val bookmarks: List<BookmarkRecord> = emptyList(),
    val downloads: List<DownloadItem> = emptyList(),
    val localDownloads: List<LocalDownloadRecord> = emptyList(),
    val downloadError: String? = null,
    val siteInfo: SiteInfo? = null,
) {
    val selectedId: String? get() = if (activeMode == TabMode.NORMAL) selectedNormalId else selectedPrivateId
    val selectedTab: BrowserTab? get() = tabs.firstOrNull { it.id == selectedId }
    val visibleTabs: List<BrowserTab> get() = tabs.filter { it.mode == activeMode }
}
