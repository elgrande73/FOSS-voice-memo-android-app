package io.github.elgrande73.voicememo

import android.view.KeyEvent
import io.github.elgrande73.voicememo.shortcuts.HardwareGesture
import io.github.elgrande73.voicememo.shortcuts.KeyboardShortcut
import io.github.elgrande73.voicememo.shortcuts.KeyboardShortcut.Companion.ALT
import io.github.elgrande73.voicememo.shortcuts.KeyboardShortcut.Companion.CTRL
import io.github.elgrande73.voicememo.shortcuts.KeyboardShortcut.Companion.SHIFT
import io.github.elgrande73.voicememo.shortcuts.KeyboardShortcut.Problem
import io.github.elgrande73.voicememo.shortcuts.RecordingShortcutHandler
import io.github.elgrande73.voicememo.shortcuts.RecordingShortcutHandler.Companion.CHORD_HOLD_MS
import io.github.elgrande73.voicememo.shortcuts.RecordingShortcutHandler.Companion.CHORD_WINDOW_MS
import io.github.elgrande73.voicememo.shortcuts.RecordingShortcutHandler.Companion.SINGLE_KEY_HOLD_MS
import io.github.elgrande73.voicememo.shortcuts.ShortcutConfig
import io.github.elgrande73.voicememo.shortcuts.ShortcutKeyInput
import io.github.elgrande73.voicememo.shortcuts.ToggleDebouncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure state-machine tests (no Android runtime): times are explicit, the host's timer is simulated with [tickUntil]. */
class RecordingShortcutHandlerTest {

    private val up = KeyEvent.KEYCODE_VOLUME_UP
    private val down = KeyEvent.KEYCODE_VOLUME_DOWN

    private var toggles = 0
    private val volumeSteps = mutableListOf<Boolean>() // true = raise

    private fun handler(config: ShortcutConfig) = RecordingShortcutHandler(config, { toggles++ }) { volumeSteps += it }

    private fun hardware(gesture: HardwareGesture = HardwareGesture.HOLD_BOTH_VOLUME) =
        handler(ShortcutConfig(hardwareEnabled = true, hardwareGesture = gesture))

    private fun RecordingShortcutHandler.down(key: Int, t: Long, repeat: Int = 0, modifiers: Int = 0, virtual: Boolean = false, text: Boolean = false) =
        onKey(ShortcutKeyInput(true, key, repeat, modifiers, t, virtual, text))

    private fun RecordingShortcutHandler.up(key: Int, t: Long, modifiers: Int = 0) = onKey(ShortcutKeyInput(false, key, 0, modifiers, t))

    /** What the host's timer does: run onTick whenever a deadline has passed. */
    private fun RecordingShortcutHandler.tickUntil(t: Long) {
        while (true) {
            val deadline = nextDeadlineMs ?: return
            if (deadline > t) return
            onTick(deadline)
        }
    }

    // --- Both volume buttons ---

    @Test
    fun holdingBothVolumeButtonsTogglesOnceAndLeavesTheVolumeAlone() {
        val h = hardware()
        assertTrue(h.down(up, 0))
        assertTrue(h.down(down, 80))
        h.tickUntil(80 + CHORD_HOLD_MS - 1)
        assertEquals(0, toggles)
        h.tickUntil(80 + CHORD_HOLD_MS)
        assertEquals(1, toggles)
        // Key repeats while still held never fire again
        for (t in 700L..3000L step 50) {
            assertTrue(h.down(up, t, repeat = 1))
            assertTrue(h.down(down, t, repeat = 1))
        }
        assertTrue(h.up(up, 3100))
        assertTrue(h.up(down, 3120))
        assertEquals(1, toggles)
        assertTrue(volumeSteps.isEmpty())
        assertNull(h.nextDeadlineMs)
    }

    @Test
    fun holdIsAlsoDetectedFromKeyRepeatsWithoutTimer() {
        val h = hardware()
        h.down(down, 0)
        h.down(up, 50)
        h.down(up, 50 + CHORD_HOLD_MS, repeat = 5)
        assertEquals(1, toggles)
    }

    @Test
    fun aSecondPressStopsTheMemo() {
        val h = hardware()
        h.down(up, 0); h.down(down, 10); h.tickUntil(2000); h.up(up, 2000); h.up(down, 2000)
        h.down(up, 5000); h.down(down, 5050); h.tickUntil(7000); h.up(up, 7000); h.up(down, 7000)
        assertEquals(2, toggles)
    }

    @Test
    fun shortVolumePressStillChangesTheVolumeOnce() {
        val h = hardware()
        assertTrue(h.down(up, 0))
        h.tickUntil(100)
        assertTrue(h.up(up, 120))
        assertEquals(listOf(true), volumeSteps)
        assertEquals(0, toggles)
    }

    @Test
    fun holdingOneVolumeButtonKeepsAdjustingTheVolume() {
        val h = hardware()
        h.down(down, 0)
        h.tickUntil(CHORD_WINDOW_MS) // not joined by the other key: a normal press
        assertEquals(listOf(false), volumeSteps)
        h.down(down, 500, repeat = 1)
        h.down(down, 550, repeat = 2)
        h.up(down, 600)
        assertEquals(listOf(false, false, false), volumeSteps)
        assertEquals(0, toggles)
        // Pressing the other key late is a normal volume press too, not the gesture
        h.down(up, 1000); h.tickUntil(1000 + CHORD_WINDOW_MS); h.down(down, 1000 + CHORD_WINDOW_MS + 10)
        h.tickUntil(5000)
        assertEquals(0, toggles)
    }

    @Test
    fun releasingBeforeTheHoldTimeCancelsWithoutSideEffects() {
        val h = hardware()
        h.down(up, 0); h.down(down, 30)
        h.tickUntil(300)
        assertTrue(h.up(down, 300))
        h.tickUntil(2000)
        assertTrue(h.up(up, 2000))
        assertEquals(0, toggles)
        assertTrue(volumeSteps.isEmpty())
    }

    @Test
    fun disabledShortcutDoesNotConsumeVolumeKeys() {
        val h = handler(ShortcutConfig(hardwareEnabled = false))
        assertFalse(h.down(up, 0))
        assertFalse(h.up(up, 50))
        assertNull(h.nextDeadlineMs)
    }

    @Test
    fun unseenKeyUpOrRepeatIsPassedOn() {
        val h = hardware()
        assertFalse(h.down(up, 0, repeat = 3))
        assertFalse(h.up(down, 10))
    }

    // --- One volume button held ---

    @Test
    fun holdVolumeDownFiresAfterOneSecondAndShortPressesStillWork() {
        val h = hardware(HardwareGesture.HOLD_VOLUME_DOWN)
        assertFalse("Volume Up keeps its normal behaviour", h.down(up, 0))
        assertFalse(h.up(up, 50))

        h.down(down, 100); h.up(down, 300) // short press
        assertEquals(listOf(false), volumeSteps)

        h.down(down, 1000)
        h.tickUntil(1000 + SINGLE_KEY_HOLD_MS - 1)
        assertEquals(0, toggles)
        h.tickUntil(1000 + SINGLE_KEY_HOLD_MS)
        assertEquals(1, toggles)
        h.down(down, 2500, repeat = 10)
        h.up(down, 2600)
        assertEquals(1, toggles)
        assertEquals("no volume change from the gesture", listOf(false), volumeSteps)
    }

    @Test
    fun holdVolumeUpUsesTheOtherKey() {
        val h = hardware(HardwareGesture.HOLD_VOLUME_UP)
        assertFalse(h.down(down, 0))
        h.down(up, 100); h.tickUntil(100 + SINGLE_KEY_HOLD_MS); h.up(up, 2000)
        assertEquals(1, toggles)
    }

    @Test
    fun togglesAreAtLeastTheMinimumIntervalApart() {
        val h = hardware(HardwareGesture.HOLD_VOLUME_DOWN)
        h.down(down, 0); h.tickUntil(SINGLE_KEY_HOLD_MS); h.up(down, SINGLE_KEY_HOLD_MS)
        // A bouncing button: a second complete hold could not happen this fast, but a keyboard press could
        val k = handler(ShortcutConfig(keyboardEnabled = true, keyboardShortcut = KeyboardShortcut(KeyEvent.KEYCODE_F9, 0)))
        k.down(KeyEvent.KEYCODE_F9, 0); k.up(KeyEvent.KEYCODE_F9, 20)
        k.down(KeyEvent.KEYCODE_F9, 300); k.up(KeyEvent.KEYCODE_F9, 320)
        k.down(KeyEvent.KEYCODE_F9, 900); k.up(KeyEvent.KEYCODE_F9, 920)
        assertEquals(1 + 2, toggles)
    }

    // --- Keyboard ---

    private val ctrlShiftR = KeyboardShortcut(KeyEvent.KEYCODE_R, CTRL or SHIFT)

    private fun keyboard(shortcut: KeyboardShortcut = ctrlShiftR) = handler(ShortcutConfig(keyboardEnabled = true, keyboardShortcut = shortcut))

    @Test
    fun keyboardShortcutFiresOnceOnKeyDownAndConsumesRepeatsAndKeyUp() {
        val h = keyboard()
        assertTrue(h.down(KeyEvent.KEYCODE_R, 0, modifiers = CTRL or SHIFT))
        for (r in 1..20) assertTrue(h.down(KeyEvent.KEYCODE_R, 30L * r, repeat = r, modifiers = CTRL or SHIFT))
        assertTrue(h.up(KeyEvent.KEYCODE_R, 700, modifiers = CTRL or SHIFT))
        assertEquals(1, toggles)
        // Released modifiers before the key: the key-up still belongs to the shortcut
        h.down(KeyEvent.KEYCODE_R, 2000, modifiers = CTRL or SHIFT)
        assertTrue(h.up(KeyEvent.KEYCODE_R, 2100, modifiers = 0))
        assertEquals(2, toggles)
    }

    @Test
    fun otherKeysAndModifiersAreIgnored() {
        val h = keyboard()
        assertFalse(h.down(KeyEvent.KEYCODE_R, 0))
        assertFalse(h.down(KeyEvent.KEYCODE_R, 10, modifiers = CTRL))
        assertFalse(h.down(KeyEvent.KEYCODE_R, 20, modifiers = CTRL or SHIFT or ALT))
        assertFalse(h.down(KeyEvent.KEYCODE_T, 30, modifiers = CTRL or SHIFT))
        assertFalse(h.up(KeyEvent.KEYCODE_R, 40))
        assertEquals(0, toggles)
    }

    @Test
    fun onScreenKeyboardEventsNeverTrigger() {
        val h = keyboard()
        assertFalse(h.down(KeyEvent.KEYCODE_R, 0, modifiers = CTRL or SHIFT, virtual = true))
        assertEquals(0, toggles)
    }

    @Test
    fun whileTypingOnlyShortcutsWithCommandModifiersFire() {
        val withModifiers = keyboard()
        assertTrue(withModifiers.down(KeyEvent.KEYCODE_R, 0, modifiers = CTRL or SHIFT, text = true))
        assertEquals(1, toggles)
        val functionKey = keyboard(KeyboardShortcut(KeyEvent.KEYCODE_F9, 0))
        assertFalse(functionKey.down(KeyEvent.KEYCODE_F9, 5000, text = true))
        assertEquals(1, toggles)
        assertTrue(functionKey.down(KeyEvent.KEYCODE_F9, 6000, text = false))
        assertEquals(2, toggles)
    }

    @Test
    fun disabledKeyboardShortcutDoesNothing() {
        val h = handler(ShortcutConfig(keyboardEnabled = false, keyboardShortcut = ctrlShiftR))
        assertFalse(h.down(KeyEvent.KEYCODE_R, 0, modifiers = CTRL or SHIFT))
        assertEquals(0, toggles)
    }

    @Test
    fun changingTheConfigurationResetsHalfFinishedGestures() {
        val h = hardware()
        h.down(up, 0); h.down(down, 10)
        h.config = ShortcutConfig(hardwareEnabled = false)
        h.tickUntil(5000)
        assertEquals(0, toggles)
        assertNull(h.nextDeadlineMs)
    }

    // --- Validation and labels ---

    @Test
    fun shortcutValidationRejectsTypingSystemAndEditingKeys() {
        assertNull(KeyboardShortcut.validate(KeyEvent.KEYCODE_R, CTRL or SHIFT))
        assertNull(KeyboardShortcut.validate(KeyEvent.KEYCODE_M, ALT))
        assertNull(KeyboardShortcut.validate(KeyEvent.KEYCODE_F9, 0))
        assertNull(KeyboardShortcut.validate(KeyEvent.KEYCODE_MEDIA_RECORD, 0))
        assertEquals(Problem.TYPING_KEY, KeyboardShortcut.validate(KeyEvent.KEYCODE_R, 0))
        assertEquals(Problem.TYPING_KEY, KeyboardShortcut.validate(KeyEvent.KEYCODE_R, SHIFT))
        assertEquals(Problem.TYPING_KEY, KeyboardShortcut.validate(KeyEvent.KEYCODE_SPACE, 0))
        assertEquals(Problem.MODIFIER_ONLY, KeyboardShortcut.validate(KeyEvent.KEYCODE_CTRL_LEFT, CTRL))
        assertEquals(Problem.RESERVED, KeyboardShortcut.validate(KeyEvent.KEYCODE_VOLUME_UP, CTRL))
        assertEquals(Problem.RESERVED, KeyboardShortcut.validate(KeyEvent.KEYCODE_BACK, 0))
        assertEquals(Problem.RESERVED, KeyboardShortcut.validate(KeyEvent.KEYCODE_ESCAPE, CTRL))
        assertEquals(Problem.EDITING_SHORTCUT, KeyboardShortcut.validate(KeyEvent.KEYCODE_C, CTRL))
        assertEquals(Problem.EDITING_SHORTCUT, KeyboardShortcut.validate(KeyEvent.KEYCODE_Z, CTRL or SHIFT))
        assertNull(KeyboardShortcut.validate(KeyEvent.KEYCODE_C, CTRL or ALT))
        assertEquals(Problem.SYSTEM_SHORTCUT, KeyboardShortcut.validate(KeyEvent.KEYCODE_TAB, ALT))
    }

    @Test
    fun modifiersAndLabels() {
        assertEquals(CTRL or SHIFT, KeyboardShortcut.modifiersOf(KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_NUM_LOCK_ON))
        assertEquals("Ctrl+Shift+R", ctrlShiftR.label)
        assertEquals("F9", KeyboardShortcut(KeyEvent.KEYCODE_F9, 0).label)
        assertEquals("Alt+7", KeyboardShortcut(KeyEvent.KEYCODE_7, ALT).label)
    }

    @Test
    fun debouncerLetsOneRequestThroughPerInterval() {
        val d = ToggleDebouncer(800)
        assertTrue(d.tryAcquire(0))
        assertFalse(d.tryAcquire(500))
        assertTrue(d.tryAcquire(800))
        assertFalse(d.tryAcquire(1599))
    }
}
