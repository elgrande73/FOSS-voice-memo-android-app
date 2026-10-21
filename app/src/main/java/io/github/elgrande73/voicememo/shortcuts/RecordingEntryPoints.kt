package io.github.elgrande73.voicememo.shortcuts

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.elgrande73.voicememo.BuildConfig
import io.github.elgrande73.voicememo.MainActivity

/**
 * One-tap recording from outside the app (Quick Settings tile, home-screen widget, launcher shortcut).
 *
 * Android lets an app start using the microphone only while one of its screens is visible (Android 11+; Android 14
 * refuses to start the microphone foreground service otherwise). So:
 * - while monitoring runs, its foreground service already holds the microphone and these controls toggle the memo
 *   through it ([io.github.elgrande73.voicememo.monitoring.AudioMonitoringService.requestToggle]), without opening the app;
 * - otherwise they open the app with [ACTION_RECORD_MEMO]; the app starts the memo exactly like the dashboard's Quick
 *   Memo button (asking for the microphone permission first if needed) and shows the dashboard with its Stop button.
 *
 * There is no second recorder: every path ends in the same AudioMonitoringService actions.
 */
object RecordingEntryPoints {
    /** Opens the app and starts a memo (a memo already being recorded is kept). Never stops one. */
    const val ACTION_RECORD_MEMO = "${BuildConfig.APPLICATION_ID}.action.RECORD_MEMO"

    fun recordIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION_RECORD_MEMO)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun recordPendingIntent(context: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode, recordIntent(context),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Called whenever monitoring or recording state changes: refreshes the tile and the widgets (no polling). */
    fun onStateChanged(context: Context) {
        RecordingTileService.requestUpdate(context)
        RecordingWidgetProvider.updateAll(context)
    }
}
