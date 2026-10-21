package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import io.github.elgrande73.voicememo.memo.MemoFilter
import io.github.elgrande73.voicememo.memo.MemoSource
import io.github.elgrande73.voicememo.memo.VoiceNote
import io.github.elgrande73.voicememo.memo.VoiceNoteFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoFilterTest {

    private val today = 1_000_000L
    private fun note(id: Int, trigger: String, ts: Long = today + id, favorite: Boolean = false, title: String = "Memo $id") =
        VoiceNote(id = id, title = title, timestamp = ts, durationSeconds = 1.0, audioFilePath = "/x/$id.wav", triggerType = trigger, isFavorite = favorite)

    private val notes = listOf(
        note(1, MemoTriggerDetector.WAKE_PHRASE_TRIGGER_TYPE),
        note(2, "MANUAL", favorite = true),
        note(3, "AUTO_SOUND"),
        note(4, "AUTO_VOICE", ts = today - 10),
        note(5, "", favorite = true), // value of an interrupted early version
        note(6, MemoTriggerDetector.WAKE_PHRASE_TRIGGER_TYPE, ts = today - 5, favorite = true, title = "Groceries"),
        note(7, "manual") // other casing
    )

    private fun ids(filters: Set<VoiceNoteFilter>, query: String = "") = MemoFilter.apply(notes, query, filters, today).map { it.id }

    @Test
    fun persistedTriggerTypesAreInterpretedIncludingOldAndUnknownValues() {
        assertEquals(MemoSource.WAKE_PHRASE, MemoSource.of(MemoTriggerDetector.WAKE_PHRASE_TRIGGER_TYPE))
        assertEquals(MemoSource.WAKE_PHRASE, MemoSource.of("keyword"))
        assertEquals(MemoSource.MANUAL, MemoSource.of("MANUAL"))
        assertEquals(MemoSource.SOUND, MemoSource.of("AUTO_SOUND"))
        assertEquals(MemoSource.SOUND, MemoSource.of("AUTO_VOICE"))
        assertEquals(MemoSource.UNKNOWN, MemoSource.of(""))
        assertEquals(MemoSource.UNKNOWN, MemoSource.of(null))
        assertEquals(MemoSource.UNKNOWN, MemoSource.of("SOMETHING"))
    }

    @Test
    fun noFilterShowsEverythingInTheOriginalOrder() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), ids(emptySet()))
    }

    @Test
    fun manualAndWakePhraseAreAlternativesOfOneFacet() {
        assertEquals(listOf(2, 7), ids(setOf(VoiceNoteFilter.MANUAL)))
        assertEquals(listOf(1, 6), ids(setOf(VoiceNoteFilter.KEYWORD)))
        assertEquals(listOf(1, 2, 6, 7), ids(setOf(VoiceNoteFilter.KEYWORD, VoiceNoteFilter.MANUAL)))
    }

    @Test
    fun restrictionsCombineWithSourcesAndSearch() {
        assertEquals(listOf(2, 6), ids(setOf(VoiceNoteFilter.FAVORITES, VoiceNoteFilter.KEYWORD, VoiceNoteFilter.MANUAL)))
        assertEquals(listOf(2), ids(setOf(VoiceNoteFilter.FAVORITES, VoiceNoteFilter.MANUAL, VoiceNoteFilter.TODAY)))
        assertEquals(listOf(6), ids(setOf(VoiceNoteFilter.FAVORITES), "grocer"))
        assertEquals(emptyList<Int>(), ids(setOf(VoiceNoteFilter.TODAY), "grocer"))
    }

    @Test
    fun allClearsAndChipsToggle() {
        var active = MemoFilter.toggle(emptySet(), VoiceNoteFilter.MANUAL)
        active = MemoFilter.toggle(active, VoiceNoteFilter.KEYWORD)
        assertEquals(setOf(VoiceNoteFilter.MANUAL, VoiceNoteFilter.KEYWORD), active)
        active = MemoFilter.toggle(active, VoiceNoteFilter.MANUAL)
        assertEquals(setOf(VoiceNoteFilter.KEYWORD), active)
        assertEquals(emptySet<VoiceNoteFilter>(), MemoFilter.toggle(active, VoiceNoteFilter.ALL))
    }
}
