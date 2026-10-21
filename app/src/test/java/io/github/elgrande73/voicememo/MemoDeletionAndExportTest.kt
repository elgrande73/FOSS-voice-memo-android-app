package io.github.elgrande73.voicememo

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.memo.AppDatabase
import io.github.elgrande73.voicememo.memo.MemoArchive
import io.github.elgrande73.voicememo.memo.MemoDeleter
import io.github.elgrande73.voicememo.memo.VoiceNote
import io.github.elgrande73.voicememo.memo.VoiceNoteDao
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/** Bulk deletion (database and files stay consistent, only selected memos are touched) and export archives. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MemoDeletionAndExportTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var memosDir: File
    private lateinit var wakePhraseDir: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        memosDir = File(app.filesDir, "memos").apply { deleteRecursively(); mkdirs() }
        wakePhraseDir = File(app.filesDir, "wake_phrase/samples").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun memo(i: Int, title: String = "Memo $i", trigger: String = "MANUAL", bytes: Int = 4000): VoiceNote = runBlocking {
        val file = File(memosDir, "memo_$i.wav").apply { writeBytes(Random(i).nextBytes(bytes)) }
        val note = VoiceNote(title = title, timestamp = 1_700_000_000_000L + i * 1000L, durationSeconds = 1.5, audioFilePath = file.path, triggerType = trigger, transcriptionText = "text $i", transcriptionStatus = "COMPLETED")
        note.copy(id = db.voiceNoteDao().insertVoiceNote(note).toInt())
    }

    private fun ids() = runBlocking { db.voiceNoteDao().getAllNoteIds().toSet() }

    @Test
    fun deletesExactlyTheSelectedMemosAndNothingElse() = runBlocking {
        val notes = (1..6).map { memo(it) }
        val sample = File(wakePhraseDir, "sample_1.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val selected = setOf(notes[1].id, notes[3].id)

        val report = MemoDeleter(db.voiceNoteDao(), memosDir).delete(selected)

        assertEquals(selected, report.deleted)
        assertTrue(report.failed.isEmpty() && report.alreadyGone.isEmpty())
        assertEquals(notes.map { it.id }.toSet() - selected, ids())
        for (n in notes) assertEquals(n.id !in selected, File(n.audioFilePath).exists())
        assertTrue("enrollment sample untouched", sample.exists())
        assertTrue("no leftovers", File(memosDir, MemoDeleter.TRASH_DIR).listFiles().isNullOrEmpty())
    }

    @Test
    fun memosDeletedMeanwhileAreReportedAndNotRecreated() = runBlocking {
        val a = memo(1)
        val b = memo(2)
        db.voiceNoteDao().deleteById(b.id) // e.g. deleted from another screen after selection
        val report = MemoDeleter(db.voiceNoteDao(), memosDir).delete(setOf(a.id, b.id, 999))
        assertEquals(setOf(a.id), report.deleted)
        assertEquals(setOf(b.id, 999), report.alreadyGone)
        assertTrue(ids().isEmpty())
    }

    @Test
    fun aMemoWhoseFileCannotBeMovedIsKeptCompletely() = runBlocking {
        val a = memo(1)
        val b = memo(2)
        // The trash folder cannot be created (a file is in the way): no audio can be moved, nothing may be deleted
        File(memosDir, MemoDeleter.TRASH_DIR).writeText("blocker")
        val report = MemoDeleter(db.voiceNoteDao(), memosDir).delete(setOf(a.id, b.id))
        assertEquals(setOf(a.id, b.id), report.failed)
        assertEquals(setOf(a.id, b.id), ids())
        assertTrue(File(a.audioFilePath).exists() && File(b.audioFilePath).exists())
    }

    @Test
    fun aDatabaseFailureRestoresEveryAudioFile() = runBlocking {
        val notes = (1..3).map { memo(it) }
        val before = notes.map { File(it.audioFilePath).readBytes() }
        val real = db.voiceNoteDao()
        val failing = Proxy.newProxyInstance(VoiceNoteDao::class.java.classLoader, arrayOf(VoiceNoteDao::class.java)) { _, method, args ->
            if (method.name.startsWith("deleteByIds")) throw android.database.sqlite.SQLiteFullException("disk full")
            method.invoke(real, *(args ?: emptyArray()))
        } as VoiceNoteDao
        val report = MemoDeleter(failing, memosDir).delete(notes.map { it.id })
        assertEquals(notes.map { it.id }.toSet(), report.failed)
        assertEquals(notes.map { it.id }.toSet(), ids())
        notes.forEachIndexed { i, n -> assertArrayEquals(before[i], File(n.audioFilePath).readBytes()) }
    }

    @Test
    fun filesOutsideTheMemoFolderAreNeverDeleted() = runBlocking {
        val sample = File(wakePhraseDir, "sample_7.bin").apply { writeBytes(byteArrayOf(9)) }
        val bogus = VoiceNote(title = "broken", durationSeconds = 1.0, audioFilePath = sample.path)
        val id = db.voiceNoteDao().insertVoiceNote(bogus).toInt()
        val report = MemoDeleter(db.voiceNoteDao(), memosDir).delete(setOf(id))
        assertEquals(setOf(id), report.deleted)
        assertTrue(sample.exists())
    }

    @Test
    fun largeLibrariesAreDeletedInOneConsistentPass() = runBlocking {
        val dao = db.voiceNoteDao()
        val ids = (1..2500).map { i ->
            val file = File(memosDir, "m$i.wav").apply { writeBytes(ByteArray(44)) }
            dao.insertVoiceNote(VoiceNote(title = "m$i", durationSeconds = 0.1, audioFilePath = file.path)).toInt()
        }
        val keep = ids.take(10).toSet()
        val start = System.nanoTime()
        val report = MemoDeleter(dao, memosDir).delete(ids.toSet() - keep)
        val ms = (System.nanoTime() - start) / 1e6
        println("MEASURE bulk delete of 2490 memos (host, Robolectric, in-memory DB): %.0f ms".format(ms))
        assertEquals(2490, report.deleted.size)
        assertEquals(keep, ids())
        assertEquals(10, memosDir.listFiles { f -> f.isFile }!!.size)
    }

    // --- Export ---

    private fun unzip(bytes: ByteArray): Map<String, Pair<ZipEntry, ByteArray>> {
        val out = linkedMapOf<String, Pair<ZipEntry, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name] = e to zip.readBytes()
            }
        }
        return out
    }

    @Test
    fun zipContainsTheOriginalAudioUncompressedAndAManifest() {
        val a = memo(1, title = "Shopping: milk/eggs", trigger = "KEYWORD_CIAO_LINDA")
        val b = memo(2, title = "Shopping: milk/eggs") // same title and minute: names must not collide
        val c = memo(3, title = "Idea \"quoted\"\nsecond line")
        val buffer = ByteArrayOutputStream()
        val report = MemoArchive.writeZip(listOf(a, b, c), buffer, now = 0L)
        assertEquals(3, report.exported)
        val entries = unzip(buffer.toByteArray())
        val audio = entries.filterKeys { it.startsWith("audio/") }
        assertEquals(3, audio.size)
        assertEquals(3, audio.keys.map { it.lowercase() }.toSet().size)
        for ((entry, bytes) in audio.values) assertEquals(ZipEntry.STORED, entry.method)
        val originals = listOf(a, b, c).map { File(it.audioFilePath).readBytes() }
        assertTrue(audio.values.all { (_, bytes) -> originals.any { it.contentEquals(bytes) } })
        assertTrue(audio.keys.none { it.removePrefix("audio/").contains('/') || it.contains(':') })

        val json = String(entries.getValue("manifest.json").second)
        assertTrue(json.contains("\"startedBy\": \"wake_phrase\"") && json.contains("\"startedBy\": \"manual\""))
        assertTrue(json.contains("\"transcription\": \"text 1\""))
        assertTrue(json.contains("Idea \\\"quoted\\\"\\nsecond line"))
        assertTrue(json.contains("\"recordedAtUtc\": \"2023-11-14T22:13:21Z\""))
        val csv = String(entries.getValue("manifest.csv").second).lines()
        assertTrue(csv[0].startsWith("id,title,file,sha256"))
        // Sources are untouched
        listOf(a, b, c).forEachIndexed { i, n -> assertArrayEquals(originals[i], File(n.audioFilePath).readBytes()) }
    }

    @Test
    fun missingAudioIsReportedAndListedWithoutFailingTheExport() {
        val a = memo(1)
        val b = memo(2)
        File(b.audioFilePath).delete()
        val buffer = ByteArrayOutputStream()
        val report = MemoArchive.writeZip(listOf(a, b), buffer)
        assertEquals(1, report.exported)
        assertEquals(listOf(b.id), report.missing)
        val json = String(unzip(buffer.toByteArray()).getValue("manifest.json").second)
        assertTrue(json.contains("\"file\": null") && json.contains("audio file missing"))
    }

    @Test
    fun cancellationStopsTheExportAndLeavesSourcesIntact() {
        val notes = (1..4).map { memo(it, bytes = 300_000) }
        var checks = 0
        try {
            MemoArchive.writeZip(notes, ByteArrayOutputStream(), isCancelled = { ++checks > 5 })
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        notes.forEach { assertTrue(File(it.audioFilePath).length() == 300_000L) }
    }

    @Test
    fun singleExportIsTheOriginalWav() {
        val a = memo(1)
        val buffer = ByteArrayOutputStream()
        assertTrue(MemoArchive.writeWav(a, buffer))
        assertArrayEquals(File(a.audioFilePath).readBytes(), buffer.toByteArray())
        File(a.audioFilePath).delete()
        assertFalse(MemoArchive.writeWav(a, ByteArrayOutputStream()))
        assertTrue(MemoArchive.baseName(a.copy(title = "a/b:c*d?")).endsWith("a_b_c_d_"))
    }
}
