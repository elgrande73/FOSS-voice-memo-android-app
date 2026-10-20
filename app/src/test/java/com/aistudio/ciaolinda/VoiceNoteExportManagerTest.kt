package com.aistudio.ciaolinda

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aistudio.ciaolinda.memo.VoiceNote
import com.aistudio.ciaolinda.memo.VoiceNoteExportManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceNoteExportManagerTest {

    @Test
    fun testCsvGenerationWithVoiceNotes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val exportManager = VoiceNoteExportManager(context)

        val testNotes = listOf(
            VoiceNote(
                id = 1,
                title = "Idea for project",
                timestamp = 1771372800000L,
                durationSeconds = 4.5,
                audioFilePath = "/dummy/path/memo_1.wav",
                triggerType = "KEYWORD_CIAO_LINDA",
                transcriptionText = "Buy groceries",
                peakDb = 68.5f,
                avgDb = 54.0f,
                isFavorite = true
            ),
            VoiceNote(
                id = 2,
                title = "Quick reminder",
                timestamp = 1771372860000L,
                durationSeconds = 2.0,
                audioFilePath = "/dummy/path/memo_2.wav",
                triggerType = "AUTO_SOUND",
                transcriptionText = null,
                peakDb = 62.0f,
                avgDb = 48.0f,
                isFavorite = false
            )
        )

        val csv = exportManager.generateCsvContent(testNotes)
        assertNotNull(csv)
        assertTrue(csv.contains("ID,Timestamp,Date_Time,Title,Duration_Sec,Peak_dB,Avg_dB,Trigger_Type,Transcription_Status,Transcription_Text"))
        assertTrue(csv.contains("Idea for project"))
        assertTrue(csv.contains("KEYWORD_CIAO_LINDA"))
        assertTrue(csv.contains("Buy groceries"))
        assertTrue(csv.contains("Quick reminder"))
    }

    @Test
    fun testJsonGenerationWithVoiceNotes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val exportManager = VoiceNoteExportManager(context)

        val testNotes = listOf(
            VoiceNote(
                id = 1,
                title = "Meeting summary",
                timestamp = 1771372800000L,
                durationSeconds = 12.0,
                audioFilePath = "/dummy/path/memo_1.wav",
                triggerType = "MANUAL",
                transcriptionText = "Discussed Q3 goals",
                peakDb = 70.0f,
                avgDb = 55.0f
            )
        )

        val json = exportManager.generateJsonContent(testNotes)
        assertNotNull(json)
        assertTrue(json.contains("\"title\": \"Meeting summary\""))
        assertTrue(json.contains("\"triggerType\": \"MANUAL\""))
        assertTrue(json.contains("\"transcription\": \"Discussed Q3 goals\""))
    }
}
