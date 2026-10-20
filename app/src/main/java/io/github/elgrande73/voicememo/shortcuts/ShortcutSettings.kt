package io.github.elgrande73.voicememo.shortcuts

import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.elgrande73.voicememo.settings.PreferenceKeys

/** Reads and writes the shortcut and lock-screen settings; corrupt or unknown values fall back to the defaults. */
object ShortcutSettings {

    /** Lock-screen recording controls are on unless the user turns them off. */
    const val DEFAULT_LOCK_SCREEN_CONTROLS = true

    fun load(prefs: SharedPreferences): ShortcutConfig {
        val keyCode = readInt(prefs, PreferenceKeys.KEYBOARD_SHORTCUT_KEY_CODE)
        val modifiers = readInt(prefs, PreferenceKeys.KEYBOARD_SHORTCUT_MODIFIERS) ?: 0
        val shortcut = keyCode
            ?.takeIf { KeyboardShortcut.validate(it, modifiers) == null }
            ?.let { KeyboardShortcut(it, modifiers) }
        val gesture = readString(prefs, PreferenceKeys.HARDWARE_SHORTCUT_GESTURE)
            ?.let { name -> HardwareGesture.entries.firstOrNull { it.name == name } }
            ?: HardwareGesture.HOLD_BOTH_VOLUME
        return ShortcutConfig(
            hardwareEnabled = readBoolean(prefs, PreferenceKeys.HARDWARE_SHORTCUT_ENABLED) ?: false,
            hardwareGesture = gesture,
            keyboardEnabled = (readBoolean(prefs, PreferenceKeys.KEYBOARD_SHORTCUT_ENABLED) ?: false) && shortcut != null,
            keyboardShortcut = shortcut
        )
    }

    fun save(prefs: SharedPreferences, config: ShortcutConfig) {
        prefs.edit {
            putBoolean(PreferenceKeys.HARDWARE_SHORTCUT_ENABLED, config.hardwareEnabled)
            putString(PreferenceKeys.HARDWARE_SHORTCUT_GESTURE, config.hardwareGesture.name)
            putBoolean(PreferenceKeys.KEYBOARD_SHORTCUT_ENABLED, config.keyboardEnabled && config.keyboardShortcut != null)
            val shortcut = config.keyboardShortcut
            if (shortcut != null) {
                putInt(PreferenceKeys.KEYBOARD_SHORTCUT_KEY_CODE, shortcut.keyCode)
                putInt(PreferenceKeys.KEYBOARD_SHORTCUT_MODIFIERS, shortcut.modifiers)
            } else {
                remove(PreferenceKeys.KEYBOARD_SHORTCUT_KEY_CODE)
                remove(PreferenceKeys.KEYBOARD_SHORTCUT_MODIFIERS)
            }
        }
    }

    fun lockScreenControlsEnabled(prefs: SharedPreferences): Boolean =
        readBoolean(prefs, PreferenceKeys.LOCK_SCREEN_CONTROLS_ENABLED) ?: DEFAULT_LOCK_SCREEN_CONTROLS

    fun setLockScreenControlsEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit { putBoolean(PreferenceKeys.LOCK_SCREEN_CONTROLS_ENABLED, enabled) }
    }

    private fun readInt(prefs: SharedPreferences, key: String): Int? =
        if (!prefs.contains(key)) null else try { prefs.getInt(key, 0) } catch (_: ClassCastException) { null }

    private fun readBoolean(prefs: SharedPreferences, key: String): Boolean? =
        if (!prefs.contains(key)) null else try { prefs.getBoolean(key, false) } catch (_: ClassCastException) { null }

    private fun readString(prefs: SharedPreferences, key: String): String? =
        if (!prefs.contains(key)) null else try { prefs.getString(key, null) } catch (_: ClassCastException) { null }
}
