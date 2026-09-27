package com.kotlinsun.current.browser

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.content.ComponentCallbacks2
import android.webkit.WebView
import android.print.PrintDocumentAdapter
import android.provider.MediaStore
import android.webkit.WebSettings
import com.kotlinsun.current.R
import com.kotlinsun.current.data.BookmarkRecord
import com.kotlinsun.current.data.BookmarkFolderRecord
import com.kotlinsun.current.data.BrowserStore
import com.kotlinsun.current.data.HistoryRecord
import com.kotlinsun.current.data.LocalDownloadRecord
import com.kotlinsun.current.data.SitePermissionRecord
import com.kotlinsun.current.engine.BlobReceiver
import com.kotlinsun.current.engine.BlobTransfer
import com.kotlinsun.current.engine.BLOB_TRANSFER_CANCELLED
import com.kotlinsun.current.engine.BrowserEngine
import com.kotlinsun.current.engine.ClientCertificateRequest
import com.kotlinsun.current.engine.DownloadRequest
import com.kotlinsun.current.engine.EngineCallbacks
import com.kotlinsun.current.engine.EngineSession
import com.kotlinsun.current.engine.EngineState
import com.kotlinsun.current.engine.FileSelectionRequest
import com.kotlinsun.current.engine.FullScreenRequest
import com.kotlinsun.current.engine.HttpAuthenticationRequest
import com.kotlinsun.current.engine.JavaScriptDialogRequest
import com.kotlinsun.current.engine.LinkTarget
import com.kotlinsun.current.engine.PageError
import com.kotlinsun.current.engine.PopupRequest
import com.kotlinsun.current.engine.SessionConfig
import com.kotlinsun.current.engine.SiteInfo
import com.kotlinsun.current.engine.TabMode
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
    fun openExternal(url: String, privateMode: Boolean)
    fun share(url: String)
    fun shareFile(uri: Uri, mimeType: String?, fileName: String)
    fun launchFileSelection(request: FileSelectionRequest)
    fun cancelFileSelection()
    fun clearPrivateUploadCaptures(): Boolean
    fun requestPermissions(permissions: Array<String>, callback: (Boolean) -> Unit)
    fun chooseClientCertificate(request: ClientCertificateRequest)
    fun cancelClientCertificateSelection()
    fun canAuthenticatePrivate(): Boolean
    fun authenticatePrivate(callback: (Boolean) -> Unit)
    fun cancelPrivateAuthentication()
    fun setFullscreen(enabled: Boolean)
    fun openDownload(id: Long)
    fun openDownloadsFolder()
    fun openWebViewSettings()
    fun openPdf(id: Long, privateMode: Boolean)
    fun openLocalFile(uri: Uri, mimeType: String?, privateMode: Boolean)
    fun print(adapter: PrintDocumentAdapter, jobName: String)
    fun isDefaultBrowser(): Boolean?
    fun requestDefaultBrowser(callback: (Boolean?) -> Unit)
    fun pickBookmarkFile(): Boolean
    fun createBookmarkFile(): Boolean
    fun pinPage(title: String, url: String, favicon: ByteArray?, pwa: PwaSite?): Boolean
    fun openWebApp(site: PwaSite)
    fun speakReader(text: String, finished: (Boolean) -> Unit)
    fun stopReaderSpeech()
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
    private var suggestionJob: Job? = null
    private var suggestionVersion = 0
    private var pendingJavaScript: JavaScriptDialogRequest? = null
    private var pendingHttpAuthentication: HttpAuthenticationRequest? = null
    private val sitePermissions = SitePermissionCoordinator()
    private val savedPermissions = mutableMapOf<Pair<String, String>, SitePermissionRecord>()
    private var pendingDownload: Pair<String, DownloadRequest>? = null
    private var pendingFile: Pair<String, FileSelectionRequest>? = null
    private var activeFullScreen: Pair<String, FullScreenRequest>? = null
    private data class ClosedTab(val tab: BrowserTab, val state: ByteArray?)
    private data class QuickLinkResult(
        val links: List<AddressSuggestion>,
        val bookmarks: List<String>,
    )
    private val recentlyClosed = ArrayDeque<ClosedTab>()
    private var pendingBookmarkImport: ParsedBookmarkBackup? = null
    private var pendingBookmarkPreview: BookmarkImportPreview? = null
    private var pendingHttpAction: (() -> Unit)? = null
    private var activeBlob: Pair<String, BlobTransfer>? = null
    var awaitingFileResult = false
        private set
    var awaitingPermissionResult = false
        private set
    private val pendingPdf = mutableMapOf<Long, Boolean>()
    private var downloadRefreshVersion = 0
    private val retryingDownloadIds = mutableSetOf<Long>()
    private val approvedHttpRestores = mutableSetOf<String>()
    private val pendingHttpRedirects = mutableMapOf<String, String>()
    private var pendingExternalSessionId: String? = null
    private var foreground = false
    private var privateUnlockGeneration = 0
    private var privateUnlockInFlight = false
    private var pendingClientCertificate: Pair<String, ClientCertificateRequest>? = null
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val downloadRepository = DownloadRepository(context, store, downloadManager)
    private val bookmarkBackupService = BookmarkBackupService(context, store)
    private val tabRestoration = TabRestoration(store)

    fun fileResultStarted() { awaitingFileResult = true }
    fun fileResultFinished() { awaitingFileResult = false }
    fun permissionResultStarted() { awaitingPermissionResult = true }
    fun permissionResultFinished() { awaitingPermissionResult = false }

    fun attach(value: BrowserHost) {
        host = value
        mutableUi.value = mutableUi.value.copy(privateLockAvailable = value.canAuthenticatePrivate())
        if (mutableUi.value.ready) ensureSelectedSession()
    }

    fun detach() {
        host?.stopReaderSpeech()
        mutableUi.value = mutableUi.value.copy(readerSpeaking = false)
        cancelClientCertificate()
        privateUnlockGeneration++
        privateUnlockInFlight = false
        host?.cancelPrivateAuthentication()
        closeFind()
        activeBlob?.second?.cancel()
        activeBlob = null
        exitFullscreen()
        cancelDialog()
        host?.cancelFileSelection()
        pendingFile = null
        sessions.keys.toList().forEach { id -> discardSession(id, save = true) }
        host?.clearPrivateUploadCaptures()
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
            var privateReason = if (privateAvailable) null else context.getString(R.string.private_unsupported)
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
                    privateReason = context.getString(R.string.private_previous_cleanup_error)
                }
            }
            val preferences = runCatching { store.loadPreferences() }.getOrNull()
            suggestionCutoff = preferences?.suggestionCutoff ?: 0L
            val searchEngine = SearchEngine.fromStored(preferences?.searchEngine ?: "GOOGLE")
            val records = store.loadTabs().getOrElse {
                mutableUi.value = mutableUi.value.copy(startupError = context.getString(R.string.tab_read_error))
                started = false
                return@launch
            }
            val permissionRecords = withContext(Dispatchers.IO) {
                runCatching { store.loadSitePermissions() }.getOrDefault(emptyList())
            }
            savedPermissions.clear()
            permissionRecords.forEach { savedPermissions[it.origin to it.kind] = it }
            onboardingCompleted = preferences?.onboardingSeen ?: records.isNotEmpty()
            if (preferences?.onboardingSeen == null) runCatching {
                store.saveOnboardingSeen(onboardingCompleted)
            }
            val firstUrl = if (records.isEmpty() && queuedUrls.isNotEmpty()) queuedUrls.removeFirst() else null
            val tabs = records.map { TabRestoration.fromRecord(it) }
                .ifEmpty { listOf(BrowserTab(UUID.randomUUID().toString(), url = firstUrl,
                    title = firstUrl ?: context.getString(R.string.new_tab))) }
            val selected = records.firstOrNull { it.selected }?.id ?: tabs.first().id
            mutableUi.value = BrowserUiState(tabs = tabs, selectedNormalId = selected,
                searchEngine = searchEngine, privateAvailable = privateAvailable,
                privateUnavailableReason = privateReason, ready = true,
                showOnboarding = !onboardingCompleted && !skipOnboardingForLaunch,
                themeChoice = ThemeChoice.entries.firstOrNull { it.name == preferences?.theme } ?: ThemeChoice.SYSTEM,
                textZoom = preferences?.textZoom?.coerceIn(75, 200) ?: 100,
                allowThirdPartyCookies = preferences?.thirdPartyCookies ?: false)
            mutableUi.value = mutableUi.value.copy(
                homePageUrl = preferences?.homePageUrl,
                trackingProtection = preferences?.trackingProtection ?: false,
                trackingExceptions = preferences?.trackingExceptions ?: emptySet(),
                privateLockAvailable = host?.canAuthenticatePrivate() == true)
            mutableUi.value = mutableUi.value.copy(savedSitePermissions = permissionRecords)
            pendingBookmarkPreview?.let { preview ->
                mutableUi.value = mutableUi.value.copy(page = BrowserPage.BOOKMARKS,
                    bookmarkImportPreview = preview)
            }
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
        pendingHttpRedirects.remove(id)
        invalidateRestore(id)
        stateCache.remove(id)
        if (current.mode == TabMode.NORMAL) store.deleteTabState(id)
        updateTab(id) { it.copy(url = url, title = url, engine = EngineState(url = url)) }
        persistTabs()
        val session = sessions[id] ?: ensureSession(id, restoreSavedState = false)
        session?.allowHttpOnce(url)
        session?.load(url)
    }

    fun newTab(url: String? = null, mode: TabMode = mutableUi.value.activeMode,
               foreground: Boolean = true, useHomePage: Boolean = true) {
        val destination = url ?: mutableUi.value.homePageUrl.takeIf {
            useHomePage && mode == TabMode.NORMAL
        }
        if (destination != null && requestHttp(destination) {
                newTabApproved(destination, mode, foreground)
            }) return
        newTabApproved(destination, mode, foreground)
    }

    private fun newTabApproved(url: String?, mode: TabMode, foreground: Boolean) {
        if (mode == TabMode.PRIVATE && !mutableUi.value.privateAvailable) {
            notice(mutableUi.value.privateUnavailableReason ?: context.getString(R.string.private_unavailable))
            return
        }
        if (mode == TabMode.PRIVATE && privateProfileName == null) {
            privateProfileName = profilePrefix + UUID.randomUUID()
        }
        if (foreground) {
            if (mutableUi.value.page == BrowserPage.READER) closeReader()
            closeFind()
            clearSuggestions()
            dismissExternal()
            activeBlob?.second?.cancel()
            activeBlob = null
            cancelDialog()
            host?.cancelFileSelection()
            pendingFile = null
            mutableUi.value.selectedId?.let { sessions[it]?.let { session -> snapshot(it, session); session.pause() } }
        }
        val created = BrowserTab(UUID.randomUUID().toString(), mode, url,
            url ?: context.getString(R.string.new_tab))
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
        if (foreground && mode == TabMode.PRIVATE) requestPrivateUnlock()
    }

    fun switchMode(mode: TabMode) {
        if (mode == TabMode.PRIVATE && !mutableUi.value.privateAvailable) {
            notice(mutableUi.value.privateUnavailableReason ?: context.getString(R.string.private_unavailable))
            return
        }
        if (mode == mutableUi.value.activeMode) return
        if (mutableUi.value.page == BrowserPage.READER) closeReader()
        if (mode == TabMode.NORMAL && privateUnlockInFlight) {
            privateUnlockGeneration++
            privateUnlockInFlight = false
            host?.cancelPrivateAuthentication()
        }
        cancelClientCertificate()
        closeFind()
        clearSuggestions()
        dismissExternal()
        activeBlob?.second?.cancel()
        activeBlob = null
        cancelDialog()
        host?.cancelFileSelection()
        pendingFile = null
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
        if (mode == TabMode.PRIVATE) requestPrivateUnlock()
    }

    fun selectTab(id: String) {
        val selected = tab(id) ?: return
        if (mutableUi.value.page == BrowserPage.READER) closeReader()
        if (id != mutableUi.value.selectedId) {
            cancelClientCertificate()
            closeFind()
            clearSuggestions()
            dismissExternal()
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
        val pendingHttp = pendingHttpRedirects.remove(id)
        ensureSelectedSession()
        pendingHttp?.let { onHttpNavigation(id, it) }
        if (selected.mode == TabMode.PRIVATE) requestPrivateUnlock()
    }

    fun closeTab(id: String) {
        val closing = tab(id) ?: return
        if (mutableUi.value.selectedId == id && mutableUi.value.page == BrowserPage.READER) {
            closeReader()
            mutableUi.value = mutableUi.value.copy(page = BrowserPage.WEB)
        }
        if (pendingClientCertificate?.first == id) cancelClientCertificate()
        if (pendingExternalSessionId == id) dismissExternal()
        if (mutableUi.value.selectedId == id) closeFind()
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
        pendingHttpRedirects.remove(id)
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
            remaining.add(BrowserTab(UUID.randomUUID().toString(),
                title = context.getString(R.string.new_tab)))
        }
        val nextNormal = if (old.selectedNormalId == id) remaining.lastOrNull { it.mode == TabMode.NORMAL }?.id else old.selectedNormalId
        val nextPrivate = if (old.selectedPrivateId == id) remaining.lastOrNull { it.mode == TabMode.PRIVATE }?.id else old.selectedPrivateId
        val nextMode = if (old.activeMode == TabMode.PRIVATE && nextPrivate == null) TabMode.NORMAL else old.activeMode
        mutableUi.value = old.copy(tabs = remaining, selectedNormalId = nextNormal,
            selectedPrivateId = nextPrivate, activeMode = nextMode,
            lastClosedTabId = if (closing.mode == TabMode.PRIVATE && nextPrivate == null) null else id)
        if (nextPrivate == null) {
            privateUnlockGeneration++
            privateUnlockInFlight = false
            host?.cancelPrivateAuthentication()
            mutableUi.value = mutableUi.value.copy(privateLocked = false)
            pendingPdf.entries.removeAll { it.value }
            if (host?.clearPrivateUploadCaptures() == false)
                notice(context.getString(R.string.private_upload_cleanup_error))
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

    fun toggleTabPinned(id: String) {
        val current = tab(id) ?: return
        updateTab(id) { it.copy(pinned = !current.pinned) }
        persistTabs()
    }

    fun setTabGroup(id: String, name: String) {
        if (tab(id) == null) return
        val normalized = name.trim().take(32)
        if (normalized.any { it.code < 32 || it.code == 127 }) return
        updateTab(id) { it.copy(groupName = normalized.takeIf { value -> value.isNotEmpty() }) }
        persistTabs()
    }

    fun renameTabGroup(oldName: String, newName: String) {
        val normalized = newName.trim().take(32)
        if (normalized.isEmpty() || normalized.any { it.code < 32 || it.code == 127 }) return
        val mode = mutableUi.value.activeMode
        mutableUi.value = mutableUi.value.copy(tabs = mutableUi.value.tabs.map {
            if (it.mode == mode && it.groupName == oldName) it.copy(groupName = normalized) else it
        })
        persistTabs()
    }

    fun ungroupTabs(name: String) {
        val mode = mutableUi.value.activeMode
        mutableUi.value = mutableUi.value.copy(tabs = mutableUi.value.tabs.map {
            if (it.mode == mode && it.groupName == name) it.copy(groupName = null) else it
        })
        persistTabs()
    }

    fun moveTab(id: String, direction: Int) {
        val state = mutableUi.value
        val ordered = state.visibleTabs.toMutableList()
        val index = ordered.indexOfFirst { it.id == id }
        val target = index + direction
        if (index < 0 || target !in ordered.indices ||
            ordered[index].pinned != ordered[target].pinned) return
        java.util.Collections.swap(ordered, index, target)
        val reordered = ordered.iterator()
        mutableUi.value = state.copy(tabs = state.tabs.map {
            if (it.mode == state.activeMode) reordered.next() else it
        })
        persistTabs()
    }

    fun closeDuplicateTabs() {
        val ordered = mutableUi.value.visibleTabs
        val selectedId = mutableUi.value.selectedId
        val duplicates = ordered.filter { it.url?.let(::isWebUrl) == true }
            .groupBy { it.url.orEmpty() }
            .values.flatMap { group ->
                val keep = group.firstOrNull { it.id == selectedId }
                    ?: group.firstOrNull { it.pinned } ?: group.first()
                group.filterNot { it.id == keep.id }.map { it.id }
            }
        duplicates.forEach(::closeTab)
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
        if (mutableUi.value.page == BrowserPage.READER && page != BrowserPage.READER)
            closeReader()
        if (page != BrowserPage.BOOKMARKS && mutableUi.value.bookmarkImportPreview != null)
            cancelBookmarkImport()
        if (mutableUi.value.activeMode == TabMode.PRIVATE &&
            (page == BrowserPage.HISTORY || page == BrowserPage.DOWNLOADS)) {
            notice(context.getString(R.string.private_library_unavailable))
            return
        }
        if (page == BrowserPage.SITE_INFO) {
            val selected = mutableUi.value.selectedTab
            mutableUi.value = mutableUi.value.copy(siteInfo = sessionForSelectedTab()?.siteInfo()
                ?: SiteInfo(selected?.url, null, null, null, null))
        }
        if (page != BrowserPage.WEB) closeFind()
        mutableUi.value = mutableUi.value.copy(page = page)
        when (page) {
            BrowserPage.HISTORY -> refreshHistory()
            BrowserPage.BOOKMARKS -> refreshBookmarks()
            BrowserPage.DOWNLOADS -> refreshDownloads()
            BrowserPage.SETTINGS -> mutableUi.value = mutableUi.value.copy(
                defaultBrowser = host?.isDefaultBrowser())
            BrowserPage.SITE_PERMISSIONS -> refreshSitePermissions()
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
                true -> context.getString(R.string.default_browser_success)
                false -> context.getString(R.string.default_browser_failed)
                null -> context.getString(R.string.default_browser_unsupported)
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

    private fun inspectPwa(onResult: (PwaSite?) -> Unit) {
        val tab = mutableUi.value.selectedTab ?: return
        val url = tab.url?.takeIf { it.startsWith("https://", true) } ?: run {
            onResult(null); return
        }
        val session = sessionForSelectedTab() ?: run { onResult(null); return }
        session.manifestLink { link ->
            scope.launch {
                val site = link?.let { PwaSupport.inspect(url, it) }
                if (mutableUi.value.selectedId == tab.id && mutableUi.value.selectedTab?.url == url)
                    onResult(site)
            }
        }
    }

    fun pinCurrentPage() {
        val tab = mutableUi.value.selectedTab ?: return
        val url = tab.url?.takeIf(::isWebUrl) ?: return
        if (tab.mode == TabMode.PRIVATE) {
            notice(context.getString(R.string.page_shortcut_private_unavailable)); return
        }
        inspectPwa { site ->
            if (!foreground || mutableUi.value.selectedId != tab.id ||
                this.tab(tab.id)?.url != url) return@inspectPwa
            val currentHost = host ?: return@inspectPwa
            val shortcutSite = site?.takeIf { PwaSupport.withinScope(url, it.scopeUrl) }
                ?.copy(startUrl = url)
            scope.launch {
                val accepted = withContext(Dispatchers.IO) {
                    currentHost.pinPage(tab.title, url, tab.favicon, shortcutSite)
                }
                if (host === currentHost) notice(context.getString(if (accepted)
                    R.string.page_shortcut_pending else R.string.page_shortcut_unavailable))
            }
        }
    }

    fun openCurrentWebApp() {
        inspectPwa { site ->
            if (!foreground) return@inspectPwa
            if (site == null) notice(context.getString(R.string.pwa_unavailable))
            else host?.openWebApp(site)
        }
    }

    fun openReader() {
        val tab = mutableUi.value.selectedTab ?: return
        val url = tab.url ?: return
        val session = sessionForSelectedTab() ?: return
        session.extractReadablePage { result ->
            if (mutableUi.value.selectedId != tab.id || mutableUi.value.selectedTab?.url != url)
                return@extractReadablePage
            if (result == null) notice(context.getString(R.string.reader_unavailable))
            else mutableUi.value = mutableUi.value.copy(page = BrowserPage.READER,
                readerPage = result, readerSpeaking = false)
        }
    }

    fun toggleReaderSpeech() {
        val state = mutableUi.value
        if (state.readerSpeaking) {
            host?.stopReaderSpeech()
            mutableUi.value = state.copy(readerSpeaking = false)
            return
        }
        if (state.activeMode == TabMode.PRIVATE) {
            notice(context.getString(R.string.reader_private_speech_unavailable)); return
        }
        val text = state.readerPage?.text ?: return
        mutableUi.value = state.copy(readerSpeaking = true)
        host?.speakReader(text) { success ->
            mutableUi.value = mutableUi.value.copy(readerSpeaking = false)
            if (!success && mutableUi.value.page == BrowserPage.READER)
                notice(context.getString(R.string.reader_speech_error))
        }
    }

    private fun closeReader() {
        host?.stopReaderSpeech()
        mutableUi.value = mutableUi.value.copy(readerPage = null, readerSpeaking = false)
    }

    fun printCurrentPage() {
        val tab = mutableUi.value.selectedTab ?: return
        val title = tab.title.ifBlank { context.getString(R.string.web_page_title) }
        val adapter = sessionForSelectedTab()?.createPrintAdapter(title)
        if (adapter == null) notice(context.getString(R.string.print_page_missing))
        else host?.print(adapter, title)
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

    fun showBackHistory() {
        val entries = sessionForSelectedTab()?.backHistory().orEmpty()
        if (entries.isNotEmpty())
            mutableUi.value = mutableUi.value.copy(dialog = BrowserDialog.BackHistory(entries))
    }

    fun navigateBackHistory(offset: Int) {
        val dialog = mutableUi.value.dialog as? BrowserDialog.BackHistory ?: return
        val entry = dialog.entries.firstOrNull { it.offset == offset } ?: return
        mutableUi.value = mutableUi.value.copy(dialog = null)
        val session = sessionForSelectedTab() ?: return
        val action: () -> Unit = {
            session.allowHttpOnce(entry.url)
            session.goBackOrForward(offset)
        }
        if (!requestHttp(entry.url, action)) action()
    }

    fun goHome() {
        val selected = mutableUi.value.selectedId ?: return
        val home = mutableUi.value.homePageUrl
        if (home == null) returnToNewTab() else loadTab(selected, home)
        mutableUi.value = mutableUi.value.copy(page = BrowserPage.WEB)
    }

    fun saveHomePage(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isBlank()) {
            mutableUi.value = mutableUi.value.copy(homePageUrl = null)
            store.saveHomePage(null)
            return true
        }
        if (trimmed.any { it.isWhitespace() } ||
            !(trimmed.contains('.') || trimmed.startsWith("http://", true) ||
                trimmed.startsWith("https://", true) || trimmed.startsWith("localhost", true))) return false
        val url = AddressResolver.resolve(trimmed, mutableUi.value.searchEngine) ?: return false
        if (!isWebUrl(url)) return false
        mutableUi.value = mutableUi.value.copy(homePageUrl = url)
        store.saveHomePage(url)
        return true
    }

    fun changeTrackingProtection(enabled: Boolean) {
        mutableUi.value = mutableUi.value.copy(trackingProtection = enabled)
        store.saveTrackingProtection(enabled)
        sessions.forEach { (id, session) ->
            session.applyTrackingProtection(enabled, if (tab(id)?.mode == TabMode.PRIVATE)
                emptySet() else mutableUi.value.trackingExceptions)
        }
    }

    fun toggleTrackingException() {
        if (mutableUi.value.activeMode != TabMode.NORMAL) return
        val origin = mutableUi.value.selectedTab?.url?.let(::trackingOrigin) ?: return
        val updated = mutableUi.value.trackingExceptions.toMutableSet().apply {
            if (!add(origin)) remove(origin)
        }
        mutableUi.value = mutableUi.value.copy(trackingExceptions = updated)
        store.saveTrackingExceptions(updated)
        sessions.forEach { (id, session) ->
            if (tab(id)?.mode == TabMode.NORMAL)
                session.applyTrackingProtection(mutableUi.value.trackingProtection, updated)
        }
    }

    fun isCurrentSiteTrackingException(): Boolean = mutableUi.value.selectedTab?.url
        ?.let(::trackingOrigin)?.let { it in mutableUi.value.trackingExceptions } ?: false

    private fun trackingOrigin(url: String): String? = runCatching {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase() ?: return@runCatching null
        val host = uri.host?.lowercase() ?: return@runCatching null
        if (scheme !in setOf("http", "https") || uri.userInfo != null) return@runCatching null
        "$scheme://$host" + if (uri.port >= 0) ":${uri.port}" else ""
    }.getOrNull()

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
                if (session.state.value.url == null && url != null) session.load(url)
                else session.reload()
            }
            if (url == null || !requestHttp(url, action)) action()
        }
    }

    fun showFind() {
        if (mutableUi.value.selectedTab?.url == null) return
        mutableUi.value = mutableUi.value.copy(findVisible = true, findQuery = "",
            findActive = 0, findTotal = 0)
    }

    fun updateFindQuery(query: String) {
        if (!mutableUi.value.findVisible) return
        mutableUi.value = mutableUi.value.copy(findQuery = query, findActive = 0, findTotal = 0)
        sessionForSelectedTab()?.find(query)
    }

    fun findNext(forward: Boolean) { sessionForSelectedTab()?.findNext(forward) }

    fun closeFind() {
        if (!mutableUi.value.findVisible) return
        sessionForSelectedTab()?.clearFind()
        mutableUi.value = mutableUi.value.copy(findVisible = false, findQuery = "",
            findActive = 0, findTotal = 0)
    }

    fun toggleDesktopMode() {
        val selected = mutableUi.value.selectedTab ?: return
        val enabled = !selected.desktopMode
        val url = selected.url
        val action: () -> Unit = {
            updateTab(selected.id) { it.copy(desktopMode = enabled) }
            persistTabs()
            val session = sessions[selected.id] ?: ensureSession(selected.id, restoreSavedState = false)
            if (session?.state?.value?.isLoading == true) session.stop()
            session?.setDesktopMode(enabled)
            if (url != null) session?.let {
                it.allowHttpOnce(url)
                if (it.state.value.url == null) it.load(url) else it.reload()
            }
            Unit
        }
        if (url == null || !requestHttp(url, action)) action()
    }

    fun handleSystemBack() {
        when {
            mutableUi.value.privateLocked && mutableUi.value.activeMode == TabMode.PRIVATE ->
                leaveLockedPrivate()
            activeFullScreen != null -> exitFullscreen()
            mutableUi.value.findVisible -> closeFind()
            mutableUi.value.dialog != null -> cancelDialog()
            mutableUi.value.linkTarget != null -> dismissLinkMenu()
            mutableUi.value.pendingExternalUrl != null -> dismissExternal()
            mutableUi.value.page != BrowserPage.WEB -> showPage(BrowserPage.WEB)
            else -> goBack()
        }
    }

    fun canHandleSystemBack(): Boolean {
        val state = mutableUi.value
        return state.privateLocked && state.activeMode == TabMode.PRIVATE ||
            activeFullScreen != null || state.findVisible || state.dialog != null ||
            state.linkTarget != null || state.pendingExternalUrl != null ||
            state.page != BrowserPage.WEB || state.selectedTab?.engine?.canGoBack == true ||
            state.visibleTabs.size > 1 || state.selectedTab?.mode == TabMode.PRIVATE ||
            state.selectedTab?.url != null
    }

    fun backPreviewLabel(): String? {
        val state = mutableUi.value
        return when {
            state.privateLocked && state.activeMode == TabMode.PRIVATE ->
                context.getString(R.string.private_unlock_leave)
            activeFullScreen != null -> context.getString(R.string.back_preview_fullscreen)
            state.findVisible -> context.getString(R.string.back_preview_find)
            state.dialog != null || state.linkTarget != null || state.pendingExternalUrl != null ->
                context.getString(R.string.back_preview_dialog)
            state.page != BrowserPage.WEB -> context.getString(R.string.back_preview_web)
            state.selectedTab?.engine?.canGoBack == true ->
                sessionForSelectedTab()?.backUrl()?.let(AddressResolver::displayUrl)
                    ?: context.getString(R.string.back_preview_page)
            state.visibleTabs.size > 1 || state.selectedTab?.mode == TabMode.PRIVATE ->
                context.getString(R.string.back_preview_tab)
            state.selectedTab?.url != null -> context.getString(R.string.back_preview_new_tab)
            else -> null
        }
    }

    fun onStop() {
        foreground = false
        host?.stopReaderSpeech()
        if (mutableUi.value.readerSpeaking)
            mutableUi.value = mutableUi.value.copy(readerSpeaking = false)
        if (pendingClientCertificate?.first?.let { tab(it)?.mode == TabMode.PRIVATE } == true)
            cancelClientCertificate()
        if (mutableUi.value.tabs.any { it.mode == TabMode.PRIVATE } &&
            host?.canAuthenticatePrivate() == true) {
            mutableUi.value = mutableUi.value.copy(privateLocked = true)
            privateUnlockGeneration++
            privateUnlockInFlight = false
            host?.cancelPrivateAuthentication()
        }
        sessions.forEach { (id, session) -> snapshot(id, session); session.pause() }
        sessions.values.firstOrNull()?.pauseTimers()
        persistTabs()
    }

    fun onResume() {
        foreground = true
        ensureSelectedSession()
        sessions.values.firstOrNull()?.resumeTimers()
        if (!(mutableUi.value.privateLocked && mutableUi.value.activeMode == TabMode.PRIVATE))
            sessionForSelectedTab()?.resume()
        if (mutableUi.value.page == BrowserPage.DOWNLOADS) refreshDownloads()
        requestPrivateUnlock()
    }

    fun onTrimMemory(level: Int) {
        if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        val selectedId = mutableUi.value.selectedId
        sessions.keys.toList().filter { it != selectedId }.forEach { id ->
            sessions[id]?.let { snapshot(id, it, preview = false) }
            discardSession(id, save = false)
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            mutableUi.value = mutableUi.value.copy(tabs = mutableUi.value.tabs.map { tab ->
                if (tab.id == selectedId) tab else tab.copy(preview = null)
            })
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            stateCache.keys.filter { it != selectedId }.forEach(stateCache::remove)
        }
    }

    fun requestPrivateUnlock() {
        if (!mutableUi.value.privateLocked || mutableUi.value.activeMode != TabMode.PRIVATE ||
            privateUnlockInFlight || !foreground) return
        val owner = host ?: return
        privateUnlockInFlight = true
        val generation = ++privateUnlockGeneration
        owner.authenticatePrivate { unlocked ->
            if (generation != privateUnlockGeneration) return@authenticatePrivate
            privateUnlockInFlight = false
            if (unlocked) {
                mutableUi.value = mutableUi.value.copy(privateLocked = false)
                ensureSelectedSession()
                sessionForSelectedTab()?.resume()
            }
        }
    }

    fun leaveLockedPrivate() {
        if (mutableUi.value.privateLocked) switchMode(TabMode.NORMAL)
    }

    private fun returnToNewTab() {
        val id = mutableUi.value.selectedId ?: return
        invalidateRestore(id)
        discardSession(id, save = false)
        stateCache.remove(id)
        if (tab(id)?.mode == TabMode.NORMAL) store.deleteTabState(id)
        updateTab(id) { it.copy(url = null, title = context.getString(R.string.new_tab), engine = EngineState()) }
        persistTabs()
    }

    private fun ensureSelectedSession() {
        val selected = mutableUi.value.selectedTab ?: return
        if (selected.mode == TabMode.PRIVATE && mutableUi.value.privateLocked) return
        if (selected.url != null && host != null) ensureSession(selected.id)?.resume()
    }

    private fun ensureSession(id: String, restoreSavedState: Boolean = true, protectedId: String? = null): EngineSession? {
        sessions[id]?.let { return it }
        val selected = tab(id) ?: return null
        if (selected.mode == TabMode.PRIVATE && mutableUi.value.privateLocked) return null
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
            desktopMode = selected.desktopMode,
            trackingProtection = mutableUi.value.trackingProtection,
            trackingExceptions = if (selected.mode == TabMode.PRIVATE) emptySet()
                else mutableUi.value.trackingExceptions,
        ), this) }
            .getOrElse {
                val unavailable = runCatching { WebView.getCurrentWebViewPackage() == null }
                    .getOrDefault(false)
                if (unavailable) mutableUi.value = mutableUi.value.copy(webViewUnavailable = true)
                updateTab(id) { tab ->
                    tab.copy(engine = tab.engine.copy(isLoading = false,
                        error = PageError(context.getString(if (unavailable)
                            R.string.webview_missing_error else R.string.webview_open_error), tab.url)))
                }
                return null
            }
        if (mutableUi.value.webViewUnavailable)
            mutableUi.value = mutableUi.value.copy(webViewUnavailable = false)
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
                val bytes = tabRestoration.readState(selected, stateCache[id])
                if (generation(id) != expectedGeneration || sessions.entries.none { it.key == id && it.value === session }) return@launch
                restoreJobs.remove(id)
                if (!tabRestoration.restoreOrLoad(session, bytes, selected.url)) {
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

    private fun snapshot(id: String, session: EngineSession, preview: Boolean = true) {
        val selected = tab(id) ?: return
        if (restoreJobs[id] == null && session.state.value.error == null &&
            session.state.value.url != null) {
            session.saveState(256 * 1024)?.let { bytes ->
                stateCache[id] = bytes
                while (stateCache.size > 24) stateCache.remove(stateCache.keys.first())
                if (selected.mode == TabMode.NORMAL) store.writeTabState(id, bytes)
            }
        }
        if (preview) session.capturePreview()?.let { bytes ->
            updateTab(id) { it.copy(preview = bytes) }
            if (selected.mode == TabMode.NORMAL) store.writePreview(id, bytes)
        }
    }

    private fun destroyPrivateProfile() {
        val name = privateProfileName ?: return
        if (mutableUi.value.tabs.any { it.mode == TabMode.PRIVATE }) return
        privateProfileName = null
        val removed = runCatching {
            name !in engine.privateProfileNames(profilePrefix) || engine.deletePrivateProfile(name)
        }.getOrDefault(false)
        if (!removed) {
            privateCleanupFailed = true
            mutableUi.value = mutableUi.value.copy(privateAvailable = false,
                privateUnavailableReason = context.getString(R.string.private_cleanup_restart))
            if (host != null) notice(context.getString(R.string.private_cleanup_retry))
        }
        stateCache.keys.filter { id -> tab(id)?.mode == TabMode.PRIVATE }.forEach(stateCache::remove)
    }

    private fun persistTabs() {
        val state = mutableUi.value
        if (!state.ready) return
        store.saveTabs(state.tabs.filter { it.mode == TabMode.NORMAL }.mapIndexed { index, item ->
            TabRestoration.toRecord(item, index, item.id == state.selectedNormalId)
        })
    }

    private fun updateTab(id: String, change: (BrowserTab) -> BrowserTab) {
        mutableUi.value = mutableUi.value.copy(tabs = mutableUi.value.tabs.map { if (it.id == id) change(it) else it })
    }

    private fun isWebUrl(url: String): Boolean = url.startsWith("https://", true) || url.startsWith("http://", true)

    private fun notice(message: String) { mutableUi.value = mutableUi.value.copy(dialog = BrowserDialog.Notice(message)) }

    fun confirmExternal() {
        val url = mutableUi.value.pendingExternalUrl ?: return
        val originTab = pendingExternalSessionId?.let(::tab)
        val stillSelected = originTab != null && originTab.id == mutableUi.value.selectedId
        dismissExternal()
        if (stillSelected) host?.openExternal(url, originTab?.mode == TabMode.PRIVATE)
    }
    fun dismissExternal() {
        pendingExternalSessionId = null
        mutableUi.value = mutableUi.value.copy(pendingExternalUrl = null)
    }

    fun updateSuggestions(query: String) {
        suggestionJob?.cancel()
        val version = ++suggestionVersion
        val text = query.trim()
        if (text.isEmpty()) {
            mutableUi.value = mutableUi.value.copy(suggestions = emptyList())
            return
        }
        suggestionJob = scope.launch {
            delay(150)
            val bookmarks = withContext(Dispatchers.IO) { runCatching { store.searchBookmarks(text) }.getOrDefault(emptyList()) }
                .take(5).map { AddressSuggestion(it.url, it.title, R.string.bookmarks) }
            if (version == suggestionVersion)
                mutableUi.value = mutableUi.value.copy(suggestions = bookmarks)
        }
    }

    fun inlineCompletion(input: String): String? {
        val text = input.trim()
        if (text.length < 2 || text.any { it.isWhitespace() }) return null
        val prefix = if (text.startsWith("https://", true)) "https://" else if (text.startsWith("http://", true)) "http://" else ""
        val hostPart = text.substring(prefix.length)
        if (hostPart.contains('/') || hostPart.contains('?') || hostPart.contains('#')) return null
        val host = bookmarkDomains.distinct()
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
                    val bookmarks = allBookmarks.sortedWith(compareByDescending<BookmarkRecord> {
                        it.pinnedToHome
                    }.thenByDescending { it.createdAt }).distinctBy {
                        Uri.parse(it.url).host ?: it.url
                    }.take(4).map {
                        AddressSuggestion(it.url, it.title, R.string.bookmarks)
                    }
                    val seen = bookmarks.mapNotNull { Uri.parse(it.url).host }.toMutableSet()
                    val frequent = topSites.mapNotNull { site ->
                        val host = Uri.parse(site.url).host ?: return@mapNotNull null
                        if (!seen.add(host)) null
                        else AddressSuggestion(site.url, site.title.ifBlank { host }, R.string.suggestion_frequent)
                    }.take(4)
                    QuickLinkResult(bookmarks + frequent,
                        allBookmarks.mapNotNull { Uri.parse(it.url).host }.distinct())
                }.getOrDefault(QuickLinkResult(emptyList(), emptyList()))
            }
            if (version == quickLinksVersion) {
                bookmarkDomains = result.bookmarks
                mutableUi.value = mutableUi.value.copy(quickLinks = result.links)
            }
        }
    }

    fun useSuggestion(item: AddressSuggestion) {
        mutableUi.value.selectedId?.let { loadTab(it, item.url) }
        clearSuggestions()
        mutableUi.value = mutableUi.value.copy(page = BrowserPage.WEB)
    }

    fun clearSuggestions() {
        suggestionVersion++
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
                publishClosedTabs()
                refreshQuickLinks()
            } catch (_: Exception) {
                notice(context.getString(R.string.history_delete_error))
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
                publishClosedTabs()
                refreshQuickLinks()
            } catch (_: Exception) {
                notice(context.getString(R.string.history_delete_error))
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
            val result = withContext(Dispatchers.IO) { runCatching {
                store.loadBookmarks() to store.loadBookmarkFolders()
            } }
            result.onSuccess { (records, folders) ->
                mutableUi.value = mutableUi.value.copy(bookmarks = records, bookmarkFolders = folders)
            }.onFailure { notice(context.getString(R.string.bookmark_read_error)) }
        }
    }

    fun createBookmarkFolder(name: String, parentId: String?) {
        val value = name.trim().take(60)
        if (value.isBlank() || value.any { it.code < 32 || it.code == 127 }) return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val folders = store.loadBookmarkFolders()
                if (parentId != null && folders.none { it.id == parentId })
                    error("북마크 폴더를 찾을 수 없습니다.")
                if (folders.any { it.parentId == parentId && it.name.equals(value, true) })
                    error("같은 이름의 폴더가 있습니다.")
                store.addBookmarkFolder(BookmarkFolderRecord(UUID.randomUUID().toString(),
                    value, parentId, System.currentTimeMillis()))
            } }.onSuccess { refreshBookmarks() }
                .onFailure { notice(context.getString(R.string.bookmark_folder_error)) }
        }
    }

    fun renameBookmarkFolder(id: String, name: String) {
        val value = name.trim().take(60)
        if (value.isBlank() || value.any { it.code < 32 || it.code == 127 }) return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val folders = store.loadBookmarkFolders()
                val old = folders.firstOrNull { it.id == id } ?: return@withContext
                if (folders.any { it.id != id && it.parentId == old.parentId && it.name.equals(value, true) })
                    error("같은 이름의 폴더가 있습니다.")
                store.updateBookmarkFolder(old.copy(name = value))
            } }.onSuccess { refreshBookmarks() }
                .onFailure { notice(context.getString(R.string.bookmark_folder_error)) }
        }
    }

    fun deleteBookmarkFolder(id: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.deleteBookmarkFolder(id) } }
                .onSuccess { refreshBookmarks() }
                .onFailure { notice(context.getString(R.string.bookmark_folder_error)) }
        }
    }

    fun toggleBookmarkHomePin(id: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val old = store.loadBookmarks().firstOrNull { it.id == id } ?: return@withContext
                store.updateBookmark(old.copy(pinnedToHome = !old.pinnedToHome))
            } }.onSuccess { refreshBookmarks(); refreshQuickLinks() }
                .onFailure { notice(context.getString(R.string.bookmark_update_error)) }
        }
    }

    fun beginBookmarkImport() {
        if (host?.pickBookmarkFile() != true)
            notice(context.getString(R.string.bookmark_backup_unavailable))
    }

    fun readBookmarkFile(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            runCatching {
                val parsed = bookmarkBackupService.read(uri)
                if (parsed.entries.isEmpty() && parsed.folderPaths.isEmpty())
                    error("No supported bookmarks")
                pendingBookmarkImport = parsed
                pendingBookmarkPreview = bookmarkBackupService.preview(parsed)
                mutableUi.value = mutableUi.value.copy(page = BrowserPage.BOOKMARKS,
                    bookmarkImportPreview = pendingBookmarkPreview)
            }.onFailure { notice(context.getString(R.string.bookmark_import_error)) }
        }
    }

    fun cancelBookmarkImport() {
        pendingBookmarkImport = null
        pendingBookmarkPreview = null
        mutableUi.value = mutableUi.value.copy(bookmarkImportPreview = null)
    }

    fun confirmBookmarkImport() {
        val parsed = pendingBookmarkImport ?: return
        cancelBookmarkImport()
        scope.launch {
            runCatching {
                val imported = bookmarkBackupService.importBackup(parsed)
                refreshBookmarks()
                refreshQuickLinks()
                notice(if (imported.second > 0)
                    context.getString(R.string.bookmark_import_result_with_folders,
                        imported.first, imported.second)
                else context.getString(R.string.bookmark_import_result, imported.first))
            }.onFailure { refreshBookmarks(); notice(context.getString(R.string.bookmark_import_error)) }
        }
    }

    fun beginBookmarkExport() {
        if (host?.createBookmarkFile() != true)
            notice(context.getString(R.string.bookmark_backup_unavailable))
    }

    fun writeBookmarkFile(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            runCatching {
                bookmarkBackupService.write(uri)
                notice(context.getString(R.string.bookmark_export_result))
            }.onFailure { notice(context.getString(R.string.bookmark_export_error)) }
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
                .onFailure { notice(context.getString(R.string.bookmark_save_error)) }
        }
    }

    fun updateBookmark(id: String, title: String, url: String, folderId: String? = null) {
        val resolved = AddressResolver.resolve(url, mutableUi.value.searchEngine)?.takeIf(::isWebUrl)
        if (resolved == null || title.isBlank()) {
            notice(context.getString(R.string.bookmark_input_error)); return
        }
        scope.launch {
            runCatching {
                val record = withContext(Dispatchers.IO) {
                    store.loadBookmarks().firstOrNull { it.id == id }
                } ?: return@launch
                withContext(Dispatchers.IO) {
                    val folders = store.loadBookmarkFolders()
                    if (folderId != null && folders.none { it.id == folderId })
                        error("북마크 폴더를 찾을 수 없습니다.")
                    store.updateBookmark(record.copy(title = title.trim(), url = resolved,
                        folderId = folderId))
                }
            }.onSuccess { refreshBookmarks(); refreshQuickLinks() }
                .onFailure { notice(context.getString(R.string.bookmark_update_error)) }
        }
    }

    fun deleteBookmark(id: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.deleteBookmark(id) } }
                .onSuccess { refreshBookmarks(); refreshQuickLinks() }
                .onFailure { notice(context.getString(R.string.bookmark_delete_error)) }
        }
    }

    fun openSavedUrl(url: String) {
        val id = mutableUi.value.selectedId ?: return
        loadTab(id, url)
        mutableUi.value = mutableUi.value.copy(page = BrowserPage.WEB)
    }

    fun refreshDownloads() {
        val version = ++downloadRefreshVersion
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { downloadRepository.snapshot() } }
            if (version != downloadRefreshVersion) return@launch
            result.onSuccess { snapshot ->
                mutableUi.value = mutableUi.value.copy(downloads = snapshot.managed,
                    localDownloads = snapshot.local, missingLocalDownloadIds = snapshot.missingLocalIds,
                    inaccessibleLocalDownloadIds = snapshot.inaccessibleLocalIds,
                    downloadError = if (snapshot.managed.any { it.status == DOWNLOAD_STATUS_QUERY_ERROR } ||
                        snapshot.inaccessibleLocalIds.isNotEmpty())
                        context.getString(R.string.download_list_partial_error) else null)
            }.onFailure {
                mutableUi.value = mutableUi.value.copy(downloadError =
                    context.getString(R.string.download_list_error))
            }
        }
    }

    fun openDownloadsFolder() { host?.openDownloadsFolder() }
    fun openWebViewSettings() { host?.openWebViewSettings() }

    fun requestDeleteDownload(item: DownloadItem) {
        mutableUi.value = mutableUi.value.copy(dialog = BrowserDialog.DeleteDownload(item.record.id, item.record.fileName))
    }

    fun retryDownload(item: DownloadItem) {
        if (item.status != DownloadManager.STATUS_FAILED && item.status != DOWNLOAD_STATUS_MISSING) return
        if (DownloadSafety.isDangerous(item.record.fileName, item.record.mimeType)) {
            mutableUi.value = mutableUi.value.copy(dialog = BrowserDialog.RetryDangerousDownload(
                item.record.id, item.record.fileName))
            return
        }
        retryDownloadApproved(item)
    }

    private fun retryDownloadApproved(item: DownloadItem) {
        if (item.status != DownloadManager.STATUS_FAILED && item.status != DOWNLOAD_STATUS_MISSING) return
        val record = item.record
        if (!retryingDownloadIds.add(record.id)) return
        mutableUi.value = mutableUi.value.copy(retryingDownloadIds = retryingDownloadIds.toSet())
        val request = DownloadRequest(record.url, WebSettings.getDefaultUserAgent(context), null,
            record.mimeType, -1)
        enqueueDownload(TabMode.NORMAL, request, downloadRepository.uniqueFileName(record.fileName),
            retryOriginalId = record.id)
    }

    fun clearMissingDownloadRecords() {
        val ids = mutableUi.value.downloads.filter { it.status == DOWNLOAD_STATUS_MISSING }.map { it.record.id }
        val localIds = mutableUi.value.missingLocalDownloadIds
        if (ids.isEmpty() && localIds.isEmpty()) return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                ids.forEach {
                    runCatching { downloadManager.remove(it) }
                    store.deleteDownload(it)
                }
                localIds.forEach { store.deleteLocalDownload(it) }
            } }
                .onSuccess { refreshDownloads() }
                .onFailure { notice(context.getString(R.string.download_missing_cleanup_error)) }
        }
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

    fun shareDownload(item: DownloadItem) {
        if (item.status != DownloadManager.STATUS_SUCCESSFUL) return
        scope.launch {
            val uri = withContext(Dispatchers.IO) { runCatching {
                downloadManager.openDownloadedFile(item.record.id).use { }
                downloadManager.getUriForDownloadedFile(item.record.id)
            }.getOrNull() }
            if (uri == null) notice(context.getString(R.string.download_file_not_found))
            else host?.shareFile(uri, item.record.mimeType, item.record.fileName)
        }
    }

    fun shareLocalDownload(item: LocalDownloadRecord) {
        if (item.id in mutableUi.value.missingLocalDownloadIds) {
            notice(context.getString(R.string.download_file_not_found))
            return
        }
        if (item.id in mutableUi.value.inaccessibleLocalDownloadIds) {
            notice(context.getString(R.string.download_file_access_error))
            return
        }
        val uri = Uri.parse(item.contentUri)
        scope.launch {
            val exists = withContext(Dispatchers.IO) { runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true
            }.getOrDefault(false) }
            if (!exists) notice(context.getString(R.string.download_file_not_found))
            else host?.shareFile(uri, item.mimeType, item.fileName)
        }
    }

    fun openLocalDownload(item: LocalDownloadRecord) {
        if (item.id in mutableUi.value.missingLocalDownloadIds) {
            notice(context.getString(R.string.download_file_not_found))
            return
        }
        if (item.id in mutableUi.value.inaccessibleLocalDownloadIds) {
            notice(context.getString(R.string.download_file_access_error))
            return
        }
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
            } else notice(context.getString(R.string.pdf_download_incomplete))
        }
    }

    private fun deleteDownload(id: Long) {
        val missingFile = mutableUi.value.downloads.firstOrNull { it.record.id == id }?.status == DOWNLOAD_STATUS_MISSING
        pendingPdf.remove(id)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (downloadManager.remove(id) <= 0 && !missingFile)
                        error("다운로드 파일을 삭제하지 못했습니다.")
                    store.deleteDownload(id)
                }
            }.onSuccess { refreshDownloads() }
                .onFailure {
                    refreshDownloads()
                    notice(context.getString(R.string.download_delete_error))
                }
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
                .onFailure { notice(context.getString(R.string.local_download_delete_error)) }
        }
    }

    fun requestClearSiteData() { mutableUi.value = mutableUi.value.copy(dialog = BrowserDialog.ClearSiteData) }

    private fun clearSiteData() {
        suggestionCutoff = System.currentTimeMillis()
        store.saveSuggestionCutoff(suggestionCutoff)
        clearSuggestions()
        quickLinksVersion++
        mutableUi.value = mutableUi.value.copy(quickLinks = mutableUi.value.quickLinks.filter {
            it.sourceRes == R.string.bookmarks
        })
        recentlyClosed.clear()
        mutableUi.value = mutableUi.value.copy(trackingExceptions = emptySet())
        store.saveTrackingExceptions(emptySet())
        sessions.forEach { (_, session) ->
            session.applyTrackingProtection(mutableUi.value.trackingProtection, emptySet())
        }
        savedPermissions.clear()
        mutableUi.value = mutableUi.value.copy(savedSitePermissions = emptyList())
        mutableUi.value = mutableUi.value.copy(lastClosedTabId = null)
        publishClosedTabs()
        val privateIds = mutableUi.value.tabs.filter { it.mode == TabMode.PRIVATE }.map { it.id }
        privateIds.forEach(::closeTab)
        rebaseNormalTabs(reopen = false)
        scope.launch {
            val permissionsCleared = withContext(Dispatchers.IO) {
                runCatching { store.clearSitePermissions() }.isSuccess
            }
            runCatching { engine.clearDefaultSiteData(context) { cleared ->
                if (destroyed) return@clearDefaultSiteData
                ensureSelectedSession()
                refreshQuickLinks()
                notice(when {
                    !cleared || !permissionsCleared -> context.getString(R.string.site_data_partial_error)
                    privateCleanupFailed -> context.getString(R.string.site_data_private_retry)
                    else -> context.getString(R.string.site_data_deleted)
                })
            } }.onFailure {
                ensureSelectedSession()
                notice(context.getString(R.string.site_data_delete_error))
            }
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

    fun confirmDialog(promptValue: String? = null, passwordValue: String? = null,
                      dangerousAccepted: Boolean = false) {
        val dialog = mutableUi.value.dialog
        if ((dialog is BrowserDialog.Download && dialog.dangerous ||
                dialog is BrowserDialog.RetryDangerousDownload) && !dangerousAccepted) return
        val httpAction = if (dialog is BrowserDialog.HttpNavigation) pendingHttpAction else null
        when (dialog) {
            is BrowserDialog.JavaScript -> pendingJavaScript?.confirm(promptValue)
            is BrowserDialog.HttpAuthentication -> pendingHttpAuthentication?.let { request ->
                if (!promptValue.isNullOrBlank() && passwordValue != null)
                    request.proceed(promptValue, passwordValue)
                else request.cancel()
            }
            is BrowserDialog.Permission -> {
                sitePermissions.approve(host)
            }
            is BrowserDialog.Download -> pendingDownload?.let { (id, request) ->
                if (request.url.startsWith("blob:", true)) beginBlobDownload(id, request,
                    dialog.fileName, dangerousAccepted)
                else tab(id)?.let { enqueueDownload(it.mode, request, dialog.fileName) }
            }
            is BrowserDialog.DeleteDownload -> deleteDownload(dialog.id)
            is BrowserDialog.RetryDangerousDownload -> mutableUi.value.downloads
                .firstOrNull { it.record.id == dialog.id }?.let(::retryDownloadApproved)
            is BrowserDialog.DeleteLocalDownload -> deleteLocalDownload(dialog.id)
            BrowserDialog.ClearSiteData -> clearSiteData()
            else -> Unit
        }
        pendingJavaScript = null
        pendingHttpAuthentication = null
        pendingDownload = null
        pendingHttpAction = null
        mutableUi.value = mutableUi.value.copy(dialog = null)
        httpAction?.invoke()
    }

    fun cancelDialog() {
        pendingJavaScript?.cancel()
        pendingJavaScript = null
        pendingHttpAuthentication?.cancel()
        pendingHttpAuthentication = null
        sitePermissions.cancel()
        pendingDownload = null
        pendingHttpAction = null
        mutableUi.value = mutableUi.value.copy(dialog = null)
    }

    private fun persistPermissionDecision(origin: String, kinds: Set<com.kotlinsun.current.engine.WebPermissionKind>,
                                          allowed: Boolean) {
        val records = kinds.map { SitePermissionRecord(origin, it.name, allowed, System.currentTimeMillis()) }
        records.forEach { savedPermissions[it.origin to it.kind] = it }
        mutableUi.value = mutableUi.value.copy(savedSitePermissions = savedPermissions.values.toList())
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.saveSitePermissions(records) } }
                .onFailure { notice(context.getString(R.string.site_permission_save_error)) }
        }
    }

    fun approveSitePermission(remember: Boolean) {
        val request = sitePermissions.pending ?: return
        val origin = SitePermissionCoordinator.canonicalOrigin(request.origin) ?: run {
            sitePermissions.cancel(); return
        }
        val normal = mutableUi.value.selectedTab?.mode == TabMode.NORMAL
        sitePermissions.approve(host) { granted ->
            if (granted && remember && normal) persistPermissionDecision(origin, request.kinds, true)
        }
        mutableUi.value = mutableUi.value.copy(dialog = null)
    }

    fun denySitePermission(remember: Boolean) {
        val request = sitePermissions.pending ?: return
        val origin = SitePermissionCoordinator.canonicalOrigin(request.origin)
        if (remember && origin != null && mutableUi.value.selectedTab?.mode == TabMode.NORMAL)
            persistPermissionDecision(origin, request.kinds, false)
        cancelDialog()
    }

    fun refreshSitePermissions() {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.loadSitePermissions() } }
                .onSuccess { records ->
                    savedPermissions.clear()
                    records.forEach { savedPermissions[it.origin to it.kind] = it }
                    mutableUi.value = mutableUi.value.copy(savedSitePermissions = records)
                }
                .onFailure { notice(context.getString(R.string.site_permission_read_error)) }
        }
    }

    fun deleteSitePermission(record: SitePermissionRecord) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.deleteSitePermission(record.origin, record.kind) } }
                .onSuccess { refreshSitePermissions() }
                .onFailure { notice(context.getString(R.string.site_permission_save_error)) }
        }
    }

    private fun enqueueDownload(mode: TabMode, request: DownloadRequest, name: String,
                                retryOriginalId: Long? = null) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                downloadRepository.enqueue(mode, request, name, retryOriginalId)
            } }.onSuccess { (downloadId, cleanupFailed) ->
                retryOriginalId?.let { retryingDownloadIds.remove(it) }
                mutableUi.value = mutableUi.value.copy(retryingDownloadIds = retryingDownloadIds.toSet())
                if (cleanupFailed) notice(context.getString(R.string.download_retry_cleanup_error))
                if (request.mimeType?.substringBefore(';')?.equals("application/pdf", true) == true ||
                    name.endsWith(".pdf", true)) {
                    pendingPdf[downloadId] = mode == TabMode.PRIVATE
                    checkPendingPdf(downloadId)
                }
                refreshDownloads()
            }.onFailure {
                retryOriginalId?.let { id -> retryingDownloadIds.remove(id) }
                mutableUi.value = mutableUi.value.copy(retryingDownloadIds = retryingDownloadIds.toSet())
                notice(context.getString(R.string.download_start_error,
                    it.message ?: context.getString(R.string.download_generic_error)))
            }
        }
    }

    private fun beginBlobDownload(id: String, request: DownloadRequest, name: String,
                                  dangerousAccepted: Boolean) {
        val session = sessions[id] ?: return
        session.blobUnavailableReason(request.url)?.let { notice(it); return }
        val privateMode = tab(id)?.mode == TabMode.PRIVATE
        val directory = File(context.cacheDir, "blob_transfers").apply { mkdirs() }
        val temporary = runCatching { File.createTempFile("transfer-", ".tmp", directory) }
            .getOrElse { notice(context.getString(R.string.temp_file_create_error)); return }
        val cancelled = AtomicBoolean(false)
        var handle: BlobTransfer? = null
        fun release() {
            if (handle != null && activeBlob?.second === handle) activeBlob = null
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
                                if (cancelled.get()) error(BLOB_TRANSFER_CANCELLED)
                                val effectiveMime = mimeType?.takeIf { it.contains('/') }
                                    ?: request.mimeType
                                    ?: if (name.endsWith(".pdf", true)) "application/pdf"
                                    else "application/octet-stream"
                                if (!dangerousAccepted && DownloadSafety.isDangerous(name, effectiveMime))
                                    error(context.getString(R.string.blob_dangerous_blocked))
                                val values = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                                    put(MediaStore.MediaColumns.MIME_TYPE, effectiveMime)
                                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                                }
                                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                    ?: error(context.getString(R.string.local_file_create_error))
                                var recordId: Long? = null
                                try {
                                    context.contentResolver.openOutputStream(uri)?.use { output ->
                                        temporary.inputStream().use { input ->
                                            val buffer = ByteArray(64 * 1024)
                                            while (true) {
                                                if (cancelled.get()) error(BLOB_TRANSFER_CANCELLED)
                                                val size = input.read(buffer)
                                                if (size < 0) break
                                                output.write(buffer, 0, size)
                                            }
                                        }
                                    } ?: error(context.getString(R.string.local_file_open_error))
                                    if (cancelled.get()) error(BLOB_TRANSFER_CANCELLED)
                                    values.clear()
                                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                    if (context.contentResolver.update(uri, values, null, null) <= 0)
                                        error(context.getString(R.string.local_file_save_incomplete))
                                    if (cancelled.get()) error(BLOB_TRANSFER_CANCELLED)
                                    if (!privateMode) recordId = store.addLocalDownload(LocalDownloadRecord(
                                        url = request.url, fileName = name, mimeType = effectiveMime,
                                        contentUri = uri.toString(), createdAt = System.currentTimeMillis()))
                                    if (cancelled.get()) error(BLOB_TRANSFER_CANCELLED)
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
                            else notice(context.getString(R.string.local_download_saved))
                        }
                    }.onFailure {
                        if (!cancelled.get()) notice(context.getString(R.string.local_download_save_error,
                            it.message ?: context.getString(R.string.download_generic_error)))
                    }
                }
            }

            override fun onError(message: String) {
                cancelled.set(true)
                release()
                temporary.delete()
                if (message != BLOB_TRANSFER_CANCELLED) notice(message)
            }
        }
        val transfer = runCatching { session.downloadBlob(request.url, 20L * 1024 * 1024, receiver) }
            .getOrElse {
                temporary.delete()
                notice(context.getString(R.string.blob_transfer_start_error))
                return
            }
        if (transfer == null || cancelled.get()) {
            if (cancelled.get()) transfer?.cancel()
            temporary.delete()
            if (!cancelled.get()) notice(context.getString(R.string.blob_read_error))
        } else {
            val currentHandle = object : BlobTransfer {
                override fun cancel() {
                    cancelled.set(true)
                    transfer.cancel()
                    temporary.delete()
                }
            }
            handle = currentHandle
            activeBlob = id to currentHandle
        }
    }

    private fun safeFileName(request: DownloadRequest): String {
        return downloadRepository.safeFileName(request)
    }

    override fun onExternalNavigation(sessionId: String, url: String, hasGesture: Boolean) {
        if (sessionId != mutableUi.value.selectedId || !hasGesture) return
        pendingExternalSessionId = sessionId
        mutableUi.value = mutableUi.value.copy(pendingExternalUrl = url)
    }

    override fun onNavigationStarted(sessionId: String) {
        if (sessionId == mutableUi.value.selectedId && mutableUi.value.page == BrowserPage.READER) {
            closeReader()
            mutableUi.value = mutableUi.value.copy(page = BrowserPage.WEB)
        }
        if (pendingClientCertificate?.first == sessionId) cancelClientCertificate()
        if (pendingExternalSessionId == sessionId) dismissExternal()
        pendingHttpRedirects.remove(sessionId)
        if (sessionId == mutableUi.value.selectedId) closeFind()
        if (activeBlob?.first == sessionId) {
            activeBlob?.second?.cancel()
            activeBlob = null
        }
        if (pendingFile?.first == sessionId) {
            host?.cancelFileSelection()
            pendingFile = null
        }
        updateTab(sessionId) { it.copy(favicon = null) }
        if (sessionId == mutableUi.value.selectedId &&
            (pendingJavaScript != null || pendingHttpAuthentication != null || sitePermissions.pending != null ||
                pendingDownload?.first == sessionId))
            cancelDialog()
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
        newTab(mode = parent.mode, useHomePage = false)
        val child = mutableUi.value.selectedTab?.takeIf {
            it.id != parentId && it.mode == parent.mode && it.url == null
        } ?: return false
        val session = ensureSession(child.id, restoreSavedState = false, protectedId = parentId)
            ?: run { closeTab(child.id); return false }
        return runCatching { request.accept(session) }.fold(
            onSuccess = { true },
            onFailure = { closeTab(child.id); false },
        )
    }

    override fun onPopupBlocked(parentId: String) {
        if (parentId == mutableUi.value.selectedId && mutableUi.value.dialog == null)
            notice(context.getString(R.string.popup_blocked_notice))
    }

    override fun onCloseRequested(sessionId: String) { closeTab(sessionId) }

    override fun onRendererGone(sessionId: String, session: EngineSession) {
        if (sessions[sessionId] !== session) return
        if (pendingClientCertificate?.first == sessionId) cancelClientCertificate()
        if (sessionId == mutableUi.value.selectedId) closeFind()
        if (pendingFile?.first == sessionId) {
            host?.cancelFileSelection()
            pendingFile = null
        }
        if (sessionId == mutableUi.value.selectedId) cancelDialog()
        discardSession(sessionId, save = false)
        stateCache.remove(sessionId)
        if (tab(sessionId)?.mode == TabMode.NORMAL) store.deleteTabState(sessionId)
        updateTab(sessionId) { it.copy(engine = it.engine.copy(isLoading = false,
            error = PageError(context.getString(R.string.renderer_gone_error), it.url))) }
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
            val owner = host
            if (owner == null) {
                pendingFile = null
                request.complete(null)
            } else owner.launchFileSelection(request)
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
            notice(context.getString(R.string.download_type_unsupported)); return
        }
        val selected = tab(sessionId) ?: return
        pendingDownload = sessionId to request
        val name = safeFileName(request)
        mutableUi.value = mutableUi.value.copy(dialog = BrowserDialog.Download(name,
            request.url, selected.mode == TabMode.PRIVATE,
            DownloadSafety.isDangerous(name, request.mimeType)))
    }

    override fun onPermission(sessionId: String, request: WebPermissionRequest) {
        if (sessionId != mutableUi.value.selectedId || mutableUi.value.dialog != null) {
            request.deny()
            return
        }
        val currentTab = tab(sessionId) ?: run { request.deny(); return }
        if (!sitePermissions.begin(request)) return
        val origin = SitePermissionCoordinator.canonicalOrigin(request.origin) ?: run {
            sitePermissions.cancel(); return
        }
        if (currentTab.mode == TabMode.NORMAL) {
            val records = request.kinds.map { savedPermissions[origin to it.name] }
            if (records.any { it?.allowed == false }) { sitePermissions.cancel(); return }
            if (records.all { it?.allowed == true }) { sitePermissions.approve(host); return }
        }
        mutableUi.value = mutableUi.value.copy(dialog = BrowserDialog.Permission(origin,
            request.kinds, currentTab.mode == TabMode.NORMAL))
    }

    override fun onPermissionCanceled(sessionId: String, request: WebPermissionRequest) {
        if (sitePermissions.cancelIfSame(request)) {
            if (mutableUi.value.dialog is BrowserDialog.Permission)
                mutableUi.value = mutableUi.value.copy(dialog = null)
        }
    }

    override fun onHttpAuthentication(sessionId: String, request: HttpAuthenticationRequest) {
        if (sessionId != mutableUi.value.selectedId || mutableUi.value.dialog != null) {
            request.cancel()
            return
        }
        pendingHttpAuthentication = request
        mutableUi.value = mutableUi.value.copy(dialog = BrowserDialog.HttpAuthentication(
            request.host, request.realm))
    }

    override fun onClientCertificate(sessionId: String, request: ClientCertificateRequest) {
        if (sessionId != mutableUi.value.selectedId || mutableUi.value.privateLocked || host == null) {
            request.cancel()
            return
        }
        cancelClientCertificate()
        val guarded = object : ClientCertificateRequest {
            private var completed = false
            override val host: String = request.host
            override val port: Int = request.port
            override val keyTypes: Array<String> = request.keyTypes
            override val principals: Array<java.security.Principal>? = request.principals
            override fun proceed(privateKey: java.security.PrivateKey,
                chain: Array<java.security.cert.X509Certificate>) {
                if (completed) return
                completed = true
                if (pendingClientCertificate?.second === this) pendingClientCertificate = null
                if (sessions[sessionId] != null && mutableUi.value.selectedId == sessionId &&
                    !mutableUi.value.privateLocked)
                    request.proceed(privateKey, chain)
                else request.cancel()
            }
            override fun cancel() {
                if (completed) return
                completed = true
                if (pendingClientCertificate?.second === this) pendingClientCertificate = null
                request.cancel()
            }
        }
        pendingClientCertificate = sessionId to guarded
        host?.chooseClientCertificate(guarded)
    }

    private fun cancelClientCertificate() {
        pendingClientCertificate?.second?.cancel()
        pendingClientCertificate = null
        host?.cancelClientCertificateSelection()
    }

    override fun onPullToRefresh(sessionId: String) {
        if (sessionId == mutableUi.value.selectedId && mutableUi.value.page == BrowserPage.WEB &&
            !mutableUi.value.privateLocked && activeFullScreen == null &&
            mutableUi.value.selectedTab?.engine?.isLoading != true) reloadOrStop()
    }

    override fun onFindResult(sessionId: String, activeIndex: Int, total: Int) {
        if (sessionId == mutableUi.value.selectedId && mutableUi.value.findVisible)
            mutableUi.value = mutableUi.value.copy(findActive = if (total > 0) activeIndex + 1 else 0,
                findTotal = total)
    }

    override fun onHttpNavigation(sessionId: String, url: String) {
        if (sessionId != mutableUi.value.selectedId) {
            pendingHttpRedirects[sessionId] = url
            sessions[sessionId]?.stop()
            return
        }
        requestHttp(url) {
            sessions[sessionId]?.let { session ->
                session.allowHttpOnce(url)
                session.load(url)
            }
        }
    }
}
