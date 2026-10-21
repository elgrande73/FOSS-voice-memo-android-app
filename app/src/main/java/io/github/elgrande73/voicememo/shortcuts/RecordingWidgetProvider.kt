package io.github.elgrande73.voicememo.shortcuts

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import io.github.elgrande73.voicememo.BuildConfig
import io.github.elgrande73.voicememo.R
import io.github.elgrande73.voicememo.monitoring.AudioMonitoringService

/**
 * Home-screen widget with one large Record / Stop button. It shows the real state of the monitoring service (updated
 * by [RecordingEntryPoints.onStateChanged] on every change; no polling, `updatePeriodMillis` is 0):
 * - monitoring off: "Record memo" opens the app, which starts the memo ([RecordingEntryPoints]);
 * - monitoring on, not recording: "Record memo" starts a memo through the running service, without opening the app;
 * - recording: "Stop memo" stops it the same way.
 */
class RecordingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = views(context)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_WIDGET_TOGGLE) {
            // A stale widget (monitoring stopped meanwhile) is only redrawn: from the background the app may not
            // start the microphone; the redrawn button opens the app instead
            AudioMonitoringService.requestToggle(context)
            updateAll(context)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_WIDGET_TOGGLE = "${BuildConfig.APPLICATION_ID}.action.WIDGET_TOGGLE"

        /** What the widget shows for a monitoring/recording state. */
        enum class Display(val label: String, val description: String) {
            OPEN_AND_RECORD("Record memo", "Opens Voice Memo and starts a memo"),
            RECORD("Record memo", "Starts a memo"),
            STOP("Stop memo", "Stops the memo being recorded")
        }

        fun display(monitoring: Boolean, recording: Boolean): Display = when {
            monitoring && recording -> Display.STOP
            monitoring -> Display.RECORD
            else -> Display.OPEN_AND_RECORD
        }

        fun views(context: Context): RemoteViews {
            val shown = display(AudioMonitoringService.isServiceRunning.value, AudioMonitoringService.isCurrentlyRecording.value)
            val click = if (shown == Display.OPEN_AND_RECORD) {
                RecordingEntryPoints.recordPendingIntent(context, 20)
            } else {
                PendingIntent.getBroadcast(
                    context, 21,
                    Intent(context, RecordingWidgetProvider::class.java).setAction(ACTION_WIDGET_TOGGLE),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            return RemoteViews(context.packageName, R.layout.widget_recording).apply {
                setTextViewText(R.id.widget_label, shown.label)
                setImageViewResource(R.id.widget_icon, if (shown == Display.STOP) R.drawable.ic_stop_memo else R.drawable.ic_record_memo)
                setInt(R.id.widget_button, "setBackgroundResource",
                    if (shown == Display.STOP) R.drawable.widget_background_recording else R.drawable.widget_background)
                setContentDescription(R.id.widget_button, shown.description)
                setOnClickPendingIntent(R.id.widget_button, click)
            }
        }

        /** Redraws every placed widget with the current state (no-op if none is placed). */
        fun updateAll(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, RecordingWidgetProvider::class.java))
                if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
            } catch (_: RuntimeException) {
                // Widget host not available (tests, restricted profiles)
            }
        }
    }
}
