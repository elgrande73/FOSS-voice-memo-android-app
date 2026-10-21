package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.MemoDetectionEvent
import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import io.github.elgrande73.voicememo.settings.MatchConfidenceThreshold
import io.github.elgrande73.voicememo.settings.SettingsMigrator
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Threshold mechanics with a deterministic synthetic phrase enrolled as the sample ([SyntheticPhrase]). */
class MatchConfidenceThresholdTest {

    private val canonicalPhrase = SyntheticPhrase.generate(amplitude = 15000.0)

    private fun bestConfidenceAndMatch(thresholdPercent: Int, phrase: List<ShortArray> = canonicalPhrase): Pair<Float, Boolean> {
        val detector = SyntheticPhrase.detector()
        detector.setMatchConfidenceThreshold(MatchConfidenceThreshold.toDetectorConfidence(thresholdPercent))
        var best = 0f
        var matched = false
        for (chunk in phrase) {
            val result = detector.processFrame(chunk, rms = 0.5f, db = 65f)
            best = maxOf(best, result.confidence)
            matched = matched || result.matched
        }
        return best to matched
    }

    /**
     * The default phrase with some frames swapped (a mispronounced phrase), so its confidence lands inside the
     * upper part of the 20%–80% range. Fully deterministic.
     */
    private fun partiallyMatchingPhrase(): Pair<List<ShortArray>, Float> {
        for (swappedFrames in 1..12) {
            val altered = canonicalPhrase.mapIndexed { index, chunk ->
                if (index in 1..swappedFrames) canonicalPhrase[(index + 11) % canonicalPhrase.size] else chunk
            }
            val confidence = bestConfidenceAndMatch(MatchConfidenceThreshold.MIN_PERCENT, altered).first
            if (confidence in 0.56f..0.74f) return altered to confidence
        }
        throw AssertionError("No altered phrase produced a confidence inside the supported threshold range")
    }

    @Test
    fun rangeIsTwentyToEightyPercentWithDefaultFifty() {
        assertEquals(20, MatchConfidenceThreshold.MIN_PERCENT)
        assertEquals(80, MatchConfidenceThreshold.MAX_PERCENT)
        assertEquals(50, MatchConfidenceThreshold.DEFAULT_PERCENT)
    }

    @Test
    fun percentMapsOneToOneOntoDetectorConfidenceScale() {
        assertEquals(0.20f, MatchConfidenceThreshold.toDetectorConfidence(20), 0.0001f)
        assertEquals(0.35f, MatchConfidenceThreshold.toDetectorConfidence(35), 0.0001f)
        assertEquals(0.50f, MatchConfidenceThreshold.toDetectorConfidence(50), 0.0001f)
        assertEquals(0.65f, MatchConfidenceThreshold.toDetectorConfidence(65), 0.0001f)
        assertEquals(0.80f, MatchConfidenceThreshold.toDetectorConfidence(80), 0.0001f)
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        assertEquals(20, MatchConfidenceThreshold.clampPercent(10))
        assertEquals(20, MatchConfidenceThreshold.clampPercent(20))
        assertEquals(50, MatchConfidenceThreshold.clampPercent(50))
        assertEquals(0.20f, MatchConfidenceThreshold.toDetectorConfidence(-5), 0.0001f)
        assertEquals(80, MatchConfidenceThreshold.clampPercent(99))
        assertEquals(0.80f, MatchConfidenceThreshold.toDetectorConfidence(250), 0.0001f)
    }

    @Test
    fun defaultWakePhraseIsRecognizedAtDefaultThreshold() {
        val (_, matched) = bestConfidenceAndMatch(MatchConfidenceThreshold.DEFAULT_PERCENT)
        assertTrue("The synthetic phrase must match its enrolled template at the default threshold", matched)
    }

    @Test
    fun thresholdControlsActualRecognition() {
        val (phrase, confidence) = partiallyMatchingPhrase()
        val confidencePercent = (confidence * 100).toInt()

        // Same audio: accepted when the threshold is below its confidence, rejected when above
        assertTrue(bestConfidenceAndMatch(confidencePercent - 5, phrase).second)
        assertFalse(
            "A threshold above the achieved confidence ($confidencePercent%) must reject the phrase",
            bestConfidenceAndMatch(confidencePercent + 5, phrase).second
        )
    }

    @Test
    fun matchRequiresConfidenceAtLeastThreshold() {
        val detector = SyntheticPhrase.detector()
        detector.setMatchConfidenceThreshold(0.80f)
        for (chunk in canonicalPhrase) {
            val result = detector.processFrame(chunk, rms = 0.5f, db = 65f)
            if (result.matched) assertTrue(result.confidence >= 0.80f)
        }
    }

    @Test
    fun triggerDetectorAppliesThresholdToWakePhraseDetector() {
        val (phrase, confidence) = partiallyMatchingPhrase()
        fun triggers(percent: Int): Boolean {
            val trigger = MemoTriggerDetector(keywordDetector = SyntheticPhrase.detector())
            trigger.detectionMode = DetectionMode.KEYWORD_ONLY
            trigger.thresholdDb = 45f
            trigger.matchConfidenceThreshold = MatchConfidenceThreshold.toDetectorConfidence(percent)
            trigger.reset(startMonitoring = true)
            return phrase.any { chunk -> trigger.processFrame(chunk).any { it is MemoDetectionEvent.TriggerStarted } }
        }
        val confidencePercent = (confidence * 100).toInt()
        assertTrue(triggers(confidencePercent - 5))
        assertFalse(triggers(confidencePercent + 5))
    }

    @Test
    fun legacySensitivityMapsToItsFormerEffectiveThreshold() {
        // Former default sensitivity 0.60 required 40% confidence
        assertEquals(0.40f, SettingsMigrator.legacySensitivityToConfidence(0.60f), 0.001f)
        // Lowest former sensitivity was the strictest setting (50%)
        assertEquals(0.50f, SettingsMigrator.legacySensitivityToConfidence(0.10f), 0.001f)
    }
}
