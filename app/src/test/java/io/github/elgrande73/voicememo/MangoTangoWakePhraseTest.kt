package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.AudioFixtures.Noise
import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.KeywordBehavior
import io.github.elgrande73.voicememo.detection.MemoDetectionEvent
import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import io.github.elgrande73.voicememo.detection.RecordingStopReason
import io.github.elgrande73.voicememo.wakephrase.BuiltInWakePhrase
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEnrollment
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Detection of the default wake phrase "Mango Tango" through the monitoring pipeline, on synthetic speech (eSpeak NG
 * fixtures, see app/src/test/resources/audio/README.md): the built-in reference on voices it was not built from, a user
 * who enrolled the phrase in their own voice, start and stop with the same phrase, and a custom phrase instead.
 * Results are printed with "MEASURE" and documented in docs/WAKE_PHRASE_DETECTION.md; assertions are regression guards
 * derived from the measured values, not accuracy targets.
 */
class MangoTangoWakePhraseTest {

    private val heldOutVoices = (1..10).map { "mango_eval_$it" }
    private val nearMisses = (1..8).map { "mango_neg_$it" }
    private val conversation = WakePhraseBenchmark.CONVERSATION
    private val ownVoice = (1..4).map { "mango_target_$it" }
    private val conditions = listOf(-65.0 to Noise.PINK, -50.0 to Noise.PINK, -45.0 to Noise.WHITE)
    private val peaks = listOf(-12.0, -22.0)

    private fun enroll(names: List<String>): List<ByteArray> = names.map { name ->
        val recording = AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load(name), -12.0), 2.5, 0.3, -65.0, Noise.PINK, seed = name.hashCode())
        (WakePhraseEnrollment.validate(AudioFixtures.frames(recording)) as WakePhraseEnrollment.Result.Accepted).templateBytes
    }

    /** Clips (of [clips]) that would start a memo, over all conditions, and the number of trials. */
    private fun detections(
        clips: List<String>,
        templates: List<ByteArray>,
        phrase: BuiltInWakePhrase = BuiltInWakePhrase.MANGO_TANGO,
        thresholdPercent: Int = 50
    ): Pair<Int, Int> {
        var hits = 0; var n = 0; var seed = 300
        for ((noiseDbfs, noise) in conditions) for (peak in peaks) for (clip in clips) {
            n++
            val offset = Random(seed).nextDouble(0.0, 0.05)
            val frames = AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load(clip), peak), 3.0, 0.5 + offset, noiseDbfs, noise, seed++))
            if (WakePhraseEvaluator.evaluate(frames, templates, thresholdPercent / 100f, WakePhraseBenchmark.Setup().minLevelDb, builtInPhrase = phrase).wouldTrigger) hits++
        }
        return hits to n
    }

    @Test
    fun builtInReferenceOnVoicesItWasNotBuiltFrom() {
        for (threshold in listOf(40, 50, 60)) {
            val (tp, positives) = detections(heldOutVoices, emptyList(), thresholdPercent = threshold)
            val (fpNear, nearN) = detections(nearMisses, emptyList(), thresholdPercent = threshold)
            val (fpConv, convN) = detections(conversation, emptyList(), thresholdPercent = threshold)
            println("MEASURE mango built-in @$threshold: held-out voices $tp/$positives, near misses $fpNear/$nearN, conversation $fpConv/$convN")
            if (threshold == 50) {
                assertTrue("recall regressed: $tp/$positives", tp >= MEASURED_BUILT_IN_TP_AT_50)
                assertEquals("conversation must not trigger at the default", 0, fpConv)
                assertTrue("near misses: $fpNear", fpNear <= MEASURED_BUILT_IN_NEAR_MISS_FP_AT_50)
            }
        }
        // The previous built-in reference (Ciao Linda) never reacts to "Mango Tango"
        assertEquals(0, detections(heldOutVoices, emptyList(), BuiltInWakePhrase.CIAO_LINDA).first)
    }

    @Test
    fun ownVoiceEnrollmentDetectsMangoTangoAndRejectsNearMisses() {
        for (samples in listOf(1, 3)) {
            val templates = enroll((1..samples).map { "mango_enroll_$it" })
            val (tp, n) = detections(ownVoice, templates)
            val (fpNear, nearN) = detections(nearMisses, templates)
            val (fpConv, convN) = detections(conversation, templates)
            println("MEASURE mango enrolled samples=$samples @50: own voice $tp/$n, near misses $fpNear/$nearN, conversation $fpConv/$convN")
            assertTrue("own voice: $tp/$n", tp >= MEASURED_OWN_VOICE_TP.getValue(samples))
            assertEquals(0, fpConv)
            assertTrue("near misses: $fpNear/$nearN", fpNear <= nearN / 4)
        }
    }

    @Test
    fun customPhraseReplacesMangoTango() {
        // A user who recorded "Ciao Linda" (any custom phrase): that phrase is detected, Mango Tango no longer is
        val templates = WakePhraseBenchmark.templates(3)
        val (custom, n) = detections(WakePhraseBenchmark.TARGET_EVAL, templates)
        val (mango, m) = detections(heldOutVoices + ownVoice, templates)
        println("MEASURE custom phrase (Ciao Linda, 3 samples) @50: custom $custom/$n, Mango Tango $mango/$m")
        assertTrue("$custom/$n", custom >= MEASURED_CUSTOM_TP)
        assertEquals(0, mango)
    }

    @Test
    fun theSamePhraseStartsAndStopsAMemoAndOnlyTheStopPhraseIsRemoved() {
        val templates = enroll((1..3).map { "mango_enroll_$it" })
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
            val frames = AudioFixtures.frames(stream)
            for (frame in frames) for (event in detector.processFrame(frame)) {
                if (event is MemoDetectionEvent.TriggerStarted) started++
                if (event is MemoDetectionEvent.RecordingCompleted) result = event
            }
            if (result == null) result = detector.stopManualRecording(RecordingStopReason.USER)
            println("MEASURE mango start+stop $clip noise $noiseDbfs: starts=$started stop=${result?.stopReason} removed=${result?.removedStopPhraseFrames} durationMs=${result?.durationMs}")
            if (started == 1 && result?.stopReason == RecordingStopReason.KEYWORD) {
                completed++
                // The memo speech is kept; only the stop phrase and the 1 s pause after it (20 frames) are removed
                val memoMs = memo.size * 1000L / rate
                assertTrue("memo ${result.durationMs} ms vs speech $memoMs ms", result.durationMs >= memoMs)
                assertTrue("removed ${result.removedStopPhraseFrames}", result.removedStopPhraseFrames in 1..(phrase.size / 800 + 20))
            }
        }
        println("MEASURE mango start+stop with the same enrolled phrase: $completed/$trials memos started and stopped by the phrase")
        assertTrue("$completed/$trials", completed >= MEASURED_START_STOP)
    }

    private companion object {
        // Measured on the fixtures (see docs/WAKE_PHRASE_DETECTION.md, "Default wake phrase: Mango Tango")
        const val MEASURED_BUILT_IN_TP_AT_50 = 16 // of 60
        const val MEASURED_BUILT_IN_NEAR_MISS_FP_AT_50 = 0 // of 48
        val MEASURED_OWN_VOICE_TP = mapOf(1 to 17, 3 to 18) // of 24
        const val MEASURED_CUSTOM_TP = 25 // of 36
        const val MEASURED_START_STOP = 9 // of 12 (the misses never started: white noise at −45 dBFS)
    }
}
