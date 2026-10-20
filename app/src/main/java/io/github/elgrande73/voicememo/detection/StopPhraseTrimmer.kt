package io.github.elgrande73.voicememo.detection

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Removes the spoken stop phrase from the end of a memo that was ended by repeating the wake phrase.
 *
 * Timing model: the wake-phrase matcher reports a match at the newest frame (the recognition decision) together with
 * the length of the audio window that matched ([io.github.elgrande73.voicememo.wakephrase.KeywordMatchResult.windowFrames]).
 * Recording stops at that frame, so audio after the decision is never part of the memo; the phrase itself began
 * `windowFrames` frames earlier — that is the recognition latency, which is why simply stopping at the decision would
 * keep (most of) the spoken command.
 *
 * Algorithm:
 * 1. Estimated phrase start = decision frame − windowFrames + 1.
 * 2. Refinement: people usually pause briefly before a command. Within a few frames around the estimate the quietest
 *    frame is searched; if it is clearly quieter than the phrase ([PAUSE_MARGIN_DB]), the cut moves to just after that
 *    pause, so neither the last memo word nor the beginning of the command is kept by mistake.
 * 3. The cut never reaches into the audio before [minKeepFrames] (pre-trigger audio and the start phrase).
 * 4. A short linear fade-out ([FADE_SAMPLES], 10 ms) at the new end avoids an audible click.
 *
 * Frames are copied; the input is never modified, so a failure leaves the untrimmed memo available.
 */
object StopPhraseTrimmer {

    const val SEARCH_FRAMES_BEFORE = 2
    const val SEARCH_FRAMES_AFTER = 4
    const val PAUSE_MARGIN_DB = 6.0
    const val FADE_SAMPLES = 160

    data class Result(val frames: List<ShortArray>, val removedFrames: Int)

    fun trim(frames: List<ShortArray>, windowFrames: Int, minKeepFrames: Int): Result {
        if (frames.isEmpty() || windowFrames <= 0) return Result(frames, 0)
        val levels = DoubleArray(frames.size) { levelDb(frames[it]) }
        val decision = frames.size - 1
        val estimate = (decision - windowFrames + 1).coerceIn(0, decision)

        val phraseLevel = median((estimate..decision).map { levels[it] })
        val searchStart = (estimate - SEARCH_FRAMES_BEFORE).coerceAtLeast(0)
        val searchEnd = (estimate + SEARCH_FRAMES_AFTER).coerceAtMost(decision)
        val quietest = (searchStart..searchEnd).minByOrNull { levels[it] } ?: estimate
        val cut = if (levels[quietest] <= phraseLevel - PAUSE_MARGIN_DB) quietest + 1 else estimate

        val keep = cut.coerceIn(minKeepFrames.coerceAtLeast(0), frames.size)
        if (keep >= frames.size) return Result(frames, 0)
        val kept = frames.subList(0, keep).map { it.copyOf() }
        fadeOut(kept)
        return Result(kept, frames.size - keep)
    }

    private fun fadeOut(frames: List<ShortArray>) {
        var remaining = FADE_SAMPLES
        for (f in frames.indices.reversed()) {
            val frame = frames[f]
            for (i in frame.indices.reversed()) {
                if (remaining <= 0) return
                val gain = (FADE_SAMPLES - remaining).toDouble() / FADE_SAMPLES
                frame[i] = (frame[i] * gain).toInt().toShort()
                remaining--
            }
        }
    }

    private fun levelDb(frame: ShortArray): Double {
        if (frame.isEmpty()) return -120.0
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        return 20.0 * log10(max(sqrt(sum / frame.size) / 32768.0, 1e-6))
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return -120.0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
