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
    const val MIN_PERCENT = 50
    const val MAX_PERCENT = 80

    /**
     * Default for fresh installations. The previous default (keyword sensitivity 0.60) corresponded to an
     * effective confidence threshold of 40%, which is outside the supported 50%–80% range, so the closest
     * valid value (50%) is used to keep recognition behavior as close as possible to before.
     */
    const val DEFAULT_PERCENT = 50

    /** Out-of-range values are clamped to the nearest supported bound. */
    fun clampPercent(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** Converts the stored percentage into the detector's 0.0..1.0 confidence scale. */
    fun toDetectorConfidence(percent: Int): Float = clampPercent(percent) / 100f
}
