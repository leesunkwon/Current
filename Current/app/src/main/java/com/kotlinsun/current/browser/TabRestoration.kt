package com.kotlinsun.current.browser

import com.kotlinsun.current.data.BrowserStore
import com.kotlinsun.current.data.TabRecord
import com.kotlinsun.current.engine.EngineSession
import com.kotlinsun.current.engine.TabMode

/** Keeps the persisted tab fields and WebView state fallback in one place. */
internal class TabRestoration(private val store: BrowserStore) {
    suspend fun readState(tab: BrowserTab, cached: ByteArray?): ByteArray? =
        cached ?: if (tab.mode == TabMode.NORMAL) store.readTabState(tab.id) else null

    fun restoreOrLoad(session: EngineSession, bytes: ByteArray?, fallbackUrl: String?): Boolean {
        if (bytes == null) {
            fallbackUrl?.let(session::load)
            return true
        }
        return session.restoreState(bytes, fallbackUrl)
    }

    companion object {
        fun fromRecord(record: TabRecord): BrowserTab = BrowserTab(record.id,
            url = record.url, title = record.title, desktopMode = record.desktopMode,
            pinned = record.pinned, groupName = record.groupName)

        fun toRecord(tab: BrowserTab, position: Int, selected: Boolean): TabRecord =
            TabRecord(tab.id, position, tab.url, tab.title, selected, tab.desktopMode,
                tab.pinned, tab.groupName)
    }
}
