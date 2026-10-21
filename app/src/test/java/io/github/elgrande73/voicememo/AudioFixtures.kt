package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.audio.PcmFrameAssembler
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic audio fixtures for wake-phrase tests. Speech comes from the `.pcm` files in `src/test/resources/audio`
 * (16 kHz mono 16-bit, synthetic speech, see the README there); noise is generated from fixed seeds.
 */
object AudioFixtures {
    const val SAMPLE_RATE = 16000

    /** Same voice as the enrollment phrase ("a"): two renditions of the wake phrase. */
    val WAKE_PHRASE_SAME_VOICE = listOf("wake_phrase_a", "wake_phrase_b")
    /** Speech that does not contain the wake phrase. */
    val OTHER_SPEECH = listOf("other_speech", "other_speech_en")

    enum class Noise { NONE, PINK, WHITE }

    fun load(name: String): ShortArray {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/audio/$name.pcm")) { "Missing fixture $name" }.readBytes()
        val samples = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return samples
    }

    /** Scales [samples] so that their peak is at [peakDbfs]. */
    fun atPeak(samples: ShortArray, peakDbfs: Double): ShortArray {
        val peak = samples.maxOf { abs(it.toInt()) }.toDouble()
        val gain = 32767 * 10.0.pow(peakDbfs / 20) / peak
        return ShortArray(samples.size) { (samples[it] * gain).roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    fun noise(sampleCount: Int, rmsDbfs: Double, seed: Int, type: Noise): DoubleArray {
        if (type == Noise.NONE) return DoubleArray(sampleCount)
        val random = Random(seed)
        var lowPass = 0.0
        val raw = DoubleArray(sampleCount) {
            val x = random.nextDouble() * 2 - 1
            if (type == Noise.WHITE) x else { lowPass = 0.7 * lowPass + 0.3 * x; lowPass }
        }
        val rms = sqrt(raw.sumOf { it * it } / sampleCount)
        val gain = 32767 * 10.0.pow(rmsDbfs / 20) / rms
        return DoubleArray(sampleCount) { raw[it] * gain }
    }

    /** [phrase] placed at [leadSeconds] inside [totalSeconds] of background noise. */
    fun scene(
        phrase: ShortArray,
        totalSeconds: Double,
        leadSeconds: Double,
        noiseDbfs: Double = -65.0,
        noise: Noise = Noise.PINK,
        seed: Int = 1
    ): ShortArray {
        val total = (totalSeconds * SAMPLE_RATE).toInt()
        val lead = (leadSeconds * SAMPLE_RATE).toInt()
        val background = noise(total, noiseDbfs, seed, noise)
        return ShortArray(total) { i ->
            val speech = if (i - lead in phrase.indices) phrase[i - lead].toDouble() else 0.0
            (speech + background[i]).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun frames(samples: ShortArray): List<ShortArray> = PcmFrameAssembler.frames(samples)

    /** Enrolls voice "a" like the Settings workflow: 2.5 s recording, phrase after 0.3 s, close to the microphone. */
    fun enrollTemplate(noiseDbfs: Double = -65.0, noise: Noise = Noise.PINK): ByteArray {
        val detector = TemplateKeywordDetector()
        val recording = scene(atPeak(load("wake_phrase_a"), -10.0), 2.5, 0.3, noiseDbfs, noise, seed = 1)
        check(detector.recordTemplate(frames(recording))) { "Enrollment failed" }
        return detector.getTemplateData()!!
    }
}
