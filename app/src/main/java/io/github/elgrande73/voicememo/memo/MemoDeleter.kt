package io.github.elgrande73.voicememo.memo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** Outcome of deleting a set of memos. */
data class DeleteReport(
    /** Memos whose audio and database entry were removed. */
    val deleted: Set<Int>,
    /** Requested memos that no longer existed (e.g. deleted elsewhere in the meantime). */
    val alreadyGone: Set<Int>,
    /** Memos that were kept because their audio file could not be removed, or the database update failed. */
    val failed: Set<Int>
) {
    val requested: Int get() = deleted.size + alreadyGone.size + failed.size
}

/**
 * Deletes memos (database entry and WAV file) so that the database and the file system stay consistent: every memo is
 * either removed completely or kept completely.
 *
 * 1. Each memo's audio file is moved into a trash folder next to it (a rename on the same file system). If that fails
 *    the memo is kept.
 * 2. The database rows of the moved memos are deleted in one transaction. If that fails, the files are moved back.
 * 3. The trash is emptied; anything left there (e.g. after a crash) is removed by [emptyTrash] later. Rows are already
 *    gone at that point, so nothing in the app refers to those files.
 *
 * Only files inside [memosDir] are ever touched: a memo whose path points elsewhere (it should never happen) loses its
 * database entry but its file is left alone, so enrollment samples, test recordings and other data cannot be removed
 * through this class.
 */
class MemoDeleter(private val dao: VoiceNoteDao, private val memosDir: File) {

    private val trashDir get() = File(memosDir, TRASH_DIR)

    suspend fun delete(ids: Collection<Int>): DeleteReport = withContext(Dispatchers.IO + NonCancellable) {
        // Not cancellable: a half-finished deletion is rolled back or completed, never abandoned
        val requested = ids.toSet()
        if (requested.isEmpty()) return@withContext DeleteReport(emptySet(), emptySet(), emptySet())
        val notes = requested.toList().chunked(VoiceNoteDao.SQL_CHUNK).flatMap { dao.getNotesByIds(it) }
        val alreadyGone = requested - notes.map { it.id }.toSet()

        val moved = mutableMapOf<Int, Pair<File, File>>() // id -> (original, trash)
        val removable = mutableSetOf<Int>()
        val failed = mutableSetOf<Int>()
        for (note in notes) {
            val file = File(note.audioFilePath)
            when {
                !isInsideMemosDir(file) -> {
                    Log.w(TAG, "Memo ${note.id}: audio outside the memo folder is not deleted")
                    removable += note.id
                }
                !file.exists() -> removable += note.id // audio already missing: only the entry remains
                else -> {
                    val target = File(trashDir.apply { mkdirs() }, "${note.id}_${file.name}")
                    if (file.renameTo(target)) {
                        moved[note.id] = file to target
                        removable += note.id
                    } else {
                        failed += note.id
                    }
                }
            }
        }

        val deleted = try {
            dao.deleteByIds(removable.toList())
            removable.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Deleting ${removable.size} memo entries failed; restoring their audio", e)
            for ((original, trash) in moved.values) {
                if (!trash.renameTo(original)) Log.e(TAG, "Could not restore ${original.name}")
            }
            failed += removable
            emptySet()
        }
        if (deleted.isNotEmpty()) emptyTrash()
        DeleteReport(deleted, alreadyGone, failed)
    }

    /** Removes files left in the trash folder; their database entries no longer exist. */
    fun emptyTrash() {
        trashDir.listFiles()?.forEach { if (!it.delete()) Log.w(TAG, "Could not remove ${it.name} from the trash") }
    }

    private fun isInsideMemosDir(file: File): Boolean = try {
        val dir = memosDir.canonicalFile
        val canonical = file.canonicalFile
        canonical.parentFile == dir && canonical.name.endsWith(".wav", ignoreCase = true)
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val TAG = "MemoDeleter"
        const val TRASH_DIR = ".deleting"
    }
}
