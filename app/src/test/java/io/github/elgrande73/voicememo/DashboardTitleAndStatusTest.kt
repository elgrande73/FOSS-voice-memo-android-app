package io.github.elgrande73.voicememo

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.audio.PhraseCaptureKind
import io.github.elgrande73.voicememo.audio.PhraseCaptureState
import io.github.elgrande73.voicememo.monitoring.MonitoringEngineState
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DashboardTitleAndStatusTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun dashboardShowsVoiceMemoTitleAlsoAfterRecreation() {
        composeTestRule.onNodeWithTag("dashboard_title").assertIsDisplayed().assertTextEquals("Voice memo")
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.onNodeWithTag("dashboard_title").assertIsDisplayed().assertTextEquals("Voice memo")
    }

    @Test
    fun titleAndLauncherNameAreSeparateResources() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Voice memo", context.getString(R.string.dashboard_title))
        assertEquals("Voice Memo", context.getString(R.string.app_name))
    }

    @Test
    fun statusReflectsTheRealEngineState() {
        composeTestRule.onNodeWithTag("monitoring_status").assertTextEquals("Monitoring Stopped")
        val idle = PhraseCaptureState.Idle
        assertEquals("Monitoring Active", monitoringStatusText(false, MonitoringEngineState.ACTIVE, idle))
        assertEquals("Starting monitoring…", monitoringStatusText(false, MonitoringEngineState.STARTING, idle))
        assertEquals("Waiting for the microphone…", monitoringStatusText(false, MonitoringEngineState.WAITING_FOR_MICROPHONE, idle))
        assertEquals("Microphone unavailable", monitoringStatusText(false, MonitoringEngineState.UNAVAILABLE, idle))
        assertEquals("Stopping monitoring…", monitoringStatusText(false, MonitoringEngineState.STOPPING, idle))
        assertEquals(
            "Stopping monitoring…",
            monitoringStatusText(false, MonitoringEngineState.ACTIVE, PhraseCaptureState.StoppingMonitoring(PhraseCaptureKind.WAKE_PHRASE))
        )
        assertEquals(
            "Paused: recording a phrase in Settings",
            monitoringStatusText(false, MonitoringEngineState.STOPPED, PhraseCaptureState.Recording(PhraseCaptureKind.TEST_PHRASE))
        )
        assertEquals("Recording memo…", monitoringStatusText(true, MonitoringEngineState.ACTIVE, idle))
    }
}
