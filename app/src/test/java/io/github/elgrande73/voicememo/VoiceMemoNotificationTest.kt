package io.github.elgrande73.voicememo

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.monitoring.AudioMonitoringService
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import io.github.elgrande73.voicememo.viewmodel.VoiceMemoViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceMemoNotificationTest {

    @Test
    fun testSettingsDefaultsAndPersistence() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        val viewModel = VoiceMemoViewModel(app)
        assertEquals(48.0f, viewModel.thresholdDb.value, 0.001f)
        assertTrue(viewModel.noiseFilterEnabled.value)

        viewModel.setThresholdDb(55.0f)
        assertEquals(55.0f, viewModel.thresholdDb.value, 0.001f)
        assertEquals(55.0f, prefs.getFloat("thresholdDb", 0f), 0.001f)

        viewModel.resetAllSettingsToDefaults()
        assertEquals(48.0f, viewModel.thresholdDb.value, 0.001f)
    }

    @Test
    fun testNotificationChannelsCreated() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                AudioMonitoringService.CHANNEL_ID,
                "Voice Memo Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live background voice-note monitoring status"
            }
            notificationManager.createNotificationChannel(serviceChannel)

            val noteChannel = NotificationChannel(
                AudioMonitoringService.CHANNEL_NOTE_ID,
                "Voice Note Captured",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when an automatic voice memo is saved"
            }
            notificationManager.createNotificationChannel(noteChannel)

            val retrievedServiceChannel = notificationManager.getNotificationChannel(AudioMonitoringService.CHANNEL_ID)
            assertNotNull(retrievedServiceChannel)
            assertEquals("Voice Memo Monitoring", retrievedServiceChannel.name)

            val retrievedNoteChannel = notificationManager.getNotificationChannel(AudioMonitoringService.CHANNEL_NOTE_ID)
            assertNotNull(retrievedNoteChannel)
            assertEquals("Voice Note Captured", retrievedNoteChannel.name)
        }
    }
}
