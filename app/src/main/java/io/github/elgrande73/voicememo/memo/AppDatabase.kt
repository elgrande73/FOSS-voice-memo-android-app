package io.github.elgrande73.voicememo.memo

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.elgrande73.voicememo.detection.MemoTriggerDetector

/**
 * Room Database storing Voice Notes and diagnostic Error Logs.
 *
 * Data safety rules:
 * - The schema is exported to `app/schemas` so every version is reviewable and testable.
 * - There is deliberately NO destructive migration fallback. A schema change must ship with an explicit
 *   [androidx.room.migration.Migration] in [MIGRATIONS]; otherwise Room fails loudly instead of
 *   silently dropping the user's recordings.
 */
@Database(entities = [VoiceNote::class, ErrorLog::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun voiceNoteDao(): VoiceNoteDao
    abstract fun errorLogDao(): ErrorLogDao

    companion object {
        /** Persisted database file name. Never rename: existing installations would lose their memos index. */
        const val DATABASE_NAME = "voice_memo_database"

        /**
         * 1 → 2: no schema change. Every wake-phrase memo gets the trigger type
         * [io.github.elgrande73.voicememo.detection.MemoTriggerDetector.WAKE_PHRASE_TRIGGER_TYPE]; earlier versions stored
         * other `KEYWORD…` values. Only that column of those rows is rewritten; audio, titles and transcripts are untouched.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE voice_notes SET trigger_type = ? WHERE trigger_type LIKE 'KEYWORD%' AND trigger_type != ?",
                    arrayOf<Any>(MemoTriggerDetector.WAKE_PHRASE_TRIGGER_TYPE, MemoTriggerDetector.WAKE_PHRASE_TRIGGER_TYPE)
                )
            }
        }

        /** Explicit, non-destructive schema migrations. */
        val MIGRATIONS = arrayOf<Migration>(MIGRATION_1_2)

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
