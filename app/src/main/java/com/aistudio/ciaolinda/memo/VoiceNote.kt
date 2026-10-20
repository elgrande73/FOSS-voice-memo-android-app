package com.aistudio.ciaolinda.memo

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room Database Entity representing an automatically captured or manual voice note / memo.
 */
@Entity(tableName = "voice_notes")
data class VoiceNote(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "duration_seconds")
    val durationSeconds: Double,

    @ColumnInfo(name = "audio_file_path")
    val audioFilePath: String,

    @ColumnInfo(name = "trigger_type")
    val triggerType: String = "AUTO_SOUND", // "KEYWORD_CIAO_LINDA", "AUTO_SOUND", "MANUAL"

    @ColumnInfo(name = "transcription_text")
    val transcriptionText: String? = null,

    @ColumnInfo(name = "transcription_status")
    val transcriptionStatus: String = "PENDING", // "PENDING", "COMPLETED", "SKIPPED", "FAILED"

    @ColumnInfo(name = "peak_db")
    val peakDb: Float = 0f,

    @ColumnInfo(name = "avg_db")
    val avgDb: Float = 0f,

    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false
)
