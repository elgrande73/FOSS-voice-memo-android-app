package io.github.elgrande73.voicememo

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import io.github.elgrande73.voicememo.ui.theme.MyApplicationTheme
import io.github.elgrande73.voicememo.viewmodel.VoiceMemoViewModel
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The dashboard names "Mango Tango"; an upgraded installation sees a one-time notice about the changed phrase. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WakePhraseHintUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun dashboardNamesThePhraseToSay() {
        val viewModel = VoiceMemoViewModel(app)
        viewModel.setDetectionMode(DetectionMode.KEYWORD_ONLY)
        composeTestRule.setContent { MyApplicationTheme { DashboardScreen(viewModel = viewModel, hasMicPermission = true, onRequestPermission = {}) } }
        composeTestRule.onNodeWithTag("dashboard_wake_phrase_hint").assertTextContains("Say \"Mango Tango\" to start and stop a memo")
        composeTestRule.onNodeWithTag("wake_phrase_changed_notice").assertDoesNotExist()
    }

    @Test
    fun upgradedInstallationSeesTheNoticeOnceAndCanDismissIt() {
        // Settings of an installation from before v4 (no samples)
        app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(PreferenceKeys.SETTINGS_SCHEMA_VERSION, 2).putInt(PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT, 50).commit()
        val viewModel = VoiceMemoViewModel(app)
        viewModel.setDetectionMode(DetectionMode.KEYWORD_ONLY)
        composeTestRule.setContent { MyApplicationTheme { DashboardScreen(viewModel = viewModel, hasMicPermission = true, onRequestPermission = {}) } }
        composeTestRule.onNodeWithTag("wake_phrase_changed_notice").assertExists()
        composeTestRule.onNodeWithText("The built-in wake phrase is now \"Mango Tango\"", substring = true).assertExists()
        // Semantic click: on the small test screen the fixed record bar covers this part of the dashboard
        composeTestRule.onNodeWithTag("dismiss_wake_phrase_notice").performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithTag("wake_phrase_changed_notice").assertDoesNotExist()
        assertFalse(VoiceMemoViewModel(app).wakePhraseChangedNotice.value)
    }
}
