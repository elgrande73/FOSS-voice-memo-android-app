package io.github.elgrande73.voicememo.transcription

import android.util.Log
import io.github.elgrande73.voicememo.memo.VoiceNoteDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** In-memory state of a memo in the transcription queue (memos not in the map are idle). */
sealed interface TranscriptionJobState {
    data object Queued : TranscriptionJobState
    /** Running; [progress] in percent as reported by the engine (0 until it reports). */
    data class Running(val progress: Int = 0) : TranscriptionJobState
    /** The last attempt failed in this session; the database holds [TranscriptionStatus.FAILED]. */
    data class Failed(val message: String, val modelMissing: Boolean = false) : TranscriptionJobState
}

/**
 * Transcribes saved memos one at a time on a background [worker] (never the main thread). Only memos the user asked
 * for, or new memos while automatic transcription is on, are transcribed. The original audio is only read.
 *
 * States (database, survive restarts): [TranscriptionStatus.SKIPPED] not transcribed, [TranscriptionStatus.PENDING]
 * queued (resumed by [resumePending] after the app restarts), [TranscriptionStatus.COMPLETED],
 * [TranscriptionStatus.FAILED]. "Transcribing" exists only in memory ([jobs]), so a run interrupted by the app being
 * stopped goes back to the queue instead of staying "transcribing".
 *
 * Every run ends in a terminal outcome: success, failure (any exception or error, including native-library
 * problems and a watchdog timeout), or cancellation (the stored transcript and status are restored).
 */
class TranscriptionManager(
    private val dao: VoiceNoteDao,
    private val transcriberFactory: () -> SpeechTranscriber,
    private val settings: () -> TranscriptionSettings,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val idleUnloadMs: Long = DEFAULT_IDLE_UNLOAD_MS,
    private val audioLoader: (String) -> FloatArray = { path -> WavReader.read16kMono(File(path)).samples },
    /** Longest a run may take for [audioSeconds] of audio before it is aborted as failed. */
    private val timeoutMs: (audioSeconds: Double) -> Long = { s -> maxOf(MIN_TIMEOUT_MS, (s * TIMEOUT_PER_AUDIO_SECOND_MS).toLong()) },
    /** Called with true when work is queued or running and false when the queue is empty (foreground service). */
    private val onBusyChanged: (Boolean) -> Unit = {}
) {
    private val _jobs = MutableStateFlow<Map<Int, TranscriptionJobState>>(emptyMap())
    val jobs: StateFlow<Map<Int, TranscriptionJobState>> = _jobs.asStateFlow()

    private val _lastResult = MutableStateFlow<TranscriptResult?>(null)
    /** Measurement of the last successful run (for diagnostics). */
    val lastResult: StateFlow<TranscriptResult?> = _lastResult.asStateFlow()

    private val queue = Channel<Int>(Channel.UNLIMITED)
    private val cancelTokens = mutableMapOf<Int, CancelToken>()
    private var transcriber: SpeechTranscriber? = null
    private var unloadJob: Job? = null
    private var busy = false
    /**
     * Status writes run one at a time in request order (e.g. "queued" before a following "cancelled"): the single
     * dispatcher starts them in order and the fair mutex, taken before the first suspension, keeps that order
     * across the database calls.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val statusWrites = Dispatchers.IO.limitedParallelism(1)
    private val statusLock = Mutex()

    init {
        scope.launch(worker) {
            for (noteId in queue) {
                // A failing run must never stop the queue
                try {
                    process(noteId)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Unexpected failure in the transcription queue", t)
                }
            }
        }
    }

    /** Queues [noteId] unless it is already queued or running, and marks it queued in the database. */
    fun enqueue(noteId: Int) {
        synchronized(cancelTokens) {
            val current = _jobs.value[noteId]
            if (current == TranscriptionJobState.Queued || current is TranscriptionJobState.Running) return
            cancelTokens[noteId] = CancelToken()
            _jobs.update { it + (noteId to TranscriptionJobState.Queued) }
        }
        updateBusy()
        unloadJob?.cancel()
        scope.launch(statusWrites) {
            statusLock.withLock {
                try {
                    dao.getNoteById(noteId)?.let { dao.updateTranscription(noteId, it.transcriptionText, TranscriptionStatus.PENDING) }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not mark memo $noteId as queued", e)
                }
                queue.trySend(noteId)
            }
        }
    }

    /** Re-queues memos left in the queue when the app was last stopped. */
    fun resumePending() {
        scope.launch(Dispatchers.IO) {
            try {
                dao.getNoteIdsWithTranscriptionStatus(TranscriptionStatus.PENDING).forEach { enqueue(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not resume queued transcriptions", e)
            }
        }
    }

    /**
     * Cancels a queued or running transcription. The stored transcript is kept and the status returns to
     * COMPLETED (if there is text) or SKIPPED.
     */
    fun cancel(noteId: Int) {
        synchronized(cancelTokens) {
            cancelTokens.remove(noteId)?.cancel() ?: return
            _jobs.update { it - noteId }
        }
        updateBusy()
        scope.launch(statusWrites) { statusLock.withLock { restoreAfterCancel(noteId) } }
    }

    /** Forgets a failure message (e.g. after the transcript was edited). */
    fun clearState(noteId: Int) {
        synchronized(cancelTokens) {
            if (_jobs.value[noteId] is TranscriptionJobState.Failed) _jobs.update { it - noteId }
        }
    }

    private suspend fun restoreAfterCancel(noteId: Int) {
        try {
            val note = dao.getNoteById(noteId) ?: return
            if (note.transcriptionStatus == TranscriptionStatus.PENDING) {
                dao.updateTranscription(
                    noteId, note.transcriptionText,
                    if (note.transcriptionText != null) TranscriptionStatus.COMPLETED else TranscriptionStatus.SKIPPED
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore memo $noteId after cancelling", e)
        }
    }

    private suspend fun process(noteId: Int) {
        val token = synchronized(cancelTokens) {
            val t = cancelTokens[noteId] ?: return // cancelled while queued
            _jobs.update { it + (noteId to TranscriptionJobState.Running()) }
            t
        }
        val outcome: TranscriptionJobState? = try {
            val note = dao.getNoteById(noteId)
            if (note == null) {
                null // memo deleted meanwhile
            } else {
                val samples = try {
                    audioLoader(note.audioFilePath)
                } catch (e: Exception) {
                    throw TranscriptionException(TranscriptionException.Kind.AUDIO, "The recording could not be read (missing or damaged file).")
                }
                if (samples.isEmpty()) throw TranscriptionException(TranscriptionException.Kind.AUDIO, "The recording is empty.")
                if (!TranscriptText.hasAudibleContent(samples)) {
                    // Silence: Whisper would invent text; store an empty transcript without running the model
                    dao.updateTranscription(noteId, "", TranscriptionStatus.COMPLETED)
                } else {
                    val engine = transcriber ?: transcriberFactory().also { transcriber = it }
                    val result = runWithWatchdog(noteId, engine, samples, token)
                    if (token.isCancelled) throw TranscriptionException(TranscriptionException.Kind.CANCELLED, "Cancelled.")
                    val text = TranscriptText.clean(result.text)
                    dao.updateTranscription(noteId, text, TranscriptionStatus.COMPLETED)
                    _lastResult.value = result.copy(text = text)
                }
                null
            }
        } catch (e: TranscriptionException) {
            if (e.kind == TranscriptionException.Kind.CANCELLED && token.isCancelled) {
                null
            } else {
                markFailed(noteId)
                TranscriptionJobState.Failed(e.message ?: "Transcription failed.", modelMissing = e.kind == TranscriptionException.Kind.MODEL_MISSING)
            }
        } catch (e: OutOfMemoryError) {
            releaseQuietly()
            markFailed(noteId)
            TranscriptionJobState.Failed("Not enough memory to transcribe this memo. Close other apps, or choose a smaller model.")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Includes errors such as UnsatisfiedLinkError: never leave the memo "transcribing"
            Log.e(TAG, "Transcription of memo $noteId failed", t)
            releaseQuietly()
            markFailed(noteId)
            TranscriptionJobState.Failed("Transcription failed (${t.javaClass.simpleName}). Tap Retry; if it keeps failing, restart the app.")
        }
        synchronized(cancelTokens) {
            // Only the run that owns the current token may change the state (a cancel + new request may follow)
            if (cancelTokens[noteId] === token) {
                cancelTokens.remove(noteId)
                _jobs.update { if (outcome != null) it + (noteId to outcome) else it - noteId }
            } else if (outcome is TranscriptionJobState.Failed && !_jobs.value.containsKey(noteId)) {
                _jobs.update { it + (noteId to outcome) }
            }
        }
        updateBusy()
        if (!busy) scheduleUnload()
    }

    /** Runs the engine; reports progress and aborts the run (as failed) if it exceeds the time limit. */
    private suspend fun runWithWatchdog(noteId: Int, engine: SpeechTranscriber, samples: FloatArray, token: CancelToken): TranscriptResult {
        val limitMs = timeoutMs(samples.size / 16_000.0)
        val engineToken = CancelToken()
        token.setOnCancel { engineToken.cancel() }
        var timedOut = false
        val monitor = scope.launch(Dispatchers.Default) {
            val start = System.currentTimeMillis()
            while (isActive) {
                delay(PROGRESS_POLL_MS)
                val progress = engine.progress()
                synchronized(cancelTokens) {
                    if (cancelTokens[noteId] === token) _jobs.update { it + (noteId to TranscriptionJobState.Running(progress)) }
                }
                if (System.currentTimeMillis() - start > limitMs) {
                    timedOut = true
                    engineToken.cancel()
                    break
                }
            }
        }
        try {
            return engine.transcribe(samples, settings(), engineToken)
        } catch (e: TranscriptionException) {
            if (timedOut) {
                throw TranscriptionException(
                    TranscriptionException.Kind.FAILED,
                    "Transcription took too long on this device and was stopped. Try the \"Fast\" quality setting or a smaller model."
                )
            }
            throw e
        } finally {
            monitor.cancel()
            token.setOnCancel(null)
        }
    }

    private suspend fun markFailed(noteId: Int) {
        try {
            val note = dao.getNoteById(noteId) ?: return
            dao.updateTranscription(noteId, note.transcriptionText, TranscriptionStatus.FAILED)
        } catch (e: Exception) {
            Log.w(TAG, "Could not mark memo $noteId as failed", e)
        }
    }

    private fun releaseQuietly() {
        try {
            transcriber?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Releasing the speech model failed", t)
        }
        transcriber = null
    }

    private fun updateBusy() {
        val nowBusy = _jobs.value.values.any { it == TranscriptionJobState.Queued || it is TranscriptionJobState.Running }
        val changed = synchronized(this) {
            if (busy == nowBusy) false else { busy = nowBusy; true }
        }
        if (changed) {
            try {
                onBusyChanged(nowBusy)
            } catch (e: Exception) {
                Log.w(TAG, "Busy notification failed", e)
            }
        }
    }

    /** Frees the model memory after a quiet period. */
    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(idleUnloadMs)
            withContext(worker) {
                if (!busy) releaseQuietly()
            }
        }
    }

    /** Frees the model now (e.g. when the active model changed); takes effect after a running job. */
    fun releaseModel() {
        scope.launch(worker) { releaseQuietly() }
    }

    companion object {
        private const val TAG = "TranscriptionManager"
        const val DEFAULT_IDLE_UNLOAD_MS = 60_000L
        const val PROGRESS_POLL_MS = 500L
        /** A run is aborted after max(5 min, 40 × audio duration): far beyond normal speed, but never endless. */
        const val MIN_TIMEOUT_MS = 5 * 60_000L
        const val TIMEOUT_PER_AUDIO_SECOND_MS = 40_000.0
    }
}
