package io.github.elgrande73.voicememo

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.settings.MatchConfidenceThreshold
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import io.github.elgrande73.voicememo.settings.SettingsMigrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsMigratorTest {

    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun threshold() = prefs.getInt(PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT, -1)

    @Test
    fun freshInstallationGetsValidDefaultThreshold() {
        SettingsMigrator.migrate(prefs)
        assertEquals(MatchConfidenceThreshold.DEFAULT_PERCENT, threshold())
        assertEquals(SettingsMigrator.CURRENT_VERSION, prefs.getInt(PreferenceKeys.SETTINGS_SCHEMA_VERSION, 0))
    }

    @Test
    fun savedThresholdSurvivesUpgradeAndRepeatedMigration() {
        prefs.edit()
            .putInt(PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT, 72)
            .putInt(PreferenceKeys.SETTINGS_SCHEMA_VERSION, SettingsMigrator.CURRENT_VERSION)
            .putFloat("thresholdDb", 57f)
            .putString("detectionMode", "AUTO_SOUND")
            .commit()

        repeat(3) { SettingsMigrator.migrate(prefs) }

        assertEquals(72, threshold())
        assertEquals(57f, prefs.getFloat("thresholdDb", 0f), 0.001f)
        assertEquals("AUTO_SOUND", prefs.getString("detectionMode", null))
    }

    @Test
    fun existingThresholdWithoutVersionIsNotOverwrittenByDefault() {
        prefs.edit().putInt(PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT, 65).commit()
        SettingsMigrator.migrate(prefs)
        assertEquals(65, threshold())
    }

    @Test
    fun legacySensitivityIsMigratedClampedAndRemoved() {
        prefs.edit().putFloat(PreferenceKeys.LEGACY_KEYWORD_SENSITIVITY, 0.60f).putBoolean("dynamicColor", false).commit()

        SettingsMigrator.migrate(prefs)

        // 0.60 used to mean a 40% confidence threshold; step v1 clamped it into the 50–80% range of its time and keeps
        // doing so, so a migration gives the same result whenever it runs
        assertEquals(50, threshold())
        assertFalse(prefs.contains(PreferenceKeys.LEGACY_KEYWORD_SENSITIVITY))
        assertFalse(prefs.getBoolean("dynamicColor", true))
    }

    @Test
    fun legacyValueDoesNotOverrideAlreadyMigratedThreshold() {
        prefs.edit()
            .putInt(PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT, 70)
            .putFloat(PreferenceKeys.LEGACY_KEYWORD_SENSITIVITY, 0.9f)
            .commit()
        SettingsMigrator.migrate(prefs)
        assertEquals(70, threshold())
    }

    @Test
    fun outOfRangeThresholdIsClamped() {
        prefs.edit()
            .putInt(PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT, 95)
            .putInt(PreferenceKeys.SETTINGS_SCHEMA_VERSION, SettingsMigrator.CURRENT_VERSION)
            .commit()
        SettingsMigrator.migrate(prefs)
        assertEquals(80, threshold())

        prefs.edit().putInt(PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT, 5).commit()
        SettingsMigrator.migrate(prefs)
        assertEquals(20, threshold())
    }

    @Test
    fun thresholdsInTheExtendedRangeAreKept() {
        prefs.edit()
            .putInt(PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT, 20)
            .putInt(PreferenceKeys.SETTINGS_SCHEMA_VERSION, SettingsMigrator.CURRENT_VERSION)
            .commit()
        SettingsMigrator.migrate(prefs)
        assertEquals(20, threshold())
        prefs.edit().putInt(PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT, 35).commit()
        SettingsMigrator.migrate(prefs)
        assertEquals(35, threshold())
    }

    @Test
    fun corruptThresholdTypeIsReplacedWithDefaultWithoutTouchingOtherSettings() {
        prefs.edit()
            .putString(PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT, "not a number")
            .putInt(PreferenceKeys.SETTINGS_SCHEMA_VERSION, SettingsMigrator.CURRENT_VERSION)
            .putLong("silenceTimeoutMs", 3000L)
            .commit()

        SettingsMigrator.migrate(prefs)

        assertEquals(MatchConfidenceThreshold.DEFAULT_PERCENT, threshold())
        assertEquals(3000L, prefs.getLong("silenceTimeoutMs", 0L))
    }

    @Test
    fun corruptLegacyValueFallsBackToDefault() {
        prefs.edit().putString(PreferenceKeys.LEGACY_KEYWORD_SENSITIVITY, "garbage").commit()
        SettingsMigrator.migrate(prefs)
        assertEquals(MatchConfidenceThreshold.DEFAULT_PERCENT, threshold())
    }

    // --- v2: optional minimum audio level of the wake phrase ---

    private fun minLevelEnabled() = prefs.getBoolean(PreferenceKeys.WAKE_PHRASE_MIN_LEVEL_ENABLED, false)
    private fun minLevelDbfs() = prefs.getFloat(PreferenceKeys.WAKE_PHRASE_MIN_LEVEL_DBFS, Float.NaN)

    @Test
    fun freshInstallationGetsTheMeasuredLevelDefault() {
        SettingsMigrator.migrate(prefs)
        assertEquals(io.github.elgrande73.voicememo.settings.WakePhraseLevel.DEFAULT_ENABLED, minLevelEnabled())
        assertEquals(io.github.elgrande73.voicememo.settings.WakePhraseLevel.DEFAULT_DBFS, minLevelDbfs(), 0.001f)
    }

    @Test
    fun existingInstallationKeepsTheLoudnessConditionItHad() {
        // v1 with the default activation threshold (48 = −42 dBFS), never changed by the user
        prefs.edit().putInt(PreferenceKeys.SETTINGS_SCHEMA_VERSION, 1).putInt(PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT, 60).commit()
        SettingsMigrator.migrate(prefs)
        assertTrue(minLevelEnabled())
        assertEquals(-42f, minLevelDbfs(), 0.001f)
        assertEquals(60, threshold())
        assertEquals(SettingsMigrator.CURRENT_VERSION, prefs.getInt(PreferenceKeys.SETTINGS_SCHEMA_VERSION, 0))
    }

    @Test
    fun customisedActivationThresholdIsCarriedOverAndLeftUntouched() {
        prefs.edit().putInt(PreferenceKeys.SETTINGS_SCHEMA_VERSION, 1).putFloat(PreferenceKeys.ACTIVATION_THRESHOLD_DB, 57f).commit()
        SettingsMigrator.migrate(prefs)
        assertTrue(minLevelEnabled())
        assertEquals(-33f, minLevelDbfs(), 0.001f)
        assertEquals(57f, prefs.getFloat(PreferenceKeys.ACTIVATION_THRESHOLD_DB, 0f), 0.001f)
    }

    @Test
    fun levelSettingsTheUserChoseAreNeverReplaced() {
        prefs.edit().putInt(PreferenceKeys.SETTINGS_SCHEMA_VERSION, 1)
            .putBoolean(PreferenceKeys.WAKE_PHRASE_MIN_LEVEL_ENABLED, false)
            .putFloat(PreferenceKeys.WAKE_PHRASE_MIN_LEVEL_DBFS, -37f).commit()
        repeat(3) { SettingsMigrator.migrate(prefs) }
        assertFalse(minLevelEnabled())
        assertEquals(-37f, minLevelDbfs(), 0.001f)
    }
}
