package io.github.elgrande73.voicememo

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.monitoring.AudioMonitoringService
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import io.github.elgrande73.voicememo.shortcuts.HardwareGesture
import io.github.elgrande73.voicememo.shortcuts.KeyboardShortcut
import io.github.elgrande73.voicememo.shortcuts.RecordingShortcutHandler
import io.github.elgrande73.voicememo.shortcuts.ShortcutConfig
import io.github.elgrande73.voicememo.shortcuts.ShortcutRuntime
import io.github.elgrande73.voicememo.shortcuts.ShortcutSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import java.time.Duration

/**
 * Key events delivered to the running activity (what Android does while Voice Memo is in the foreground). Recording
 * itself is not started under Robolectric; the test checks the service requests the shortcuts produce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShortcutActivityTest {

    private lateinit var app: Application
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        ShortcutRuntime.textInputFocused = false
        ShortcutRuntime.captureActive = false
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        // Let pending frame callbacks run: Compose's shared UI dispatcher would otherwise wait for a frame that never
        // comes, and Compose tests in later classes of the same JVM never become idle
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
    }

    private fun launch(config: ShortcutConfig) {
        ShortcutSettings.save(app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE), config)
        // A leaked activity would keep its ViewModel (and its main-thread work) alive for later test classes
        if (::controller.isInitialized) controller.pause().stop().destroy()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        drainStartedServices()
    }

    private fun drainStartedServices() {
        while (shadowOf(app).nextStartedService != null) { /* discard */ }
    }

    /** Service requests made since the last call (actions; null = start monitoring). */
    private fun startedServiceActions(): List<String?> = buildList {
        while (true) {
            val intent: Intent = shadowOf(app).nextStartedService ?: break
            if (intent.component?.className == AudioMonitoringService::class.java.name) add(intent.action)
        }
    }

    /** A key from a physical keyboard (device id 1; the 2-argument constructor would mark it as virtual). */
    private fun key(action: Int, code: Int, meta: Int = 0, repeat: Int = 0): Boolean {
        val now = SystemClock.uptimeMillis()
        return activity.dispatchKeyEvent(KeyEvent(now, now, action, code, repeat, meta, 1, 0))
    }

    private fun press(code: Int, meta: Int = 0) {
        key(KeyEvent.ACTION_DOWN, code, meta)
        key(KeyEvent.ACTION_UP, code, meta)
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private val ctrlShiftR = KeyboardShortcut(KeyEvent.KEYCODE_R, KeyboardShortcut.CTRL or KeyboardShortcut.SHIFT)
    private val ctrlShiftMeta = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON

    @Test
    fun keyboardShortcutStartsAMemoOncePerPress() {
        launch(ShortcutConfig(keyboardEnabled = true, keyboardShortcut = ctrlShiftR))
        assertTrue(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_R, ctrlShiftMeta))
        assertTrue(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_R, ctrlShiftMeta, repeat = 1))
        assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_R, ctrlShiftMeta))
        // Monitoring is off: like Quick Memo, it is started first, then the memo
        assertEquals(listOf(null, AudioMonitoringService.ACTION_START_MANUAL_RECORDING), startedServiceActions())
    }

    @Test
    fun plainTypingAndDisabledShortcutsDoNothing() {
        launch(ShortcutConfig(keyboardEnabled = false, keyboardShortcut = ctrlShiftR))
        press(KeyEvent.KEYCODE_R, ctrlShiftMeta)
        launch(ShortcutConfig(keyboardEnabled = true, keyboardShortcut = KeyboardShortcut(KeyEvent.KEYCODE_F9, 0)))
        press(KeyEvent.KEYCODE_R)
        ShortcutRuntime.textInputFocused = true
        press(KeyEvent.KEYCODE_F9) // no command modifier while a text field has focus
        ShortcutRuntime.textInputFocused = false
        assertEquals(emptyList<String?>(), startedServiceActions())
        press(KeyEvent.KEYCODE_F9)
        assertEquals(2, startedServiceActions().size)
    }

    @Test
    fun capturingANewShortcutDoesNotTriggerTheOldOne() {
        launch(ShortcutConfig(keyboardEnabled = true, keyboardShortcut = ctrlShiftR))
        val vm = activityViewModel()
        vm.startKeyboardShortcutCapture()
        press(KeyEvent.KEYCODE_R, ctrlShiftMeta)
        assertEquals(emptyList<String?>(), startedServiceActions())
        assertFalse(vm.keyboardShortcutCapture.value)
        assertEquals(ctrlShiftR, vm.shortcutConfig.value.keyboardShortcut)

        vm.startKeyboardShortcutCapture()
        press(KeyEvent.KEYCODE_ESCAPE)
        assertFalse("Esc cancels", vm.keyboardShortcutCapture.value)
    }

    @Test
    fun holdingBothVolumeButtonsStartsAMemoAfterTheHoldTime() {
        launch(ShortcutConfig(hardwareEnabled = true, hardwareGesture = HardwareGesture.HOLD_BOTH_VOLUME))
        assertTrue(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))
        advance(RecordingShortcutHandler.CHORD_HOLD_MS / 2)
        assertEquals(emptyList<String?>(), startedServiceActions())
        advance(RecordingShortcutHandler.CHORD_HOLD_MS)
        assertEquals(2, startedServiceActions().size)
        assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN))
        advance(2000)
        assertEquals(emptyList<String?>(), startedServiceActions())
    }

    @Test
    fun volumeButtonsAreUntouchedWhenTheShortcutIsOff() {
        launch(ShortcutConfig(hardwareEnabled = false))
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)
        advance(3000)
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP)
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
        assertEquals(emptyList<String?>(), startedServiceActions())
    }

    private fun activityViewModel() = androidx.lifecycle.ViewModelProvider(activity)[io.github.elgrande73.voicememo.viewmodel.VoiceMemoViewModel::class.java]
}
