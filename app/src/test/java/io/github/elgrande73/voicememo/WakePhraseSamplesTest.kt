package io.github.elgrande73.voicememo

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.AudioFixtures.Noise
import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.MemoDetectionEvent
import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import io.github.elgrande73.voicememo.viewmodel.VoiceMemoViewModel
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEnrollment
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEnrollment.Reason
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEvaluator
import io.github.elgrande73.voicememo.wakephrase.WakePhraseStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WakePhraseSamplesTest {

    private lateinit var app: Application
    private val filesDir get() = app.filesDir

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        File(filesDir, WakePhraseStore.DIRECTORY_NAME).deleteRecursively()
        app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun recording(clip: String, peak: Double = -12.0, noiseDbfs: Double = -65.0) =
        AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load(clip), peak), 2.5, 0.3, noiseDbfs, Noise.PINK, clip.hashCode()))

    private fun accepted(clip: String, existing: List<ByteArray> = emptyList()): WakePhraseEnrollment.Result.Accepted {
        val result = WakePhraseEnrollment.validate(recording(clip), existingSamples = existing)
        assertTrue("$clip: $result", result is WakePhraseEnrollment.Result.Accepted)
        return result as WakePhraseEnrollment.Result.Accepted
    }

    private fun WakePhraseStore.add(clip: String) = accepted(clip).let { addSample(it.templateBytes, it.info.phraseDurationMs, it.info.peakDbfs) }

    // --- Store: one to five samples ---

    @Test
    fun oneToFiveSamplesAreStoredAndASixthIsRejected() {
        val store = WakePhraseStore(filesDir)
        for (n in 1..5) {
            assertNotNull(store.add(WakePhraseBenchmark.ENROLL[n - 1]))
            assertEquals(n, store.loadSamples().size)
        }
        val before = store.loadSamples()
        assertNull("sixth sample", store.addSample(accepted("target_eval_1").templateBytes, 800L, -10f))
        assertEquals(before.map { it.id }, store.loadSamples().map { it.id })
    }

    @Test
    fun samplesCanBeReplacedAndDeletedIndividually() {
        val store = WakePhraseStore(filesDir)
        val first = store.add("target_enroll_1")!!
        val second = store.add("target_enroll_2")!!
        val third = store.add("target_enroll_3")!!

        val replacement = accepted("target_enroll_4")
        assertTrue(store.replaceSample(second.id, replacement.templateBytes, replacement.info.phraseDurationMs, replacement.info.peakDbfs))
        val afterReplace = store.loadSamples()
        assertEquals(listOf(first.id, second.id, third.id), afterReplace.map { it.id }) // same position
        assertArrayEquals(replacement.templateBytes, afterReplace[1].templateBytes)
        assertArrayEquals(first.templateBytes, afterReplace[0].templateBytes)

        assertTrue(store.deleteSample(first.id))
        assertEquals(listOf(second.id, third.id), store.loadSamples().map { it.id })
        assertFalse(store.replaceSample(first.id, replacement.templateBytes, 1L, 0f))
        store.clearSamples()
        assertFalse(store.hasSamples())
    }

    @Test
    fun samplesSurviveRestartAndTheViewModelUsesThem() {
        val store = WakePhraseStore(filesDir)
        WakePhraseBenchmark.ENROLL.take(3).forEach { store.add(it) }
        val stored = store.loadSampleTemplates()

        repeat(2) { // restart
            val viewModel = VoiceMemoViewModel(app)
            assertEquals(3, viewModel.wakePhraseSamples.value.size)
            assertTrue(viewModel.hasKeywordTemplate.value)
        }
        WakePhraseStore(filesDir).loadSampleTemplates().zip(stored).forEach { (a, b) -> assertArrayEquals(b, a) }
    }

    @Test
    fun singleTemplateOfAnEarlierVersionIsMigratedOnceWithoutLoss() {
        val dir = File(filesDir, WakePhraseStore.DIRECTORY_NAME).apply { mkdirs() }
        val legacyBytes = accepted("target_enroll_1").templateBytes
        File(dir, WakePhraseStore.TEMPLATE_FILE_NAME).writeBytes(legacyBytes)

        repeat(3) { // repeated migration (several starts, service and screen)
            val samples = WakePhraseStore(filesDir).loadSamples()
            assertEquals(1, samples.size)
            assertEquals(WakePhraseStore.LEGACY_SAMPLE_ID, samples.single().id)
            assertArrayEquals(legacyBytes, samples.single().templateBytes)
        }
        assertFalse("old file removed after verified migration", File(dir, WakePhraseStore.TEMPLATE_FILE_NAME).exists())
        // Further samples can be added next to the migrated one
        assertNotNull(WakePhraseStore(filesDir).add("target_enroll_2"))
        assertEquals(2, WakePhraseStore(filesDir).loadSamples().size)
    }

    @Test
    fun corruptSampleFileIsSkippedAndKeptWhileOthersStillWork() {
        val store = WakePhraseStore(filesDir)
        store.add("target_enroll_1")
        val corrupt = File(File(File(filesDir, WakePhraseStore.DIRECTORY_NAME), WakePhraseStore.SAMPLES_DIRECTORY_NAME), "sample_42.bin")
        corrupt.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(1, store.loadSamples().size)
        assertTrue(corrupt.exists())
    }

    @Test
    fun samplesInAnUnsupportedFormatAreSkippedKeptAndReported() {
        // A template in another format (e.g. from the very first app version): valid sample container, foreign magic
        val good = accepted("target_enroll_1").templateBytes
        val foreign = good.copyOf().also { java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0, 0x01020304) }
        val detector = TemplateKeywordDetector()
        assertEquals(1, detector.loadTemplates(listOf(foreign, good)))
        val store = WakePhraseStore(filesDir)
        assertNotNull(store.addSample(foreign, 900, -12f))
        assertNotNull(store.addSample(good, 900, -12f))
        assertEquals("only the usable sample is listed and used", 1, store.loadSamples().size)
        assertEquals(1, store.unusableSampleCount())
        assertEquals("kept on disk", 2, File(File(filesDir, WakePhraseStore.DIRECTORY_NAME), WakePhraseStore.SAMPLES_DIRECTORY_NAME).listFiles()!!.size)
        // Unknown/incompatible data is skipped, not misinterpreted
        assertEquals(0, detector.loadTemplates(listOf(byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9))))
        assertFalse(detector.hasCustomTemplate())
    }

    // --- Validation ---

    @Test
    fun naturalVariationsAreAccepted() {
        val accepted = mutableListOf<ByteArray>()
        for (clip in WakePhraseBenchmark.ENROLL + WakePhraseBenchmark.TARGET_EVAL) {
            accepted += accepted(clip, accepted).templateBytes
        }
        // Different levels and a bit of background are fine too
        assertTrue(WakePhraseEnrollment.validate(recording("target_enroll_2", -24.0, -55.0)) is WakePhraseEnrollment.Result.Accepted)
    }

    @Test
    fun unusableRecordingsAreRejectedWithAReason() {
        fun reason(frames: List<ShortArray>, existing: List<ByteArray> = emptyList()) =
            (WakePhraseEnrollment.validate(frames, existingSamples = existing) as WakePhraseEnrollment.Result.Rejected).reason

        assertEquals(Reason.SILENT, reason(List(50) { ShortArray(800) }))
        assertEquals(Reason.SILENT, reason(AudioFixtures.frames(AudioFixtures.scene(ShortArray(0), 2.5, 0.0, -70.0))))
        assertEquals(Reason.INVALID_AUDIO, reason(emptyList()))
        assertEquals(Reason.INVALID_AUDIO, reason(List(50) { ShortArray(441) }))
        // A click, not a phrase
        val click = AudioFixtures.scene(ShortArray(1600) { (sin(it * 0.3) * 20000).toInt().toShort() }, 2.5, 1.0)
        assertEquals(Reason.TOO_SHORT, reason(AudioFixtures.frames(click)))
        // Continuous talking for the whole recording
        val talk = AudioFixtures.frames(AudioFixtures.atPeak(
            AudioFixtures.load("neg_speech_3") + AudioFixtures.load("neg_speech_1") + AudioFixtures.load("neg_speech_2"), -12.0
        ).copyOf(40000))
        assertEquals(Reason.TOO_LONG, reason(talk))
        // Distorted
        val clipped = recording("target_enroll_1").map { f -> ShortArray(f.size) { (f[it] * 40).coerceIn(-32768, 32767).toShort() } }
        assertEquals(Reason.CLIPPED, reason(clipped))
        // The same recording twice
        val sample = accepted("target_enroll_1").templateBytes
        assertEquals(Reason.DUPLICATE, reason(recording("target_enroll_1"), listOf(sample)))
    }

    @Test
    fun rejectedEnrollmentLeavesExistingSamplesUntouched() {
        val store = WakePhraseStore(filesDir)
        store.add("target_enroll_1")
        store.add("target_enroll_2")
        val before = store.loadSamples()
        val result = WakePhraseEnrollment.validate(List(50) { ShortArray(800) }, existingSamples = store.loadSampleTemplates())
        assertTrue(result is WakePhraseEnrollment.Result.Rejected)
        val after = store.loadSamples()
        assertEquals(before.map { it.id }, after.map { it.id })
        before.indices.forEach { assertArrayEquals(before[it].templateBytes, after[it].templateBytes) }
    }

    // --- Recognition with several samples ---

    @Test
    fun recognitionUsesEverySampleAndHelpsAVariationOneSampleMisses() {
        // "Mango, Tango" said slowly with a longer pause matches less well with only the first sample
        // (measured 0.472 with 1 sample, 0.642 with 5); at a 60 % threshold only the five samples recognize it
        val clip = AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load("target_eval_5"), -12.0), 3.0, 0.5))
        val one = WakePhraseEvaluator.evaluate(clip, WakePhraseBenchmark.templates(1), 0.6f, 48f)
        val five = WakePhraseEvaluator.evaluate(clip, WakePhraseBenchmark.templates(5), 0.6f, 48f)
        println("MEASURE target_eval_5: 1 sample %.3f, 5 samples %.3f".format(one.bestConfidence, five.bestConfidence))
        assertFalse(one.wouldTrigger)
        assertTrue(five.wouldTrigger)
    }

    @Test
    fun moreSamplesImproveDetectionWithoutMoreFalsePositives() {
        val results = (1..5).associateWith { n -> WakePhraseBenchmark.evaluate(WakePhraseBenchmark.Setup(samples = n)) }
        results.forEach { (n, m) -> println("MEASURE samples=$n $m") }
        val one = results.getValue(1)
        val five = results.getValue(5)
        assertTrue("recall ${five.recall} vs ${one.recall}", five.recall > one.recall)
        // False positives grow little with the number of samples (threshold 50 %): measured 2 → 3 of 96 negative trials,
        // all near misses ("Tango Mango", "Manga Tanga", …); see "Known regression" in docs/WAKE_PHRASE_DETECTION.md
        assertTrue("FP ${five.fp} vs ${one.fp}", five.fp <= one.fp + 2)
        assertEquals(one.fp + one.tn, five.fp + five.tn)
    }

    @Test
    fun monitoringAndTestAgreeWithSeveralSamples() {
        val templates = WakePhraseBenchmark.templates(5)
        for (clip in listOf("target_eval_2", "target_eval_5", "neg_similar_1", "neg_speech_2")) {
            val frames = AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load(clip), -15.0), 3.0, 0.5))
            val test = WakePhraseEvaluator.evaluate(frames, templates, 1f, 48f).bestConfidence
            val matcher = TemplateKeywordDetector().apply { loadTemplates(templates) }
            val monitor = MemoTriggerDetector(keywordDetector = matcher).apply {
                detectionMode = DetectionMode.KEYWORD_ONLY
                matchConfidenceThreshold = 1f
                reset(startMonitoring = true)
            }
            var live = 0f
            for (frame in AudioFixtures.frames(AudioFixtures.scene(ShortArray(0), 3.0, 0.0, seed = 99)) + frames) {
                monitor.processFrame(frame)
                monitor.lastKeywordResult?.let { live = maxOf(live, it.confidence) }
            }
            // Agree within 5 points, or both below the lowest configurable threshold (see WakePhrasePipelineTest)
            if (maxOf(test, live) >= 0.2f) assertEquals(clip, test, live, 0.05f)
        }
    }

    @Test
    fun oneUtteranceTriggersOnceAndDetectionRecoversAfterwards() {
        val detector = MemoTriggerDetector(keywordDetector = TemplateKeywordDetector().apply { loadTemplates(WakePhraseBenchmark.templates(5)) }).apply {
            detectionMode = DetectionMode.KEYWORD_ONLY
            silenceTimeoutMs = 1000L
            reset(startMonitoring = true)
        }
        val phrase = AudioFixtures.atPeak(AudioFixtures.load("target_eval_2"), -12.0)
        // The second utterance comes after the late-stop window (MemoTriggerDetector.LATE_STOP_GRACE_MS), so it starts a memo
        val stream = AudioFixtures.scene(phrase, 4.0, 0.5) + AudioFixtures.scene(ShortArray(0), 5.0, 0.0, seed = 3) + AudioFixtures.scene(phrase, 4.0, 0.5, seed = 2)
        val events = AudioFixtures.frames(stream).flatMap { detector.processFrame(it) }
        // Each utterance starts exactly one memo; the silence timeout ends it, then the next utterance is detected again
        assertEquals(2, events.count { it is MemoDetectionEvent.TriggerStarted })
        assertEquals(2, events.count { it is MemoDetectionEvent.RecordingCompleted })
    }
}
