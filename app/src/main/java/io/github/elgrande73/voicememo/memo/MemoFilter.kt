package io.github.elgrande73.voicememo.memo

/**
 * How a memo was started, derived from the persisted [VoiceNote.triggerType]. Stored values are frozen; this mapping
 * also interprets values written by earlier versions.
 */
enum class MemoSource {
    /** Started by the wake phrase (any phrase; stored as MemoTriggerDetector.WAKE_PHRASE_TRIGGER_TYPE). */
    WAKE_PHRASE,
    /** Started by the user: record button, notification, tile or shortcut (stored as "MANUAL"). */
    MANUAL,
    /** Started by sound or voice activity (stored as "AUTO_SOUND" / "AUTO_VOICE"). */
    SOUND,
    /** Empty or unknown value: shown only when no source filter is active. */
    UNKNOWN;

    companion object {
        fun of(triggerType: String?): MemoSource {
            val type = triggerType?.trim()?.uppercase().orEmpty()
            return when {
                type.startsWith("KEYWORD") -> WAKE_PHRASE
                type.startsWith("MANUAL") -> MANUAL
                type.startsWith("AUTO") -> SOUND
                else -> UNKNOWN
            }
        }
    }
}

/** Filter chips of the history list. */
enum class VoiceNoteFilter(val label: String) {
    ALL("All Memos"),
    TODAY("Today"),
    FAVORITES("Favorites"),
    KEYWORD("Wake Phrase"),
    MANUAL("Manual");

    /** The source this chip selects, or null for a non-source filter. */
    val source: MemoSource?
        get() = when (this) {
            KEYWORD -> MemoSource.WAKE_PHRASE
            MANUAL -> MemoSource.MANUAL
            else -> null
        }
}

/**
 * Combined history filter. Semantics: the search query AND every active restriction must match; "Today" and
 * "Favorites" are restrictions, the source chips ("Wake Phrase", "Manual") are alternatives of one facet (OR among
 * them). No active filter shows every memo. The order of [notes] is kept.
 */
object MemoFilter {

    /** Toggles [filter] in [active]; [VoiceNoteFilter.ALL] clears every filter. */
    fun toggle(active: Set<VoiceNoteFilter>, filter: VoiceNoteFilter): Set<VoiceNoteFilter> = when {
        filter == VoiceNoteFilter.ALL -> emptySet()
        filter in active -> active - filter
        else -> active + filter
    }

    fun apply(notes: List<VoiceNote>, query: String, active: Set<VoiceNoteFilter>, startOfTodayMs: Long): List<VoiceNote> {
        val sources = active.mapNotNull { it.source }.toSet()
        val trimmed = query.trim()
        return notes.filter { note ->
            (trimmed.isEmpty() || note.title.contains(trimmed, ignoreCase = true) ||
                note.transcriptionText?.contains(trimmed, ignoreCase = true) == true) &&
                (VoiceNoteFilter.TODAY !in active || note.timestamp >= startOfTodayMs) &&
                (VoiceNoteFilter.FAVORITES !in active || note.isFavorite) &&
                (sources.isEmpty() || MemoSource.of(note.triggerType) in sources)
        }
    }
}
