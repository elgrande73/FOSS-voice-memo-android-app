package io.github.elgrande73.voicememo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.elgrande73.voicememo.ui.GuideTab
import io.github.elgrande73.voicememo.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuideTabTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testGuideTabRendersSuccessfully() {
        composeTestRule.setContent {
            MyApplicationTheme {
                GuideTab()
            }
        }

        // Verify Guide Tab root and hero header are present
        composeTestRule.onNodeWithTag("guide_tab").assertIsDisplayed()
        composeTestRule.onNodeWithText("Voice Memo Guide").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hands-free voice memos").assertIsDisplayed()
    }
}
