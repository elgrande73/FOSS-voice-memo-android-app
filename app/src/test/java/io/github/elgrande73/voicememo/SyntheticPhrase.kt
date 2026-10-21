package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.wakephrase.FrontEndConfig
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import io.github.elgrande73.voicememo.wakephrase.WakePhraseFrontEnd
import kotlin.math.sin

/**
 * Deterministic synthetic test signal: a 23-frame (1.15 s) sequence of tone and formant segments (fricative-like
 * burst, vowels, a short dip, a nasal murmur, a stop transient, a final vowel), 16 kHz, 800 samples per frame. It is
 * not speech; tests use it where an exactly repeatable "phrase" is needed (threshold mechanics, persistence, legacy
 * template formats), enrolled as a sample. Speech tests use the eSpeak NG fixtures instead.
 */
object SyntheticPhrase {
    /** Features of [generate] as a sample template (same front end as monitoring, every frame, no endpointing). */
    fun template(): ByteArray {
        val frontEnd = WakePhraseFrontEnd(FrontEndConfig.DEFAULT)
        return TemplateKeywordDetector.serialize(generate().map { frontEnd.process(it) })
    }

    /** A matcher with the synthetic phrase enrolled as its only sample. */
    fun detector(): TemplateKeywordDetector = TemplateKeywordDetector().apply { check(loadTemplate(template())) }

    fun generate(
        sampleRate: Int = 16000,
        amplitude: Double = 12000.0
    ): List<ShortArray> {
        val chunks = mutableListOf<ShortArray>()
        val frameSize = 800 // 50ms

        for (f in 0 until 23) {
            val chunk = ShortArray(frameSize)
            for (i in 0 until frameSize) {
                val t = (f * frameSize + i).toDouble() / sampleRate
                val sample = when (f) {
                    in 0..2 -> { // high frequency friction
                        val n1 = sin(2.0 * Math.PI * 3800.0 * t) * 0.5
                        val n2 = sin(2.0 * Math.PI * 5200.0 * t) * 0.5
                        (n1 + n2) * amplitude * 0.75
                    }
                    in 3..6 -> { // F1 750Hz, F2 1300Hz, F0 130Hz
                        val f0 = sin(2.0 * Math.PI * 130.0 * t) * 0.3
                        val f1 = sin(2.0 * Math.PI * 750.0 * t) * 0.4
                        val f2 = sin(2.0 * Math.PI * 1300.0 * t) * 0.3
                        (f0 + f1 + f2) * amplitude
                    }
                    in 7..9 -> { // F1 500Hz, F2 900Hz, F0 125Hz
                        val f0 = sin(2.0 * Math.PI * 125.0 * t) * 0.3
                        val f1 = sin(2.0 * Math.PI * 500.0 * t) * 0.5
                        val f2 = sin(2.0 * Math.PI * 900.0 * t) * 0.2
                        (f0 + f1 + f2) * amplitude * 0.9
                    }
                    10 -> { // Brief transition
                        val f0 = sin(2.0 * Math.PI * 120.0 * t) * 0.2
                        f0 * amplitude * 0.3
                    }
                    in 11..13 -> { // F1 380Hz, F2 2100Hz, F0 135Hz
                        val f0 = sin(2.0 * Math.PI * 135.0 * t) * 0.3
                        val f1 = sin(2.0 * Math.PI * 380.0 * t) * 0.4
                        val f2 = sin(2.0 * Math.PI * 2100.0 * t) * 0.3
                        (f0 + f1 + f2) * amplitude * 0.95
                    }
                    in 14..16 -> { // F1 280Hz nasal murmur
                        val f0 = sin(2.0 * Math.PI * 130.0 * t) * 0.4
                        val f1 = sin(2.0 * Math.PI * 280.0 * t) * 0.6
                        (f0 + f1) * amplitude * 0.7
                    }
                    in 17..18 -> { // release transient
                        val f0 = sin(2.0 * Math.PI * 2200.0 * t) * 0.7
                        f0 * amplitude * 0.6
                    }
                    else -> { // final vowel
                        val f0 = sin(2.0 * Math.PI * 120.0 * t) * 0.3
                        val f1 = sin(2.0 * Math.PI * 700.0 * t) * 0.4
                        val f2 = sin(2.0 * Math.PI * 1250.0 * t) * 0.3
                        (f0 + f1 + f2) * amplitude * 0.9
                    }
                }
                chunk[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            chunks.add(chunk)
        }
        return chunks
    }
}
