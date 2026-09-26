package com.kotlinsun.current.browser

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.print.PrintDocumentAdapter
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.URLUtil
import com.kotlinsun.current.data.BookmarkRecord
import com.kotlinsun.current.data.BrowserStore
import com.kotlinsun.current.data.DownloadRecord
import com.kotlinsun.current.data.HistoryRecord
import com.kotlinsun.current.data.LocalDownloadRecord
import com.kotlinsun.current.data.TabRecord
import com.kotlinsun.current.engine.BlobReceiver
import com.kotlinsun.current.engine.BlobTransfer
import com.kotlinsun.current.engine.BrowserEngine
import com.kotlinsun.current.engine.DownloadRequest
import com.kotlinsun.current.engine.EngineCallbacks
import com.kotlinsun.current.engine.EngineSession
import com.kotlinsun.current.engine.EngineState
import com.kotlinsun.current.engine.FileSelectionRequest
import com.kotlinsun.current.engine.FullScreenRequest
import com.kotlinsun.current.engine.JavaScriptDialogRequest
import com.kotlinsun.current.engine.LinkTarget
import com.kotlinsun.current.engine.PageError
import com.kotlinsun.current.engine.PopupRequest
import com.kotlinsun.current.engine.SessionConfig
import com.kotlinsun.current.engine.SiteInfo
import com.kotlinsun.current.engine.TabMode
import com.kotlinsun.current.engine.WebPermissionKind
import com.kotlinsun.current.engine.WebPermissionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

interface BrowserHost {
    val activity: Activity
    fun openExternal(url: String)
    fun share(url: String)
    fun launchFileSelection(request: FileSelectionRequest)
    fun cancelFileSelection()
    fun requestPermissions(permissions: Array<String>, callback: (Boolean) -> Unit)
    fun setFullscreen(enabled: Boolean)
    fun openDownload(id: Long)
    fun openPdf(id: Long, privateMode: Boolean)
    fun openLocalFile(uri: Uri, mimeType: String?, privateMode: Boolean)
    fun print(adapter: PrintDocumentAdapter, jobName: String)
    fun isDefaultBrowser(): Boolean?
    fun requestDefaultBrowser(callback: (Boolean?) -> Unit)
}

class BrowserController(
    private val context: Context,
    private val store: BrowserStore,
    private val engine: BrowserEngine,
) : EngineCallbacks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableUi = MutableStateFlow(BrowserUiState())
    val ui: StateFlow<BrowserUiState> = mutableUi
    private var host: BrowserHost? = null
    private var started = false
    private var destroyed = false
    private var onboardingCompleted = false
    private var skipOnboardingForLaunch = false
    private var privateProfileName: String? = null
    private var privateCleanupFailed = false
    private val profilePrefix = "current-private-v1-"
    private val sessions = LinkedHashMap<String, EngineSession>(8, 0.75f, true)
    private val sessionJobs = mutableMapOf<String, Job>()
    private val restoreJobs = mutableMapOf<String, Job>()
    private val generations = mutableMapOf<String, Int>()
    private val stateCache = mutableMapOf<String, ByteArray>()
    private val queuedUrls = ArrayDeque<String>()
    private val queuedInputs = ArrayDeque<String>()
    private val suppressedVisits = mutableMapOf<String, String>()
    private val visitJobs = mutableMapOf<String, Job>()
    private val pendingVisitJobs = mutableSetOf<Job>()
    private var historyClearCount = 0
    private var historyRefreshVersion = 0
    private var quickLinksVersion = 0
    private var suggestionCutoff = 0L
    private var bookmarkDomains = emptyList<String>()
    private var historyDomains = emptyList<String>()
    private var suggestionJob: Job? = null
    private var pendingJavaScript: JavaScriptDialogRequest? = null
    private var pendingPermission: WebPermissionRequest? = null
    private var pendingDownload: Pair<String, DownloadRequest>? = null
    private var pendingFile: Pair<String, FileSelectionRequest>? = null
    private var activeFullScreen: Pair<String, FullScreenRequest>? = null
    private data class ClosedTab(val tab: BrowserTab, val state: ByteArray?)
    private data class QuickLinkResult(
        val links: List<AddressSuggestion>,
        val bookmarks: List<String>,
        val history: List<String>,
    )
    private val recentlyClosed = ArrayDeque<ClosedTab>()
    private var pendingHttpAction: (() -> Unit)? = null
    private var activeBlob: Pair<String, BlobTransfer>? = null
    var awaitingFileResult = false
        private set
    var awaitingPermissionResult = false
        private set
    private val pendingPdf = mutableMapOf<Long, Boolean>()
    private val approvedHttpRestores = mutableSetOf<String>()
    private var foreground = false
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun fileResultStarted() { awaitingFileResult = true }
    fun fileResultFinished() { awaitingFileResult = false }
    fun permissionResultStarted() { awaitingPermissionResult = true }
    fun permissionResultFinished() { awaitingPermissionResult = false }

    fun attach(value: BrowserHost) {
        host = value
        if (mutableUi.value.ready) ensureSelectedSession()
    }

    fun detach() {
        activeBlob?.second?.cancel()
        activeBlob = null
        exitFullscreen()
        cancelDialog()
        host?.cancelFileSelection()
        pendingFile = null
        sessions.keys.toList().forEach { id -> discardSession(id, save = true) }
        host = null
    }

    fun destroy() {
        destroyed = true
        detach()
        mutableUi.value = mutableUi.value.copy(tabs = mutableUi.value.tabs.filter { it.mode == TabMode.NORMAL },
            selectedPrivateId = null, activeMode = TabMode.NORMAL)
        destroyPrivateProfile()
        stateCache.clear()
        scope.cancel()
    }

    fun start(initialUrl: String?, skipOnboarding: Boolean = false) {
        skipOnboardingForLaunch = skipOnboardingForLaunch || skipOnboarding || initialUrl != null
        if (started) {
            initialUrl?.let(::openUrlFromIntent)
            return
        }
        initialUrl?.let(queuedUrls::add)
        started = true
        scope.launch {
            File(context.cacheDir, "blob_transfers").listFiles()?.forEach { it.delete() }
            var privateAvailable = runCatching { engine.supportsPrivateMode() }.getOrDefault(false)
            var privateReason = if (privateAvailable) null else "이 기기의 WebView는 시크릿 탭을 지원하지 않습니다."
            if (privateAvailable) {
                val cleanupSucceeded = runCatching {
                    var allDeleted = true
                    engine.privateProfileNames(profilePrefix).forEach { name ->
                        if (!runCatching { engine.deletePrivateProfile(name) }.getOrDefault(false)) allDeleted = false
                    }
                    allDeleted
                }.getOrDefault(false)
                if (!cleanupSucceeded) {
                    privateAvailable = false
                    privateReason = "이전 시크릿 데이터를 정리하지 못해 시크릿 탭을 사용할 수 없습니다."
                }
            }
            val preferences = runCatching { store.loadPreferences() }.getOrNull()
            suggestionCutoff = preferences?.suggestionCutoff ?: 0L
            val searchEngine = SearchEngine.fromStored(preferences?.searchEngine ?: "GOOGLE")
            val records = store.loadTabs().getOrElse {
                mutableUi.value = mutableUi.value.copy(startupError = "탭 데이터를 읽을 수 없습니다. 다시 시도해 주세요.")
                started = false
                return@launch
            }
            onboardingCompleted = preferences?.onboardingSeen ?: records.isNotEmpty()
            if (preferences?.onboardingSeen == null) runCatching {
                store.saveOnboardingSeen(onboardingCompleted)
            }
            val firstUrl = if (records.isEmpty() && queuedUrls.isNotEmpty()) queuedUrls.removeFirst() else null
            val tabs = records.map { BrowserTab(it.id, url = it.url, title = it.title) }
                .ifEmpty { listOf(BrowserTab(UUID.randomUUID().toString(), url = firstUrl, title = firstUrl ?: "새 탭")) }
            val selected = records.firstOrNull { it.selected }?.id ?: tabs.first().id
            mutableUi.value = BrowserUiState(tabs = tabs, selectedNormalId = selected,
                searchEngine = searchEngine, privateAvailable = privateAvailable,
                privateUnavailableReason = privateReason, ready = true,
                showOnboarding = !onboardingCompleted && !skipOnboardingForLaunch,
                themeChoice = ThemeChoice.entries.firstOrNull { it.name == preferences?.theme } ?: ThemeChoice.SYSTEM,
                textZoom = preferences?.textZoom?.coerceIn(75, 200) ?: 100,
                allowThirdPartyCookies = preferences?.thirdPartyCookies ?: false)
            if (records.isEmpty()) persistTabs()
            store.prunePreviews(tabs.map { it.id }.toSet())
            store.pruneTabStates(tabs.map { it.id }.toSet())
            tabs.forEach { tab ->
                scope.launch {
                    val preview = store.readPreview(tab.id)
                    if (preview != null && mutableUi.value.tabs.any { it.id == tab.id }) {
                        updateTab(tab.id) { it.copy(preview = preview) }
                    }
                }
            }
            ensureSelectedSession()
            refreshQuickLinks()
            while (queuedUrls.isNotEmpty()) newTab(queuedUrls.removeFirst())
            while (queuedInputs.isNotEmpty()) openInputFromIntent(queuedInputs.removeFirst())
        }
    }

    fun retryStart() { if (!started) start(null) }

    fun completeOnboarding() {
        if (!mutableUi.value.showOnboarding) return
        onboardingCompleted = true
        mutableUi.value = mutableUi.value.copy(showOnboarding = false)
        store.persistOnboardingSeen(true)
    }

    fun onNormalLaunch() {
        skipOnboardingForLaunch = false
        if (!onboardingCompleted && mutableUi.value.ready) {
            mutableUi.value = mutableUi.value.copy(showOnboarding = true)
        }
    }

    fun openUrlFromIntent(url: String) {
        skipOnboardingForLaunch = true
        mutableUi.value = mutableUi.value.copy(showOnboarding = false)
        if (!mutableUi.value.ready) queuedUrls.add(url) else newTab(url, TabMode.NORMAL)
    }

    fun openInputFromIntent(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        skipOnboardingForLaunch = true
        mutableUi.value = mutableUi.value.copy(showOnboarding = false)
        if (!mutableUi.value.ready) { queuedInputs.add(text); return }
        val resolved = AddressResolver.resolve(text, mutableUi.value.searchEngine)
            ?: mutableUi.value.searchEngine.searchUrl(text)
        openUrlFromIntent(resolved)
    }

    fun sessionForSelectedTab(): EngineSession? {
        val id = mutableUi.value.selectedId ?: return null
        return sessions.entries.firstOrNull { it.key == id }?.value
    }

    private fun tab(id: String): BrowserTab? = mutableUi.value.tabs.firstOrNull { it.id == id }
    private fun generation(id: String): Int = generations[id] ?: 0
    private fun invalidateRestore(id: String) {
        generations[id] = generation(id) + 1
        restoreJobs.remove(id)?.cancel()
    }

    private fun requestHttp(url: String, action: () -> Unit): Boolean {
        if (!url.startsWith("http://", true)) return false
        if (mutableUi.value.dialog is BrowserDialog.HttpNavigation &&
            (mutableUi.value.dialog as BrowserDialog.HttpNavigation).url == url) {
            pendingHttpAction = action
            return true
        }
        cancelDialog()
        pendingHttpAction = action
        mutableUi.value = mutableUi.value.copy(dialog = BrowserDialog.HttpNavigation(url))
        return true
    }

    fun submitAddress(input: String): Boolean {
        val url = AddressResolver.resolve(input, mutableUi.value.searchEngine) ?: return false
        val current = mutableUi.value.selectedTab ?: return false
        loadTab(current.id, url)
        mutableUi.value = mutableUi.value.copy(page = BrowserPage.WEB, suggestions = emptyList())
        return true
    }

    private fun loadTab(id: String, url: String) {
        if (requestHttp(url) { loadTabApproved(id, url) }) return
        loadTabApproved(id, url)
    }

    private fun loadTabApproved(id: String, url: String) {
        val current = tab(id) ?: return
        invalidateRestore(id)
        stateCache.remove(id)
        if (current.mode == TabMode.NORMAL) store.deleteTabState(id)
        updateTab(id) { it.copy(url = url, title = url, engine = EngineState(url = url)) }
        persistTabs()
        val session = sessions[id] ?: ensureSession(id, restoreSavedState = false)
        session?.allowHttpOnce(url)
        session?.load(url)
    }

    fun newTab(url: String? = null, mode: TabMode = mutableUi.value.activeMode, foreground: Boolean = true) {
        if (url != null && requestHttp(url) { newTabApproved(url, mode, foreground) }) return
        newTabApproved(url, mode, foreground)
    }

    private fun newTabApproved(url: String?, mode: TabMode, foreground: Boolean) {
        if (mode == TabMode.PRIVATE && !mutableUi.value.privateAvailable) {
            notice(mutableUi.value.privateUnavailableReason ?: "시크릿 탭을 사용할 수 없습니다.")
            return
        }
        if (mode == TabMode.PRIVATE && privateProfileName == null) {
            privateProfileName = profilePrefix + UUID.randomUUID()
        }
        if (foreground) {
            clearSuggestions()
            activeBlob?.second?.cancel()
            activeBlob = null
            cancelDialog()
            host?.cancelFileSelection()
            pendingFile = null
            mutableUi.value.selectedId?.let { sessions[it]?.let { session -> snapshot(it, session); session.pause() } }
        }
        val created = BrowserTab(UUID.randomUUID().toString(), mode, url, url ?: "새 탭")
        val state = mutableUi.value
        mutableUi.value = state.copy(
            tabs = state.tabs + created,
            selectedNormalId = if (foreground && mode == TabMode.NORMAL) created.id else state.selectedNormalId,
            selectedPrivateId = if (foreground && mode == TabMode.PRIVATE) created.id else state.selectedPrivateId,
            activeMode = if (foreground) mode else state.activeMode,
            page = if (foreground) BrowserPage.WEB else state.page,
        )
        publishClosedTabs()
        persistTabs()
        if (!foreground && url?.startsWith("http://", true) == true) approvedHttpRestores.add(created.id)
        if (foreground && url != null) ensureSession(created.id, restoreSavedState = false)?.let {
            it.allowHttpOnce(url)
            it.load(url)
        }
    }

    fun switchMode(mode: TabMode) {
        if (mode == TabMode.PRIVATE && !mutableUi.value.privateAvailable) {
            notice(mutableUi.value.privateUnavailableReason ?: "시크릿 탭을 사용할 수 없습니다.")
            return
        }
        if (mode == mutableUi.value.activeMode) return
        clearSuggestions()
        activeBlob?.second?.cancel()
        activeBlob = null
        cancelDialog()
        mutableUi.value.selectedId?.let { sessions[it]?.let { session -> snapshot(it, session); session.pause() } }
        val privateId = mutableUi.value.selectedPrivateId
        if (mode == TabMode.PRIVATE && privateId == null) {
            newTab(mode = TabMode.PRIVATE)
            mutableUi.value = mutableUi.value.copy(page = BrowserPage.TABS)
        } else {
            mutableUi.value = mutableUi.value.copy(activeMode = mode, page = BrowserPage.TABS)
            publishClosedTabs()
            ensureSelectedSession()
        }
    }

    fun selectTab(id: String) {
        val selected = tab(id) ?: return
        if (id != mutableUi.value.selectedId) {
            clearSuggestions()
            activeBlob?.second?.cancel()
            activeBlob = null
            cancelDialog()
            host?.cancelFileSelection()
            pendingFile = null
        }
        mutableUi.value.selectedId?.let { old ->
            if (old != id) sessions[old]?.let { snapshot(old, it); it.pause() }
        }
        val state = mutableUi.value
        mutableUi.value = state.copy(
            activeMode = selected.mode,
            selectedNormalId = if (selected.mode == TabMode.NORMAL) id else state.selectedNormalId,
            selectedPrivateId = if (selected.mode == TabMode.PRIVATE) id else state.selectedPrivateId,
            page = BrowserPage.WEB,
        )
        publishClosedTabs()
        persistTabs()
        ensureSelectedSession()
    }

    fun closeTab(id: String) {
        val closing = tab(id) ?: return
        if (mutableUi.value.selectedId == id) clearSuggestions()
        if (activeBlob?.first == id) {
            activeBlob?.second?.cancel()
            activeBlob = null
        }
        val closedState = if (restoreJobs[id] == null)
            sessions[id]?.saveState(256 * 1024) ?: stateCache[id]
        else stateCache[id]
        recentlyClosed.addFirst(ClosedTab(closing, closedState))
        while (recentlyClosed.count { it.tab.mode == closing.mode } > 10) {
            val oldest = recentlyClosed.lastOrNull { it.tab.mode == closing.mode } ?: break
            recentlyClosed.remove(oldest)
        }
        if (activeFullScreen?.first == id) exitFullscreen()
        if (mutableUi.value.selectedId == id) cancelDialog()
        if (pendingFile?.first == id) {
            host?.cancelFileSelection()
            pendingFile = null
        }
        discardSession(id, save = false)
        stateCache.remove(id)
        generations.remove(id)
        visitJobs.remove(id)
        suppressedVisits.remove(id)
        if (closing.mode == TabMode.NORMAL) {
            store.deleteTabState(id)
            store.deletePreview(id)
        }
        val old = mutableUi.value
        val remaining = old.tabs.filterNot { it.id == id }.toMutableList()
        if (remaining.none { it.mode == TabMode.NORMAL }) {
            remaining.add(BrowserTab(UUID.randomUUID().toString()))
        }
        val nextNormal = if (old.selectedNormalId == id) remaining.lastOrNull { it.mode == TabMode.NORMAL }?.id else old.selectedNormalId
        val nextPrivate = if (old.selectedPrivateId == id) remaining.lastOrNull { it.mode == TabMode.PRIVATE }?.id else old.selectedPrivateId
        val nextMode = if (old.activeMode == TabMode.PRIVATE && nextPrivate == null) TabMode.NORMAL else old.activeMode
        mutableUi.value = old.copy(tabs = remaining, selectedNormalId = nextNormal,
            selectedPrivateId = nextPrivate, activeMode = nextMode,
            lastClosedTabId = if (closing.mode == TabMode.PRIVATE && nextPrivate == null) null else id)
        if (nextPrivate == null) {
            pendingPdf.entries.removeAll { it.value }
            destroyPrivateProfile()
            recentlyClosed.removeAll { it.tab.mode == TabMode.PRIVATE }
        }
        publishClosedTabs()
        persistTabs()
        ensureSelectedSession()
    }

    fun closeAllTabs() {
        mutableUi.value.visibleTabs.map { it.id }.forEach(::closeTab)
        mutableUi.value = mutableUi.value.copy(page = BrowserPage.WEB)
    }

    private fun publishClosedTabs() {
        mutableUi.value = mutableUi.value.copy(closedTabs = recentlyClosed
            .filter { it.tab.mode == mutableUi.value.activeMode }.map {
            ClosedTabSummary(it.tab.id, it.tab.title, it.tab.url)
        })
    }

    fun reopenClosedTab(id: String? = recentlyClosed.firstOrNull {
        it.tab.mode == mutableUi.value.activeMode
    }?.tab?.id) {
        val closed = recentlyClosed.firstOrNull { it.tab.id == id } ?: return
        val url = closed.tab.url
        if (url != null && requestHttp(url) { reopenClosedTabApproved(closed) }) return
        reopenClosedTabApproved(closed)
    }

    private fun reopenClosedTabApproved(closed: ClosedTab) {
        if (!recentlyClosed.remove(closed)) return
        if (closed.tab.mode == TabMode.PRIVATE && privateProfileName == null) return
        val id = UUID.randomUUID().toString()
        val restored = closed.tab.copy(id = id, engine = EngineState(url = closed.tab.url))
        val state = mutableUi.value
        mutableUi.value = state.copy(tabs = state.tabs + restored,
            selectedNormalId = if (restored.mode == TabMode.NORMAL) id else state.selectedNormalId,
            selectedPrivateId = if (restored.mode == TabMode.PRIVATE) id else state.selectedPrivateId,
            activeMode = restored.mode, page = BrowserPage.WEB)
        closed.state?.let { stateCache[id] = it }
        if (restored.url?.startsWith("http://", true) == true) approvedHttpRestores.add(id)
        publishClosedTabs()
        persistTabs()
        ensureSelectedSession()
    }

    fun showPage(page: BrowserPage) {
        if (mutableUi.value.activeMode == TabMode.PRIVATE &&
            (page == BrowserPage.HISTORY || page == BrowserPage.DOWNLOADS)) {
            notice("시크릿 탭에서는 일반 방문 기록·다운로드 목록을 표시하지 않습니다.")
            return
        }
        if (page == BrowserPage.SITE_INFO) {
            val selected = mutableUi.value.selectedTab
            mutableUi.value = mutableUi.value.copy(siteInfo = sessionForSelectedTab()?.siteInfo()
                ?: SiteInfo(selected?.url, null, null, null, null))
        }
        mutableUi.value = mutableUi.value.copy(page = page)
        when (page) {
            BrowserPage.HISTORY -> refreshHistory()
            BrowserPage.BOOKMARKS -> refreshBookmarks()
            BrowserPage.DOWNLOADS -> refreshDownloads()
            BrowserPage.SETTINGS -> mutableUi.value = mutableUi.value.copy(
                defaultBrowser = host?.isDefaultBrowser())
            else -> Unit
        }
    }

    fun changeSearchEngine(value: SearchEngine) {
        mutableUi.value = mutableUi.value.copy(searchEngine = value)
        store.saveSearchEngine(value.name)
    }

    fun changeTheme(value: ThemeChoice) {
        mutableUi.value = mutableUi.value.copy(themeChoice = value)
        store.saveTheme(value.name)
    }

    fun changeTextZoom(value: Int) {
        val chosen = value.coerceIn(75, 200)
        mutableUi.value = mutableUi.value.copy(textZoom = chosen)
        store.saveTextZoom(chosen)
        sessions.forEach { (id, session) -> session.applySettings(
            tab(id)?.mode == TabMode.NORMAL && mutableUi.value.allowThirdPartyCookies,
            (chosen * context.resources.configuration.fontScale).toInt()) }
    }

    fun changeThirdPartyCookies(value: Boolean) {
        if (mutableUi.value.activeMode != TabMode.NORMAL) return
        mutableUi.value = mutableUi.value.copy(allowThirdPartyCookies = value)
        store.saveThirdPartyCookies(value)
        sessions.forEach { (id, session) -> session.applySettings(
            tab(id)?.mode == TabMode.NORMAL && value,
            (mutableUi.value.textZoom * context.resources.configuration.fontScale).toInt()) }
    }

    fun requestDefaultBrowser() {
        host?.requestDefaultBrowser { held ->
            mutableUi.value = mutableUi.value.copy(defaultBrowser = held)
            notice(when (held) {
                true -> "기본 브라우저로 설정되었습니다."
                false -> "기본 브라우저 설정이 완료되지 않았습니다."
                null -> "이 기기에서는 기본 브라우저 설정을 사용할 수 없습니다."
            })
        }
    }

    fun copyCurrentPage() {
        val url = mutableUi.value.selectedTab?.url?.takeIf(::isWebUrl) ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
    }

    fun shareCurrentPage() {
        mutableUi.value.selectedTab?.url?.takeIf(::isWebUrl)?.let { host?.share(it) }
    }

    fun printCurrentPage() {
        val tab = mutableUi.value.selectedTab ?: return
        val adapter = sessionForSelectedTab()?.createPrintAdapter(tab.title.ifBlank { "웹 페이지" })
        if (adapter == null) notice("인쇄할 페이지가 없습니다.")
        else host?.print(adapter, tab.title.ifBlank { "웹 페이지" })
    }

    fun goBack() {
        val session = sessionForSelectedTab()
        when {
            session?.state?.value?.canGoBack == true -> {
                val url = session.backUrl()
                val action: () -> Unit = {
                    if (url != null) session.allowHttpOnce(url)
                    session.goBack()
                }
                if (url == null || !requestHttp(url, action)) action()
            }
            mutableUi.value.visibleTabs.size > 1 -> mutableUi.value.selectedId?.let(::closeTab)
            mutableUi.value.selectedTab?.mode == TabMode.PRIVATE -> mutableUi.value.selectedId?.let(::closeTab)
            mutableUi.value.selectedTab?.url != null -> returnToNewTab()
            else -> host?.activity?.finish()
        }
    }

    fun goForward() {
        val session = sessionForSelectedTab() ?: return
        val url = session.forwardUrl()
        val action: () -> Unit = {
            if (url != null) session.allowHttpOnce(url)
            session.goForward()
        }
        if (url == null || !requestHttp(url, action)) action()
    }
    fun reloadOrStop() {
        val tab = mutableUi.value.selectedTab ?: return
        val session = sessionForSelectedTab()
        if (session == null) {
            tab.url?.let { url ->
                val action: () -> Unit = {
                    ensureSession(tab.id, restoreSavedState = false)?.let {
                        it.allowHttpOnce(url)
                        it.load(url)
                    }
                    Unit
                }
                if (!requestHttp(url, action)) action()
            }
        } else if (session.state.value.isLoading) session.stop()
        else {
            val url = tab.url
            val action = {
                if (url != null) session.allowHttpOnce(url)
                session.reload()
            }
            if (url == null || !requestHttp(url, action)) action()
        }
    }

    fun handleSystemBack() {
        when {
            activeFullScreen != null -> exitFullscreen()
            mutableUi.value.dialog != null -> cancelDialog()
            mutableUi.value.linkTarget != null -> dismissLinkMenu()
            mutableUi.value.pendingExternalUrl != null -> dismissExternal()
            mutableUi.value.page != BrowserPage.WEB -> showPage(BrowserPage.WEB)
            else -> goBack()
        }
    }

    fun onStop() {
        foreground = false
        sessions.forEach { (id, session) -> snapshot(id, session); session.pause() }
        persistTabs()
    }

    fun onResume() {
        foreground = true
        ensureSelectedSession()
        sessionForSelectedTab()?.resume()
        if (mutableUi.value.page == BrowserPage.DOWNLOADS) refreshDownloads()
    }

    private fun returnToNewTab() {
        val id = mutableUi.value.selectedId ?: return
        invalidateRestore(id)
        discardSession(id, save = false)
        stateCache.remove(id)
        if (tab(id)?.mode == TabMode.NORMAL) store.deleteTabState(id)
        updateTab(id) { it.copy(url = null, title = "새 탭", engine = EngineState()) }
        persistTabs()
    }

    private fun ensureSelectedSession() {
        val selected = mutableUi.value.selectedTab ?: return
        if (selected.url != null && host != null) ensureSession(selected.id)?.resume()
    }

    private fun ensureSession(id: String, restoreSavedState: Boolean = true, protectedId: String? = null): EngineSession? {
        sessions[id]?.let { return it }
        val selected = tab(id) ?: return null
        if (restoreSavedState && selected.url?.startsWith("http://", true) == true &&
            id !in approvedHttpRestores) {
            requestHttp(selected.url) {
                approvedHttpRestores.add(id)
                ensureSession(id)
            }
            return null
        }
        val activity = host?.activity ?: return null
        val profile = if (selected.mode == TabMode.PRIVATE) privateProfileName else null
        if (selected.mode == TabMode.PRIVATE && profile == null) return null
        val session = runCatching { engine.createSession(activity, id, SessionConfig(
            profileName = profile,
            allowThirdPartyCookies = selected.mode == TabMode.NORMAL && mutableUi.value.allowThirdPartyCookies,
            textZoom = (mutableUi.value.textZoom * context.resources.configuration.fontScale).toInt(),
        ), this) }
            .getOrElse {
                updateTab(id) { tab ->
                    tab.copy(engine = tab.engine.copy(isLoading = false,
                        error = PageError("WebView를 열 수 없습니다.", tab.url)))
                }
                return null
            }
        sessions[id] = session
        approvedHttpRestores.remove(id)
        selected.url?.let(session::allowHttpOnce)
        sessionJobs[id] = scope.launch {
            session.state.collect { state ->
                if (sessions.entries.any { it.key == id && it.value === session }) {
                    val old = tab(id) ?: return@collect
                    val url = state.url?.takeIf(::isWebUrl) ?: old.url
                    val title = state.title.ifBlank { old.title }
                    val engineState = if (state.url == null && !state.isLoading &&
                        old.engine.error != null) old.engine else state
                    updateTab(id) { it.copy(url = url, title = title, engine = engineState) }
                    if (old.url != url || old.title != title) persistTabs()
                }
            }
        }
        if (restoreSavedState) {
            val expectedGeneration = generation(id)
            val restore = scope.launch(start = CoroutineStart.LAZY) {
                val bytes = stateCache[id] ?: if (selected.mode == TabMode.NORMAL) store.readTabState(id) else null
                if (generation(id) != expectedGeneration || sessions.entries.none { it.key == id && it.value === session }) return@launch
                restoreJobs.remove(id)
                if (bytes == null) {
                    selected.url?.let(session::load)
                } else if (!session.restoreState(bytes, selected.url)) {
                    stateCache.remove(id)
                    discardSession(id, save = false)
                    if (selected.mode == TabMode.NORMAL) store.deleteTabState(id)
                    val fresh = ensureSession(id, restoreSavedState = false)
                    selected.url?.let { fresh?.load(it) }
                } else {
                    stateCache.remove(id)
                }
            }
            restoreJobs[id] = restore
            restore.start()
        }
        trimSessions(protectedId)
        return session
    }

    private fun discardSession(id: String, save: Boolean) {
        restoreJobs.remove(id)?.cancel()
        sessionJobs.remove(id)?.cancel()
        val session = sessions.remove(id) ?: return
        if (save) snapshot(id, session)
        session.close()
    }

    private fun trimSessions(protectedId: String? = null) {
        while (sessions.size > 3) {
            val oldest = sessions.keys.firstOrNull { it != mutableUi.value.selectedId && it != protectedId } ?: return
            discardSession(oldest, save = true)
        }
    }

    private fun snapshot(id: String, session: EngineSession) {
        val selected = tab(id) ?: return
        if (restoreJobs[id] == null && session.state.value.error == null &&
            session.state.value.url != null) {
            session.saveState(256 * 1024)?.let { bytes ->
                stateCache[id] = bytes
                while (stateCache.size > 24) stateCache.remove(stateCache.keys.first())
                if (selected.mode == TabMode.NORMAL) store.writeTabState(id, bytes)
            }
        }
        session.capturePreview()?.let { preview ->
            updateTab(id) { it.copy(preview = preview) }
            if (selected.mode == TabMode.NORMAL) store.writePreview(id, preview)
        }
    }

    private fun destroyPrivateProfile() {
        val name = privateProfileName ?: return
        if (mutableUi.value.tabs.any { it.mode == TabMode.PRIVATE }) return
        privateProfileName = null
        val removed = runCatching { engine.deletePrivateProfile(name) }.getOrDefault(false)
        if (!removed) {
            privateCleanupFailed = true
            mutableUi.value = mutableUi.value.copy(privateAvailable = false,
                privateUnavailableReason = "시크릿 데이터를 정리하지 못했습니다. 앱을 다시 시작해 주세요.")
            if (host != null) notice("시크릿 데이터 정리를 완료하지 못했습니다. 다음 실행 때 다시 시도합니다.")
        }
        stateCache.keys.filter { id -> tab(id)?.mode == TabMode.PRIVATE }.forEach(stateCache::remove)
    }

    private fun persistTabs() {
        val state = mutableUi.value
        if (!state.ready) return
        store.saveTabs(state.tabs.filter { it.mode == TabMode.NORMAL }.mapIndexed { index, item ->
            TabRecord(item.id, index, item.url, item.title, item.id == state.selectedNormalId)
        })
    }

    private fun updateTab(id: String, change: (BrowserTab) -> BrowserTab) {
        mutableUi.value = mutableUi.value.copy(tabs = mutableUi.value.tabs.map { if (it.id == id) change(it) else it })
    }

    private fun isWebUrl(url: String): Boolean = url.startsWith("https://", true) || url.startsWith("http://", true)

    private fun notice(message: String) { mutableUi.value = mutableUi.value.copy(dialog = BrowserDialog.Notice(message)) }

    fun confirmExternal() {
        val url = mutableUi.value.pendingExternalUrl ?: return
        dismissExternal()
        host?.openExternal(url)
    }
    fun dismissExternal() { mutableUi.value = mutableUi.value.copy(pendingExternalUrl = null) }

    fun updateSuggestions(query: String) {
        suggestionJob?.cancel()
        val text = query.trim()
        if (text.isEmpty()) {
            mutableUi.value = mutableUi.value.copy(suggestions = emptyList())
            return
        }
        val mode = mutableUi.value.activeMode
        suggestionJob = scope.launch {
            delay(150)
            val open = mutableUi.value.tabs.filter { it.mode == mode && it.url != null &&
                (it.url.contains(text, true) || it.title.contains(text, true)) }
                .take(3).map { AddressSuggestion(it.url.orEmpty(), it.title, "열린 탭", it.id) }
            val bookmarks = withContext(Dispatchers.IO) { runCatching { store.searchBookmarks(text) }.getOrDefault(emptyList()) }
                .take(3).map { AddressSuggestion(it.url, it.title, "북마크") }
            val history = if (mode == TabMode.NORMAL) withContext(Dispatchers.IO) {
                runCatching {
                    val ranked = store.loadTopSites(suggestionCutoff).associateBy { it.url }
                    store.searchHistory(text, suggestionCutoff).distinctBy { it.url }
                        .sortedWith(compareByDescending<HistoryRecord> { ranked[it.url]?.visitCount ?: 0L }
                            .thenByDescending { it.visitedAt })
                }.getOrDefault(emptyList())
            }.take(4).map { AddressSuggestion(it.url, it.title, "방문 기록") } else emptyList()
            if (mode == mutableUi.value.activeMode) mutableUi.value = mutableUi.value.copy(
                suggestions = (open + bookmarks + history).distinctBy { it.url }.take(8))
        }
    }

    fun inlineCompletion(input: String): String? {
        val text = input.trim()
        if (text.length < 2 || text.any { it.isWhitespace() }) return null
        val prefix = if (text.startsWith("https://", true)) "https://" else if (text.startsWith("http://", true)) "http://" else ""
        val hostPart = text.substring(prefix.length)
        if (hostPart.contains('/') || hostPart.contains('?') || hostPart.contains('#')) return null
        val candidates = if (mutableUi.value.activeMode == TabMode.PRIVATE) bookmarkDomains
            else bookmarkDomains + historyDomains
        val host = (mutableUi.value.visibleTabs.mapNotNull { it.url?.let { url -> Uri.parse(url).host } } +
            candidates).distinct()
            .firstOrNull { it.startsWith(hostPart, true) && it.length > hostPart.length } ?: return null
        return prefix + host
    }

    fun refreshQuickLinks() {
        val version = ++quickLinksVersion
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val allBookmarks = store.loadBookmarks()
                    val topSites = store.loadTopSites(suggestionCutoff)
                    val bookmarks = allBookmarks.distinctBy {
                        Uri.parse(it.url).host ?: it.url
                    }.take(4).map {
                        AddressSuggestion(it.url, it.title, "북마크")
                    }
                    val seen = bookmarks.mapNotNull { Uri.parse(it.url).host }.toMutableSet()
                    val frequent = topSites.mapNotNull { site ->
                        val host = Uri.parse(site.url).host ?: return@mapNotNull null
                        if (!seen.add(host)) null
                        else AddressSuggestion(site.url, site.title.ifBlank { host }, "자주 방문")
                    }.take(4)
                    QuickLinkResult(bookmarks + frequent,
                        allBookmarks.mapNotNull { Uri.parse(it.url).host }.distinct(),
                        topSites.mapNotNull { Uri.parse(it.url).host }.distinct())
                }.getOrDefault(QuickLinkResult(emptyList(), emptyList(), emptyList()))
            }
            if (version == quickLinksVersion) {
                bookmarkDomains = result.bookmarks
                historyDomains = result.history
                mutableUi.value = mutableUi.value.copy(quickLinks = result.links)
            }
        }
    }

    fun useSuggestion(item: AddressSuggestion) {
        if (item.tabId != null) selectTab(item.tabId)
        else mutableUi.value.selectedId?.let { loadTab(it, item.url) }
        mutableUi.value = mutableUi.value.copy(suggestions = emptyList(), page = BrowserPage.WEB)
    }

    fun clearSuggestions() {
        suggestionJob?.cancel()
        suggestionJob = null
        mutableUi.value = mutableUi.value.copy(suggestions = emptyList())
    }

    fun refreshHistory() {
        val version = ++historyRefreshVersion
        scope.launch {
            val records = withContext(Dispatchers.IO) { runCatching { store.loadHistory() }.getOrDefault(emptyList()) }
            if (version == historyRefreshVersion) mutableUi.value = mutableUi.value.copy(history = records)
        }
    }

    fun deleteVisit(id: Long) {
        scope.launch {
            historyClearCount++
            historyRefreshVersion++
            try {
                pendingVisitJobs.toList().forEach { it.join() }
                withContext(Dispatchers.IO) { store.deleteVisit(id) }
                rebaseNormalTabs()
                refreshHistory()
                recentlyClosed.clear()
                mutableUi.value = mutableUi.value.copy(lastClosedTabId = null)
                historyDomains = emptyList()
                publishClosedTabs()
                refreshQuickLinks()
            } catch (_: Exception) {
                notice("방문 기록을 삭제하지 못했습니다.")
            } finally {
                historyClearCount--
            }
        }
    }

    fun deleteHistory(range: HistoryRange) {
        scope.launch {
            historyClearCount++
            historyRefreshVersion++
            suggestionJob?.cancel()
            mutableUi.value = mutableUi.value.copy(suggestions = emptyList())
            pendingVisitJobs.toList().forEach { it.join() }
            val from = range.durationMillis?.let { System.currentTimeMillis() - it } ?: 0L
            try {
                withContext(Dispatchers.IO) { store.deleteHistorySince(from) }
                rebaseNormalTabs()
                refreshHistory()
                recentlyClosed.clear()
                mutableUi.value = mutableUi.value.copy(lastClosedTabId = null)
                historyDomains = emptyList()
                publishClosedTabs()
                refreshQuickLinks()
            } catch (_: Exception) {
                notice("방문 기록을 삭제하지 못했습니다.")
            } finally {
                historyClearCount--
            }
        }
    }

    private fun rebaseNormalTabs(reopen: Boolean = true) {
        val normal = mutableUi.value.tabs.filter { it.mode == TabMode.NORMAL }
        normal.forEach { item ->
            invalidateRestore(item.id)
            discardSession(item.id, save = false)
            stateCache.remove(item.id)
            item.url?.let { suppressedVisits[item.id] = it }
        }
        store.clearAllTabStates()
        if (reopen) ensureSelectedSession()
    }

    fun refreshBookmarks() {
        scope.launch {
            val records = withContext(Dispatchers.IO) { runCatching { store.loadBookmarks() }.getOrDefault(emptyList()) }
            mutableUi.value = mutableUi.value.copy(bookmarks = records)
        }
    }

    fun saveCurrentBookmark() {
        val current = mutableUi.value.selectedTab ?: return
        val url = current.url?.takeIf(::isWebUrl) ?: return
        scope.launch {
            runCatching {
                val existing = withContext(Dispatchers.IO) { store.loadBookmarks().firstOrNull { it.url == url } }
                withContext(Dispatchers.IO) {
                    if (existing == null) store.addBookmark(BookmarkRecord(UUID.randomUUID().toString(), url,
                        current.title, System.currentTimeMillis()))
                    else store.deleteBookmark(existing.id)
                }
            }.onSuccess { refreshBookmarks(); refreshQuickLinks() }
                .onFailure { notice("북마크를 저장하지 못했습니다.") }
        }
    }

    fun updateBookmark(id: String, title: String, url: String) {
        val resolved = AddressResolver.resolve(url, mutableUi.value.searchEngine)?.takeIf(::isWebUrl)
        if (resolved == null || title.isBlank()) { notice("북마크 제목과 웹 주소를 확인해 주세요."); return }
        scope.launch {
            runCatching {
                val record = withContext(Dispatchers.IO) {
                    store.loadBookmarks().firstOrNull { it.id == id }
                } ?: return@launch
                withContext(Dispatchers.IO) {
                    store.updateBookmark(record.copy(title = title.trim(), url = resolved))
                }
            }.onSuccess { refreshBookmarks(); refreshQuickLinks() }
                .onFailure { notice("북마크를 수정하지 못했습니다.") }
        }
    }

    fun deleteBookmark(id: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.deleteBookmark(id) } }
                .onSuccess { refreshBookmarks(); refreshQuickLinks() }
                .onFailure { notice("북마크를 삭제하지 못했습니다.") }
        }
    }

    fun openSavedUrl(url: String) {
        val id = mutableUi.value.selectedId ?: return
        loadTab(id, url)
        mutableUi.value = mutableUi.value.copy(page = BrowserPage.WEB)
    }

    fun refreshDownloads() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rows = store.loadDownloads().map { record ->
                        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(record.id))
                            ?: error("DownloadManager 조회 실패")
                        cursor.use {
                            if (it.moveToFirst()) DownloadItem(record,
                                it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                                it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                                it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                                it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
                            else DownloadItem(record, -1, 0, 0, -1)
                        }
                    }
                    rows to store.loadLocalDownloads()
                }
            }
            result.onSuccess { (rows, local) ->
                mutableUi.value = mutableUi.value.copy(downloads = rows,
                    localDownloads = local, downloadError = null)
            }.onFailure {
                mutableUi.value = mutableUi.value.copy(downloads = emptyList(),
                    localDownloads = emptyList(), downloadError = "다운로드 목록을 읽지 못했습니다.")
            }
        }
    }

    fun requestDeleteDownload(item: DownloadItem) {
        mutableUi.value = mutableUi.value.copy(dialog = BrowserDialog.DeleteDownload(item.record.id, item.record.fileName))
    }

    fun requestDeleteLocalDownload(item: LocalDownloadRecord) {
        mutableUi.value = mutableUi.value.copy(dialog = BrowserDialog.DeleteLocalDownload(item.id, item.fileName))
    }

    fun openDownload(id: Long) {
        val item = mutableUi.value.downloads.firstOrNull { it.record.id == id }
        if (item?.record?.mimeType?.substringBefore(';')?.equals("application/pdf", true) == true ||
            item?.record?.fileName?.endsWith(".pdf", true) == true) host?.openPdf(id, false)
        else host?.openDownload(id)
    }

    fun openLocalDownload(item: LocalDownloadRecord) {
        val mimeType = item.mimeType ?: if (item.fileName.endsWith(".pdf", true))
            "application/pdf" else null
        host?.openLocalFile(Uri.parse(item.contentUri), mimeType, false)
    }

    fun onDownloadComplete(id: Long) {
        if (id < 0) return
        refreshDownloads()
        checkPendingPdf(id, finalEvent = true)
    }

    private fun checkPendingPdf(id: Long, finalEvent: Boolean = false) {
        if (id !in pendingPdf) return
        scope.launch {
            val status = withContext(Dispatchers.IO) {
                runCatching { downloadManager.query(DownloadManager.Query().setFilterById(id))?.use {
                    if (it.moveToFirst()) it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    else null
                } }.getOrNull()
            }
            if (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING ||
                status == DownloadManager.STATUS_PAUSED) return@launch
            if (status == null && !finalEvent) {
                delay(500)
                checkPendingPdf(id, finalEvent = true)
                return@launch
            }
            val privateMode = pendingPdf.remove(id) ?: return@launch
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                if (foreground) host?.openPdf(id, privateMode)
            } else notice("PDF 다운로드가 완료되지 않았습니다. 다운로드 목록을 확인해 주세요.")
        }
    }

    private fun deleteDownload(id: Long) {
        val missingFile = mutableUi.value.downloads.firstOrNull { it.record.id == id }?.status == -1
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (downloadManager.remove(id) <= 0 && !missingFile)
                        error("다운로드 파일을 삭제하지 못했습니다.")
                    store.deleteDownload(id)
                }
            }.onSuccess { refreshDownloads() }
                .onFailure { notice("다운로드를 삭제하지 못했습니다.") }
        }
    }

    private fun deleteLocalDownload(id: Long) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val record = store.loadLocalDownloads().firstOrNull { it.id == id } ?: return@withContext
                    val uri = Uri.parse(record.contentUri)
                    val deleted = context.contentResolver.delete(uri, null, null)
                    if (deleted <= 0) {
                        val exists = try {
                            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true
                        } catch (_: java.io.FileNotFoundException) { false }
                        if (exists) error("파일을 삭제하지 못했습니다.")
                    }
                    store.deleteLocalDownload(id)
                }
            }.onSuccess { refreshDownloads() }
                .onFailure { notice("저장된 파일을 삭제하지 못했습니다.") }
        }
    }

    fun requestClearSiteData() { mutableUi.value = mutableUi.value.copy(dialog = BrowserDialog.ClearSiteData) }

    private fun clearSiteData() {
        suggestionCutoff = System.currentTimeMillis()
        store.saveSuggestionCutoff(suggestionCutoff)
        clearSuggestions()
        quickLinksVersion++
        historyDomains = emptyList()
        mutableUi.value = mutableUi.value.copy(quickLinks = mutableUi.value.quickLinks.filter {
            it.source == "북마크"
        })
        recentlyClosed.clear()
        mutableUi.value = mutableUi.value.copy(lastClosedTabId = null)
        publishClosedTabs()
        val privateIds = mutableUi.value.tabs.filter { it.mode == TabMode.PRIVATE }.map { it.id }
        privateIds.forEach(::closeTab)
        rebaseNormalTabs(reopen = false)
        runCatching { engine.clearDefaultSiteData(context) { cleared ->
            if (!destroyed) {
                ensureSelectedSession()
                refreshQuickLinks()
                notice(when {
                    !cleared -> "사이트 데이터 일부를 삭제하지 못했습니다."
                    privateCleanupFailed -> "일반 사이트 데이터는 삭제했지만 시크릿 데이터 정리는 다음 실행 때 다시 시도합니다."
                    else -> "쿠키·캐시·사이트 데이터를 삭제했습니다."
                })
            }
        } }.onFailure {
            ensureSelectedSession()
            notice("사이트 데이터를 삭제하지 못했습니다.")
        }
    }

    fun dismissLinkMenu() { mutableUi.value = mutableUi.value.copy(linkTarget = null) }

    fun openLink(foreground: Boolean, privateMode: Boolean = false) {
        val target = mutableUi.value.linkTarget ?: return
        val mode = if (privateMode) TabMode.PRIVATE else mutableUi.value.activeMode
        dismissLinkMenu()
        newTab(target.url, mode, foreground)
    }

    fun copyLink() {
        val url = mutableUi.value.linkTarget?.url ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", url))
        dismissLinkMenu()
    }

    fun shareLink() {
        val url = mutableUi.value.linkTarget?.url ?: return
        dismissLinkMenu()
        host?.share(url)
    }

    fun saveImage() {
        val target = mutableUi.value.linkTarget ?: return
        val url = target.imageUrl ?: return
        dismissLinkMenu()
        val selected = mutableUi.value.selectedTab ?: return
        onDownload(selected.id, DownloadRequest(url, sessionForSelectedTab()?.userAgent.orEmpty(),
            null, null, -1))
    }

    fun exitFullscreen() {
        val request = activeFullScreen ?: return
        activeFullScreen = null
        request.second.close()
        mutableUi.value = mutableUi.value.copy(fullScreenView = null)
        host?.setFullscreen(false)
    }

    fun confirmDialog(promptValue: String? = null) {
        val dialog = mutableUi.value.dialog
        val httpAction = if (dialog is BrowserDialog.HttpNavigation) pendingHttpAction else null
        when (dialog) {
            is BrowserDialog.JavaScript -> pendingJavaScript?.confirm(promptValue)
            is BrowserDialog.Permission -> {
                val request = pendingPermission
                val permissions = request?.kinds.orEmpty().mapNotNull {
                    when (it) {
                        WebPermissionKind.CAMERA -> android.Manifest.permission.CAMERA
                        WebPermissionKind.MICROPHONE -> android.Manifest.permission.RECORD_AUDIO
                        WebPermissionKind.LOCATION -> android.Manifest.permission.ACCESS_COARSE_LOCATION
                        WebPermissionKind.PROTECTED_MEDIA -> null
                    }
                }.distinct().toTypedArray()
                if (request != null) {
                    if (permissions.isEmpty()) {
                        request.grant()
                        pendingPermission = null
                    }
                    else host?.requestPermissions(permissions) { granted ->
                        if (pendingPermission === request) {
                            if (granted) request.grant() else request.deny()
                            pendingPermission = null
                        }
                    } ?: run {
                        request.deny()
                        pendingPermission = null
                    }
                }
            }
            is BrowserDialog.Download -> pendingDownload?.let { (id, request) ->
                if (request.url.startsWith("blob:", true)) beginBlobDownload(id, request, dialog.fileName)
                else enqueueDownload(id, request, dialog.fileName)
            }
            is BrowserDialog.DeleteDownload -> deleteDownload(dialog.id)
            is BrowserDialog.DeleteLocalDownload -> deleteLocalDownload(dialog.id)
            BrowserDialog.ClearSiteData -> clearSiteData()
            else -> Unit
        }
        pendingJavaScript = null
        if (dialog !is BrowserDialog.Permission) pendingPermission = null
        pendingDownload = null
        pendingHttpAction = null
        mutableUi.value = mutableUi.value.copy(dialog = null)
        httpAction?.invoke()
    }

    fun cancelDialog() {
        pendingJavaScript?.cancel()
        pendingJavaScript = null
        pendingPermission?.deny()
        pendingPermission = null
        pendingDownload = null
        pendingHttpAction = null
        mutableUi.value = mutableUi.value.copy(dialog = null)
    }

    private fun enqueueDownload(id: String, request: DownloadRequest, name: String) {
        val selected = tab(id) ?: return
        val cookie = if (selected.mode == TabMode.NORMAL) CookieManager.getInstance().getCookie(request.url) else null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
            val uri = Uri.parse(request.url)
            if (uri.scheme !in listOf("http", "https")) error("지원하지 않는 다운로드 주소")
            val item = DownloadManager.Request(uri)
                .setTitle(name)
                .setDescription(uri.host.orEmpty())
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            request.mimeType?.let(item::setMimeType)
            if (request.userAgent.isNotBlank()) item.addRequestHeader("User-Agent", request.userAgent)
            cookie?.let { item.addRequestHeader("Cookie", it) }
            val downloadId = downloadManager.enqueue(item)
            if (selected.mode == TabMode.NORMAL) {
                try {
                    store.addDownload(DownloadRecord(downloadId, request.url, name, request.mimeType,
                        System.currentTimeMillis()))
                } catch (error: Exception) {
                    val removed = runCatching { downloadManager.remove(downloadId) > 0 }.getOrDefault(false)
                    throw IllegalStateException(if (removed)
                        "다운로드 기록을 저장하지 못해 전송을 취소했습니다."
                    else "기록 저장에 실패했습니다. 기기의 Downloads에서 전송 상태를 확인해 주세요.", error)
                }
            }
            downloadId
            } }.onSuccess { downloadId ->
                if (request.mimeType?.substringBefore(';')?.equals("application/pdf", true) == true ||
                    name.endsWith(".pdf", true)) {
                    pendingPdf[downloadId] = selected.mode == TabMode.PRIVATE
                    checkPendingPdf(downloadId)
                }
                refreshDownloads()
            }.onFailure { notice("다운로드 오류: " + (it.message ?: "오류")) }
        }
    }

    private fun beginBlobDownload(id: String, request: DownloadRequest, name: String) {
        val session = sessions[id] ?: return
        session.blobUnavailableReason(request.url)?.let { notice(it); return }
        val privateMode = tab(id)?.mode == TabMode.PRIVATE
        val directory = File(context.cacheDir, "blob_transfers").apply { mkdirs() }
        val temporary = runCatching { File.createTempFile("transfer-", ".tmp", directory) }
            .getOrElse { notice("임시 파일을 만들지 못했습니다."); return }
        val cancelled = AtomicBoolean(false)
        lateinit var handle: BlobTransfer
        fun release() {
            if (activeBlob?.second === handle) activeBlob = null
        }
        val receiver = object : BlobReceiver {
            override fun onChunk(bytes: ByteArray, acknowledge: (Boolean) -> Unit) {
                scope.launch(Dispatchers.IO) {
                    val saved = !cancelled.get() && runCatching {
                        FileOutputStream(temporary, true).use { it.write(bytes) }
                    }.isSuccess
                    if (cancelled.get()) temporary.delete()
                    withContext(Dispatchers.Main) { acknowledge(saved && !cancelled.get()) }
                }
            }

            override fun onComplete(mimeType: String?) {
                if (cancelled.get()) return
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            runCatching {
                                if (cancelled.get()) error("취소됨")
                                val effectiveMime = mimeType?.takeIf { it.contains('/') }
                                    ?: request.mimeType
                                    ?: if (name.endsWith(".pdf", true)) "application/pdf"
                                    else "application/octet-stream"
                                val values = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                                    put(MediaStore.MediaColumns.MIME_TYPE, effectiveMime)
                                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                                }
                                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                    ?: error("파일을 만들지 못했습니다.")
                                var recordId: Long? = null
                                try {
                                    context.contentResolver.openOutputStream(uri)?.use { output ->
                                        temporary.inputStream().use { input ->
                                            val buffer = ByteArray(64 * 1024)
                                            while (true) {
                                                if (cancelled.get()) error("취소됨")
                                                val size = input.read(buffer)
                                                if (size < 0) break
                                                output.write(buffer, 0, size)
                                            }
                                        }
                                    } ?: error("파일을 열지 못했습니다.")
                                    if (cancelled.get()) error("취소됨")
                                    values.clear()
                                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                    if (context.contentResolver.update(uri, values, null, null) <= 0)
                                        error("파일 저장을 완료하지 못했습니다.")
                                    if (cancelled.get()) error("취소됨")
                                    if (!privateMode) recordId = store.addLocalDownload(LocalDownloadRecord(
                                        url = request.url, fileName = name, mimeType = effectiveMime,
                                        contentUri = uri.toString(), createdAt = System.currentTimeMillis()))
                                    if (cancelled.get()) error("취소됨")
                                    Triple(uri, recordId, effectiveMime)
                                } catch (error: Exception) {
                                    recordId?.let { runCatching { store.deleteLocalDownload(it) } }
                                    runCatching { context.contentResolver.delete(uri, null, null) }
                                    throw error
                                }
                            }
                        } finally {
                            temporary.delete()
                        }
                    }
                    release()
                    result.onSuccess { (uri, recordId, effectiveMime) ->
                        if (cancelled.get()) scope.launch(Dispatchers.IO) {
                            recordId?.let { runCatching { store.deleteLocalDownload(it) } }
                            runCatching { context.contentResolver.delete(uri, null, null) }
                        } else {
                            refreshDownloads()
                            if (effectiveMime.substringBefore(';').equals("application/pdf", true) && foreground)
                                host?.openLocalFile(uri, effectiveMime, privateMode)
                            else notice("파일을 Downloads에 저장했습니다.")
                        }
                    }.onFailure {
                        if (!cancelled.get()) notice("파일을 저장하지 못했습니다: " + (it.message ?: "오류"))
                    }
                }
            }

            override fun onError(message: String) {
                cancelled.set(true)
                release()
                temporary.delete()
                if (message != "취소됨") notice(message)
            }
        }
        val transfer = session.downloadBlob(request.url, 20L * 1024 * 1024, receiver)
        if (transfer == null) {
            temporary.delete()
            notice("이 페이지의 blob 파일을 안전하게 읽을 수 없습니다.")
        } else {
            handle = object : BlobTransfer {
                override fun cancel() {
                    cancelled.set(true)
                    transfer.cancel()
                    temporary.delete()
                }
            }
            activeBlob = id to handle
        }
    }

    private fun safeFileName(request: DownloadRequest): String {
        val guessed = runCatching {
            URLUtil.guessFileName(request.url, request.contentDisposition, request.mimeType)
        }.getOrNull().orEmpty().ifBlank { "download" }
        val safe = guessed.map { if (it == '/' || it == '\\' || it.code < 32 ||
            Character.getType(it) == Character.FORMAT.toInt()) '_' else it }
            .joinToString("").trim('.').take(120).ifBlank { "download" }
        val dot = safe.lastIndexOf('.')
        val suffix = "-" + System.currentTimeMillis().toString(36)
        return if (dot > 0) safe.substring(0, dot).take(100) + suffix + safe.substring(dot)
        else safe.take(100) + suffix
    }

    override fun onExternalNavigation(sessionId: String, url: String, hasGesture: Boolean) {
        if (sessionId != mutableUi.value.selectedId) return
        if (hasGesture) host?.openExternal(url)
        else mutableUi.value = mutableUi.value.copy(pendingExternalUrl = url)
    }

    override fun onNavigationStarted(sessionId: String) {
        if (activeBlob?.first == sessionId) {
            activeBlob?.second?.cancel()
            activeBlob = null
        }
        updateTab(sessionId) { it.copy(favicon = null) }
        if (sessionId == mutableUi.value.selectedId &&
            (pendingJavaScript != null || pendingPermission != null)) cancelDialog()
    }

    override fun onPageFinished(sessionId: String) {
        val session = sessions[sessionId] ?: return
        val state = session.state.value
        val url = state.url?.takeIf(::isWebUrl) ?: return
        updateTab(sessionId) { it.copy(url = url, title = state.title.ifBlank { it.title }, engine = state) }
        persistTabs()
        snapshot(sessionId, session)
        val current = tab(sessionId)
        if (current != null && current.mode == TabMode.NORMAL) scope.launch {
            visitJobs[sessionId]?.join()
            withContext(Dispatchers.IO) {
                store.updateVisit(url, state.title.ifBlank { url }, current.favicon)
            }
            refreshQuickLinks()
        }
    }

    override fun onVisited(sessionId: String, url: String, isReload: Boolean) {
        val selected = tab(sessionId) ?: return
        val suppressed = suppressedVisits.remove(sessionId)
        if (selected.mode == TabMode.PRIVATE || isReload || suppressed == url || historyClearCount > 0) return
        val job = scope.launch(Dispatchers.IO) {
            store.addVisit(HistoryRecord(url = url, title = selected.title, visitedAt = System.currentTimeMillis()))
        }
        visitJobs[sessionId] = job
        pendingVisitJobs.add(job)
        job.invokeOnCompletion {
            scope.launch {
                pendingVisitJobs.remove(job)
                if (visitJobs[sessionId] === job) visitJobs.remove(sessionId)
            }
        }
    }

    override fun onFavicon(sessionId: String, url: String, icon: ByteArray) {
        if (sessions[sessionId]?.state?.value?.url != url) return
        updateTab(sessionId) { it.copy(favicon = icon) }
        val selected = tab(sessionId) ?: return
        if (selected.mode == TabMode.NORMAL) scope.launch {
            visitJobs[sessionId]?.join()
            withContext(Dispatchers.IO) { store.updateVisit(url, selected.title, icon) }
        }
    }

    override fun onPopupRequested(parentId: String, request: PopupRequest): Boolean {
        val parent = tab(parentId) ?: return false
        if (parentId != mutableUi.value.selectedId) return false
        newTab(mode = parent.mode)
        val child = mutableUi.value.selectedTab ?: return false
        val session = ensureSession(child.id, restoreSavedState = false, protectedId = parentId)
            ?: run { closeTab(child.id); return false }
        request.accept(session)
        return true
    }

    override fun onCloseRequested(sessionId: String) { closeTab(sessionId) }

    override fun onRendererGone(sessionId: String, session: EngineSession) {
        if (sessions[sessionId] !== session) return
        discardSession(sessionId, save = false)
        stateCache.remove(sessionId)
        if (tab(sessionId)?.mode == TabMode.NORMAL) store.deleteTabState(sessionId)
        updateTab(sessionId) { it.copy(engine = it.engine.copy(isLoading = false,
            error = PageError("페이지가 종료되었습니다. 다시 시도해 주세요.", it.url))) }
        if (sessionId == mutableUi.value.selectedId && host != null) {
            Handler(Looper.getMainLooper()).post {
                if (!destroyed && sessionId == mutableUi.value.selectedId &&
                    sessions[sessionId] == null) ensureSession(sessionId, restoreSavedState = false)
            }
        }
    }

    override fun onFileSelection(sessionId: String, request: FileSelectionRequest) {
        if (sessionId != mutableUi.value.selectedId) request.complete(null)
        else {
            host?.cancelFileSelection()
            pendingFile = sessionId to request
            host?.launchFileSelection(request) ?: request.complete(null)
        }
    }

    fun fileSelectionCompleted(request: FileSelectionRequest) {
        if (pendingFile?.second === request) pendingFile = null
    }

    override fun onJavaScriptDialog(sessionId: String, request: JavaScriptDialogRequest) {
        if (sessionId != mutableUi.value.selectedId || mutableUi.value.dialog != null) { request.cancel(); return }
        pendingJavaScript = request
        mutableUi.value = mutableUi.value.copy(dialog = BrowserDialog.JavaScript(request.origin,
            request.message, request.kind, request.defaultValue))
    }

    override fun onFullScreen(sessionId: String, request: FullScreenRequest) {
        if (sessionId != mutableUi.value.selectedId) { request.close(); return }
        activeFullScreen = sessionId to request
        mutableUi.value = mutableUi.value.copy(fullScreenView = request.view)
        host?.setFullscreen(true)
    }

    override fun onFullScreenClosed(sessionId: String) {
        if (activeFullScreen?.first != sessionId) return
        activeFullScreen = null
        mutableUi.value = mutableUi.value.copy(fullScreenView = null)
        host?.setFullscreen(false)
    }

    override fun onLinkLongPress(sessionId: String, target: LinkTarget) {
        if (sessionId == mutableUi.value.selectedId && mutableUi.value.dialog == null) {
            mutableUi.value = mutableUi.value.copy(linkTarget = target)
        }
    }

    override fun onDownload(sessionId: String, request: DownloadRequest) {
        if (sessionId != mutableUi.value.selectedId || mutableUi.value.dialog != null) return
        if (!isWebUrl(request.url) && !request.url.startsWith("blob:", true)) {
            notice("이 형식의 다운로드는 아직 지원하지 않습니다."); return
        }
        val selected = tab(sessionId) ?: return
        pendingDownload = sessionId to request
        mutableUi.value = mutableUi.value.copy(dialog = BrowserDialog.Download(safeFileName(request),
            request.url, selected.mode == TabMode.PRIVATE))
    }

    override fun onPermission(sessionId: String, request: WebPermissionRequest) {
        if (sessionId != mutableUi.value.selectedId || mutableUi.value.dialog != null || pendingPermission != null) {
            request.deny()
            return
        }
        val origin = runCatching { Uri.parse(request.origin) }.getOrNull()
        if (origin?.scheme != "https" && !(origin?.scheme == "http" && origin.host == "localhost")) {
            request.deny()
            return
        }
        pendingPermission = request
        mutableUi.value = mutableUi.value.copy(dialog = BrowserDialog.Permission(request.origin, request.kinds))
    }

    override fun onPermissionCanceled(sessionId: String, request: WebPermissionRequest) {
        if (pendingPermission === request) {
            pendingPermission = null
            mutableUi.value = mutableUi.value.copy(dialog = null)
        }
    }

    override fun onHttpNavigation(sessionId: String, url: String) {
        if (sessionId != mutableUi.value.selectedId) return
        requestHttp(url) {
            sessions[sessionId]?.let { session ->
                session.allowHttpOnce(url)
                session.load(url)
            }
        }
    }
}
