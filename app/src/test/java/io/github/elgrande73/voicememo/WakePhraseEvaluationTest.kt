package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.WakePhraseBenchmark.CONVERSATION
import io.github.elgrande73.voicememo.WakePhraseBenchmark.HELD_OUT
import io.github.elgrande73.voicememo.WakePhraseBenchmark.NEAR_MISSES
import io.github.elgrande73.voicememo.WakePhraseBenchmark.Setup
import io.github.elgrande73.voicememo.WakePhraseBenchmark.TARGET_EVAL
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproducible accuracy evaluation of wake-phrase detection on the synthetic fixtures. Results are printed with the
 * prefix "MEASURE" (run with `-i` to see them) and documented in docs/WAKE_PHRASE_DETECTION.md. The assertions are
 * regression guards derived from measured values, not targets: they fail when a change makes detection worse.
 */
class WakePhraseEvaluationTest {

    @Test
    fun baselineAndMultiSampleConfusionMatrices() {
        for (conditions in listOf("tuning" to WakePhraseBenchmark.TUNING, "held-out" to HELD_OUT)) {
            val one = WakePhraseBenchmark.evaluate(Setup(samples = 1, conditions = conditions.second))
            val five = WakePhraseBenchmark.evaluate(Setup(samples = 5, conditions = conditions.second))
            println("MEASURE ${conditions.first} 1 sample @50: $one")
            println("MEASURE ${conditions.first} 5 samples @50: $five")
            assertTrue("${conditions.first}: recall ${five.recall} < ${one.recall}", five.recall >= one.recall)
            // Measured: equal on the tuning set, +1 false positive (18 vs 17 of 84) on the held-out set. Guard that
            // additional samples never add more than that; see "Known regression" in docs/WAKE_PHRASE_DETECTION.md.
            assertTrue("${conditions.first}: FP ${five.fp} vs ${one.fp}", five.fp <= one.fp + 1)
        }
    }

    @Test
    fun thresholdSweepSeparatesNearMissesFromConversation() {
        for (samples in listOf(1, 5)) {
            var previousRecall = 1.0
            for (threshold in 20..80 step 5) {
                val setup = Setup(samples = samples, thresholdPercent = threshold)
                val nearMiss = WakePhraseBenchmark.evaluate(setup, TARGET_EVAL, NEAR_MISSES)
                val conversation = WakePhraseBenchmark.evaluate(setup, emptyList(), CONVERSATION)
                println(
                    "MEASURE sweep samples=$samples threshold=$threshold recall %.3f near-miss FPR %.3f conversation FPR %.3f"
                        .format(nearMiss.recall, nearMiss.falsePositiveRate, conversation.falsePositiveRate)
                )
                // Ordinary speech never triggered at the default or stricter thresholds (measured); below 50 % it does,
                // which is reported, not asserted
                if (threshold >= 50) assertEquals("conversation FP at $threshold", 0, conversation.fp)
                assertTrue(nearMiss.recall <= previousRecall + 1e-9)
                previousRecall = nearMiss.recall
            }
        }
    }

    @Test
    fun moreSamplesNeverLoseRecallAtTheSameThreshold() {
        for (threshold in listOf(50, 60, 70)) {
            val one = WakePhraseBenchmark.evaluate(Setup(samples = 1, thresholdPercent = threshold), negatives = emptyList())
            val five = WakePhraseBenchmark.evaluate(Setup(samples = 5, thresholdPercent = threshold), negatives = emptyList())
            println("MEASURE recall @$threshold: 1 sample %.3f, 5 samples %.3f".format(one.recall, five.recall))
            assertTrue("@$threshold", five.recall >= one.recall)
        }
    }

    @Test
    fun falseActivationsPerHourOfContinuousMonitoring() {
        for (samples in listOf(1, 5)) for (threshold in listOf(30, 40, 50, 70)) {
            val setup = Setup(samples = samples, thresholdPercent = threshold)
            val (conversation, conversationPerHour) = WakePhraseBenchmark.falseActivationsPerHour(setup, CONVERSATION)
            val (nearMiss, nearMissPerHour) = WakePhraseBenchmark.falseActivationsPerHour(setup, NEAR_MISSES)
            println(
                "MEASURE FA/h samples=$samples threshold=$threshold conversation $conversation in 10 min (%.0f/h), near misses $nearMiss in 10 min (%.0f/h)"
                    .format(conversationPerHour, nearMissPerHour)
            )
            if (threshold >= 50) assertEquals("conversation activations", 0, conversation)
        }
    }

    @Test
    fun processingTimePerFrameWithFiveSamples() {
        val frames = AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load("neg_speech_1"), -15.0), 30.0, 1.0))
        val perFrame = (1..5).associateWith { count ->
            val detector = TemplateKeywordDetector().apply { loadTemplates(WakePhraseBenchmark.templates(count)) }
            frames.take(200).forEach { detector.processFrame(it, 0f, 0f) } // warm-up
            val start = System.nanoTime()
            frames.forEach { detector.processFrame(it, 0f, 0f) }
            (System.nanoTime() - start) / 1e6 / frames.size
        }
        perFrame.forEach { (n, ms) -> println("MEASURE processing samples=$n %.3f ms per 50 ms frame (JVM, host CPU)".format(ms)) }
        // Far below real time even on a slow host; the phone is slower, see docs
        assertTrue("${perFrame[5]} ms", perFrame.getValue(5) < 25.0)
    }
}
