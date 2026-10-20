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

/** A speech-to-text model available on this device: the bundled one (an APK asset) or an imported file. */
data class SpeechModel(
    val id: String,
    val displayName: String,
    /** Path inside the APK's assets for the bundled model, otherwise null. */
    val assetPath: String?,
    /** Model file for imported models, otherwise null. */
    val file: File?,
    val sizeBytes: Long,
    val sha256: String,
    val bundled: Boolean,
    val info: GgmlModelInfo
) {
    val description: String
        get() = "Whisper ${info.sizeName}, ${if (info.multilingual) "multilingual" else "English only"}, ${sizeBytes / 1_000_000} MB"
}

/**
 * Speech models on the device. The default model ships uncompressed inside the APK and is read from there directly
 * (no second copy; its integrity is covered by the APK signature, and its SHA-256 is checked by a unit test). Further
 * whisper.cpp models can be imported from a file the user selects;
 * they are validated (header, checksum recorded, test load) before they can be used, and a failed import never
 * changes the working model. Nothing is downloaded by the app and models are excluded from backups.
 */
/** InputStream.readNBytes is API 33+; this works on all supported versions. */
private fun InputStream.readNBytesCompat(n: Int): ByteArray {
    val out = ByteArray(n)
    var done = 0
    while (done < n) {
        val r = read(out, done, n - done)
        if (r < 0) break
        done += r
    }
    return out.copyOf(done)
}

class SpeechModelStore(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val directory: File = File(context.noBackupFilesDir, DIRECTORY_NAME)
) {
    private val importedDirectory = File(directory, "imported")

    sealed interface ImportResult {
        data class Imported(val model: SpeechModel) : ImportResult
        data class Rejected(val reason: String) : ImportResult
    }

    private var bundled: SpeechModel? = null

    /** The bundled model (read from the APK assets). */
    @Synchronized
    fun bundledModel(): SpeechModel {
        bundled?.let { return it }
        val header = File.createTempFile("model-header", ".bin", context.cacheDir)
        try {
            context.assets.open(BUNDLED_ASSET_PATH).use { input -> header.writeBytes(input.readNBytesCompat(64)) }
            val info = GgmlModelInfo.read(header) ?: throw IOException("Bundled speech model is unreadable")
            return SpeechModel(BUNDLED_ID, BUNDLED_NAME, BUNDLED_ASSET_PATH, null, BUNDLED_SIZE, BUNDLED_SHA256, bundled = true, info = info)
                .also { bundled = it }
        } finally {
            header.delete()
        }
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
                SpeechModel(json.getString("id"), json.getString("name"), null, file, file.length(), json.getString("sha256"), false, info)
            } catch (e: Exception) {
                Log.w(TAG, "Ignoring unreadable model metadata ${meta.name}", e)
                null
            }
        }.sortedBy { it.displayName.lowercase() }
    }

    var activeModelId: String
        get() = prefs.getString(PREF_ACTIVE_MODEL, BUNDLED_ID) ?: BUNDLED_ID
        set(value) { prefs.edit().putString(PREF_ACTIVE_MODEL, value).apply() }

    /** The selected model, or the bundled one if the selection is missing or no longer valid. */
    fun activeModel(): SpeechModel {
        val id = activeModelId
        if (id != BUNDLED_ID) importedModels().firstOrNull { it.id == id }?.let { return it }
        return bundledModel()
    }

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
            if (sha == BUNDLED_SHA256 || importedModels().any { it.sha256 == sha }) {
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
            return ImportResult.Imported(SpeechModel(id, name, null, target, target.length(), sha, false, info))
        } catch (e: IOException) {
            Log.w(TAG, "Model import failed", e)
            return ImportResult.Rejected("The file could not be read.")
        } finally {
            temp.delete()
        }
    }

    /** Removes an imported model; the bundled one cannot be removed. Falls back to the bundled model if it was active. */
    @Synchronized
    fun delete(id: String): Boolean {
        if (id == BUNDLED_ID) return false
        val model = importedModels().firstOrNull { it.id == id } ?: return false
        if (activeModelId == id) activeModelId = BUNDLED_ID
        File(importedDirectory, "$id.json").delete()
        return model.file?.delete() ?: false
    }

    companion object {
        private const val TAG = "SpeechModelStore"
        const val DIRECTORY_NAME = "speech_models"
        const val PREF_ACTIVE_MODEL = "transcriptionModelId"

        const val BUNDLED_ID = "bundled-whisper-base-q5_0"
        const val BUNDLED_NAME = "Whisper base (included)"
        const val BUNDLED_ASSET_PATH = "models/ggml-base-q5_0.bin"
        /** See scripts/whisper-model/README.md for how this file is reproduced. */
        const val BUNDLED_SHA256 = "1dfdc5f732fa9be0107f46803c6b25c02b625c80b72a89541f23568bec00bea5"
        const val BUNDLED_SIZE = 55_295_433L

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
