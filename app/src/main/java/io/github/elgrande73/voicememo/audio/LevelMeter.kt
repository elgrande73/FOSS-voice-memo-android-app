package io.github.elgrande73.voicememo.audio

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Display value for the live microphone level, fed with every captured 50 ms frame.
 *
 * Level: RMS of the frame in dBFS (0 dBFS = full scale; a digital level, not sound pressure). The display rises
 * immediately and falls by at most [releaseDbPerFrame] per frame (30 dB/s by default), so short peaks stay visible
 * without the needle jumping. A value is published every [publishEveryFrames] frames (10 times per second by default).
 * Pure Kotlin and allocation-free; called on the capture thread.
 */
class LevelMeter(
    private val publishEveryFrames: Int = 2,
    private val releaseDbPerFrame: Float = 1.5f
) {
    private var display = SILENCE_DBFS
    private var frames = 0

    fun reset() {
        display = SILENCE_DBFS
        frames = 0
    }

    /** Updates the meter; returns the value to publish, or null if this frame is not a publishing frame. */
    fun update(frame: ShortArray): Float? {
        val level = frameDbfs(frame)
        display = if (level >= display) level else maxOf(level, display - releaseDbPerFrame)
        frames++
        return if (frames % publishEveryFrames == 0) display else null
    }

    companion object {
        /** Floor of the scale: digital silence and anything quieter. */
        const val SILENCE_DBFS = -90f

        fun frameDbfs(frame: ShortArray): Float {
            if (frame.isEmpty()) return SILENCE_DBFS
            var sum = 0.0
            for (s in frame) sum += s.toDouble() * s
            val rms = sqrt(sum / frame.size) / 32768.0
            if (rms <= 0.0) return SILENCE_DBFS
            return (20.0 * log10(rms)).toFloat().coerceIn(SILENCE_DBFS, 0f)
        }
    }
}
