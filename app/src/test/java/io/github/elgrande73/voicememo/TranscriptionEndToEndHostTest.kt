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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import io.github.elgrande73.voicememo.transcription.CancelToken
import io.github.elgrande73.voicememo.transcription.ModelDownloader
import io.github.elgrande73.voicememo.transcription.ModelSource
import io.github.elgrande73.voicememo.transcription.TranscriptionException
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors

/**
 * End-to-end transcription with real inference on the build machine: the model is "downloaded" by the app's
 * [ModelDownloader] from a local HTTP server (checksum, header check and test load with the real engine), registered in
 * the model store, and used by TranscriptionManager, WhisperTranscriber, the JNI bridge and whisper.cpp with a Room
 * database. Skipped unless scripts/host-whisper-jni/build.sh (engine) and scripts/whisper-model/build-bundled-model.sh
 * (Whisper base q5_0 test model) were run. Not covered: the real download source (not reachable here), the Android
 * CPU-variant selection and phone performance (device only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptionEndToEndHostTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val hostLib = File(root, "build/host-whisper-jni/lib/libvmwhisper.so")
    private val modelFile = WhisperNativeHostTest.model

    @Test
    fun memoIsTranscribedLocallyStoredAndStillThereAfterRestart() {
        assumeTrue("host library not built", hostLib.exists())
        assumeTrue("test model not built", modelFile.exists())
        WhisperNative.loadFrom(hostLib.absolutePath, File(root, "build/host-whisper-jni/bin").absolutePath)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        File(app.noBackupFilesDir, SpeechModelStore.DIRECTORY_NAME).deleteRecursively()
        val store = SpeechModelStore(app, prefs)
        assertNull("the app contains no model", store.activeModelOrNull())
        val engine = WhisperTranscriber(app, store)
        // Without a model, transcription fails at once with the download hint (no endless "waiting")
        val missing = runCatching { engine.transcribe(FloatArray(16000), TranscriptionSettings(), CancelToken()) }.exceptionOrNull()
        assertEquals(TranscriptionException.Kind.MODEL_MISSING, (missing as TranscriptionException).kind)

        // Download through the app's downloader from a local server (same file name as the real source)
        val bytes = modelFile.readBytes()
        val server = TestHttpServer { bytes }
        val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        val source = ModelSource.WHISPER_BASE.copy(url = "http://127.0.0.1:${server.port}/ggml-base.bin", sha1 = sha1)
        val downloadStart = System.nanoTime()
        val result = ModelDownloader(allowHttp = true).download(
            source, store.downloadDirectory, isCancelled = { false }, onProgress = { _, _ -> }, onStage = {},
            verifyLoad = { engine.canLoad(it) }
        ) as ModelDownloader.Result.Installed
        server.close()
        store.registerDownload(result.file, result.sha256)
        println("MEASURE local download + checksum + test load of %d MB: %.1f s (host)".format(modelFile.length() / 1_000_000, (System.nanoTime() - downloadStart) / 1e9))
        assertEquals(ModelSource.WHISPER_BASE.id, store.activeModel().id)

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
