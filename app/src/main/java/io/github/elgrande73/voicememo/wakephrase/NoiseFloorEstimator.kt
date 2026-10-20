package io.github.elgrande73.voicememo.wakephrase

import kotlin.math.pow

/**
 * Adaptive background-noise estimator for a set of power values (one per spectral band, or a single broadband value),
 * updated once per audio frame (50 ms in this app). It needs no user configuration.
 *
 * Algorithm (a simplified minimum-tracking estimator):
 * - **Start-up**: during the first [INIT_FRAMES] valid frames the estimate is the minimum observed power, so it
 *   converges within 0.5 s even if speech is present at start.
 * - **Falling**: when the power drops below the estimate, the estimate follows quickly (it closes [FALL_RATE] of the
 *   gap in dB per frame, about 0.2 s time constant), so quieter conditions are tracked within about a second.
 * - **Noise-like frames** (less than [NOISE_MARGIN] = 6 dB above the estimate) pull the estimate up slowly
 *   ([NOISE_RISE_RATE] of the gap in dB per frame, about 1 s time constant).
 * - Interpolation happens in the log (dB) domain, so large changes are tracked at the same rate as small ones.
 * - **Foreground frames** (speech, transients; 6 dB or more above the estimate) can raise the estimate by at most
 *   [MAX_RISE_DB_PER_SECOND] dB/s. A 2 s utterance therefore moves it by at most 4 dB and a door slam has no lasting
 *   effect, while a genuinely louder environment (e.g. a fan switched on) is followed within seconds.
 * - Exact digital silence (power 0, e.g. muted frames or AudioRecord start-up) is ignored.
 *
 * The estimate is bounded to [MIN_POWER]..1.0 (full scale).
 */
class NoiseFloorEstimator(private val size: Int) {

    private val estimate = DoubleArray(size)
    private var validFrames = 0

    /** Number of frames that have contributed to the estimate since the last [reset]. */
    val framesObserved: Int get() = validFrames

    fun reset() {
        estimate.fill(0.0)
        validFrames = 0
    }

    /** Current estimate for [index] (power, same unit as the input), or 0 before the first valid frame. */
    fun estimate(index: Int): Double = estimate[index]

    /**
     * Updates the estimates with one frame of [power] values. Returns false (without changing anything) when the frame
     * is digital silence or contains invalid values.
     */
    fun update(power: DoubleArray): Boolean {
        var total = 0.0
        for (i in 0 until size) {
            val p = power[i]
            if (p.isNaN() || p < 0.0 || p.isInfinite()) return false
            total += p
        }
        if (total <= 0.0) return false

        if (validFrames < INIT_FRAMES) {
            for (i in 0 until size) {
                val p = power[i].coerceIn(MIN_POWER, 1.0)
                estimate[i] = if (validFrames == 0) p else minOf(estimate[i], p)
            }
            validFrames++
            return true
        }

        for (i in 0 until size) {
            val p = power[i].coerceIn(MIN_POWER, 1.0)
            val current = estimate[i]
            estimate[i] = when {
                p <= current -> current * (p / current).pow(FALL_RATE)
                p < current * NOISE_MARGIN -> current * (p / current).pow(NOISE_RISE_RATE)
                else -> minOf(p, current * MAX_RISE_PER_FRAME)
            }.coerceIn(MIN_POWER, 1.0)
        }
        validFrames++
        return true
    }

    companion object {
        const val INIT_FRAMES = 10
        const val FALL_RATE = 0.2
        const val NOISE_RISE_RATE = 0.05
        const val MAX_RISE_DB_PER_SECOND = 2.0
        const val FRAME_SECONDS = 0.05
        const val MIN_POWER = 1e-14

        /** 6 dB in power. */
        const val NOISE_MARGIN = 3.981

        val MAX_RISE_PER_FRAME: Double = 10.0.pow(MAX_RISE_DB_PER_SECOND * FRAME_SECONDS / 10.0)
    }
}
