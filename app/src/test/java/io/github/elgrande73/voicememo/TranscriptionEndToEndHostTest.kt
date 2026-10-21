package io.github.elgrande73.voicememo

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.audio.WavWriter
import io.github.elgrande73.voicememo.memo.AppDatabase
import io.github.elgrande73.voicememo.memo.VoiceNote
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import io.github.elgrande73.voicememo.transcription.SpeechModelStore
import io.github.elgrande73.voicememo.transcription.TranscriptionJobState
import io.github.elgrande73.voicememo.transcription.TranscriptionManager
import io.github.elgrande73.voicememo.transcription.TranscriptionQuality
import io.github.elgrande73.voicememo.transcription.TranscriptionSettings
import io.github.elgrande73.voicememo.transcription.TranscriptionStatus
import io.github.elgrande73.voicememo.transcription.WhisperNative
import io.github.elgrande73.voicememo.transcription.WhisperTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors

/**
 * End-to-end transcription with real inference: the app's TranscriptionManager, WhisperTranscriber, model store and
 * Room database, the JNI bridge and whisper.cpp with the bundled model, on the build machine (host build of the
 * native code; no network is used). Skipped unless scripts/host-whisper-jni/build.sh was run.
 * Not covered here: loading the model from the APK asset and the Android CPU-variant selection (device only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptionEndToEndHostTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val hostLib = File(root, "build/host-whisper-jni/lib/libvmwhisper.so")
    private val modelFile = File(root, "app/src/main/assets/models/ggml-base-q5_0.bin")

    @Test
    fun memoIsTranscribedLocallyStoredAndStillThereAfterRestart() {
        assumeTrue("host library not built", hostLib.exists())
        WhisperNative.loadFrom(hostLib.absolutePath, File(root, "build/host-whisper-jni/bin").absolutePath)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        File(app.noBackupFilesDir, SpeechModelStore.DIRECTORY_NAME).deleteRecursively()
        val store = SpeechModelStore(app, prefs)
        // On the host the APK asset loader is unavailable: the same bundled model file is used directly
        val bundled = store.bundledModel()
        val engine = WhisperTranscriber(app, store) { bundled.copy(assetPath = null, file = modelFile) }
        assertTrue(engine.canLoad(modelFile))

        // Real audio: "Can you send me the file later" (synthetic speech fixture) with silence around it
        val speech = AudioFixtures.load("neg_speech_5")
        val frames = AudioFixtures.frames(ShortArray(8000) + speech + ShortArray(8000))
        val wav = File(app.filesDir, "e2e.wav").also { WavWriter.writeWavFile(it, frames, 16000, 1) }
        val corrupt = File(app.filesDir, "corrupt.wav").apply { writeBytes(ByteArray(2000) { 3 }) }
        val silent = File(app.filesDir, "silent.wav").also { WavWriter.writeWavFile(it, List(40) { ShortArray(800) }, 16000, 1) }

        val dbName = "e2e-transcription.db"
        app.deleteDatabase(dbName)
        var db = Room.databaseBuilder(app, AppDatabase::class.java, dbName).build()
        val ids = runBlocking {
            listOf(wav, corrupt, silent).map { f ->
                db.voiceNoteDao().insertVoiceNote(VoiceNote(title = f.name, durationSeconds = 3.0, audioFilePath = f.absolutePath, transcriptionStatus = TranscriptionStatus.SKIPPED)).toInt()
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        val manager = TranscriptionManager(
            dao = db.voiceNoteDao(), transcriberFactory = { engine },
            settings = { TranscriptionSettings(language = "en", quality = TranscriptionQuality.FAST, threads = 4) },
            scope = CoroutineScope(SupervisorJob()), worker = executor.asCoroutineDispatcher()
        )
        val start = System.nanoTime()
        ids.forEach { manager.enqueue(it) }
        val deadline = System.currentTimeMillis() + 120_000
        while (manager.jobs.value.values.any { it == TranscriptionJobState.Queued || it is TranscriptionJobState.Running } ||
            runBlocking { db.voiceNoteDao().getNoteById(ids[0])!!.transcriptionStatus } == TranscriptionStatus.PENDING) {
            check(System.currentTimeMillis() < deadline) { "transcription did not finish" }
            Thread.sleep(50)
        }
        val seconds = (System.nanoTime() - start) / 1e9
        val text = runBlocking { db.voiceNoteDao().getNoteById(ids[0])!!.transcriptionText }
        println("MEASURE e2e transcription (host, base q5_0, greedy, 3 memos incl. model load) %.2f s: '%s'".format(seconds, text))
        assertTrue(text!!.lowercase().contains("send") && text.lowercase().contains("file"))
        assertTrue((manager.jobs.value[ids[1]] as TranscriptionJobState.Failed).message.contains("could not be read"))
        assertEquals("", runBlocking { db.voiceNoteDao().getNoteById(ids[2])!!.transcriptionText })
        engine.release()
        executor.shutdown()

        // "Restart": a new database instance reads what was stored
        db.close()
        db = Room.databaseBuilder(app, AppDatabase::class.java, dbName).build()
        val reloaded = runBlocking { db.voiceNoteDao().getNoteById(ids[0])!! }
        assertEquals(TranscriptionStatus.COMPLETED, reloaded.transcriptionStatus)
        assertEquals(text, reloaded.transcriptionText)
        assertEquals(TranscriptionStatus.FAILED, runBlocking { db.voiceNoteDao().getNoteById(ids[1])!!.transcriptionStatus })
        db.close()
    }
}
