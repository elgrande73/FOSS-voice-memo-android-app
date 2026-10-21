package io.github.elgrande73.voicememo.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.elgrande73.voicememo.wakephrase.BuiltInWakePhrase

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
    /** Wake-word detection backend (WakeWordModel.id); absent means Standard. */
    const val WAKE_WORD_MODEL = "wakeWordModel"
    /** Words of the user's recorded wake phrase (needed by the speech check); absent means not entered. */
    const val RECORDED_WAKE_PHRASE_TEXT = "recordedWakePhraseText"
    /** Built-in wake phrase used while no sample is enrolled (BuiltInWakePhrase.id). */
    const val BUILT_IN_WAKE_PHRASE = "builtInWakePhrase"
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
    const val CURRENT_VERSION = 3

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
        if (version < 3) {
            migrateToV3(prefs, editor, existingInstallation)
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
     * v3: the default wake phrase of new installations becomes "Mango Tango". Existing installations keep "Ciao Linda",
     * the phrase they were set up with (it can be changed in Settings); enrolled samples are not affected.
     */
    private fun migrateToV3(prefs: SharedPreferences, editor: SharedPreferences.Editor, existingInstallation: Boolean) {
        if (BuiltInWakePhrase.fromId(readString(prefs, PreferenceKeys.BUILT_IN_WAKE_PHRASE)) == null) {
            val phrase = if (existingInstallation) BuiltInWakePhrase.CIAO_LINDA else BuiltInWakePhrase.DEFAULT
            editor.putString(PreferenceKeys.BUILT_IN_WAKE_PHRASE, phrase.id)
        }
    }

    /** Built-in wake phrase stored in [prefs]; a missing or unknown value means the default. */
    fun builtInWakePhrase(prefs: SharedPreferences): BuiltInWakePhrase =
        BuiltInWakePhrase.fromId(readString(prefs, PreferenceKeys.BUILT_IN_WAKE_PHRASE)) ?: BuiltInWakePhrase.DEFAULT

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
