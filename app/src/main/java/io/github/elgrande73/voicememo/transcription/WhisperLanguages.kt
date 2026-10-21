package io.github.elgrande73.voicememo.transcription

import android.util.Log

/** How the transcription language is chosen (persisted by name: never rename). */
enum class LanguageMode {
    /** Always the selected language: no language detection (faster; the default). */
    FIXED,
    /** Detect the language of each recording (whisper.cpp language identification before transcribing). */
    AUTO
}

/** A language whisper.cpp can transcribe: its stable code (stored) and readable English name. */
data class TranscriptionLanguage(val code: String, val name: String) {
    /** For example "English (en)". */
    val label: String get() = "$name ($code)"
}

/** What the speech engine will actually use for a model and the language settings ([WhisperLanguages.resolve]). */
sealed interface EffectiveLanguage {
    /** The language code passed to whisper.cpp ("auto" = detect). */
    val engineCode: String

    data class Fixed(val language: TranscriptionLanguage) : EffectiveLanguage {
        override val engineCode get() = language.code
    }

    data object Automatic : EffectiveLanguage {
        override val engineCode get() = TranscriptionSettings.AUTO_LANGUAGE
    }

    /** English-only model (".en"): it always transcribes English, whatever is selected; no detection. */
    data object EnglishOnlyModel : EffectiveLanguage {
        override val engineCode get() = WhisperLanguages.ENGLISH
    }

    /** The selected language is not in the model's vocabulary: transcription fails with a message (never substituted). */
    data class Unsupported(val code: String) : EffectiveLanguage {
        override val engineCode get() = code
    }
}

/**
 * Languages of whisper.cpp and which of them a model supports.
 *
 * The list is whisper.cpp's own language table (`whisper_lang_max_id`, `whisper_lang_str`, `whisper_lang_str_full`),
 * read from the native library once it is loaded ([useNativeTable]); until then (or if the library is unavailable) a
 * copy of the table of the pinned whisper.cpp v1.9.5 is used, which tests check against the submodule source and the
 * native library. The language with id `i` is encoded by the token `sot + 1 + i`, so a model supports the first
 * `n_vocab − 51765 − 1` languages (whisper.cpp `num_languages()`): 99 for tiny…large-v2 (Cantonese, id 99, is
 * large-v3 only). English-only models (`n_vocab` < 51865) have no language tokens and always transcribe English.
 */
object WhisperLanguages {
    const val ENGLISH = "en"

    /** whisper.cpp v1.9.5 `g_lang`, in id order (index = id). */
    private val BUILT_IN: List<Pair<String, String>> = listOf(
        "en" to "english", "zh" to "chinese", "de" to "german", "es" to "spanish", "ru" to "russian",
        "ko" to "korean", "fr" to "french", "ja" to "japanese", "pt" to "portuguese", "tr" to "turkish",
        "pl" to "polish", "ca" to "catalan", "nl" to "dutch", "ar" to "arabic", "sv" to "swedish",
        "it" to "italian", "id" to "indonesian", "hi" to "hindi", "fi" to "finnish", "vi" to "vietnamese",
        "he" to "hebrew", "uk" to "ukrainian", "el" to "greek", "ms" to "malay", "cs" to "czech",
        "ro" to "romanian", "da" to "danish", "hu" to "hungarian", "ta" to "tamil", "no" to "norwegian",
        "th" to "thai", "ur" to "urdu", "hr" to "croatian", "bg" to "bulgarian", "lt" to "lithuanian",
        "la" to "latin", "mi" to "maori", "ml" to "malayalam", "cy" to "welsh", "sk" to "slovak",
        "te" to "telugu", "fa" to "persian", "lv" to "latvian", "bn" to "bengali", "sr" to "serbian",
        "az" to "azerbaijani", "sl" to "slovenian", "kn" to "kannada", "et" to "estonian", "mk" to "macedonian",
        "br" to "breton", "eu" to "basque", "is" to "icelandic", "hy" to "armenian", "ne" to "nepali",
        "mn" to "mongolian", "bs" to "bosnian", "kk" to "kazakh", "sq" to "albanian", "sw" to "swahili",
        "gl" to "galician", "mr" to "marathi", "pa" to "punjabi", "si" to "sinhala", "km" to "khmer",
        "sn" to "shona", "yo" to "yoruba", "so" to "somali", "af" to "afrikaans", "oc" to "occitan",
        "ka" to "georgian", "be" to "belarusian", "tg" to "tajik", "sd" to "sindhi", "gu" to "gujarati",
        "am" to "amharic", "yi" to "yiddish", "lo" to "lao", "uz" to "uzbek", "fo" to "faroese",
        "ht" to "haitian creole", "ps" to "pashto", "tk" to "turkmen", "nn" to "nynorsk", "mt" to "maltese",
        "sa" to "sanskrit", "lb" to "luxembourgish", "my" to "myanmar", "bo" to "tibetan", "tl" to "tagalog",
        "mg" to "malagasy", "as" to "assamese", "tt" to "tatar", "haw" to "hawaiian", "ln" to "lingala",
        "ha" to "hausa", "ba" to "bashkir", "jw" to "javanese", "su" to "sundanese", "yue" to "cantonese"
    )

    @Volatile
    private var table: List<Pair<String, String>> = BUILT_IN

    /** Replaces the built-in copy with the list read from the native library (code, English name per id). */
    fun useNativeTable(entries: List<Pair<String, String>>) {
        if (entries.isEmpty()) return
        if (entries != BUILT_IN) Log.i("WhisperLanguages", "Native language table differs from the built-in copy (${entries.size} languages)")
        table = entries
    }

    /** Every language of the whisper.cpp language table, in id order. */
    fun all(): List<TranscriptionLanguage> = table.map { (code, name) -> TranscriptionLanguage(code, readable(name)) }

    fun find(code: String): TranscriptionLanguage? = all().firstOrNull { it.code == code }

    /** Number of languages (ids 0 until this) the model can transcribe; base multilingual when the model is unknown. */
    fun languageCount(info: GgmlModelInfo?): Int = when {
        info == null -> MULTILINGUAL_LANGUAGES
        !info.multilingual -> 1
        else -> (info.nVocab - 51765 - 1).coerceIn(1, table.size)
    }

    /** Languages the model can transcribe, sorted by name (English-only models: English). */
    fun supported(info: GgmlModelInfo?): List<TranscriptionLanguage> = all().take(languageCount(info)).sortedBy { it.name }

    fun isSupported(code: String, info: GgmlModelInfo?): Boolean = all().take(languageCount(info)).any { it.code == code }

    /** The language the engine uses for [mode] / [fixedCode] with a model described by [info] (null: not installed). */
    fun resolve(mode: LanguageMode, fixedCode: String, info: GgmlModelInfo?): EffectiveLanguage = when {
        info != null && !info.multilingual -> EffectiveLanguage.EnglishOnlyModel
        mode == LanguageMode.AUTO -> EffectiveLanguage.Automatic
        !isSupported(fixedCode, info) -> EffectiveLanguage.Unsupported(fixedCode)
        else -> EffectiveLanguage.Fixed(find(fixedCode)!!)
    }

    /** "armenian" → "Armenian", "haitian creole" → "Haitian Creole". */
    private fun readable(name: String) = name.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

    /** Languages of the multilingual tiny … large-v2 models (Whisper base included). */
    const val MULTILINGUAL_LANGUAGES = 99
}

/** The read-only "Effective configuration" shown in Settings: what the engine will use for the next memo. */
data class EffectiveTranscriptionConfig(val model: String, val language: String, val problem: String? = null) {
    companion object {
        /**
         * [model]: the model transcription will use (null: none installed yet; Whisper base multilingual is the one
         * offered for download). The language comes from the same [WhisperLanguages.resolve] the engine uses.
         */
        fun of(model: SpeechModel?, settings: TranscriptionSettings): EffectiveTranscriptionConfig {
            val info = model?.info
            val modelName = when {
                model == null -> "Base Multilingual (not downloaded yet)"
                info != null && info.sizeName != "custom" ->
                    info.sizeName.replaceFirstChar(Char::uppercaseChar) + if (info.multilingual) " Multilingual" else " English"
                else -> model.displayName
            }
            return when (val language = settings.effectiveLanguage(info)) {
                is EffectiveLanguage.Fixed -> EffectiveTranscriptionConfig(modelName, language.language.label)
                EffectiveLanguage.Automatic -> EffectiveTranscriptionConfig(modelName, "Automatic detection")
                EffectiveLanguage.EnglishOnlyModel -> EffectiveTranscriptionConfig(
                    modelName, "English (en)", "This model only transcribes English; the language setting does not apply."
                )
                is EffectiveLanguage.Unsupported -> EffectiveTranscriptionConfig(
                    modelName, "${WhisperLanguages.find(language.code)?.label ?: language.code} — not supported",
                    "This model cannot transcribe the selected language. Choose another language; transcription fails until then."
                )
            }
        }
    }
}
