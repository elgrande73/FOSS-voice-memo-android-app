package io.github.elgrande73.voicememo

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.monitoring.AudioMonitoringService
import io.github.elgrande73.voicememo.monitoring.MonitoringRequest
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import io.github.elgrande73.voicememo.viewmodel.VoiceMemoViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Quick Memo while monitoring is off runs monitoring only for that memo; monitoring that was already on stays on.
 * Real service (no microphone under Robolectric: the memo is started and stopped through the service's own actions).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OneMemoMonitoringTest {

    private lateinit var app: Application
    private var controller: ServiceController<AudioMonitoringService>? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).clearStartedServices()
    }

    @After
    fun tearDown() {
        controller?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun intent(action: String? = null) = Intent(app, AudioMonitoringService::class.java).apply { if (action != null) setAction(action) }

    /** The configuration request the ViewModel sends to start (or re-apply) monitoring. */
    private fun startRequest(request: MonitoringRequest?) = intent().apply {
        putExtra("feedbackSoundsEnabled", false)
        if (request != null) putExtra(AudioMonitoringService.EXTRA_MONITORING_REQUEST, request.name)
    }

    private fun send(i: Intent, id: Int) {
        controller!!.withIntent(i).startCommand(0, id)
    }

    /** Stops the memo and waits until it is saved and the service had the chance to stop. */
    private fun stopMemoAndSettle() {
        send(intent(AudioMonitoringService.ACTION_STOP_MANUAL_RECORDING), 99)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            if (!AudioMonitoringService.isCurrentlyRecording.value && (shadowOf(controller!!.get()).isStoppedBySelf || System.currentTimeMillis() > deadline - 7_000)) break
            Thread.sleep(50)
        }
        // Give a pending one-memo stop time to run (save on IO, then the main thread)
        repeat(20) { Thread.sleep(50); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
    }

    @Test
    fun quickMemoWhileMonitoringIsOffStopsMonitoringAfterTheMemo() {
        controller = Robolectric.buildService(AudioMonitoringService::class.java, startRequest(MonitoringRequest.ONE_MEMO)).create().startCommand(0, 1)
        send(intent(AudioMonitoringService.ACTION_START_MANUAL_RECORDING), 2)
        assertTrue(AudioMonitoringService.isServiceRunning.value)
        assertTrue(AudioMonitoringService.isCurrentlyRecording.value)
        assertTrue(AudioMonitoringService.oneMemoSession.value)
        stopMemoAndSettle()
        assertTrue("monitoring that the quick memo started is stopped again", shadowOf(controller!!.get()).isStoppedBySelf)
    }

    @Test
    fun quickMemoWhileMonitoringRunsKeepsMonitoringOn() {
        controller = Robolectric.buildService(AudioMonitoringService::class.java, startRequest(MonitoringRequest.CONTINUOUS)).create().startCommand(0, 1)
        // What the ViewModel would send if a quick memo raced with the running service: never downgrades monitoring
        send(startRequest(MonitoringRequest.ONE_MEMO), 2)
        assertFalse(AudioMonitoringService.oneMemoSession.value)
        send(intent(AudioMonitoringService.ACTION_START_MANUAL_RECORDING), 3)
        stopMemoAndSettle()
        assertFalse("monitoring was on before: it stays on", shadowOf(controller!!.get()).isStoppedBySelf)
        assertTrue(AudioMonitoringService.isServiceRunning.value)
    }

    @Test
    fun requestsFromEarlierVersionsMeanContinuousMonitoring() {
        controller = Robolectric.buildService(AudioMonitoringService::class.java, startRequest(null)).create().startCommand(0, 1)
        send(intent(AudioMonitoringService.ACTION_START_MANUAL_RECORDING), 2)
        stopMemoAndSettle()
        assertFalse(shadowOf(controller!!.get()).isStoppedBySelf)
    }

    @Test
    fun keepingMonitoringOnDuringTheQuickMemoKeepsItOnAndKeepsTheMemo() {
        controller = Robolectric.buildService(AudioMonitoringService::class.java, startRequest(MonitoringRequest.ONE_MEMO)).create().startCommand(0, 1)
        send(intent(AudioMonitoringService.ACTION_START_MANUAL_RECORDING), 2)
        // "Keep Monitoring On" (dashboard) while the memo is recorded
        send(startRequest(MonitoringRequest.CONTINUOUS), 3)
        assertTrue("the memo in progress is not discarded", AudioMonitoringService.isCurrentlyRecording.value)
        assertFalse(AudioMonitoringService.oneMemoSession.value)
        stopMemoAndSettle()
        assertFalse(shadowOf(controller!!.get()).isStoppedBySelf)
    }

    @Test
    fun settingsReappliedDuringTheQuickMemoKeepTheOneMemoMode() {
        controller = Robolectric.buildService(AudioMonitoringService::class.java, startRequest(MonitoringRequest.ONE_MEMO)).create().startCommand(0, 1)
        send(intent(AudioMonitoringService.ACTION_START_MANUAL_RECORDING), 2)
        send(startRequest(MonitoringRequest.KEEP), 3) // e.g. silence timeout changed in Settings
        assertTrue(AudioMonitoringService.isCurrentlyRecording.value)
        assertTrue(AudioMonitoringService.oneMemoSession.value)
        stopMemoAndSettle()
        assertTrue(shadowOf(controller!!.get()).isStoppedBySelf)
    }

    @Test
    fun viewModelAsksForOneMemoOnlyWhenMonitoringIsOff() {
        assertFalse(AudioMonitoringService.isServiceRunning.value)
        val vm = VoiceMemoViewModel(app)
        vm.startManualRecording()
        val requests = generateSequence { shadowOf(app).nextStartedService }.toList()
        assertEquals(MonitoringRequest.ONE_MEMO.name, requests.first().getStringExtra(AudioMonitoringService.EXTRA_MONITORING_REQUEST))
        assertEquals(AudioMonitoringService.ACTION_START_MANUAL_RECORDING, requests.last().action)
        vm.setMonitoringEnabledByUser(true)
        assertEquals(MonitoringRequest.CONTINUOUS.name, shadowOf(app).nextStartedService.getStringExtra(AudioMonitoringService.EXTRA_MONITORING_REQUEST))
    }
}
