package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.AudioFixtures.Noise
import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.KeywordBehavior
import io.github.elgrande73.voicememo.detection.MemoDetectionEvent
import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import io.github.elgrande73.voicememo.wakephrase.FrontEndConfig
import io.github.elgrande73.voicememo.wakephrase.ReferenceAggregation
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEnrollment
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEvaluator
import kotlin.random.Random

/** Confusion matrix of one evaluation; every metric handles empty denominators (returns NaN, printed as "–"). */
data class ConfusionMatrix(val tp: Int, val fp: Int, val fn: Int, val tn: Int) {
    private fun ratio(num: Int, den: Int) = if (den == 0) Double.NaN else num.toDouble() / den
    val precision get() = ratio(tp, tp + fp)
    val recall get() = ratio(tp, tp + fn)
    val specificity get() = ratio(tn, tn + fp)
    val falsePositiveRate get() = ratio(fp, fp + tn)
    val falseNegativeRate get() = ratio(fn, fn + tp)
    val f1 get() = if (precision.isNaN() || recall.isNaN() || precision + recall == 0.0) Double.NaN else 2 * precision * recall / (precision + recall)

    override fun toString(): String {
        fun f(x: Double) = if (x.isNaN()) "–" else "%.3f".format(x)
        return "TP=$tp FN=$fn FP=$fp TN=$tn | precision ${f(precision)} recall ${f(recall)} specificity ${f(specificity)} FPR ${f(falsePositiveRate)} FNR ${f(falseNegativeRate)} F1 ${f(f1)}"
    }
}

/**
 * Reproducible wake-phrase evaluation on the synthetic fixtures (see app/src/test/resources/audio/README.md).
 *
 * Evaluation unit: one clip inside a 3 s recording (phrase after 0.5 s plus a random sub-frame offset), decided by the
 * monitoring logic (WakePhraseEvaluator: same front end, matcher, threshold and loudness criterion as monitoring).
 * Conditions: quiet room, pink noise −50 dBFS, white noise −45 dBFS; speech peaks −12 and −22 dBFS.
 * Enrollment samples are recorded in a quiet room and are never used as evaluation clips.
 */
object WakePhraseBenchmark {

    val ENROLL = (1..5).map { "target_enroll_$it" }
    val TARGET_EVAL = (1..6).map { "target_eval_$it" }
    val OTHER_SPEAKERS = (1..3).map { "other_speaker_$it" }
    /** Phrases that sound like the wake phrase, or contain part of it. */
    val NEAR_MISSES = (1..5).map { "neg_similar_$it" } + (1..2).map { "neg_partial_$it" }
    /** Ordinary speech without the wake phrase. */
    val CONVERSATION = (1..5).map { "neg_speech_$it" } + listOf("other_speech", "other_speech_en")
    val NEGATIVES = NEAR_MISSES + CONVERSATION

    /** Tuning conditions (background, speech peak levels) and a held-out set used only to confirm decisions. */
    val TUNING = Conditions(listOf(-65.0 to Noise.PINK, -50.0 to Noise.PINK, -45.0 to Noise.WHITE), listOf(-12.0, -22.0), 1000)
    val HELD_OUT = Conditions(listOf(-60.0 to Noise.WHITE, -55.0 to Noise.PINK, -42.0 to Noise.PINK), listOf(-16.0, -26.0), 5000)

    data class Conditions(val backgrounds: List<Pair<Double, Noise>>, val speechPeaks: List<Double>, val seed: Int)

    data class Setup(
        val samples: Int = 1,
        val aggregation: ReferenceAggregation = ReferenceAggregation.MAX,
        val thresholdPercent: Int = 50,
        val consecutiveMatchFrames: Int = 1,
        val frontEnd: FrontEndConfig = FrontEndConfig.DEFAULT,
        val localMismatchWeight: Float = TemplateKeywordDetector.DEFAULT_LOCAL_MISMATCH_WEIGHT,
        val distanceScale: Float = TemplateKeywordDetector.DEFAULT_DISTANCE_SCALE,
        val conditions: Conditions = TUNING,
        /** Optional minimum phrase level in dBFS (null = off). Default: the app's setting for new installations. */
        val minLevelDbfs: Float? = LEVEL_DEFAULT_DBFS
    ) {
        val minLevelDb: Float? get() = minLevelDbfs?.let { it + 90f }
    }

    /** Minimum phrase level used by the benchmark unless a setup says otherwise (same as the app default). */
    val LEVEL_DEFAULT_DBFS: Float? = io.github.elgrande73.voicememo.settings.WakePhraseLevel.DEFAULT_DBFS

    fun templates(count: Int, frontEnd: FrontEndConfig = FrontEndConfig.DEFAULT): List<ByteArray> = ENROLL.take(count).map { name ->
        val recording = AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load(name), -12.0), 2.5, 0.3, -65.0, Noise.PINK, seed = name.hashCode())
        val result = WakePhraseEnrollment.validate(AudioFixtures.frames(recording), frontEnd)
        check(result is WakePhraseEnrollment.Result.Accepted) { "enrollment of $name rejected: $result" }
        result.templateBytes
    }

    private fun detected(setup: Setup, templates: List<ByteArray>, clip: String, noiseDbfs: Double, noise: Noise, peak: Double, seed: Int): Boolean {
        val offset = Random(seed).nextDouble(0.0, 0.05)
        val frames = AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load(clip), peak), 3.0, 0.5 + offset, noiseDbfs, noise, seed))
        return WakePhraseEvaluator.evaluate(
            frames, templates, setup.thresholdPercent / 100f, setup.minLevelDb, setup.frontEnd, setup.aggregation, setup.consecutiveMatchFrames,
            setup.localMismatchWeight, setup.distanceScale
        ).wouldTrigger
    }

    /** Confusion matrix over target-speaker positives and all negatives. */
    fun evaluate(setup: Setup, positives: List<String> = TARGET_EVAL, negatives: List<String> = NEGATIVES): ConfusionMatrix {
        val templates = templates(setup.samples, setup.frontEnd)
        var tp = 0; var fn = 0; var fp = 0; var tn = 0
        var seed = setup.conditions.seed
        for ((noiseDbfs, noise) in setup.conditions.backgrounds) for (peak in setup.conditions.speechPeaks) {
            for (clip in positives) if (detected(setup, templates, clip, noiseDbfs, noise, peak, seed++)) tp++ else fn++
            for (clip in negatives) if (detected(setup, templates, clip, noiseDbfs, noise, peak, seed++)) fp++ else tn++
        }
        return ConfusionMatrix(tp, fp, fn, tn)
    }

    /** Best match scores (threshold-independent) of positives and negatives over all conditions. */
    fun scores(setup: Setup, positives: List<String> = TARGET_EVAL, negatives: List<String> = NEGATIVES): Pair<List<Float>, List<Float>> {
        val templates = templates(setup.samples, setup.frontEnd)
        val pos = mutableListOf<Float>()
        val neg = mutableListOf<Float>()
        var seed = setup.conditions.seed
        for ((noiseDbfs, noise) in setup.conditions.backgrounds) for (peak in setup.conditions.speechPeaks) {
            for (clip in positives + negatives) {
                val offset = Random(seed).nextDouble(0.0, 0.05)
                val frames = AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load(clip), peak), 3.0, 0.5 + offset, noiseDbfs, noise, seed++))
                val score = WakePhraseEvaluator.evaluate(
                    frames, templates, 1f, null, setup.frontEnd, setup.aggregation, 1, setup.localMismatchWeight, setup.distanceScale
                ).bestConfidence
                if (clip in positives) pos += score else neg += score
            }
        }
        return pos to neg
    }

    /** Area under the ROC curve: probability that a positive scores higher than a negative (ties count half). */
    fun auc(pos: List<Float>, neg: List<Float>): Double {
        var wins = 0.0
        for (p in pos) for (n in neg) wins += if (p > n) 1.0 else if (p == n) 0.5 else 0.0
        return wins / (pos.size * neg.size)
    }

    /**
     * Continuous monitoring on [minutes] of negative-only audio (all negative clips in random order, random gaps,
     * varying level, pink background at −55 dBFS): number of wake-phrase activations, reported per hour.
     */
    fun falseActivationsPerHour(setup: Setup, negatives: List<String> = NEGATIVES, minutes: Double = 10.0): Pair<Int, Double> {
        val templates = templates(setup.samples, setup.frontEnd)
        val matcher = TemplateKeywordDetector(
            frontEndConfig = setup.frontEnd, aggregation = setup.aggregation, localMismatchWeight = setup.localMismatchWeight,
            distanceScale = setup.distanceScale
        ).apply { loadTemplates(templates) }
        val detector = MemoTriggerDetector(keywordDetector = matcher).apply {
            detectionMode = DetectionMode.KEYWORD_ONLY
            keywordBehavior = KeywordBehavior.START_AND_STOP
            thresholdDb = 48f
            wakePhraseMinLevelDb = setup.minLevelDb
            matchConfidenceThreshold = setup.thresholdPercent / 100f
            consecutiveMatchFrames = setup.consecutiveMatchFrames
            reset(startMonitoring = true)
        }
        val random = Random(7)
        val totalSamples = (minutes * 60 * AudioFixtures.SAMPLE_RATE).toInt()
        val background = AudioFixtures.noise(totalSamples, -55.0, 11, Noise.PINK)
        val stream = ShortArray(totalSamples) { background[it].toInt().toShort() }
        var position = AudioFixtures.SAMPLE_RATE
        while (true) {
            val clip = AudioFixtures.atPeak(AudioFixtures.load(negatives[random.nextInt(negatives.size)]), random.nextDouble(-25.0, -10.0))
            if (position + clip.size >= totalSamples) break
            for (i in clip.indices) stream[position + i] = (stream[position + i] + clip[i]).coerceIn(-32768, 32767).toShort()
            position += clip.size + (random.nextDouble(0.3, 3.0) * AudioFixtures.SAMPLE_RATE).toInt()
        }
        var activations = 0
        // Starts and stop-phrase events both count as false activations
        for (frame in AudioFixtures.frames(stream)) {
            for (event in detector.processFrame(frame)) {
                if (event is MemoDetectionEvent.TriggerStarted) activations++
                if (event is MemoDetectionEvent.RecordingCompleted && event.stopReason.trimsStopPhrase) activations++
            }
        }
        return activations to activations / (minutes / 60.0)
    }
}
