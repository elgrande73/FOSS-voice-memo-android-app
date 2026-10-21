package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.AudioFixtures.Noise
import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.KeywordBehavior
import io.github.elgrande73.voicememo.detection.MemoDetectionEvent
import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import kotlin.random.Random

/**
 * Utterance-level wake-phrase corpus with a development / test split by speaker (synthetic eSpeak NG fixtures, see
 * app/src/test/resources/audio/README.md). Development clips may be used to choose parameters; the test split only to
 * confirm a decision made on development data.
 *
 * Unit of evaluation: one utterance in one acoustic condition (a 3 s recording, the clip after 0.5 s plus a random
 * sub-frame offset), decided once by the monitoring state machine — never individual frames, which are correlated.
 */
object WakePhraseCorpus {

    data class Split(val name: String, val positives: List<String>, val nearMisses: List<String>, val conversation: List<String>) {
        val negatives get() = nearMisses + conversation
    }

    val DEV = Split(
        "dev",
        (1..12).map { "dev_pos_$it" },
        (1..32).map { "dev_near_$it" },
        (1..6).map { "dev_conv_$it" }
    )
    val TEST = Split(
        "test",
        (1..10).map { "test_pos_$it" } + (1..10).map { "mango_eval_$it" },
        (1..32).map { "test_near_$it" },
        (1..6).map { "test_conv_$it" }
    )

    /** Acoustic conditions (background, speech peak in dBFS), the same for both splits. */
    val BACKGROUNDS = listOf(-65.0 to Noise.PINK, -50.0 to Noise.PINK, -45.0 to Noise.WHITE)
    val PEAKS = listOf(-12.0, -22.0)

    data class Config(
        /** Enrolled samples (empty = built-in "Mango Tango" reference). */
        val templates: List<ByteArray> = emptyList(),
        val thresholdPercent: Int = 50,
        val minLevelDbfs: Float? = WakePhraseBenchmark.LEVEL_DEFAULT_DBFS,
        val consecutiveMatchFrames: Int = MemoTriggerDetector.DEFAULT_CONSECUTIVE_MATCH_FRAMES,
        val matcher: () -> TemplateKeywordDetector = { TemplateKeywordDetector() }
    )

    data class Trial(val clip: String, val positive: Boolean, val triggered: Boolean, val latencyFromStartMs: Long?, val latencyFromEndMs: Long?, val bestScore: Float)

    class Report(val split: Split, val trials: List<Trial>, val activations: Int, val negativeSpeechSeconds: Double, val streamSeconds: Double, val msPerFrame: Double) {
        val matrix: ConfusionMatrix get() {
            val tp = trials.count { it.positive && it.triggered }
            val fn = trials.count { it.positive && !it.triggered }
            val fp = trials.count { !it.positive && it.triggered }
            val tn = trials.count { !it.positive && !it.triggered }
            return ConfusionMatrix(tp, fp, fn, tn)
        }
        val nearMissFp get() = trials.count { it.clip in split.nearMisses && it.triggered }
        val conversationFp get() = trials.count { it.clip in split.conversation && it.triggered }
        /** False activations per hour of negative speech (gaps between utterances excluded) and per hour of audio. */
        val falseActivationsPerSpeechHour get() = activations / (negativeSpeechSeconds / 3600)
        val falseActivationsPerHour get() = activations / (streamSeconds / 3600)
        private val latencies get() = trials.mapNotNull { if (it.positive) it.latencyFromStartMs else null }.sorted()
        fun latencyPercentile(p: Double): Long? = latencies.takeIf { it.isNotEmpty() }?.let { it[((it.size - 1) * p).toInt()] }
        val meanLatencyFromEndMs get() = trials.mapNotNull { if (it.positive) it.latencyFromEndMs else null }.average()
        val auc get() = WakePhraseBenchmark.auc(trials.filter { it.positive }.map { it.bestScore }, trials.filter { !it.positive }.map { it.bestScore })

        override fun toString() = buildString {
            val m = matrix
            append("${split.name}: $m | miss rate %.3f AUC %.3f".format(m.falseNegativeRate, auc))
            append(" | near-miss FP $nearMissFp/${split.nearMisses.size * CONDITIONS} conversation FP $conversationFp/${split.conversation.size * CONDITIONS}")
            append(" | latency from phrase start median ${latencyPercentile(0.5)} ms p90 ${latencyPercentile(0.9)} ms, after phrase end mean %.0f ms".format(meanLatencyFromEndMs))
            append(" | continuous: $activations activations in %.1f min of negative speech (%.0f/h of speech, %.0f/h of audio)".format(negativeSpeechSeconds / 60, falseActivationsPerSpeechHour, falseActivationsPerHour))
            append(" | %.3f ms/frame".format(msPerFrame))
        }
    }

    private const val CONDITIONS = 6
    private const val FRAME_MS = 50L
    private const val LEAD_S = 0.5

    private fun newDetector(config: Config, matcher: TemplateKeywordDetector) = MemoTriggerDetector(keywordDetector = matcher).apply {
        detectionMode = DetectionMode.KEYWORD_ONLY
        keywordBehavior = KeywordBehavior.START_AND_STOP
        wakePhraseMinLevelDb = config.minLevelDbfs?.let { it + 90f }
        matchConfidenceThreshold = config.thresholdPercent / 100f
        consecutiveMatchFrames = config.consecutiveMatchFrames
        reset(startMonitoring = true)
    }

    private fun trial(config: Config, clip: String, positive: Boolean, noiseDbfs: Double, noise: Noise, peak: Double, seed: Int): Trial {
        val audio = AudioFixtures.load(clip)
        val offset = Random(seed).nextDouble(0.0, 0.05)
        val start = LEAD_S + offset
        val total = maxOf(3.0, start + audio.size.toDouble() / AudioFixtures.SAMPLE_RATE + 1.5)
        val frames = AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(audio, peak), total, start, noiseDbfs, noise, seed))
        val startMs = (start * 1000).toLong()
        val endMs = startMs + audio.size * 1000L / AudioFixtures.SAMPLE_RATE

        // Threshold-independent score: the matcher alone over every frame
        val scorer = config.matcher().apply { loadTemplates(config.templates); primeNoiseFloor(frames) }
        var best = 0f
        for (frame in frames) best = maxOf(best, scorer.processFrame(frame, 0f, 0f).confidence)

        // Decision: the monitoring state machine (same as WakePhraseEvaluator), noting the frame that starts a memo
        val matcher = config.matcher().apply { loadTemplates(config.templates) }
        val detector = newDetector(config, matcher)
        matcher.primeNoiseFloor(frames)
        var triggerMs: Long? = null
        for ((index, frame) in frames.withIndex()) {
            if (detector.processFrame(frame).any { it is MemoDetectionEvent.TriggerStarted }) {
                triggerMs = (index + 1) * FRAME_MS // the end of the frame that completed the match
                break
            }
        }
        return Trial(clip, positive, triggerMs != null, triggerMs?.minus(startMs), triggerMs?.minus(endMs), best)
    }

    /** Continuous monitoring over each negative clip of [split] [repeats] times in random order, with random gaps and levels. */
    private fun continuous(config: Config, split: Split, repeats: Int = 4): Triple<Int, Double, Double> {
        val random = Random(17)
        val clips = (List(repeats) { split.negatives }.flatten()).shuffled(random)
        val pieces = clips.map { AudioFixtures.atPeak(AudioFixtures.load(it), random.nextDouble(-25.0, -10.0)) }
        val gaps = pieces.map { (random.nextDouble(0.3, 3.0) * AudioFixtures.SAMPLE_RATE).toInt() }
        val totalSamples = AudioFixtures.SAMPLE_RATE + pieces.sumOf { it.size } + gaps.sum()
        val background = AudioFixtures.noise(totalSamples, -55.0, 11, Noise.PINK)
        val stream = ShortArray(totalSamples) { background[it].toInt().toShort() }
        var position = AudioFixtures.SAMPLE_RATE
        for ((i, piece) in pieces.withIndex()) {
            for (j in piece.indices) stream[position + j] = (stream[position + j] + piece[j]).coerceIn(-32768, 32767).toShort()
            position += piece.size + gaps[i]
        }
        val matcher = config.matcher().apply { loadTemplates(config.templates) }
        val detector = newDetector(config, matcher)
        var activations = 0
        for (frame in AudioFixtures.frames(stream)) for (event in detector.processFrame(frame)) {
            if (event is MemoDetectionEvent.TriggerStarted) activations++
            if (event is MemoDetectionEvent.RecordingCompleted && event.stopReason.trimsStopPhrase) activations++
        }
        val speech = pieces.sumOf { it.size }.toDouble() / AudioFixtures.SAMPLE_RATE
        return Triple(activations, speech, totalSamples.toDouble() / AudioFixtures.SAMPLE_RATE)
    }

    /** Matcher time per 50 ms frame on speech (host JVM), after a warm-up. */
    fun msPerFrame(config: Config): Double {
        val frames = AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load("dev_conv_1"), -15.0), 20.0, 1.0))
        val matcher = config.matcher().apply { loadTemplates(config.templates) }
        frames.take(100).forEach { matcher.processFrame(it, 0f, 0f) }
        val start = System.nanoTime()
        repeat(3) { frames.forEach { matcher.processFrame(it, 0f, 0f) } }
        return (System.nanoTime() - start) / 1e6 / (3 * frames.size)
    }

    fun evaluate(split: Split, config: Config = Config(), withContinuous: Boolean = true): Report {
        val trials = mutableListOf<Trial>()
        var seed = if (split === TEST) 9000 else 7000
        for ((noiseDbfs, noise) in BACKGROUNDS) for (peak in PEAKS) {
            for (clip in split.positives) trials += trial(config, clip, true, noiseDbfs, noise, peak, seed++)
            for (clip in split.negatives) trials += trial(config, clip, false, noiseDbfs, noise, peak, seed++)
        }
        val (activations, speech, stream) = if (withContinuous) continuous(config, split) else Triple(0, Double.NaN, Double.NaN)
        return Report(split, trials, activations, speech, stream, if (withContinuous) msPerFrame(config) else Double.NaN)
    }
}
