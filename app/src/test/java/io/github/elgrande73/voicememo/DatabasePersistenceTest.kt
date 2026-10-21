package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.memo.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Verifies that recordings stored with the exported schema are preserved when the current app version opens the
 * database, repeatedly, and that their audio files are left untouched.
 *
 * The version-1 database is created exactly as an installed app would have it, from the committed schema in
 * `app/schemas` (tables, indices and Room's identity hash). If the entities change without a version bump and a
 * migration, Room rejects this database and the test fails instead of users losing data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabasePersistenceTest {

    private val testDbName = "persistence-test-db"
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun createDatabaseFromExportedSchema(version: Int): SQLiteDatabase {
        val schemaFile = File("schemas/${AppDatabase::class.java.name}/$version.json")
        assertTrue("Exported schema missing: ${schemaFile.absolutePath}", schemaFile.isFile)
        val database = JSONObject(schemaFile.readText()).getJSONObject("database")

        val dbFile = context.getDatabasePath(testDbName).apply { parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        val entities = database.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices")
            for (j in 0 until (indices?.length() ?: 0)) {
                db.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val setupQueries = database.getJSONArray("setupQueries")
        for (i in 0 until setupQueries.length()) db.execSQL(setupQueries.getString(i))
        db.version = version
        return db
    }

    @After
    fun tearDown() {
        context.deleteDatabase(testDbName)
    }

    private fun openWithCurrentApp(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, testDbName)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    @Test
    fun recordingsWrittenWithSchemaV1AreKeptByCurrentVersionOnEveryOpen() {
        val audio = File(context.filesDir, "memos/memo_test.wav").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(64) { it.toByte() })
        }

        createDatabaseFromExportedSchema(1).use { db ->
            val values = ContentValues().apply {
                put("title", "Wake Phrase Memo (Oct 20)")
                put("timestamp", 1_700_000_000_000L)
                put("duration_seconds", 4.5)
                put("audio_file_path", audio.absolutePath)
                put("trigger_type", MemoTriggerDetector.WAKE_PHRASE_TRIGGER_TYPE)
                put("is_favorite", 1)
                put("transcription_status", "SKIPPED")
                put("peak_db", 61.0f)
                put("avg_db", 48.0f)
            }
            db.insertOrThrow("voice_notes", null, values)
        }

        repeat(2) {
            val database = openWithCurrentApp()
            val notes = runBlocking { database.voiceNoteDao().getAllNotes().first() }
            assertEquals(1, notes.size)
            assertEquals("Wake Phrase Memo (Oct 20)", notes.single().title)
            assertEquals(MemoTriggerDetector.WAKE_PHRASE_TRIGGER_TYPE, notes.single().triggerType)
            assertTrue(notes.single().isFavorite)
            assertEquals(audio.absolutePath, notes.single().audioFilePath)
            database.close()
        }
        assertEquals(64L, audio.length())
    }
}
