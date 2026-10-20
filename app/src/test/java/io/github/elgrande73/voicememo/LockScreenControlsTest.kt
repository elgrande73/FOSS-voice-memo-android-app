package io.github.elgrande73.voicememo

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.monitoring.AudioMonitoringService
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import io.github.elgrande73.voicememo.shortcuts.ShortcutSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The monitoring notification's lock-screen behaviour (notification built by the real service, no capture). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LockScreenControlsTest {

    private lateinit var app: Application
    private val prefs get() = app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        prefs.edit().clear().commit()
    }

    private fun notificationAfterRefresh(): Notification {
        val controller = Robolectric.buildService(AudioMonitoringService::class.java,
            Intent(app, AudioMonitoringService::class.java).setAction(AudioMonitoringService.ACTION_REFRESH_NOTIFICATION)).create().startCommand(0, 1)
        val manager = app.getSystemService(NotificationManager::class.java)
        val notification = shadowOf(manager).getNotification(AudioMonitoringService.NOTIFICATION_ID)
            ?: shadowOf(controller.get()).lastForegroundNotification
        controller.destroy()
        return notification
    }

    @Test
    fun notificationOffersAStartButtonOnTheLockScreen() {
        val notification = notificationAfterRefresh()
        assertEquals(AudioMonitoringService.CONTROLS_CHANNEL_ID, notification.channelId)
        assertEquals(Notification.VISIBILITY_PUBLIC, notification.visibility)
        val action = notification.actions.single()
        assertEquals("Start memo", action.title.toString())
        assertFalse("runs without unlocking", action.isAuthenticationRequired)

        val manager = app.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(AudioMonitoringService.CONTROLS_CHANNEL_ID)
        assertNotNull(channel)
        assertNull("never makes a sound", channel.sound)
        assertEquals(Notification.VISIBILITY_PUBLIC, channel.lockscreenVisibility)
        assertTrue("shown on lock screens that hide silent notifications", channel.importance >= NotificationManager.IMPORTANCE_DEFAULT)
        assertNull("old silent channel removed", manager.getNotificationChannel(AudioMonitoringService.CHANNEL_ID))
    }

    @Test
    fun turningLockScreenControlsOffRequiresUnlockingAndHidesTheButton() {
        ShortcutSettings.setLockScreenControlsEnabled(prefs, false)
        val notification = notificationAfterRefresh()
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertTrue(notification.actions.single().isAuthenticationRequired)
        assertNotNull(notification.publicVersion)
        assertTrue("lock-screen version has no buttons", notification.publicVersion.actions.isNullOrEmpty())
    }

    @Test
    fun otherServiceRequestsDuringAMemoKeepTheStopButton() {
        val controller = Robolectric.buildService(AudioMonitoringService::class.java,
            Intent(app, AudioMonitoringService::class.java).setAction(AudioMonitoringService.ACTION_START_MANUAL_RECORDING)).create().startCommand(0, 1)
        val manager = app.getSystemService(NotificationManager::class.java)
        assertEquals("Stop memo", shadowOf(manager).getNotification(AudioMonitoringService.NOTIFICATION_ID).actions.single().title.toString())
        // e.g. the threshold changed in Settings while recording
        controller.withIntent(
            Intent(app, AudioMonitoringService::class.java).setAction(AudioMonitoringService.ACTION_UPDATE_MATCH_CONFIDENCE_THRESHOLD)
                .putExtra(AudioMonitoringService.EXTRA_MATCH_CONFIDENCE_THRESHOLD_PERCENT, 60)
        ).startCommand(0, 2)
        assertEquals("Stop memo", shadowOf(manager).getNotification(AudioMonitoringService.NOTIFICATION_ID).actions.single().title.toString())
        controller.destroy()
    }

    @Test
    fun toggleRequestsAreRefusedWhileMonitoringIsOff() {
        assertFalse(AudioMonitoringService.isServiceRunning.value)
        assertFalse(AudioMonitoringService.requestToggle(app))
        assertNull(shadowOf(app).nextStartedService)
    }
}
