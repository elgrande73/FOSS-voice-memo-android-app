package io.github.elgrande73.voicememo

import android.Manifest
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Looper
import android.service.quicksettings.Tile
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.monitoring.AudioMonitoringService
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import io.github.elgrande73.voicememo.shortcuts.RecordingEntryPoints
import io.github.elgrande73.voicememo.shortcuts.RecordingTileService
import io.github.elgrande73.voicememo.shortcuts.RecordingWidgetProvider
import io.github.elgrande73.voicememo.shortcuts.RecordingWidgetProvider.Companion.Display
import org.junit.After
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Quick Settings tile, home-screen widget and launcher shortcut: they show the real monitoring/recording state, never
 * record by themselves, and all end in the same AudioMonitoringService requests as the dashboard (no second recorder).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingEntryPointsTest {

    private lateinit var app: Application
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(app).clearStartedServices()
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).runToEndOfTasks()
    }

    private fun startedActions(): List<String?> = generateSequence { shadowOf(app).nextStartedService }.map { it.action }.toList()

    @Test
    fun tileNeverClaimsARecordingThatIsNotRunning() {
        assertEquals(Tile.STATE_INACTIVE, RecordingTileService.tileView(monitoring = false, recording = false).state)
        assertEquals("Tap to open and record", RecordingTileService.tileView(false, false).subtitle)
        assertEquals(Tile.STATE_INACTIVE, RecordingTileService.tileView(monitoring = true, recording = false).state)
        assertEquals(Tile.STATE_ACTIVE, RecordingTileService.tileView(monitoring = true, recording = true).state)
        assertEquals("Stop memo", RecordingTileService.tileView(true, true).label)
        // A stale "recording" flag without a running service is not shown as recording
        assertEquals(Tile.STATE_INACTIVE, RecordingTileService.tileView(monitoring = false, recording = true).state)
    }

    @Test
    fun widgetFollowsTheState() {
        assertEquals(Display.OPEN_AND_RECORD, RecordingWidgetProvider.display(monitoring = false, recording = false))
        assertEquals(Display.OPEN_AND_RECORD, RecordingWidgetProvider.display(monitoring = false, recording = true))
        assertEquals(Display.RECORD, RecordingWidgetProvider.display(monitoring = true, recording = false))
        assertEquals(Display.STOP, RecordingWidgetProvider.display(monitoring = true, recording = true))
    }

    @Test
    fun placingOrRefreshingTheWidgetNeverRecords() {
        val provider = RecordingWidgetProvider()
        val manager = AppWidgetManager.getInstance(app)
        val id = shadowOf(manager).createWidget(RecordingWidgetProvider::class.java, R.layout.widget_recording)
        provider.onUpdate(app, manager, intArrayOf(id))
        RecordingWidgetProvider.updateAll(app)
        assertEquals(emptyList<String?>(), startedActions())
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun aStaleWidgetToggleWithoutMonitoringStartsNothingInTheBackground() {
        assertFalse(AudioMonitoringService.isServiceRunning.value)
        RecordingWidgetProvider().onReceive(app, Intent(app, RecordingWidgetProvider::class.java).setAction(RecordingWidgetProvider.ACTION_WIDGET_TOGGLE))
        assertEquals(emptyList<String?>(), startedActions())
    }

    @Test
    fun recordIntentStartsAMemoThroughTheServiceOnce() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        controller = Robolectric.buildActivity(MainActivity::class.java, RecordingEntryPoints.recordIntent(app)).setup()
        val actions = startedActions()
        assertTrue("monitoring started: $actions", actions.contains(null))
        assertEquals(1, actions.count { it == AudioMonitoringService.ACTION_START_MANUAL_RECORDING })
        // Handled once: the activity's intent no longer asks to record (recreation does not start another memo)
        assertEquals(Intent.ACTION_MAIN, controller!!.get().intent.action)
        controller!!.recreate()
        assertEquals(0, startedActions().count { it == AudioMonitoringService.ACTION_START_MANUAL_RECORDING })
    }

    @Test
    fun recordIntentWithoutMicrophonePermissionAsksFirstAndRecordsNothing() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        controller = Robolectric.buildActivity(MainActivity::class.java, RecordingEntryPoints.recordIntent(app)).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, startedActions().count { it == AudioMonitoringService.ACTION_START_MANUAL_RECORDING })
        val request = shadowOf(controller!!.get()).lastRequestedPermission
        assertNotNull("permission asked", request)
        assertTrue(request.requestedPermissions.contains(Manifest.permission.RECORD_AUDIO))
    }

    @Test
    fun launcherShortcutOpensTheRecordAction() {
        val activityInfo = app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), android.content.pm.PackageManager.GET_META_DATA)
        assertEquals(R.xml.shortcuts, activityInfo.metaData.getInt("android.app.shortcuts"))
        val xml = app.resources.getXml(R.xml.shortcuts)
        var action: String? = null
        while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "intent") {
                action = xml.getAttributeValue("http://schemas.android.com/apk/res/android", "action")
            }
        }
        assertEquals(RecordingEntryPoints.ACTION_RECORD_MEMO, action)
        assertNotNull(app.getSystemService(ShortcutManager::class.java))
    }
}
