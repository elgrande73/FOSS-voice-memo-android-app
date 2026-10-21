package io.github.elgrande73.voicememo.shortcuts

import android.view.KeyEvent

/** A key on an external keyboard plus the modifier keys that must be held with it. */
data class KeyboardShortcut(val keyCode: Int, val modifiers: Int) {

    val hasCommandModifier: Boolean get() = modifiers and (CTRL or ALT or META) != 0

    /** Human-readable form, e.g. "Ctrl+Shift+R". */
    val label: String
        get() = buildList {
            if (modifiers and CTRL != 0) add("Ctrl")
            if (modifiers and ALT != 0) add("Alt")
            if (modifiers and SHIFT != 0) add("Shift")
            if (modifiers and META != 0) add("Meta")
            add(keyLabel(keyCode))
        }.joinToString("+")

    enum class Problem(val message: String) {
        MODIFIER_ONLY("Press a key together with the modifier keys (for example Ctrl+Shift+R)."),
        RESERVED("This key is used by Android for navigation, volume or power. Choose another key."),
        TYPING_KEY("Plain typing keys would start recordings while you type. Add Ctrl, Alt or Meta, or use F1–F12."),
        EDITING_SHORTCUT("This combination is a common text-editing shortcut. Choose another combination."),
        SYSTEM_SHORTCUT("This combination switches apps on many devices. Choose another combination.")
    }

    companion object {
        const val CTRL = 1
        const val ALT = 2
        const val SHIFT = 4
        const val META = 8

        /** Modifier bitmask of a KeyEvent meta state (Caps Lock, Num Lock and Fn are ignored). */
        fun modifiersOf(metaState: Int): Int {
            var m = 0
            if (metaState and KeyEvent.META_CTRL_ON != 0) m = m or CTRL
            if (metaState and KeyEvent.META_ALT_ON != 0) m = m or ALT
            if (metaState and KeyEvent.META_SHIFT_ON != 0) m = m or SHIFT
            if (metaState and KeyEvent.META_META_ON != 0) m = m or META
            return m
        }

        private val MODIFIER_KEYS = setOf(
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_FUNCTION, KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_SCROLL_LOCK,
            KeyEvent.KEYCODE_SYM
        )

        private val RESERVED_KEYS = setOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP,
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_UNKNOWN
        )

        /** Keys that never produce text, so they are safe without a modifier. */
        private val STANDALONE_KEYS = (KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12).toSet() + KeyEvent.KEYCODE_MEDIA_RECORD

        private val EDITING_KEYS = setOf(
            KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_V, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_Y
        )

        /** Why a combination cannot be used, or null if it can. */
        fun validate(keyCode: Int, modifiers: Int): Problem? {
            if (keyCode in MODIFIER_KEYS) return Problem.MODIFIER_ONLY
            if (keyCode in RESERVED_KEYS) return Problem.RESERVED
            val command = modifiers and (CTRL or ALT or META)
            if (command == 0) return if (keyCode in STANDALONE_KEYS) null else Problem.TYPING_KEY
            if (command == CTRL && keyCode in EDITING_KEYS) return Problem.EDITING_SHORTCUT
            if (keyCode == KeyEvent.KEYCODE_TAB && modifiers and (ALT or META) != 0) return Problem.SYSTEM_SHORTCUT
            return null
        }

        fun keyLabel(keyCode: Int): String = when (keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${keyCode - KeyEvent.KEYCODE_F1 + 1}"
            KeyEvent.KEYCODE_MEDIA_RECORD -> "Record"
            KeyEvent.KEYCODE_SPACE -> "Space"
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_TAB -> "Tab"
            else -> "Key $keyCode"
        }
    }
}

/** Persisted shortcut settings. Off until the user enables it. */
data class ShortcutConfig(
    val keyboardEnabled: Boolean = false,
    val keyboardShortcut: KeyboardShortcut? = null
)

/** One key event, reduced to what the shortcut logic needs (keeps it independent of android.view.KeyEvent). */
data class ShortcutKeyInput(
    val isDown: Boolean,
    val keyCode: Int,
    val repeatCount: Int,
    val modifiers: Int,
    val timeMs: Long,
    /** Event produced by an on-screen keyboard (IME) rather than a physical key. */
    val fromVirtualKeyboard: Boolean = false,
    /** A text field of this app has focus. */
    val textInputFocused: Boolean = false
)

/**
 * Turns key events into "toggle recording" requests for the keyboard shortcut: it fires on the key-down of the main
 * key with exactly the saved modifiers (repeats and the key-up are consumed), never for events from on-screen
 * keyboards, and while a text field has focus only if the shortcut includes Ctrl, Alt or Meta. Two toggles are at
 * least [MIN_TOGGLE_INTERVAL_MS] apart. Not thread-safe: use it from one thread (the main thread in practice).
 */
class RecordingShortcutHandler(
    config: ShortcutConfig,
    private val onToggle: () -> Unit
) {
    var config: ShortcutConfig = config
        set(value) {
            field = value
            reset()
        }

    private var latchedKeyboardKey: Int? = null
    private var lastToggleMs = Long.MIN_VALUE / 2

    fun reset() {
        latchedKeyboardKey = null
    }

    /** @return true if the event was consumed (must not reach the system or other views). */
    fun onKey(input: ShortcutKeyInput): Boolean {
        val latched = latchedKeyboardKey
        if (latched != null && input.keyCode == latched) {
            if (!input.isDown) latchedKeyboardKey = null
            return true
        }
        val shortcut = config.keyboardShortcut ?: return false
        if (!config.keyboardEnabled || !input.isDown || input.repeatCount > 0 || input.fromVirtualKeyboard) return false
        if (input.keyCode != shortcut.keyCode || input.modifiers != shortcut.modifiers) return false
        if (input.textInputFocused && !shortcut.hasCommandModifier) return false
        latchedKeyboardKey = input.keyCode
        if (input.timeMs - lastToggleMs >= MIN_TOGGLE_INTERVAL_MS) {
            lastToggleMs = input.timeMs
            onToggle()
        }
        return true
    }

    companion object {
        const val MIN_TOGGLE_INTERVAL_MS = 800L
    }
}

/** Lets one request through per [minIntervalMs]; used where several inputs can ask for the same toggle at once. */
class ToggleDebouncer(private val minIntervalMs: Long) {
    private var last = Long.MIN_VALUE / 2

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        if (nowMs - last < minIntervalMs) return false
        last = nowMs
        return true
    }
}
