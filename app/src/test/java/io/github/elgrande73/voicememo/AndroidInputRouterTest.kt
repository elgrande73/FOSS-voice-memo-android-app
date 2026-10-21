package io.github.elgrande73.voicememo

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.audio.AndroidInputRouter
import io.github.elgrande73.voicememo.audio.AudioInputManager
import io.github.elgrande73.voicememo.audio.InputKind
import io.github.elgrande73.voicememo.audio.InputSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioManager

/**
 * [AndroidInputRouter] against Robolectric's audio service: which devices are offered, Bluetooth voice-link setup and
 * teardown on Android 12+ and before, and the fallback to the phone microphone. This checks the calls the app makes;
 * it cannot show that a real headset delivers audio (see docs/AUDIO_INPUT.md for the hardware checks).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidInputRouterTest {

    private lateinit var app: Application
    private lateinit var audioManager: AudioManager
    private lateinit var shadow: ShadowAudioManager

    private fun device(type: Int): AudioDeviceInfo = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        shadow = shadowOf(audioManager)
    }

    @Test
    @Config(sdk = [35])
    fun offersHeadsetMicrophonesButNotAnEmptyJackOrOutputs() {
        shadow.setInputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_MIC), device(AudioDeviceInfo.TYPE_WIRED_HEADSET)))
        shadow.setOutputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
        val router = AndroidInputRouter(app)
        assertTrue("jack port reported with nothing plugged in", router.selectablePorts().isEmpty())
        shadow.setOutputDevices(listOf(device(AudioDeviceInfo.TYPE_WIRED_HEADSET)))
        assertEquals(listOf(InputKind.WIRED), router.selectablePorts().map { it.kind })
        // Settings: the phone microphone first, then the headset
        val listed = AudioInputManager.getAvailableInputDevices(app)
        assertEquals(2, listed.size)
        assertTrue(listed[1].isWired)
    }

    @Test
    @Config(sdk = [35])
    fun bluetoothLinkIsActivatedThroughTheCommunicationDeviceAndReleased() {
        val scoIn = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        val scoOut = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        shadow.setInputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_MIC), scoIn))
        shadow.setAvailableCommunicationDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE), scoOut))
        val router = AndroidInputRouter(app)
        val port = router.inputPorts().single { it.kind == InputKind.BLUETOOTH }

        assertTrue(runBlocking { router.activateBluetooth(port) })
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, audioManager.communicationDevice?.type)
        router.releaseBluetooth()
        assertNull(audioManager.communicationDevice)
    }

    @Test
    @Config(sdk = [35])
    fun aRefusedOrMissingBluetoothLinkIsReportedAndNothingIsLeftActive() {
        shadow.setInputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_MIC), device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))
        val router = AndroidInputRouter(app)
        val port = router.inputPorts().single { it.kind == InputKind.BLUETOOTH }
        // The headset offers no call (communication) route
        shadow.setAvailableCommunicationDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)))
        assertFalse(runBlocking { router.activateBluetooth(port) })
        assertNull(audioManager.communicationDevice)
        // Android refuses to change the communication device
        shadow.setAvailableCommunicationDevices(listOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))
        shadow.lockCommunicationDevice(true)
        assertFalse(runBlocking { router.activateBluetooth(port) })
        router.releaseBluetooth()
    }

    @Test
    @Config(sdk = [30])
    @Suppress("DEPRECATION")
    fun beforeAndroid12TheScoLinkIsAwaitedAndReleased() {
        val router = AndroidInputRouter(app)
        val port = io.github.elgrande73.voicememo.audio.InputPort(7, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds")
        shadow.setIsBluetoothScoAvailableOffCall(true)
        val result = runBlocking {
            val activation = async(Dispatchers.Default) { router.activateBluetooth(port) }
            // Android reports the SCO link as connected (delivered on the main thread)
            Thread.sleep(200)
            app.sendBroadcast(
                Intent(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                    .putExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_CONNECTED)
            )
            shadowOf(Looper.getMainLooper()).idle()
            activation.await()
        }
        assertTrue(result)
        assertTrue(audioManager.isBluetoothScoOn)
        router.releaseBluetooth()
        assertFalse(audioManager.isBluetoothScoOn)
    }

    @Test
    @Config(sdk = [30])
    fun beforeAndroid12AHeadsetWithoutCallSupportIsNotUsed() {
        shadow.setIsBluetoothScoAvailableOffCall(false)
        val port = io.github.elgrande73.voicememo.audio.InputPort(7, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds")
        assertFalse(runBlocking { AndroidInputRouter(app).activateBluetooth(port) })
    }

    @Test
    @Config(sdk = [35])
    fun openingFallsBackToThePhoneMicrophoneWhenTheBluetoothLinkFails() {
        shadow.setInputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_MIC), device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))
        shadow.setAvailableCommunicationDevices(emptyList())
        val router = AndroidInputRouter(app)
        val bt = router.inputPorts().single { it.kind == InputKind.BLUETOOTH }
        val opened = runBlocking { router.open(InputSelection(bt.id, "Buds", bt.type), 16000, 8192) }
        assertNotNull(opened)
        assertFalse(opened!!.plan.bluetooth)
        assertTrue(opened.plan.selectedUnavailable)
        assertTrue(opened.problem!!.contains("could not be activated"))
        router.close(opened)
        assertNull(audioManager.communicationDevice)
    }

    @Test
    @Config(sdk = [35])
    fun openingTheBluetoothMicrophoneKeepsTheLinkUntilClosed() {
        shadow.setInputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_MIC), device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))
        shadow.setAvailableCommunicationDevices(listOf(device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)))
        val router = AndroidInputRouter(app)
        val bt = router.inputPorts().single { it.kind == InputKind.BLUETOOTH }
        val opened = runBlocking { router.open(InputSelection(bt.id, "Buds", bt.type), 16000, 8192) }!!
        assertTrue(opened.plan.bluetooth)
        assertEquals(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, audioManager.communicationDevice?.type)
        router.close(opened)
        assertNull(audioManager.communicationDevice)
    }

    @Test
    @Config(sdk = [35])
    fun aDisconnectedSelectionUsesThePhoneMicrophoneAndSaysSo() {
        shadow.setInputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_MIC)))
        val router = AndroidInputRouter(app)
        val opened = runBlocking { router.open(InputSelection(42, "USB Condenser Mic", AudioDeviceInfo.TYPE_USB_DEVICE), 16000, 8192) }!!
        assertTrue(opened.plan.selectedUnavailable)
        assertEquals("USB Condenser Mic is not connected; using the phone microphone.", opened.problem)
        router.close(opened)
    }
}
