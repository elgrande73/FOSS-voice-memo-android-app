package io.github.elgrande73.voicememo.shortcuts

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.elgrande73.voicememo.monitoring.AudioMonitoringService
import io.github.elgrande73.voicememo.settings.PreferenceKeys

/**
 * Quick Settings tile that starts and stops a memo. Adding or refreshing the tile never records; only a tap does.
 * - Monitoring running: a tap toggles the memo through the running service (also on the lock screen while recording
 *   controls are on; otherwise Android asks to unlock first).
 * - Monitoring off: a tap opens the app, which starts the memo ([RecordingEntryPoints]); Android lets an app start the
 *   microphone only from a visible screen. On the lock screen the device is unlocked first (no app content is ever
 *   shown over the lock screen).
 * The tile shows the real state (Recording / Tap to record) and is refreshed on every state change.
 */
class RecordingTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (!AudioMonitoringService.isServiceRunning.value) {
            if (isLocked) unlockAndRun { openAppToRecord() } else openAppToRecord()
            return
        }
        val prefs = getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        if (isLocked && !ShortcutSettings.lockScreenControlsEnabled(prefs)) {
            unlockAndRun { AudioMonitoringService.requestToggle(this) }
        } else {
            AudioMonitoringService.requestToggle(this)
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAppToRecord() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(RecordingEntryPoints.recordPendingIntent(this, 30))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(RecordingEntryPoints.recordIntent(this))
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val view = tileView(AudioMonitoringService.isServiceRunning.value, AudioMonitoringService.isCurrentlyRecording.value)
        tile.state = view.state
        tile.label = view.label
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = view.subtitle
        tile.updateTile()
    }

    /** Tile appearance for a state: never "active" unless a memo is really being recorded. */
    data class TileView(val state: Int, val label: String, val subtitle: String)

    companion object {
        fun tileView(monitoring: Boolean, recording: Boolean): TileView = when {
            monitoring && recording -> TileView(Tile.STATE_ACTIVE, "Stop memo", "Recording")
            monitoring -> TileView(Tile.STATE_INACTIVE, "Voice memo", "Tap to record")
            else -> TileView(Tile.STATE_INACTIVE, "Voice memo", "Tap to open and record")
        }

        /** Asks the system to refresh the tile (after the recording or monitoring state changed). */
        fun requestUpdate(context: Context) {
            try {
                TileService.requestListeningState(context, ComponentName(context, RecordingTileService::class.java))
            } catch (_: RuntimeException) {
                // No tile added, or the system UI is not available (tests)
            }
        }
    }
}
