package com.kotlinsun.current

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.security.KeyChain
import android.security.KeyChainAliasCallback
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.kotlinsun.current.browser.DownloadRepository
import com.kotlinsun.current.browser.DownloadSafety
import com.kotlinsun.current.browser.PwaSupport
import com.kotlinsun.current.browser.SitePermissionCoordinator
import com.kotlinsun.current.data.BrowserStore
import com.kotlinsun.current.data.HistoryRecord
import com.kotlinsun.current.data.SitePermissionRecord
import com.kotlinsun.current.engine.BrowserEngine
import com.kotlinsun.current.engine.ClientCertificateRequest
import com.kotlinsun.current.engine.DownloadRequest
import com.kotlinsun.current.engine.EngineCallbacks
import com.kotlinsun.current.engine.EngineSession
import com.kotlinsun.current.engine.FileSelectionRequest
import com.kotlinsun.current.engine.FullScreenRequest
import com.kotlinsun.current.engine.HttpAuthenticationRequest
import com.kotlinsun.current.engine.JavaScriptDialogKind
import com.kotlinsun.current.engine.JavaScriptDialogRequest
import com.kotlinsun.current.engine.LinkTarget
import com.kotlinsun.current.engine.PopupRequest
import com.kotlinsun.current.engine.SessionConfig
import com.kotlinsun.current.engine.TabMode
import com.kotlinsun.current.engine.WebPermissionKind
import com.kotlinsun.current.engine.WebPermissionRequest
import com.kotlinsun.current.engine.webview.WebViewEngine
import com.kotlinsun.current.ui.theme.CurrentTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.UUID
import java.io.File
import java.util.ArrayDeque

/** Addressless surface. Web behavior stays in the same engine as ordinary tabs. */
class WebAppActivity : ComponentActivity(), EngineCallbacks {
    companion object {
        const val EXTRA_START = "pwa_start"
        const val EXTRA_SCOPE = "pwa_scope"
        const val EXTRA_TITLE = "pwa_title"
    }

    private sealed interface Prompt {
        data class Notice(val message: String) : Prompt
        data class Permission(val owner: String, val request: WebPermissionRequest) : Prompt
        data class Download(val owner: String, val request: DownloadRequest,
            val name: String, val dangerous: Boolean) : Prompt
        data class External(val owner: String, val url: String) : Prompt
        data class Script(val owner: String, val request: JavaScriptDialogRequest) : Prompt
        data class HttpAuth(val owner: String, val request: HttpAuthenticationRequest) : Prompt
    }
    private data class Owned<T>(val owner: String, val value: T)

    private val engine: BrowserEngine = WebViewEngine()
    private val store by lazy { BrowserStore.get(this) }
    private val downloads by lazy {
        DownloadRepository(this, store, getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
    }
    private var mainSession by mutableStateOf<EngineSession?>(null)
    private var popupSession by mutableStateOf<EngineSession?>(null)
    private var fullScreen by mutableStateOf<Owned<FullScreenRequest>?>(null)
    private var prompt by mutableStateOf<Prompt?>(null)
    private val noticeQueue = ArrayDeque<String>()
    private var themeChoice by mutableStateOf("SYSTEM")
    private var settingsReady by mutableStateOf(false)
    private var scopeUrl = ""
    private var currentUrl = ""
    private var appTitle = ""
    private var textZoom = 100
    private var thirdPartyCookies = false
    private var trackingEnabled = false
    private var trackingExceptions = emptySet<String>()
    private var savedPermissions = emptyMap<Pair<String, String>, SitePermissionRecord>()
    private var pendingFile: Owned<FileSelectionRequest>? = null
    private var filePickerInFlight = false
    private var cameraFile: File? = null
    private var cameraUri: Uri? = null
    private val retainedCaptures = mutableListOf<File>()
    private var cameraPermissionInFlight = false
    private var uploadChoiceDialog: android.app.AlertDialog? = null
    private var pendingRuntime: Owned<WebPermissionRequest>? = null
    private var runtimeInFlight = false
    private var runtimePermissions: Array<String> = emptyArray()
    private var clientCertificate: Owned<ClientCertificateRequest>? = null
    private var activeBlob: Owned<BlobDownloadTask>? = null
    private val pendingPdf = mutableSetOf<Long>()
    private var receiverRegistered = false
    private var foreground = false
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id in pendingPdf) checkPdf(id, true)
            }
        }
    }

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        filePickerInFlight = false
        val pending = pendingFile
        pendingFile = null
        val uris = if (result.resultCode == Activity.RESULT_OK)
            WebFileChooser.selectedUris(this, result.data, cameraUri, pending?.value) else null
        clearCameraCapture(keepFile = uris?.any { it == cameraUri } == true)
        pending?.value?.complete(uris)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { result ->
        runtimeInFlight = false
        val pending = pendingRuntime
        pendingRuntime = null
        val requested = runtimePermissions
        runtimePermissions = emptyArray()
        if (pending != null) {
            if (session(pending.owner) != null && requested.isNotEmpty() &&
                requested.all { result[it] == true }) pending.value.grant()
            else pending.value.deny()
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermissionInFlight = false
        pendingFile?.takeIf { session(it.owner) != null }?.let { pending ->
            launchFilePicker(pending, granted)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filePickerInFlight = savedInstanceState?.getBoolean("file_picker_in_flight") == true
        runtimeInFlight = savedInstanceState?.getBoolean("runtime_permission_in_flight") == true
        cameraPermissionInFlight = savedInstanceState?.getBoolean("camera_permission_in_flight") == true
        val start = intent.getStringExtra(EXTRA_START)
        val scope = intent.getStringExtra(EXTRA_SCOPE)
        if (start == null || scope == null || !PwaSupport.withinScope(start, scope)) {
            finish(); return
        }
        scopeUrl = scope
        currentUrl = savedInstanceState?.getString("current_url")
            ?.takeIf { PwaSupport.withinScope(it, scope) } ?: start
        savedInstanceState?.getLongArray("pending_pdf")?.forEach(pendingPdf::add)
        appTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        ContextCompat.registerReceiver(this, downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        setContent { Screen() }
        lifecycleScope.launch {
            runCatching { store.loadPreferences() }.getOrNull()?.let { preferences ->
                themeChoice = preferences.theme
                textZoom = preferences.textZoom
                thirdPartyCookies = preferences.thirdPartyCookies
                trackingEnabled = preferences.trackingProtection
                trackingExceptions = preferences.trackingExceptions
            }
            savedPermissions = runCatching { store.loadSitePermissions() }.getOrDefault(emptyList())
                .associateBy { it.origin to it.kind }
            settingsReady = true
            openMain(currentUrl)
        }
    }

    private fun config(scope: String?): SessionConfig = SessionConfig(
        allowThirdPartyCookies = thirdPartyCookies,
        textZoom = (textZoom * resources.configuration.fontScale).toInt(),
        trackingProtection = trackingEnabled,
        trackingExceptions = trackingExceptions,
        navigationScope = scope,
    )

    private fun openMain(url: String) {
        val created = runCatching {
            engine.createSession(this, UUID.randomUUID().toString(), config(scopeUrl), this)
        }.getOrElse { showNotice(getString(R.string.webview_open_error)); return }
        mainSession = created
        runCatching { created.load(url) }.onFailure {
            mainSession = null
            created.close()
            showNotice(getString(R.string.webview_open_error))
        }
    }

    private fun clearCameraCapture(keepFile: Boolean = false) {
        cameraFile?.let { file ->
            if (keepFile) retainedCaptures.add(file) else runCatching { file.delete() }
        }
        cameraFile = null
        cameraUri = null
    }

    private fun captureIntent(image: Boolean): Intent? = runCatching {
        val directory = File(cacheDir, "upload_capture").apply { mkdirs() }
        val file = File.createTempFile("pwa-capture-", if (image) ".jpg" else ".mp4", directory)
        val uri = try { FileProvider.getUriForFile(this,
            "$packageName.fileprovider", file) } catch (error: Exception) {
            file.delete(); throw error
        }
        clearCameraCapture()
        cameraFile = file
        cameraUri = uri
        Intent(if (image) MediaStore.ACTION_IMAGE_CAPTURE else MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = android.content.ClipData.newUri(contentResolver,
                getString(R.string.capture_file), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }.getOrNull()

    private fun session(id: String): EngineSession? = when (id) {
        mainSession?.id -> mainSession
        popupSession?.id -> popupSession
        else -> null
    }

    private fun isVisible(id: String): Boolean = id == (popupSession ?: mainSession)?.id

    @Composable
    private fun Screen() {
        CurrentTheme(darkTheme = when (themeChoice) {
            "DARK" -> true
            "LIGHT" -> false
            else -> isSystemInDarkTheme()
        }) {
            val shown = popupSession ?: mainSession
            val state = shown?.state?.collectAsState()?.value
            val fullscreen = fullScreen
            BackHandler(enabled = true) {
                when {
                    fullscreen != null -> closeFullscreen()
                    prompt != null -> dismissPrompt()
                    popupSession != null && shown?.state?.value?.canGoBack == true -> shown.goBack()
                    popupSession != null -> closePopup()
                    shown?.state?.value?.canGoBack == true -> shown.goBack()
                    else -> finish()
                }
            }
            if (fullscreen != null) key(fullscreen) {
                AndroidView(factory = {
                    (fullscreen.value.view.parent as? ViewGroup)?.removeView(fullscreen.value.view)
                    fullscreen.value.view
                }, modifier = Modifier.fillMaxSize())
            }
            else Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    IconButton(onClick = {
                        if (popupSession != null) closePopup()
                        else if (shown?.state?.value?.canGoBack == true) shown.goBack()
                        else finish()
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = getString(R.string.back)) }
                    val heading = if (popupSession != null) state?.url?.let {
                        Uri.parse(it).host ?: it
                    } ?: getString(R.string.pwa_popup) else state?.title?.ifBlank { appTitle } ?: appTitle
                    Text(heading, Modifier.weight(1f).padding(top = 12.dp), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { shown?.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = getString(R.string.reload))
                    }
                    IconButton(onClick = { if (popupSession != null) closePopup() else finish() }) {
                        Icon(Icons.Filled.Close, contentDescription = getString(R.string.close))
                    }
                }
                if (state != null && state.progress in 1..99)
                    LinearProgressIndicator(progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth())
                if (state != null && (state.blockedTrackers > 0 ||
                    state.blockedServiceWorkers > 0)) Text(
                    getString(R.string.pwa_tracker_counts, state.blockedTrackers,
                        state.blockedServiceWorkers),
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall)
                state?.error?.let { Text(it.message, Modifier.padding(16.dp)) }
                if (shown != null) key(shown) {
                    AndroidView(factory = {
                        (shown.view.parent as? ViewGroup)?.removeView(shown.view)
                        shown.view
                    }, modifier = Modifier.weight(1f).fillMaxWidth())
                } else if (!settingsReady) Text(getString(R.string.startup_loading), Modifier.padding(20.dp))
            }
            prompt?.let { PromptView(it) }
        }
    }

    @Composable
    private fun PromptView(value: Prompt) {
        var response by remember(value) { mutableStateOf("") }
        var password by remember(value) { mutableStateOf("") }
        var dangerousAccepted by remember(value) { mutableStateOf(false) }
        val title = when (value) {
            is Prompt.Notice -> getString(R.string.dialog_notice)
            is Prompt.Permission -> getString(R.string.dialog_permission)
            is Prompt.Download -> getString(R.string.dialog_download)
            is Prompt.External -> getString(R.string.open_external_app)
            is Prompt.Script -> value.request.origin
            is Prompt.HttpAuth -> getString(R.string.http_auth_title)
        }
        AlertDialog(onDismissRequest = { dismissPrompt() },
            title = { Text(title) },
            text = { Column {
                when (value) {
                    is Prompt.Notice -> Text(value.message)
                    is Prompt.Permission -> Text(getString(R.string.pwa_permission_request,
                        value.request.origin, value.request.kinds.joinToString { kind ->
                            getString(when (kind) {
                                WebPermissionKind.CAMERA -> R.string.permission_camera
                                WebPermissionKind.MICROPHONE -> R.string.permission_microphone
                                WebPermissionKind.LOCATION -> R.string.permission_location
                                WebPermissionKind.PROTECTED_MEDIA -> R.string.permission_protected_media
                            })
                        }))
                    is Prompt.Download -> {
                        Text(value.name + "\n" + value.request.url + "\n" +
                            getString(R.string.download_location_notice))
                        if (value.dangerous) Row {
                            Checkbox(dangerousAccepted, { dangerousAccepted = it })
                            Text(getString(R.string.download_dangerous_warning))
                        }
                    }
                    is Prompt.External -> Text(value.url)
                    is Prompt.Script -> {
                        Text(value.request.message)
                        if (value.request.kind == JavaScriptDialogKind.PROMPT) OutlinedTextField(
                            response, { response = it }, label = { Text(getString(R.string.dialog_response)) })
                    }
                    is Prompt.HttpAuth -> {
                        Text("${value.request.host} · ${value.request.realm}")
                        OutlinedTextField(response, { response = it },
                            label = { Text(getString(R.string.http_auth_username)) })
                        OutlinedTextField(password, { password = it },
                            label = { Text(getString(R.string.http_auth_password)) },
                            visualTransformation = PasswordVisualTransformation())
                    }
                }
            } },
            confirmButton = {
                Button(onClick = { confirmPrompt(value, response, password, dangerousAccepted) },
                    enabled = value !is Prompt.Download || !value.dangerous || dangerousAccepted,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(getString(when (value) {
                        is Prompt.Notice -> R.string.dialog_confirm
                        is Prompt.Download -> R.string.downloads
                        is Prompt.External -> R.string.open
                        is Prompt.Permission -> R.string.permission_allow_once
                        is Prompt.HttpAuth -> R.string.http_auth_sign_in
                        is Prompt.Script -> R.string.dialog_confirm
                    }))
                }
            },
            dismissButton = { if (value !is Prompt.Notice) TextButton(onClick = { dismissPrompt() },
                modifier = Modifier.heightIn(min = 48.dp)) { Text(getString(R.string.cancel)) } },
        )
    }

    private fun confirmPrompt(value: Prompt, response: String, password: String, accepted: Boolean) {
        if (prompt !== value) return
        prompt = null
        when (value) {
            is Prompt.Notice -> Unit
            is Prompt.Permission -> requestSitePermission(value.owner, value.request)
            is Prompt.Download -> if (!value.dangerous || accepted) {
                if (Uri.parse(value.request.url).scheme == "blob")
                    beginBlob(value.owner, value.request, value.name, accepted)
                else lifecycleScope.launch {
                    runCatching { downloads.enqueue(TabMode.NORMAL, value.request, value.name, null) }
                        .onSuccess { (id, _) ->
                            val pdf = value.request.mimeType?.substringBefore(';')
                                    ?.equals("application/pdf", true) == true ||
                                value.name.endsWith(".pdf", true)
                            if (pdf) {
                                pendingPdf.add(id)
                                checkPdf(id, false)
                            } else showNotice(getString(R.string.pwa_download_started))
                        }
                        .onFailure { showNotice(getString(R.string.download_start_error,
                            it.message ?: getString(R.string.download_generic_error))) }
                }
            }
            is Prompt.External -> if (!PwaExternalOpener.open(this, value.url, ::openInBrowser))
                showNotice(getString(R.string.external_app_unavailable))
            is Prompt.Script -> value.request.confirm(response)
            is Prompt.HttpAuth -> value.request.proceed(response, password)
        }
        nextNotice()
    }

    private fun requestSitePermission(owner: String, request: WebPermissionRequest) {
        if (session(owner) == null || runtimeInFlight) { request.deny(); return }
        val permissions = SitePermissionCoordinator.androidPermissions(request.kinds)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isEmpty()) { request.grant(); return }
        pendingRuntime = Owned(owner, request)
        runtimeInFlight = true
        runtimePermissions = permissions.toTypedArray()
        runCatching { permissionLauncher.launch(permissions.toTypedArray()) }.onFailure {
            runtimeInFlight = false
            pendingRuntime = null
            runtimePermissions = emptyArray()
            request.deny()
        }
    }

    private fun dismissPrompt(showQueued: Boolean = true) {
        val previous = prompt
        prompt = null
        when (previous) {
            is Prompt.Permission -> previous.request.deny()
            is Prompt.Script -> previous.request.cancel()
            is Prompt.HttpAuth -> previous.request.cancel()
            else -> Unit
        }
        if (showQueued) nextNotice()
    }

    private fun nextNotice() {
        if (prompt == null && noticeQueue.isNotEmpty()) prompt = Prompt.Notice(noticeQueue.removeFirst())
    }
    private fun replacePrompt(next: Prompt) { dismissPrompt(false); prompt = next }
    private fun showNotice(message: String) {
        if (prompt == null) prompt = Prompt.Notice(message) else noticeQueue.addLast(message)
    }

    private fun cancelRequests(owner: String) {
        if (activeBlob?.owner == owner) {
            activeBlob?.value?.cancel()
            activeBlob = null
        }
        if (clientCertificate?.owner == owner) {
            clientCertificate?.value?.cancel()
            clientCertificate = null
        }
        if (pendingFile?.owner == owner) {
            uploadChoiceDialog?.dismiss()
            uploadChoiceDialog = null
            val request = pendingFile
            pendingFile = null
            clearCameraCapture()
            request?.value?.complete(null)
        }
        if (pendingRuntime?.owner == owner) {
            val request = pendingRuntime
            pendingRuntime = null
            request?.value?.deny()
        }
        when (val current = prompt) {
            is Prompt.Permission -> if (current.owner == owner) dismissPrompt()
            is Prompt.Script -> if (current.owner == owner) dismissPrompt()
            is Prompt.HttpAuth -> if (current.owner == owner) dismissPrompt()
            is Prompt.Download -> if (current.owner == owner) { prompt = null; nextNotice() }
            is Prompt.External -> if (current.owner == owner) { prompt = null; nextNotice() }
            else -> Unit
        }
    }

    private fun closePopup() {
        val popup = popupSession ?: return
        cancelRequests(popup.id)
        if (fullScreen?.owner == popup.id) closeFullscreen()
        popupSession = null
        popup.close()
    }

    private fun closeFullscreen() {
        val request = fullScreen
        fullScreen = null
        request?.value?.close()
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun openInBrowser(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return
        startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(uri))
    }

    override fun onNavigationStarted(sessionId: String) { cancelRequests(sessionId) }
    override fun onFindResult(sessionId: String, activeIndex: Int, total: Int) = Unit
    override fun onPageFinished(sessionId: String) {
        if (sessionId == mainSession?.id) mainSession?.state?.value?.url?.let { url ->
            if (PwaSupport.withinScope(url, scopeUrl)) currentUrl = url
        }
    }
    override fun onVisited(sessionId: String, url: String, isReload: Boolean) {
        if (!isReload && session(sessionId) != null) lifecycleScope.launch {
            runCatching { store.addVisit(HistoryRecord(url = url,
                title = session(sessionId)?.state?.value?.title.orEmpty(),
                visitedAt = System.currentTimeMillis())) }
        }
    }
    override fun onFavicon(sessionId: String, url: String, icon: ByteArray) = Unit
    override fun onExternalNavigation(sessionId: String, url: String, hasGesture: Boolean) {
        if (hasGesture && sessionId == (popupSession ?: mainSession)?.id)
            replacePrompt(Prompt.External(sessionId, url))
    }
    override fun onScopeExit(sessionId: String, url: String) {
        if (sessionId == mainSession?.id) openInBrowser(url)
    }
    override fun onHttpNavigation(sessionId: String, url: String) { openInBrowser(url) }
    override fun onPopupRequested(parentId: String, request: PopupRequest): Boolean {
        if (popupSession != null || parentId != mainSession?.id) return false
        val child = runCatching { engine.createSession(this, UUID.randomUUID().toString(),
            config(null), this) }.getOrNull() ?: return false
        popupSession = child
        return runCatching {
            request.accept(child)
            true
        }.getOrElse { popupSession = null; child.close(); false }
    }
    override fun onPopupBlocked(parentId: String) { showNotice(getString(R.string.pwa_popup_blocked)) }
    override fun onCloseRequested(sessionId: String) {
        if (sessionId == popupSession?.id) closePopup()
        else if (sessionId == mainSession?.id) finish()
    }
    override fun onRendererGone(sessionId: String, session: EngineSession) {
        cancelRequests(sessionId)
        if (popupSession === session) { closePopup(); showNotice(getString(R.string.pwa_popup_closed)); return }
        if (mainSession !== session) { session.close(); return }
        session.close()
        mainSession = null
        closePopup()
        openMain(currentUrl)
        if (mainSession != null) showNotice(getString(R.string.pwa_renderer_error))
    }
    override fun onFileSelection(sessionId: String, request: FileSelectionRequest) {
        if (!isVisible(sessionId) || filePickerInFlight || cameraPermissionInFlight ||
            pendingFile != null) {
            request.complete(null); return
        }
        val pending = Owned(sessionId, request)
        pendingFile = pending
        val cameraEligible = WebFileChooser.cameraEligible(request)
        if (cameraEligible && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            cameraPermissionInFlight = true
            runCatching { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }.onFailure {
                cameraPermissionInFlight = false
                launchFilePicker(pending, false)
            }
        } else launchFilePicker(pending, cameraEligible)
    }

    private fun launchFilePicker(pending: Owned<FileSelectionRequest>, allowCamera: Boolean) {
        if (pendingFile !== pending || session(pending.owner) == null) return
        val request = pending.value
        val types = WebFileChooser.mimeTypes(request)
        val picker = WebFileChooser.picker(request, types)
        val image = types.isEmpty() || types.any { it == "*/*" || it.startsWith("image/") }
        val video = types.any { it.startsWith("video/") }
        if (allowCamera && image && video) {
            val dialogTheme = if (themeChoice == "DARK" || (themeChoice == "SYSTEM" &&
                (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES))
                R.style.Theme_Current_UploadDialog_Dark else R.style.Theme_Current_UploadDialog_Light
            uploadChoiceDialog = runCatching { android.app.AlertDialog.Builder(this, dialogTheme)
                .setTitle(R.string.upload_method)
                .setItems(R.array.upload_methods) { _, choice ->
                    uploadChoiceDialog = null
                    val target = when (choice) {
                        1 -> captureIntent(true)
                        2 -> captureIntent(false)
                        else -> picker
                    } ?: picker
                    launchFileIntent(pending, target)
                }
                .setOnCancelListener {
                    uploadChoiceDialog = null
                    if (pendingFile === pending) {
                        pendingFile = null
                        request.complete(null)
                    }
                }.show() }.getOrElse {
                pendingFile = null
                request.complete(null)
                showNotice(getString(R.string.upload_picker_unavailable))
                return
            }
            return
        }
        val camera = if (allowCamera && (image || video)) captureIntent(image) else null
        val intent = if (request.capture && camera != null) camera else
            Intent.createChooser(picker, getString(R.string.choose_file)).apply {
                if (camera != null) putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
            }
        launchFileIntent(pending, intent)
    }

    private fun launchFileIntent(pending: Owned<FileSelectionRequest>, intent: Intent) {
        if (pendingFile !== pending) return
        filePickerInFlight = true
        runCatching { fileLauncher.launch(intent) }.onFailure {
            filePickerInFlight = false
            pendingFile = null
            clearCameraCapture()
            pending.value.complete(null)
            showNotice(getString(R.string.upload_picker_unavailable))
        }
    }
    override fun onJavaScriptDialog(sessionId: String, request: JavaScriptDialogRequest) {
        if (!isVisible(sessionId)) request.cancel()
        else replacePrompt(Prompt.Script(sessionId, request))
    }
    override fun onFullScreen(sessionId: String, request: FullScreenRequest) {
        if (!isVisible(sessionId)) { request.close(); return }
        closeFullscreen()
        fullScreen = Owned(sessionId, request)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }
    override fun onFullScreenClosed(sessionId: String) {
        if (fullScreen?.owner == sessionId) {
            fullScreen = null
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }
    override fun onLinkLongPress(sessionId: String, target: LinkTarget) = Unit
    override fun onDownload(sessionId: String, request: DownloadRequest) {
        if (!isVisible(sessionId)) return
        val scheme = Uri.parse(request.url).scheme
        if (scheme !in setOf("http", "https", "blob")) {
            showNotice(getString(R.string.download_type_unsupported)); return
        }
        if (scheme == "blob") session(sessionId)?.blobUnavailableReason(request.url)?.let {
            showNotice(it); return
        }
        val name = downloads.safeFileName(request)
        replacePrompt(Prompt.Download(sessionId, request, name,
            DownloadSafety.isDangerous(name, request.mimeType)))
    }

    private fun beginBlob(owner: String, request: DownloadRequest, name: String,
        acceptedDangerous: Boolean) {
        val source = session(owner) ?: return
        if (activeBlob != null) {
            showNotice(getString(R.string.blob_transfer_start_error)); return
        }
        lateinit var transfer: BlobDownloadTask
        transfer = runCatching { BlobDownloadTask(this, store, source, request, name,
            acceptedDangerous) { outcome ->
            if (activeBlob?.value === transfer) activeBlob = null
            if (!isFinishing && !isDestroyed) {
                when {
                    outcome.error != null -> showNotice(outcome.error)
                    outcome.uri != null && outcome.mimeType?.substringBefore(';')
                        ?.equals("application/pdf", true) == true && foreground ->
                        openPdfUri(outcome.uri)
                    else -> showNotice(getString(R.string.local_download_saved))
                }
            }
        } }.getOrElse {
            showNotice(getString(R.string.temp_file_create_error)); return
        }
        activeBlob = Owned(owner, transfer)
        transfer.start()
    }

    private fun checkPdf(id: Long, finalEvent: Boolean) {
        lifecycleScope.launch {
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val status = withContext(Dispatchers.IO) { runCatching {
                manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
                    if (cursor.moveToFirst())
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    else null
                }
            }.getOrNull() }
            if (id !in pendingPdf) return@launch
            if (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_RUNNING ||
                status == DownloadManager.STATUS_PAUSED) return@launch
            if (status == null && !finalEvent) {
                delay(500)
                checkPdf(id, true)
                return@launch
            }
            pendingPdf.remove(id)
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                showNotice(getString(R.string.pdf_download_incomplete)); return@launch
            }
            if (!foreground) return@launch
            val uri = runCatching { manager.getUriForDownloadedFile(id) }.getOrNull()
            if (uri == null) { showNotice(getString(R.string.pdf_file_not_found)); return@launch }
            openPdfUri(uri)
        }
    }

    private fun openPdfUri(uri: Uri) {
        runCatching {
            startActivity(Intent(this, PdfActivity::class.java)
                .setDataAndType(uri, "application/pdf")
                .putExtra(PdfActivity.EXTRA_PRIVATE, false)
                .putExtra(PdfActivity.EXTRA_DARK, themeChoice == "DARK" ||
                    (themeChoice == "SYSTEM" &&
                        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/pdf")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                .onFailure { showNotice(getString(R.string.pdf_open_error)) }
        }
    }
    override fun onPermission(sessionId: String, request: WebPermissionRequest) {
        val origin = SitePermissionCoordinator.canonicalOrigin(request.origin)
        if (!isVisible(sessionId) || runtimeInFlight || prompt != null || origin == null) {
            request.deny(); return
        }
        val decisions = request.kinds.map { savedPermissions[origin to it.name] }
        if (decisions.any { it?.allowed == false }) { request.deny(); return }
        if (decisions.all { it?.allowed == true }) {
            requestSitePermission(sessionId, request); return
        }
        replacePrompt(Prompt.Permission(sessionId, request))
    }
    override fun onPermissionCanceled(sessionId: String, request: WebPermissionRequest) {
        if ((prompt as? Prompt.Permission)?.request === request) dismissPrompt()
        if (pendingRuntime?.value === request) {
            pendingRuntime = null
            request.deny()
        }
    }
    override fun onHttpAuthentication(sessionId: String, request: HttpAuthenticationRequest) {
        if (!isVisible(sessionId)) request.cancel()
        else replacePrompt(Prompt.HttpAuth(sessionId, request))
    }
    override fun onClientCertificate(sessionId: String, request: ClientCertificateRequest) {
        if (!isVisible(sessionId) || clientCertificate != null) {
            request.cancel(); return
        }
        val pending = Owned(sessionId, request)
        clientCertificate = pending
        runCatching {
            KeyChain.choosePrivateKeyAlias(this, KeyChainAliasCallback { alias ->
                lifecycleScope.launch {
                    if (clientCertificate !== pending || session(sessionId) == null) return@launch
                    if (alias == null) {
                        clientCertificate = null
                        request.cancel()
                        return@launch
                    }
                    val credentials = withContext(Dispatchers.IO) { runCatching {
                        KeyChain.getPrivateKey(applicationContext, alias) to
                            KeyChain.getCertificateChain(applicationContext, alias)
                    }.getOrNull() }
                    if (clientCertificate !== pending || session(sessionId) == null) return@launch
                    clientCertificate = null
                    val key = credentials?.first
                    val chain = credentials?.second
                    if (key != null && !chain.isNullOrEmpty()) request.proceed(key, chain)
                    else request.cancel()
                }
            }, request.keyTypes, request.principals, request.host, request.port, null)
        }.onFailure {
            clientCertificate = null
            request.cancel()
        }
    }
    override fun onPullToRefresh(sessionId: String) { session(sessionId)?.reload() }

    override fun onPause() {
        foreground = false
        popupSession?.pause()
        mainSession?.pause()
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        foreground = true
        pendingPdf.toList().forEach { checkPdf(it, false) }
        mainSession?.resume()
        popupSession?.resume()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("current_url", currentUrl)
        outState.putLongArray("pending_pdf", pendingPdf.toLongArray())
        outState.putBoolean("file_picker_in_flight", filePickerInFlight)
        outState.putBoolean("runtime_permission_in_flight", runtimeInFlight)
        outState.putBoolean("camera_permission_in_flight", cameraPermissionInFlight)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (receiverRegistered) unregisterReceiver(downloadReceiver)
        receiverRegistered = false
        pendingPdf.clear()
        dismissPrompt(false)
        noticeQueue.clear()
        uploadChoiceDialog?.dismiss()
        uploadChoiceDialog = null
        pendingFile?.value?.complete(null)
        pendingFile = null
        clearCameraCapture()
        pendingRuntime?.value?.deny()
        pendingRuntime = null
        activeBlob?.value?.cancel()
        activeBlob = null
        clientCertificate?.value?.cancel()
        clientCertificate = null
        closeFullscreen()
        closePopup()
        mainSession?.close()
        mainSession = null
        retainedCaptures.forEach { runCatching { it.delete() } }
        retainedCaptures.clear()
        super.onDestroy()
    }
}
