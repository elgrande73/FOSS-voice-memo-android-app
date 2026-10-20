package io.github.elgrande73.voicememo

import android.app.Application
import android.content.Context
import android.view.KeyEvent
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import io.github.elgrande73.voicememo.detection.RecordingLifecycleState
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import io.github.elgrande73.voicememo.shortcuts.HardwareGesture
import io.github.elgrande73.voicememo.shortcuts.KeyboardShortcut
import io.github.elgrande73.voicememo.shortcuts.ShortcutConfig
import io.github.elgrande73.voicememo.shortcuts.ShortcutRuntime
import io.github.elgrande73.voicememo.shortcuts.ShortcutSettings
import io.github.elgrande73.voicememo.ui.theme.MyApplicationTheme
import io.github.elgrande73.voicememo.viewmodel.VoiceMemoViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShortcutSettingsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application
    private val prefs get() = app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        prefs.edit().clear().commit()
        ShortcutRuntime.captureActive = false
    }

    @Test
    fun everythingIsOffByDefaultExceptLockScreenControls() {
        assertEquals(ShortcutConfig(), ShortcutSettings.load(prefs))
        assertFalse(ShortcutConfig().hardwareEnabled)
        assertFalse(ShortcutConfig().keyboardEnabled)
        assertTrue(ShortcutSettings.lockScreenControlsEnabled(prefs))
    }

    @Test
    fun settingsSurviveRestart() {
        VoiceMemoViewModel(app).apply {
            setHardwareShortcutEnabled(true)
            setHardwareGesture(HardwareGesture.HOLD_VOLUME_DOWN)
            setLockScreenControlsEnabled(false)
            startKeyboardShortcutCapture()
            onShortcutKeyCaptured(KeyEvent.KEYCODE_R, KeyboardShortcut.CTRL or KeyboardShortcut.SHIFT)
        }
        val restarted = VoiceMemoViewModel(app)
        assertEquals(
            ShortcutConfig(true, HardwareGesture.HOLD_VOLUME_DOWN, true, KeyboardShortcut(KeyEvent.KEYCODE_R, KeyboardShortcut.CTRL or KeyboardShortcut.SHIFT)),
            restarted.shortcutConfig.value
        )
        assertFalse(restarted.lockScreenControlsEnabled.value)
    }

    @Test
    fun corruptOrUnknownValuesFallBackToDefaultsWithoutTouchingOtherSettings() {
        prefs.edit()
            .putString(PreferenceKeys.HARDWARE_SHORTCUT_ENABLED, "yes")
            .putString(PreferenceKeys.HARDWARE_SHORTCUT_GESTURE, "TRIPLE_TAP")
            .putBoolean(PreferenceKeys.KEYBOARD_SHORTCUT_ENABLED, true)
            .putInt(PreferenceKeys.KEYBOARD_SHORTCUT_KEY_CODE, KeyEvent.KEYCODE_A) // no modifier: not allowed
            .putFloat(PreferenceKeys.LOCK_SCREEN_CONTROLS_ENABLED, 1f)
            .putInt(PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT, 65)
            .commit()
        assertEquals(ShortcutConfig(), ShortcutSettings.load(prefs))
        assertTrue(ShortcutSettings.lockScreenControlsEnabled(prefs))
        assertEquals(65, VoiceMemoViewModel(app).matchConfidenceThresholdPercent.value)
    }

    @Test
    fun captureIgnoresModifiersRejectsUnsafeKeysAndSavesAValidCombination() {
        val vm = VoiceMemoViewModel(app)
        assertFalse("not capturing", vm.onShortcutKeyCaptured(KeyEvent.KEYCODE_R, KeyboardShortcut.CTRL))

        vm.setKeyboardShortcutEnabled(true) // nothing set yet
        assertFalse(vm.shortcutConfig.value.keyboardEnabled)
        assertNotNull(vm.keyboardShortcutMessage.value)

        vm.startKeyboardShortcutCapture()
        assertTrue(ShortcutRuntime.captureActive)
        assertTrue(vm.onShortcutKeyCaptured(KeyEvent.KEYCODE_CTRL_LEFT, KeyboardShortcut.CTRL))
        assertTrue(vm.keyboardShortcutCapture.value)
        assertNull(vm.keyboardShortcutMessage.value)

        vm.onShortcutKeyCaptured(KeyEvent.KEYCODE_R, 0)
        assertEquals(KeyboardShortcut.Problem.TYPING_KEY.message, vm.keyboardShortcutMessage.value)
        vm.onShortcutKeyCaptured(KeyEvent.KEYCODE_V, KeyboardShortcut.CTRL)
        assertEquals(KeyboardShortcut.Problem.EDITING_SHORTCUT.message, vm.keyboardShortcutMessage.value)
        assertTrue(vm.keyboardShortcutCapture.value)
        assertNull(vm.shortcutConfig.value.keyboardShortcut)

        vm.onShortcutKeyCaptured(KeyEvent.KEYCODE_F9, 0)
        assertFalse(vm.keyboardShortcutCapture.value)
        assertFalse(ShortcutRuntime.captureActive)
        assertEquals(KeyboardShortcut(KeyEvent.KEYCODE_F9, 0), vm.shortcutConfig.value.keyboardShortcut)
        assertTrue(vm.shortcutConfig.value.keyboardEnabled)

        vm.clearKeyboardShortcut()
        assertNull(ShortcutSettings.load(prefs).keyboardShortcut)
        assertFalse(ShortcutSettings.load(prefs).keyboardEnabled)
    }

    @Test
    fun dashboardRecordButtonIsLargeLabelledAndAtTheBottom() {
        val vm = VoiceMemoViewModel(app)
        composeTestRule.setContent {
            MyApplicationTheme { DashboardScreen(viewModel = vm, hasMicPermission = true, onRequestPermission = {}) }
        }
        composeTestRule.onNodeWithTag("manual_record_button")
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertWidthIsAtLeast(96.dp)
        composeTestRule.onNodeWithContentDescription("Start quick memo").assertIsDisplayed()
        composeTestRule.onNodeWithTag("manual_record_label").assertTextEquals("Quick Memo")

        val root = composeTestRule.onNodeWithTag("dashboard_screen").fetchSemanticsNode().boundsInRoot
        val button = composeTestRule.onNodeWithTag("manual_record_button").fetchSemanticsNode().boundsInRoot
        val monitoring = composeTestRule.onNodeWithTag("toggle_monitoring_button").fetchSemanticsNode().boundsInRoot
        assertEquals("centred", root.center.x, button.center.x, 2f)
        assertTrue("in the lower part of the screen", button.top > root.top + root.height * 0.6f)
        assertTrue("does not overlap the monitoring button", button.top >= monitoring.bottom || button.bottom <= monitoring.top)
    }

    @Test
    fun recordButtonShowsTheStopState() {
        composeTestRule.setContent {
            MyApplicationTheme { MemoRecordBar(isRecording = true, onClick = {}) }
        }
        composeTestRule.onNodeWithContentDescription("Stop memo and save").assertIsDisplayed()
        composeTestRule.onNodeWithTag("manual_record_label").assertTextEquals("Stop Memo")
    }

    @Test
    fun repeatedStartRequestKeepsTheMemoInProgress() {
        val detector = MemoTriggerDetector().apply {
            detectionMode = DetectionMode.KEYWORD_ONLY
            reset(startMonitoring = true)
        }
        val frame = ShortArray(800) { (sin(it * 0.2) * 3000).toInt().toShort() }
        assertNotNull(detector.startManualRecording())
        repeat(10) { detector.processFrame(frame) }
        assertNull("second start is ignored", detector.startManualRecording())
        assertEquals(RecordingLifecycleState.RECORDING, detector.currentState)
        val memo = detector.stopManualRecording()!!
        assertTrue("frames recorded before the second start are kept", memo.audioChunks.size >= 10)
    }
}
