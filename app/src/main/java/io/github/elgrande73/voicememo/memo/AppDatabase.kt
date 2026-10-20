package io.github.elgrande73.voicememo.memo

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room Database storing Voice Notes and diagnostic Error Logs.
 *
 * Data safety rules:
 * - The schema is exported to `app/schemas` so every version is reviewable and testable.
 * - There is deliberately NO destructive migration fallback. A schema change must ship with an explicit
 *   [androidx.room.migration.Migration] in [MIGRATIONS]; otherwise Room fails loudly instead of
 *   silently dropping the user's recordings.
 */
@Database(entities = [VoiceNote::class, ErrorLog::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun voiceNoteDao(): VoiceNoteDao
    abstract fun errorLogDao(): ErrorLogDao

    companion object {
        /** Persisted database file name. Never rename: existing installations would lose their memos index. */
        const val DATABASE_NAME = "voice_memo_database"

        /** Explicit, non-destructive schema migrations (none needed yet: schema is at version 1). */
        val MIGRATIONS = emptyArray<androidx.room.migration.Migration>()

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(*MIGRATIONS)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
