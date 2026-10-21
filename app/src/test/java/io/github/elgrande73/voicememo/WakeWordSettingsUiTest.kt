package io.github.elgrande73.voicememo

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import io.github.elgrande73.voicememo.ui.theme.MyApplicationTheme
import io.github.elgrande73.voicememo.viewmodel.VoiceMemoViewModel
import io.github.elgrande73.voicememo.wakephrase.BuiltInWakePhrase
import io.github.elgrande73.voicememo.wakephrase.WakeWordModel
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings controls for the built-in phrase and the detection model, and the dashboard hint that follows them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WakeWordSettingsUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun detectionModelAndBuiltInPhraseArePersistedFromSettings() {
        val viewModel = VoiceMemoViewModel(app)
        composeTestRule.setContent { MyApplicationTheme { SettingsScreen(viewModel) } }

        composeTestRule.onNodeWithTag("wake_word_model_standard").performScrollTo().assertIsSelected()
        composeTestRule.onNodeWithTag("wake_word_model_status").assertTextEquals("Takes effect when monitoring runs.")
        composeTestRule.onNodeWithTag("wake_word_model_speech_check").performScrollTo().performClick().assertIsSelected()
        composeTestRule.onNodeWithTag("built_in_phrase_ciao_linda").performScrollTo().performClick()

        val restarted = VoiceMemoViewModel(app)
        assertEquals(WakeWordModel.SPEECH_CHECK, restarted.wakeWordModel.value)
        assertEquals(BuiltInWakePhrase.CIAO_LINDA, restarted.builtInWakePhrase.value)
        assertEquals(null, restarted.speechCheckProblem()) // built-in phrase: its words are known
    }

    @Test
    fun dashboardNamesThePhraseToSay() {
        val viewModel = VoiceMemoViewModel(app)
        viewModel.setDetectionMode(DetectionMode.KEYWORD_ONLY)
        composeTestRule.setContent { MyApplicationTheme { DashboardScreen(viewModel = viewModel, hasMicPermission = true, onRequestPermission = {}) } }
        composeTestRule.onNodeWithTag("dashboard_wake_phrase_hint").assertTextContains("Say \"Mango Tango\" to start and stop a memo")
        composeTestRule.runOnIdle { viewModel.setBuiltInWakePhrase(BuiltInWakePhrase.CIAO_LINDA) }
        composeTestRule.onNodeWithTag("dashboard_wake_phrase_hint").assertTextContains("Say \"Ciao Linda\"", substring = true)
    }
}
