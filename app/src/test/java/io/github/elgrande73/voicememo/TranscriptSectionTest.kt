package io.github.elgrande73.voicememo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.elgrande73.voicememo.memo.VoiceNote
import io.github.elgrande73.voicememo.transcription.TranscriptionJobState
import io.github.elgrande73.voicememo.transcription.TranscriptionStage
import io.github.elgrande73.voicememo.transcription.TranscriptionStatus
import io.github.elgrande73.voicememo.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptSectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val note = VoiceNote(id = 7, title = "m", durationSeconds = 3.0, audioFilePath = "/x.wav", transcriptionText = null, transcriptionStatus = TranscriptionStatus.SKIPPED)

    @Test
    fun actionsFollowTheTranscriptionState() {
        var noteState by androidx.compose.runtime.mutableStateOf(note)
        var job by androidx.compose.runtime.mutableStateOf<TranscriptionJobState?>(null)
        val clicks = mutableListOf<String>()
        composeTestRule.setContent {
            MyApplicationTheme {
                TranscriptSection(noteState, job, { clicks += "transcribe" }, { clicks += "cancel" }, { clicks += "edit" }, { clicks += "delete" })
            }
        }
        composeTestRule.onNodeWithTag("transcript_start_7").assertTextEquals("Transcribe").performClick()
        composeTestRule.runOnIdle { job = TranscriptionJobState.Running(TranscriptionStage.LOADING_MODEL) }
        composeTestRule.onNodeWithTag("transcript_status_7").assertTextEquals("Loading speech model… · 0:03 of audio")
        composeTestRule.runOnIdle { job = TranscriptionJobState.Running(TranscriptionStage.TRANSCRIBING, 35) }
        composeTestRule.onNodeWithTag("transcript_status_7").assertTextEquals("Transcribing on this device… 35% · 0:03 of audio")
        composeTestRule.onNodeWithTag("transcript_cancel_7").performClick()
        composeTestRule.runOnIdle { job = TranscriptionJobState.Failed("Not enough memory to transcribe this memo.") }
        composeTestRule.onNodeWithTag("transcript_status_7").assertTextEquals("Transcription failed: Not enough memory to transcribe this memo.")
        composeTestRule.onNodeWithTag("transcript_start_7").assertTextEquals("Retry Transcription")
        composeTestRule.runOnIdle {
            job = null
            noteState = note.copy(transcriptionText = "Comprare il pane", transcriptionStatus = TranscriptionStatus.COMPLETED)
        }
        composeTestRule.onNodeWithTag("transcript_text_7").assertTextEquals("Comprare il pane")
        composeTestRule.onNodeWithTag("transcript_edit_7").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithTag("transcript_delete_7").performClick()
        composeTestRule.onNodeWithTag("transcript_start_7").assertTextEquals("Transcribe Again")
        composeTestRule.runOnIdle { assertEquals(listOf("transcribe", "cancel", "edit", "delete"), clicks) }
        // After a restart a queued memo shows as waiting (from the stored status) and can be cancelled
        composeTestRule.runOnIdle { noteState = note.copy(transcriptionStatus = TranscriptionStatus.PENDING) }
        composeTestRule.onNodeWithTag("transcript_status_7").assertTextEquals("Waiting to be transcribed…")
        composeTestRule.onNodeWithTag("transcript_cancel_7").assertIsDisplayed()
    }

    @Test
    fun statusTextShowsStageElapsedTimeAndOutcome() {
        val running = TranscriptionJobState.Running(TranscriptionStage.TRANSCRIBING, null, startedAtMs = 10_000)
        assertEquals("Transcribing on this device… · 0:04 elapsed · 0:03 of audio", transcriptionStatusText(note, running, 14_200))
        val long = note.copy(durationSeconds = 95.0)
        assertEquals(
            "Saving transcript… · 1:05 elapsed · 1:35 of audio",
            transcriptionStatusText(long, TranscriptionJobState.Running(TranscriptionStage.SAVING, null, 1_000), 66_000)
        )
        assertEquals("Transcribed 1:35 of audio in 0:21.", transcriptionStatusText(long, TranscriptionJobState.Completed(95_000, 21_400), 0))
        assertEquals("Transcription cancelled.", transcriptionStatusText(note, TranscriptionJobState.Cancelled, 0))
        assertEquals(
            "Transcription cancelled; the previous text was kept.",
            transcriptionStatusText(note.copy(transcriptionText = "old"), TranscriptionJobState.Cancelled, 0)
        )
        assertNull(transcriptionStatusText(note, null, 0))
    }
}
