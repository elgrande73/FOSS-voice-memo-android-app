package io.github.elgrande73.voicememo

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.memo.AppDatabase
import io.github.elgrande73.voicememo.settings.MatchConfidenceThreshold
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import io.github.elgrande73.voicememo.ui.theme.MyApplicationTheme
import io.github.elgrande73.voicememo.viewmodel.VoiceMemoViewModel
import io.github.elgrande73.voicememo.wakephrase.BuiltInWakePhrase
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import io.github.elgrande73.voicememo.wakephrase.WakePhraseStore
import io.github.elgrande73.voicememo.wakephrase.WakePhraseTestResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.io.File

/**
 * Restart and upgrade behavior of user data inside one Voice Memo installation. A "restart" is simulated by creating
 * a new ViewModel (and store) over the same app-private storage, which is also what an in-place app update sees.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserDataPersistenceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        File(app.filesDir, WakePhraseStore.DIRECTORY_NAME).deleteRecursively()
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("Condition not met in time", condition())
    }

    @Test
    fun freshInstallationUsesDefaultWakePhraseAndValidDefaultThreshold() {
        val viewModel = VoiceMemoViewModel(app)

        assertEquals(MatchConfidenceThreshold.DEFAULT_PERCENT, viewModel.matchConfidenceThresholdPercent.value)
        assertFalse("No enrolled phrase: the built-in phrase is used", viewModel.hasKeywordTemplate.value)
        assertEquals(BuiltInWakePhrase.MANGO_TANGO, viewModel.builtInWakePhrase.value)
        assertTrue(TemplateKeywordDetector().hasTemplate())
        assertNull(viewModel.keywordTestResult.value)
    }

    @Test
    fun thresholdPersistsAcrossRestartAndDashboardShowsIt() {
        VoiceMemoViewModel(app).setMatchConfidenceThresholdPercent(65)

        val restarted = VoiceMemoViewModel(app)
        assertEquals(65, restarted.matchConfidenceThresholdPercent.value)

        composeTestRule.setContent {
            MyApplicationTheme {
                DashboardScreen(viewModel = restarted, hasMicPermission = true, onRequestPermission = {})
            }
        }
        composeTestRule.onNodeWithTag("dashboard_trigger_threshold").assertTextEquals("Trigger threshold: 65%")

        // Changing the setting updates the dashboard from the same authoritative value
        composeTestRule.runOnIdle { restarted.setMatchConfidenceThresholdPercent(72) }
        composeTestRule.onNodeWithTag("dashboard_trigger_threshold").assertTextEquals("Trigger threshold: 72%")
    }

    @Test
    fun thresholdSettingIsClampedToSupportedRange() {
        val viewModel = VoiceMemoViewModel(app)
        viewModel.setMatchConfidenceThresholdPercent(120)
        assertEquals(80, viewModel.matchConfidenceThresholdPercent.value)
        viewModel.setMatchConfidenceThresholdPercent(10)
        assertEquals(20, viewModel.matchConfidenceThresholdPercent.value)
    }

    @Test
    fun lowThresholdPersistsAndDashboardShowsTheValueTheDetectorUses() {
        VoiceMemoViewModel(app).setMatchConfidenceThresholdPercent(20)
        val restarted = VoiceMemoViewModel(app)
        assertEquals(20, restarted.matchConfidenceThresholdPercent.value)
        assertEquals(0.20f, MatchConfidenceThreshold.toDetectorConfidence(restarted.matchConfidenceThresholdPercent.value), 0.0001f)
        composeTestRule.setContent {
            MyApplicationTheme {
                DashboardScreen(viewModel = restarted, hasMicPermission = true, onRequestPermission = {})
            }
        }
        composeTestRule.onNodeWithTag("dashboard_trigger_threshold").assertTextEquals("Trigger threshold: 20%")
    }

    @Test
    fun enrolledWakePhraseIsNotReplacedByDefaultOnRestart() {
        val detector = TemplateKeywordDetector()
        assertTrue(detector.recordTemplate(TemplateKeywordDetector.generateCanonicalCiaoLindaAudio(amplitude = 7000.0)))
        val template = detector.getTemplateData()!!
        WakePhraseStore(app.filesDir).addSample(template, 800L, -10f)

        repeat(2) {
            val viewModel = VoiceMemoViewModel(app)
            assertTrue(viewModel.hasKeywordTemplate.value)
        }
        assertArrayEquals(template, WakePhraseStore(app.filesDir).loadSampleTemplates().single())
    }

    @Test
    fun testPhraseResultAndRecordingAreRestoredAfterRestart() {
        WakePhraseStore(app.filesDir).apply {
            saveTestRecording(TemplateKeywordDetector.generateCanonicalCiaoLindaAudio())
            saveTestResult(WakePhraseTestResult(0.66f, 1_700_000_000_000L))
        }

        val viewModel = VoiceMemoViewModel(app)
        awaitCondition { viewModel.keywordTestResult.value != null }
        assertEquals(WakePhraseTestResult(0.66f, 1_700_000_000_000L), viewModel.keywordTestResult.value)
        awaitCondition { viewModel.hasSavedTestRecording.value }
    }

    @Test
    fun otherSavedSettingsSurviveRestartAndThresholdChanges() {
        VoiceMemoViewModel(app).apply {
            setThresholdDb(57f)
            setSilenceTimeoutMs(3000L)
            setMatchConfidenceThresholdPercent(70)
        }

        val restarted = VoiceMemoViewModel(app)
        assertEquals(57f, restarted.thresholdDb.value, 0.001f)
        assertEquals(3000L, restarted.silenceTimeoutMs.value)
        assertEquals(70, restarted.matchConfidenceThresholdPercent.value)
    }

    @Test
    fun persistedIdentifiersDoNotDependOnDisplayName() {
        // Storage names are fixed constants: renaming the app label must never point the app at new, empty storage
        assertEquals("Voice Memo", app.getString(R.string.app_name))
        assertEquals("voice_memo_preferences", PreferenceKeys.PREFS_NAME)
        assertEquals("voice_memo_database", AppDatabase.DATABASE_NAME)
        assertEquals("wake_phrase", WakePhraseStore.DIRECTORY_NAME)
    }

    @Test
    fun newIdentityDoesNotClaimDataFromOldApplication() {
        assertEquals("io.github.elgrande73.voicememo", BuildConfig.APPLICATION_ID)
        assertNotEquals("com.aistudio.ciaolinda", app.packageName)

        val viewModel = VoiceMemoViewModel(app)
        assertFalse(viewModel.hasKeywordTemplate.value)
        assertNull(viewModel.keywordTestResult.value)
        assertEquals(MatchConfidenceThreshold.DEFAULT_PERCENT, viewModel.matchConfidenceThresholdPercent.value)
    }
}
