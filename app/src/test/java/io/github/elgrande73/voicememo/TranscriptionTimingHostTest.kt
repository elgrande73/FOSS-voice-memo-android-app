package io.github.elgrande73.voicememo

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.audio.WavWriter
import io.github.elgrande73.voicememo.memo.AppDatabase
import io.github.elgrande73.voicememo.memo.VoiceNote
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import io.github.elgrande73.voicememo.transcription.GgmlModelInfo
import io.github.elgrande73.voicememo.transcription.SpeechModel
import io.github.elgrande73.voicememo.transcription.SpeechModelStore
import io.github.elgrande73.voicememo.transcription.TranscriptionJobState
import io.github.elgrande73.voicememo.transcription.TranscriptionManager
import io.github.elgrande73.voicememo.transcription.TranscriptionQuality
import io.github.elgrande73.voicememo.transcription.TranscriptionSettings
import io.github.elgrande73.voicememo.transcription.TranscriptionStatus
import io.github.elgrande73.voicememo.transcription.TranscriptionTimings
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
 * Stage timings of real transcriptions on the build machine (x86-64 host, Whisper base q5_0 test model): reading the
 * WAV, loading the model (cold run only), decoding and saving, through TranscriptionManager and WhisperTranscriber.
 * Printed with "MEASURE" and documented in docs/TRANSCRIPTION.md; phones are several times slower. Skipped unless
 * scripts/host-whisper-jni/build.sh and scripts/whisper-model/build-bundled-model.sh were run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptionTimingHostTest {

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val hostLib = File(root, "build/host-whisper-jni/lib/libvmwhisper.so")
    private val modelFile = WhisperNativeHostTest.model

    @Test
    fun stageTimingsColdAndWarm() {
        assumeTrue("host library not built", hostLib.exists())
        assumeTrue("test model not built", modelFile.exists())
        WhisperNative.loadFrom(hostLib.absolutePath, File(root, "build/host-whisper-jni/bin").absolutePath)
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val model = SpeechModel("host-test", "Whisper base q5_0", modelFile, modelFile.length(), "", false, GgmlModelInfo.read(modelFile)!!)

        // Memos of 4 s, 12 s and 35 s (synthetic sentences with short pauses)
        val sentences = listOf("neg_speech_4", "neg_speech_5", "test_conv_1", "test_conv_3", "dev_conv_6")
        fun memo(name: String, seconds: Int): File {
            val pcm = ArrayList<Short>()
            var i = 0
            while (pcm.size < seconds * 16000) {
                AudioFixtures.load(sentences[i++ % sentences.size]).forEach { pcm += it }
                repeat(8000) { pcm += 0 }
            }
            val frames = AudioFixtures.frames(pcm.take(seconds * 16000).toShortArray())
            return File(app.filesDir, name).also { WavWriter.writeWavFile(it, frames, 16000, 1) }
        }
        val files = listOf(memo("t4.wav", 4), memo("t12.wav", 12), memo("t35.wav", 35))

        for (threads in listOf(4, 2)) {
            File(app.noBackupFilesDir, SpeechModelStore.DIRECTORY_NAME).deleteRecursively()
            val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
            val engine = WhisperTranscriber(app, SpeechModelStore(app, prefs)) { model }
            val executor = Executors.newSingleThreadExecutor()
            val manager = TranscriptionManager(
                dao = db.voiceNoteDao(), transcriberFactory = { engine },
                settings = { TranscriptionSettings(language = "en", quality = TranscriptionQuality.FAST, threads = threads) },
                scope = CoroutineScope(SupervisorJob()), worker = executor.asCoroutineDispatcher()
            )
            // Cold: the first memo loads the model; then each memo again warm
            val runs = mutableListOf<Pair<String, TranscriptionTimings>>()
            for ((index, file) in (files + files).withIndex()) {
                val id = runBlocking {
                    db.voiceNoteDao().insertVoiceNote(VoiceNote(title = file.name, durationSeconds = 0.0, audioFilePath = file.absolutePath, transcriptionStatus = TranscriptionStatus.SKIPPED)).toInt()
                }
                manager.enqueue(id)
                val deadline = System.currentTimeMillis() + 180_000
                while (manager.jobs.value[id] !is TranscriptionJobState.Completed) {
                    check(manager.jobs.value[id] !is TranscriptionJobState.Failed) { "failed: ${manager.jobs.value[id]}" }
                    check(System.currentTimeMillis() < deadline) { "did not finish" }
                    Thread.sleep(20)
                }
                val t = manager.lastTimings.value!!
                val label = (if (index == 0) "cold" else "warm") + " ${file.name}"
                runs += label to t
                println(
                    "MEASURE stages threads=$threads $label: audio %.1f s | prepare %d ms, load %d ms, transcribe %d ms, save %d ms | total %d ms, RTF %.3f"
                        .format(t.audioMs / 1000.0, t.prepareAudioMs, t.loadModelMs, t.transcribeMs, t.saveMs, t.totalMs, t.realTimeFactor)
                )
                assertEquals(TranscriptionStatus.COMPLETED, runBlocking { db.voiceNoteDao().getNoteById(id)!!.transcriptionStatus })
            }
            assertTrue("cold run loads the model", runs[0].second.loadModelMs > 0)
            assertTrue("warm runs do not", runs.drop(1).all { it.second.loadModelMs < 50 })
            engine.release()
            executor.shutdown()
            db.close()
        }
        println("MEASURE host cores: ${Runtime.getRuntime().availableProcessors()}")
    }
}
