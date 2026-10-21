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
        val chunks = SyntheticPhrase.generate(amplitude = 9000.0)
        assertTrue(detector.recordTemplate(chunks))
        return detector.getTemplateData()!!
    }

    @Test
    fun enrolledWakePhraseSurvivesRestart() {
        val template = enrolledTemplate()
        WakePhraseStore(filesDir).addSample(template, 800L, -10f)

        // A new store instance simulates an application restart
        val reloaded = WakePhraseStore(filesDir)
        assertTrue(reloaded.hasSamples())
        assertArrayEquals(template, reloaded.loadSampleTemplates().single())

        val detector = TemplateKeywordDetector()
        assertEquals(1, detector.loadTemplates(reloaded.loadSampleTemplates()))
        assertTrue("Custom phrase must not be replaced by the default", detector.hasCustomTemplate())
    }

    @Test
    fun testRecordingAndResultSurviveRestart() {
        val chunks = SyntheticPhrase.generate(amplitude = 12000.0)
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
        assertFalse(store.hasSamples())
        assertTrue(store.loadSamples().isEmpty())
        assertNull(store.loadTestRecording())
        assertNull(store.loadTestResult())
    }

    @Test
    fun corruptTestDataIsIgnoredWithoutDeletingTheEnrolledPhrase() {
        val template = enrolledTemplate()
        val store = WakePhraseStore(filesDir)
        store.addSample(template, 800L, -10f)
        val dir = File(filesDir, WakePhraseStore.DIRECTORY_NAME)
        File(dir, WakePhraseStore.TEST_RECORDING_FILE_NAME).writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        File(dir, WakePhraseStore.TEST_RESULT_FILE_NAME).writeBytes(byteArrayOf(9))

        assertNull(store.loadTestRecording())
        assertNull(store.loadTestResult())
        assertArrayEquals(template, store.loadSampleTemplates().single())
        assertTrue(File(dir, WakePhraseStore.TEST_RECORDING_FILE_NAME).exists())
    }

    @Test
    fun corruptTemplateIsRejectedByDetectorWhichKeepsDefaultPhrase() {
        val store = WakePhraseStore(filesDir)
        store.addSample(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), 800L, -10f)
        val detector = TemplateKeywordDetector()
        assertEquals(0, detector.loadTemplates(store.loadSampleTemplates()))
        assertFalse(detector.hasCustomTemplate())
        assertTrue(detector.hasTemplate())
    }

    @Test
    fun testRecordingInAnUnsupportedFormatIsRejected() {
        val dir = File(filesDir, WakePhraseStore.DIRECTORY_NAME).apply { mkdirs() }
        val target = File(dir, WakePhraseStore.TEST_RECORDING_FILE_NAME)
        for ((rate, channels) in listOf(44100 to 1, 16000 to 2, 8000 to 1)) {
            io.github.elgrande73.voicememo.audio.WavWriter.writeWavFile(target, listOf(ShortArray(1600) { 100 }), rate, channels)
            assertNull("rate $rate channels $channels", WakePhraseStore(filesDir).loadTestRecording())
        }
        io.github.elgrande73.voicememo.audio.WavWriter.writeWavFile(target, listOf(ShortArray(1600) { 100 }), 16000, 1)
        assertEquals(2, WakePhraseStore(filesDir).loadTestRecording()!!.size)
    }

    @Test
    fun deletingEnrolledPhraseKeepsTestRecording() {
        val store = WakePhraseStore(filesDir)
        store.addSample(enrolledTemplate(), 800L, -10f)
        store.saveTestRecording(SyntheticPhrase.generate())
        store.clearSamples()

        assertFalse(store.hasSamples())
        assertNotNull(store.loadTestRecording())
    }
}
