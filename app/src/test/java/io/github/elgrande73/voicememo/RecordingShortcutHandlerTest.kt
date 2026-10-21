package io.github.elgrande73.voicememo

import android.view.KeyEvent
import io.github.elgrande73.voicememo.shortcuts.KeyboardShortcut
import io.github.elgrande73.voicememo.shortcuts.KeyboardShortcut.Companion.ALT
import io.github.elgrande73.voicememo.shortcuts.KeyboardShortcut.Companion.CTRL
import io.github.elgrande73.voicememo.shortcuts.KeyboardShortcut.Companion.SHIFT
import io.github.elgrande73.voicememo.shortcuts.KeyboardShortcut.Problem
import io.github.elgrande73.voicememo.shortcuts.RecordingShortcutHandler
import io.github.elgrande73.voicememo.shortcuts.ShortcutConfig
import io.github.elgrande73.voicememo.shortcuts.ShortcutKeyInput
import io.github.elgrande73.voicememo.shortcuts.ToggleDebouncer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure state-machine tests of the keyboard shortcut (no Android runtime); times are explicit. */
class RecordingShortcutHandlerTest {

    private var toggles = 0

    private fun handler(config: ShortcutConfig) = RecordingShortcutHandler(config) { toggles++ }

    private fun RecordingShortcutHandler.down(key: Int, t: Long, repeat: Int = 0, modifiers: Int = 0, virtual: Boolean = false, text: Boolean = false) =
        onKey(ShortcutKeyInput(true, key, repeat, modifiers, t, virtual, text))

    private fun RecordingShortcutHandler.up(key: Int, t: Long, modifiers: Int = 0) = onKey(ShortcutKeyInput(false, key, 0, modifiers, t))

    @Test
    fun togglesAreAtLeastTheMinimumIntervalApart() {
        val k = handler(ShortcutConfig(keyboardEnabled = true, keyboardShortcut = KeyboardShortcut(KeyEvent.KEYCODE_F9, 0)))
        k.down(KeyEvent.KEYCODE_F9, 0); k.up(KeyEvent.KEYCODE_F9, 20)
        k.down(KeyEvent.KEYCODE_F9, 300); k.up(KeyEvent.KEYCODE_F9, 320)
        k.down(KeyEvent.KEYCODE_F9, 900); k.up(KeyEvent.KEYCODE_F9, 920)
        assertEquals(2, toggles)
    }

    @Test
    fun volumeKeysAreNeverConsumed() {
        // The volume-button shortcut was removed: volume keys always keep their normal behaviour
        val h = keyboard()
        for (key in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) {
            assertFalse(h.down(key, 0))
            assertFalse(h.down(key, 600, repeat = 1))
            assertFalse(h.up(key, 1200))
        }
        assertEquals(0, toggles)
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
    fun changingTheConfigurationReleasesAHeldShortcutKey() {
        val h = keyboard()
        h.down(KeyEvent.KEYCODE_R, 0, modifiers = CTRL or SHIFT)
        h.config = ShortcutConfig()
        // The key-up of the earlier press is no longer the shortcut's: passed on
        assertFalse(h.up(KeyEvent.KEYCODE_R, 100, modifiers = CTRL or SHIFT))
        assertEquals(1, toggles)
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
