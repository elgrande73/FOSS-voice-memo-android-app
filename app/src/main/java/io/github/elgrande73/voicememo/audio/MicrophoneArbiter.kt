package io.github.elgrande73.voicememo.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

enum class MicrophoneOwner { NONE, MONITORING, PHRASE_CAPTURE }

/**
 * Single source of truth for who may capture from the microphone in this process. Monitoring and the Settings phrase
 * recordings must hold the microphone here before creating an AudioRecord and release it only after the AudioRecord
 * has been released, so the two pipelines can never capture at the same time.
 */
class MicrophoneArbiter {

    private val _owner = MutableStateFlow(MicrophoneOwner.NONE)
    val owner: StateFlow<MicrophoneOwner> = _owner.asStateFlow()

    /** Takes the microphone if it is free (or already held by [owner]). Never blocks. */
    @Synchronized
    fun tryAcquire(owner: MicrophoneOwner): Boolean {
        require(owner != MicrophoneOwner.NONE)
        val current = _owner.value
        if (current != MicrophoneOwner.NONE && current != owner) return false
        _owner.value = owner
        return true
    }

    /** Releases the microphone if [owner] holds it; a release by anyone else is ignored. */
    @Synchronized
    fun release(owner: MicrophoneOwner) {
        if (_owner.value == owner) _owner.value = MicrophoneOwner.NONE
    }

    /** Suspends (without blocking a thread) until the microphone could be taken, or returns false after [timeoutMs]. */
    suspend fun acquire(owner: MicrophoneOwner, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!tryAcquire(owner)) {
                _owner.first { it == MicrophoneOwner.NONE || it == owner }
            }
            true
        } ?: false

    /** Suspends until nobody else holds the microphone. */
    suspend fun awaitAvailableFor(owner: MicrophoneOwner) {
        _owner.first { it == MicrophoneOwner.NONE || it == owner }
    }

    companion object {
        /** The process-wide instance shared by AudioMonitoringService and the Settings recordings. */
        val shared = MicrophoneArbiter()
    }
}
