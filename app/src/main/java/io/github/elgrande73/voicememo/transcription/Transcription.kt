package io.github.elgrande73.voicememo.transcription

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import io.github.elgrande73.voicememo.memo.AppDatabase
import io.github.elgrande73.voicememo.monitoring.AudioMonitoringService
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** Process-wide access to on-device transcription (one model in memory at most). */
object Transcription {
    @Volatile
    private var manager: TranscriptionManager? = null
    @Volatile
    private var store: SpeechModelStore? = null
    @Volatile
    private var engine: WhisperTranscriber? = null

    /**
     * One worker thread, slightly below normal priority (whisper.cpp's threads inherit it), so audio capture and the
     * UI keep precedence. Not THREAD_PRIORITY_BACKGROUND: on Android that also moves the thread into the background
     * CPU group (typically only the slow cores, with a small CPU share), which made transcription take many minutes.
     */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(WORKER_PRIORITY)
            runnable.run()
        }, "transcription").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /** Nice value +4: below the UI and audio threads, but above Android's background group (+10). */
    const val WORKER_PRIORITY = 4

    fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)

    fun modelStore(context: Context): SpeechModelStore =
        store ?: synchronized(this) {
            store ?: SpeechModelStore(context.applicationContext, preferences(context)).also { store = it }
        }

    fun engine(context: Context): WhisperTranscriber =
        engine ?: synchronized(this) {
            engine ?: WhisperTranscriber(context.applicationContext, modelStore(context)).also { engine = it }
        }

    fun manager(context: Context): TranscriptionManager =
        manager ?: synchronized(this) {
            manager ?: TranscriptionManager(
                dao = AppDatabase.getDatabase(context.applicationContext).voiceNoteDao(),
                transcriberFactory = { engine(context) },
                settings = { settings(context) },
                scope = CoroutineScope(SupervisorJob()),
                worker = worker,
                onBusyChanged = { busy -> if (busy) TranscriptionService.start(context.applicationContext) }
            ).also {
                manager = it
                // Memos queued when the app was last stopped continue
                it.resumePending()
            }
        }

    fun settings(context: Context): TranscriptionSettings {
        val prefs = preferences(context)
        val quality = prefs.getString(PreferenceKeys.TRANSCRIPTION_QUALITY, null)
            ?.let { name -> TranscriptionQuality.entries.firstOrNull { it.name == name } } ?: TranscriptionQuality.DEFAULT
        return TranscriptionSettings(
            languageMode = languageMode(prefs),
            fixedLanguage = fixedLanguage(prefs),
            quality = quality,
            threads = threadCount(monitoring = AudioMonitoringService.isServiceRunning.value)
        )
    }

    /** Saved language mode; fixed (the default) if absent or unreadable. */
    fun languageMode(prefs: SharedPreferences): LanguageMode = try {
        prefs.getString(PreferenceKeys.TRANSCRIPTION_LANGUAGE_MODE, null)
    } catch (_: ClassCastException) {
        null
    }?.let { name -> LanguageMode.entries.firstOrNull { it.name == name } } ?: LanguageMode.FIXED

    /**
     * Saved fixed language; English if absent or not a whisper.cpp language code at all (a corrupt value, never a
     * user's choice). A valid code the current model lacks is kept and reported (see [WhisperLanguages.resolve]).
     */
    fun fixedLanguage(prefs: SharedPreferences): String = try {
        prefs.getString(PreferenceKeys.TRANSCRIPTION_FIXED_LANGUAGE, null)
    } catch (_: ClassCastException) {
        null
    }?.takeIf { WhisperLanguages.find(it) != null } ?: WhisperLanguages.ENGLISH

    /**
     * Decoding threads (at most 4): one core stays free for the app, and while monitoring runs a second one for audio
     * capture and wake-phrase detection (about 0.1 ms of CPU per 50 ms frame on the build host). Measured on the host:
     * 4 threads decode about 1.8× faster than 2 (docs/TRANSCRIPTION.md).
     */
    fun threadCount(monitoring: Boolean, cores: Int = Runtime.getRuntime().availableProcessors()): Int =
        (cores - if (monitoring) 2 else 1).coerceIn(1, 4)
}
