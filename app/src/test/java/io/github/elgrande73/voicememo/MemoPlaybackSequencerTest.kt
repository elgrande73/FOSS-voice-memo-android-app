package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.playback.ChimePlayer
import io.github.elgrande73.voicememo.playback.MemoAudioOutput
import io.github.elgrande73.voicememo.playback.MemoPlaybackSequencer
import io.github.elgrande73.voicememo.playback.MemoPlaybackSequencer.State
import io.github.elgrande73.voicememo.playback.PlaybackCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoPlaybackSequencerTest {

    /** Ordered log of everything audible: "chime:MEMO_BEGIN", "memo:3", ... */
    private val log = mutableListOf<String>()
    private val pendingChimes = ArrayDeque<() -> Unit>()
    private val startedTokens = mutableMapOf<Int, Long>()
    private val unplayable = mutableSetOf<Int>()
    private val reportedUnplayable = mutableListOf<Int>()
    private var displayedList = listOf(1, 2, 3)
    private lateinit var sequencer: MemoPlaybackSequencer

    @Before
    fun setUp() {
        sequencer = MemoPlaybackSequencer(
            chimes = object : ChimePlayer {
                override fun play(cue: PlaybackCue, onFinished: () -> Unit) {
                    log += "chime:$cue"
                    pendingChimes.addLast(onFinished)
                }
            },
            output = object : MemoAudioOutput {
                override fun start(noteId: Int, token: Long) {
                    log += "memo:$noteId"
                    startedTokens[noteId] = token
                }

                override fun stop() {}
            },
            isPlayable = { it !in unplayable }
        ).apply { onUnplayable = { reportedUnplayable += it } }
    }

    private fun finishChime() = pendingChimes.removeFirst().invoke()

    private fun playFromList(noteId: Int) = sequencer.play(noteId) { displayedList }

    /** Lets the begin chime of the current memo finish so its audio starts. */
    private fun startAudio() {
        finishChime()
    }

    private fun completeMemo(noteId: Int) = sequencer.onMemoCompleted(startedTokens.getValue(noteId))

    @Test
    fun beginChimePlaysBeforeMemoAudio() {
        playFromList(1)
        assertEquals(State.BeginChime(1), sequencer.state)
        assertEquals(listOf("chime:MEMO_BEGIN"), log)

        startAudio()
        assertEquals(State.PlayingMemo(1), sequencer.state)
        assertEquals(listOf("chime:MEMO_BEGIN", "memo:1"), log)
    }

    @Test
    fun naturalCompletionPlaysEndChimeThenNextBeginChimeThenNextMemo() {
        playFromList(1)
        startAudio()
        completeMemo(1)
        assertEquals(State.EndChime(1), sequencer.state)

        finishChime() // ending chime of memo 1
        assertEquals(State.BeginChime(2), sequencer.state)
        finishChime() // beginning chime of memo 2

        assertEquals(
            listOf("chime:MEMO_BEGIN", "memo:1", "chime:MEMO_END", "chime:MEMO_BEGIN", "memo:2"),
            log
        )
        assertEquals(State.PlayingMemo(2), sequencer.state)
    }

    @Test
    fun followsDisplayedOrderOfFilteredList() {
        // Only memos 7, 3 and 5 are visible (e.g. a search or favorites filter), in this display order
        displayedList = listOf(7, 3, 5)
        playFromList(7)
        startAudio()
        completeMemo(7)
        finishChime()
        finishChime()
        completeMemo(3)
        finishChime()
        finishChime()

        assertEquals(listOf("memo:7", "memo:3", "memo:5"), log.filter { it.startsWith("memo") })
    }

    @Test
    fun endOfListPlaysEndingChimeAndStopsWithoutWrapping() {
        playFromList(3)
        startAudio()
        completeMemo(3)
        assertEquals(State.EndChime(3), sequencer.state)
        finishChime()

        assertEquals(State.Idle, sequencer.state)
        assertEquals(listOf("chime:MEMO_BEGIN", "memo:3", "chime:MEMO_END"), log)
        assertTrue(pendingChimes.isEmpty())
    }

    @Test
    fun singleMemoWithoutListDoesNotAdvance() {
        sequencer.play(2, null)
        startAudio()
        completeMemo(2)
        finishChime()

        assertEquals(State.Idle, sequencer.state)
        assertEquals(listOf("chime:MEMO_BEGIN", "memo:2", "chime:MEMO_END"), log)
    }

    @Test
    fun manualStopPlaysNoEndingChimeAndDoesNotAdvance() {
        playFromList(1)
        startAudio()
        val token = startedTokens.getValue(1)
        sequencer.stop()
        // A late completion callback after stopping must be ignored
        sequencer.onMemoCompleted(token)

        assertEquals(State.Idle, sequencer.state)
        assertEquals(listOf("chime:MEMO_BEGIN", "memo:1"), log)
    }

    @Test
    fun stopDuringBeginChimeNeverStartsTheMemo() {
        playFromList(1)
        sequencer.stop()
        finishChime() // the already-playing chime finishes after the stop

        assertEquals(State.Idle, sequencer.state)
        assertEquals(listOf("chime:MEMO_BEGIN"), log)
    }

    @Test
    fun stopDuringEndingChimeCancelsAutomaticAdvance() {
        playFromList(1)
        startAudio()
        completeMemo(1)
        sequencer.stop()
        finishChime()

        assertEquals(State.Idle, sequencer.state)
        assertEquals(listOf("chime:MEMO_BEGIN", "memo:1", "chime:MEMO_END"), log)
    }

    @Test
    fun duplicateCompletionEventsAreIgnored() {
        playFromList(1)
        startAudio()
        completeMemo(1)
        completeMemo(1)
        completeMemo(1)

        assertEquals(1, log.count { it == "chime:MEMO_END" })
        finishChime()
        finishChime()
        assertEquals(listOf("memo:1", "memo:2"), log.filter { it.startsWith("memo") })
        assertTrue(pendingChimes.isEmpty())
    }

    @Test
    fun staleCompletionFromPreviousMemoIsIgnoredAfterNewSelection() {
        playFromList(1)
        startAudio()
        val oldToken = startedTokens.getValue(1)
        playFromList(3) // user picks another memo
        sequencer.onMemoCompleted(oldToken)

        assertEquals(State.BeginChime(3), sequencer.state)
        assertEquals(0, log.count { it == "chime:MEMO_END" })
    }

    @Test
    fun newSelectionWhileChimePendingDoesNotStartTwoMemos() {
        playFromList(1)
        playFromList(2)
        finishChime() // stale begin chime of memo 1
        finishChime() // begin chime of memo 2

        assertEquals(listOf("memo:2"), log.filter { it.startsWith("memo") })
        assertEquals(State.PlayingMemo(2), sequencer.state)
    }

    @Test
    fun unplayableNextMemoIsSkipped() {
        unplayable += 2
        playFromList(1)
        startAudio()
        completeMemo(1)
        finishChime()
        finishChime()

        assertEquals(listOf("memo:1", "memo:3"), log.filter { it.startsWith("memo") })
    }

    @Test
    fun memoRemovedFromListDuringPlaybackIsNotPlayed() {
        playFromList(1)
        startAudio()
        displayedList = listOf(1, 3) // memo 2 deleted or filtered out while memo 1 plays
        completeMemo(1)
        finishChime()
        finishChime()

        assertEquals(listOf("memo:1", "memo:3"), log.filter { it.startsWith("memo") })
    }

    @Test
    fun currentMemoLeavingListContinuesFromItsPreviousPosition() {
        displayedList = listOf(1, 2, 3, 4)
        playFromList(2)
        startAudio()
        displayedList = listOf(1, 4) // filter changed: memo 2 and 3 no longer belong to the list
        completeMemo(2)
        finishChime()
        finishChime()

        assertEquals(listOf("memo:2", "memo:4"), log.filter { it.startsWith("memo") })
    }

    @Test
    fun failureDuringAutomaticAdvanceSkipsToFollowingMemo() {
        playFromList(1)
        startAudio()
        completeMemo(1)
        finishChime()
        finishChime() // memo 2 starts
        sequencer.onMemoFailed(startedTokens.getValue(2))
        finishChime() // begin chime of memo 3

        assertEquals(listOf("memo:1", "memo:2", "memo:3"), log.filter { it.startsWith("memo") })
        assertEquals(1, log.count { it == "chime:MEMO_END" })
    }

    @Test
    fun failureOfExplicitlySelectedMemoStopsAndReportsIt() {
        playFromList(1)
        startAudio()
        sequencer.onMemoFailed(startedTokens.getValue(1))

        assertEquals(State.Idle, sequencer.state)
        assertEquals(listOf(1), reportedUnplayable)
        assertTrue(pendingChimes.isEmpty())
    }

    @Test
    fun unplayableSelectionIsReportedWithoutChimes() {
        unplayable += 1
        playFromList(1)

        assertEquals(State.Idle, sequencer.state)
        assertTrue(log.isEmpty())
        assertEquals(listOf(1), reportedUnplayable)
        assertNull(sequencer.currentNoteId)
    }

    @Test
    fun singleModeEndsAfterTheEndChime() {
        sequencer.autoAdvance = false
        playFromList(1)
        startAudio()
        completeMemo(1)
        assertEquals(State.EndChime(1), sequencer.state)
        finishChime()
        assertEquals(State.Idle, sequencer.state)
        assertEquals(listOf("chime:MEMO_BEGIN", "memo:1", "chime:MEMO_END"), log)
        assertTrue(pendingChimes.isEmpty())
    }

    @Test
    fun switchingModeDuringPlaybackNeitherInterruptsNorRestartsTheMemo() {
        playFromList(1)
        startAudio()
        sequencer.autoAdvance = false // user switches to Single while memo 1 plays
        assertEquals(State.PlayingMemo(1), sequencer.state)
        sequencer.autoAdvance = true // and back to Autoplay
        sequencer.autoAdvance = false
        assertEquals(listOf("chime:MEMO_BEGIN", "memo:1"), log)
        completeMemo(1)
        finishChime()
        assertEquals(State.Idle, sequencer.state)
        assertEquals(listOf("chime:MEMO_BEGIN", "memo:1", "chime:MEMO_END"), log)
    }

    @Test
    fun switchingToAutoplayDuringTheLastMemoOfSingleModeContinuesWithTheNext() {
        sequencer.autoAdvance = false
        playFromList(1)
        startAudio()
        sequencer.autoAdvance = true
        completeMemo(1)
        finishChime()
        finishChime()
        assertEquals(State.PlayingMemo(2), sequencer.state)
        assertEquals(1, log.count { it.startsWith("memo:1") })
    }

    @Test
    fun autoplayNeverPlaysAMemoThatIsFilteredOutAndStopsAtTheEndOfTheList() {
        displayedList = listOf(1, 2, 3, 4)
        playFromList(1)
        startAudio()
        displayedList = listOf(1, 3) // a filter now hides 2 and 4
        completeMemo(1)
        finishChime()
        finishChime()
        assertEquals(State.PlayingMemo(3), sequencer.state)
        completeMemo(3)
        finishChime()
        assertEquals(State.Idle, sequencer.state)
        assertTrue(log.none { it == "memo:2" || it == "memo:4" })
    }
}
