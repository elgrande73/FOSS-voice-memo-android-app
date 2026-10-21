package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.WakePhraseCorpus.Config
import io.github.elgrande73.voicememo.WakePhraseCorpus.DEV
import io.github.elgrande73.voicememo.WakePhraseCorpus.TEST
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Utterance-level evaluation of the built-in "Mango Tango" reference on the speaker-split corpus ([WakePhraseCorpus]).
 * Results are printed with "MEASURE" and documented in docs/WAKE_PHRASE_DETECTION.md (synthetic speech: relative
 * comparisons, not real-world accuracy).
 */
class WakePhraseCorpusTest {

    @Test
    fun builtInReferenceOnBothSplits() {
        for (threshold in listOf(40, 50, 60)) for (split in listOf(DEV, TEST)) {
            val report = WakePhraseCorpus.evaluate(split, Config(thresholdPercent = threshold), withContinuous = threshold == 50)
            println("MEASURE corpus built-in @$threshold $report")
            if (threshold == 50) {
                // Regression guards from the measured values (docs/WAKE_PHRASE_DETECTION.md)
                assertTrue("${split.name} recall ${report.matrix.tp}", report.matrix.tp >= MEASURED_TP_AT_50.getValue(split.name))
                assertTrue("${split.name} FP ${report.matrix.fp}", report.matrix.fp <= MEASURED_FP_AT_50.getValue(split.name))
                assertTrue("${split.name} activations ${report.activations}", report.activations <= MEASURED_ACTIVATIONS_AT_50.getValue(split.name))
                assertEquals(0, report.conversationFp)
            }
        }
    }

    @Test
    fun referenceSelectionOnTheDevelopmentSplit() {
        org.junit.Assume.assumeTrue(System.getenv("SELECT_BUILT_IN") == "1")
        val selection = BuiltInReferenceSelection.select()
        selection.steps.forEach { println("MEASURE selection $it") }
        val config = Config(matcher = BuiltInReferenceSelection.matcher(selection.chosen))
        for (split in listOf(DEV, TEST)) println("MEASURE corpus selected ${selection.chosen} @50 ${WakePhraseCorpus.evaluate(split, config)}")
    }

    /**
     * Mean-normalised matching (the default) against the earlier matcher, both on the built-in reference: the
     * separation of positives and negatives (AUC, threshold-independent) must stay clearly better on both splits.
     */
    @Test
    fun meanNormalisedMatchingSeparatesBetterOnBothSplits() {
        val earlier = Config(matcher = { TemplateKeywordDetector(meanNormalization = false) })
        for (split in listOf(DEV, TEST)) {
            val before = WakePhraseCorpus.evaluate(split, earlier, withContinuous = split === TEST)
            val after = WakePhraseCorpus.evaluate(split, Config(), withContinuous = split === TEST)
            println("MEASURE corpus earlier matcher @50 $before")
            println("MEASURE corpus mean-normalised @50 $after")
            // Measured AUC: dev 0.746 -> 0.900, test 0.698 -> 0.836
            assertTrue("${split.name} AUC ${after.auc} vs ${before.auc}", after.auc >= before.auc + 0.10)
            assertTrue("${split.name} FP ${after.matrix.fp} vs ${before.matrix.fp}", after.matrix.fp <= before.matrix.fp)
            assertEquals(0, after.conversationFp)
        }
    }

    companion object {
        val MEASURED_TP_AT_50 = mapOf("dev" to 23, "test" to 35) // of 72 / 120
        val MEASURED_FP_AT_50 = mapOf("dev" to 0, "test" to 5) // of 228 / 228
        val MEASURED_ACTIVATIONS_AT_50 = mapOf("dev" to 0, "test" to 6) // continuous negative speech, ~3 min each
    }
}
