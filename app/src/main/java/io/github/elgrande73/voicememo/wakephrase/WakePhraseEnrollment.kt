package io.github.elgrande73.voicememo.wakephrase

import io.github.elgrande73.voicememo.audio.PcmFrameAssembler
import kotlin.math.log10

/**
 * Validation of one wake-phrase sample recorded in Settings, before it is added to the enrolled samples.
 *
 * Rejected: unusable audio only — silence, clipping, a phrase that is too short or too long, malformed frames, and an
 * exact duplicate of an enrolled sample. Natural differences in pace, pitch, distance or volume are deliberately NOT
 * rejected: they are what makes several samples useful.
 */
object WakePhraseEnrollment {

    /** Measurements of an accepted sample (stored as metadata; no audio). */
    data class SampleInfo(val phraseDurationMs: Long, val peakDbfs: Float, val noiseFloorDbfs: Float)

    enum class Reason(val message: String) {
        INVALID_AUDIO("The recording could not be processed. Please try again."),
        SILENT("Nothing was heard. Speak the wake phrase at your normal volume."),
        TOO_SHORT("The phrase was too short. Say the whole wake phrase."),
        TOO_LONG("The recording contains more than the wake phrase, or the background is too loud. Record in a quieter place and say only the phrase."),
        CLIPPED("The recording was too loud (distorted). Hold the phone a little further away."),
        DUPLICATE("This sample is identical to one you already enrolled.")
    }

    sealed interface Result {
        data class Accepted(val templateBytes: ByteArray, val info: SampleInfo) : Result
        data class Rejected(val reason: Reason) : Result
    }

    const val MIN_PHRASE_MS = 300L
    const val MAX_PHRASE_MS = 2_000L
    const val SILENCE_PEAK_DBFS = -50f
    /** More than 1 % clipped samples within the phrase. */
    const val MAX_CLIPPED_RATIO = 0.01
    /** Mean DTW frame distance below which two samples are treated as the same recording. */
    const val DUPLICATE_DISTANCE = 0.02f

    fun validate(
        frames: List<ShortArray>,
        frontEnd: FrontEndConfig = FrontEndConfig.DEFAULT,
        existingSamples: List<ByteArray> = emptyList()
    ): Result {
        if (frames.isEmpty() || frames.any { it.size != PcmFrameAssembler.DEFAULT_FRAME_SIZE }) {
            return Result.Rejected(Reason.INVALID_AUDIO)
        }
        val analysis = try {
            TemplateKeywordDetector.analyzeRecording(frames, frontEnd)
        } catch (e: RuntimeException) {
            return Result.Rejected(Reason.INVALID_AUDIO)
        }
        val peakDbfs = frames.maxOf { frame ->
            val peak = frame.maxOf { kotlin.math.abs(it.toInt()) }
            (20 * log10(maxOf(peak, 1) / 32768.0)).toFloat()
        }
        if (peakDbfs < SILENCE_PEAK_DBFS) return Result.Rejected(Reason.SILENT)
        val span = analysis.speechSpan ?: return Result.Rejected(Reason.TOO_SHORT)

        val durationMs = (span.last - span.first + 1) * FRAME_MS
        if (durationMs < MIN_PHRASE_MS) return Result.Rejected(Reason.TOO_SHORT)
        if (durationMs > MAX_PHRASE_MS) return Result.Rejected(Reason.TOO_LONG)
        val clipped = (span.first..span.last).sumOf { analysis.clippedSamples[it] }
        if (clipped > MAX_CLIPPED_RATIO * (span.last - span.first + 1) * PcmFrameAssembler.DEFAULT_FRAME_SIZE) {
            return Result.Rejected(Reason.CLIPPED)
        }

        val features = analysis.features.subList(span.first, span.last + 1).toList()
        for (existing in existingSamples) {
            val other = TemplateKeywordDetector.parseFeatures(existing) ?: continue
            if (other.size != features.size) continue
            val (cost, steps) = TemplateKeywordDetector.dtwDistance(other, features)
            if (cost / steps < DUPLICATE_DISTANCE) return Result.Rejected(Reason.DUPLICATE)
        }
        return Result.Accepted(
            TemplateKeywordDetector.serialize(features),
            SampleInfo(durationMs, peakDbfs, analysis.noiseFloorDbfs)
        )
    }

    private const val FRAME_MS = 50L
}
