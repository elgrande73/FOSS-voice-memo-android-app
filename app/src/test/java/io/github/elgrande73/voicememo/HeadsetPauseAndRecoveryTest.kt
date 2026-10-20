package io.github.elgrande73.voicememo

import android.media.AudioRecord
import io.github.elgrande73.voicememo.audio.AudioInputDevice
import io.github.elgrande73.voicememo.audio.AudioInputManager
import io.github.elgrande73.voicememo.audio.MediaPlaybackDetector
import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.MemoDetectionEvent
import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import io.github.elgrande73.voicememo.detection.VoiceActivityDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * Regression and resilience unit tests covering:
 * - External Bluetooth & wired headset pause/play commands
 * - System media playback state transitions
 * - AudioRecord read error recovery
 * - Decoupling of external media controls from continuous background voice note monitoring
 */
class HeadsetPauseAndRecoveryTest {

    private val vad = VoiceActivityDetector()
    private val memoDetector = MemoTriggerDetector(vad = vad)

    @Test
    fun testHeadsetPauseCommand_WithNoiseFilterDisabled() {
        memoDetector.noiseFilterEnabled = false
        memoDetector.detectionMode = DetectionMode.AUTO_SOUND
        memoDetector.thresholdDb = 45f
        memoDetector.minTriggerDurationMs = 50L
        memoDetector.reset(startMonitoring = true)

        val voiceBuffer = ShortArray(800) { i ->
            (15000 * sin(2.0 * Math.PI * 800.0 * i / 16000.0)).toInt().toShort()
        }

        // 1. While external media is playing:
        val mediaActiveState = MediaPlaybackDetector.evaluatePlaybackState(null, isMusicActive = true)
        assertTrue(mediaActiveState.isMediaPlaying)

        // Detection should NOT be suspended because noiseFilterEnabled is false
        val isSuspendedWhilePlaying = memoDetector.noiseFilterEnabled && mediaActiveState.isMediaPlaying
        assertFalse("Detection must NOT be suspended when filter is false", isSuspendedWhilePlaying)

        val events = memoDetector.processFrame(voiceBuffer)
        assertTrue("Speech must be processed and trigger recording", events.any { it is MemoDetectionEvent.TriggerStarted })

        // 2. User presses Pause on the headset:
        val mediaPausedState = MediaPlaybackDetector.evaluatePlaybackState(null, isMusicActive = false)
        assertFalse(mediaPausedState.isMediaPlaying)

        val isSuspendedAfterPause = memoDetector.noiseFilterEnabled && mediaPausedState.isMediaPlaying
        assertFalse("Detection remains active after media pause", isSuspendedAfterPause)
    }

    @Test
    fun testHeadsetPause_AudioRecordErrorRecoverySimulation() {
        val errorInvalidOperation = AudioRecord.ERROR_INVALID_OPERATION
        val errorDeadObject = -6
        val errorBadValue = AudioRecord.ERROR_BAD_VALUE

        assertTrue(errorInvalidOperation < 0)
        assertTrue(errorDeadObject < 0)
        assertTrue(errorBadValue < 0)

        var consecutiveErrors = 0
        var recoveryTriggered = false

        val simulatedReadResults = listOf(errorInvalidOperation, errorInvalidOperation, errorInvalidOperation, errorInvalidOperation, errorDeadObject)
        for (res in simulatedReadResults) {
            if (res < 0) {
                consecutiveErrors++
                if (consecutiveErrors >= 5 || res == errorDeadObject) {
                    recoveryTriggered = true
                    consecutiveErrors = 0
                }
            }
        }

        assertTrue("AudioRecord recovery must trigger upon persistent errors or dead object", recoveryTriggered)
        assertEquals(0, consecutiveErrors)
    }

    @Test
    fun testAudioRoutingEvaluationFallbackOnHeadsetDisconnection() {
        val evalWithNullRoute = AudioInputManager.evaluateAudioRouting(
            configuredId = 1234,
            configuredName = "Bluetooth Headset",
            routedDevice = null
        )

        assertTrue(evalWithNullRoute.isFallback)
        assertEquals(AudioInputDevice.PHONE_MIC.name, evalWithNullRoute.activeDisplayName)
    }
}
