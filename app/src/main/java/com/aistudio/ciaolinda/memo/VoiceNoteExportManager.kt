package com.aistudio.ciaolinda.memo

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Result data class returned by export operations.
 */
data class ExportResult(
    val uri: Uri,
    val file: File,
    val mimeType: String,
    val noteCount: Int = 1
)

/**
 * Manager for exporting individual or batch voice notes to standard WAV, CSV, JSON, and ZIP packages.
 */
class VoiceNoteExportManager(private val context: Context) {

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val readableDateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Prepares a single VoiceNote for sharing via FileProvider.
     */
    suspend fun prepareSingleNoteForSharing(note: VoiceNote): ExportResult? = withContext(Dispatchers.IO) {
        val originalFile = File(note.audioFilePath)
        if (!originalFile.exists()) return@withContext null

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val cleanTitle = note.title.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val exportFilename = "${dateFormatter.format(Date(note.timestamp))}_${cleanTitle}.wav"
        val destFile = File(exportDir, exportFilename)

        originalFile.copyTo(destFile, overwrite = true)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            destFile
        )

        ExportResult(
            uri = uri,
            file = destFile,
            mimeType = "audio/wav",
            noteCount = 1
        )
    }

    /**
     * Generates CSV formatted text content for a list of Voice Notes.
     */
    fun generateCsvContent(notes: List<VoiceNote>): String {
        val csvHeader = "ID,Timestamp,Date_Time,Title,Duration_Sec,Peak_dB,Avg_dB,Trigger_Type,Transcription_Status,Transcription_Text\n"
        return buildString {
            append(csvHeader)
            for (note in notes) {
                append(note.id).append(",")
                append(note.timestamp).append(",")
                append("\"").append(readableDateFormatter.format(Date(note.timestamp))).append("\",")
                append("\"").append(note.title.replace("\"", "\"\"")).append("\",")
                append(String.format(Locale.US, "%.2f", note.durationSeconds)).append(",")
                append(String.format(Locale.US, "%.1f", note.peakDb)).append(",")
                append(String.format(Locale.US, "%.1f", note.avgDb)).append(",")
                append("\"").append(note.triggerType).append("\",")
                append("\"").append(note.transcriptionStatus).append("\",")
                append("\"").append((note.transcriptionText ?: "").replace("\"", "\"\"")).append("\"\n")
            }
        }
    }

    /**
     * Generates JSON formatted text content for a list of Voice Notes.
     */
    fun generateJsonContent(notes: List<VoiceNote>): String {
        val jsonBuilder = StringBuilder("[\n")
        notes.forEachIndexed { index, note ->
            jsonBuilder.append("  {\n")
            jsonBuilder.append("    \"id\": ").append(note.id).append(",\n")
            jsonBuilder.append("    \"title\": \"").append(escapeJson(note.title)).append("\",\n")
            jsonBuilder.append("    \"timestamp\": ").append(note.timestamp).append(",\n")
            jsonBuilder.append("    \"dateTime\": \"").append(readableDateFormatter.format(Date(note.timestamp))).append("\",\n")
            jsonBuilder.append("    \"durationSeconds\": ").append(note.durationSeconds).append(",\n")
            jsonBuilder.append("    \"peakDb\": ").append(note.peakDb).append(",\n")
            jsonBuilder.append("    \"triggerType\": \"").append(note.triggerType).append("\",\n")
            jsonBuilder.append("    \"transcription\": ").append(note.transcriptionText?.let { "\"${escapeJson(it)}\"" } ?: "null").append("\n")
            jsonBuilder.append("  }").append(if (index < notes.size - 1) ",\n" else "\n")
        }
        jsonBuilder.append("]\n")
        return jsonBuilder.toString()
    }

    /**
     * Batch exports a collection of Voice Notes into a single ZIP archive containing audio and metadata.
     */
    suspend fun exportNotesAsZip(notes: List<VoiceNote>): ExportResult? = withContext(Dispatchers.IO) {
        if (notes.isEmpty()) return@withContext null

        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val timestampStr = dateFormatter.format(Date())
        val zipFile = File(exportDir, "CiaoLinda_VoiceNotes_$timestampStr.zip")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            // 1. Write CSV Metadata
            val csvEntry = ZipEntry("notes_metadata.csv")
            zos.putNextEntry(csvEntry)
            zos.write(generateCsvContent(notes).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 2. Write JSON Metadata
            val jsonEntry = ZipEntry("notes_metadata.json")
            zos.putNextEntry(jsonEntry)
            zos.write(generateJsonContent(notes).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 3. Write individual WAV audio files
            val buffer = ByteArray(8192)
            for (note in notes) {
                val audioFile = File(note.audioFilePath)
                if (audioFile.exists() && audioFile.length() > 0) {
                    val safeName = "audio/${dateFormatter.format(Date(note.timestamp))}_note_${note.id}.wav"
                    val audioEntry = ZipEntry(safeName)
                    zos.putNextEntry(audioEntry)
                    FileInputStream(audioFile).use { fis ->
                        var read: Int
                        while (fis.read(buffer).also { read = it } != -1) {
                            zos.write(buffer, 0, read)
                        }
                    }
                    zos.closeEntry()
                }
            }
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            zipFile
        )

        ExportResult(
            uri = uri,
            file = zipFile,
            mimeType = "application/zip",
            noteCount = notes.size
        )
    }

    /**
     * Creates an Android Share Intent for an ExportResult.
     */
    fun createShareIntent(exportResult: ExportResult, title: String = "Share Voice Notes"): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = exportResult.mimeType
            putExtra(Intent.EXTRA_STREAM, exportResult.uri)
            putExtra(Intent.EXTRA_SUBJECT, "Ciao Linda Voice Notes")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
