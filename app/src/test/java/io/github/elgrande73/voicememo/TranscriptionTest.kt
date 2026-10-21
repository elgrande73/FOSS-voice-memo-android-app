package io.github.elgrande73.voicememo

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.audio.WavWriter
import io.github.elgrande73.voicememo.memo.AppDatabase
import io.github.elgrande73.voicememo.memo.VoiceNote
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import io.github.elgrande73.voicememo.transcription.CancelToken
import io.github.elgrande73.voicememo.transcription.GgmlModelInfo
import io.github.elgrande73.voicememo.transcription.SpeechModelStore
import io.github.elgrande73.voicememo.transcription.SpeechModelMissingException
import io.github.elgrande73.voicememo.transcription.ModelSource
import io.github.elgrande73.voicememo.transcription.SpeechTranscriber
import io.github.elgrande73.voicememo.transcription.TranscriptResult
import io.github.elgrande73.voicememo.transcription.Transcription
import io.github.elgrande73.voicememo.transcription.TranscriptionException
import io.github.elgrande73.voicememo.transcription.TranscriptionJobState
import io.github.elgrande73.voicememo.transcription.TranscriptionManager
import io.github.elgrande73.voicememo.transcription.TranscriptionSettings
import io.github.elgrande73.voicememo.transcription.TranscriptionStatus
import io.github.elgrande73.voicememo.transcription.TranscriptText
import io.github.elgrande73.voicememo.transcription.WavReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptionTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private val executor = Executors.newSingleThreadExecutor()
    private val worker = executor.asCoroutineDispatcher()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        File(app.noBackupFilesDir, SpeechModelStore.DIRECTORY_NAME).deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
        executor.shutdownNow()
    }

    // --- Helpers ---

    private fun speechWav(name: String = "memo.wav", silent: Boolean = false): File {
        val frames = List(40) { f -> ShortArray(800) { i -> if (silent) 0 else (sin((f * 800 + i) * 0.05) * 8000).toInt().toShort() } }
        return File(app.filesDir, name).also { WavWriter.writeWavFile(it, frames, 16000, 1) }
    }

    private fun insert(file: File, text: String? = null, status: String = TranscriptionStatus.SKIPPED): Int = runBlocking {
        db.voiceNoteDao().insertVoiceNote(VoiceNote(title = "m", durationSeconds = 2.0, audioFilePath = file.absolutePath, transcriptionText = text, transcriptionStatus = status)).toInt()
    }

    private fun note(id: Int) = runBlocking { db.voiceNoteDao().getNoteById(id) }

    private open class FakeEngine(private val behaviour: (FloatArray, CancelToken) -> TranscriptResult) : SpeechTranscriber {
        val calls = AtomicInteger()
        val releases = AtomicInteger()
        @Volatile var reportedProgress = 0
        override fun progress() = reportedProgress
        override fun transcribe(samples: FloatArray, settings: TranscriptionSettings, cancel: CancelToken): TranscriptResult {
            calls.incrementAndGet()
            return behaviour(samples, cancel)
        }
        override fun release() { releases.incrementAndGet() }
    }

    private fun manager(
        engine: SpeechTranscriber,
        idleUnloadMs: Long = 60_000,
        timeoutMs: Long? = null,
        worker: kotlinx.coroutines.CoroutineDispatcher = this.worker
    ) = TranscriptionManager(
        dao = db.voiceNoteDao(), transcriberFactory = { engine }, settings = { TranscriptionSettings() },
        scope = CoroutineScope(SupervisorJob()), worker = worker, idleUnloadMs = idleUnloadMs,
        timeoutMs = { s -> timeoutMs ?: TranscriptionManager.MIN_TIMEOUT_MS }
    )

    /** Engine that runs until it is cancelled (or [maxMs] passes), like a hung or very slow native call. */
    private fun blockingEngine(maxMs: Long = 10_000) = FakeEngine { _, cancel ->
        val aborted = CountDownLatch(1)
        cancel.setOnCancel { aborted.countDown() }
        aborted.await(maxMs, TimeUnit.MILLISECONDS)
        throw TranscriptionException(TranscriptionException.Kind.CANCELLED, "Cancelled.")
    }

    private fun waitFor(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("condition not met")
            Thread.sleep(10)
        }
    }

    // --- Manager ---

    @Test
    fun transcriptIsStoredWithTheRightMemoAndAudioIsUntouched() {
        val file = speechWav()
        val before = file.readBytes()
        val other = insert(speechWav("other.wav"), text = "keep me", status = TranscriptionStatus.COMPLETED)
        val id = insert(file)
        val engine = FakeEngine { _, _ -> TranscriptResult(" Allora, ricordati il pane [Musica] ", "it", "m", 5) }
        val m = manager(engine)
        m.enqueue(id)
        waitFor { note(id)?.transcriptionStatus == TranscriptionStatus.COMPLETED }
        assertEquals("Allora, ricordati il pane", note(id)!!.transcriptionText)
        assertEquals("keep me", note(other)!!.transcriptionText)
        assertArrayEquals(before, file.readBytes())
        waitFor { m.jobs.value.isEmpty() }
    }

    @Test
    fun silentMemoIsNotSentToTheModel() {
        val id = insert(speechWav(silent = true))
        val engine = FakeEngine { _, _ -> TranscriptResult("invented", "it", "m", 1) }
        manager(engine).enqueue(id)
        waitFor { note(id)?.transcriptionStatus == TranscriptionStatus.COMPLETED }
        assertEquals("", note(id)!!.transcriptionText)
        assertEquals(0, engine.calls.get())
    }

    @Test
    fun failureKeepsThePreviousTranscriptAndCanBeRetried() {
        val id = insert(speechWav(), text = "older text", status = TranscriptionStatus.COMPLETED)
        var fail = true
        val engine = FakeEngine { _, _ ->
            if (fail) throw TranscriptionException(TranscriptionException.Kind.MODEL, "The speech model could not be loaded.")
            TranscriptResult("new text", "it", "m", 1)
        }
        val m = manager(engine)
        m.enqueue(id)
        waitFor { m.jobs.value[id] is TranscriptionJobState.Failed }
        assertEquals(TranscriptionStatus.FAILED, note(id)!!.transcriptionStatus)
        assertEquals("older text", note(id)!!.transcriptionText)
        assertEquals("The speech model could not be loaded.", (m.jobs.value[id] as TranscriptionJobState.Failed).message)
        fail = false
        m.enqueue(id)
        waitFor { note(id)?.transcriptionStatus == TranscriptionStatus.COMPLETED }
        assertEquals("new text", note(id)!!.transcriptionText)
    }

    @Test
    fun cancellingARunningTranscriptionChangesNothing() {
        val id = insert(speechWav(), text = null, status = TranscriptionStatus.SKIPPED)
        val started = CountDownLatch(1)
        val engine = FakeEngine { _, cancel ->
            started.countDown()
            val aborted = CountDownLatch(1)
            cancel.setOnCancel { aborted.countDown() }
            aborted.await(5, TimeUnit.SECONDS)
            throw TranscriptionException(TranscriptionException.Kind.CANCELLED, "Cancelled.")
        }
        val m = manager(engine)
        m.enqueue(id)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(m.jobs.value[id] is TranscriptionJobState.Running)
        m.cancel(id)
        waitFor { m.jobs.value.isEmpty() }
        Thread.sleep(100)
        assertEquals(TranscriptionStatus.SKIPPED, note(id)!!.transcriptionStatus)
        assertNull(note(id)!!.transcriptionText)
    }

    @Test
    fun queuedItemsCanBeCancelledAndDuplicatesAreIgnored() {
        val first = insert(speechWav("a.wav"))
        val second = insert(speechWav("b.wav"))
        val gate = CountDownLatch(1)
        val engine = FakeEngine { _, _ -> gate.await(5, TimeUnit.SECONDS); TranscriptResult("x", "it", "m", 1) }
        val m = manager(engine)
        m.enqueue(first)
        m.enqueue(first)
        m.enqueue(second)
        m.cancel(second)
        assertFalse(m.jobs.value.containsKey(second))
        gate.countDown()
        waitFor { note(first)?.transcriptionStatus == TranscriptionStatus.COMPLETED }
        Thread.sleep(200)
        assertEquals(1, engine.calls.get())
        assertEquals(TranscriptionStatus.SKIPPED, note(second)!!.transcriptionStatus)
    }

    @Test
    fun missingRecordingFailsWithoutTouchingTheMemo() {
        val file = speechWav()
        val id = insert(file)
        file.delete()
        val m = manager(FakeEngine { _, _ -> TranscriptResult("x", "it", "m", 1) })
        m.enqueue(id)
        waitFor { m.jobs.value[id] is TranscriptionJobState.Failed }
        assertTrue((m.jobs.value[id] as TranscriptionJobState.Failed).message.startsWith("The recording could not be read"))
        assertEquals(file.absolutePath, note(id)!!.audioFilePath)
    }

    @Test
    fun outOfMemoryIsReportedAndTheModelIsReleased() {
        val id = insert(speechWav())
        val engine = FakeEngine { _, _ -> throw OutOfMemoryError() }
        val m = manager(engine)
        m.enqueue(id)
        waitFor { m.jobs.value[id] is TranscriptionJobState.Failed }
        assertTrue((m.jobs.value[id] as TranscriptionJobState.Failed).message.startsWith("Not enough memory to transcribe this memo."))
        assertTrue(engine.releases.get() >= 1)
    }

    @Test
    fun modelIsUnloadedAfterIdleTime() {
        val id = insert(speechWav())
        val engine = FakeEngine { _, _ -> TranscriptResult("x", "it", "m", 1) }
        manager(engine, idleUnloadMs = 100).enqueue(id)
        waitFor { engine.releases.get() == 1 }
    }

    @Test
    fun nativeErrorsFailTheMemoAndTheQueueKeepsWorking() {
        // Root cause of "Transcribing…/Waiting…" forever: Errors (not Exceptions) escaped and stopped the queue
        val first = insert(speechWav("a.wav"))
        val second = insert(speechWav("b.wav"))
        var calls = 0
        val engine = FakeEngine { _, _ ->
            calls++
            if (calls == 1) throw UnsatisfiedLinkError("No implementation found for init")
            TranscriptResult("second ok", "en", "m", 1)
        }
        val m = manager(engine)
        m.enqueue(first)
        m.enqueue(second)
        try {
            waitFor { note(second)?.transcriptionStatus == TranscriptionStatus.COMPLETED }
        } catch (e: AssertionError) {
            throw AssertionError("jobs=${m.jobs.value} first=${note(first)} second=${note(second)} calls=$calls", e)
        }
        assertEquals(TranscriptionStatus.FAILED, note(first)!!.transcriptionStatus)
        assertTrue((m.jobs.value[first] as TranscriptionJobState.Failed).message.contains("UnsatisfiedLinkError"))
        assertEquals("second ok", note(second)!!.transcriptionText)
    }

    @Test
    fun aRunThatNeverEndsIsStoppedByTheWatchdogWithAnExplanation() {
        val id = insert(speechWav())
        val m = manager(blockingEngine(), timeoutMs = 300)
        m.enqueue(id)
        waitFor { m.jobs.value[id] is TranscriptionJobState.Failed }
        assertTrue((m.jobs.value[id] as TranscriptionJobState.Failed).message.contains("took too long"))
        assertEquals(TranscriptionStatus.FAILED, note(id)!!.transcriptionStatus)
    }

    @Test
    fun queuedStateIsStoredAndResumedAfterRestart() {
        val id = insert(speechWav())
        // The first "process" hangs in the engine and never writes again (as if it had been killed)
        val first = manager(blockingEngine(maxMs = 10_000))
        first.enqueue(id)
        waitFor { note(id)?.transcriptionStatus == TranscriptionStatus.PENDING }
        // "Process death": a new manager (own worker, like a new process) finds the memo still queued and finishes it
        val secondExecutor = Executors.newSingleThreadExecutor()
        try {
            val restarted = manager(FakeEngine { _, _ -> TranscriptResult("after restart", "en", "m", 1) }, worker = secondExecutor.asCoroutineDispatcher())
            restarted.resumePending()
            waitFor { note(id)?.transcriptionStatus == TranscriptionStatus.COMPLETED }
            assertEquals("after restart", note(id)!!.transcriptionText)
        } finally {
            first.cancel(id) // release the hung test engine; a cancel never changes a completed memo
            secondExecutor.shutdownNow()
        }
        waitFor { first.jobs.value.isEmpty() }
        assertEquals("after restart", note(id)!!.transcriptionText)
        assertEquals(TranscriptionStatus.COMPLETED, note(id)!!.transcriptionStatus)
    }

    @Test
    fun retryRightAfterCancelIsNotLost() {
        val id = insert(speechWav())
        val started = CountDownLatch(1)
        var calls = 0
        val engine = FakeEngine { _, cancel ->
            calls++
            if (calls == 1) {
                started.countDown()
                val aborted = CountDownLatch(1)
                cancel.setOnCancel { aborted.countDown() }
                aborted.await(5, TimeUnit.SECONDS)
                Thread.sleep(100) // the old run is still finishing while the new request arrives
                throw TranscriptionException(TranscriptionException.Kind.CANCELLED, "Cancelled.")
            }
            TranscriptResult("second attempt", "en", "m", 1)
        }
        val m = manager(engine)
        m.enqueue(id)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        m.cancel(id)
        m.enqueue(id)
        waitFor { note(id)?.transcriptionText == "second attempt" }
        assertEquals(TranscriptionStatus.COMPLETED, note(id)!!.transcriptionStatus)
    }

    @Test
    fun progressIsReportedWhileRunning() {
        val id = insert(speechWav())
        val engine = blockingEngine(maxMs = 3000).apply { reportedProgress = 42 }
        val m = manager(engine)
        m.enqueue(id)
        waitFor { (m.jobs.value[id] as? TranscriptionJobState.Running)?.progress == 42 }
        m.cancel(id)
        waitFor { m.jobs.value.isEmpty() }
    }

    @Test
    fun threadCountLeavesRoomForMonitoring() {
        assertEquals(2, Transcription.threadCount(monitoring = true, cores = 8))
        assertEquals(4, Transcription.threadCount(monitoring = false, cores = 8))
        assertEquals(1, Transcription.threadCount(monitoring = false, cores = 1))
        assertEquals(1, Transcription.threadCount(monitoring = true, cores = 1))
    }

    // --- Text and audio helpers ---

    @Test
    fun cleanupRemovesNonSpeechAnnotationsOnly() {
        assertEquals("Buy milk", TranscriptText.clean(" [Music] Buy  milk (applause) ♪ "))
        assertEquals("", TranscriptText.clean("[BLANK_AUDIO]"))
        assertFalse(TranscriptText.hasAudibleContent(FloatArray(16000)))
        assertTrue(TranscriptText.hasAudibleContent(FloatArray(16000) { if (it in 8000..8800) 0.1f else 0f }))
    }

    @Test
    fun wavReaderReturnsTheSamplesWavWriterStored() {
        val file = speechWav()
        val audio = WavReader.read16kMono(file)
        assertEquals(32000, audio.samples.size)
        assertEquals((sin(5 * 0.05) * 8000).toInt() / 32768f, audio.samples[5], 1e-4f)
        // 44.1 kHz stereo is converted
        val stereo = File(app.filesDir, "s.wav")
        val n = 44100
        val bb = ByteBuffer.allocate(44 + n * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + n * 4).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(2).putInt(44100).putInt(44100 * 4).putShort(4).putShort(16).put("data".toByteArray()).putInt(n * 4)
        repeat(n) { bb.putShort(1000).putShort(3000) }
        stereo.writeBytes(bb.array())
        val converted = WavReader.read16kMono(stereo)
        assertEquals(16000, converted.samples.size)
        assertEquals(2000 / 32768f, converted.samples[100], 1e-4f)
        val corrupt = File(app.filesDir, "c.wav").apply { writeBytes(ByteArray(100) { 1 }) }
        try { WavReader.read16kMono(corrupt); throw AssertionError("accepted a corrupt file") } catch (e: IOException) {}
    }

    // --- Model store ---

    private fun store() = SpeechModelStore(app, app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE))

    /** Smallest file with a valid whisper "tiny" header (not loadable, which the fake loader decides). */
    private fun fakeGgml(seed: Byte): ByteArray {
        val b = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x67676d6c)
        intArrayOf(51865, 1500, 384, 6, 4, 448, 384, 6, 4, 80, 1).forEach { b.putInt(it) }
        while (b.hasRemaining()) b.put(seed)
        return b.array()
    }

    @Test
    fun withoutAModelTranscriptionFailsAtOnceWithTheDownloadHint() {
        val s = store()
        assertNull(s.activeModelOrNull())
        assertTrue(runCatching { s.activeModel() }.exceptionOrNull() is SpeechModelMissingException)
        // The manager ends the job at once with an actionable state; after the download a retry works
        val id = insert(speechWav())
        var installed = false
        val engine = FakeEngine { _, _ ->
            if (!installed) throw TranscriptionException(TranscriptionException.Kind.MODEL_MISSING, SpeechModelMissingException.MISSING_MESSAGE)
            TranscriptResult("now it works", "en", "m", 1)
        }
        val m = manager(engine)
        m.enqueue(id)
        waitFor { m.jobs.value[id] is TranscriptionJobState.Failed }
        val failed = m.jobs.value[id] as TranscriptionJobState.Failed
        assertTrue(failed.modelMissing)
        assertTrue(failed.message.contains("Download Whisper base"))
        assertEquals(TranscriptionStatus.FAILED, note(id)!!.transcriptionStatus)
        installed = true
        m.enqueue(id)
        waitFor { note(id)?.transcriptionStatus == TranscriptionStatus.COMPLETED }
        assertEquals("now it works", note(id)!!.transcriptionText)
    }

    @Test
    fun aDownloadedModelIsUsedAfterRegistrationAndRemovalKeepsEverythingElse() {
        val s = store()
        val dir = s.downloadDirectory.apply { mkdirs() }
        val file = File(dir, ModelSource.WHISPER_BASE.fileName).apply { writeBytes(fakeBase()) }
        assertNull("not registered yet (e.g. a download that is still being checked)", s.downloadedModel())
        s.registerDownload(file, SpeechModelStore.sha256(file))
        assertEquals(ModelSource.WHISPER_BASE.id, s.activeModel().id)
        assertTrue(s.activeModel().downloaded)
        // A truncated file is never used
        file.writeBytes(fakeBase().copyOf(100))
        assertNull(s.downloadedModel())
        file.writeBytes(fakeBase())
        assertEquals(ModelSource.WHISPER_BASE.id, s.activeModel().id)
        // Removing it leaves imported models and all other data alone
        val imported = s.import(fakeGgml(4).inputStream(), "My tiny", 4096) { true } as SpeechModelStore.ImportResult.Imported
        val unrelated = File(app.filesDir, "memos/keep.wav").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        assertTrue(s.removeDownloaded())
        assertNull(s.downloadedModel())
        assertEquals(imported.model.id, s.activeModel().id)
        assertTrue(unrelated.exists())
    }

    @Test
    fun importValidatesBeforeActivationAndNeverReplacesTheWorkingModel() {
        val s = store()
        assertTrue(s.import("not a model".byteInputStream(), "x", 11) { true } is SpeechModelStore.ImportResult.Rejected)
        assertTrue(s.import(fakeGgml(1).inputStream(), "broken", 4096) { false } is SpeechModelStore.ImportResult.Rejected)
        assertTrue(s.importedModels().isEmpty())
        assertNull(s.activeModelOrNull())
        // No partial files are left behind
        assertTrue(File(app.noBackupFilesDir, "${SpeechModelStore.DIRECTORY_NAME}/imported").listFiles()!!.isEmpty())

        val ok = s.import(fakeGgml(2).inputStream(), "My tiny", 4096) { true } as SpeechModelStore.ImportResult.Imported
        assertEquals("tiny", ok.model.info.sizeName)
        assertTrue(s.import(fakeGgml(2).inputStream(), "again", 4096) { true } is SpeechModelStore.ImportResult.Rejected)
        assertEquals(listOf("My tiny"), s.importedModels().map { it.displayName })
        s.activeModelId = ok.model.id
        assertEquals(ok.model.id, s.activeModel().id)
        assertTrue(s.delete(ok.model.id))
        assertNull(s.activeModelOrNull())
    }

    /** A file with a Whisper base (multilingual) header. */
    private fun fakeBase(): ByteArray {
        val b = java.nio.ByteBuffer.allocate(4096).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x67676d6c)
        intArrayOf(51865, 1500, 512, 8, 6, 448, 512, 8, 6, 80, 8).forEach { b.putInt(it) }
        return b.array()
    }

    @Test
    fun headerParserRejectsOtherFiles() {
        val f = File(app.filesDir, "h.bin")
        f.writeBytes(fakeGgml(3))
        assertEquals("tiny", GgmlModelInfo.read(f)!!.sizeName)
        f.writeBytes(ByteArray(10))
        assertNull(GgmlModelInfo.read(f))
    }
}
