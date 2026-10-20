package io.github.elgrande73.voicememo.shortcuts

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import io.github.elgrande73.voicememo.monitoring.AudioMonitoringService

/**
 * Optional accessibility service that lets the configured shortcuts work while another app is open or the lock screen
 * is shown (screen on). It only filters key events: it requests no accessibility events, cannot read window content,
 * and consumes only the keys of a configured shortcut. The user enables it in Android's accessibility settings.
 *
 * In the background it can only start a memo while monitoring is running, because Android lets an app start using
 * the microphone only from the foreground.
 */
class RecordingShortcutAccessibilityService : AccessibilityService() {

    private var host: ShortcutKeyHost? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        host = ShortcutKeyHost(this) { toggle() }.also { it.start() }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = host?.onKeyEvent(event) ?: false

    private fun toggle() {
        ShortcutRuntime.foregroundToggle?.let { it(); return }
        if (!AudioMonitoringService.requestToggle(this)) {
            Toast.makeText(this, "Voice Memo: start monitoring in the app to use shortcuts in the background", Toast.LENGTH_LONG).show()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        host?.stop()
        host = null
        super.onDestroy()
    }

    companion object {
        /** Whether the user has enabled this service in Android's accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, RecordingShortcutAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
