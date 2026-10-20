package io.github.elgrande73.voicememo.shortcuts

import android.view.KeyEvent

/**
 * Hardware-button gestures that start or stop a memo. Android only delivers volume-key events to an app whose window
 * has focus, or to an enabled accessibility service while the screen is on; see docs/SHORTCUTS_AND_LOCK_SCREEN.md.
 * The names are persisted: never rename them.
 */
enum class HardwareGesture(val title: String, val description: String) {
    HOLD_BOTH_VOLUME("Hold both volume buttons", "Press Volume Up and Volume Down together and hold for about half a second."),
    HOLD_VOLUME_DOWN("Hold Volume Down", "Hold Volume Down for one second. A short press still lowers the volume."),
    HOLD_VOLUME_UP("Hold Volume Up", "Hold Volume Up for one second. A short press still raises the volume.")
}

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

/** Persisted shortcut settings. Everything is off until the user enables it. */
data class ShortcutConfig(
    val hardwareEnabled: Boolean = false,
    val hardwareGesture: HardwareGesture = HardwareGesture.HOLD_BOTH_VOLUME,
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
 * Turns key events into "toggle recording" requests.
 *
 * - Each gesture fires at most once per press: nothing fires again until every involved key is released, and two
 *   toggles are at least [MIN_TOGGLE_INTERVAL_MS] apart.
 * - Volume keys involved in a gesture are consumed. A press that turns out not to be the gesture is replayed as a
 *   normal volume step through [onVolumeStep] (at release, or after [CHORD_WINDOW_MS] while held, then once per key
 *   repeat), so the volume buttons keep working.
 * - Keyboard shortcuts fire on the key-down of the main key (repeats and the key-up are consumed), never for events
 *   from on-screen keyboards, and while a text field has focus only if they include Ctrl, Alt or Meta.
 *
 * Time is passed in explicitly; the host must call [onTick] at [nextDeadlineMs] (hold detection without key repeats).
 * Not thread-safe: use it from one thread (the main thread in practice).
 */
class RecordingShortcutHandler(
    config: ShortcutConfig,
    private val onToggle: () -> Unit,
    private val onVolumeStep: (raise: Boolean) -> Unit
) {
    var config: ShortcutConfig = config
        set(value) {
            field = value
            reset()
        }

    private sealed interface Phase {
        data object Idle : Phase
        /** One volume key is down; waiting to see whether the other one follows (chord) or it is a volume press. */
        data class Pending(val keyCode: Int, val deadline: Long) : Phase
        /** Not a gesture: volume keys behave normally until all are released. */
        data object PassThrough : Phase
        /** Gesture keys held; fires at [deadline]. */
        data class Holding(val deadline: Long) : Phase
        /** Gesture fired or abandoned: consume until all gesture keys are released. */
        data object Latched : Phase
    }

    private var phase: Phase = Phase.Idle
    private val volumeKeysDown = mutableSetOf<Int>()
    private var latchedKeyboardKey: Int? = null
    private var lastToggleMs = Long.MIN_VALUE / 2

    val nextDeadlineMs: Long?
        get() = when (val p = phase) {
            is Phase.Pending -> p.deadline
            is Phase.Holding -> p.deadline
            else -> null
        }

    fun reset() {
        phase = Phase.Idle
        volumeKeysDown.clear()
        latchedKeyboardKey = null
    }

    /** @return true if the event was consumed (must not reach the system or other views). */
    fun onKey(input: ShortcutKeyInput): Boolean {
        if (input.keyCode == KeyEvent.KEYCODE_VOLUME_UP || input.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (!config.hardwareEnabled && phase == Phase.Idle) return false
            return onVolumeKey(input)
        }
        return onKeyboardKey(input)
    }

    fun onTick(timeMs: Long) {
        when (val p = phase) {
            is Phase.Pending -> if (timeMs >= p.deadline) {
                // Held alone past the chord window: it is a normal volume press
                phase = Phase.PassThrough
                onVolumeStep(p.keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            }
            is Phase.Holding -> if (timeMs >= p.deadline) fire(timeMs)
            else -> {}
        }
    }

    private fun onVolumeKey(input: ShortcutKeyInput): Boolean {
        val key = input.keyCode
        val raise = key == KeyEvent.KEYCODE_VOLUME_UP
        val gesture = config.hardwareGesture
        if (gesture != HardwareGesture.HOLD_BOTH_VOLUME && key != targetKey(gesture)) {
            return false // the other volume key keeps its normal behaviour
        }

        if (!input.isDown) {
            volumeKeysDown.remove(key)
            when (val p = phase) {
                is Phase.Pending -> if (p.keyCode == key) { onVolumeStep(raise); phase = Phase.Idle }
                is Phase.Holding -> {
                    // Released before the hold time: a single-key gesture was a short volume press; an
                    // unfinished chord is abandoned without changing the volume
                    if (gesture != HardwareGesture.HOLD_BOTH_VOLUME) onVolumeStep(raise)
                    phase = if (volumeKeysDown.isEmpty()) Phase.Idle else Phase.Latched
                }
                Phase.PassThrough, Phase.Latched -> if (volumeKeysDown.isEmpty()) phase = Phase.Idle
                Phase.Idle -> return false
            }
            return true
        }

        if (input.repeatCount > 0) {
            onTick(input.timeMs)
            if (phase == Phase.PassThrough) onVolumeStep(raise)
            return phase != Phase.Idle
        }

        volumeKeysDown += key
        when (val p = phase) {
            Phase.Idle -> phase = if (gesture == HardwareGesture.HOLD_BOTH_VOLUME) {
                Phase.Pending(key, input.timeMs + CHORD_WINDOW_MS)
            } else {
                Phase.Holding(input.timeMs + SINGLE_KEY_HOLD_MS)
            }
            is Phase.Pending -> if (p.keyCode != key) phase = Phase.Holding(input.timeMs + CHORD_HOLD_MS)
            Phase.PassThrough -> onVolumeStep(raise)
            is Phase.Holding, Phase.Latched -> {}
        }
        return true
    }

    private fun onKeyboardKey(input: ShortcutKeyInput): Boolean {
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
        fire(input.timeMs)
        return true
    }

    private fun fire(timeMs: Long) {
        if (phase is Phase.Holding) phase = Phase.Latched
        if (timeMs - lastToggleMs < MIN_TOGGLE_INTERVAL_MS) return
        lastToggleMs = timeMs
        onToggle()
    }

    private fun targetKey(gesture: HardwareGesture) =
        if (gesture == HardwareGesture.HOLD_VOLUME_UP) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN

    companion object {
        /** Both volume keys must go down within this time to count as pressed together. */
        const val CHORD_WINDOW_MS = 200L
        /** Hold time for the two-key gesture; well below Android's 3 s accessibility shortcut. */
        const val CHORD_HOLD_MS = 600L
        /** Hold time for a single volume key; longer, because single keys are often held to change the volume. */
        const val SINGLE_KEY_HOLD_MS = 1000L
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
