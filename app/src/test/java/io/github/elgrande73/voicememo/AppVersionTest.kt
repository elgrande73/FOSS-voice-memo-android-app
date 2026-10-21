package io.github.elgrande73.voicememo

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The version shown in Settings → About comes from the installed package and matches the build configuration, and the
 * release metadata (F-Droid draft, fastlane changelog) agrees with it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppVersionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun aboutShowsTheInstalledVersion() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(BuildConfig.VERSION_NAME, installedVersionName(app))
        composeTestRule.setContent { MyApplicationTheme { AboutCard() } }
        composeTestRule.onNodeWithTag("app_version").assertIsDisplayed()
            .assertTextEquals("Version: ${BuildConfig.VERSION_NAME}")
        composeTestRule.onNodeWithTag("open_source_licenses").assertIsDisplayed()
    }

    @Test
    fun versionFollowsTheDocumentedScheme() {
        val parts = BuildConfig.VERSION_NAME.split(".").map { it.toInt() }
        assertEquals("versionName is major.minor.patch", 3, parts.size)
        assertEquals(parts[0] * 10000 + parts[1] * 100 + parts[2], BuildConfig.VERSION_CODE)
        assertTrue("above the pre-releases (versionCode 1)", BuildConfig.VERSION_CODE > 1)
    }

    @Test
    fun releaseMetadataMatchesTheBuild() {
        val metadata = File(root, "fdroid/metadata/${BuildConfig.APPLICATION_ID}.yml").readText()
        assertTrue(metadata.contains("CurrentVersion: ${BuildConfig.VERSION_NAME}\n"))
        assertTrue(metadata.contains("CurrentVersionCode: ${BuildConfig.VERSION_CODE}\n"))
        assertTrue(metadata.contains("versionName: ${BuildConfig.VERSION_NAME}\n"))
        assertTrue(metadata.contains("versionCode: ${BuildConfig.VERSION_CODE}\n"))
        assertTrue(
            "fastlane changelog for this versionCode",
            File(root, "fastlane/metadata/android/en-US/changelogs/${BuildConfig.VERSION_CODE}.txt").length() in 1..500
        )
        assertTrue(File(root, "CHANGELOG.md").readText().contains("## ${BuildConfig.VERSION_NAME} "))
    }
}
