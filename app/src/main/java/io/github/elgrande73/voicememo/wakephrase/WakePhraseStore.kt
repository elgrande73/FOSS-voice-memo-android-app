package io.github.elgrande73.voicememo.wakephrase

import android.util.AtomicFile
import android.util.Log
import io.github.elgrande73.voicememo.audio.PcmFrameAssembler
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
 * One enrolled recording of the wake phrase. Only derived features ([templateBytes]) and metadata are stored —
 * never the audio itself.
 *
 * @param id Stable identifier (used to replace or delete this sample).
 * @param phraseDurationMs Length of the detected phrase, or -1 if unknown (migrated from an earlier version).
 * @param peakDbfs Peak level of the recording, or NaN if unknown.
 */
data class WakePhraseSample(
    val id: Long,
    val createdAt: Long,
    val phraseDurationMs: Long,
    val peakDbfs: Float,
    val templateBytes: ByteArray
)

/**
 * Durable, app-private storage for wake-phrase user data (never the cache directory):
 * - up to [MAX_SAMPLES] enrolled samples of the wake phrase (features + metadata, one file each, `samples/`),
 * - the most recent test recording (16 kHz mono WAV) and
 * - its test result.
 *
 * The built-in wake phrase (BuiltInWakePhrase, "Mango Tango") is part of the app and is never written here,
 * so enrolled samples are never replaced by the default. All writes are atomic; a missing or
 * unreadable file is reported as absent without deleting other data.
 *
 * Earlier versions stored a single template in [TEMPLATE_FILE_NAME]; [migrateLegacyTemplate] turns it into the first
 * sample (idempotent, the old file is removed only after the new one has been verified).
 */
class WakePhraseStore(filesDir: File) {

    private val directory = File(filesDir, DIRECTORY_NAME)
    private val testRecordingFile = AtomicFile(File(directory, TEST_RECORDING_FILE_NAME))
    private val testResultFile = AtomicFile(File(directory, TEST_RESULT_FILE_NAME))

    private val samplesDirectory = File(directory, SAMPLES_DIRECTORY_NAME)

    fun hasSamples(): Boolean = loadSamples().isNotEmpty()

    /**
     * All usable samples, oldest first. Unreadable files and samples in a template format the matcher no longer supports
     * (recorded by the very first app version) are skipped and kept on disk; see [unusableSampleCount].
     */
    fun loadSamples(): List<WakePhraseSample> = readAllSamples().filter { isUsable(it) }.take(MAX_SAMPLES)

    /** Samples on disk that cannot be used (unreadable, or an unsupported template format); the user records again. */
    fun unusableSampleCount(): Int {
        val files = sampleFiles()
        return files.size - readAllSamples().count { isUsable(it) }
    }

    private fun sampleFiles(): List<File> =
        samplesDirectory.listFiles { f -> f.name.startsWith(SAMPLE_PREFIX) && f.name.endsWith(SAMPLE_SUFFIX) }?.toList() ?: emptyList()

    private fun readAllSamples(): List<WakePhraseSample> {
        migrateLegacyTemplate()
        return sampleFiles().mapNotNull { readSample(AtomicFile(it)) }.sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    private fun isUsable(sample: WakePhraseSample) = TemplateKeywordDetector.parseFeatures(sample.templateBytes) != null

    /** Templates of all samples, for the matcher. */
    fun loadSampleTemplates(): List<ByteArray> = loadSamples().map { it.templateBytes }

    /**
     * Adds a sample. Returns null (and stores nothing) if [MAX_SAMPLES] samples are already enrolled.
     */
    fun addSample(templateBytes: ByteArray, phraseDurationMs: Long, peakDbfs: Float, now: Long = System.currentTimeMillis()): WakePhraseSample? =
        synchronized(LOCK) {
            val existing = loadSamples()
            if (existing.size >= MAX_SAMPLES) return null
            val id = maxOf(now, (existing.maxOfOrNull { it.id } ?: 0L) + 1)
            WakePhraseSample(id, now, phraseDurationMs, peakDbfs, templateBytes).also { writeSample(it) }
        }

    /** Replaces the sample [id] (keeps its id and position). Returns false if it does not exist. */
    fun replaceSample(id: Long, templateBytes: ByteArray, phraseDurationMs: Long, peakDbfs: Float): Boolean = synchronized(LOCK) {
        val old = loadSamples().firstOrNull { it.id == id } ?: return false
        writeSample(old.copy(phraseDurationMs = phraseDurationMs, peakDbfs = peakDbfs, templateBytes = templateBytes))
        true
    }

    fun deleteSample(id: Long): Boolean = synchronized(LOCK) { sampleFile(id).let { it.baseFile.exists().also { _ -> it.delete() } } }

    /** Explicit user action: removes all enrolled samples (the built-in default wake phrase is used again). */
    fun clearSamples() = synchronized(LOCK) {
        samplesDirectory.listFiles()?.forEach { it.delete() }
        AtomicFile(File(directory, TEMPLATE_FILE_NAME)).delete()
    }

    /**
     * Converts the single template of earlier versions into the first sample. Safe to call any number of times:
     * the old file is deleted only after the sample has been written and read back identically.
     */
    fun migrateLegacyTemplate() = synchronized(LOCK) {
        val legacy = AtomicFile(File(directory, TEMPLATE_FILE_NAME))
        if (!legacy.baseFile.exists()) return@synchronized
        val bytes = readBytes(legacy) ?: return@synchronized // unreadable: leave it untouched
        val target = sampleFile(LEGACY_SAMPLE_ID)
        val existing = readSample(target)
        if (existing == null) {
            writeSample(WakePhraseSample(LEGACY_SAMPLE_ID, legacy.baseFile.lastModified(), -1L, Float.NaN, bytes))
        }
        if (readSample(target)?.templateBytes?.contentEquals(bytes) == true) legacy.delete()
    }

    private fun sampleFile(id: Long) = AtomicFile(File(samplesDirectory, "$SAMPLE_PREFIX$id$SAMPLE_SUFFIX"))

    private fun writeSample(sample: WakePhraseSample) {
        samplesDirectory.mkdirs()
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use {
            it.writeInt(SAMPLE_MAGIC)
            it.writeInt(SAMPLE_FORMAT_VERSION)
            it.writeLong(sample.id)
            it.writeLong(sample.createdAt)
            it.writeLong(sample.phraseDurationMs)
            it.writeFloat(sample.peakDbfs)
            it.writeInt(sample.templateBytes.size)
            it.write(sample.templateBytes)
        }
        writeBytes(sampleFile(sample.id), out.toByteArray())
    }

    private fun readSample(file: AtomicFile): WakePhraseSample? {
        val bytes = readBytes(file) ?: return null
        return try {
            DataInputStream(bytes.inputStream()).use {
                if (it.readInt() != SAMPLE_MAGIC || it.readInt() != SAMPLE_FORMAT_VERSION) return null
                val id = it.readLong()
                val createdAt = it.readLong()
                val duration = it.readLong()
                val peak = it.readFloat()
                val length = it.readInt()
                if (length !in 12..MAX_TEMPLATE_BYTES) return null
                val template = ByteArray(length)
                it.readFully(template)
                WakePhraseSample(id, createdAt, duration, peak, template)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Unreadable wake-phrase sample ${file.baseFile.name}", e)
            null
        }
    }

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
            Log.w(TAG, "Saved test recording is not a 16 kHz mono 16-bit PCM WAV file")
            return null
        }
        val samples = ShortArray(pcm.remaining() / 2)
        pcm.asShortBuffer().get(samples)
        return PcmFrameAssembler.frames(samples, FRAME_SIZE)
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

    /**
     * Returns the PCM payload if [wav] is a RIFF/WAVE file in the detector's format (PCM, mono, 16 kHz, 16-bit);
     * any other sample rate, channel count or encoding is rejected rather than misinterpreted.
     */
    private fun extractPcmData(wav: ByteArray): ByteBuffer? {
        if (wav.size < 12) return null
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" || String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var offset = 12
        var formatValid = false
        while (offset + 8 <= wav.size) {
            val id = String(wav, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            if (size < 0) return null
            if (id == "fmt " && size >= 16 && offset + 24 <= wav.size) {
                val encoding = buffer.getShort(offset + 8).toInt()
                val channels = buffer.getShort(offset + 10).toInt()
                val sampleRate = buffer.getInt(offset + 12)
                val bitsPerSample = buffer.getShort(offset + 22).toInt()
                formatValid = encoding == 1 && channels == 1 && sampleRate == SAMPLE_RATE && bitsPerSample == 16
                if (!formatValid) return null
            }
            if (id == "data") {
                if (!formatValid) return null
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
        /** Single template of earlier versions (migrated to the first sample). */
        const val TEMPLATE_FILE_NAME = "enrolled_template.bin"
        const val SAMPLES_DIRECTORY_NAME = "samples"
        const val SAMPLE_PREFIX = "sample_"
        const val SAMPLE_SUFFIX = ".bin"
        const val MAX_SAMPLES = TemplateKeywordDetector.MAX_REFERENCES
        /** Id of the sample created from the template of an earlier version. */
        const val LEGACY_SAMPLE_ID = 1L

        private const val SAMPLE_MAGIC = 0x564D5753 // 'VMWS'
        private const val SAMPLE_FORMAT_VERSION = 1
        private const val MAX_TEMPLATE_BYTES = 12 + 200 * 17 * 4
        private val LOCK = Any()
        const val TEST_RECORDING_FILE_NAME = "test_recording.wav"
        const val TEST_RESULT_FILE_NAME = "test_result.bin"

        private const val TEST_RESULT_FORMAT_VERSION = 1
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 800 // 50 ms at 16 kHz, the detector's frame size
    }
}
