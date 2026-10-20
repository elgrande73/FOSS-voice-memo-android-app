package io.github.elgrande73.voicememo.shortcuts

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.elgrande73.voicememo.monitoring.AudioMonitoringService
import io.github.elgrande73.voicememo.settings.PreferenceKeys

/**
 * Quick Settings tile that starts and stops a memo, also from the lock screen. It works while monitoring is running
 * (Android lets an app start using the microphone only from the foreground). If lock-screen controls are turned off,
 * a tap on the lock screen asks the user to unlock first.
 */
class RecordingTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (!AudioMonitoringService.isServiceRunning.value) {
            updateTile()
            return
        }
        val prefs = getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        if (isLocked && !ShortcutSettings.lockScreenControlsEnabled(prefs)) {
            unlockAndRun { AudioMonitoringService.requestToggle(this) }
        } else {
            AudioMonitoringService.requestToggle(this)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val running = AudioMonitoringService.isServiceRunning.value
        val recording = AudioMonitoringService.isCurrentlyRecording.value
        tile.state = when {
            !running -> Tile.STATE_UNAVAILABLE
            recording -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = if (recording) "Stop memo" else "Voice memo"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !running -> "Monitoring off"
                recording -> "Recording"
                else -> "Tap to record"
            }
        }
        tile.updateTile()
    }

    companion object {
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
