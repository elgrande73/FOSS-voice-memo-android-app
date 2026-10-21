package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.memo.AppDatabase
import io.github.elgrande73.voicememo.memo.VoiceNote
import io.github.elgrande73.voicememo.playback.PlaybackMode
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import io.github.elgrande73.voicememo.ui.PlayingMemoAutoScroll
import io.github.elgrande73.voicememo.ui.theme.MyApplicationTheme
import io.github.elgrande73.voicememo.viewmodel.VoiceMemoViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking { AppDatabase.getDatabase(app).voiceNoteDao().deleteAll() }
    }

    private fun insert(count: Int, trigger: (Int) -> String): List<Int> = runBlocking {
        val dir = File(app.filesDir, "memos").apply { mkdirs() }
        (1..count).map { i ->
            val file = File(dir, "memo_test_$i.wav").apply { writeBytes(ByteArray(64)) }
            AppDatabase.getDatabase(app).voiceNoteDao().insertVoiceNote(
                VoiceNote(title = "Memo $i", timestamp = 1_000_000L + i, durationSeconds = 1.0, audioFilePath = file.path, triggerType = trigger(i))
            ).toInt()
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        composeTestRule.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
    }

    private fun showHistory(viewModel: VoiceMemoViewModel) {
        composeTestRule.setContent { MyApplicationTheme { HistoryScreen(viewModel) } }
    }

    @Test
    fun manualFilterCombinesWithWakePhraseFilter() {
        insert(6) { if (it % 3 == 0) "MANUAL" else if (it % 3 == 1) MemoTriggerDetector.WAKE_PHRASE_TRIGGER_TYPE else "AUTO_SOUND" }
        val viewModel = VoiceMemoViewModel(app)
        showHistory(viewModel)
        waitFor { viewModel.filteredVoiceNotes.value.size == 6 }

        composeTestRule.onNodeWithTag("filter_manual").performClick()
        waitFor { viewModel.filteredVoiceNotes.value.size == 2 }
        composeTestRule.onNodeWithTag("filter_keyword").performClick()
        waitFor { viewModel.filteredVoiceNotes.value.size == 4 }
        composeTestRule.onNodeWithTag("filter_all").performClick()
        waitFor { viewModel.filteredVoiceNotes.value.size == 6 }
        composeTestRule.onNodeWithTag("filter_all").assertIsSelected()
    }

    @Test
    fun playbackModeIsPersistedAndAutoplayIsTheDefault() {
        val viewModel = VoiceMemoViewModel(app)
        assertEquals(PlaybackMode.AUTOPLAY, viewModel.playbackMode.value)
        insert(1) { "MANUAL" }
        showHistory(viewModel)
        waitFor { viewModel.filteredVoiceNotes.value.size == 1 }
        composeTestRule.onNodeWithTag("playback_mode_single").performClick()
        composeTestRule.onNodeWithTag("playback_mode_single").assertIsSelected()
        assertEquals(PlaybackMode.SINGLE, VoiceMemoViewModel(app).playbackMode.value)
    }

    @Test
    fun selectAllShownThenDeleteRemovesExactlyTheConfirmedMemos() {
        val ids = insert(5) { if (it <= 3) "MANUAL" else "AUTO_SOUND" }
        val viewModel = VoiceMemoViewModel(app)
        showHistory(viewModel)
        waitFor { viewModel.filteredVoiceNotes.value.size == 5 }

        composeTestRule.onNodeWithTag("filter_manual").performClick()
        waitFor { viewModel.filteredVoiceNotes.value.size == 3 }
        composeTestRule.onNodeWithTag("start_selection").performClick()
        composeTestRule.onNodeWithTag("select_all_shown").performClick()
        composeTestRule.onNodeWithTag("selection_count").assertTextContains("3 selected", substring = true)
        composeTestRule.onNodeWithTag("delete_selected").performClick()
        composeTestRule.onNodeWithTag("bulk_delete_message").assertTextContains("3 selected memos", substring = true)
        // Changing the selection behind the open dialog must not widen the deletion
        composeTestRule.runOnIdle { viewModel.selectWholeLibrary() }
        waitFor { viewModel.selectedIds.value.size == 5 }
        composeTestRule.onNodeWithTag("confirm_bulk_delete").performClick()

        waitFor { runBlocking { AppDatabase.getDatabase(app).voiceNoteDao().getAllNoteIds() }.size == 2 }
        val remaining = runBlocking { AppDatabase.getDatabase(app).voiceNoteDao().getAllNoteIds() }.toSet()
        assertEquals(ids.takeLast(2).toSet(), remaining)
        assertEquals(remaining, viewModel.selectedIds.value)
    }

    @Test
    fun selectionCheckboxesToggleAndWholeLibraryIncludesHiddenMemos() {
        insert(4) { if (it == 1) "MANUAL" else "AUTO_SOUND" }
        val viewModel = VoiceMemoViewModel(app)
        showHistory(viewModel)
        waitFor { viewModel.filteredVoiceNotes.value.size == 4 }
        composeTestRule.onNodeWithTag("filter_manual").performClick()
        waitFor { viewModel.filteredVoiceNotes.value.size == 1 }
        composeTestRule.onNodeWithTag("start_selection").performClick()
        val id = viewModel.filteredVoiceNotes.value.single().id
        composeTestRule.onNodeWithTag("select_memo_$id").assertIsOff().performClick().assertIsOn()
        composeTestRule.onNodeWithTag("select_memo_$id").performClick().assertIsOff()
        composeTestRule.onNodeWithTag("select_library").performClick()
        waitFor { viewModel.selectedIds.value.size == 4 }
        composeTestRule.onNodeWithTag("selection_count").assertTextContains("4 selected (3 not shown by the current filter)")
    }

    // --- Auto-scroll on different screen sizes ---

    private fun autoScrollKeepsThePlayButtonVisible(cardHeightDp: Int) {
        var playing by mutableStateOf<Int?>(null)
        composeTestRule.setContent {
            val state = rememberLazyListState()
            PlayingMemoAutoScroll(state, playing, playing?.let { it - 1 } ?: -1, enabled = true, marginPx = 8)
            Column(Modifier.fillMaxSize()) {
                Text("Fixed header", Modifier.height(120.dp)) // search and filters stay above the list
                LazyColumn(state = state, modifier = Modifier.fillMaxWidth().weight(1f).testTag("list")) {
                    items((1..80).toList(), key = { it }) { i ->
                        Column(Modifier.fillMaxWidth().height(cardHeightDp.dp)) {
                            Box(Modifier.weight(1f))
                            Text("play $i", Modifier.testTag("play_$i").height(48.dp))
                        }
                    }
                }
            }
        }
        for (target in listOf(37, 38, 79, 3)) {
            composeTestRule.runOnIdle { playing = target }
            composeTestRule.mainClock.advanceTimeBy(1_000)
            composeTestRule.waitForIdle()
            val list = composeTestRule.onNodeWithTag("list").getUnclippedBoundsInRoot()
            val button = composeTestRule.onNodeWithTag("play_$target").getUnclippedBoundsInRoot()
            assertTrue("play $target ($button) inside list ($list)", button.top >= list.top && button.bottom <= list.bottom)
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp")
    fun autoScrollOnASmallPhone() = autoScrollKeepsThePlayButtonVisible(cardHeightDp = 220)

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun autoScrollOnATablet() = autoScrollKeepsThePlayButtonVisible(cardHeightDp = 220)

    @Test
    @Config(qualifiers = "w480dp-h320dp-land")
    fun autoScrollWithCardsTallerThanTheList() = autoScrollKeepsThePlayButtonVisible(cardHeightDp = 400)

    @Test
    fun noAutoScrollWhenDisabledOrAlreadyVisible() {
        var playing by mutableStateOf<Int?>(null)
        var enabled by mutableStateOf(false)
        lateinit var state: androidx.compose.foundation.lazy.LazyListState
        composeTestRule.setContent {
            state = rememberLazyListState()
            PlayingMemoAutoScroll(state, playing, playing?.let { it - 1 } ?: -1, enabled = enabled, marginPx = 8)
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                items((1..80).toList(), key = { it }) { i -> Text("item $i", Modifier.height(100.dp)) }
            }
        }
        composeTestRule.runOnIdle { playing = 60 }
        composeTestRule.waitForIdle()
        assertEquals(0, state.firstVisibleItemIndex) // Single mode: the list never moves
        composeTestRule.runOnIdle { enabled = true; playing = 2 }
        composeTestRule.waitForIdle()
        assertEquals(0, state.firstVisibleItemIndex) // already visible: no jump
    }
}
