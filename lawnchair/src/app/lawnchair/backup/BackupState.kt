package app.lawnchair.backup

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.util.Xml
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.room.withTransaction
import app.lawnchair.data.AppDatabase
import com.android.launcher3.LauncherPrefs
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

/** Backup helpers operate on snapshots or live APIs, never on files with open writers. */
internal object BackupState {
    val mutex = Mutex()
    val layoutTables = listOf("IconOverride", "Wallpapers", "Folders", "FolderItems")
    val categoryTables = listOf("Categories", "CategoryItems")
    val allTables = layoutTables + categoryTables

    suspend fun <T> onModelThread(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            com.android.launcher3.util.Executors.MODEL_EXECUTOR.submit(Callable(block)).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    fun openDatabase(context: Context, file: File): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
        .addMigrations(AppDatabase.MIGRATION_1_3, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
        .build()

    suspend fun copyTables(source: AppDatabase, target: AppDatabase, tables: List<String>) {
        // Keep both transactions on this thread. Switching dispatchers while a cursor is open
        // would lose its transaction connection; collecting every icon blob would exhaust RAM.
        source.withTransaction {
            target.runInTransaction(
                Runnable {
                    val targetDb = target.openHelper.writableDatabase
                    tables.asReversed().forEach { targetDb.execSQL("DELETE FROM `$it`") }
                    tables.forEach { table ->
                        source.openHelper.writableDatabase.query("SELECT * FROM `$table`").use { rows ->
                            while (rows.moveToNext()) {
                                val values = ContentValues()
                                DatabaseUtils.cursorRowToContentValues(rows, values)
                                targetDb.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
                            }
                        }
                    }
                },
            )
        }
    }

    suspend fun writeDatabaseSnapshot(context: Context, target: File) {
        openDatabase(context, target).useSnapshot { snapshot ->
            copyTables(AppDatabase.INSTANCE.get(context), snapshot, allTables)
        }
    }

    // Use a temporary DataStore to read/write protobufs through its public serializer.
    suspend fun readDataStore(file: File): Preferences = withDataStore(file) { it.data.first() }

    suspend fun writeDataStore(file: File, preferences: Preferences) {
        withDataStore(file) { it.updateData { preferences } }
    }

    private suspend fun <T> withDataStore(
        file: File,
        block: suspend (androidx.datastore.core.DataStore<Preferences>) -> T,
    ): T {
        val job = Job()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
        return try {
            block(store)
        } finally {
            job.cancelAndJoin()
        }
    }

    fun readSharedPreferences(file: File): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        file.inputStream().use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, "UTF-8")
            parser.nextTag()
            parser.require(XmlPullParser.START_TAG, null, "map")
            while (parser.nextTag() != XmlPullParser.END_TAG) {
                val type = parser.name
                val key = parser.getAttributeValue(null, "name") ?: throw IOException("Missing preference name")
                val value = parser.getAttributeValue(null, "value")
                result[key] = when (type) {
                    "string" -> parser.nextText()

                    "set" -> buildSet {
                        while (parser.nextTag() != XmlPullParser.END_TAG) {
                            parser.require(XmlPullParser.START_TAG, null, "string")
                            add(parser.nextText())
                        }
                    }

                    "int" -> value!!.toInt().also { parser.nextTag() }

                    "long" -> value!!.toLong().also { parser.nextTag() }

                    "float" -> value!!.toFloat().also { parser.nextTag() }

                    "boolean" -> value!!.toBooleanStrict().also { parser.nextTag() }

                    else -> throw IOException("Unsupported preference type $type")
                }
                parser.require(XmlPullParser.END_TAG, null, type)
            }
        }
        return result
    }

    fun writeSharedPreferences(file: File, values: Map<String, *>) {
        file.outputStream().use { output ->
            val xml = Xml.newSerializer()
            xml.setOutput(output, "UTF-8")
            xml.startDocument("UTF-8", true)
            xml.startTag(null, "map")
            values.forEach { (key, value) ->
                val type = when (value) {
                    is String -> "string"
                    is Set<*> -> "set"
                    is Int -> "int"
                    is Long -> "long"
                    is Float -> "float"
                    is Boolean -> "boolean"
                    else -> throw IOException("Unsupported preference $key")
                }
                xml.startTag(null, type).attribute(null, "name", key)
                when (value) {
                    is String -> xml.text(value)
                    is Set<*> -> value.forEach { xml.startTag(null, "string").text(it as String).endTag(null, "string") }
                    else -> xml.attribute(null, "value", value.toString())
                }
                xml.endTag(null, type)
            }
            xml.endTag(null, "map")
            xml.endDocument()
        }
    }

    fun applySharedPreferences(context: Context, values: Map<String, *>) {
        val editor = LauncherPrefs.getPrefs(context).edit().clear()
        values.forEach { (key, value) -> putPreference(editor, key, value) }
        if (!editor.commit()) throw IOException("Unable to save restored preferences")
    }

    private fun putPreference(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is String -> editor.putString(key, value)
            is Set<*> -> editor.putStringSet(key, value.map { it as String }.toSet())
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            else -> throw IOException("Unsupported preference $key")
        }
    }
}

internal suspend fun <T> AppDatabase.useSnapshot(block: suspend (AppDatabase) -> T): T = try {
    block(this)
} finally {
    close()
}
