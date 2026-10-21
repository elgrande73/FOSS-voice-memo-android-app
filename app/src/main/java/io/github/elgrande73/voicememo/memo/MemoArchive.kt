package io.github.elgrande73.voicememo.memo

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.cancellation.CancellationException

/** Result of writing an export. [missing] lists memos whose audio file could not be read (listed in the manifest). */
data class ArchiveReport(val exported: Int, val missing: List<Int>)

/**
 * Writes memos for export without changing them:
 * - ZIP: every WAV is stored byte for byte (ZIP method STORED, no recompression) under `audio/`, plus `manifest.json`
 *   and `manifest.csv` with id, title, timestamps (local and UTC ISO 8601), duration, how the memo was started,
 *   favourite flag, peak level (dBFS), transcription status and text, file name and SHA-256 of the audio.
 * - Single WAV: the original file, copied as is.
 * File names are derived from date and title, made safe for every file system and unique within the archive.
 * Sources are only read, never moved or deleted. [isCancelled] is checked between and during files; on cancellation a
 * [CancellationException] is thrown and the caller discards the partial output.
 */
object MemoArchive {

    const val MANIFEST_VERSION = 1
    private const val BUFFER = 64 * 1024

    fun writeZip(notes: List<VoiceNote>, out: OutputStream, isCancelled: () -> Boolean = { false }, now: Long = System.currentTimeMillis()): ArchiveReport {
        val names = UniqueNames()
        val rows = mutableListOf<ManifestRow>()
        val missing = mutableListOf<Int>()
        ZipOutputStream(out).use { zip ->
            for (note in notes) {
                checkCancelled(isCancelled)
                val file = File(note.audioFilePath)
                val entryName = "audio/" + names.claim(baseName(note), ".wav")
                val digest = try {
                    if (!file.isFile) throw java.io.FileNotFoundException(file.name)
                    // First pass: CRC and size (required up front for STORED entries) and the SHA-256 for the manifest
                    val crc = CRC32()
                    val sha = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    readAll(file, isCancelled) { buffer, n -> crc.update(buffer, 0, n); sha.update(buffer, 0, n); size += n }
                    zip.putNextEntry(ZipEntry(entryName).apply {
                        method = ZipEntry.STORED
                        this.size = size
                        compressedSize = size
                        this.crc = crc.value
                        time = note.timestamp
                    })
                    readAll(file, isCancelled) { buffer, n -> zip.write(buffer, 0, n) }
                    zip.closeEntry()
                    sha.digest().joinToString("") { "%02x".format(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: java.io.IOException) {
                    missing += note.id
                    null
                }
                rows += ManifestRow(note, if (digest != null) entryName else null, digest)
            }
            checkCancelled(isCancelled)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestJson(rows, now).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.csv"))
            zip.write(manifestCsv(rows).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return ArchiveReport(notes.size - missing.size, missing)
    }

    /** Copies the original WAV of [note] to [out]; false if the file cannot be read. */
    fun writeWav(note: VoiceNote, out: OutputStream, isCancelled: () -> Boolean = { false }): Boolean {
        val file = File(note.audioFilePath)
        if (!file.isFile) return false
        out.use { stream -> readAll(file, isCancelled) { buffer, n -> stream.write(buffer, 0, n) } }
        return true
    }

    /** Suggested file name for a ZIP export of [count] memos. */
    fun zipName(count: Int, now: Long = System.currentTimeMillis()): String =
        "VoiceMemo_${count}_memos_${SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(now))}.zip"

    /** "2026-10-21_09-30-00_Shopping list": date first so files sort chronologically. */
    fun baseName(note: VoiceNote): String {
        val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(note.timestamp))
        val title = note.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").replace(Regex("\\s+"), " ").trim().trim('.').take(60)
        return if (title.isEmpty()) date else "${date}_$title"
    }

    private class UniqueNames {
        private val used = mutableSetOf<String>()
        fun claim(base: String, extension: String): String {
            var candidate = base + extension
            var n = 2
            while (!used.add(candidate.lowercase(Locale.ROOT))) candidate = "$base ($n)$extension".also { n++ }
            return candidate
        }
    }

    private class ManifestRow(val note: VoiceNote, val file: String?, val sha256: String?)

    private fun readAll(file: File, isCancelled: () -> Boolean, sink: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(BUFFER)
        FileInputStream(file).use { input ->
            while (true) {
                checkCancelled(isCancelled)
                val n = input.read(buffer)
                if (n < 0) break
                sink(buffer, n)
            }
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw CancellationException("Export cancelled")
    }

    private fun source(note: VoiceNote) = when (MemoSource.of(note.triggerType)) {
        MemoSource.WAKE_PHRASE -> "wake_phrase"
        MemoSource.MANUAL -> "manual"
        MemoSource.SOUND -> "sound"
        MemoSource.UNKNOWN -> "unknown"
    }

    private fun utc(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))
    private fun local(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
    private fun peakDbfs(note: VoiceNote) = String.format(Locale.US, "%.1f", note.peakDb - 90f)

    private fun json(value: String?): String = if (value == null) "null" else buildString {
        append('"')
        for (c in value) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append(String.format(Locale.US, "\\u%04x", c.code))
            else -> append(c)
        }
        append('"')
    }

    private fun manifestJson(rows: List<ManifestRow>, now: Long): String = buildString {
        append("{\n  \"format\": \"voice-memo-export\",\n  \"version\": ").append(MANIFEST_VERSION)
        append(",\n  \"exportedAtUtc\": ").append(json(utc(now)))
        append(",\n  \"count\": ").append(rows.size).append(",\n  \"memos\": [")
        rows.forEachIndexed { i, row ->
            val n = row.note
            append(if (i == 0) "\n" else ",\n").append("    {")
            append("\"id\": ").append(n.id)
            append(", \"title\": ").append(json(n.title))
            append(", \"file\": ").append(json(row.file))
            append(", \"sha256\": ").append(json(row.sha256))
            append(", \"timestamp\": ").append(n.timestamp)
            append(", \"recordedAtUtc\": ").append(json(utc(n.timestamp)))
            append(", \"recordedAtLocal\": ").append(json(local(n.timestamp)))
            append(", \"durationSeconds\": ").append(String.format(Locale.US, "%.2f", n.durationSeconds))
            append(", \"startedBy\": ").append(json(source(n)))
            append(", \"triggerType\": ").append(json(n.triggerType))
            append(", \"favorite\": ").append(n.isFavorite)
            append(", \"peakDbfs\": ").append(peakDbfs(n))
            append(", \"transcriptionStatus\": ").append(json(n.transcriptionStatus))
            append(", \"transcription\": ").append(json(n.transcriptionText))
            if (row.file == null) append(", \"error\": \"audio file missing or unreadable\"")
            append("}")
        }
        append("\n  ]\n}\n")
    }

    private fun csv(value: String?) = "\"" + (value ?: "").replace("\"", "\"\"") + "\""

    private fun manifestCsv(rows: List<ManifestRow>): String = buildString {
        append("id,title,file,sha256,recorded_at_utc,recorded_at_local,duration_seconds,started_by,favorite,peak_dbfs,transcription_status,transcription\n")
        for (row in rows) {
            val n = row.note
            append(n.id).append(',').append(csv(n.title)).append(',').append(csv(row.file)).append(',').append(csv(row.sha256)).append(',')
            append(utc(n.timestamp)).append(',').append(csv(local(n.timestamp))).append(',')
            append(String.format(Locale.US, "%.2f", n.durationSeconds)).append(',').append(source(n)).append(',')
            append(n.isFavorite).append(',').append(peakDbfs(n)).append(',').append(csv(n.transcriptionStatus)).append(',')
            append(csv(n.transcriptionText)).append('\n')
        }
    }
}
