package io.github.elgrande73.voicememo.shortcuts

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import io.github.elgrande73.voicememo.settings.PreferenceKeys

/**
 * State shared by the places that receive key events in this process (the activity and the optional accessibility
 * service).
 */
object ShortcutRuntime {
    /** A text field of this app has focus (set by the fields themselves). */
    @Volatile var textInputFocused: Boolean = false

    /** Settings is waiting for the user to press a new keyboard shortcut: shortcuts must not fire meanwhile. */
    @Volatile var captureActive: Boolean = false

    /**
     * Set while the app is in the foreground: toggles a memo the same way as the dashboard button (which may also
     * start monitoring). Null in the background, where only an already running monitoring service can record.
     */
    @Volatile var foregroundToggle: (() -> Unit)? = null
}

/**
 * Connects Android key events to [RecordingShortcutHandler]: reads the settings (and follows changes), schedules the
 * hold timer on the main thread and replays non-gesture volume presses through [AudioManager].
 * Call [onKeyEvent] on the main thread.
 */
class ShortcutKeyHost(context: Context, private val onToggle: () -> Unit) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val handler = RecordingShortcutHandler(ShortcutSettings.load(prefs), { onToggle() }) { raise ->
        try {
            audioManager?.adjustSuggestedStreamVolume(
                if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.USE_DEFAULT_STREAM_TYPE,
                AudioManager.FLAG_SHOW_UI
            )
        } catch (_: SecurityException) {
            // Do Not Disturb policy can forbid volume changes; the system would not have changed it either
        }
    }

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        handler.config = ShortcutSettings.load(prefs)
        schedule()
    }

    private val tick = Runnable {
        handler.onTick(SystemClock.uptimeMillis())
        schedule()
    }

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        handler.config = ShortcutSettings.load(prefs)
    }

    fun stop() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        mainHandler.removeCallbacks(tick)
        handler.reset()
    }

    /** @return true if the event belongs to a shortcut and must be consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (ShortcutRuntime.captureActive) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        val consumed = handler.onKey(
            ShortcutKeyInput(
                isDown = event.action == KeyEvent.ACTION_DOWN,
                keyCode = event.keyCode,
                repeatCount = event.repeatCount,
                modifiers = KeyboardShortcut.modifiersOf(event.metaState),
                timeMs = event.eventTime, // uptime base, like the tick below
                fromVirtualKeyboard = event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD || event.device?.isVirtual == true,
                textInputFocused = ShortcutRuntime.textInputFocused
            )
        )
        schedule()
        return consumed
    }

    private fun schedule() {
        mainHandler.removeCallbacks(tick)
        handler.nextDeadlineMs?.let { mainHandler.postAtTime(tick, it) }
    }
}
