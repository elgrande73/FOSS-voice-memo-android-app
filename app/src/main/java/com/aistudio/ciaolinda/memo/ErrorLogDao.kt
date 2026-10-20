package com.aistudio.ciaolinda.memo

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for ErrorLog entities.
 */
@Dao
interface ErrorLogDao {

    @Query("SELECT * FROM error_logs ORDER BY timestamp DESC LIMIT 100")
    fun getAllLogs(): Flow<List<ErrorLog>>

    @Insert
    suspend fun insertLog(log: ErrorLog)

    @Query("DELETE FROM error_logs")
    suspend fun deleteAllLogs()
}
