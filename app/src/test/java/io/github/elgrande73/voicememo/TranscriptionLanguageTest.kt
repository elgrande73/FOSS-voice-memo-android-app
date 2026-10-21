package io.github.elgrande73.voicememo

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import io.github.elgrande73.voicememo.settings.SettingsMigrator
import io.github.elgrande73.voicememo.transcription.EffectiveLanguage
import io.github.elgrande73.voicememo.transcription.EffectiveTranscriptionConfig
import io.github.elgrande73.voicememo.transcription.GgmlModelInfo
import io.github.elgrande73.voicememo.transcription.LanguageMode
import io.github.elgrande73.voicememo.transcription.SpeechModel
import io.github.elgrande73.voicememo.transcription.Transcription
import io.github.elgrande73.voicememo.transcription.TranscriptionSettings
import io.github.elgrande73.voicememo.transcription.WhisperLanguages
import io.github.elgrande73.voicememo.ui.theme.MyApplicationTheme
import io.github.elgrande73.voicememo.viewmodel.VoiceMemoViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Transcription language: defaults, persistence, mode switching, supported languages and the effective configuration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptionLanguageTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application
    private val prefs get() = app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)

    // Header values of the real model files (whisper.cpp hparams)
    private val baseMultilingual = GgmlModelInfo(51865, 512, 6, 512, 6, 80)
    private val baseEnglish = GgmlModelInfo(51864, 512, 6, 512, 6, 80)
    private val largeV3 = GgmlModelInfo(51866, 1280, 32, 1280, 32, 128)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        prefs.edit().clear().commit()
        SettingsMigrator.migrate(prefs)
    }

    @Test
    fun newInstallationsUseFixedEnglishWithoutDetection() {
        val settings = Transcription.settings(app)
        assertEquals(LanguageMode.FIXED, settings.languageMode)
        assertEquals("en", settings.fixedLanguage)
        val effective = settings.effectiveLanguage(baseMultilingual)
        assertTrue(effective is EffectiveLanguage.Fixed)
        assertEquals("en", effective.engineCode) // passed to whisper_full: no language identification
    }

    @Test
    fun theRequiredLanguagesAreSupportedByBaseMultilingual() {
        val required = mapOf("en" to "English", "de" to "German", "es" to "Spanish", "fr" to "French", "fi" to "Finnish",
            "et" to "Estonian", "hy" to "Armenian", "ru" to "Russian")
        val supported = WhisperLanguages.supported(baseMultilingual).associate { it.code to it.name }
        required.forEach { (code, name) -> assertEquals(code, name, supported[code]) }
        assertEquals("all languages of base, not only eight", 99, supported.size)
        assertEquals("Armenian (hy)", WhisperLanguages.find("hy")!!.label)
    }

    @Test
    fun theLanguageListFollowsTheModelVocabulary() {
        assertFalse("Cantonese exists only in large-v3", WhisperLanguages.isSupported("yue", baseMultilingual))
        assertTrue(WhisperLanguages.isSupported("yue", largeV3))
        assertEquals(listOf("en"), WhisperLanguages.supported(baseEnglish).map { it.code })
        // English-only model: always English, no detection, whatever the settings say
        assertEquals(EffectiveLanguage.EnglishOnlyModel, WhisperLanguages.resolve(LanguageMode.AUTO, "de", baseEnglish))
        assertEquals("en", WhisperLanguages.resolve(LanguageMode.FIXED, "de", baseEnglish).engineCode)
    }

    @Test
    fun unsupportedOrInvalidCodesAreNeverSubstituted() {
        assertEquals(EffectiveLanguage.Unsupported("yue"), WhisperLanguages.resolve(LanguageMode.FIXED, "yue", baseMultilingual))
        assertNull(WhisperLanguages.find("xx"))
        // A corrupt stored value (not a whisper.cpp code at all) reads as English; a valid code stays as stored
        prefs.edit().putString(PreferenceKeys.TRANSCRIPTION_FIXED_LANGUAGE, "xx").putString(PreferenceKeys.TRANSCRIPTION_LANGUAGE_MODE, "SOMETHING").commit()
        assertEquals("en", Transcription.fixedLanguage(prefs))
        assertEquals(LanguageMode.FIXED, Transcription.languageMode(prefs))
        prefs.edit().putInt(PreferenceKeys.TRANSCRIPTION_FIXED_LANGUAGE, 3).commit()
        assertEquals("en", Transcription.fixedLanguage(prefs))
        val vm = VoiceMemoViewModel(app)
        vm.setTranscriptionFixedLanguage("xx")
        assertEquals("en", vm.transcriptionFixedLanguage.value)
    }

    @Test
    fun modeAndLanguageSurviveARestartAndSwitchingBackRestoresTheLanguage() {
        val vm = VoiceMemoViewModel(app)
        vm.setTranscriptionFixedLanguage("de")
        vm.setTranscriptionLanguageMode(LanguageMode.AUTO)
        val restarted = VoiceMemoViewModel(app)
        assertEquals(LanguageMode.AUTO, restarted.transcriptionLanguageMode.value)
        assertEquals("de", restarted.transcriptionFixedLanguage.value)
        assertEquals("auto", Transcription.settings(app).effectiveLanguage(baseMultilingual).engineCode)
        restarted.setTranscriptionLanguageMode(LanguageMode.FIXED)
        assertEquals("de", Transcription.settings(app).effectiveLanguage(baseMultilingual).engineCode)
    }

    @Test
    fun effectiveConfigurationDescribesWhatTheEngineUses() {
        val model = SpeechModel("downloaded-whisper-base", "Whisper base", File("x"), 1, "", true, baseMultilingual)
        assertEquals(EffectiveTranscriptionConfig("Base Multilingual", "English (en)"), EffectiveTranscriptionConfig.of(model, TranscriptionSettings()))
        assertEquals("German (de)", EffectiveTranscriptionConfig.of(model, TranscriptionSettings(fixedLanguage = "de")).language)
        assertEquals("Automatic detection", EffectiveTranscriptionConfig.of(model, TranscriptionSettings(languageMode = LanguageMode.AUTO, fixedLanguage = "de")).language)
        assertEquals("Base Multilingual (not downloaded yet)", EffectiveTranscriptionConfig.of(null, TranscriptionSettings()).model)
        val english = model.copy(info = baseEnglish)
        assertEquals("Base English", EffectiveTranscriptionConfig.of(english, TranscriptionSettings(fixedLanguage = "de")).model)
        assertEquals("English (en)", EffectiveTranscriptionConfig.of(english, TranscriptionSettings(fixedLanguage = "de")).language)
        assertTrue(EffectiveTranscriptionConfig.of(model, TranscriptionSettings(fixedLanguage = "yue")).problem != null)
    }

    @Test
    fun settingsScreenSwitchesModesAndUpdatesTheSummaryImmediately() {
        val vm = VoiceMemoViewModel(app)
        composeTestRule.setContent { MyApplicationTheme { TranscriptionLanguageSettings(vm) } }
        composeTestRule.onNodeWithTag("transcription_language_mode_FIXED").assertIsSelected()
        composeTestRule.onNodeWithTag("transcription_fixed_language_help")
            .assertTextEquals("Always use the selected language instead of detecting it for each recording.")
        composeTestRule.onNodeWithTag("transcription_language").assertIsDisplayed()
        composeTestRule.onNodeWithTag("effective_language").assertTextEquals("Language: English (en)")

        composeTestRule.onNodeWithTag("transcription_language").performClick()
        composeTestRule.onNodeWithTag("transcription_language_hy").performClick()
        composeTestRule.onNodeWithTag("effective_language").assertTextEquals("Language: Armenian (hy)")

        composeTestRule.onNodeWithTag("transcription_language_mode_AUTO").performClick()
        composeTestRule.onNodeWithTag("transcription_language").assertDoesNotExist()
        composeTestRule.onNodeWithTag("effective_language").assertTextEquals("Language: Automatic detection")

        composeTestRule.onNodeWithTag("transcription_language_mode_FIXED").performClick()
        composeTestRule.onNodeWithTag("effective_language").assertTextEquals("Language: Armenian (hy)")
        assertEquals("hy", prefs.getString(PreferenceKeys.TRANSCRIPTION_FIXED_LANGUAGE, null))
        assertEquals("FIXED", prefs.getString(PreferenceKeys.TRANSCRIPTION_LANGUAGE_MODE, null))
    }

    @Test
    fun builtInLanguageTableEqualsThePinnedWhisperSource() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val source = File(root, "third_party/whisper.cpp/src/whisper.cpp")
        org.junit.Assume.assumeTrue("submodule not checked out", source.exists())
        val text = source.readText()
        val block = text.substring(text.indexOf("g_lang = {")).let { it.substring(0, it.indexOf("};")) }
        val entries = Regex("""\{\s*"([a-z]+)",\s*\{\s*(\d+),\s*"([a-z ]+)"""").findAll(block)
            .map { Triple(it.groupValues[1], it.groupValues[2].toInt(), it.groupValues[3]) }.sortedBy { it.second }.toList()
        assertEquals(entries.indices.toList(), entries.map { it.second })
        assertEquals(entries.map { it.first }, WhisperLanguages.all().map { it.code })
        assertEquals(entries.map { it.third }, WhisperLanguages.all().map { it.name.lowercase() })
    }
}
