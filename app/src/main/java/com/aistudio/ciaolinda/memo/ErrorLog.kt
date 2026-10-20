package com.aistudio.ciaolinda.memo

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for tracking non-fatal runtime DSP, AudioRecord, and service diagnostic errors.
 */
@Entity(tableName = "error_logs")
data class ErrorLog(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "tag")
    val tag: String,

    @ColumnInfo(name = "message")
    val message: String,

    @ColumnInfo(name = "stack_trace")
    val stackTrace: String? = null
)
