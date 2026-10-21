package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.AudioFixtures.Noise
import io.github.elgrande73.voicememo.wakephrase.FrontEndConfig
import io.github.elgrande73.voicememo.wakephrase.WakePhraseFrontEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class WakePhraseFrontEndTest {

    private fun dbfs(frame: ShortArray): Double {
        val rms = sqrt(frame.sumOf { it.toDouble() * it } / frame.size) / 32768.0
        return 20 * kotlin.math.log10(rms.coerceAtLeast(1e-9))
    }

    @Test
    fun noiseFloorIsEstimatedFromTheSignal() {
        val frontEnd = WakePhraseFrontEnd()
        AudioFixtures.frames(AudioFixtures.scene(ShortArray(0), 2.0, 0.0, noiseDbfs = -50.0)).forEach { frontEnd.process(it) }
        assertEquals(-50.0, frontEnd.noiseFloorDbfs!!.toDouble(), 3.0)
    }

    @Test
    fun backgroundIsAttenuatedButQuietSpeechIsPreserved() {
        val frontEnd = WakePhraseFrontEnd()
        val phrase = AudioFixtures.atPeak(AudioFixtures.load("wake_phrase_a"), -30.0) // quiet speech
        val frames = AudioFixtures.frames(AudioFixtures.scene(phrase, 4.0, 2.5, noiseDbfs = -55.0))
        val speechStart = (2.5 * 16000 / 800).toInt()
        val backgroundSuppression = mutableListOf<Float>()
        val speechSuppression = mutableListOf<Float>()
        frames.forEachIndexed { i, frame ->
            frontEnd.process(frame)
            val inSpeech = i in speechStart until speechStart + phrase.size / 800 && dbfs(frame) > -45
            if (i in 20 until speechStart) backgroundSuppression += frontEnd.diagnostics.suppressionDb
            if (inSpeech) speechSuppression += frontEnd.diagnostics.suppressionDb
        }
        println("MEASURE suppression: background mean %.1f dB, speech mean %.1f dB".format(backgroundSuppression.average(), speechSuppression.average()))
        assertTrue("background only ${backgroundSuppression.average()} dB", backgroundSuppression.average() < -10.0)
        assertTrue("speech attenuated ${speechSuppression.average()} dB", speechSuppression.average() > -3.0)
    }

    @Test
    fun bypassLeavesTheSpectrumUntouched() {
        val frontEnd = WakePhraseFrontEnd(FrontEndConfig.BYPASS)
        AudioFixtures.frames(AudioFixtures.scene(ShortArray(0), 2.0, 0.0, noiseDbfs = -50.0)).forEach {
            frontEnd.process(it)
            assertEquals(0f, frontEnd.diagnostics.suppressionDb, 0.001f)
        }
    }

    @Test
    fun featuresAreNormalisedAndFiniteForSilenceClippingAndOddFrameSizes() {
        val frontEnd = WakePhraseFrontEnd()
        val inputs = listOf(
            ShortArray(800),                                   // digital silence
            ShortArray(800) { if (it % 2 == 0) 32767 else -32768 }, // full-scale, clipped
            ShortArray(320) { (it * 37 % 2000 - 1000).toShort() },  // shorter frame (< FFT size)
            ShortArray(800) { 1 }                              // DC offset only
        )
        for (frame in inputs) {
            val features = frontEnd.process(frame)
            assertEquals(WakePhraseFrontEnd.NUM_BANDS, features.size)
            assertTrue(features.all { it.isFinite() && it >= 0f })
            assertEquals(1.0, sqrt(features.sumOf { it.toDouble() * it }), 1e-4)
        }
        frontEnd.process(inputs[1])
        assertTrue(frontEnd.diagnostics.clippedSamples > 0)
        frontEnd.process(inputs[0])
        assertTrue(frontEnd.diagnostics.nearSilence)
    }

    @Test
    fun resetRestoresTheInitialState() {
        val frames = AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load("other_speech"), -20.0), 3.0, 1.0, -50.0, Noise.WHITE))
        val first = WakePhraseFrontEnd().let { fe -> frames.map { fe.process(it) } }
        val reused = WakePhraseFrontEnd().apply { frames.forEach { process(it) }; reset() }
        val second = frames.map { reused.process(it) }
        first.indices.forEach { i -> first[i].indices.forEach { b -> assertEquals(first[i][b], second[i][b], 0f) } }
    }

    @Test
    fun processingIsFastEnoughForRealTime() {
        val frontEnd = WakePhraseFrontEnd()
        val frames = AudioFixtures.frames(AudioFixtures.scene(AudioFixtures.atPeak(AudioFixtures.load("wake_phrase_a"), -20.0), 10.0, 2.0, -50.0))
        repeat(2) { frames.forEach { frontEnd.process(it) } } // JIT warm-up
        val start = System.nanoTime()
        frames.forEach { frontEnd.process(it) }
        val perFrameMicros = (System.nanoTime() - start) / frames.size / 1000.0
        println("MEASURE front end: %.0f µs per 50 ms frame (JVM)".format(perFrameMicros))
        // Budget: a small fraction of the 50 ms frame period
        assertTrue("$perFrameMicros µs per frame", perFrameMicros < 5_000)
        assertTrue(abs(frontEnd.diagnostics.processingNanos) > 0)
    }
}
