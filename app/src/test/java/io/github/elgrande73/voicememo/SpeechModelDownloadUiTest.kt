package io.github.elgrande73.voicememo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.elgrande73.voicememo.memo.VoiceNote
import io.github.elgrande73.voicememo.transcription.ModelDownloadState
import io.github.elgrande73.voicememo.transcription.SpeechModelMissingException
import io.github.elgrande73.voicememo.transcription.TranscriptionJobState
import io.github.elgrande73.voicememo.transcription.TranscriptionStatus
import io.github.elgrande73.voicememo.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every download state has a visible status and the right action; a missing model offers the download on the memo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeechModelDownloadUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun downloadStatesAndActions() {
        var state by mutableStateOf<ModelDownloadState>(ModelDownloadState.Missing)
        var downloads = 0; var cancels = 0; var removals = 0
        composeTestRule.setContent {
            MyApplicationTheme {
                SpeechModelDownloadSection(state, onDownload = { downloads++ }, onCancel = { cancels++ }, onRemove = { removals++ })
            }
        }
        composeTestRule.onNodeWithTag("speech_model_download_status").assertTextContains("Not installed", substring = true)
        composeTestRule.onNodeWithTag("download_speech_model").assertTextContains("Download Whisper Base model", substring = true).performClick()
        assertEquals(1, downloads)

        composeTestRule.runOnIdle { state = ModelDownloadState.Downloading(30_000_000, 148_000_000) }
        composeTestRule.onNodeWithTag("speech_model_download_status").assertTextContains("30 of 148 MB (20%)", substring = true)
        composeTestRule.onNodeWithTag("cancel_model_download").performClick()
        assertEquals(1, cancels)

        composeTestRule.runOnIdle { state = ModelDownloadState.Validating }
        composeTestRule.onNodeWithTag("speech_model_download_status").assertTextContains("Checking", substring = true)
        composeTestRule.onNodeWithTag("download_speech_model").assertDoesNotExist()

        composeTestRule.runOnIdle { state = ModelDownloadState.Failed("The download was interrupted.", retryable = true) }
        composeTestRule.onNodeWithTag("download_speech_model").assertTextContains("Retry download").performClick()
        assertEquals(2, downloads)

        composeTestRule.runOnIdle { state = ModelDownloadState.Ready }
        composeTestRule.onNodeWithTag("remove_speech_model").performClick()
        assertEquals("removal needs confirmation", 0, removals)
        composeTestRule.onNodeWithTag("confirm_remove_speech_model").performClick()
        assertEquals(1, removals)
    }

    @Test
    fun aMemoThatFailedForLackOfAModelOffersTheDownload() {
        val note = VoiceNote(id = 3, title = "m", durationSeconds = 1.0, audioFilePath = "/x.wav", transcriptionStatus = TranscriptionStatus.FAILED)
        var downloads = 0
        composeTestRule.setContent {
            MyApplicationTheme {
                TranscriptSection(
                    note, TranscriptionJobState.Failed(SpeechModelMissingException.MISSING_MESSAGE, modelMissing = true),
                    onTranscribe = {}, onCancel = {}, onEdit = {}, onDelete = {}, onDownloadModel = { downloads++ }
                )
            }
        }
        composeTestRule.onNodeWithTag("transcript_status_3").assertTextContains("No speech model is installed", substring = true)
        composeTestRule.onNodeWithTag("transcript_download_model_3").performClick()
        assertEquals(1, downloads)
    }
}
