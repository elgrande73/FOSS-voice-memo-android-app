package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.AudioFixtures.Noise
import io.github.elgrande73.voicememo.wakephrase.FrontEndConfig
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Evaluates the noise suppression against the same front end with suppression bypassed, on identical fixtures:
 * wake phrase (same voice, two renditions) and unrelated speech, in quiet and in pink/white background noise, at three
 * speech levels, with enrollment in the same environment. Counts score ≥ 50 % (default threshold) as a
 * detection for the wake phrase and as a false match for other speech.
 */
class WakePhraseNoiseRobustnessTest {

    private data class Counts(val detections: Int, val phraseTrials: Int, val falseMatches: Int, val otherTrials: Int)

    private fun run(config: FrontEndConfig, conditions: List<Pair<Double, Noise>>): Counts {
        var detections = 0
        var phraseTrials = 0
        var falseMatches = 0
        var otherTrials = 0
        for ((noiseDbfs, noise) in conditions) {
            val enroll = TemplateKeywordDetector(frontEndConfig = config)
            val recording = AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load("wake_phrase_a"), -10.0), 2.5, 0.3, noiseDbfs, noise, 1)
            check(enroll.recordTemplate(AudioFixtures.frames(recording)))
            val template = enroll.getTemplateData()!!
            for (peak in listOf(-10.0, -20.0, -25.0)) {
                fun score(name: String, seed: Int) = WakePhraseEvaluator.evaluate(
                    AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load(name), peak), 3.0, 0.5, noiseDbfs, noise, seed)),
                    template, 0.5f, 0f, config
                ).bestConfidence
                AudioFixtures.WAKE_PHRASE_SAME_VOICE.forEachIndexed { i, name ->
                    phraseTrials++
                    if (score(name, 30 + i) >= 0.5f) detections++
                }
                AudioFixtures.OTHER_SPEECH.forEachIndexed { i, name ->
                    otherTrials++
                    if (score(name, 40 + i) >= 0.5f) falseMatches++
                }
            }
        }
        return Counts(detections, phraseTrials, falseMatches, otherTrials)
    }

    private val noisy = listOf(-50.0 to Noise.PINK, -45.0 to Noise.PINK, -40.0 to Noise.PINK, -50.0 to Noise.WHITE, -45.0 to Noise.WHITE, -40.0 to Noise.WHITE)
    private val quiet = listOf(-65.0 to Noise.PINK, -65.0 to Noise.WHITE)

    @Test
    fun suppressionRemovesFalseMatchesInBackgroundNoise() {
        val withSuppression = run(FrontEndConfig.DEFAULT, noisy)
        val bypass = run(FrontEndConfig.BYPASS, noisy)
        println("MEASURE noise: suppression $withSuppression | bypass $bypass")
        assertEquals("false matches with suppression", 0, withSuppression.falseMatches)
        // Measured with recorded samples matched in a 20 dB range: suppression 23/36 detections, bypass 13/36, neither
        // with false matches (with the earlier 40 dB matching, bypass had 23 false matches of 36)
        assertTrue("bypass false matches ${bypass.falseMatches}", bypass.falseMatches >= withSuppression.falseMatches)
        assertTrue("detections $withSuppression vs bypass $bypass", withSuppression.detections > bypass.detections)
        assertTrue("detections $withSuppression", withSuppression.detections * 3 >= withSuppression.phraseTrials)
    }

    @Test
    fun suppressionDoesNotHurtQuietConditions() {
        val withSuppression = run(FrontEndConfig.DEFAULT, quiet)
        val bypass = run(FrontEndConfig.BYPASS, quiet)
        println("MEASURE quiet: suppression $withSuppression | bypass $bypass")
        assertEquals(0, withSuppression.falseMatches)
        assertTrue("detections $withSuppression vs bypass $bypass", withSuppression.detections >= bypass.detections - 1)
    }
}
