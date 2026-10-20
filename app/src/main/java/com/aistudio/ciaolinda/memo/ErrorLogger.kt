package com.aistudio.ciaolinda.memo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Diagnostic logger that logs errors to Android Logcat as well as the local SQLite database.
 */
object ErrorLogger {

    private val scope = CoroutineScope(Dispatchers.IO)

    fun log(context: Context, tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        scope.launch {
            try {
                val db = AppDatabase.getDatabase(context)
                db.errorLogDao().insertLog(
                    ErrorLog(
                        tag = tag,
                        message = message,
                        stackTrace = throwable?.stackTraceToString()
                    )
                )
            } catch (_: Exception) {}
        }
    }
}
