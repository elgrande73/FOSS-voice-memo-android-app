package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.AudioFixtures.Noise
import io.github.elgrande73.voicememo.audio.PcmFrameAssembler
import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.MemoDetectionEvent
import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import io.github.elgrande73.voicememo.settings.MatchConfidenceThreshold
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEvaluator
import io.github.elgrande73.voicememo.wakephrase.WakePhraseFrontEnd
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Regression tests for the monitoring / Settings-test discrepancy. The same fixture audio is fed to the live
 * monitoring pipeline ([MemoTriggerDetector] streaming) and to the Settings phrase test ([WakePhraseEvaluator]).
 *
 * Score tolerance: both paths run identical preprocessing and matching code; they differ only in the adaptive
 * noise-floor state (monitoring has listened to 3 s of background before the phrase, the test primes the estimator
 * with the recording itself). Measured differences on these fixtures are ≤ 0.002, so 0.05 (5 percentage points)
 * leaves room for other noise conditions without hiding a real pipeline mismatch (which showed differences of 0.3–0.6).
 *
 * Monitoring stops scoring when it triggers, so raw scores are compared with a monitoring run whose threshold is never
 * reached ([NO_TRIGGER]); trigger decisions are compared at the real thresholds.
 */
class WakePhrasePipelineTest {

    private val tolerance = 0.05f
    private val NO_TRIGGER = 1.0f
    private val defaultThreshold = MatchConfidenceThreshold.toDetectorConfidence(MatchConfidenceThreshold.DEFAULT_PERCENT)

    private data class MonitorResult(val bestScore: Float, val triggered: Boolean)

    /** Streams [frames] through monitoring after [warmUpSeconds] of background, like a long-running service. */
    private fun monitor(
        frames: List<ShortArray>,
        template: ByteArray?,
        threshold: Float = defaultThreshold,
        warmUpSeconds: Double = 3.0,
        noiseDbfs: Double = -65.0,
        noise: Noise = Noise.PINK,
        detector: MemoTriggerDetector? = null
    ): MonitorResult {
        val trigger = detector ?: MemoTriggerDetector(keywordDetector = TemplateKeywordDetector().apply {
            if (template != null) loadTemplate(template)
        })
        trigger.detectionMode = DetectionMode.KEYWORD_ONLY
        trigger.thresholdDb = 48f
        trigger.matchConfidenceThreshold = threshold
        if (detector == null) trigger.reset(startMonitoring = true)
        val warmUp = AudioFixtures.frames(AudioFixtures.scene(ShortArray(0), warmUpSeconds, 0.0, noiseDbfs, noise, seed = 99))
        var best = 0f
        var triggered = false
        for (frame in warmUp + frames) {
            val events = trigger.processFrame(frame)
            trigger.lastKeywordResult?.let { best = maxOf(best, it.confidence) }
            if (events.any { it is MemoDetectionEvent.TriggerStarted }) triggered = true
        }
        return MonitorResult(best, triggered)
    }

    private fun testRecording(name: String, peakDbfs: Double, noiseDbfs: Double = -65.0, noise: Noise = Noise.PINK, lead: Double = 0.5) =
        AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load(name), peakDbfs), 3.0, lead, noiseDbfs, noise, seed = 7))

    @Test
    fun originalDiscrepancy_monitoringNowScoresLikeTheSettingsTest() {
        val template = AudioFixtures.enrollTemplate()
        val frames = testRecording("ciao_linda_a", peakDbfs = -20.0)

        val test = WakePhraseEvaluator.evaluate(frames, template, defaultThreshold, 48f)
        val liveScore = monitor(frames, template, NO_TRIGGER).bestScore
        val live = monitor(frames, template)
        println("MEASURE discrepancy: test=%.3f monitoring=%.3f".format(test.bestConfidence, liveScore))

        // Before the fix, monitoring stayed at 0.00 (matcher received only loud fragments) while the test reached ~0.5
        assertTrue("Settings test must recognise the enrolled phrase", test.bestConfidence >= defaultThreshold)
        assertTrue("Monitoring must reach the same score", liveScore >= defaultThreshold)
        assertTrue(live.triggered && test.wouldTrigger)
        assertEquals(test.bestConfidence, liveScore, tolerance)
    }

    @Test
    fun sameAudioGivesEquivalentScoresAndDecisionsInBothPipelines() {
        for ((noiseDbfs, noise) in listOf(-65.0 to Noise.PINK, -50.0 to Noise.PINK, -45.0 to Noise.WHITE)) {
            val template = AudioFixtures.enrollTemplate(noiseDbfs, noise)
            for (name in AudioFixtures.WAKE_PHRASE_SAME_VOICE + AudioFixtures.OTHER_SPEECH) {
                for (peak in listOf(-10.0, -20.0)) {
                    val frames = testRecording(name, peak, noiseDbfs, noise)
                    val test = WakePhraseEvaluator.evaluate(frames, template, defaultThreshold, 48f)
                    val liveScore = monitor(frames, template, NO_TRIGGER, noiseDbfs = noiseDbfs, noise = noise).bestScore
                    val live = monitor(frames, template, noiseDbfs = noiseDbfs, noise = noise)
                    println("MEASURE equivalence noise=%s/%.0f %s peak=%.0f test=%.3f monitoring=%.3f".format(
                        noise, noiseDbfs, name, peak, test.bestConfidence, liveScore))
                    assertEquals("$name @ $noise $noiseDbfs / $peak", test.bestConfidence, liveScore, tolerance)
                    if (abs(test.bestConfidence - defaultThreshold) > tolerance) {
                        assertEquals("decision for $name", test.wouldTrigger, live.triggered)
                    }
                }
            }
        }
    }

    @Test
    fun speechWithoutTheWakePhraseDoesNotTrigger() {
        for ((noiseDbfs, noise) in listOf(-65.0 to Noise.PINK, -50.0 to Noise.PINK, -45.0 to Noise.PINK, -45.0 to Noise.WHITE)) {
            val template = AudioFixtures.enrollTemplate(noiseDbfs, noise)
            for (name in AudioFixtures.OTHER_SPEECH) for (peak in listOf(-10.0, -20.0)) {
                val live = monitor(testRecording(name, peak, noiseDbfs, noise), template, noiseDbfs = noiseDbfs, noise = noise)
                assertFalse("$name @ $noise $noiseDbfs / $peak triggered (score ${live.bestScore})", live.triggered)
            }
        }
    }

    @Test
    fun silenceAndNearSilenceNeverTrigger() {
        val template = AudioFixtures.enrollTemplate()
        val digitalSilence = List(100) { ShortArray(800) }
        val nearSilence = AudioFixtures.frames(AudioFixtures.scene(ShortArray(0), 5.0, 0.0, noiseDbfs = -72.0))
        for (frames in listOf(digitalSilence, nearSilence)) {
            val live = monitor(frames, template, warmUpSeconds = 0.0)
            assertFalse(live.triggered)
            assertTrue("score on silence was ${live.bestScore}", live.bestScore < defaultThreshold)
        }
    }

    @Test
    fun differentRecordingLevelsAreDetected() {
        val template = AudioFixtures.enrollTemplate()
        // Enrolled close to the microphone (-10 dBFS peak), spoken at 1x, ~3x and ~6x the distance
        val scores = listOf(-10.0, -20.0, -25.0).map { peak ->
            WakePhraseEvaluator.evaluate(testRecording("ciao_linda_a", peak), template, defaultThreshold, 0f).bestConfidence
        }
        println("MEASURE level dependence: $scores")
        // Quieter speech loses detail below the background, so the score decreases, but stays above the default threshold
        assertTrue("scores $scores", scores.all { it >= defaultThreshold })
        assertTrue("scores $scores", scores[0] >= scores[2])
    }

    @Test
    fun bufferBoundariesDoNotChangeFramesFeaturesOrScores() {
        val template = AudioFixtures.enrollTemplate()
        val samples = AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load("ciao_linda_a"), -15.0), 3.0, 0.5)
        val reference = AudioFixtures.frames(samples)
        val referenceScore = WakePhraseEvaluator.evaluate(reference, template, defaultThreshold, 48f).bestConfidence

        for (readSize in listOf(160, 333, 800, 1024, 4096)) {
            val assembler = PcmFrameAssembler()
            val frames = mutableListOf<ShortArray>()
            var offset = 0
            while (offset < samples.size) {
                val chunk = samples.copyOfRange(offset, minOf(samples.size, offset + readSize))
                assembler.push(chunk) { frames += it }
                offset += readSize
            }
            assertEquals(reference.size, frames.size)
            reference.indices.forEach { assertArrayEquals(reference[it], frames[it]) }

            val featuresA = WakePhraseFrontEnd().let { fe -> reference.map { fe.process(it) } }
            val featuresB = WakePhraseFrontEnd().let { fe -> frames.map { fe.process(it) } }
            featuresA.indices.forEach { assertArrayEquals(featuresA[it], featuresB[it], 0f) }
            assertEquals(referenceScore, WakePhraseEvaluator.evaluate(frames, template, defaultThreshold, 48f).bestConfidence, 0f)
        }
    }

    @Test
    fun phraseIsDetectedWhateverItsPositionWithinFramesAndWindows() {
        val template = AudioFixtures.enrollTemplate()
        // Sub-frame offsets (0, 12.5, 25, 37.5 ms) and a later start spanning other matcher windows
        val scores = listOf(0.5, 0.5125, 0.525, 0.5375, 1.2).map { lead ->
            val frames = testRecording("ciao_linda_a", -15.0, lead = lead)
            assertTrue("not triggered with lead $lead s", monitor(frames, template).triggered)
            monitor(frames, template, NO_TRIGGER).bestScore
        }
        // Known limitation: with non-overlapping 50 ms frames the score varies with frame alignment (documented)
        println("MEASURE alignment: $scores")
        assertTrue("scores $scores", scores.all { it >= defaultThreshold })
    }

    @Test
    fun thresholdDecisionsAt50IntermediateAnd80Percent() {
        val template = AudioFixtures.enrollTemplate()
        for ((name, peak) in listOf("ciao_linda_a" to -15.0, "ciao_linda_b" to -20.0, "other_speech" to -10.0)) {
            val frames = testRecording(name, peak)
            val score = monitor(frames, template, NO_TRIGGER).bestScore
            for (percent in listOf(50, 65, 80)) {
                val threshold = MatchConfidenceThreshold.toDetectorConfidence(percent)
                val test = WakePhraseEvaluator.evaluate(frames, template, threshold, 48f)
                val live = monitor(frames, template, threshold)
                // The score does not depend on the threshold; the decision is exactly "score ≥ configured threshold"
                assertEquals(score, test.bestConfidence, tolerance)
                if (abs(score - threshold) > tolerance) {
                    assertEquals("test $name @ $percent%", score >= threshold, test.wouldTrigger)
                    assertEquals("monitoring $name @ $percent%", score >= threshold, live.triggered)
                }
                println("MEASURE threshold %s %d%%: score=%.3f test=%s monitoring=%s".format(name, percent, score, test.wouldTrigger, live.triggered))
            }
        }
    }

    @Test
    fun activationRequiresTheLoudnessThreshold() {
        val template = AudioFixtures.enrollTemplate()
        val quiet = testRecording("ciao_linda_a", peakDbfs = -50.0) // peak ≈ 40 dB on the VAD scale
        val result = WakePhraseEvaluator.evaluate(quiet, template, defaultThreshold, minLevelDb = 48f)
        assertFalse(result.wouldTrigger)
        assertTrue(result.peakLevelDb < 48f)
    }

    @Test
    fun monitoringRestartReproducesResultsOfAFreshDetector() {
        val template = AudioFixtures.enrollTemplate()
        val frames = testRecording("ciao_linda_b", -15.0)
        val fresh = monitor(frames, template)

        val reused = MemoTriggerDetector(keywordDetector = TemplateKeywordDetector().apply { loadTemplate(template) })
        monitor(testRecording("other_speech", -10.0), template, detector = reused.apply {
            detectionMode = DetectionMode.KEYWORD_ONLY
            reset(startMonitoring = true)
        })
        reused.reset(startMonitoring = true) // monitoring restarted
        val afterRestart = monitor(frames, template, detector = reused)

        assertEquals(fresh.bestScore, afterRestart.bestScore, 0f)
        assertEquals(fresh.triggered, afterRestart.triggered)
    }

    @Test
    fun captureStreamRestartRelearnsTheBackgroundWithoutBreakingDetection() {
        val template = AudioFixtures.enrollTemplate(-50.0)
        val detector = MemoTriggerDetector(keywordDetector = TemplateKeywordDetector().apply { loadTemplate(template) }).apply {
            detectionMode = DetectionMode.KEYWORD_ONLY
            reset(startMonitoring = true)
        }
        monitor(AudioFixtures.frames(AudioFixtures.scene(ShortArray(0), 3.0, 0.0, -40.0)), template, detector = detector, warmUpSeconds = 0.0)
        detector.restartStream() // e.g. a different microphone with a different background
        val result = monitor(testRecording("ciao_linda_a", -10.0, -50.0), template, warmUpSeconds = 2.0, noiseDbfs = -50.0, detector = detector)
        assertTrue("score ${result.bestScore}", result.triggered)
    }

    @Test
    fun templatesFromEarlierVersionsStillLoadAndAreFlagged() {
        val legacy = TemplateKeywordDetector()
        // An earlier-version template: 'CIAO' magic with legacy features
        val legacyFeatures = TemplateKeywordDetector.generateCanonicalCiaoLindaAudio().map { legacy.extractFeatureVector(it) }
        val buffer = java.nio.ByteBuffer.allocate(12 + legacyFeatures.size * 16 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(TemplateKeywordDetector.MAGIC_LEGACY_TEMPLATE).putInt(legacyFeatures.size).putInt(16)
        legacyFeatures.forEach { vec -> vec.forEach { buffer.putFloat(it) } }

        val detector = TemplateKeywordDetector()
        assertTrue(detector.loadTemplate(buffer.array()))
        assertTrue(detector.isLegacyTemplate)
        assertTrue(detector.hasCustomTemplate())
        // Matched with the legacy features it was recorded with: the canonical audio still matches itself
        val best = TemplateKeywordDetector.generateCanonicalCiaoLindaAudio().maxOf { detector.processFrame(it, 0f, 0f).confidence }
        assertTrue("legacy self-match $best", best > 0.9f)
        // Saving keeps the legacy marker
        assertTrue(TemplateKeywordDetector().apply { loadTemplate(detector.getTemplateData()!!) }.isLegacyTemplate)
    }

    @Test
    fun enrollmentKeepsOnlyTheSpokenPart() {
        val detector = TemplateKeywordDetector()
        val recording = AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load("ciao_linda_a"), -10.0), 2.5, 0.3)
        assertTrue(detector.recordTemplate(AudioFixtures.frames(recording)))
        val phraseFrames = AudioFixtures.load("ciao_linda_a").size / 800.0
        // ~0.72 s phrase = ~15 frames (+ 1 frame padding each side), not the 50 frames of the whole recording
        assertTrue("template frames ${detector.templateFrameCount}", detector.templateFrameCount in phraseFrames.toInt()..(phraseFrames.toInt() + 4))
        assertFalse(detector.isLegacyTemplate)
    }

    @Test
    fun fullMonitoringStepFitsTheRealTimeBudget() {
        // The matcher now runs on every frame (not only on loud frames), so its cost matters
        val template = AudioFixtures.enrollTemplate()
        val detector = MemoTriggerDetector(keywordDetector = TemplateKeywordDetector().apply { loadTemplate(template) }).apply {
            detectionMode = DetectionMode.KEYWORD_ONLY
            matchConfidenceThreshold = 1f // keep matching continuously
            reset(startMonitoring = true)
        }
        val frames = AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load("other_speech"), -20.0), 20.0, 5.0, -50.0))
        frames.forEach { detector.processFrame(it) } // JIT warm-up
        val start = System.nanoTime()
        frames.forEach { detector.processFrame(it) }
        val perFrameMicros = (System.nanoTime() - start) / frames.size / 1000.0
        println("MEASURE monitoring step: %.0f µs per 50 ms frame (JVM, %d-frame template)".format(perFrameMicros, TemplateKeywordDetector().apply { loadTemplate(template) }.templateFrameCount))
        assertTrue("$perFrameMicros µs per frame", perFrameMicros < 10_000)
    }

    @Test
    fun silentEnrollmentIsRejected() {
        val detector = TemplateKeywordDetector()
        assertFalse(detector.recordTemplate(List(50) { ShortArray(800) }))
        assertFalse(detector.recordTemplate(AudioFixtures.frames(AudioFixtures.scene(ShortArray(0), 2.5, 0.0, -75.0))))
        assertFalse(detector.hasCustomTemplate())
    }
}
