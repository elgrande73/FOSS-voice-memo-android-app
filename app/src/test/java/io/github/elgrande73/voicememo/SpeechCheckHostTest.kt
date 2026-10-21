package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.AudioFixtures.Noise
import io.github.elgrande73.voicememo.transcription.SpeechCheckModel
import io.github.elgrande73.voicememo.transcription.WhisperNative
import io.github.elgrande73.voicememo.transcription.WhisperPhraseVerifier
import io.github.elgrande73.voicememo.wakephrase.BuiltInWakePhrase
import io.github.elgrande73.voicememo.wakephrase.SpeechCheckedKeywordDetector
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEvaluator
import io.github.elgrande73.voicememo.wakephrase.WakeWordEngines
import io.github.elgrande73.voicememo.wakephrase.WakeWordModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * The speech-checked backend with the real speech model (bundled Whisper base, x86-64 host build of the JNI bridge),
 * through the same evaluation path as the Settings phrase test and monitoring (WakePhraseEvaluator, synchronous
 * checks). Skipped unless scripts/host-whisper-jni/build.sh was run. Synthetic speech fixtures only; host CPU numbers,
 * not phone numbers. Results are printed with "MEASURE" and documented in docs/WAKE_PHRASE_DETECTION.md.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class SpeechCheckHostTest {

    companion object {
        private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        private val hostLib = File(root, "build/host-whisper-jni/lib/libvmwhisper.so")
        private var available = false

        @BeforeClass
        @JvmStatic
        fun load() {
            if (!hostLib.exists()) return
            WhisperNative.loadFrom(hostLib.absolutePath, File(root, "build/host-whisper-jni/bin").absolutePath)
            available = true
        }

        // Measured with the bundled model on the fixtures (docs/WAKE_PHRASE_DETECTION.md, section "Speech check")
        const val MEASURED_MANGO_HELD_OUT = 27 // of 60
        const val MEASURED_MANGO_NEAR_MISS = 9 // of 48
        const val MEASURED_CUSTOM_TARGET = 14 // of 54

        fun lease() = SpeechCheckModel.acquire { WhisperNative.init(WhisperNativeHostTest.model.absolutePath) }
    }

    private val conditions = listOf(-65.0 to Noise.PINK, -50.0 to Noise.PINK, -45.0 to Noise.WHITE)
    private val peaks = listOf(-12.0, -22.0)

    /** Clips that would start a memo with the speech check, over all conditions; and the number of trials. */
    private fun detections(clips: List<String>, phrase: BuiltInWakePhrase, samples: List<ByteArray> = emptyList(), text: String? = null, threshold: Int = 50): Pair<Int, Int> {
        var hits = 0; var n = 0; var seed = 300
        for ((noiseDbfs, noise) in conditions) for (peak in peaks) for (clip in clips) {
            n++
            val offset = Random(seed).nextDouble(0.0, 0.05)
            val frames = AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load(clip), peak), 3.0, 0.5 + offset, noiseDbfs, noise, seed++))
            val result = WakePhraseEvaluator.evaluate(
                frames, samples, threshold / 100f, WakePhraseBenchmark.Setup().minLevelDb, builtInPhrase = phrase,
                matcherFactory = {
                    WakeWordEngines.create(WakeWordModel.SPEECH_CHECK, phrase, samples, text, { t -> WhisperPhraseVerifier(lease(), t, threads = 4) }, synchronous = true).detector
                }
            )
            if (result.wouldTrigger) hits++
        }
        return hits to n
    }

    @Test
    fun mangoTangoWithoutRecordedSamples() {
        assumeTrue("host library not built", available)
        val (heldOut, n) = detections((1..10).map { "mango_eval_$it" }, BuiltInWakePhrase.MANGO_TANGO)
        val (own, m) = detections((1..4).map { "mango_target_$it" }, BuiltInWakePhrase.MANGO_TANGO)
        val (near, k) = detections((1..8).map { "mango_neg_$it" }, BuiltInWakePhrase.MANGO_TANGO)
        val (conv, c) = detections(WakePhraseBenchmark.CONVERSATION, BuiltInWakePhrase.MANGO_TANGO)
        println("MEASURE speech check Mango Tango @50: held-out voices $heldOut/$n, enrolled-user voice (not enrolled here) $own/$m, near misses $near/$k, conversation $conv/$c")
        // Regression guards from the measured values (see docs); Standard measured 16/60, 0/48, 0/42 on the same set
        assertTrue("$heldOut/$n", heldOut >= MEASURED_MANGO_HELD_OUT)
        assertEquals(0, conv)
        assertTrue("$near/$k", near <= MEASURED_MANGO_NEAR_MISS)
    }

    @Test
    fun customPhraseWithItsWords() {
        assumeTrue("host library not built", available)
        val samples = WakePhraseBenchmark.templates(3)
        val (target, n) = detections(WakePhraseBenchmark.TARGET_EVAL + WakePhraseBenchmark.OTHER_SPEAKERS, BuiltInWakePhrase.MANGO_TANGO, samples, "Ciao Linda")
        val (near, k) = detections(WakePhraseBenchmark.NEAR_MISSES, BuiltInWakePhrase.MANGO_TANGO, samples, "Ciao Linda")
        val (conv, c) = detections(WakePhraseBenchmark.CONVERSATION, BuiltInWakePhrase.MANGO_TANGO, samples, "Ciao Linda")
        val (mango, m) = detections((1..4).map { "mango_target_$it" }, BuiltInWakePhrase.MANGO_TANGO, samples, "Ciao Linda")
        println("MEASURE speech check custom phrase 'Ciao Linda' @50: target+other speakers $target/$n, near misses $near/$k, conversation $conv/$c, Mango Tango $mango/$m")
        assertTrue("$target/$n", target >= MEASURED_CUSTOM_TARGET)
        assertEquals(0, conv)
        assertEquals(0, mango)
    }

    @Test
    fun latencyCpuAndMemoryOfOneCheck() {
        assumeTrue("host library not built", available)
        fun rssKb() = File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }.filter { it.isDigit() }.toLong()
        val before = rssKb()
        val loadStart = System.nanoTime()
        val lease = lease()
        val loadMs = (System.nanoTime() - loadStart) / 1e6
        val verifier = WhisperPhraseVerifier(lease, "Mango Tango", threads = 2)
        val pcm = AudioFixtures.atPeak(AudioFixtures.load("mango_target_1"), -14.0)
        val snippet = FloatArray(pcm.size + 4800) { i -> if (i - 2400 in pcm.indices) pcm[i - 2400] / 32768f else 0f }
        verifier.score(snippet) // warm-up
        // Process CPU time (all threads, including whisper's) from /proc, in clock ticks of 10 ms
        fun cpuTicks() = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ').let { it[11].toLong() + it[12].toLong() }
        val cpu0 = cpuTicks()
        val t0 = System.nanoTime()
        repeat(10) { verifier.score(snippet) }
        val wallMs = (System.nanoTime() - t0) / 1e6 / 10
        val cpuMs = (cpuTicks() - cpu0) * 10.0 / 10
        val after = rssKb()
        println("MEASURE speech check (host x86-64, 2 threads): model load %.0f ms, RSS +%d MB, one check of %.1f s audio %.0f ms wall, %.0f ms CPU (all threads)"
            .format(loadMs, (after - before) / 1024, snippet.size / 16000.0, wallMs, cpuMs))
        verifier.close()
        assertTrue(wallMs < 2_000)
    }

    @Test
    fun theModelIsSharedAndFreedWhenTheLastUserReleasesIt() {
        assumeTrue("host library not built", available)
        var loads = 0
        val a = SpeechCheckModel.acquire { loads++; WhisperNative.init(WhisperNativeHostTest.model.absolutePath) }
        val b = SpeechCheckModel.acquire { loads++; WhisperNative.init(WhisperNativeHostTest.model.absolutePath) }
        assertEquals(1, loads)
        a.close(); a.close() // double release is harmless
        b.use { assertTrue(it != 0L) }
        b.close()
        SpeechCheckModel.acquire { loads++; WhisperNative.init(WhisperNativeHostTest.model.absolutePath) }.close()
        assertEquals("loaded again after the last release", 2, loads)
    }

    @Test
    fun aDetectorKeepsWorkingWhenSpeechChecksFail() {
        // A model that cannot be loaded: Standard is used, with the reason
        val engine = WakeWordEngines.create(WakeWordModel.SPEECH_CHECK, BuiltInWakePhrase.MANGO_TANGO, emptyList(), null,
            { t -> WhisperPhraseVerifier(SpeechCheckModel.acquire { 0L }, t) })
        assertEquals(WakeWordModel.STANDARD, engine.status.active)
        assertTrue(engine.status.message!!.contains("could not be loaded"))
        // Checks failing at run time (e.g. a native error) never crash monitoring: no match, counted as failures
        val failing = SpeechCheckedKeywordDetector(io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector(),
            object : io.github.elgrande73.voicememo.wakephrase.PhraseVerifier {
                override fun score(samples: FloatArray): Float = throw IllegalStateException("Speech check failed")
                override fun close() {}
            }, synchronous = true)
        val frames = AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load("mango_target_1"), -14.0), 3.0, 0.5))
        assertTrue((frames + List(8) { ShortArray(800) }).none { failing.processFrame(it, 0f, 0f).matched })
        assertEquals(1, failing.failures)
    }
}
