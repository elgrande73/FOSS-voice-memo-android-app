package io.github.elgrande73.voicememo

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.transcription.ModelDownloadState
import io.github.elgrande73.voicememo.transcription.ModelDownloader
import io.github.elgrande73.voicememo.transcription.ModelDownloads
import io.github.elgrande73.voicememo.transcription.ModelSource
import io.github.elgrande73.voicememo.transcription.SpeechModelStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** The app-level download controller: states, a single download at a time, and that a bad file never becomes the model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelDownloadsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun aFileThatIsNotTheOfficialModelEndsInFailedAndIsNotInstalled() {
        File(app.noBackupFilesDir, SpeechModelStore.DIRECTORY_NAME).deleteRecursively()
        ModelDownloads.refresh(app)
        assertEquals(ModelDownloadState.Missing, ModelDownloads.state.value)
        TestHttpServer { ByteArray(4096) { 7 } }.use { server ->
            val downloader = ModelDownloader(opener = { URL("http://127.0.0.1:${server.port}/ggml-base.bin").openConnection() as HttpURLConnection }, allowHttp = true)
            assertTrue(ModelDownloads.run(app, downloader) { true })
        }
        val state = ModelDownloads.state.value
        assertTrue(state.toString(), state is ModelDownloadState.Failed && state.message.contains("checksum"))
        assertEquals(null, ModelDownloads.store(app).downloadedModel())
        assertFalse(File(ModelDownloads.store(app).downloadDirectory, ModelSource.WHISPER_BASE.fileName).exists())
    }

    @Test
    fun aSecondDownloadIsNotStartedWhileOneIsRunning() {
        val release = CountDownLatch(1)
        TestHttpServer { release.await(); ByteArray(4096) }.use { server ->
            val downloader = ModelDownloader(opener = { URL("http://127.0.0.1:${server.port}/ggml-base.bin").openConnection() as HttpURLConnection }, allowHttp = true)
            val first = thread { ModelDownloads.run(app, downloader) { true } }
            val deadline = System.currentTimeMillis() + 5000
            while (!ModelDownloads.isRunning && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue(ModelDownloads.isRunning)
            assertFalse("duplicate", ModelDownloads.run(app, downloader) { true })
            release.countDown()
            first.join(10_000)
        }
        assertFalse(ModelDownloads.isRunning)
    }
}
