package io.github.elgrande73.voicememo.memo

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Voice Notes table.
 */
@Dao
interface VoiceNoteDao {

    @Query("SELECT * FROM voice_notes ORDER BY timestamp DESC")
    fun getAllNotes(): Flow<List<VoiceNote>>

    @Query("SELECT * FROM voice_notes WHERE id = :id LIMIT 1")
    suspend fun getNoteById(id: Int): VoiceNote?

    @Query("SELECT * FROM voice_notes WHERE is_favorite = 1 ORDER BY timestamp DESC")
    fun getFavoriteNotes(): Flow<List<VoiceNote>>

    @Query("SELECT * FROM voice_notes WHERE title LIKE '%' || :query || '%' OR transcription_text LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchNotes(query: String): Flow<List<VoiceNote>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoiceNote(note: VoiceNote): Long

    @Update
    suspend fun updateVoiceNote(note: VoiceNote)

    @Query("UPDATE voice_notes SET title = :newTitle WHERE id = :id")
    suspend fun updateTitle(id: Int, newTitle: String)

    @Query("UPDATE voice_notes SET is_favorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Int, isFavorite: Boolean)

    @Query("SELECT id FROM voice_notes WHERE transcription_status = :status ORDER BY timestamp ASC")
    suspend fun getNoteIdsWithTranscriptionStatus(status: String): List<Int>

    @Query("UPDATE voice_notes SET transcription_text = :text, transcription_status = :status WHERE id = :id")
    suspend fun updateTranscription(id: Int, text: String?, status: String)

    @Delete
    suspend fun deleteVoiceNote(note: VoiceNote)

    @Query("DELETE FROM voice_notes WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM voice_notes")
    suspend fun deleteAll()

    @Query("SELECT * FROM voice_notes WHERE id IN (:ids)")
    suspend fun getNotesByIds(ids: List<Int>): List<VoiceNote>

    @Query("SELECT id FROM voice_notes ORDER BY timestamp DESC")
    suspend fun getAllNoteIds(): List<Int>

    @Query("DELETE FROM voice_notes WHERE id IN (:ids)")
    suspend fun deleteByIdsChunk(ids: List<Int>): Int

    /** Deletes the rows of [ids] in one transaction (all or none); returns the number of rows removed. */
    @Transaction
    suspend fun deleteByIds(ids: List<Int>): Int = ids.chunked(SQL_CHUNK).sumOf { deleteByIdsChunk(it) }

    companion object {
        /** Stays below SQLite's limit of bound variables per statement on old Android versions (999). */
        const val SQL_CHUNK = 500
    }
}
