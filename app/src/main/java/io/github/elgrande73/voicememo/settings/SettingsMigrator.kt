package io.github.elgrande73.voicememo.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Names of persisted preference files and keys. These are stored on the user's device:
 * never rename them, or existing Voice Memo installations would lose their configuration.
 */
object PreferenceKeys {
    const val PREFS_NAME = "voice_memo_preferences"

    const val SETTINGS_SCHEMA_VERSION = "settingsSchemaVersion"
    const val MATCH_CONFIDENCE_THRESHOLD_PERCENT = "matchConfidenceThresholdPercent"

    /** Optional minimum audio level of the wake phrase (see WakePhraseLevel). */
    const val WAKE_PHRASE_MIN_LEVEL_ENABLED = "wakePhraseMinLevelEnabled"
    const val WAKE_PHRASE_MIN_LEVEL_DBFS = "wakePhraseMinLevelDbfs"
    /** History playback mode (PlaybackMode.id); absent means the default. */
    const val HISTORY_PLAYBACK_MODE = "historyPlaybackMode"
    /** True until the user dismissed the notice that the built-in wake phrase changed (set by migration v4). */
    const val WAKE_PHRASE_CHANGED_NOTICE = "wakePhraseChangedNotice"
    /** Loudness-only activation threshold, VAD dB scale (dBFS + 90). Key predates the versioned settings. */
    const val ACTIVATION_THRESHOLD_DB = "thresholdDb"

    /** On-device transcription (see transcription.Transcription). */
    const val TRANSCRIPTION_LANGUAGE = "transcriptionLanguage"
    const val TRANSCRIPTION_QUALITY = "transcriptionQuality"
    /** Automatic transcription of new memos (key predates the versioned settings). */
    const val SPEECH_TO_TEXT_ENABLED = "speechToTextEnabled"

    /** Shortcut settings (see shortcuts.ShortcutSettings); absent means the default. */
    const val HARDWARE_SHORTCUT_ENABLED = "hardwareShortcutEnabled"
    const val HARDWARE_SHORTCUT_GESTURE = "hardwareShortcutGesture"
    const val KEYBOARD_SHORTCUT_ENABLED = "keyboardShortcutEnabled"
    const val KEYBOARD_SHORTCUT_KEY_CODE = "keyboardShortcutKeyCode"
    const val KEYBOARD_SHORTCUT_MODIFIERS = "keyboardShortcutModifiers"
    const val LOCK_SCREEN_CONTROLS_ENABLED = "lockScreenControlsEnabled"

    /** Pre-standardization key holding the detector "keyword sensitivity" (0.10..0.95, higher = easier). */
    const val LEGACY_KEYWORD_SENSITIVITY = "keywordSensitivity"
}

/**
 * Versioned, idempotent migrations for [PreferenceKeys.PREFS_NAME]. Safe to run on every start:
 * each step runs once (guarded by [PreferenceKeys.SETTINGS_SCHEMA_VERSION]), values the user already
 * saved are never replaced by defaults, and unrelated keys are never touched.
 */
object SettingsMigrator {
    const val CURRENT_VERSION = 4

    private const val TAG = "SettingsMigrator"

    /** Lower bound of the threshold when migration step v1 was written (the range is now wider). */
    private const val V1_MIN_PERCENT = 50

    /** Default activation threshold (VAD dB scale) of versions before v2, used if the user never changed it. */
    private const val V1_ACTIVATION_THRESHOLD_DB = 48f

    fun preferences(context: Context): SharedPreferences {
        val prefs = context.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        migrate(prefs)
        return prefs
    }

    fun migrate(prefs: SharedPreferences) {
        val version = readInt(prefs, PreferenceKeys.SETTINGS_SCHEMA_VERSION) ?: 0
        // Anything stored before the first migration means an existing installation, not a fresh one
        val existingInstallation = version >= 1 || prefs.all.isNotEmpty()
        val editor = prefs.edit()

        if (version < 1) {
            migrateToV1(prefs, editor)
        }
        if (version < 2) {
            migrateToV2(prefs, editor, existingInstallation)
        }
        // v3 stored a choice of built-in wake phrase; v4 replaces it, so there is nothing to do for v3 itself
        if (version < 4) {
            migrateToV4(prefs, editor, existingInstallation)
        }

        // Always validate the authoritative threshold, so a corrupt or out-of-range value can never reach the detector
        val stored = readInt(prefs, PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT)
        if (stored != null && stored != MatchConfidenceThreshold.clampPercent(stored)) {
            editor.putInt(PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT, MatchConfidenceThreshold.clampPercent(stored))
        } else if (stored == null && version >= 1) {
            Log.w(TAG, "Match confidence threshold missing or corrupt; restoring default")
            editor.putInt(PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT, MatchConfidenceThreshold.DEFAULT_PERCENT)
        }

        if (version < CURRENT_VERSION) {
            editor.putInt(PreferenceKeys.SETTINGS_SCHEMA_VERSION, CURRENT_VERSION)
        }
        if (!editor.commit()) {
            Log.e(TAG, "Failed to persist settings migration")
        }
    }

    /**
     * v1: introduces the single match-confidence threshold (percent). A legacy keyword sensitivity is converted
     * into the confidence threshold it effectively produced, then clamped to the supported range.
     */
    private fun migrateToV1(prefs: SharedPreferences, editor: SharedPreferences.Editor) {
        if (readInt(prefs, PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT) == null) {
            val legacySensitivity = readFloat(prefs, PreferenceKeys.LEGACY_KEYWORD_SENSITIVITY)
            val percent = if (legacySensitivity != null) {
                // Frozen behaviour of this step: it always clamped to the 50–80 % range of its time
                Math.round(legacySensitivityToConfidence(legacySensitivity) * 100f).coerceIn(V1_MIN_PERCENT, MatchConfidenceThreshold.MAX_PERCENT)
            } else {
                MatchConfidenceThreshold.DEFAULT_PERCENT
            }
            editor.putInt(PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT, percent)
        }
        // The legacy key is only removed in the same commit that stores its migrated value
        editor.remove(PreferenceKeys.LEGACY_KEYWORD_SENSITIVITY)
    }

    /**
     * v2: the wake phrase's loudness condition becomes its own, optional setting ("Minimum audio level", dBFS).
     * Until v1 the wake phrase always had to reach the loudness-mode activation threshold, so existing installations
     * keep exactly that level (condition on); new installations get the measured default.
     */
    private fun migrateToV2(prefs: SharedPreferences, editor: SharedPreferences.Editor, existingInstallation: Boolean) {
        if (!prefs.contains(PreferenceKeys.WAKE_PHRASE_MIN_LEVEL_ENABLED)) {
            editor.putBoolean(PreferenceKeys.WAKE_PHRASE_MIN_LEVEL_ENABLED, if (existingInstallation) true else WakePhraseLevel.DEFAULT_ENABLED)
        }
        if (readFloat(prefs, PreferenceKeys.WAKE_PHRASE_MIN_LEVEL_DBFS) == null) {
            val dbfs = if (existingInstallation) {
                WakePhraseLevel.fromDetectorDb(readFloat(prefs, PreferenceKeys.ACTIVATION_THRESHOLD_DB) ?: V1_ACTIVATION_THRESHOLD_DB)
            } else {
                WakePhraseLevel.DEFAULT_DBFS
            }
            editor.putFloat(PreferenceKeys.WAKE_PHRASE_MIN_LEVEL_DBFS, WakePhraseLevel.clampDbfs(dbfs))
        }
    }

    /**
     * v4: one built-in wake phrase ("Mango Tango") and one detection method (template matching).
     * - Settings of removed features are dropped: the choice of built-in phrase (v3) and the experimental
     *   speech-checked detection with the typed words of the recorded phrase. Installations that used the speech check
     *   continue with template matching and the same enrolled samples; samples are never touched here.
     * - An existing installation whose built-in phrase was not already "Mango Tango" is told once (Settings, dashboard)
     *   that the built-in phrase changed; its recorded samples keep working as before.
     */
    private fun migrateToV4(prefs: SharedPreferences, editor: SharedPreferences.Editor, existingInstallation: Boolean) {
        if (existingInstallation && readString(prefs, V3_BUILT_IN_WAKE_PHRASE) != V3_MANGO_TANGO_ID) {
            editor.putBoolean(PreferenceKeys.WAKE_PHRASE_CHANGED_NOTICE, true)
        }
        editor.remove(V3_BUILT_IN_WAKE_PHRASE)
        editor.remove(REMOVED_WAKE_WORD_MODEL)
        editor.remove(REMOVED_RECORDED_WAKE_PHRASE_TEXT)
    }

    /** Whether the one-time notice about the changed built-in wake phrase is still to be shown. */
    fun wakePhraseChangedNotice(prefs: SharedPreferences): Boolean = try {
        prefs.getBoolean(PreferenceKeys.WAKE_PHRASE_CHANGED_NOTICE, false)
    } catch (_: ClassCastException) {
        false
    }

    // Keys of settings removed by v4 (read or removed only by the migration)
    private const val V3_BUILT_IN_WAKE_PHRASE = "builtInWakePhrase"
    private const val V3_MANGO_TANGO_ID = "mango_tango"
    private const val REMOVED_WAKE_WORD_MODEL = "wakeWordModel"
    private const val REMOVED_RECORDED_WAKE_PHRASE_TEXT = "recordedWakePhraseText"

    /**
     * Effective confidence threshold of the former sensitivity model: a match required
     * `distance <= 0.28 + (s - 0.5) * 0.12` and `confidence >= clamp(0.42 - (s - 0.5) * 0.20, 0.25, 0.75)`,
     * with `confidence = 1 - distance / 0.40`; both reduce to a single minimum confidence.
     */
    fun legacySensitivityToConfidence(sensitivity: Float): Float {
        val s = sensitivity.coerceIn(0.1f, 0.95f)
        val maxDistance = 0.28f + (s - 0.50f) * 0.12f
        val minConfidence = (0.42f - (s - 0.50f) * 0.20f).coerceIn(0.25f, 0.75f)
        return maxOf(minConfidence, 1.0f - maxDistance / 0.40f).coerceIn(0f, 1f)
    }

    private fun readInt(prefs: SharedPreferences, key: String): Int? {
        if (!prefs.contains(key)) return null
        return try {
            prefs.getInt(key, 0)
        } catch (e: ClassCastException) {
            Log.w(TAG, "Ignoring corrupt preference '$key'", e)
            null
        }
    }

    private fun readString(prefs: SharedPreferences, key: String): String? = try {
        prefs.getString(key, null)
    } catch (e: ClassCastException) {
        Log.w(TAG, "Ignoring corrupt preference '$key'", e)
        null
    }

    private fun readFloat(prefs: SharedPreferences, key: String): Float? {
        if (!prefs.contains(key)) return null
        return try {
            prefs.getFloat(key, 0f).takeIf { !it.isNaN() }
        } catch (e: ClassCastException) {
            Log.w(TAG, "Ignoring corrupt preference '$key'", e)
            null
        }
    }
}
