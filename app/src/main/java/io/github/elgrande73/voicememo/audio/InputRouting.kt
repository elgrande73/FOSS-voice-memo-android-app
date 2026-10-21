package io.github.elgrande73.voicememo.audio

import android.media.AudioDeviceInfo
import android.media.MediaRecorder

/** Kind of microphone, as far as routing is concerned. */
enum class InputKind { BUILT_IN, WIRED, USB, BLUETOOTH, OTHER }

/**
 * An Android input device ([AudioDeviceInfo]) reduced to what routing decisions need, so they can be unit-tested
 * without Android's audio service.
 */
data class InputPort(val id: Int, val type: Int, val name: String, val address: String = "") {
    val kind: InputKind get() = InputRouting.kindOf(type)
}

/** The microphone the user chose in Settings, as persisted. [id] is [PHONE_ID] for the phone's own microphone. */
data class InputSelection(val id: Int, val name: String, val type: Int = TYPE_UNKNOWN, val address: String = "") {
    val isPhone: Boolean get() = id == PHONE_ID

    companion object {
        const val PHONE_ID = -1
        const val TYPE_UNKNOWN = 0
        val PHONE = InputSelection(PHONE_ID, AudioInputDevice.PHONE_MIC.name, AudioDeviceInfo.TYPE_BUILTIN_MIC)
    }
}

/**
 * How to open the microphone for a [InputSelection].
 *
 * @param target device for `AudioRecord.setPreferredDevice` (the built-in microphone for the phone selection, so a
 *   plugged-in headset or USB microphone does not take over); null if Android reported none.
 * @param bluetooth the target is a Bluetooth headset microphone: its voice link (SCO / LE audio) must be active
 *   before recording, otherwise Android delivers silence or another microphone.
 * @param selectedUnavailable the chosen device is not connected; the phone microphone is used instead.
 */
data class CapturePlan(
    val target: InputPort?,
    val audioSource: Int,
    val bluetooth: Boolean,
    val selectedUnavailable: Boolean
)

/** Routing decisions shared by monitoring and the Settings phrase recording. Pure functions; see [AndroidInputRouter]. */
object InputRouting {

    fun kindOf(type: Int): InputKind = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputKind.BUILT_IN
        // A wired headset with a microphone; TYPE_WIRED_HEADPHONES has no microphone and is never an input
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> InputKind.WIRED
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> InputKind.USB
        // Headset microphones use the voice link (HFP/SCO, or LE audio on Android 12+). A2DP and BLE speakers are
        // output-only: a Bluetooth device that plays music does not necessarily provide a microphone.
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET -> InputKind.BLUETOOTH
        else -> InputKind.OTHER
    }

    /**
     * Inputs offered in Settings besides the phone microphone: headset, USB and Bluetooth microphones. Virtual and
     * system inputs (telephony, remote submix, tuners, bus) are left out. A wired-headset input is offered only while
     * a wired headset is plugged in ([wiredHeadsetPlugged]): some phones report the jack's microphone port even when
     * nothing is connected.
     */
    fun selectable(ports: List<InputPort>, wiredHeadsetPlugged: Boolean): List<InputPort> =
        ports.filter {
            when (it.kind) {
                InputKind.USB, InputKind.BLUETOOTH -> true
                InputKind.WIRED -> wiredHeadsetPlugged
                InputKind.BUILT_IN, InputKind.OTHER -> false
            }
        }.distinctBy { it.id }

    /**
     * Finds the selected device among the connected [ports]. Android assigns new ids when a device reconnects, so an
     * id is trusted only together with the same type; then the same type with the same address (Bluetooth) or name.
     */
    fun find(selection: InputSelection, ports: List<InputPort>): InputPort? {
        if (selection.isPhone) return null
        val candidates = ports.filter { it.kind != InputKind.BUILT_IN && it.kind != InputKind.OTHER }
        fun sameType(port: InputPort) =
            if (selection.type == InputSelection.TYPE_UNKNOWN) true else port.type == selection.type
        return candidates.firstOrNull { it.id == selection.id && sameType(it) }
            ?: candidates.firstOrNull { sameType(it) && selection.address.isNotBlank() && it.address == selection.address }
            ?: candidates.firstOrNull { sameType(it) && selection.name.isNotBlank() && it.name.equals(selection.name, ignoreCase = true) }
    }

    fun plan(selection: InputSelection, ports: List<InputPort>): CapturePlan {
        val builtIn = ports.firstOrNull { it.kind == InputKind.BUILT_IN }
        val phone = CapturePlan(builtIn, MediaRecorder.AudioSource.MIC, bluetooth = false, selectedUnavailable = false)
        if (selection.isPhone) return phone
        val found = find(selection, ports) ?: return phone.copy(selectedUnavailable = true)
        val bluetooth = found.kind == InputKind.BLUETOOTH
        return CapturePlan(
            target = found,
            // VOICE_RECOGNITION: the source meant for an unprocessed speech signal; it follows the Bluetooth voice
            // route. Everything else keeps MIC, as before.
            audioSource = if (bluetooth) MediaRecorder.AudioSource.VOICE_RECOGNITION else MediaRecorder.AudioSource.MIC,
            bluetooth = bluetooth,
            selectedUnavailable = false
        )
    }

    /**
     * The phone microphone, used when the selected device is missing or its route could not be activated: monitoring
     * keeps working and the dashboard says which microphone is in use.
     */
    fun phoneFallback(ports: List<InputPort>): CapturePlan =
        CapturePlan(ports.firstOrNull { it.kind == InputKind.BUILT_IN }, MediaRecorder.AudioSource.MIC, bluetooth = false, selectedUnavailable = true)

    /**
     * True if Android actually records from the planned device. [routedType] is `AudioRecord.getRoutedDevice()`'s
     * type (null if Android has not reported one yet; then nothing can be concluded and the plan is trusted).
     */
    fun routedAsPlanned(plan: CapturePlan, routedId: Int?, routedType: Int?): Boolean {
        val target = plan.target ?: return true
        if (routedType == null) return true
        if (routedId == target.id) return true
        // The same physical microphone may be reported under another id (e.g. the built-in microphone array)
        return kindOf(routedType) == target.kind && (target.kind == InputKind.BUILT_IN || routedType == target.type)
    }

    /** AudioDeviceInfo.TYPE_BLE_HEADSET (API 31); the constant is inlined so older platforms only compare numbers. */
    const val TYPE_BLE_HEADSET = 26
}

/**
 * Detects a microphone path that delivers digital silence (all samples within ±[maxSilentAmplitude]), which is what
 * a Bluetooth headset whose voice link is not active produces. A real microphone always picks up some noise, so this
 * never fires for a working input in a quiet room; a quiet signal does not prove that the right microphone is used.
 * Evaluated once per capture stream, after [windowFrames] frames.
 */
class InputSignalCheck(private val windowFrames: Int = 40, private val maxSilentAmplitude: Int = 2) {
    enum class Result { PENDING, SIGNAL, SILENT }

    private var frames = 0
    private var peak = 0
    var result: Result = Result.PENDING
        private set

    /** Feeds one frame; returns the result once it is decided (then it no longer changes until [reset]). */
    fun push(frame: ShortArray): Result {
        if (result != Result.PENDING) return result
        for (s in frame) {
            val a = if (s < 0) -s.toInt() else s.toInt()
            if (a > peak) peak = a
        }
        if (peak > maxSilentAmplitude) {
            result = Result.SIGNAL
        } else if (++frames >= windowFrames) {
            result = Result.SILENT
        }
        return result
    }

    fun reset() {
        frames = 0
        peak = 0
        result = Result.PENDING
    }
}
