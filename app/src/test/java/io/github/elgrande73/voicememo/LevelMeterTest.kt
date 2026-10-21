package io.github.elgrande73.voicememo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.elgrande73.voicememo.audio.LevelMeter
import io.github.elgrande73.voicememo.monitoring.MonitoringEngineState
import io.github.elgrande73.voicememo.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LevelMeterTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun tone(dbfsRms: Double) = ShortArray(800) {
        (sin(2 * PI * 440 * it / 16000) * 32768 * Math.pow(10.0, dbfsRms / 20) * Math.sqrt(2.0)).toInt().toShort()
    }

    @Test
    fun measuresRmsInDbfsAndHandlesSilence() {
        assertEquals(LevelMeter.SILENCE_DBFS, LevelMeter.frameDbfs(ShortArray(800)), 0f)
        assertEquals(LevelMeter.SILENCE_DBFS, LevelMeter.frameDbfs(ShortArray(0)), 0f)
        assertEquals(-20f, LevelMeter.frameDbfs(tone(-20.0)), 0.3f)
        assertEquals(-50f, LevelMeter.frameDbfs(tone(-50.0)), 0.3f)
        assertTrue(LevelMeter.frameDbfs(ShortArray(800) { Short.MAX_VALUE }) <= 0f)
    }

    @Test
    fun publishesTenTimesPerSecondRisesAtOnceAndFallsGradually() {
        val meter = LevelMeter()
        val published = (0 until 20).map { meter.update(tone(-60.0)) } // one second of frames
        assertEquals(10, published.count { it != null })
        assertNull(published[0])
        meter.update(tone(-20.0))
        assertEquals(-20f, meter.update(tone(-20.0))!!, 0.3f) // immediate rise
        // Then silence: falls by 1.5 dB per frame (30 dB/s), not to the floor at once
        meter.update(ShortArray(800))
        assertEquals(-23f, meter.update(ShortArray(800))!!, 0.4f)
        repeat(100) { meter.update(ShortArray(800)) }
        assertEquals(LevelMeter.SILENCE_DBFS, meter.update(ShortArray(800)) ?: meter.update(ShortArray(800))!!, 0f)
        meter.reset()
        assertNull(meter.update(tone(-30.0)))
    }

    @Test
    fun cardShowsDbfsAndNoStaleValueWithoutInput() {
        var level by androidx.compose.runtime.mutableStateOf<Float?>(-41.6f)
        composeTestRule.setContent {
            MyApplicationTheme {
                LiveLevelCard(level, -63f, -50f, "Wake-phrase minimum", MonitoringEngineState.ACTIVE)
            }
        }
        composeTestRule.onNodeWithTag("live_level_value").assertTextEquals("-42 dBFS")
        composeTestRule.onNodeWithTag("live_level_marker").assertTextEquals("Wake-phrase minimum: -50 dBFS")
        composeTestRule.onNodeWithTag("live_level_status").assertTextEquals("Noise floor: -63 dBFS")
        composeTestRule.runOnIdle { level = null }
        composeTestRule.onNodeWithTag("live_level_value").assertTextEquals("—")
        composeTestRule.onNodeWithTag("live_level_status").assertTextEquals("No live input while monitoring is off")
    }
}
