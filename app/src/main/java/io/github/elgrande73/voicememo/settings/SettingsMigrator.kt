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

    /** Pre-standardization key holding the detector "keyword sensitivity" (0.10..0.95, higher = easier). */
    const val LEGACY_KEYWORD_SENSITIVITY = "keywordSensitivity"
}

/**
 * Versioned, idempotent migrations for [PreferenceKeys.PREFS_NAME]. Safe to run on every start:
 * each step runs once (guarded by [PreferenceKeys.SETTINGS_SCHEMA_VERSION]), values the user already
 * saved are never replaced by defaults, and unrelated keys are never touched.
 */
object SettingsMigrator {
    const val CURRENT_VERSION = 1

    private const val TAG = "SettingsMigrator"

    fun preferences(context: Context): SharedPreferences {
        val prefs = context.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        migrate(prefs)
        return prefs
    }

    fun migrate(prefs: SharedPreferences) {
        val version = readInt(prefs, PreferenceKeys.SETTINGS_SCHEMA_VERSION) ?: 0
        val editor = prefs.edit()

        if (version < 1) {
            migrateToV1(prefs, editor)
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
                MatchConfidenceThreshold.clampPercent(Math.round(legacySensitivityToConfidence(legacySensitivity) * 100f))
            } else {
                MatchConfidenceThreshold.DEFAULT_PERCENT
            }
            editor.putInt(PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT, percent)
        }
        // The legacy key is only removed in the same commit that stores its migrated value
        editor.remove(PreferenceKeys.LEGACY_KEYWORD_SENSITIVITY)
    }

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
