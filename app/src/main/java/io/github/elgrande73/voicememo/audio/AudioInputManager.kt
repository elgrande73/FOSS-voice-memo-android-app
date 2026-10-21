package io.github.elgrande73.voicememo.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Build

/**
 * Data representation of an audio input hardware or Bluetooth peripheral device.
 */
data class AudioInputDevice(
    val id: Int,
    val name: String,
    val type: Int,
    val typeName: String,
    val isBuiltIn: Boolean,
    val isBluetooth: Boolean,
    val isUsb: Boolean,
    val isWired: Boolean,
    /** Bluetooth address (Android 9+), used to find the device again after it reconnects under a new id. */
    val address: String = ""
) {
    val displayName: String get() = name

    companion object {
        val PHONE_MIC = AudioInputDevice(
            id = -1,
            name = "Phone microphone",
            type = AudioDeviceInfo.TYPE_BUILTIN_MIC,
            typeName = "Internal Phone Microphone",
            isBuiltIn = true,
            isBluetooth = false,
            isUsb = false,
            isWired = false
        )
        val DEFAULT_DEVICE = PHONE_MIC
    }
}

/**
 * Result evaluation of requested input device vs actual device routing.
 */
data class AudioRoutingEvaluation(
    val configuredDisplayName: String,
    val activeDisplayName: String,
    val isFallback: Boolean
)

/**
 * Manager and utility functions for audio input hardware discovery, selection, and routing.
 */
object AudioInputManager {

    /**
     * The phone microphone plus the connected headset, USB and Bluetooth microphones ([InputRouting.selectable]).
     * Output-only devices (Bluetooth music profiles, headphones without microphone) and virtual inputs are not listed.
     */
    fun getAvailableInputDevices(context: Context): List<AudioInputDevice> =
        listOf(AudioInputDevice.PHONE_MIC) + AndroidInputRouter(context).selectablePorts().map { port ->
            AudioInputDevice(
                id = port.id,
                name = port.name,
                type = port.type,
                typeName = getDeviceTypeName(port.type),
                isBuiltIn = false,
                isBluetooth = port.kind == InputKind.BLUETOOTH,
                isUsb = port.kind == InputKind.USB,
                isWired = port.kind == InputKind.WIRED,
                address = port.address
            )
        }

    fun isUsbType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_USB_ACCESSORY)
    }

    fun isBluetoothType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (
                        type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                                type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                                type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                        ))
    }

    fun isBuiltInType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BUILTIN_MIC ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
    }

    fun isWiredType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
    }

    fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Internal Phone Microphone"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset / Mask (SCO)"
            InputRouting.TYPE_BLE_HEADSET -> "Bluetooth LE Headset Microphone"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP Device"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset Microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Microphone"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset Microphone"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Audio Accessory"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog Line-in"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital Line-in"
            AudioDeviceInfo.TYPE_BUS -> "System Bus Audio"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Remote Submix Loopback"
            else -> "Audio Input Device ($type)"
        }
    }

    fun isPhoneMicrophoneSelection(id: Int, name: String): Boolean {
        if (id == -1) return true
        val lower = name.lowercase()
        return lower.contains("phone") || lower.contains("built-in") || lower.contains("internal") || lower.isEmpty()
    }

    fun evaluateAudioRouting(
        configuredId: Int,
        configuredName: String,
        routedDevice: AudioDeviceInfo?
    ): AudioRoutingEvaluation {
        val isConfiguredAsPhone = isPhoneMicrophoneSelection(configuredId, configuredName)
        val configDisplay = if (configuredName.isNotBlank()) configuredName else AudioInputDevice.PHONE_MIC.name

        if (routedDevice == null) {
            return AudioRoutingEvaluation(
                configuredDisplayName = configDisplay,
                activeDisplayName = AudioInputDevice.PHONE_MIC.name,
                isFallback = !isConfiguredAsPhone
            )
        }

        val activeName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val prod = routedDevice.productName.toString()
            if (prod.isNotBlank()) prod else getDeviceTypeName(routedDevice.type)
        } else {
            val prod = routedDevice.productName.toString()
            if (prod.isNotBlank()) prod else getDeviceTypeName(routedDevice.type)
        }

        val isFallback = !isConfiguredAsPhone && (routedDevice.id != configuredId)

        return AudioRoutingEvaluation(
            configuredDisplayName = configDisplay,
            activeDisplayName = activeName,
            isFallback = isFallback
        )
    }
}
