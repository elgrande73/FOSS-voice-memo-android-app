package io.github.elgrande73.voicememo.wakephrase

import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.MemoDetectionEvent
import io.github.elgrande73.voicememo.detection.MemoTriggerDetector

/**
 * Result of evaluating a recording with the monitoring detection pipeline.
 *
 * @param bestConfidence Highest raw match score (0.0..1.0, see [TemplateKeywordDetector] for its meaning) the phrase
 *   reaches. It does not depend on the threshold: monitoring stops scoring at the moment it triggers, so the score is
 *   taken from a scoring pass that never triggers.
 * @param wouldTrigger Whether monitoring with the same settings would have started a recording.
 * @param peakLevelDb Loudest frame on the VAD dB scale (dBFS + 90), comparable with the activation threshold.
 */
data class WakePhraseEvaluation(val bestConfidence: Float, val wouldTrigger: Boolean, val peakLevelDb: Float)

/**
 * Evaluates recorded audio exactly as live monitoring would: the frames are streamed, in order, through the same
 * front end and matcher, and the trigger decision comes from a [MemoTriggerDetector] in Wake Phrase mode with the same
 * template, match confidence threshold and activation (loudness) threshold. The Settings phrase test uses this, so its
 * result comes from the same preprocessing, matcher and confidence interpretation as monitoring.
 *
 * Difference from monitoring by design: before scoring, the noise floor is primed with the same recording, because a
 * 3 s recording is too short for the adaptive estimator to converge on its own, whereas during monitoring it has been
 * running for a long time.
 */
object WakePhraseEvaluator {

    fun evaluate(
        frames: List<ShortArray>,
        templateBytes: ByteArray?,
        matchConfidenceThreshold: Float,
        activationThresholdDb: Float,
        frontEndConfig: FrontEndConfig = FrontEndConfig.DEFAULT
    ): WakePhraseEvaluation {
        fun newMatcher() = TemplateKeywordDetector(frontEndConfig = frontEndConfig).apply {
            if (templateBytes != null) loadTemplate(templateBytes)
        }

        // Scoring pass: the matcher alone, every frame in order, never reset by a trigger
        val scorer = newMatcher().apply { primeNoiseFloor(frames) }
        var best = 0f
        for (frame in frames) best = maxOf(best, scorer.processFrame(frame, 0f, 0f).confidence)

        // Decision pass: the monitoring state machine, including the loudness criterion
        val matcher = newMatcher()
        val detector = MemoTriggerDetector(keywordDetector = matcher).apply {
            detectionMode = DetectionMode.KEYWORD_ONLY
            thresholdDb = activationThresholdDb
            this.matchConfidenceThreshold = matchConfidenceThreshold
            reset(startMonitoring = true)
        }
        matcher.primeNoiseFloor(frames)
        var triggered = false
        var peakDb = 0f
        for (frame in frames) {
            if (detector.processFrame(frame).any { it is MemoDetectionEvent.TriggerStarted }) triggered = true
            peakDb = maxOf(peakDb, levelDb(frame))
        }
        return WakePhraseEvaluation(best, triggered, peakDb)
    }

    private fun levelDb(frame: ShortArray): Float {
        if (frame.isEmpty()) return 0f
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        val rms = kotlin.math.sqrt(sum / frame.size) / 32767.0
        return (20.0 * kotlin.math.log10(rms.coerceAtLeast(1e-5)) + 90.0).toFloat().coerceIn(0f, 120f)
    }
}
