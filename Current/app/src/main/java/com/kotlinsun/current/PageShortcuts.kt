package com.kotlinsun.current

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import com.kotlinsun.current.browser.PwaSite
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal object PageShortcuts {
    enum class Result { REQUESTED, UPDATED, UNAVAILABLE }
    private const val EXTRA_ID = "shortcut_id"
    private val pending = ConcurrentHashMap<String, Long>()

    fun confirmed(id: String?) { if (id != null) pending.remove(id) }

    @Synchronized
    fun request(context: Context, title: String, url: String, favicon: ByteArray?, pwa: PwaSite?): Result {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return Result.UNAVAILABLE
        val id = "page-" + MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        val target = if (pwa != null) Intent(context, WebAppActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(WebAppActivity.EXTRA_START, pwa.startUrl)
            putExtra(WebAppActivity.EXTRA_SCOPE, pwa.scopeUrl)
            putExtra(WebAppActivity.EXTRA_TITLE, pwa.title)
        } else Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(url)
        }
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pinned = manager.pinnedShortcuts.firstOrNull { it.id == id }
        val icon = favicon?.let(::siteIcon) ?: pinned?.icon
            ?: Icon.createWithResource(context, R.mipmap.ic_launcher)
        val label = title.ifBlank { Uri.parse(url).host.orEmpty() }.take(25)
        val shortcut = ShortcutInfo.Builder(context, id)
            .setShortLabel(label).setLongLabel(title.ifBlank { url }.take(80))
            .setIcon(icon).setIntent(target).build()
        if (pinned != null) {
            pending.remove(id)
            return if (runCatching { manager.updateShortcuts(listOf(shortcut)) }.getOrDefault(false))
                Result.UPDATED else Result.UNAVAILABLE
        }
        if (!manager.isRequestPinShortcutSupported) return Result.UNAVAILABLE
        val now = SystemClock.elapsedRealtime()
        if (pending[id]?.let { now - it < 30_000 } == true) return Result.REQUESTED
        val confirmation = PendingIntent.getBroadcast(context, id.hashCode(),
            Intent(context, PageShortcutReceiver::class.java).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        pending[id] = now
        return if (runCatching { manager.requestPinShortcut(shortcut, confirmation.intentSender) }
            .getOrDefault(false)) Result.REQUESTED else {
            pending.remove(id)
            Result.UNAVAILABLE
        }
    }

    private fun siteIcon(bytes: ByteArray): Icon? = runCatching {
        if (bytes.size > 32768) return@runCatching null
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
        try {
            val icon = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            Canvas(icon).apply {
                drawColor(Color.WHITE)
                drawBitmap(source, null, android.graphics.Rect(20, 20, 108, 108),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            Icon.createWithBitmap(icon)
        } finally { source.recycle() }
    }.getOrNull()
}

class PageShortcutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PageShortcuts.confirmed(intent.getStringExtra("shortcut_id"))
        Toast.makeText(context, R.string.page_shortcut_added, Toast.LENGTH_SHORT).show()
    }
}
