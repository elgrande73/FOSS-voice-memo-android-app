package io.github.elgrande73.voicememo.transcription

import android.util.Log
import io.github.elgrande73.voicememo.memo.VoiceNoteDao
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** State of a memo in the transcription queue (memos not in the map are idle). */
sealed interface TranscriptionJobState {
    data object Queued : TranscriptionJobState
    data object Running : TranscriptionJobState
    /** The last attempt failed in this session; the database holds [TranscriptionStatus.FAILED]. */
    data class Failed(val message: String) : TranscriptionJobState
}

/**
 * Transcribes saved memos one at a time on a background [worker] (never the main thread). Only memos the user asked
 * for, or new memos while automatic transcription is on, are transcribed. The original audio is only read.
 *
 * Database: a successful run stores the text with COMPLETED; a failed run sets FAILED and keeps any previous text;
 * a cancelled run changes nothing. Queue state is held in memory only, so a memo can never be left "transcribing"
 * in the database if the app is stopped.
 */
class TranscriptionManager(
    private val dao: VoiceNoteDao,
    private val transcriberFactory: () -> SpeechTranscriber,
    private val settings: () -> TranscriptionSettings,
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher,
    private val idleUnloadMs: Long = DEFAULT_IDLE_UNLOAD_MS,
    private val audioLoader: (String) -> FloatArray = { path -> WavReader.read16kMono(File(path)).samples }
) {
    private val _jobs = MutableStateFlow<Map<Int, TranscriptionJobState>>(emptyMap())
    val jobs: StateFlow<Map<Int, TranscriptionJobState>> = _jobs.asStateFlow()

    private val _lastResult = MutableStateFlow<TranscriptResult?>(null)
    /** Measurement of the last successful run (for diagnostics and the benchmark). */
    val lastResult: StateFlow<TranscriptResult?> = _lastResult.asStateFlow()

    private val queue = Channel<Int>(Channel.UNLIMITED)
    private val cancelTokens = mutableMapOf<Int, CancelToken>()
    private var transcriber: SpeechTranscriber? = null
    private var unloadJob: Job? = null

    init {
        scope.launch(worker) {
            for (noteId in queue) process(noteId)
        }
    }

    /** Queues [noteId] unless it is already queued or running. */
    fun enqueue(noteId: Int) {
        synchronized(cancelTokens) {
            val current = _jobs.value[noteId]
            if (current == TranscriptionJobState.Queued || current == TranscriptionJobState.Running) return
            cancelTokens[noteId] = CancelToken()
            _jobs.update { it + (noteId to TranscriptionJobState.Queued) }
        }
        unloadJob?.cancel()
        queue.trySend(noteId)
    }

    /** Cancels a queued or running transcription; the memo's stored transcript is not changed. */
    fun cancel(noteId: Int) {
        synchronized(cancelTokens) {
            cancelTokens.remove(noteId)?.cancel() ?: return
            _jobs.update { it - noteId }
        }
    }

    /** Forgets a failure message (e.g. after the transcript was edited). */
    fun clearState(noteId: Int) {
        synchronized(cancelTokens) {
            if (_jobs.value[noteId] is TranscriptionJobState.Failed) _jobs.update { it - noteId }
        }
    }

    private suspend fun process(noteId: Int) {
        val token = synchronized(cancelTokens) {
            val t = cancelTokens[noteId] ?: return // cancelled while queued
            _jobs.update { it + (noteId to TranscriptionJobState.Running) }
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
                    throw TranscriptionException(TranscriptionException.Kind.AUDIO, "The recording could not be read.")
                }
                if (samples.isEmpty()) throw TranscriptionException(TranscriptionException.Kind.AUDIO, "The recording is empty.")
                if (!TranscriptText.hasAudibleContent(samples)) {
                    // Silence: Whisper would invent text; store an empty transcript without running the model
                    dao.updateTranscription(noteId, "", TranscriptionStatus.COMPLETED)
                } else {
                    val engine = transcriber ?: transcriberFactory().also { transcriber = it }
                    val result = engine.transcribe(samples, settings(), token)
                    if (token.isCancelled) throw TranscriptionException(TranscriptionException.Kind.CANCELLED, "Cancelled.")
                    val text = TranscriptText.clean(result.text)
                    dao.updateTranscription(noteId, text, TranscriptionStatus.COMPLETED)
                    _lastResult.value = result.copy(text = text)
                }
                null
            }
        } catch (e: TranscriptionException) {
            if (e.kind == TranscriptionException.Kind.CANCELLED || token.isCancelled) {
                null
            } else {
                markFailed(noteId)
                TranscriptionJobState.Failed(e.message ?: "Transcription failed.")
            }
        } catch (e: OutOfMemoryError) {
            transcriber?.release()
            markFailed(noteId)
            TranscriptionJobState.Failed("Not enough memory to transcribe this memo.")
        } catch (e: Exception) {
            Log.e(TAG, "Transcription of memo $noteId failed", e)
            markFailed(noteId)
            TranscriptionJobState.Failed("Transcription failed.")
        }
        synchronized(cancelTokens) {
            cancelTokens.remove(noteId)
            _jobs.update { if (outcome != null) it + (noteId to outcome) else it - noteId }
        }
        if (_jobs.value.values.none { it == TranscriptionJobState.Queued || it == TranscriptionJobState.Running }) scheduleUnload()
    }

    private suspend fun markFailed(noteId: Int) {
        val note = dao.getNoteById(noteId) ?: return
        dao.updateTranscription(noteId, note.transcriptionText, TranscriptionStatus.FAILED)
    }

    /** Frees the model memory after a quiet period. */
    private fun scheduleUnload() {
        unloadJob?.cancel()
        unloadJob = scope.launch {
            delay(idleUnloadMs)
            withContext(worker) {
                if (_jobs.value.values.none { it == TranscriptionJobState.Queued || it == TranscriptionJobState.Running }) {
                    transcriber?.release()
                }
            }
        }
    }

    /** Frees the model now (e.g. when the active model changed). */
    fun releaseModel() {
        scope.launch(worker) { transcriber?.release() }
    }

    companion object {
        private const val TAG = "TranscriptionManager"
        const val DEFAULT_IDLE_UNLOAD_MS = 60_000L
    }
}
