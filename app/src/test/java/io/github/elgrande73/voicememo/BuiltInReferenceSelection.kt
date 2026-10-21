package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.AudioFixtures.Noise
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEnrollment
import kotlin.random.Random

/**
 * Chooses the renditions that form the built-in "Mango Tango" reference, reproducibly and from the development split
 * only. Candidates: `mango_ref_1..5` and `mango_pool_1..20` (voices used in neither split).
 *
 * With [ReferenceAggregation.MAX] the best score of a set of references is the maximum of the single references' best
 * scores, so each candidate is scored once per development trial and sets are compared on those scores. Greedy forward
 * selection adds the candidate that most improves development F1 at the default threshold.
 */
object BuiltInReferenceSelection {

    val CANDIDATES = (1..5).map { "mango_ref_$it" } + (1..20).map { "mango_pool_$it" }

    /** Features of a rendition, extracted exactly like a Settings enrollment (as BuiltInWakePhraseTest does). */
    fun features(name: String): List<FloatArray> {
        val recording = AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load(name), -12.0), 2.5, 0.3, -65.0, Noise.PINK, seed = name.hashCode())
        val result = WakePhraseEnrollment.validate(AudioFixtures.frames(recording))
        check(result is WakePhraseEnrollment.Result.Accepted) { "$name rejected: $result" }
        return TemplateKeywordDetector.parseFeatures(result.templateBytes)!!
    }

    /** A matcher whose built-in reference is [names] (instead of the bundled one). */
    fun matcher(names: List<String>): () -> TemplateKeywordDetector {
        val refs = names.map { features(it) }
        return { TemplateKeywordDetector(builtInPhrase = { refs }) }
    }

    data class Selection(val chosen: List<String>, val steps: List<String>)

    fun select(split: WakePhraseCorpus.Split = WakePhraseCorpus.DEV, thresholdPercent: Int = 50, maxSize: Int = 12): Selection {
        // Trials as in WakePhraseCorpus.evaluate (same seeds), scored with each candidate alone
        data class T(val positive: Boolean, val frames: List<ShortArray>)
        val trials = mutableListOf<T>()
        var seed = 7000
        for ((noiseDbfs, noise) in WakePhraseCorpus.BACKGROUNDS) for (peak in WakePhraseCorpus.PEAKS) {
            for ((clip, positive) in split.positives.map { it to true } + split.negatives.map { it to false }) {
                val audio = AudioFixtures.load(clip)
                val start = 0.5 + Random(seed).nextDouble(0.0, 0.05)
                val total = maxOf(3.0, start + audio.size.toDouble() / AudioFixtures.SAMPLE_RATE + 1.5)
                trials += T(positive, AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(audio, peak), total, start, noiseDbfs, noise, seed++)))
            }
        }
        val scores = CANDIDATES.associateWith { name ->
            val build = matcher(listOf(name))
            trials.map { t ->
                val m = build().apply { primeNoiseFloor(t.frames) }
                t.frames.maxOf { m.processFrame(it, 0f, 0f).confidence }
            }
        }
        val threshold = thresholdPercent / 100f
        fun matrix(set: List<String>): ConfusionMatrix {
            var tp = 0; var fp = 0; var fn = 0; var tn = 0
            for ((i, t) in trials.withIndex()) {
                val hit = set.maxOf { scores.getValue(it)[i] } >= threshold
                if (t.positive) { if (hit) tp++ else fn++ } else { if (hit) fp++ else tn++ }
            }
            return ConfusionMatrix(tp, fp, fn, tn)
        }
        val chosen = mutableListOf<String>()
        val steps = mutableListOf("bundled ${(1..5).map { "mango_ref_$it" }}: ${matrix((1..5).map { "mango_ref_$it" })}")
        var bestF1 = 0.0
        while (chosen.size < maxSize) {
            val (name, m) = (CANDIDATES - chosen.toSet()).map { it to matrix(chosen + it) }.maxBy { it.second.f1.takeUnless(Double::isNaN) ?: 0.0 }
            if (m.f1 <= bestF1) break
            bestF1 = m.f1
            chosen += name
            steps += "+$name: $m"
        }
        return Selection(chosen, steps)
    }
}
