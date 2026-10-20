package io.github.elgrande73.voicememo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import io.github.elgrande73.voicememo.wakephrase.WakePhraseStore
import io.github.elgrande73.voicememo.wakephrase.WakePhraseTestResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WakePhraseStoreTest {

    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = ApplicationProvider.getApplicationContext<Context>().filesDir
        File(filesDir, WakePhraseStore.DIRECTORY_NAME).deleteRecursively()
    }

    private fun enrolledTemplate(): ByteArray {
        val detector = TemplateKeywordDetector()
        val chunks = TemplateKeywordDetector.generateCanonicalCiaoLindaAudio(amplitude = 9000.0)
        assertTrue(detector.recordTemplate(chunks))
        return detector.getTemplateData()!!
    }

    @Test
    fun enrolledWakePhraseSurvivesRestart() {
        val template = enrolledTemplate()
        WakePhraseStore(filesDir).saveTemplate(template)

        // A new store instance simulates an application restart
        val reloaded = WakePhraseStore(filesDir)
        assertTrue(reloaded.hasTemplate())
        assertArrayEquals(template, reloaded.loadTemplate())

        val detector = TemplateKeywordDetector()
        assertTrue(detector.loadTemplate(reloaded.loadTemplate()!!))
        assertTrue("Custom phrase must not be replaced by the default", detector.hasCustomTemplate())
    }

    @Test
    fun testRecordingAndResultSurviveRestart() {
        val chunks = TemplateKeywordDetector.generateCanonicalCiaoLindaAudio(amplitude = 12000.0)
        WakePhraseStore(filesDir).apply {
            saveTestRecording(chunks)
            saveTestResult(WakePhraseTestResult(0.73f, 1_700_000_000_000L))
        }

        val reloaded = WakePhraseStore(filesDir)
        val loadedChunks = reloaded.loadTestRecording()
        assertNotNull(loadedChunks)
        assertArrayEquals(chunks.flatMap { it.toList() }.toShortArray(), loadedChunks!!.flatMap { it.toList() }.toShortArray())
        assertEquals(WakePhraseTestResult(0.73f, 1_700_000_000_000L), reloaded.loadTestResult())
    }

    @Test
    fun freshInstallationHasNoStoredWakePhraseData() {
        val store = WakePhraseStore(filesDir)
        assertFalse(store.hasTemplate())
        assertNull(store.loadTemplate())
        assertNull(store.loadTestRecording())
        assertNull(store.loadTestResult())
    }

    @Test
    fun corruptTestDataIsIgnoredWithoutDeletingTheEnrolledPhrase() {
        val template = enrolledTemplate()
        val store = WakePhraseStore(filesDir)
        store.saveTemplate(template)
        val dir = File(filesDir, WakePhraseStore.DIRECTORY_NAME)
        File(dir, WakePhraseStore.TEST_RECORDING_FILE_NAME).writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        File(dir, WakePhraseStore.TEST_RESULT_FILE_NAME).writeBytes(byteArrayOf(9))

        assertNull(store.loadTestRecording())
        assertNull(store.loadTestResult())
        assertArrayEquals(template, store.loadTemplate())
        assertTrue(File(dir, WakePhraseStore.TEST_RECORDING_FILE_NAME).exists())
    }

    @Test
    fun corruptTemplateIsRejectedByDetectorWhichKeepsDefaultPhrase() {
        val store = WakePhraseStore(filesDir)
        store.saveTemplate(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12))
        val detector = TemplateKeywordDetector()
        assertFalse(detector.loadTemplate(store.loadTemplate()!!))
        assertFalse(detector.hasCustomTemplate())
        assertTrue(detector.hasTemplate())
    }

    @Test
    fun deletingEnrolledPhraseKeepsTestRecording() {
        val store = WakePhraseStore(filesDir)
        store.saveTemplate(enrolledTemplate())
        store.saveTestRecording(TemplateKeywordDetector.generateCanonicalCiaoLindaAudio())
        store.deleteTemplate()

        assertFalse(store.hasTemplate())
        assertNotNull(store.loadTestRecording())
    }
}
