package io.github.elgrande73.voicememo.audio

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.github.elgrande73.voicememo.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An opened, started microphone stream and how it was routed.
 *
 * @param plan what was requested (after a possible fallback to the phone microphone)
 * @param routedName the microphone Android reports it is recording from (or the planned one if it reports none)
 * @param problem a message for the user when the selected microphone could not be used, else null
 */
class OpenedInput(val record: AudioRecord, val plan: CapturePlan, val routedName: String, val problem: String?)

/**
 * Applies [InputRouting] decisions with Android's audio service: lists the microphones, activates a Bluetooth
 * headset's voice link before recording from it, opens the [AudioRecord] with the planned source and preferred device,
 * checks where Android actually routed it, and releases the Bluetooth link again.
 *
 * Bluetooth: a headset's microphone carries audio only while its voice link (HFP/SCO, or LE audio) is active. Android
 * lists the headset microphone as soon as it is connected, but recording from it without the link gives silence or
 * another microphone. Android 12+ activates the link with `setCommunicationDevice`; Android 7–11 with
 * `startBluetoothSco` + `setBluetoothScoOn`. Both are awaited (event, not a fixed delay) with a timeout. The audio
 * mode is never changed. Wired and USB microphones need no link: `setPreferredDevice` routes them.
 */
class AndroidInputRouter(context: Context) {
    private val context = context.applicationContext
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** True while this router holds a Bluetooth voice link it activated (and must release). */
    @Volatile
    private var ownsBluetoothLink = false

    fun inputPorts(): List<InputPort> =
        audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS).orEmpty()
            .map { InputPort(it.id, it.type, displayName(it), addressOf(it)) }

    /** A wired headset (with or without microphone) is plugged in: its output is listed. */
    fun wiredHeadsetPlugged(): Boolean =
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            .any { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }

    fun selectablePorts(): List<InputPort> = InputRouting.selectable(inputPorts(), wiredHeadsetPlugged())

    private fun deviceInfo(port: InputPort?): AudioDeviceInfo? =
        port?.let { p -> audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.firstOrNull { it.id == p.id } }

    /**
     * Opens and starts the microphone for [selection] (16 kHz mono PCM 16-bit like the rest of the app). Returns null
     * if no microphone could be opened at all; the caller retries later. Call [close] when done.
     */
    @SuppressLint("MissingPermission") // callers check RECORD_AUDIO first; a SecurityException is handled by them
    suspend fun open(selection: InputSelection, sampleRate: Int, bufferBytes: Int): OpenedInput? {
        val ports = inputPorts()
        var plan = InputRouting.plan(selection, ports)
        var problem: String? = if (plan.selectedUnavailable) "${selection.name} is not connected; using the phone microphone." else null
        if (plan.bluetooth) {
            val active = try {
                activateBluetooth(plan.target!!)
            } catch (e: CancellationException) {
                releaseBluetooth() // cancelled while waiting for the link: do not leave it active
                throw e
            }
            if (!active) {
                problem = "The Bluetooth microphone of ${selection.name} could not be activated; using the phone microphone. " +
                    "Check that the headset is connected for calls, then start monitoring again."
                plan = InputRouting.phoneFallback(ports)
            }
        } else {
            releaseBluetooth()
        }
        diag("open: selection=${selection.name} (id=${selection.id}, type=${selection.type}) ports=${ports.map { "${it.name}/${it.type}/${it.id}" }} plan=target ${plan.target?.name}/${plan.target?.id} source=${plan.audioSource} bt=${plan.bluetooth} unavailable=${plan.selectedUnavailable}")

        val record = createRecord(plan.audioSource, sampleRate, bufferBytes)
            ?: if (plan.audioSource != MediaRecorder.AudioSource.MIC) createRecord(MediaRecorder.AudioSource.MIC, sampleRate, bufferBytes) else null
        if (record == null) {
            releaseBluetooth()
            return null
        }
        deviceInfo(plan.target)?.let { target ->
            val accepted = try { record.setPreferredDevice(target) } catch (e: Exception) { false }
            diag("setPreferredDevice(${target.id}) = $accepted")
        }
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            record.release()
            releaseBluetooth()
            throw e
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            releaseBluetooth()
            return null
        }
        // Android may report the route only later (then the routing listener / device callbacks update the status)
        val routed = try { record.routedDevice } catch (e: Exception) { null }
        diag("started: preferred=${plan.target?.id} routed=${routed?.id}/${routed?.type} state=${record.recordingState}")
        if (!InputRouting.routedAsPlanned(plan, routed?.id, routed?.type) && problem == null) {
            problem = "Android records from ${routed?.let { displayName(it) } ?: "another microphone"} instead of ${plan.target?.name}."
        }
        val routedName = routed?.let { displayName(it) } ?: plan.target?.name ?: AudioInputDevice.PHONE_MIC.name
        return OpenedInput(record, plan, routedName, problem)
    }

    /** Stops and releases the stream and the Bluetooth link this router activated. */
    fun close(input: OpenedInput?) {
        if (input != null) {
            try {
                if (input.record.recordingState == AudioRecord.RECORDSTATE_RECORDING) input.record.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioRecord stop failed", e)
            }
            input.record.release()
        }
        releaseBluetooth()
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(source: Int, sampleRate: Int, bufferBytes: Int): AudioRecord? = try {
        AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            .takeIf { it.state == AudioRecord.STATE_INITIALIZED || run { it.release(); false } }
    } catch (e: IllegalArgumentException) {
        diag("AudioRecord(source=$source) rejected: ${e.message}")
        null
    }

    /** Activates the Bluetooth voice link for [port] and waits until Android reports it active. */
    suspend fun activateBluetooth(port: InputPort): Boolean {
        val am = audioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) activateCommunicationDevice(am, port) else activateSco(am)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun activateCommunicationDevice(am: AudioManager, port: InputPort): Boolean {
        // The communication device is the headset's output side; match it by type and, when known, address
        val candidates = am.availableCommunicationDevices.filter { it.type == port.type }
        val device = candidates.firstOrNull { port.address.isNotBlank() && it.address == port.address } ?: candidates.firstOrNull()
        if (device == null) {
            diag("no communication device of type ${port.type} for ${port.name}")
            return false
        }
        if (am.communicationDevice?.id == device.id) return true // already active (e.g. by us before a restart)
        val active = CompletableDeferred<Unit>()
        val listener = AudioManager.OnCommunicationDeviceChangedListener { changed ->
            if (changed?.id == device.id) active.complete(Unit)
        }
        am.addOnCommunicationDeviceChangedListener(ContextCompat.getMainExecutor(context), listener)
        try {
            if (!am.setCommunicationDevice(device)) {
                diag("setCommunicationDevice(${device.id}) refused")
                return false
            }
            ownsBluetoothLink = true
            val ok = am.communicationDevice?.id == device.id || withTimeoutOrNull(LINK_TIMEOUT_MS) { active.await() } != null
            diag("communication device ${device.id} active=$ok")
            if (!ok) releaseBluetooth()
            return ok
        } finally {
            am.removeOnCommunicationDeviceChangedListener(listener)
        }
    }

    // startBluetoothSco is the only way to activate a headset microphone before Android 12. The receiver needs no
    // export flag: this runs only below Android 12, and the flag exists from Android 13.
    @Suppress("DEPRECATION")
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private suspend fun activateSco(am: AudioManager): Boolean {
        if (!am.isBluetoothScoAvailableOffCall) return false
        val connected = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> connected.complete(true)
                    AudioManager.SCO_AUDIO_STATE_ERROR -> connected.complete(false)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        try {
            am.startBluetoothSco()
            ownsBluetoothLink = true
            val ok = withTimeoutOrNull(LINK_TIMEOUT_MS) { connected.await() } == true
            diag("SCO connected=$ok")
            if (ok) am.isBluetoothScoOn = true else releaseBluetooth()
            return ok
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    /** Releases the Bluetooth voice link if this router activated it; never touches a link it did not start. */
    @Suppress("DEPRECATION")
    @Synchronized
    fun releaseBluetooth() {
        if (!ownsBluetoothLink) return
        ownsBluetoothLink = false
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
            }
            diag("Bluetooth link released")
        } catch (e: Exception) {
            Log.w(TAG, "Releasing the Bluetooth link failed", e)
        }
    }

    private fun diag(message: String) {
        // Device names, ids and states only; never audio
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "InputRouter"
        /** Bluetooth links usually come up within 1–2 s; beyond this the headset is treated as unavailable. */
        const val LINK_TIMEOUT_MS = 4000L

        fun displayName(device: AudioDeviceInfo): String {
            // The built-in microphone reports the phone's model as its product name
            if (device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) return AudioInputDevice.PHONE_MIC.name
            val product = device.productName?.toString()?.trim().orEmpty()
            return if (product.isNotBlank() && !product.equals("Android", ignoreCase = true)) product
            else AudioInputManager.getDeviceTypeName(device.type)
        }

        private fun addressOf(device: AudioDeviceInfo): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address.orEmpty() else ""
    }
}
