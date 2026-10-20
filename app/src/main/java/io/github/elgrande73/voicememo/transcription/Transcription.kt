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
     * One background thread at low priority: whisper.cpp's worker threads inherit it, so audio capture and the UI
     * keep precedence while a memo is transcribed.
     */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "transcription").apply { isDaemon = true }
    }.asCoroutineDispatcher()

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
                worker = worker
            ).also { manager = it }
        }

    fun settings(context: Context): TranscriptionSettings {
        val prefs = preferences(context)
        val quality = prefs.getString(PreferenceKeys.TRANSCRIPTION_QUALITY, null)
            ?.let { name -> TranscriptionQuality.entries.firstOrNull { it.name == name } } ?: TranscriptionQuality.ACCURATE
        return TranscriptionSettings(
            language = prefs.getString(PreferenceKeys.TRANSCRIPTION_LANGUAGE, null) ?: TranscriptionSettings.AUTO_LANGUAGE,
            quality = quality,
            threads = threadCount(monitoring = AudioMonitoringService.isServiceRunning.value)
        )
    }

    /** Fewer threads while monitoring runs, so wake-phrase detection keeps enough CPU. */
    fun threadCount(monitoring: Boolean, cores: Int = Runtime.getRuntime().availableProcessors()): Int =
        if (monitoring) 2.coerceAtMost(cores) else (cores - 1).coerceIn(1, 4)
}
