package com.aistudio.ciaolinda.memo

import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Repository providing clean abstraction over VoiceNote Room database operations.
 */
class VoiceNoteRepository(private val dao: VoiceNoteDao) {

    val allNotes: Flow<List<VoiceNote>> = dao.getAllNotes()
    val favoriteNotes: Flow<List<VoiceNote>> = dao.getFavoriteNotes()

    fun searchNotes(query: String): Flow<List<VoiceNote>> = dao.searchNotes(query)

    suspend fun getNoteById(id: Int): VoiceNote? = dao.getNoteById(id)

    suspend fun insertVoiceNote(note: VoiceNote): Long = dao.insertVoiceNote(note)

    suspend fun updateVoiceNote(note: VoiceNote) = dao.updateVoiceNote(note)

    suspend fun updateTitle(id: Int, newTitle: String) = dao.updateTitle(id, newTitle)

    suspend fun toggleFavorite(id: Int, currentFavorite: Boolean) = dao.setFavorite(id, !currentFavorite)

    suspend fun updateTranscription(id: Int, text: String?, status: String) = dao.updateTranscription(id, text, status)

    suspend fun deleteVoiceNote(note: VoiceNote) {
        try {
            val file = File(note.audioFilePath)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {}
        dao.deleteVoiceNote(note)
    }

    suspend fun deleteAll() = dao.deleteAll()
}
