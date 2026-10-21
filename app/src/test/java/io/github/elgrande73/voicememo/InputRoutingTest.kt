package io.github.elgrande73.voicememo

import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import io.github.elgrande73.voicememo.audio.InputKind
import io.github.elgrande73.voicememo.audio.InputPort
import io.github.elgrande73.voicememo.audio.InputRouting
import io.github.elgrande73.voicememo.audio.InputSelection
import io.github.elgrande73.voicememo.audio.InputSignalCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Routing decisions for the built-in, wired, USB and Bluetooth microphones (no Android audio service needed). */
class InputRoutingTest {

    private val builtIn = InputPort(1, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Phone microphone")
    private val builtInBack = InputPort(2, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Phone microphone")
    private val wired = InputPort(10, AudioDeviceInfo.TYPE_WIRED_HEADSET, "Wired Headset Microphone")
    private val usb = InputPort(20, AudioDeviceInfo.TYPE_USB_DEVICE, "USB Condenser Mic")
    private val btSco = InputPort(30, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds", "AA:BB:CC:DD:EE:FF")
    private val bleHeadset = InputPort(31, InputRouting.TYPE_BLE_HEADSET, "LE Buds", "11:22:33:44:55:66")
    private val telephony = InputPort(40, AudioDeviceInfo.TYPE_TELEPHONY, "Telephony")
    private val submix = InputPort(41, AudioDeviceInfo.TYPE_REMOTE_SUBMIX, "Submix")
    private val all = listOf(builtIn, builtInBack, wired, usb, btSco, bleHeadset, telephony, submix)

    private fun selectionOf(port: InputPort) = InputSelection(port.id, port.name, port.type, port.address)

    @Test
    fun kinds() {
        assertEquals(InputKind.BUILT_IN, InputRouting.kindOf(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals(InputKind.WIRED, InputRouting.kindOf(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(InputKind.USB, InputRouting.kindOf(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(InputKind.USB, InputRouting.kindOf(AudioDeviceInfo.TYPE_USB_ACCESSORY))
        assertEquals(InputKind.BLUETOOTH, InputRouting.kindOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(InputKind.BLUETOOTH, InputRouting.kindOf(InputRouting.TYPE_BLE_HEADSET))
        // Output-only devices never count as microphones
        assertEquals(InputKind.OTHER, InputRouting.kindOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(InputKind.OTHER, InputRouting.kindOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(InputKind.OTHER, InputRouting.kindOf(AudioDeviceInfo.TYPE_TELEPHONY))
    }

    @Test
    fun settingsOfferHeadsetUsbAndBluetoothMicrophonesOnly() {
        assertEquals(listOf(wired, usb, btSco, bleHeadset), InputRouting.selectable(all, wiredHeadsetPlugged = true))
        // A jack microphone port reported while nothing is plugged in is not offered
        assertEquals(listOf(usb, btSco, bleHeadset), InputRouting.selectable(all, wiredHeadsetPlugged = false))
    }

    @Test
    fun phoneSelectionPrefersTheBuiltInMicrophoneSoHeadsetsDoNotTakeOver() {
        val plan = InputRouting.plan(InputSelection.PHONE, all)
        assertEquals(builtIn, plan.target)
        assertEquals(MediaRecorder.AudioSource.MIC, plan.audioSource)
        assertFalse(plan.bluetooth)
        assertFalse(plan.selectedUnavailable)
    }

    @Test
    fun wiredAndUsbAreRoutedWithoutBluetoothLink() {
        for (port in listOf(wired, usb)) {
            val plan = InputRouting.plan(selectionOf(port), all)
            assertEquals(port, plan.target)
            assertEquals(MediaRecorder.AudioSource.MIC, plan.audioSource)
            assertFalse(plan.bluetooth)
        }
    }

    @Test
    fun bluetoothHeadsetNeedsTheVoiceLink() {
        for (port in listOf(btSco, bleHeadset)) {
            val plan = InputRouting.plan(selectionOf(port), all)
            assertEquals(port, plan.target)
            assertTrue(plan.bluetooth)
            assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, plan.audioSource)
        }
    }

    @Test
    fun aReconnectedDeviceIsFoundUnderItsNewId() {
        val reconnectedBt = btSco.copy(id = 77)
        val reconnectedUsb = usb.copy(id = 78)
        val ports = listOf(builtIn, reconnectedBt, reconnectedUsb)
        assertEquals(reconnectedBt, InputRouting.find(selectionOf(btSco), ports))  // by address
        assertEquals(reconnectedUsb, InputRouting.find(selectionOf(usb), ports))   // by name
        // Selections saved by earlier versions have no type or address: id, then name
        assertEquals(reconnectedUsb, InputRouting.find(InputSelection(20, "USB Condenser Mic"), ports))
    }

    @Test
    fun aRecycledIdOfAnotherDeviceTypeIsNotUsed() {
        // Android gave the old Bluetooth id to a USB microphone
        val ports = listOf(builtIn, usb.copy(id = btSco.id))
        assertNull(InputRouting.find(selectionOf(btSco), ports))
    }

    @Test
    fun missingDeviceFallsBackToThePhoneMicrophoneAndSaysSo() {
        val plan = InputRouting.plan(selectionOf(btSco), listOf(builtIn, usb))
        assertEquals(builtIn, plan.target)
        assertFalse(plan.bluetooth)
        assertTrue(plan.selectedUnavailable)
    }

    @Test
    fun switchingBetweenInputsChangesOnlyTheRoute() {
        val sequence = listOf(selectionOf(btSco), selectionOf(wired), selectionOf(usb), InputSelection.PHONE, selectionOf(bleHeadset))
        val targets = sequence.map { InputRouting.plan(it, all).target }
        assertEquals(listOf(btSco, wired, usb, builtIn, bleHeadset), targets)
        assertEquals(listOf(true, false, false, false, true), sequence.map { InputRouting.plan(it, all).bluetooth })
    }

    @Test
    fun routedDeviceCheck() {
        val btPlan = InputRouting.plan(selectionOf(btSco), all)
        assertTrue(InputRouting.routedAsPlanned(btPlan, btSco.id, btSco.type))
        assertFalse("Android recorded from the phone instead", InputRouting.routedAsPlanned(btPlan, builtIn.id, builtIn.type))
        assertTrue("not reported yet", InputRouting.routedAsPlanned(btPlan, null, null))
        val phonePlan = InputRouting.plan(InputSelection.PHONE, all)
        assertTrue("another built-in microphone of the array", InputRouting.routedAsPlanned(phonePlan, builtInBack.id, builtInBack.type))
        assertFalse(InputRouting.routedAsPlanned(phonePlan, wired.id, wired.type))
    }

    @Test
    fun digitalSilenceIsDetectedButAQuietMicrophoneIsNot() {
        val silent = InputSignalCheck(windowFrames = 5)
        repeat(4) { assertEquals(InputSignalCheck.Result.PENDING, silent.push(ShortArray(800))) }
        assertEquals(InputSignalCheck.Result.SILENT, silent.push(ShortArray(800) { (it % 3 - 1).toShort() }))

        val quiet = InputSignalCheck(windowFrames = 5)
        assertEquals(InputSignalCheck.Result.SIGNAL, quiet.push(ShortArray(800) { if (it == 400) 9 else 0 }))
        assertEquals(InputSignalCheck.Result.SIGNAL, quiet.push(ShortArray(800)))
        quiet.reset()
        assertEquals(InputSignalCheck.Result.PENDING, quiet.push(ShortArray(800)))
    }
}
