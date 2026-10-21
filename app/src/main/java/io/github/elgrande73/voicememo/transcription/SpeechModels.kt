package io.github.elgrande73.voicememo.transcription

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Hyper-parameters from the header of a whisper.cpp ggml model file. */
data class GgmlModelInfo(
    val nVocab: Int,
    val nAudioState: Int,
    val nAudioLayer: Int,
    val nTextState: Int,
    val nTextLayer: Int,
    val nMels: Int
) {
    val multilingual: Boolean get() = nVocab >= 51865

    /** Whisper size name derived from the encoder shape. */
    val sizeName: String
        get() = when (nAudioLayer to nAudioState) {
            4 to 384 -> "tiny"
            6 to 512 -> "base"
            12 to 768 -> "small"
            24 to 1024 -> "medium"
            32 to 1280 -> if (nTextLayer == 4) "large-v3-turbo" else "large"
            else -> "custom"
        }

    companion object {
        private const val GGML_MAGIC = 0x67676d6c

        /** Reads and sanity-checks the header; null if the file is not a usable whisper.cpp model. */
        fun read(file: File): GgmlModelInfo? = try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 48) return null
                val header = ByteArray(48)
                raf.readFully(header)
                val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                if (b.getInt(0) != GGML_MAGIC) return null
                val v = IntArray(11) { b.getInt(4 + it * 4) }
                val info = GgmlModelInfo(nVocab = v[0], nAudioState = v[2], nAudioLayer = v[4], nTextState = v[6], nTextLayer = v[8], nMels = v[9])
                val plausible = v[0] in 51864..51866 && v[1] == 1500 && v[2] in 64..4096 && v[3] in 1..64 &&
                    v[4] in 1..64 && v[5] == 448 && v[6] in 64..4096 && v[7] in 1..64 && v[8] in 1..64 && v[9] in setOf(80, 128)
                if (plausible) info else null
            }
        } catch (e: IOException) {
            null
        }
    }
}

/** A speech-to-text model on this device: the downloaded Whisper base model or a file the user imported. */
data class SpeechModel(
    val id: String,
    val displayName: String,
    val file: File,
    val sizeBytes: Long,
    val sha256: String,
    /** True for the model downloaded from Settings ([ModelSource.WHISPER_BASE]). */
    val downloaded: Boolean,
    val info: GgmlModelInfo
) {
    val description: String
        get() = "Whisper ${info.sizeName}, ${if (info.multilingual) "multilingual" else "English only"}, ${sizeBytes / 1_000_000} MB"
}

/** No speech model is installed: transcription needs the model download (Settings) first. */
class SpeechModelMissingException : IOException(MISSING_MESSAGE) {
    companion object {
        const val MISSING_MESSAGE = "No speech model is installed. Download Whisper base in Settings → Speech-to-Text."
    }
}

/**
 * Speech models on the device, in `noBackupFilesDir/speech_models` (app-private, excluded from backups):
 * - `downloaded/`: Whisper base, downloaded on the user's request from Settings ([ModelDownloader]) and verified before
 *   use; it stays until the user removes it (survives restarts and app updates).
 * - `imported/`: further whisper.cpp models the user imported from a file; validated (header, checksum recorded, test
 *   load) before use. A failed import never changes the working model.
 * The app contains no model: without one, transcription reports [SpeechModelMissingException].
 */
class SpeechModelStore(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val directory: File = File(context.noBackupFilesDir, DIRECTORY_NAME)
) {
    private val importedDirectory = File(directory, "imported")
    /** Where [ModelSource.WHISPER_BASE] is downloaded to. */
    val downloadDirectory = File(directory, "downloaded")

    sealed interface ImportResult {
        data class Imported(val model: SpeechModel) : ImportResult
        data class Rejected(val reason: String) : ImportResult
    }

    /** The downloaded Whisper base model, or null if it is not (completely) installed. */
    fun downloadedModel(): SpeechModel? {
        val source = ModelSource.WHISPER_BASE
        val meta = File(downloadDirectory, META_FILE)
        val file = File(downloadDirectory, source.fileName)
        if (!meta.isFile || !file.isFile) return null
        return try {
            val json = JSONObject(meta.readText())
            if (file.length() != json.getLong("size")) return null
            val info = GgmlModelInfo.read(file) ?: return null
            SpeechModel(source.id, source.displayName, file, file.length(), json.getString("sha256"), true, info)
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring unreadable download metadata", e)
            null
        }
    }

    /** Records a model the downloader verified and installed; from now on it can be used. */
    @Synchronized
    fun registerDownload(file: File, sha256: String) {
        File(downloadDirectory, META_FILE).writeText(
            JSONObject().put("id", ModelSource.WHISPER_BASE.id).put("file", file.name).put("size", file.length())
                .put("sha256", sha256).put("sha1", ModelSource.WHISPER_BASE.sha1).put("url", ModelSource.WHISPER_BASE.url)
                .put("downloadedAt", System.currentTimeMillis()).toString()
        )
    }

    /**
     * Removes the downloaded model (and any partial download). Transcripts and recordings are not affected. The caller
     * makes sure no transcription is using it (TranscriptionManager.releaseModel).
     */
    @Synchronized
    fun removeDownloaded(): Boolean {
        val existed = downloadedModel() != null
        File(downloadDirectory, META_FILE).delete()
        downloadDirectory.listFiles()?.forEach { it.delete() }
        return existed
    }

    /** Imported models (metadata files without a matching, intact model file are ignored). */
    fun importedModels(): List<SpeechModel> {
        val files = importedDirectory.listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { meta ->
            try {
                val json = JSONObject(meta.readText())
                val file = File(importedDirectory, json.getString("file"))
                val info = GgmlModelInfo.read(file) ?: return@mapNotNull null
                if (file.length() != json.getLong("size")) return@mapNotNull null
                SpeechModel(json.getString("id"), json.getString("name"), file, file.length(), json.getString("sha256"), false, info)
            } catch (e: Exception) {
                Log.w(TAG, "Ignoring unreadable model metadata ${meta.name}", e)
                null
            }
        }.sortedBy { it.displayName.lowercase() }
    }

    /** Every installed model: the downloaded one first, then imported ones. */
    fun installedModels(): List<SpeechModel> = listOfNotNull(downloadedModel()) + importedModels()

    var activeModelId: String
        get() = prefs.getString(PREF_ACTIVE_MODEL, ModelSource.WHISPER_BASE.id) ?: ModelSource.WHISPER_BASE.id
        set(value) { prefs.edit().putString(PREF_ACTIVE_MODEL, value).apply() }

    /** The selected model, or the first installed one if the selection is missing; null if no model is installed. */
    fun activeModelOrNull(): SpeechModel? {
        val installed = installedModels()
        return installed.firstOrNull { it.id == activeModelId } ?: installed.firstOrNull()
    }

    /** Like [activeModelOrNull], but reports a missing model as [SpeechModelMissingException]. */
    fun activeModel(): SpeechModel = activeModelOrNull() ?: throw SpeechModelMissingException()

    /**
     * Copies [input] to a temporary file, validates it and only then makes it available. [verifyLoad] performs a test
     * load with the speech engine (injected for tests). On any failure the temporary file is deleted.
     */
    @Synchronized
    fun import(input: InputStream, displayName: String, expectedSize: Long?, verifyLoad: (File) -> Boolean): ImportResult {
        importedDirectory.mkdirs()
        if (expectedSize != null && expectedSize > MAX_IMPORT_BYTES) return ImportResult.Rejected("The file is too large for this phone (max ${MAX_IMPORT_BYTES / 1_000_000_000.0} GB).")
        if (expectedSize != null && importedDirectory.usableSpace < expectedSize + FREE_SPACE_MARGIN) {
            return ImportResult.Rejected("Not enough free storage for this model.")
        }
        val temp = File(importedDirectory, "import.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            temp.outputStream().use { out ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_IMPORT_BYTES) return ImportResult.Rejected("The file is too large for this phone.")
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
            }
            val info = GgmlModelInfo.read(temp)
                ?: return ImportResult.Rejected("This is not a whisper.cpp model (ggml .bin file).")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (installedModels().any { it.sha256 == sha }) {
                return ImportResult.Rejected("This model is already installed.")
            }
            if (!verifyLoad(temp)) return ImportResult.Rejected("The model could not be loaded (damaged file, or not enough memory).")
            val id = "imported-${sha.take(16)}"
            val target = File(importedDirectory, "$id.bin")
            if (!temp.renameTo(target)) return ImportResult.Rejected("The model could not be saved.")
            val name = displayName.ifBlank { "Whisper ${info.sizeName}" }
            File(importedDirectory, "$id.json").writeText(
                JSONObject().put("id", id).put("name", name).put("file", target.name).put("size", target.length())
                    .put("sha256", sha).put("importedAt", System.currentTimeMillis()).toString()
            )
            return ImportResult.Imported(SpeechModel(id, name, target, target.length(), sha, false, info))
        } catch (e: IOException) {
            Log.w(TAG, "Model import failed", e)
            return ImportResult.Rejected("The file could not be read.")
        } finally {
            temp.delete()
        }
    }

    /** Removes an imported model. The downloaded model is removed with [removeDownloaded]. */
    @Synchronized
    fun delete(id: String): Boolean {
        val model = importedModels().firstOrNull { it.id == id } ?: return false
        if (activeModelId == id) activeModelId = ModelSource.WHISPER_BASE.id
        File(importedDirectory, "$id.json").delete()
        return model.file.delete()
    }

    companion object {
        private const val TAG = "SpeechModelStore"
        const val DIRECTORY_NAME = "speech_models"
        const val PREF_ACTIVE_MODEL = "transcriptionModelId"
        private const val META_FILE = "model.json"

        const val MAX_IMPORT_BYTES = 1_700_000_000L
        private const val FREE_SPACE_MARGIN = 200_000_000L

        fun sha256(file: File): String = FileInputStream(file).use { sha256(it) }

        fun sha256(stream: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            stream.let { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
