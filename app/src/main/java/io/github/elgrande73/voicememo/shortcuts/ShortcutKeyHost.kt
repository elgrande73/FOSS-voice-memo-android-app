package io.github.elgrande73.voicememo.shortcuts

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyCharacterMap
import android.view.KeyEvent
import io.github.elgrande73.voicememo.settings.PreferenceKeys

/** State shared between the activity's key handling and the places that edit text or capture a new shortcut. */
object ShortcutRuntime {
    /** A text field of this app has focus (set by the fields themselves). */
    @Volatile var textInputFocused: Boolean = false

    /** Settings is waiting for the user to press a new keyboard shortcut: shortcuts must not fire meanwhile. */
    @Volatile var captureActive: Boolean = false
}

/**
 * Connects the activity's key events to [RecordingShortcutHandler] (keyboard shortcut): reads the settings and follows
 * changes. Call [onKeyEvent] on the main thread.
 */
class ShortcutKeyHost(context: Context, private val onToggle: () -> Unit) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)

    private val handler = RecordingShortcutHandler(ShortcutSettings.load(prefs)) { onToggle() }

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.config = ShortcutSettings.load(prefs)
    }

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        handler.config = ShortcutSettings.load(prefs)
    }

    fun stop() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        handler.reset()
    }

    /** @return true if the event belongs to the shortcut and must be consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (ShortcutRuntime.captureActive) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        return handler.onKey(
            ShortcutKeyInput(
                isDown = event.action == KeyEvent.ACTION_DOWN,
                keyCode = event.keyCode,
                repeatCount = event.repeatCount,
                modifiers = KeyboardShortcut.modifiersOf(event.metaState),
                timeMs = event.eventTime,
                fromVirtualKeyboard = event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD || event.device?.isVirtual == true,
                textInputFocused = ShortcutRuntime.textInputFocused
            )
        )
    }
}
