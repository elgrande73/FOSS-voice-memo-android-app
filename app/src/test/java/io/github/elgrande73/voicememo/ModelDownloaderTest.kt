package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.transcription.ModelDownloader
import io.github.elgrande73.voicememo.transcription.ModelSource
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.random.Random

/**
 * The model download against a local HTTP server: integrity check, resume after an interruption, cancellation,
 * storage check, and that nothing but a verified, loadable file becomes the model.
 */
class ModelDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: TestHttpServer
    private val requests: List<String?> get() = server.ranges
    private var payload = fakeModel(2_000_000)
    private var cutAfter: Int?
        get() = server.cutAfter
        set(value) { server.cutAfter = value }
    private var honorRange: Boolean
        get() = server.honorRange
        set(value) { server.honorRange = value }

    /** A file with a valid Whisper base (multilingual) ggml header followed by random bytes. */
    private fun fakeModel(size: Int): ByteArray {
        val header = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0x67676d6c)
        intArrayOf(51865, 1500, 512, 8, 6, 448, 512, 8, 6, 80, 8).forEach { header.putInt(it) }
        return header.array() + Random(1).nextBytes(size - 48)
    }

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun source(sha1: String = sha1(payload)) =
        ModelSource("test-model", "Test", "http://127.0.0.1:${server.port}/model.bin", sha1, payload.size.toLong(), "model.bin")

    @Before
    fun setUp() {
        server = TestHttpServer { payload }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun downloader(space: Long = Long.MAX_VALUE, allowHttp: Boolean = true) =
        ModelDownloader(opener = { url -> URL(url).openConnection() as HttpURLConnection }, usableSpace = { space }, allowHttp = allowHttp)

    private fun run(
        dir: File,
        src: ModelSource = source(),
        d: ModelDownloader = downloader(),
        cancelAt: Long = Long.MAX_VALUE,
        load: (File) -> Boolean = { true },
        progress: MutableList<Long> = mutableListOf()
    ): ModelDownloader.Result {
        var cancelled = false
        return d.download(src, dir, isCancelled = { cancelled }, onProgress = { done, _ -> progress += done; if (done >= cancelAt) cancelled = true },
            onStage = {}, verifyLoad = load)
    }

    @Test
    fun aCompleteDownloadIsVerifiedAndInstalled() {
        val dir = tmp.newFolder()
        val progress = mutableListOf<Long>()
        val result = run(dir, progress = progress)
        assertTrue(result.toString(), result is ModelDownloader.Result.Installed)
        assertArrayEquals(payload, File(dir, "model.bin").readBytes())
        assertFalse(File(dir, "model.bin.part").exists())
        assertEquals(progress.sorted(), progress)
        assertEquals(payload.size.toLong(), progress.last())
    }

    @Test
    fun aDamagedOrDifferentFileIsRejectedAndDiscarded() {
        val dir = tmp.newFolder()
        val result = run(dir, source(sha1 = "0".repeat(40)))
        assertTrue(result is ModelDownloader.Result.Failed && result.message.contains("checksum"))
        assertFalse(File(dir, "model.bin").exists())
        assertFalse("never resumed from", File(dir, "model.bin.part").exists())
    }

    @Test
    fun anInterruptedDownloadContinuesWhereItStopped() {
        val dir = tmp.newFolder()
        cutAfter = 100_000
        val first = run(dir)
        assertTrue(first.toString(), first is ModelDownloader.Result.Failed && (first as ModelDownloader.Result.Failed).retryable)
        assertEquals(100_000L, File(dir, "model.bin.part").length())
        assertFalse(File(dir, "model.bin").exists())
        cutAfter = null
        val second = run(dir)
        assertTrue(second.toString(), second is ModelDownloader.Result.Installed)
        assertArrayEquals(payload, File(dir, "model.bin").readBytes())
        assertEquals("bytes=100000-", requests.last())
    }

    @Test
    fun aServerWithoutRangeSupportStartsOver() {
        val dir = tmp.newFolder()
        File(dir, "model.bin.part").writeBytes(payload.copyOf(50_000))
        honorRange = false
        val result = run(dir)
        assertTrue(result.toString(), result is ModelDownloader.Result.Installed)
        assertArrayEquals(payload, File(dir, "model.bin").readBytes())
    }

    @Test
    fun cancellingKeepsThePartialFileButInstallsNothing() {
        val dir = tmp.newFolder()
        val result = run(dir, cancelAt = 1)
        assertEquals(ModelDownloader.Result.Cancelled, result)
        assertFalse(File(dir, "model.bin").exists())
        assertTrue(File(dir, "model.bin.part").length() in 1 until payload.size)
    }

    @Test
    fun notEnoughStorageIsReportedBeforeDownloading() {
        val dir = tmp.newFolder()
        val result = run(dir, d = downloader(space = 1_000_000))
        assertTrue(result is ModelDownloader.Result.Failed && result.message.startsWith("Not enough free storage"))
        assertFalse(File(dir, "model.bin.part").exists())
    }

    @Test
    fun aModelThatCannotBeLoadedNeverReplacesTheWorkingOne() {
        val dir = tmp.newFolder()
        val working = File(dir, "model.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val result = run(dir, load = { false })
        assertTrue(result is ModelDownloader.Result.Failed && result.message.contains("could not be loaded"))
        assertArrayEquals(byteArrayOf(1, 2, 3), working.readBytes())
        // Retrying validates the already complete file again (the server answers 416 for the range)
        assertTrue(run(dir) is ModelDownloader.Result.Installed)
        assertArrayEquals(payload, working.readBytes())
    }

    @Test
    fun onlyHttpsIsAllowed() {
        val dir = tmp.newFolder()
        val result = run(dir, d = downloader(allowHttp = false))
        assertTrue(result is ModelDownloader.Result.Failed)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun theOfficialSourceIsPinned() {
        val s = ModelSource.WHISPER_BASE
        assertTrue(s.url.startsWith("https://"))
        assertEquals(40, s.sha1.length)
        // whisper.cpp publishes this SHA-1 for ggml-base.bin in the pinned submodule's models/README.md
        val readme = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
            .resolve("third_party/whisper.cpp/models/README.md")
        if (readme.exists()) assertTrue(readme.readText().contains("| base                | 142 MiB | `${s.sha1}` |"))
    }
}
