package io.github.elgrande73.voicememo.playback

/** Short audio cues framing the playback of each individual memo. */
enum class PlaybackCue { MEMO_BEGIN, MEMO_END }

/** Plays a [PlaybackCue]; [onFinished] must be delivered on the sequencer's thread once the cue has finished (or failed). */
interface ChimePlayer {
    fun play(cue: PlaybackCue, onFinished: () -> Unit)
}

/**
 * Plays the audio of a memo. Implementations report back through [MemoPlaybackSequencer.onMemoCompleted] or
 * [MemoPlaybackSequencer.onMemoFailed] with the token they were started with.
 */
interface MemoAudioOutput {
    fun start(noteId: Int, token: Long)
    fun stop()
}

/**
 * State machine for memo playback with beginning/ending chimes and automatic advance through the list the user is
 * viewing. All methods must be called from a single thread (the main thread in the app).
 *
 * Sequence for an automatic transition: memo A completes naturally -> END chime -> BEGIN chime -> memo B starts.
 * Manual stop, pause or cancellation never plays the END chime and never advances. Every asynchronous callback
 * carries a token; callbacks from a superseded or stopped sequence are ignored, which also suppresses duplicate
 * completion events.
 */
class MemoPlaybackSequencer(
    private val chimes: ChimePlayer,
    private val output: MemoAudioOutput,
    private val isPlayable: (noteId: Int) -> Boolean
) {

    sealed interface State {
        data object Idle : State
        data class BeginChime(val noteId: Int) : State
        data class PlayingMemo(val noteId: Int) : State
        data class EndChime(val noteId: Int) : State
    }

    var state: State = State.Idle
        private set

    /** Notified after every state change. */
    var onStateChanged: ((State) -> Unit)? = null

    /** Notified when a memo the user explicitly selected cannot be played. */
    var onUnplayable: ((noteId: Int) -> Unit)? = null

    private var token = 0L
    // True once the sequence advanced automatically; only then are unplayable memos skipped silently
    private var advancedAutomatically = false
    private var queueProvider: (() -> List<Int>)? = null
    private var lastKnownQueue: List<Int> = emptyList()

    /** The note being played or framed by a chime, or null when idle. */
    val currentNoteId: Int?
        get() = when (val s = state) {
            State.Idle -> null
            is State.BeginChime -> s.noteId
            is State.PlayingMemo -> s.noteId
            is State.EndChime -> s.noteId
        }

    /**
     * Starts playback of [noteId]. [queueProvider] returns the ids of the list currently displayed, in display order
     * (filters, search and sorting applied); null plays only this memo.
     */
    fun play(noteId: Int, queueProvider: (() -> List<Int>)?) {
        cancelCurrent()
        this.queueProvider = queueProvider
        advancedAutomatically = false
        lastKnownQueue = queueProvider?.invoke() ?: emptyList()
        if (!isPlayable(noteId)) {
            moveTo(State.Idle)
            onUnplayable?.invoke(noteId)
            return
        }
        begin(noteId, token)
    }

    /** Manual stop or cancellation: no ending chime, no automatic advance. */
    fun stop() {
        val wasActive = state != State.Idle
        cancelCurrent()
        if (wasActive) moveTo(State.Idle)
    }

    /** Natural completion of the memo started with [token]. Duplicate or stale completions are ignored. */
    fun onMemoCompleted(token: Long) {
        val current = state
        if (token != this.token || current !is State.PlayingMemo) return
        output.stop()
        moveTo(State.EndChime(current.noteId))
        chimes.play(PlaybackCue.MEMO_END) {
            if (token == this.token && state == State.EndChime(current.noteId)) {
                advanceAfter(current.noteId, token)
            }
        }
    }

    /**
     * The memo started with [token] could not be played. During automatic advance it is skipped without chimes;
     * a memo the user selected explicitly stops playback and is reported via [onUnplayable].
     */
    fun onMemoFailed(token: Long) {
        val current = state
        if (token != this.token || current !is State.PlayingMemo) return
        output.stop()
        if (advancedAutomatically) {
            advanceAfter(current.noteId, token)
        } else {
            moveTo(State.Idle)
            onUnplayable?.invoke(current.noteId)
        }
    }

    private fun advanceAfter(noteId: Int, token: Long) {
        val next = findNextPlayable(noteId)
        if (next != null) {
            advancedAutomatically = true
            begin(next, token)
        } else {
            moveTo(State.Idle)
        }
    }

    private fun begin(noteId: Int, token: Long) {
        moveTo(State.BeginChime(noteId))
        chimes.play(PlaybackCue.MEMO_BEGIN) {
            if (token == this.token && state == State.BeginChime(noteId)) {
                if (isPlayable(noteId)) {
                    moveTo(State.PlayingMemo(noteId))
                    output.start(noteId, token)
                } else {
                    // Became unavailable (e.g. deleted) during the chime: continue with the following memo
                    advanceAfter(noteId, token)
                }
            }
        }
    }

    /**
     * The next memo after [currentId] in the list as it is displayed now. If [currentId] is no longer part of that
     * list (filter or search changed, or it was deleted), its position in the previously displayed list is used and
     * only memos that still belong to the current list are considered. Never wraps around.
     */
    private fun findNextPlayable(currentId: Int): Int? {
        val provider = queueProvider ?: return null
        val live = provider()
        val candidates = if (live.contains(currentId)) {
            live.drop(live.indexOf(currentId) + 1)
        } else {
            val liveSet = live.toSet()
            val previousIndex = lastKnownQueue.indexOf(currentId)
            if (previousIndex < 0) emptyList() else lastKnownQueue.drop(previousIndex + 1).filter { it in liveSet }
        }
        lastKnownQueue = live
        return candidates.firstOrNull(isPlayable)
    }

    private fun cancelCurrent() {
        token++
        if (state is State.PlayingMemo) output.stop()
    }

    private fun moveTo(newState: State) {
        state = newState
        onStateChanged?.invoke(newState)
    }
}
