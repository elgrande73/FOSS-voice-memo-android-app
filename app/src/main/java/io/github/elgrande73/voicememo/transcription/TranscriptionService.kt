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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the app process running while memos are queued or being transcribed, so Android does not freeze or stop it
 * when the app is not visible (a frozen process would leave a memo "transcribing" until the app is opened again).
 * Shows the progress and a Cancel action. The work itself runs in [TranscriptionManager]; this service only exists
 * while the queue is not empty.
 */
class TranscriptionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            val manager = Transcription.manager(this)
            manager.jobs.value.keys.forEach { manager.cancel(it) }
        }
        startForegroundCompat(buildNotification(Transcription.manager(this).jobs.value))
        scope.launch {
            Transcription.manager(this@TranscriptionService).jobs.collectLatest { jobs ->
                val active = jobs.values.any { it == TranscriptionJobState.Queued || it is TranscriptionJobState.Running }
                if (!active) {
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(jobs))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            when {
                Build.VERSION.SDK_INT >= 35 -> startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else -> startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Not allowed now (e.g. started from the background): transcription continues in the running process
            Log.w(TAG, "Could not enter the foreground", e)
            stopSelf()
        }
    }

    private fun buildNotification(jobs: Map<Int, TranscriptionJobState>): Notification {
        val running = jobs.values.filterIsInstance<TranscriptionJobState.Running>().firstOrNull()
        val queued = jobs.values.count { it == TranscriptionJobState.Queued }
        val text = when {
            running != null && queued > 0 -> "Transcribing (${running.progress}%), $queued more waiting"
            running != null -> "Transcribing (${running.progress}%)"
            else -> "$queued memo(s) waiting"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, TranscriptionService::class.java).setAction(ACTION_CANCEL_ALL), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Transcribing voice memos on this device")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, running?.progress ?: 0, running == null)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "TranscriptionService"
        const val CHANNEL_ID = "voice_memo_transcription_channel"
        const val NOTIFICATION_ID = 1101
        const val ACTION_CANCEL_ALL = "io.github.elgrande73.voicememo.ACTION_CANCEL_TRANSCRIPTIONS"

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(CHANNEL_ID, "Transcription", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress while memos are transcribed on this device"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        /** Starts the service while there is work; failures (background start not allowed) are harmless. */
        fun start(context: Context) {
            try {
                val intent = Intent(context, TranscriptionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Transcription service not started; work continues in the running process", e)
            }
        }
    }
}
