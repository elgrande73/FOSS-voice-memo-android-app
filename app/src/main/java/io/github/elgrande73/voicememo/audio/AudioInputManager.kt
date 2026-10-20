package io.github.elgrande73.voicememo.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
    val isWired: Boolean
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

    fun getAvailableInputDevices(context: Context): List<AudioInputDevice> {
        val devices = mutableListOf<AudioInputDevice>()
        devices.add(AudioInputDevice.PHONE_MIC)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val audioDevices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS) ?: emptyArray()

            for (device in audioDevices) {
                if (device.isSource) {
                    val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val address = device.address
                        val prodName = device.productName.toString()
                        if (prodName.isNotBlank()) prodName else "Input device (${device.id})"
                    } else {
                        val prodName = device.productName.toString()
                        if (prodName.isNotBlank()) prodName else "Input device (${device.id})"
                    }

                    val isBuiltIn = isBuiltInType(device.type)
                    val isBluetooth = isBluetoothType(device.type)
                    val isUsb = isUsbType(device.type)
                    val isWired = isWiredType(device.type)
                    val typeName = getDeviceTypeName(device.type)

                    // Avoid duplicate phone microphone entry
                    if (!isBuiltIn) {
                        devices.add(
                            AudioInputDevice(
                                id = device.id,
                                name = name,
                                type = device.type,
                                typeName = typeName,
                                isBuiltIn = isBuiltIn,
                                isBluetooth = isBluetooth,
                                isUsb = isUsb,
                                isWired = isWired
                            )
                        )
                    }
                }
            }
        }

        return devices
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
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP Device"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset Microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Microphone"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset Microphone"
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
