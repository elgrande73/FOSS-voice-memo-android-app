package io.github.elgrande73.voicememo.transcription

import android.content.Context

/** Persisted values of VoiceNote.transcriptionStatus. Stored in the database: never rename. */
object TranscriptionStatus {
    /** Waiting for transcription (automatic transcription was on when the memo was saved). */
    const val PENDING = "PENDING"
    const val COMPLETED = "COMPLETED"
    /** Not transcribed (automatic transcription off, or transcript deleted). */
    const val SKIPPED = "SKIPPED"
    const val FAILED = "FAILED"
}

enum class TranscriptionQuality(val title: String, val beamSize: Int) {
    ACCURATE("Accurate (slower, about 2× the time)", 5),
    FAST("Fast (recommended on phones)", 1);

    companion object {
        /**
         * Greedy decoding: measured 1.3 s vs 3.1 s (beam search) for a 15.7 s memo on the build machine; phones are
         * several times slower, so beam search is opt-in.
         */
        val DEFAULT = FAST
    }
}

data class TranscriptionSettings(
    val languageMode: LanguageMode = LanguageMode.FIXED,
    /** Language code used in [LanguageMode.FIXED] (kept while automatic detection is chosen). */
    val fixedLanguage: String = WhisperLanguages.ENGLISH,
    val quality: TranscriptionQuality = TranscriptionQuality.DEFAULT,
    val threads: Int = 4
) {
    /** The language passed to whisper.cpp for a model described by [info] (see [WhisperLanguages.resolve]). */
    fun effectiveLanguage(info: GgmlModelInfo?): EffectiveLanguage = WhisperLanguages.resolve(languageMode, fixedLanguage, info)

    companion object {
        const val AUTO_LANGUAGE = "auto"
    }
}

data class TranscriptResult(val text: String, val language: String, val modelId: String, val processingMs: Long)

class TranscriptionException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NOT_AVAILABLE, MODEL, MODEL_MISSING, AUDIO, CANCELLED, FAILED }
}

/** Engine-independent pre- and post-processing of transcripts. */
object TranscriptText {
    /** Loudest 50 ms frame below this (dBFS) means there is nothing to transcribe. */
    const val SPEECH_PRESENCE_DBFS = -55.0

    /** Whether any 50 ms frame of [samples] (16 kHz, -1..1) reaches [SPEECH_PRESENCE_DBFS]. */
    fun hasAudibleContent(samples: FloatArray): Boolean {
        val frame = 800
        var i = 0
        val threshold = Math.pow(10.0, SPEECH_PRESENCE_DBFS / 20.0)
        while (i < samples.size) {
            val end = minOf(i + frame, samples.size)
            var sum = 0.0
            for (k in i until end) sum += samples[k].toDouble() * samples[k]
            if (Math.sqrt(sum / (end - i)) >= threshold) return true
            i = end
        }
        return false
    }

    /** Removes non-speech annotations Whisper sometimes produces ("[Music]", "(applause)") and tidies spaces. */
    fun clean(text: String): String =
        text.replace(Regex("""\[[^\]]*\]|\([^)]*\)|♪+"""), " ").replace(Regex("""\s+"""), " ").trim()
}

/** Cancellation shared between the queue and the engine. */
class CancelToken {
    @Volatile
    var isCancelled = false
        private set
    private var onCancel: (() -> Unit)? = null

    @Synchronized
    fun cancel() {
        isCancelled = true
        onCancel?.invoke()
    }

    @Synchronized
    fun setOnCancel(action: (() -> Unit)?) {
        onCancel = action
        if (isCancelled) action?.invoke()
    }
}

/** A speech-to-text engine. Blocking; called from a background thread only. */
interface SpeechTranscriber {
    fun transcribe(samples: FloatArray, settings: TranscriptionSettings, cancel: CancelToken): TranscriptResult
    /** Frees the model memory; the next call loads it again. */
    fun release()
    /** Progress of the running transcription in percent; may be called from another thread. */
    fun progress(): Int = 0
    /** Whether the model is loaded, so the next run starts decoding without loading it. */
    fun isModelLoaded(): Boolean = true
    /** Loads the model if it is not loaded yet; fails like [transcribe]. Called before [transcribe]. */
    fun prepare() {}
}

/** whisper.cpp with the model selected in [SpeechModelStore]; the model stays loaded until [release]. */
class WhisperTranscriber(
    private val context: Context,
    private val store: SpeechModelStore,
    /** The model to use; the selected one by default (tests on the build machine pass a model file). */
    private val modelProvider: () -> SpeechModel = { store.activeModel() }
) : SpeechTranscriber {
    @Volatile
    private var handle = 0L
    private var loadedModelId: String? = null

    override fun progress(): Int = handle.let { if (it == 0L) 0 else WhisperNative.progress(it) }

    override fun isModelLoaded(): Boolean = handle != 0L

    @Synchronized
    override fun prepare() {
        loadModel()
    }

    /** Loads the active model unless it is already loaded; returns it. */
    private fun loadModel(): SpeechModel {
        if (!WhisperNative.load(context)) {
            throw TranscriptionException(TranscriptionException.Kind.NOT_AVAILABLE, "Speech-to-text is not available on this device.")
        }
        val model = try {
            modelProvider()
        } catch (e: SpeechModelMissingException) {
            throw TranscriptionException(TranscriptionException.Kind.MODEL_MISSING, SpeechModelMissingException.MISSING_MESSAGE)
        } catch (e: Exception) {
            throw TranscriptionException(TranscriptionException.Kind.MODEL, e.message ?: "The speech model could not be prepared.")
        }
        if (handle == 0L || loadedModelId != model.id) {
            release()
            handle = WhisperNative.init(model.file.absolutePath)
            if (handle == 0L) {
                throw TranscriptionException(TranscriptionException.Kind.MODEL, "The speech model could not be loaded (not enough memory, or a damaged file).")
            }
            loadedModelId = model.id
        }
        return model
    }

    @Synchronized
    override fun transcribe(samples: FloatArray, settings: TranscriptionSettings, cancel: CancelToken): TranscriptResult {
        val model = loadModel()
        val language = settings.effectiveLanguage(model.info)
        if (language is EffectiveLanguage.Unsupported) {
            // Never substitute another language: say what is wrong and how to fix it
            throw TranscriptionException(
                TranscriptionException.Kind.FAILED,
                "The speech model does not support the language \"${language.code}\". Choose another language in Settings."
            )
        }
        val current = handle
        val start = System.nanoTime()
        cancel.setOnCancel { WhisperNative.requestAbort(current) }
        try {
            val result = WhisperNative.transcribe(current, samples, language.engineCode, settings.threads, settings.quality.beamSize)
            if (cancel.isCancelled) throw TranscriptionException(TranscriptionException.Kind.CANCELLED, "Cancelled.")
            result ?: throw TranscriptionException(TranscriptionException.Kind.FAILED, "Transcription failed.")
            return TranscriptResult(result[1].trim(), result[0], model.id, (System.nanoTime() - start) / 1_000_000)
        } finally {
            cancel.setOnCancel(null)
        }
    }

    @Synchronized
    override fun release() {
        if (handle != 0L) WhisperNative.free(handle)
        handle = 0L
        loadedModelId = null
    }

    /** Test load used when importing a model (separate context, freed immediately). */
    fun canLoad(file: java.io.File): Boolean {
        if (!WhisperNative.load(context)) return false
        val h = WhisperNative.init(file.absolutePath)
        if (h == 0L) return false
        WhisperNative.free(h)
        return true
    }
}
