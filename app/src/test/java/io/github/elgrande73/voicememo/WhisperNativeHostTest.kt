package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.transcription.GgmlModelInfo
import io.github.elgrande73.voicememo.transcription.WhisperNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs the real JNI bridge and whisper.cpp with Whisper base (q5_0 test model) on the build machine (x86-64 host build, not a
 * phone). Skipped unless the host library was built: scripts/host-whisper-jni/build.sh. Measurements are printed with
 * "MEASURE" and are host numbers only.
 */
// Robolectric only so that it shares the sandbox class loader (and the loaded native library) with
// TranscriptionEndToEndHostTest: a JVM cannot load one native library in two class loaders
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class WhisperNativeHostTest {

    companion object {
        private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        private val hostLib = File(root, "build/host-whisper-jni/lib/libvmwhisper.so")
        private val backends = File(root, "build/host-whisper-jni/bin")
        /** Whisper base q5_0 built by scripts/whisper-model/build-bundled-model.sh (not part of the app). */
        val model = File(root, "build/whisper-model/ggml-base-q5_0.bin")
        private var handle = 0L

        @BeforeClass
        @JvmStatic
        fun load() {
            if (!hostLib.exists() || !model.exists()) return
            WhisperNative.loadFrom(hostLib.absolutePath, backends.absolutePath)
            val start = System.nanoTime()
            handle = WhisperNative.init(model.absolutePath)
            println("MEASURE whisper base q5_0 load %.0f ms (host)".format((System.nanoTime() - start) / 1e6))
        }
    }

    private fun samples(clip: String, silenceSeconds: Double = 0.5): FloatArray {
        val pcm = AudioFixtures.load(clip)
        val pad = (silenceSeconds * 16000).toInt()
        return FloatArray(pcm.size + 2 * pad) { i -> if (i - pad in pcm.indices) pcm[i - pad] / 32768f else 0f }
    }

    private fun words(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }

    @Test
    fun testModelHeaderIsWhisperBaseMultilingual() {
        assumeTrue("test model not built", model.exists())
        val info = GgmlModelInfo.read(model)!!
        assertEquals("base", info.sizeName)
        assertTrue(info.multilingual)
        assertNull(GgmlModelInfo.read(File(root, "app/src/test/resources/audio/neg_speech_1.pcm")))
    }

    @Test
    fun transcribesEnglishAndItalianSpeechOnDevice() {
        assumeTrue("host library not built", handle != 0L)
        val cases = listOf(
            Triple("neg_speech_4", "en", "Let us meet at the station at nine"),
            Triple("neg_speech_5", "en", "Can you send me the file later"),
            Triple("neg_speech_1", "it", "Domani devo chiamare il dottore"),
            Triple("neg_speech_3", "it", "Ricordati di comprare il pane e le uova")
        )
        var errors = 0
        var total = 0
        for ((clip, lang, reference) in cases) {
            val audio = samples(clip)
            val start = System.nanoTime()
            val result = WhisperNative.transcribe(handle, audio, lang, 4, 5)!!
            val ms = (System.nanoTime() - start) / 1e6
            val ref = words(reference)
            val hyp = words(result[1])
            val e = editDistance(ref, hyp)
            errors += e
            total += ref.size
            println("MEASURE stt $clip [$lang] ${"%.0f".format(ms)} ms for ${"%.1f".format(audio.size / 16000.0)} s, errors $e/${ref.size}: ${result[1].trim()}")
        }
        println("MEASURE stt WER %.3f (synthetic eSpeak speech, host)".format(errors.toDouble() / total))
        // Regression guard on synthetic speech: measured 0.571 (English 2/15 errors; eSpeak's Italian voice is
        // recognised poorly). Fails if the native integration breaks or quality drops sharply.
        assertTrue("WER ${errors.toDouble() / total}", errors.toDouble() / total <= 0.7)
        assertTrue("English must be recognised", words(WhisperNative.transcribe(handle, samples("neg_speech_5"), "en", 4, 5)!![1]).containsAll(listOf("send", "file", "later")))
    }

    @Test
    fun detectsTheLanguageWhenAskedTo() {
        assumeTrue("host library not built", handle != 0L)
        val result = WhisperNative.transcribe(handle, samples("neg_speech_4"), "auto", 4, 1)!!
        assertEquals("en", result[0])
    }

    @Test
    fun silenceGivesNoText() {
        assumeTrue("host library not built", handle != 0L)
        val result = WhisperNative.transcribe(handle, FloatArray(16000 * 3), "it", 4, 1)
        println("MEASURE stt silence -> '${result?.get(1)}' (the app skips silent memos before the model runs)")
        assertTrue(result == null || io.github.elgrande73.voicememo.transcription.TranscriptText.clean(result[1]).length < 40)
    }

    @Test
    fun abortStopsALongTranscriptionQuickly() {
        assumeTrue("host library not built", handle != 0L)
        val clip = samples("neg_speech_3", 0.2)
        val long = FloatArray(clip.size * 12) { clip[it % clip.size] } // ~40 s
        var result: Array<String>? = arrayOf("not finished")
        var elapsed = 0.0
        val worker = thread {
            val start = System.nanoTime()
            result = WhisperNative.transcribe(handle, long, "it", 2, 1)
            elapsed = (System.nanoTime() - start) / 1e6
        }
        Thread.sleep(300)
        WhisperNative.requestAbort(handle)
        worker.join(20_000)
        println("MEASURE stt abort returned after %.0f ms".format(elapsed))
        assertNull(result)
    }

    @Test
    fun invalidModelFileIsRejected() {
        assumeTrue("host library not built", handle != 0L)
        val bad = File.createTempFile("bad", ".bin").apply { writeBytes(ByteArray(4096) { 7 }) }
        assertEquals(0L, WhisperNative.init(bad.absolutePath))
        bad.delete()
    }

    @Test
    fun languageTableComesFromTheEngineAndFixedLanguagesAreHonoured() {
        assumeTrue("host library not built", handle != 0L)
        val flat = WhisperNative.languageTable()
        val native = List(flat.size / 2) { flat[2 * it] to flat[2 * it + 1] }
        assertEquals(100, native.size)
        assertEquals("the built-in copy equals the engine's table", native.map { it.first }, io.github.elgrande73.voicememo.transcription.WhisperLanguages.all().map { it.code })
        assertTrue(native.contains("hy" to "armenian"))
        // A fixed language is used as given (no detection); English and a non-English language
        val en = WhisperNative.transcribe(handle, samples("neg_speech_5"), "en", 4, 1)!!
        assertEquals("en", en[0])
        val de = WhisperNative.transcribe(handle, samples("test_conv_6"), "de", 4, 1)!!
        assertEquals("de", de[0])
        println("MEASURE fixed languages: en '${en[1].trim()}', de '${de[1].trim()}'")
        assertTrue(words(de[1]).isNotEmpty())
        // Unknown codes and languages beyond the model's vocabulary are refused, never turned into a wrong token
        assertNull(WhisperNative.transcribe(handle, samples("neg_speech_5"), "xx", 4, 1))
        assertNull("Cantonese is large-v3 only", WhisperNative.transcribe(handle, samples("neg_speech_5"), "yue", 4, 1))
        // Automatic detection still works
        assertEquals("en", WhisperNative.transcribe(handle, samples("neg_speech_5"), "auto", 4, 1)!![0])
    }

    private fun editDistance(a: List<String>, b: List<String>): Int {
        val d = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 0..a.size) d[i][0] = i
        for (j in 0..b.size) d[0][j] = j
        for (i in 1..a.size) for (j in 1..b.size) d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.size][b.size]
    }
}
