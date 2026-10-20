package io.github.elgrande73.voicememo.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** Controls automatic monitoring on behalf of the phrase-capture workflow. */
interface MonitoringControl {
    val isRunning: Boolean
    fun stop()
    fun start()
}

enum class PhraseCaptureKind { WAKE_PHRASE, TEST_PHRASE }

sealed interface PhraseCaptureState {
    data object Idle : PhraseCaptureState
    /** Monitoring was asked to stop; waiting until it has released the microphone. */
    data class StoppingMonitoring(val kind: PhraseCaptureKind) : PhraseCaptureState
    /** The microphone is ours; the AudioRecord is being started. */
    data class Starting(val kind: PhraseCaptureKind) : PhraseCaptureState
    /** The AudioRecord is capturing. */
    data class Recording(val kind: PhraseCaptureKind) : PhraseCaptureState
}

/** The workflow kind of a non-idle state, or null when idle. */
fun PhraseCaptureState.kind(): PhraseCaptureKind? = when (this) {
    PhraseCaptureState.Idle -> null
    is PhraseCaptureState.StoppingMonitoring -> kind
    is PhraseCaptureState.Starting -> kind
    is PhraseCaptureState.Recording -> kind
}

sealed interface CaptureOutcome<out T> {
    data class Completed<T>(val value: T) : CaptureOutcome<T>
    /** A capture is already in progress (e.g. repeated taps); nothing was started. */
    data object Busy : CaptureOutcome<Nothing>
    data object PermissionDenied : CaptureOutcome<Nothing>
    /** Monitoring did not release the microphone in time; recording was not started. */
    data object MicrophoneUnavailable : CaptureOutcome<Nothing>
    data class Failed(val error: Throwable) : CaptureOutcome<Nothing>
}

/** Passed to the capture block, which reports when its AudioRecord has really started. */
fun interface CaptureSession {
    fun recordingStarted()
}

/**
 * Coordinates a Settings phrase recording (wake-phrase enrollment or phrase test) with automatic monitoring:
 *
 * 1. Rejects the request if another capture is running (repeated taps start nothing).
 * 2. Checks the microphone permission.
 * 3. Remembers whether monitoring was running and asks it to stop.
 * 4. Waits — suspending, never blocking the main thread — until monitoring has released the microphone in
 *    [MicrophoneArbiter]; if that does not happen within [releaseTimeoutMs], recording is NOT started.
 * 5. Runs the capture while holding the microphone, then releases it (also on failure or cancellation).
 * 6. Restores monitoring if it was running before, unless the user started/stopped monitoring meanwhile
 *    ([onUserChangedMonitoring]) or the permission is gone. Restoration also happens after failures, so a failed
 *    recording never leaves monitoring disabled.
 */
class PhraseCaptureCoordinator(
    private val arbiter: MicrophoneArbiter,
    private val monitoring: MonitoringControl,
    private val hasPermission: () -> Boolean,
    private val releaseTimeoutMs: Long = DEFAULT_RELEASE_TIMEOUT_MS
) {
    private val _state = MutableStateFlow<PhraseCaptureState>(PhraseCaptureState.Idle)
    val state: StateFlow<PhraseCaptureState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    /** User-facing description of the last failure, cleared when a new capture starts. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val busy = AtomicBoolean(false)
    @Volatile
    private var userChangedMonitoring = false

    /** True while a capture workflow (including stopping and restoring monitoring) is in progress. */
    val isBusy: Boolean get() = busy.get()

    /** Call when the user explicitly starts or stops monitoring; a running workflow will then not restore it. */
    fun onUserChangedMonitoring() {
        if (busy.get()) userChangedMonitoring = true
    }

    suspend fun <T> runCapture(kind: PhraseCaptureKind, capture: suspend (CaptureSession) -> T): CaptureOutcome<T> {
        if (!busy.compareAndSet(false, true)) return CaptureOutcome.Busy
        _lastError.value = null
        userChangedMonitoring = false
        val wasMonitoring = monitoring.isRunning
        try {
            if (!hasPermission()) {
                _lastError.value = "Microphone permission is required to record a phrase."
                return CaptureOutcome.PermissionDenied
            }
            if (wasMonitoring) {
                _state.value = PhraseCaptureState.StoppingMonitoring(kind)
                monitoring.stop()
            }
            if (!arbiter.acquire(MicrophoneOwner.PHRASE_CAPTURE, releaseTimeoutMs)) {
                _lastError.value = "Monitoring did not release the microphone. Please try again."
                return CaptureOutcome.MicrophoneUnavailable
            }
            try {
                _state.value = PhraseCaptureState.Starting(kind)
                val value = capture(CaptureSession { _state.value = PhraseCaptureState.Recording(kind) })
                return CaptureOutcome.Completed(value)
            } finally {
                arbiter.release(MicrophoneOwner.PHRASE_CAPTURE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _lastError.value = "Recording failed: ${e.message ?: e.javaClass.simpleName}"
            return CaptureOutcome.Failed(e)
        } finally {
            withContext(NonCancellable) {
                if (wasMonitoring && !userChangedMonitoring && !monitoring.isRunning) {
                    if (hasPermission()) {
                        monitoring.start()
                    } else {
                        _lastError.value = "Monitoring could not be resumed: microphone permission is missing."
                    }
                }
                _state.value = PhraseCaptureState.Idle
                busy.set(false)
            }
        }
    }

    companion object {
        const val DEFAULT_RELEASE_TIMEOUT_MS = 3_000L
    }
}
