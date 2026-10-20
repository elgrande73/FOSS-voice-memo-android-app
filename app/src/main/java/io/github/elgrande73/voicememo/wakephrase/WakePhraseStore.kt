package io.github.elgrande73.voicememo.wakephrase

import android.util.AtomicFile
import android.util.Log
import io.github.elgrande73.voicememo.audio.WavWriter
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Last evaluation of the "Test Phrase" workflow.
 *
 * @param confidence Best match confidence (0.0..1.0) observed in the test recording.
 * @param timestamp Epoch millis when the test was performed.
 */
data class WakePhraseTestResult(val confidence: Float, val timestamp: Long)

/**
 * Durable, app-private storage for wake-phrase user data (never the cache directory):
 * - the enrolled (custom) wake-phrase template recorded by the user,
 * - the most recent test recording (16 kHz mono WAV) and
 * - its test result.
 *
 * The built-in default wake phrase ("Ciao Linda") is part of the detector and is never written here,
 * so an enrolled template is never replaced by the default. All writes are atomic; a missing or
 * unreadable file is reported as absent without deleting other data.
 */
class WakePhraseStore(filesDir: File) {

    private val directory = File(filesDir, DIRECTORY_NAME)
    private val templateFile = AtomicFile(File(directory, TEMPLATE_FILE_NAME))
    private val testRecordingFile = AtomicFile(File(directory, TEST_RECORDING_FILE_NAME))
    private val testResultFile = AtomicFile(File(directory, TEST_RESULT_FILE_NAME))

    fun hasTemplate(): Boolean = templateFile.baseFile.length() > 0

    fun loadTemplate(): ByteArray? = readBytes(templateFile)

    fun saveTemplate(bytes: ByteArray) = writeBytes(templateFile, bytes)

    fun deleteTemplate() = templateFile.delete()

    fun saveTestRecording(chunks: List<ShortArray>) {
        directory.mkdirs()
        val tmp = File(directory, "$TEST_RECORDING_FILE_NAME.tmp")
        WavWriter.writeWavFile(tmp, chunks, SAMPLE_RATE, 1)
        writeBytes(testRecordingFile, tmp.readBytes())
        tmp.delete()
    }

    /** Returns the saved test recording split into 50 ms frames, or null if absent or unreadable. */
    fun loadTestRecording(): List<ShortArray>? {
        val bytes = readBytes(testRecordingFile) ?: return null
        val pcm = extractPcmData(bytes) ?: run {
            Log.w(TAG, "Saved test recording is not a valid WAV file")
            return null
        }
        val samples = ShortArray(pcm.remaining() / 2)
        pcm.asShortBuffer().get(samples)
        return samples.toList().chunked(FRAME_SIZE).map { it.toShortArray() }
    }

    fun saveTestResult(result: WakePhraseTestResult) {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeInt(TEST_RESULT_FORMAT_VERSION)
            it.writeFloat(result.confidence)
            it.writeLong(result.timestamp)
        }
        writeBytes(testResultFile, out.toByteArray())
    }

    fun loadTestResult(): WakePhraseTestResult? {
        val bytes = readBytes(testResultFile) ?: return null
        return try {
            DataInputStream(bytes.inputStream()).use {
                if (it.readInt() != TEST_RESULT_FORMAT_VERSION) return null
                val confidence = it.readFloat()
                val timestamp = it.readLong()
                if (confidence.isNaN() || confidence !in 0f..1f) null else WakePhraseTestResult(confidence, timestamp)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Saved test result is unreadable", e)
            null
        }
    }

    private fun readBytes(file: AtomicFile): ByteArray? {
        if (!file.baseFile.exists()) return null
        return try {
            file.readFully().takeIf { it.isNotEmpty() }
        } catch (e: IOException) {
            Log.w(TAG, "Unable to read ${file.baseFile.name}", e)
            null
        }
    }

    private fun writeBytes(file: AtomicFile, bytes: ByteArray) {
        directory.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    private fun extractPcmData(wav: ByteArray): ByteBuffer? {
        if (wav.size < 12) return null
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" || String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var offset = 12
        while (offset + 8 <= wav.size) {
            val id = String(wav, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            if (size < 0) return null
            if (id == "data") {
                val length = minOf(size, wav.size - offset - 8)
                return ByteBuffer.wrap(wav, offset + 8, length).slice().order(ByteOrder.LITTLE_ENDIAN)
            }
            offset += 8 + size + (size and 1)
        }
        return null
    }

    companion object {
        private const val TAG = "WakePhraseStore"

        /** Persisted locations inside the app's private files directory. Never rename. */
        const val DIRECTORY_NAME = "wake_phrase"
        const val TEMPLATE_FILE_NAME = "enrolled_template.bin"
        const val TEST_RECORDING_FILE_NAME = "test_recording.wav"
        const val TEST_RESULT_FILE_NAME = "test_result.bin"

        private const val TEST_RESULT_FORMAT_VERSION = 1
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 800 // 50 ms at 16 kHz, the detector's frame size
    }
}
