package com.kotlinsun.current

import android.app.Activity
import android.app.DownloadManager
import android.app.role.RoleManager
import android.Manifest
import android.os.Build
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.view.WindowManager
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.kotlinsun.current.browser.BrowserController
import com.kotlinsun.current.browser.BrowserHost
import com.kotlinsun.current.browser.BrowserScreen
import com.kotlinsun.current.browser.BrowserViewModel
import com.kotlinsun.current.browser.ThemeChoice
import com.kotlinsun.current.engine.FileSelectionRequest
import com.kotlinsun.current.ui.theme.CurrentTheme
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity(), BrowserHost {
    private lateinit var controller: BrowserController
    override val activity: Activity get() = this
    private var pendingFile: FileSelectionRequest? = null
    private var cameraUri: Uri? = null
    private var cameraFile: File? = null
    private var fileChoiceDialog: android.app.AlertDialog? = null
    private var pendingPermissions: ((Boolean) -> Unit)? = null
    private var requestedPermissions: Array<String> = emptyArray()
    private var filePickerInFlight = false
    private var permissionPromptInFlight = false
    private var roleCallback: ((Boolean?) -> Unit)? = null
    private val bookmarkImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        controller.readBookmarkFile(uri)
    }

    private val bookmarkExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/html")) { uri ->
        controller.writeBookmarkFile(uri)
    }

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        filePickerInFlight = false
        controller.fileResultFinished()
        val request = pendingFile
        pendingFile = null
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            val values = mutableListOf<Uri>()
            result.data?.clipData?.let { clips ->
                for (index in 0 until clips.itemCount) values.add(clips.getItemAt(index).uri)
            }
            result.data?.data?.let(values::add)
            if (values.isEmpty()) cameraUri?.let(values::add)
            values.distinct().filter(::isReadableContentUri).take(if (request?.allowMultiple == true) 20 else 1)
                .takeIf { it.isNotEmpty() }?.toTypedArray()
        } else null
        clearCameraCapture(keepFile = request != null && cameraUri?.let { capture ->
            uris?.any { it == capture }
        } == true)
        request?.complete(uris)
        request?.let(controller::fileSelectionCompleted)
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionPromptInFlight = false
        controller.permissionResultFinished()
        val callback = pendingPermissions
        pendingPermissions = null
        callback?.invoke(requestedPermissions.all { result[it] == true })
        requestedPermissions = emptyArray()
    }

    private val roleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        roleCallback?.invoke(isDefaultBrowser())
        roleCallback = null
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                controller.onDownloadComplete(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        controller = ViewModelProvider(this)[BrowserViewModel::class.java].controller
        File(cacheDir, "upload_capture").listFiles()?.filter {
            it.name.startsWith("private-capture-") ||
                System.currentTimeMillis() - it.lastModified() > 24L * 60 * 60 * 1000
        }?.forEach { it.delete() }
        controller.attach(this)
        if (controller.ui.value.activeMode == com.kotlinsun.current.engine.TabMode.PRIVATE)
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val initialUrl = if (savedInstanceState == null && intent.action == Intent.ACTION_VIEW)
            intent.dataString?.takeIf(::isWebUrl) else null
        val sharedText = if (savedInstanceState == null && intent.action == Intent.ACTION_SEND)
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(2048) else null
        controller.start(initialUrl, skipOnboarding = initialUrl != null || !sharedText.isNullOrBlank())
        if (savedInstanceState == null && intent.action == Intent.ACTION_SEND) {
            sharedText?.let(controller::openInputFromIntent)
        }
        lifecycleScope.launch {
            controller.ui.collect { state ->
                if (state.activeMode == com.kotlinsun.current.engine.TabMode.PRIVATE)
                    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        ContextCompat.registerReceiver(this, downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED)
        setContent {
            val ui by controller.ui.collectAsState()
            val dark = when (ui.themeChoice) {
                ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                ThemeChoice.LIGHT -> false
                ThemeChoice.DARK -> true
            }
            LaunchedEffect(dark) {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            CurrentTheme(darkTheme = dark) {
                BackHandler(enabled = !ui.showOnboarding) { controller.handleSystemBack() }
                BrowserScreen(controller)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf(::isWebUrl)?.let(controller::openUrlFromIntent)
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(2048)
                ?.let(controller::openInputFromIntent)
            Intent.ACTION_MAIN -> controller.onNormalLaunch()
        }
    }

    override fun onStop() {
        controller.onStop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::controller.isInitialized) controller.onResume()
    }

    override fun onDestroy() {
        unregisterReceiver(downloadReceiver)
        cancelFileSelection()
        pendingPermissions?.invoke(false)
        pendingPermissions = null
        roleCallback = null
        controller.detach()
        super.onDestroy()
    }

    override fun launchFileSelection(request: FileSelectionRequest) {
        if (filePickerInFlight || controller.awaitingFileResult) {
            request.complete(null)
            controller.fileSelectionCompleted(request)
            return
        }
        cancelFileSelection()
        pendingFile = request
        val accepted = request.acceptTypes.flatMap { it.split(',') }.mapNotNull { raw ->
            val value = raw.trim().lowercase()
            when {
                value.startsWith('.') -> MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(value.drop(1))
                value.contains('/') && !value.contains(' ') -> value
                else -> null
            }
        }.distinct().toTypedArray()
        val cameraEligible = request.capture || accepted.any {
            it.startsWith("image/") || it.startsWith("video/")
        }
        if (pendingPermissions != null) {
            showFileChooser(request, accepted, false)
        } else if (cameraEligible && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA)) { granted ->
                if (pendingFile === request) showFileChooser(request, accepted, granted)
            }
        } else showFileChooser(request, accepted, cameraEligible)
    }

    private fun showFileChooser(request: FileSelectionRequest, accepted: Array<String>, allowCamera: Boolean) {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (accepted.size == 1) accepted[0] else "*/*"
            if (accepted.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, accepted)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request.allowMultiple)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val hasImage = accepted.any { it.startsWith("image/") }
        val hasVideo = accepted.any { it.startsWith("video/") }
        if (allowCamera && hasImage && hasVideo) {
            fileChoiceDialog = android.app.AlertDialog.Builder(this)
                .setTitle(R.string.upload_method)
                .setItems(R.array.upload_methods) { _, choice ->
                    fileChoiceDialog = null
                    val intent = when (choice) {
                        1 -> runCatching { createCameraIntent(arrayOf("image/*")) }.getOrNull()
                        2 -> runCatching { createCameraIntent(arrayOf("video/*")) }.getOrNull()
                        else -> picker
                    }
                    if (intent == null) {
                        cancelFileSelection()
                        Toast.makeText(this, R.string.upload_picker_unavailable, Toast.LENGTH_LONG).show()
                    }
                    else launchFileIntent(request, intent)
                }
                .setOnCancelListener { if (pendingFile === request) cancelFileSelection() }
                .show()
            return
        }
        val camera = if (allowCamera) runCatching { createCameraIntent(accepted) }.getOrNull() else null
        val chooser = Intent.createChooser(picker, getString(R.string.choose_file))
        if (camera != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
        val target = if (request.capture && camera != null) camera else chooser
        launchFileIntent(request, target)
    }

    private fun launchFileIntent(request: FileSelectionRequest, intent: Intent) {
        filePickerInFlight = true
        controller.fileResultStarted()
        runCatching { fileLauncher.launch(intent) }.onFailure {
            filePickerInFlight = false
            controller.fileResultFinished()
            pendingFile = null
            clearCameraCapture()
            request.complete(null)
            controller.fileSelectionCompleted(request)
            Toast.makeText(this, R.string.upload_picker_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    override fun cancelFileSelection() {
        fileChoiceDialog?.dismiss()
        fileChoiceDialog = null
        pendingFile?.complete(null)
        pendingFile?.let(controller::fileSelectionCompleted)
        pendingFile = null
        clearCameraCapture()
    }

    override fun clearPrivateUploadCaptures(): Boolean {
        val directory = File(cacheDir, "upload_capture")
        val files = directory.listFiles() ?: return !directory.exists()
        var cleared = true
        files.filter { it.name.startsWith("private-capture-") }.forEach {
            if (!runCatching { it.delete() || !it.exists() }.getOrDefault(false)) cleared = false
        }
        return cleared
    }

    override fun pickBookmarkFile(): Boolean = runCatching {
        bookmarkImportLauncher.launch(arrayOf("text/html", "text/plain", "*/*"))
        true
    }.getOrDefault(false)

    override fun createBookmarkFile(): Boolean = runCatching {
        bookmarkExportLauncher.launch("current-bookmarks.html")
        true
    }.getOrDefault(false)

    private fun clearCameraCapture(keepFile: Boolean = false) {
        if (!keepFile) cameraFile?.let { runCatching { it.delete() } }
        cameraFile = null
        cameraUri = null
    }

    private fun createCameraIntent(types: Array<String>): Intent? {
        val image = types.isEmpty() || types.any { it == "*/*" || it.startsWith("image/") }
        val video = types.any { it.startsWith("video/") }
        if (!image && !video) return null
        val directory = File(cacheDir, "upload_capture").apply { mkdirs() }
        val prefix = if (controller.ui.value.activeMode == com.kotlinsun.current.engine.TabMode.PRIVATE)
            "private-capture-" else "capture-"
        val file = File.createTempFile(prefix, if (image) ".jpg" else ".mp4", directory)
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (error: RuntimeException) {
            file.delete()
            throw error
        }
        cameraFile?.delete()
        cameraFile = file
        cameraUri = uri
        return Intent(if (image) MediaStore.ACTION_IMAGE_CAPTURE else MediaStore.ACTION_VIDEO_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = android.content.ClipData.newUri(contentResolver,
                getString(R.string.capture_file), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun isReadableContentUri(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        return runCatching { contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false }
            .getOrDefault(false)
    }

    override fun requestPermissions(permissions: Array<String>, callback: (Boolean) -> Unit) {
        if (permissionPromptInFlight || controller.awaitingPermissionResult) { callback(false); return }
        permissionPromptInFlight = true
        controller.permissionResultStarted()
        pendingPermissions = callback
        requestedPermissions = permissions
        runCatching { permissionLauncher.launch(permissions) }.onFailure {
            permissionPromptInFlight = false
            controller.permissionResultFinished()
            pendingPermissions = null
            requestedPermissions = emptyArray()
            callback(false)
        }
    }

    override fun setFullscreen(enabled: Boolean) {
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) insets.hide(WindowInsetsCompat.Type.systemBars())
        else insets.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun share(url: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(intent, getString(R.string.share_link_title)))
    }

    override fun shareFile(uri: Uri, mimeType: String?, fileName: String) {
        val type = mimeType?.substringBefore(';')?.trim()?.takeIf { '/' in it }
            ?: "application/octet-stream"
        val send = Intent(Intent.ACTION_SEND).setType(type)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newUri(contentResolver, fileName, uri)
        val chooser = Intent.createChooser(send, getString(R.string.share_file_title))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        chooser.clipData = send.clipData
        runCatching { startActivity(chooser) }
            .onFailure { Toast.makeText(this, R.string.file_share_error, Toast.LENGTH_SHORT).show() }
    }

    override fun openDownload(id: Long) {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = runCatching { manager.getUriForDownloadedFile(id) }.getOrNull() ?: run {
            Toast.makeText(this, R.string.download_file_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        val mime = runCatching { manager.getMimeTypeForDownloadedFile(id) }.getOrNull() ?: "*/*"
        openWithExternalApp(uri, mime)
    }

    override fun openPdf(id: Long, privateMode: Boolean) {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = runCatching { manager.getUriForDownloadedFile(id) }.getOrNull()
        if (uri == null) {
            Toast.makeText(this, R.string.pdf_file_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        openLocalFile(uri, "application/pdf", privateMode)
    }

    override fun openLocalFile(uri: Uri, mimeType: String?, privateMode: Boolean) {
        if (mimeType?.substringBefore(';')?.equals("application/pdf", true) == true) {
            runCatching {
                startActivity(Intent(this, PdfActivity::class.java).setDataAndType(uri, "application/pdf")
                    .putExtra(PdfActivity.EXTRA_PRIVATE, privateMode)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.onFailure { openWithExternalApp(uri, "application/pdf") }
            return
        }
        openWithExternalApp(uri, mimeType ?: "*/*")
    }

    private fun openWithExternalApp(uri: Uri, mimeType: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Toast.makeText(this, R.string.file_open_app_missing, Toast.LENGTH_SHORT).show() }
    }

    override fun print(adapter: PrintDocumentAdapter, jobName: String) {
        runCatching {
            (getSystemService(Context.PRINT_SERVICE) as PrintManager)
                .print(jobName, adapter, PrintAttributes.Builder().build())
        }.onFailure { Toast.makeText(this, R.string.print_start_error, Toast.LENGTH_SHORT).show() }
    }

    override fun isDefaultBrowser(): Boolean? =
        if (Build.VERSION.SDK_INT >= 29) {
            val manager = getSystemService(RoleManager::class.java)
            if (manager?.isRoleAvailable(RoleManager.ROLE_BROWSER) == true)
                manager.isRoleHeld(RoleManager.ROLE_BROWSER) else null
        } else null

    override fun requestDefaultBrowser(callback: (Boolean?) -> Unit) {
        if (roleCallback != null) { callback(isDefaultBrowser()); return }
        val manager = getSystemService(RoleManager::class.java)
        if (Build.VERSION.SDK_INT < 29 || manager == null || !manager.isRoleAvailable(RoleManager.ROLE_BROWSER) ||
            manager.isRoleHeld(RoleManager.ROLE_BROWSER)) {
            callback(isDefaultBrowser())
            return
        }
        roleCallback = callback
        runCatching { roleLauncher.launch(manager.createRequestRoleIntent(RoleManager.ROLE_BROWSER)) }
            .onFailure { roleCallback = null; callback(false) }
    }

    private fun isWebUrl(value: String): Boolean = Uri.parse(value).let {
        it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank()
    }

    override fun openExternal(url: String, privateMode: Boolean) {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
        val scheme = uri.scheme?.lowercase() ?: return
        if (scheme in setOf("http", "https", "file", "content", "javascript", "data", "blob", "about")) return
        val fallbackMode = if (privateMode) com.kotlinsun.current.engine.TabMode.PRIVATE
            else com.kotlinsun.current.engine.TabMode.NORMAL
        fun openFallback(value: String?) {
            if (value != null) controller.newTab(value, fallbackMode)
            else Toast.makeText(this, R.string.external_app_unavailable, Toast.LENGTH_LONG).show()
        }
        val fallback: String?
        val external = if (scheme == "intent") {
            val parsed = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return
            if (parsed.`package` == packageName) return
            fallback = parsed.getStringExtra("browser_fallback_url")?.takeIf(::isWebUrl)
            val dataScheme = parsed.data?.scheme?.lowercase()
            if (dataScheme == null || dataScheme in setOf("file", "content", "javascript",
                    "data", "blob", "about", "intent") ||
                (dataScheme in setOf("http", "https") && parsed.`package`.isNullOrBlank())) {
                openFallback(fallback)
                return
            }
            Intent(Intent.ACTION_VIEW, parsed.data).apply { `package` = parsed.`package` }
        } else {
            fallback = null
            Intent(Intent.ACTION_VIEW, uri)
        }
        external.addCategory(Intent.CATEGORY_BROWSABLE)
        external.component = null
        external.selector = null
        external.flags = 0
        external.clipData = null
        try {
            startActivity(external)
        } catch (_: ActivityNotFoundException) {
            openFallback(fallback)
        } catch (_: SecurityException) {
            openFallback(fallback)
        } catch (_: IllegalArgumentException) {
            openFallback(fallback)
        }
    }
}
