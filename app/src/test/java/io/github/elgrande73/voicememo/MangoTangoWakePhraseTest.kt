package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.AudioFixtures.Noise
import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.KeywordBehavior
import io.github.elgrande73.voicememo.detection.MemoDetectionEvent
import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import io.github.elgrande73.voicememo.detection.RecordingStopReason
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEnrollment
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Detection of the default wake phrase "Mango Tango" through the monitoring pipeline (template matching), on synthetic
 * speech (eSpeak NG fixtures, see app/src/test/resources/audio/README.md): the built-in reference on voices it was not
 * built from, a user who enrolled the phrase in their own voice, a custom phrase instead of it, and start and stop with
 * the same phrase. Results are printed with "MEASURE" and documented in docs/WAKE_PHRASE_DETECTION.md; assertions are
 * regression guards derived from the measured values, not accuracy targets.
 */
class MangoTangoWakePhraseTest {

    private val heldOutVoices = (1..10).map { "mango_eval_$it" }
    private val ownVoice = WakePhraseBenchmark.TARGET_EVAL
    private val nearMisses = WakePhraseBenchmark.NEAR_MISSES
    private val conversation = WakePhraseBenchmark.CONVERSATION
    private val conditions = listOf(-65.0 to Noise.PINK, -50.0 to Noise.PINK, -45.0 to Noise.WHITE)
    private val peaks = listOf(-12.0, -22.0)

    private fun enroll(names: List<String>): List<ByteArray> = names.map { name ->
        val recording = AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load(name), -12.0), 2.5, 0.3, -65.0, Noise.PINK, seed = name.hashCode())
        (WakePhraseEnrollment.validate(AudioFixtures.frames(recording)) as WakePhraseEnrollment.Result.Accepted).templateBytes
    }

    /** Clips (of [clips]) that would start a memo, over all conditions, and the number of trials. */
    private fun detections(clips: List<String>, templates: List<ByteArray>, thresholdPercent: Int = 50): Pair<Int, Int> {
        var hits = 0; var n = 0; var seed = 300
        for ((noiseDbfs, noise) in conditions) for (peak in peaks) for (clip in clips) {
            n++
            val offset = Random(seed).nextDouble(0.0, 0.05)
            val frames = AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load(clip), peak), 3.0, 0.5 + offset, noiseDbfs, noise, seed++))
            if (WakePhraseEvaluator.evaluate(frames, templates, thresholdPercent / 100f, WakePhraseBenchmark.Setup().minLevelDb).wouldTrigger) hits++
        }
        return hits to n
    }

    @Test
    fun builtInReferenceOnVoicesItWasNotBuiltFrom() {
        for (threshold in listOf(40, 50, 60)) {
            val (tp, positives) = detections(heldOutVoices, emptyList(), threshold)
            val (own, ownN) = detections(ownVoice, emptyList(), threshold)
            val (fpNear, nearN) = detections(nearMisses, emptyList(), threshold)
            val (fpConv, convN) = detections(conversation, emptyList(), threshold)
            println("MEASURE mango built-in @$threshold: held-out voices $tp/$positives, target speaker $own/$ownN, near misses $fpNear/$nearN, conversation $fpConv/$convN")
            if (threshold == 50) {
                assertTrue("recall regressed: $tp/$positives", tp >= MEASURED_BUILT_IN_TP_AT_50)
                assertEquals("conversation must not trigger at the default", 0, fpConv)
                assertTrue("near misses: $fpNear", fpNear <= MEASURED_BUILT_IN_NEAR_MISS_FP_AT_50)
            }
        }
    }

    @Test
    fun ownVoiceEnrollmentDetectsMangoTangoAndRejectsNearMisses() {
        for (samples in listOf(1, 3)) {
            val templates = WakePhraseBenchmark.templates(samples)
            val (tp, n) = detections(ownVoice, templates)
            val (fpNear, nearN) = detections(nearMisses, templates)
            val (fpConv, convN) = detections(conversation, templates)
            println("MEASURE mango enrolled samples=$samples @50: own voice $tp/$n, near misses $fpNear/$nearN, conversation $fpConv/$convN")
            assertTrue("own voice: $tp/$n", tp >= MEASURED_OWN_VOICE_TP.getValue(samples))
            assertEquals(0, fpConv)
            assertTrue("near misses: $fpNear/$nearN", fpNear <= MEASURED_OWN_VOICE_NEAR_MISS_FP.getValue(samples))
        }
    }

    @Test
    fun customPhraseReplacesMangoTango() {
        // The same user recorded "Open sesame" instead: that phrase is detected, Mango Tango no longer is
        val templates = enroll((1..3).map { "custom_enroll_$it" })
        val (custom, n) = detections((1..4).map { "custom_eval_$it" }, templates)
        val (mango, m) = detections(ownVoice + heldOutVoices, templates)
        val (conv, c) = detections(conversation, templates)
        println("MEASURE custom phrase ('Open sesame', 3 samples) @50: custom $custom/$n, Mango Tango $mango/$m, conversation $conv/$c")
        assertTrue("$custom/$n", custom >= MEASURED_CUSTOM_TP)
        assertEquals(0, mango)
        assertEquals(0, conv)
    }

    @Test
    fun theSamePhraseStartsAndStopsAMemoAndOnlyTheStopPhraseIsRemoved() {
        for ((label, templates) in listOf("built-in" to emptyList(), "3 samples" to WakePhraseBenchmark.templates(3))) {
            val rate = AudioFixtures.SAMPLE_RATE
            var completed = 0; var trials = 0; var seed = 900
            for ((noiseDbfs, noise) in conditions) for (clip in ownVoice) {
                trials++
                val phrase = AudioFixtures.atPeak(AudioFixtures.load(clip), -14.0)
                val memo = AudioFixtures.atPeak(AudioFixtures.load("neg_speech_4"), -14.0)
                fun bg(seconds: Double) = AudioFixtures.scene(ShortArray(0), seconds, 0.0, noiseDbfs, noise, seed++)
                fun withBg(s: ShortArray) = AudioFixtures.scene(s, s.size / rate.toDouble(), 0.0, noiseDbfs, noise, seed++)
                val stream = bg(1.5) + withBg(phrase) + bg(0.8) + withBg(memo) + bg(1.0) + withBg(phrase) + bg(1.0)
                val detector = MemoTriggerDetector(keywordDetector = TemplateKeywordDetector().apply { loadTemplates(templates) }).apply {
                    detectionMode = DetectionMode.KEYWORD_ONLY
                    keywordBehavior = KeywordBehavior.START_AND_STOP
                    silenceTimeoutMs = 20_000L
                    matchConfidenceThreshold = 0.5f
                    wakePhraseMinLevelDb = WakePhraseBenchmark.Setup().minLevelDb
                    reset(startMonitoring = true)
                }
                var started = 0
                var result: MemoDetectionEvent.RecordingCompleted? = null
                for (frame in AudioFixtures.frames(stream)) for (event in detector.processFrame(frame)) {
                    if (event is MemoDetectionEvent.TriggerStarted) started++
                    if (event is MemoDetectionEvent.RecordingCompleted) result = event
                }
                if (started == 1 && result?.stopReason == RecordingStopReason.KEYWORD) {
                    completed++
                    // The memo speech is kept; only the stop phrase and the 1 s pause after it (20 frames) are removed
                    val memoMs = memo.size * 1000L / rate
                    assertTrue("memo ${result.durationMs} ms vs speech $memoMs ms", result.durationMs >= memoMs)
                    assertTrue("removed ${result.removedStopPhraseFrames}", result.removedStopPhraseFrames in 1..(phrase.size / 800 + 20))
                }
            }
            println("MEASURE mango start+stop ($label): $completed/$trials memos started and stopped by the same phrase")
            assertTrue("$label: $completed/$trials", completed >= MEASURED_START_STOP.getValue(label))
        }
    }

    private companion object {
        // Measured on the fixtures (see docs/WAKE_PHRASE_DETECTION.md, "Default wake phrase: Mango Tango")
        const val MEASURED_BUILT_IN_TP_AT_50 = 16 // of 60
        const val MEASURED_BUILT_IN_NEAR_MISS_FP_AT_50 = 1 // of 54
        val MEASURED_OWN_VOICE_TP = mapOf(1 to 17, 3 to 22) // of 36
        val MEASURED_OWN_VOICE_NEAR_MISS_FP = mapOf(1 to 8, 3 to 8) // of 54
        const val MEASURED_CUSTOM_TP = 20 // of 24
        val MEASURED_START_STOP = mapOf("built-in" to 8, "3 samples" to 12) // of 18
    }
}
