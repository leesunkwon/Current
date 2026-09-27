package com.kotlinsun.current.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

@Entity(tableName = "tabs")
data class TabRecord(
    @PrimaryKey val id: String,
    val position: Int,
    val url: String?,
    val title: String,
    val selected: Boolean,
    @ColumnInfo(defaultValue = "0") val desktopMode: Boolean = false,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    val groupName: String? = null,
)

@Dao
interface TabDao {
    @Query("SELECT * FROM tabs ORDER BY position ASC")
    suspend fun getAll(): List<TabRecord>

    @Query("DELETE FROM tabs")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(tabs: List<TabRecord>)
}

@Entity(tableName = "history")
data class HistoryRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long,
    val favicon: ByteArray? = null,
)

data class TopSiteRecord(val url: String, val title: String, val visitCount: Long, val lastVisited: Long)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY visitedAt DESC")
    suspend fun getAll(): List<HistoryRecord>

    @Query("SELECT h.url AS url, COALESCE((SELECT latest.title FROM history AS latest WHERE latest.url = h.url ORDER BY latest.visitedAt DESC LIMIT 1), h.url) AS title, COUNT(*) AS visitCount, MAX(h.visitedAt) AS lastVisited FROM history AS h WHERE h.url LIKE 'http%' AND h.visitedAt >= :since GROUP BY h.url ORDER BY visitCount DESC, lastVisited DESC LIMIT 40")
    suspend fun topSites(since: Long): List<TopSiteRecord>

    @Insert
    suspend fun insert(record: HistoryRecord)

    @Query("UPDATE history SET title = :title, favicon = :favicon WHERE id = (SELECT id FROM history WHERE url = :url ORDER BY visitedAt DESC LIMIT 1)")
    suspend fun updateLatest(url: String, title: String, favicon: ByteArray?)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history WHERE visitedAt >= :from")
    suspend fun deleteSince(from: Long)
}

@Entity(tableName = "bookmarks")
data class BookmarkRecord(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val createdAt: Long,
    val folderId: String? = null,
    @ColumnInfo(defaultValue = "0") val pinnedToHome: Boolean = false,
)

@Entity(tableName = "bookmark_folders")
data class BookmarkFolderRecord(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
    val createdAt: Long,
)

@Dao
interface BookmarkFolderDao {
    @Query("SELECT * FROM bookmark_folders ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<BookmarkFolderRecord>

    @Insert
    suspend fun insert(record: BookmarkFolderRecord)

    @Update
    suspend fun update(record: BookmarkFolderRecord)

    @Query("DELETE FROM bookmark_folders WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE bookmark_folders SET parentId = :parentId WHERE parentId = :id")
    suspend fun moveChildren(id: String, parentId: String?)
}

@Entity(tableName = "site_permissions", primaryKeys = ["origin", "kind"])
data class SitePermissionRecord(
    val origin: String,
    val kind: String,
    val allowed: Boolean,
    val updatedAt: Long,
)

@Dao
interface SitePermissionDao {
    @Query("SELECT * FROM site_permissions ORDER BY origin ASC, kind ASC")
    suspend fun getAll(): List<SitePermissionRecord>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insert(record: SitePermissionRecord)

    @Query("DELETE FROM site_permissions WHERE origin = :origin AND kind = :kind")
    suspend fun delete(origin: String, kind: String)

    @Query("DELETE FROM site_permissions")
    suspend fun deleteAll()
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    suspend fun getAll(): List<BookmarkRecord>

    @Query("SELECT * FROM bookmarks WHERE url LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' ORDER BY createdAt DESC LIMIT 30")
    suspend fun search(query: String): List<BookmarkRecord>

    @Insert
    suspend fun insert(record: BookmarkRecord)

    @Update
    suspend fun update(record: BookmarkRecord)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE bookmarks SET folderId = :parentId WHERE folderId = :id")
    suspend fun moveFromFolder(id: String, parentId: String?)
}

@Entity(tableName = "downloads")
data class DownloadRecord(
    @PrimaryKey val id: Long,
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val createdAt: Long,
)

@Entity(tableName = "local_downloads")
data class LocalDownloadRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val contentUri: String,
    val createdAt: Long,
)

@Dao
interface LocalDownloadDao {
    @Query("SELECT * FROM local_downloads ORDER BY createdAt DESC")
    suspend fun getAll(): List<LocalDownloadRecord>

    @Insert
    suspend fun insert(record: LocalDownloadRecord): Long

    @Query("DELETE FROM local_downloads WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    suspend fun getAll(): List<DownloadRecord>

    @Insert
    suspend fun insert(record: DownloadRecord)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [TabRecord::class, HistoryRecord::class, BookmarkRecord::class,
    BookmarkFolderRecord::class, SitePermissionRecord::class,
    DownloadRecord::class, LocalDownloadRecord::class], version = 6, exportSchema = false)
abstract class BrowserDatabase : RoomDatabase() {
    abstract fun tabDao(): TabDao
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun bookmarkFolderDao(): BookmarkFolderDao
    abstract fun sitePermissionDao(): SitePermissionDao
    abstract fun downloadDao(): DownloadDao
    abstract fun localDownloadDao(): LocalDownloadDao
}

private val migration1To2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, `visitedAt` INTEGER NOT NULL, `favicon` BLOB)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `bookmarks` (`id` TEXT NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `downloads` (`id` INTEGER NOT NULL, `url` TEXT NOT NULL, `fileName` TEXT NOT NULL, `mimeType` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    }
}

private val migration2To3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `local_downloads` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `url` TEXT NOT NULL, `fileName` TEXT NOT NULL, `mimeType` TEXT, `contentUri` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
    }
}

private val migration3To4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `tabs` ADD COLUMN `desktopMode` INTEGER NOT NULL DEFAULT 0")
    }
}

private val migration4To5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `tabs` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
    }
}

private val migration5To6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `tabs` ADD COLUMN `groupName` TEXT")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `folderId` TEXT")
        db.execSQL("ALTER TABLE `bookmarks` ADD COLUMN `pinnedToHome` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS `bookmark_folders` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `parentId` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `site_permissions` (`origin` TEXT NOT NULL, `kind` TEXT NOT NULL, `allowed` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`origin`, `kind`))")
    }
}

private val Context.browserSettings by preferencesDataStore("browser_settings")
private val searchEngineKey = stringPreferencesKey("search_engine")
private val themeKey = stringPreferencesKey("theme")
private val textZoomKey = intPreferencesKey("text_zoom")
private val thirdPartyCookiesKey = booleanPreferencesKey("third_party_cookies")
private val suggestionCutoffKey = longPreferencesKey("suggestion_cutoff")
private val onboardingSeenKey = booleanPreferencesKey("onboarding_seen")
private val homePageKey = stringPreferencesKey("home_page_url")
private val trackingProtectionKey = booleanPreferencesKey("tracking_protection")
private val trackingExceptionsKey = stringSetPreferencesKey("tracking_exceptions")

data class BrowserPreferences(
    val searchEngine: String = "GOOGLE",
    val theme: String = "SYSTEM",
    val textZoom: Int = 100,
    val thirdPartyCookies: Boolean = false,
    val suggestionCutoff: Long = 0,
    val onboardingSeen: Boolean? = null,
    val homePageUrl: String? = null,
    val trackingProtection: Boolean = false,
    val trackingExceptions: Set<String> = emptySet(),
)

class BrowserStore private constructor(context: Context) {
    companion object {
        @Volatile private var instance: BrowserStore? = null

        fun get(context: Context): BrowserStore = instance ?: synchronized(this) {
            instance ?: BrowserStore(context).also { instance = it }
        }
    }

    private sealed interface StateOperation {
        data class Write(val id: String, val bytes: ByteArray) : StateOperation
        data class Delete(val id: String) : StateOperation
        data class Read(val id: String, val result: CompletableDeferred<ByteArray?>) : StateOperation
        data class WritePreview(val id: String, val bytes: ByteArray) : StateOperation
        data class DeletePreview(val id: String) : StateOperation
        data class ReadPreview(val id: String, val result: CompletableDeferred<ByteArray?>) : StateOperation
        data class PrunePreviews(val ids: Set<String>) : StateOperation
        data class PruneStates(val ids: Set<String>) : StateOperation
        data object DeleteAllStates : StateOperation
    }

    private sealed interface TabOperation {
        data class Write(val tabs: List<TabRecord>) : TabOperation
        data class Read(val result: CompletableDeferred<Result<List<TabRecord>>>) : TabOperation
    }

    private val application = context.applicationContext
    private val database = Room.databaseBuilder(application, BrowserDatabase::class.java, "browser.db")
        .addMigrations(migration1To2, migration2To3, migration3To4, migration4To5, migration5To6).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tabOperations = Channel<TabOperation>(Channel.UNLIMITED)
    private val stateOperations = Channel<StateOperation>(Channel.UNLIMITED)
    private val stateDirectory = File(application.filesDir, "tab_states").apply { mkdirs() }
    private val previewDirectory = File(application.filesDir, "tab_previews").apply { mkdirs() }

    init {
        scope.launch {
            for (operation in tabOperations) {
                when (operation) {
                    is TabOperation.Write -> try {
                        database.withTransaction {
                            database.tabDao().deleteAll()
                            database.tabDao().insertAll(operation.tabs)
                        }
                    } catch (error: Exception) {
                        Log.e("BrowserStore", "탭 저장 실패", error)
                    }
                    is TabOperation.Read -> operation.result.complete(runCatching {
                        database.tabDao().getAll()
                    }.onFailure { Log.e("BrowserStore", "탭 복원 실패", it) })
                }
            }
        }
        scope.launch {
            for (operation in stateOperations) {
                try {
                    when (operation) {
                        is StateOperation.Write -> runCatching {
                            writeStateNow(operation.id, operation.bytes)
                        }.onFailure { Log.e("BrowserStore", "탭 상태 저장 실패", it) }
                        is StateOperation.Delete -> runCatching {
                            stateFile(operation.id).delete()
                        }.onFailure { Log.e("BrowserStore", "탭 상태 삭제 실패", it) }
                        is StateOperation.Read -> operation.result.complete(runCatching {
                            readAtomic(stateFile(operation.id), 256 * 1024)
                        }.getOrNull())
                        is StateOperation.WritePreview -> runCatching {
                            writeAtomic(previewFile(operation.id), operation.bytes)
                            trimPreviews()
                        }.onFailure { Log.e("BrowserStore", "탭 미리보기 저장 실패", it) }
                        is StateOperation.DeletePreview -> previewFile(operation.id).delete()
                        is StateOperation.ReadPreview -> operation.result.complete(runCatching {
                            readAtomic(previewFile(operation.id), 128 * 1024)
                        }.getOrNull())
                        is StateOperation.PrunePreviews -> previewDirectory.listFiles()?.forEach { file ->
                            if (file.name.substringBefore('.') !in operation.ids) file.delete()
                        }
                        is StateOperation.PruneStates -> stateDirectory.listFiles()?.forEach { file ->
                            if (file.name.substringBefore('.') !in operation.ids) file.delete()
                        }
                        StateOperation.DeleteAllStates -> stateDirectory.listFiles()?.forEach { it.delete() }
                    }
                } catch (error: Exception) {
                    Log.e("BrowserStore", "탭 상태 작업 실패", error)
                    when (operation) {
                        is StateOperation.Read -> operation.result.complete(null)
                        is StateOperation.ReadPreview -> operation.result.complete(null)
                        else -> Unit
                    }
                }
            }
        }
    }

    suspend fun loadTabs(): Result<List<TabRecord>> {
        val result = CompletableDeferred<Result<List<TabRecord>>>()
        tabOperations.send(TabOperation.Read(result))
        return result.await()
    }

    fun saveTabs(tabs: List<TabRecord>) {
        tabOperations.trySend(TabOperation.Write(tabs))
    }

    suspend fun loadSearchEngine(): String = application.browserSettings.data
        .map { it[searchEngineKey] ?: "GOOGLE" }.first()

    suspend fun loadPreferences(): BrowserPreferences = application.browserSettings.data.map {
        BrowserPreferences(
            searchEngine = it[searchEngineKey] ?: "GOOGLE",
            theme = it[themeKey] ?: "SYSTEM",
            textZoom = it[textZoomKey] ?: 100,
            thirdPartyCookies = it[thirdPartyCookiesKey] ?: false,
            suggestionCutoff = it[suggestionCutoffKey] ?: 0,
            onboardingSeen = it[onboardingSeenKey],
            homePageUrl = it[homePageKey],
            trackingProtection = it[trackingProtectionKey] ?: false,
            trackingExceptions = it[trackingExceptionsKey] ?: emptySet(),
        )
    }.first()

    fun saveSearchEngine(name: String) {
        scope.launch { application.browserSettings.edit { it[searchEngineKey] = name } }
    }

    fun saveTheme(name: String) {
        scope.launch { application.browserSettings.edit { it[themeKey] = name } }
    }

    fun saveTextZoom(value: Int) {
        scope.launch { application.browserSettings.edit { it[textZoomKey] = value } }
    }

    fun saveThirdPartyCookies(value: Boolean) {
        scope.launch { application.browserSettings.edit { it[thirdPartyCookiesKey] = value } }
    }

    fun saveHomePage(url: String?) {
        scope.launch { application.browserSettings.edit {
            if (url == null) it.remove(homePageKey) else it[homePageKey] = url
        } }
    }

    fun saveTrackingProtection(enabled: Boolean) {
        scope.launch { application.browserSettings.edit { it[trackingProtectionKey] = enabled } }
    }

    fun saveTrackingExceptions(origins: Set<String>) {
        scope.launch { application.browserSettings.edit { it[trackingExceptionsKey] = origins } }
    }

    fun saveSuggestionCutoff(value: Long) {
        scope.launch { application.browserSettings.edit { it[suggestionCutoffKey] = value } }
    }

    suspend fun saveOnboardingSeen(value: Boolean) {
        application.browserSettings.edit { it[onboardingSeenKey] = value }
    }

    fun persistOnboardingSeen(value: Boolean) {
        scope.launch { runCatching { saveOnboardingSeen(value) } }
    }

    suspend fun readTabState(id: String): ByteArray? {
        val result = CompletableDeferred<ByteArray?>()
        stateOperations.send(StateOperation.Read(id, result))
        return result.await()
    }

    fun writeTabState(id: String, state: ByteArray) {
        stateOperations.trySend(StateOperation.Write(id, state))
    }

    fun deleteTabState(id: String) {
        stateOperations.trySend(StateOperation.Delete(id))
    }

    fun clearAllTabStates() {
        stateOperations.trySend(StateOperation.DeleteAllStates)
    }

    suspend fun readPreview(id: String): ByteArray? {
        val result = CompletableDeferred<ByteArray?>()
        stateOperations.send(StateOperation.ReadPreview(id, result))
        return result.await()
    }

    fun writePreview(id: String, bytes: ByteArray) {
        if (bytes.size <= 128 * 1024) stateOperations.trySend(StateOperation.WritePreview(id, bytes))
    }

    fun deletePreview(id: String) {
        stateOperations.trySend(StateOperation.DeletePreview(id))
    }

    fun prunePreviews(ids: Set<String>) {
        stateOperations.trySend(StateOperation.PrunePreviews(ids))
    }

    fun pruneTabStates(ids: Set<String>) {
        stateOperations.trySend(StateOperation.PruneStates(ids))
    }

    suspend fun loadHistory(): List<HistoryRecord> = database.historyDao().getAll()
    suspend fun loadTopSites(since: Long): List<TopSiteRecord> = database.historyDao().topSites(since)
    suspend fun addVisit(record: HistoryRecord) = database.historyDao().insert(record)
    suspend fun updateVisit(url: String, title: String, favicon: ByteArray?) =
        database.historyDao().updateLatest(url, title, favicon)
    suspend fun deleteVisit(id: Long) = database.historyDao().delete(id)
    suspend fun deleteHistorySince(from: Long) = database.historyDao().deleteSince(from)

    suspend fun loadBookmarks(): List<BookmarkRecord> = database.bookmarkDao().getAll()
    suspend fun loadBookmarkFolders(): List<BookmarkFolderRecord> = database.bookmarkFolderDao().getAll()
    suspend fun addBookmarkFolder(record: BookmarkFolderRecord) = database.bookmarkFolderDao().insert(record)
    suspend fun updateBookmarkFolder(record: BookmarkFolderRecord) = database.bookmarkFolderDao().update(record)
    suspend fun deleteBookmarkFolder(id: String) = database.withTransaction {
        val folder = database.bookmarkFolderDao().getAll().firstOrNull { it.id == id } ?: return@withTransaction
        database.bookmarkDao().moveFromFolder(id, folder.parentId)
        database.bookmarkFolderDao().moveChildren(id, folder.parentId)
        database.bookmarkFolderDao().delete(id)
    }
    suspend fun searchBookmarks(query: String): List<BookmarkRecord> = database.bookmarkDao().search(query)
    suspend fun addBookmark(record: BookmarkRecord) = database.bookmarkDao().insert(record)
    suspend fun importBookmarks(records: List<BookmarkRecord>,
                                folders: List<BookmarkFolderRecord> = emptyList()) = database.withTransaction {
        folders.forEach { database.bookmarkFolderDao().insert(it) }
        records.forEach { database.bookmarkDao().insert(it) }
    }
    suspend fun updateBookmark(record: BookmarkRecord) = database.bookmarkDao().update(record)
    suspend fun deleteBookmark(id: String) = database.bookmarkDao().delete(id)

    suspend fun loadSitePermissions(): List<SitePermissionRecord> = database.sitePermissionDao().getAll()
    suspend fun saveSitePermissions(records: List<SitePermissionRecord>) = database.withTransaction {
        records.forEach { database.sitePermissionDao().insert(it) }
    }
    suspend fun deleteSitePermission(origin: String, kind: String) =
        database.sitePermissionDao().delete(origin, kind)
    suspend fun clearSitePermissions() = database.sitePermissionDao().deleteAll()

    suspend fun loadDownloads(): List<DownloadRecord> = database.downloadDao().getAll()
    suspend fun addDownload(record: DownloadRecord) = database.downloadDao().insert(record)
    suspend fun deleteDownload(id: Long) = database.downloadDao().delete(id)
    suspend fun loadLocalDownloads(): List<LocalDownloadRecord> = database.localDownloadDao().getAll()
    suspend fun addLocalDownload(record: LocalDownloadRecord): Long = database.localDownloadDao().insert(record)
    suspend fun deleteLocalDownload(id: Long) = database.localDownloadDao().delete(id)

    private fun writeStateNow(id: String, state: ByteArray) {
        writeAtomic(stateFile(id), state)
    }

    private fun readAtomic(file: AtomicFile, maxBytes: Int): ByteArray? = file.openRead().use { input ->
        if (input.channel.size() > maxBytes) null
        else input.readBytes().takeIf { it.size <= maxBytes }
    }

    private fun writeAtomic(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun stateFile(id: String): AtomicFile = AtomicFile(File(stateDirectory, "$id.bin"))
    private fun previewFile(id: String): AtomicFile = AtomicFile(File(previewDirectory, "$id.webp"))

    private fun trimPreviews() {
        var total = 0L
        previewDirectory.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { file ->
            total += file.length()
            if (total > 12L * 1024 * 1024) file.delete()
        }
    }
}
