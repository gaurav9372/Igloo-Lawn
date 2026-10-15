package app.lawnchair.backup

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.os.Process
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.data.AppDatabase
import app.lawnchair.data.category.CategoryInfoEntity
import app.lawnchair.data.folder.FolderInfoEntity
import app.lawnchair.data.folder.FolderItemEntity
import app.lawnchair.search.algorithms.filterHiddenApps
import app.lawnchair.ui.preferences.components.HiddenAppsInSearch
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.model.DatabaseHelper
import com.android.launcher3.model.ModelDbController
import com.android.launcher3.model.data.AppInfo
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Every database and preference here is isolated from the installed launcher's user data. */
@RunWith(AndroidJUnit4::class)
class BackupStateTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private val databases = mutableListOf<AppDatabase>()

    @Before fun setUp() {
        directory = File(context.cacheDir, "backup-regression-${UUID.randomUUID()}")
        check(directory.mkdirs())
    }

    @After fun tearDown() {
        databases.forEach { it.close() }
        directory.deleteRecursively()
    }

    private fun database(name: String): AppDatabase = Room.databaseBuilder(
        context, AppDatabase::class.java, File(directory, name).path,
    ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build().also(databases::add)

    @Test fun snapshotsIncludeCommittedWalRows() = runBlocking {
        val source = database("source")
        source.openHelper.writableDatabase.query("PRAGMA wal_autocheckpoint=0").use { it.moveToFirst() }
        source.folderDao().insertFolder(FolderInfoEntity(id = 7, title = "In WAL"))
        assertTrue(File(directory, "source-wal").length() > 0)
        val file = File(directory, "snapshot")
        BackupState.openDatabase(context, file).useSnapshot { snapshot ->
            BackupState.copyTables(source, snapshot, BackupState.allTables)
        }
        // A single published database file must be sufficient after its writer is closed.
        BackupState.openDatabase(context, file).useSnapshot { reopened ->
            assertEquals("In WAL", reopened.folderDao().getAllFoldersWithItems().first().single().folder.title)
        }
    }

    @Test fun layoutImportPreservesCategoriesAndCachedDaos() = runBlocking {
        val source = database("source")
        val target = database("target")
        val cachedDao = target.folderDao()
        source.folderDao().insertFolder(FolderInfoEntity(id = 4, title = "Imported"))
        source.categoryDao().insertCategory(CategoryInfoEntity(id = 10, title = "Unselected"))
        target.categoryDao().insertCategory(CategoryInfoEntity(id = 20, title = "Keep category"))
        BackupState.copyTables(source, target, BackupState.layoutTables)
        cachedDao.insertFolder(FolderInfoEntity(id = 5, title = "Still writable"))
        assertEquals(2, cachedDao.getAllFoldersWithItems().first().size)
        assertEquals("Keep category", target.categoryDao().getAllCategoriesWithItems().first().single().category.title)
    }

    @Test fun categoryImportDoesNotReplaceFolders() = runBlocking {
        val source = database("source")
        val target = database("target")
        source.categoryDao().insertCategory(CategoryInfoEntity(id = 10, title = "Imported category"))
        source.folderDao().insertFolder(FolderInfoEntity(id = 11, title = "Unselected folder"))
        target.folderDao().insertFolder(FolderInfoEntity(id = 12, title = "Keep folder"))
        BackupState.copyTables(source, target, BackupState.categoryTables)
        assertEquals("Keep folder", target.folderDao().getAllFoldersWithItems().first().single().folder.title)
        assertEquals("Imported category", target.categoryDao().getAllCategoriesWithItems().first().single().category.title)
    }

    @Test fun failedRoomImportRollsBackParentAndChildRows() = runBlocking {
        val source = database("source")
        val target = database("target")
        source.folderDao().insertFolder(FolderInfoEntity(id = 1, title = "Broken"))
        target.folderDao().insertFolder(FolderInfoEntity(id = 2, title = "Original"))
        target.folderDao().insertFolderItems(listOf(FolderItemEntity(folderId = 2, componentKey = "app/key")))
        // Inject a payload the destination's NOT NULL constraint must reject.
        source.openHelper.writableDatabase.execSQL("ALTER TABLE Folders RENAME TO oldFolders")
        source.openHelper.writableDatabase.execSQL(
            "CREATE TABLE Folders AS SELECT id, NULL AS title, hide, rank, timestamp FROM oldFolders",
        )
        try {
            BackupState.copyTables(source, target, BackupState.layoutTables)
            fail("Import must reject an invalid title")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            val restored = target.folderDao().getAllFoldersWithItems().first().single()
            assertEquals("Original", restored.folder.title)
            assertEquals("app/key", restored.items.single().componentKey)
        }
    }

    @Test fun sharedPreferencesRoundTripAndUpdateTheCachedInstance() {
        val values = mapOf(
            "text" to "<restored & escaped>", "int" to 4, "long" to 5L,
            "float" to 1.5f, "bool" to true, "set" to setOf("a", "b"), "empty" to "",
        )
        val file = File(directory, "prefs.xml")
        BackupState.writeSharedPreferences(file, values)
        assertEquals(values, BackupState.readSharedPreferences(file))
        val testContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences(directory.name, mode)
        }
        val cached = LauncherPrefs.getPrefs(testContext)
        try {
            check(cached.edit().putString("old", "value").commit())
            BackupState.applySharedPreferences(testContext, values)
            check(cached.edit().putInt("later", 9).commit())
            assertEquals("<restored & escaped>", cached.getString("text", null))
            assertFalse(cached.contains("old"))
        } finally {
            context.deleteSharedPreferences(directory.name)
        }
    }

    @Test fun dataStoreSnapshotsCanBeReopenedWithoutLosingValues() = runBlocking {
        val file = File(directory, "preferences.preferences_pb")
        val key = intPreferencesKey("grid")
        BackupState.writeDataStore(file, preferencesOf(key to 7))
        assertEquals(7, BackupState.readDataStore(file)[key])
        BackupState.writeDataStore(file, preferencesOf(key to 9))
        assertEquals(9, BackupState.readDataStore(file)[key])
    }

    private fun workspace(name: String, title: String): ModelDbController {
        val file = File(directory, name)
        DatabaseHelper(context, file.path, { 0L }, {}).use { helper ->
            helper.writableDatabase.insertOrThrow("favorites", null, ContentValues().apply {
                put("_id", 1)
                put("title", title)
                put("itemType", 4)
                put("appWidgetId", 123)
                put("profileId", 10)
            })
        }
        return ModelDbController.openSnapshot(context, file)
    }

    @Test fun repeatedWorkspaceSnapshotsKeepNewEditsAndWidgetIds() = runBlocking {
        BackupState.onModelThread {
            val source = workspace("workspace", "First")
            try {
                val file = File(directory, "bk_workspace")
                source.writeSnapshot(file)
                source.db.execSQL("UPDATE favorites SET title = 'Edited'")
                source.writeSnapshot(file)
                val snapshot = ModelDbController.openSnapshot(context, file)
                try {
                    source.replaceFavoritesFrom(snapshot)
                    source.db.rawQuery("SELECT title, appWidgetId, profileId FROM favorites", null).use {
                        assertTrue(it.moveToFirst())
                        assertEquals("Edited", it.getString(0))
                        assertEquals(123, it.getInt(1))
                        assertEquals(10, it.getInt(2))
                    }
                } finally { snapshot.closeSnapshot() }
            } finally { source.closeSnapshot() }
        }
    }

    @Test fun failedWorkspaceImportPreservesTheOriginalLayout() = runBlocking {
        BackupState.onModelThread {
            val source = workspace("source-workspace", "Imported")
            val target = workspace("target-workspace", "Original")
            try {
                target.db.execSQL("CREATE TRIGGER reject_import BEFORE INSERT ON favorites BEGIN SELECT RAISE(ABORT, 'test failure'); END")
                try {
                    target.replaceFavoritesFrom(source)
                    fail("Import should fail")
                } catch (_: android.database.sqlite.SQLiteConstraintException) {
                    target.db.rawQuery("SELECT title FROM favorites", null).use {
                        assertTrue(it.moveToFirst())
                        assertEquals("Original", it.getString(0))
                    }
                }
            } finally {
                source.closeSnapshot()
                target.closeSnapshot()
            }
        }
    }

    @Test fun unsupportedWorkspaceMigrationIsRejected() = runBlocking {
        BackupState.onModelThread {
            val source = workspace("unsupported-workspace", "Must not become an empty import")
            try {
                source.db.version = 1 // DatabaseHelper has no migration from this schema version.
            } finally {
                source.closeSnapshot()
            }
            try {
                ModelDbController.openSnapshot(context, File(directory, "unsupported-workspace"))
                    .also { it.closeSnapshot() }
                fail("An unsupported migration must not be accepted as an empty workspace")
            } catch (_: IllegalArgumentException) {
                // Rejected before a caller can replace any live workspace rows.
            }
        }
    }

    @Test fun emptySearchRespectsAllThreeHiddenAppPolicies() {
        val app = AppInfo().apply {
            title = "Hidden app"
            componentName = ComponentName("test.app", "test.app.Main")
            user = Process.myUserHandle()
        }
        val hidden = setOf(app.toComponentKey().toString())
        assertTrue(sequenceOf(app).filterHiddenApps("", hidden, HiddenAppsInSearch.NEVER).none())
        assertTrue(sequenceOf(app).filterHiddenApps("", hidden, HiddenAppsInSearch.IF_NAME_TYPED).none())
        assertEquals(app, sequenceOf(app).filterHiddenApps("", hidden, HiddenAppsInSearch.ALWAYS).single())
        app.title = ""
        assertTrue(sequenceOf(app).filterHiddenApps("", hidden, HiddenAppsInSearch.IF_NAME_TYPED).none())
    }
}
