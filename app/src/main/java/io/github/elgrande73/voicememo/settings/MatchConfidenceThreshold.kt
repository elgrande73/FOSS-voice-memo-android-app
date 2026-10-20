package io.github.elgrande73.voicememo.settings

/**
 * The single, user-facing wake-phrase "Match confidence threshold".
 *
 * Detector semantics: [io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector] scores every
 * candidate window with a confidence in 0.0..1.0, derived from the normalized DTW distance as
 * `confidence = 1 - distance / 0.40`. A wake phrase is detected when `confidence >= threshold`.
 * The user-facing percentage therefore maps 1:1 onto that scale: `threshold = percent / 100`.
 */
object MatchConfidenceThreshold {
    const val MIN_PERCENT = 20
    const val MAX_PERCENT = 80

    /**
     * Default for fresh installations. Values below 50 % accept clearly weaker matches and, on the evaluation
     * fixtures, many more false activations (docs/WAKE_PHRASE_DETECTION.md, "Threshold sweep").
     */
    const val DEFAULT_PERCENT = 50

    /** Out-of-range values are clamped to the nearest supported bound. */
    fun clampPercent(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** Converts the stored percentage into the detector's 0.0..1.0 confidence scale. */
    fun toDetectorConfidence(percent: Int): Float = clampPercent(percent) / 100f
}
