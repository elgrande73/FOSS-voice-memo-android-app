package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.wakephrase.NoiseFloorEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.pow
import kotlin.random.Random

class NoiseFloorEstimatorTest {

    private val random = Random(5)

    /** One frame of power fluctuating ±1.5 dB around [db] dBFS (like real background noise). */
    private fun frame(db: Double) = doubleArrayOf(10.0.pow((db + random.nextDouble(-1.5, 1.5)) / 10))

    private fun NoiseFloorEstimator.db() = 10 * log10(estimate(0))

    private fun NoiseFloorEstimator.feed(db: Double, frames: Int) = repeat(frames) { update(frame(db)) }

    @Test
    fun convergesToTheBackgroundLevelWithinHalfASecond() {
        val estimator = NoiseFloorEstimator(1)
        estimator.feed(-55.0, 10) // 0.5 s
        assertEquals(-55.0, estimator.db(), 3.0)
    }

    @Test
    fun speechDoesNotPermanentlyRaiseTheEstimate() {
        val estimator = NoiseFloorEstimator(1)
        estimator.feed(-60.0, 40)
        estimator.feed(-25.0, 40) // 2 s of loud speech, 35 dB above the background
        assertTrue("rose to ${estimator.db()}", estimator.db() < -60.0 + 2.0 * 2 + 3) // ≤ 2 dB/s plus fluctuation
        estimator.feed(-60.0, 10) // 0.5 s of background again
        assertEquals(-60.0, estimator.db(), 3.0)
    }

    @Test
    fun loudTransientHasNoLastingEffect() {
        val estimator = NoiseFloorEstimator(1)
        estimator.feed(-60.0, 40)
        estimator.feed(-5.0, 2) // door slam
        estimator.feed(-60.0, 6)
        assertEquals(-60.0, estimator.db(), 3.0)
    }

    @Test
    fun followsALouderEnvironmentWithinSeconds() {
        val estimator = NoiseFloorEstimator(1)
        estimator.feed(-60.0, 40)
        estimator.feed(-45.0, 200) // a fan is switched on: 15 dB louder for 10 s
        assertEquals(-45.0, estimator.db(), 3.0)
    }

    @Test
    fun followsAQuieterEnvironmentQuickly() {
        val estimator = NoiseFloorEstimator(1)
        estimator.feed(-40.0, 40)
        estimator.feed(-60.0, 20) // 1 s
        assertEquals(-60.0, estimator.db(), 3.0)
    }

    @Test
    fun ignoresDigitalSilenceAndInvalidValues() {
        val estimator = NoiseFloorEstimator(2)
        repeat(20) { estimator.update(doubleArrayOf(1e-6, 1e-6)) }
        val before = estimator.estimate(0)
        assertFalse(estimator.update(doubleArrayOf(0.0, 0.0)))
        assertFalse(estimator.update(doubleArrayOf(Double.NaN, 1e-6)))
        assertFalse(estimator.update(doubleArrayOf(-1.0, 1e-6)))
        assertEquals(before, estimator.estimate(0), 0.0)
    }

    @Test
    fun resetStartsOver() {
        val estimator = NoiseFloorEstimator(1)
        estimator.feed(-40.0, 40)
        estimator.reset()
        assertEquals(0, estimator.framesObserved)
        estimator.feed(-70.0, 10)
        assertEquals(-70.0, estimator.db(), 3.0)
    }
}
