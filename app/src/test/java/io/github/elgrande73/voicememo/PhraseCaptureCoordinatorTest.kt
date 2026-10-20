package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.audio.CaptureOutcome
import io.github.elgrande73.voicememo.audio.MicrophoneArbiter
import io.github.elgrande73.voicememo.audio.MicrophoneOwner
import io.github.elgrande73.voicememo.audio.MonitoringControl
import io.github.elgrande73.voicememo.audio.PhraseCaptureCoordinator
import io.github.elgrande73.voicememo.audio.PhraseCaptureKind
import io.github.elgrande73.voicememo.audio.PhraseCaptureState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhraseCaptureCoordinatorTest {

    /**
     * Simulates AudioMonitoringService: while running it owns the microphone; stop() releases it asynchronously after
     * [releaseDelayMs] (or never, to simulate a stuck shutdown).
     */
    private class FakeMonitoring(
        private val scope: CoroutineScope,
        private val arbiter: MicrophoneArbiter,
        var releaseDelayMs: Long = 200,
        var releases: Boolean = true
    ) : MonitoringControl {
        override var isRunning = false
        var starts = 0
        var stops = 0
        val log = mutableListOf<String>()

        fun startNow() {
            isRunning = true
            check(arbiter.tryAcquire(MicrophoneOwner.MONITORING))
        }

        override fun start() {
            starts++
            log += "monitoring started"
            isRunning = true
            // Like the service's capture loop: take the microphone if free, otherwise wait until it is released
            if (!arbiter.tryAcquire(MicrophoneOwner.MONITORING)) scope.launch {
                arbiter.awaitAvailableFor(MicrophoneOwner.MONITORING)
                if (isRunning) arbiter.tryAcquire(MicrophoneOwner.MONITORING)
            }
        }

        override fun stop() {
            stops++
            log += "monitoring stop requested"
            isRunning = false
            if (releases) scope.launch {
                delay(releaseDelayMs)
                arbiter.release(MicrophoneOwner.MONITORING)
                log += "monitoring released microphone"
            }
        }
    }

    private class Env(scope: TestScope) {
        val arbiter = MicrophoneArbiter()
        val monitoring = FakeMonitoring(scope.backgroundScope, arbiter)
        var permission = true
        val coordinator = PhraseCaptureCoordinator(arbiter, monitoring, { permission }, releaseTimeoutMs = 1_000)
    }

    @Test
    fun recordingStartsOnlyAfterMonitoringReleasedTheMicrophoneAndMonitoringIsRestored() = runTest {
        val env = Env(this)
        env.monitoring.startNow()

        val outcome = env.coordinator.runCapture(PhraseCaptureKind.WAKE_PHRASE) { session ->
            assertEquals("microphone must be ours", MicrophoneOwner.PHRASE_CAPTURE, env.arbiter.owner.value)
            assertFalse(env.monitoring.isRunning)
            env.monitoring.log += "recording started"
            session.recordingStarted()
            assertEquals(PhraseCaptureState.Recording(PhraseCaptureKind.WAKE_PHRASE), env.coordinator.state.value)
            "frames"
        }
        advanceUntilIdle()

        assertEquals(CaptureOutcome.Completed("frames"), outcome)
        assertEquals(
            listOf("monitoring stop requested", "monitoring released microphone", "recording started", "monitoring started"),
            env.monitoring.log
        )
        assertEquals(MicrophoneOwner.MONITORING, env.arbiter.owner.value)
        assertTrue(env.monitoring.isRunning)
        assertEquals(PhraseCaptureState.Idle, env.coordinator.state.value)
    }

    @Test
    fun stateShowsStoppingThenStartingThenRecording() = runTest {
        val env = Env(this)
        env.monitoring.startNow()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val job = async {
            env.coordinator.runCapture(PhraseCaptureKind.TEST_PHRASE) { session ->
                started.await()
                session.recordingStarted()
                finish.await()
            }
        }
        runCurrent()
        assertEquals(PhraseCaptureState.StoppingMonitoring(PhraseCaptureKind.TEST_PHRASE), env.coordinator.state.value)
        advanceTimeBy(250)
        runCurrent()
        // Microphone acquired, but recording is not shown as active before the AudioRecord started
        assertEquals(PhraseCaptureState.Starting(PhraseCaptureKind.TEST_PHRASE), env.coordinator.state.value)
        started.complete(Unit)
        runCurrent()
        assertEquals(PhraseCaptureState.Recording(PhraseCaptureKind.TEST_PHRASE), env.coordinator.state.value)
        finish.complete(Unit)
        job.await()
        assertEquals(PhraseCaptureState.Idle, env.coordinator.state.value)
    }

    @Test
    fun repeatedTapsStartOnlyOneSession() = runTest {
        val env = Env(this)
        var sessions = 0
        val finish = CompletableDeferred<Unit>()
        val first = async { env.coordinator.runCapture(PhraseCaptureKind.WAKE_PHRASE) { sessions++; finish.await() } }
        runCurrent()
        val second = env.coordinator.runCapture(PhraseCaptureKind.WAKE_PHRASE) { sessions++ }
        val third = env.coordinator.runCapture(PhraseCaptureKind.TEST_PHRASE) { sessions++ }
        finish.complete(Unit)
        first.await()

        assertEquals(CaptureOutcome.Busy, second)
        assertEquals(CaptureOutcome.Busy, third)
        assertEquals(1, sessions)
    }

    @Test
    fun monitoringThatDoesNotReleaseTheMicrophoneBlocksRecordingAndIsReported() = runTest {
        val env = Env(this)
        env.monitoring.startNow()
        env.monitoring.releases = false
        var recorded = false

        val outcome = env.coordinator.runCapture(PhraseCaptureKind.WAKE_PHRASE) { recorded = true }

        assertEquals(CaptureOutcome.MicrophoneUnavailable, outcome)
        assertFalse("recording must not start while monitoring holds the microphone", recorded)
        assertNotNull(env.coordinator.lastError.value)
        assertEquals(MicrophoneOwner.MONITORING, env.arbiter.owner.value)
        // Monitoring is not left disabled
        assertEquals(1, env.monitoring.starts)
        assertEquals(PhraseCaptureState.Idle, env.coordinator.state.value)
    }

    @Test
    fun recordingFailureReleasesTheMicrophoneAndRestoresMonitoring() = runTest {
        val env = Env(this)
        env.monitoring.startNow()

        val outcome = env.coordinator.runCapture(PhraseCaptureKind.TEST_PHRASE) {
            throw IllegalStateException("The microphone could not be opened")
        }
        advanceUntilIdle()

        assertTrue(outcome is CaptureOutcome.Failed)
        assertTrue(env.coordinator.lastError.value!!.contains("could not be opened"))
        assertTrue(env.monitoring.isRunning)
        assertEquals(MicrophoneOwner.MONITORING, env.arbiter.owner.value)
        assertFalse(env.coordinator.isBusy)
    }

    @Test
    fun cancellationReleasesTheMicrophoneAndRestoresMonitoring() = runTest {
        val env = Env(this)
        env.monitoring.startNow()
        val job = launch {
            env.coordinator.runCapture(PhraseCaptureKind.WAKE_PHRASE) { session ->
                session.recordingStarted()
                delay(10_000)
            }
        }
        advanceTimeBy(500)
        assertEquals(MicrophoneOwner.PHRASE_CAPTURE, env.arbiter.owner.value)
        job.cancel() // e.g. the screen was closed
        advanceUntilIdle()

        assertTrue(env.monitoring.isRunning)
        assertEquals(MicrophoneOwner.MONITORING, env.arbiter.owner.value)
        assertEquals(PhraseCaptureState.Idle, env.coordinator.state.value)
    }

    @Test
    fun monitoringThatWasOffStaysOff() = runTest {
        val env = Env(this)
        val outcome = env.coordinator.runCapture(PhraseCaptureKind.WAKE_PHRASE) { "ok" }
        advanceUntilIdle()

        assertEquals(CaptureOutcome.Completed("ok"), outcome)
        assertEquals(0, env.monitoring.stops)
        assertEquals(0, env.monitoring.starts)
        assertFalse(env.monitoring.isRunning)
        assertEquals(MicrophoneOwner.NONE, env.arbiter.owner.value)
    }

    @Test
    fun userStoppingMonitoringDuringRecordingIsRespected() = runTest {
        val env = Env(this)
        env.monitoring.startNow()
        env.coordinator.runCapture(PhraseCaptureKind.WAKE_PHRASE) {
            env.coordinator.onUserChangedMonitoring() // user tapped "Stop Monitoring" meanwhile
        }
        advanceUntilIdle()
        assertEquals(0, env.monitoring.starts)
        assertFalse(env.monitoring.isRunning)
    }

    @Test
    fun monitoringStartedByTheUserDuringRecordingWaitsForTheMicrophone() = runTest {
        val env = Env(this)
        env.coordinator.runCapture(PhraseCaptureKind.WAKE_PHRASE) {
            env.coordinator.onUserChangedMonitoring()
            env.monitoring.start() // user taps "Start Monitoring" while the phrase is recorded
            runCurrent()
            // Contention: monitoring must not capture while the phrase recording holds the microphone
            assertEquals(MicrophoneOwner.PHRASE_CAPTURE, env.arbiter.owner.value)
        }
        advanceUntilIdle()
        runCurrent() // let the waiting monitoring capture loop take the released microphone
        assertEquals("not started twice", 1, env.monitoring.starts)
        assertEquals(MicrophoneOwner.MONITORING, env.arbiter.owner.value)
    }

    @Test
    fun missingPermissionStartsNothingAndKeepsMonitoringRunning() = runTest {
        val env = Env(this)
        env.monitoring.startNow()
        env.permission = false
        var recorded = false

        val outcome = env.coordinator.runCapture(PhraseCaptureKind.TEST_PHRASE) { recorded = true }

        assertEquals(CaptureOutcome.PermissionDenied, outcome)
        assertFalse(recorded)
        assertEquals(0, env.monitoring.stops)
        assertTrue(env.monitoring.isRunning)
    }

    @Test
    fun permissionLostDuringRecordingLeavesMonitoringOffAndReportsIt() = runTest {
        val env = Env(this)
        env.monitoring.startNow()
        env.coordinator.runCapture(PhraseCaptureKind.WAKE_PHRASE) { env.permission = false }
        advanceUntilIdle()

        assertFalse(env.monitoring.isRunning)
        assertTrue(env.coordinator.lastError.value!!.contains("permission"))
        assertEquals(MicrophoneOwner.NONE, env.arbiter.owner.value)
    }

    @Test
    fun arbiterNeverGrantsTheMicrophoneToTwoOwners() {
        val arbiter = MicrophoneArbiter()
        assertTrue(arbiter.tryAcquire(MicrophoneOwner.MONITORING))
        assertFalse(arbiter.tryAcquire(MicrophoneOwner.PHRASE_CAPTURE))
        arbiter.release(MicrophoneOwner.PHRASE_CAPTURE) // not the owner: ignored
        assertEquals(MicrophoneOwner.MONITORING, arbiter.owner.value)
        arbiter.release(MicrophoneOwner.MONITORING)
        assertTrue(arbiter.tryAcquire(MicrophoneOwner.PHRASE_CAPTURE))
        assertFalse(arbiter.tryAcquire(MicrophoneOwner.MONITORING))
    }
}
