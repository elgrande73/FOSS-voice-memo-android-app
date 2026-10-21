package io.github.elgrande73.voicememo.transcription

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.elgrande73.voicememo.MainActivity
import io.github.elgrande73.voicememo.R
import io.github.elgrande73.voicememo.settings.SettingsMigrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/** State of the speech model download (Settings → On-device transcription). */
sealed interface ModelDownloadState {
    /** No model installed and no download running. */
    data object Missing : ModelDownloadState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelDownloadState {
        val percent: Int get() = if (totalBytes <= 0) 0 else (downloadedBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
    }
    /** Checking the checksum and the file header. */
    data object Validating : ModelDownloadState
    /** Test-loading the model with the speech engine. */
    data object Initializing : ModelDownloadState
    /** The downloaded model is installed and usable. */
    data object Ready : ModelDownloadState
    data class Failed(val message: String, val retryable: Boolean) : ModelDownloadState
}

/**
 * Runs the user-initiated download of [ModelSource.WHISPER_BASE] (one at a time) and publishes its state. Nothing is
 * downloaded unless the user asks for it.
 */
object ModelDownloads {
    private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Missing)
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()

    private val running = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)

    /** True while a download is running (a second request is ignored). */
    val isRunning: Boolean get() = running.get()

    fun store(context: Context) = SpeechModelStore(context.applicationContext, SettingsMigrator.preferences(context.applicationContext))

    /** Recomputes the state from the files (on start, after removal); a running download is not affected. */
    fun refresh(context: Context) {
        if (running.get()) return
        val failed = _state.value as? ModelDownloadState.Failed
        _state.value = when {
            store(context).downloadedModel() != null -> ModelDownloadState.Ready
            failed != null -> failed
            else -> ModelDownloadState.Missing
        }
    }

    /** Starts the download in a foreground service (progress notification with Cancel). */
    fun start(context: Context) {
        if (running.get()) return
        ModelDownloadService.start(context)
    }

    fun cancel(context: Context) {
        if (running.get()) cancelRequested.set(true)
    }

    /**
     * Blocking download; called on a worker thread by [ModelDownloadService] (and by tests with an injected
     * [downloader]). Returns false if a download was already running.
     */
    fun run(context: Context, downloader: ModelDownloader = ModelDownloader(), verifyLoad: ((java.io.File) -> Boolean)? = null): Boolean {
        if (!running.compareAndSet(false, true)) return false
        cancelRequested.set(false)
        try {
            val app = context.applicationContext
            val store = store(app)
            _state.value = ModelDownloadState.Downloading(0, ModelSource.WHISPER_BASE.approxBytes)
            val result = downloader.download(
                ModelSource.WHISPER_BASE, store.downloadDirectory,
                isCancelled = { cancelRequested.get() },
                onProgress = { done, total -> _state.value = ModelDownloadState.Downloading(done, total) },
                onStage = { stage ->
                    _state.value = if (stage == ModelDownloader.Stage.VALIDATING) ModelDownloadState.Validating else ModelDownloadState.Initializing
                },
                verifyLoad = verifyLoad ?: { file -> WhisperTranscriber(app, store).canLoad(file) }
            )
            _state.value = when (result) {
                is ModelDownloader.Result.Installed -> {
                    store.registerDownload(result.file, result.sha256)
                    ModelDownloadState.Ready
                }
                is ModelDownloader.Result.Failed -> ModelDownloadState.Failed(result.message, result.retryable)
                ModelDownloader.Result.Cancelled ->
                    if (store.downloadedModel() != null) ModelDownloadState.Ready
                    else ModelDownloadState.Failed("Download cancelled. Tap Download to continue where it stopped.", true)
            }
        } catch (t: Throwable) {
            Log.e("ModelDownloads", "Model download failed", t)
            _state.value = ModelDownloadState.Failed("The download failed (${t.javaClass.simpleName}). Tap Retry.", true)
        } finally {
            running.set(false)
        }
        return true
    }
}

/**
 * Keeps the process alive while the model is downloaded (it can take several minutes) and shows its progress with a
 * Cancel action. Stops itself when the download has ended.
 */
class ModelDownloadService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            ModelDownloads.cancel(this)
            return START_NOT_STICKY
        }
        if (!startForegroundCompat(buildNotification(ModelDownloads.state.value))) return START_NOT_STICKY
        if (ModelDownloads.isRunning) return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        Thread({
            val watcher = Thread {
                try {
                    while (true) {
                        manager?.notify(NOTIFICATION_ID, buildNotification(ModelDownloads.state.value))
                        Thread.sleep(1000)
                    }
                } catch (_: InterruptedException) {
                }
            }.apply { isDaemon = true; start() }
            try {
                ModelDownloads.run(applicationContext)
            } finally {
                watcher.interrupt()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, "ModelDownload").start()
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        Log.w("ModelDownloadService", "Could not enter the foreground", e)
        // Fall back to a plain background thread (only while the app is visible)
        if (!ModelDownloads.isRunning) Thread({ ModelDownloads.run(applicationContext) }, "ModelDownload").start()
        stopSelf()
        false
    }

    private fun buildNotification(state: ModelDownloadState): Notification {
        val text = when (state) {
            is ModelDownloadState.Downloading -> "${state.downloadedBytes / 1_000_000} of ${state.totalBytes / 1_000_000} MB (${state.percent}%)"
            ModelDownloadState.Validating -> "Checking the download…"
            ModelDownloadState.Initializing -> "Preparing the model…"
            else -> "Starting…"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ModelDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val downloading = state as? ModelDownloadState.Downloading
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Downloading the speech model")
            .setContentText(text)
            .setProgress(100, downloading?.percent ?: 0, downloading == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "voice_memo_model_download_channel"
        private const val NOTIFICATION_ID = 1102
        private const val ACTION_CANCEL = "io.github.elgrande73.voicememo.action.CANCEL_MODEL_DOWNLOAD"

        fun start(context: Context) {
            try {
                val intent = Intent(context, ModelDownloadService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                Log.w("ModelDownloadService", "Could not start the download service; downloading in the app", e)
                if (!ModelDownloads.isRunning) Thread({ ModelDownloads.run(context.applicationContext) }, "ModelDownload").start()
            }
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(CHANNEL_ID, "Speech model download", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress of the speech model download you started in Settings"
                    setShowBadge(false)
                }
                context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }
        }
    }
}
