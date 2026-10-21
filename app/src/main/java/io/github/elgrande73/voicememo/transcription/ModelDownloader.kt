package io.github.elgrande73.voicememo.transcription

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/** A model the user can download from Settings. */
data class ModelSource(
    val id: String,
    val displayName: String,
    /** HTTPS address of the file. */
    val url: String,
    /** SHA-1 published by the model's maintainers; the download is rejected unless it matches. */
    val sha1: String,
    /** Approximate size, for the user and the storage check (the server's length is used when known). */
    val approxBytes: Long,
    val fileName: String
) {
    companion object {
        /**
         * OpenAI Whisper "base" (multilingual, MIT) in whisper.cpp's ggml format, as published by the whisper.cpp project
         * (ggerganov/whisper.cpp on Hugging Face). The SHA-1 is the one listed in whisper.cpp's models/README.md
         * (third_party/whisper.cpp, pinned submodule).
         */
        val WHISPER_BASE = ModelSource(
            id = "downloaded-whisper-base",
            displayName = "Whisper base",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
            sha1 = "465707469ff3a37a2b9b8d8f89f2f99de7299dac",
            approxBytes = 142L * 1024 * 1024,
            fileName = "ggml-base.bin"
        )
    }
}

/** Opens an HTTP(S) connection; replaced in tests. */
fun interface HttpOpener {
    fun open(url: String): HttpURLConnection
}

/**
 * Downloads a [ModelSource] safely:
 * - into `<name>.part` next to the target; an interrupted download continues where it stopped (HTTP Range) when the
 *   server supports it;
 * - with a storage check before starting (size + 50 MB margin) and a hard size limit;
 * - verified against the published SHA-1 and the ggml header (Whisper, multilingual) and test-loaded with the engine
 *   ([verifyLoad]) before it is moved to its final name (an atomic rename), so a partial, damaged or incompatible file
 *   is never used and a valid installed model is never replaced by one that failed;
 * - cancellable at any time ([isCancelled]): the partial file is kept for a later resume.
 */
class ModelDownloader(
    private val opener: HttpOpener = HttpOpener { url -> URL(url).openConnection() as HttpURLConnection },
    private val usableSpace: (File) -> Long = { it.usableSpace },
    /** Tests only: allow plain HTTP from a local test server. */
    private val allowHttp: Boolean = false
) {
    /** Steps after the transfer: checksum and header check, then a test load with the speech engine. */
    enum class Stage { VALIDATING, INITIALIZING }

    sealed interface Result {
        data class Installed(val file: File, val sha256: String, val info: GgmlModelInfo) : Result
        data class Failed(val message: String, val retryable: Boolean) : Result
        data object Cancelled : Result
    }

    fun download(
        source: ModelSource,
        directory: File,
        isCancelled: () -> Boolean,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        onStage: (Stage) -> Unit,
        verifyLoad: (File) -> Boolean
    ): Result {
        directory.mkdirs()
        val target = File(directory, source.fileName)
        val part = File(directory, source.fileName + ".part")
        try {
            var offset = if (part.isFile) part.length() else 0L
            var connection = connect(source.url, offset)
            var code = connection.responseCode
            if (offset > 0 && code == HttpURLConnection.HTTP_OK) {
                offset = 0L // the server ignored the range: start again
            } else if (code == 416) {
                // Range beyond the end: the part is complete or invalid; validate it below or start over
                connection.disconnect()
                if (!part.isFile) return Result.Failed("The download server rejected the request (HTTP 416).", true)
                return finish(source, part, target, onStage, verifyLoad, isCancelled)
            } else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                connection.disconnect()
                return Result.Failed("The download server answered with HTTP $code. Try again later.", true)
            }
            val remaining = connection.contentLengthLong.takeIf { it > 0 }
            val total = remaining?.let { it + offset } ?: source.approxBytes
            if (total > MAX_BYTES) {
                connection.disconnect()
                return Result.Failed("The file on the server is larger than expected; it was not downloaded.", false)
            }
            if (usableSpace(directory) < total - offset + SPACE_MARGIN) {
                connection.disconnect()
                return Result.Failed(
                    "Not enough free storage: about ${(total - offset + SPACE_MARGIN) / 1_000_000} MB are needed. Free some space and try again.",
                    true
                )
            }
            connection.inputStream.use { input ->
                FileOutputStream(part, offset > 0).use { out -> copy(input, out, offset, total, isCancelled, onProgress) }
            }
            // Some HTTP stacks report a dropped connection as a normal end: keep the part and let Retry resume it
            if (remaining != null && part.length() < total) {
                connection.disconnect()
                throw IOException("Connection closed after ${part.length()} of $total bytes")
            }
            connection.disconnect()
            return finish(source, part, target, onStage, verifyLoad, isCancelled)
        } catch (e: CancellationException) {
            return Result.Cancelled
        } catch (e: IOException) {
            return Result.Failed("The download was interrupted (${e.javaClass.simpleName}). Check the connection and tap Retry; it continues where it stopped.", true)
        } catch (e: SecurityException) {
            return Result.Failed("The app is not allowed to use the network.", false)
        }
    }

    private fun connect(url: String, offset: Long): HttpURLConnection = opener.open(url).apply {
        connectTimeout = 20_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        if (this.url.protocol != "https" && !allowHttp) throw IOException("Only HTTPS downloads are allowed")
    }

    private fun copy(input: InputStream, out: FileOutputStream, offset: Long, total: Long, isCancelled: () -> Boolean, onProgress: (Long, Long) -> Unit) {
        val buffer = ByteArray(1 shl 16)
        var done = offset
        var lastReport = 0L
        while (true) {
            if (isCancelled()) throw CancellationException("Download cancelled")
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            done += n
            if (done > MAX_BYTES) throw IOException("File larger than expected")
            if (done - lastReport >= REPORT_EVERY || done == total) {
                onProgress(done, total)
                lastReport = done
            }
        }
        out.fd.sync()
        onProgress(done, total)
    }

    private fun finish(source: ModelSource, part: File, target: File, onStage: (Stage) -> Unit, verifyLoad: (File) -> Boolean, isCancelled: () -> Boolean): Result {
        onStage(Stage.VALIDATING)
        val (sha1, sha256) = digests(part, isCancelled)
        if (!sha1.equals(source.sha1, ignoreCase = true)) {
            part.delete() // damaged or different file: never resume from it
            return Result.Failed("The downloaded file is damaged or not the expected model (checksum mismatch). Tap Retry to download it again.", true)
        }
        val info = GgmlModelInfo.read(part)
        if (info == null || !info.multilingual || info.sizeName != "base") {
            part.delete()
            return Result.Failed("The downloaded file is not the expected Whisper base model.", false)
        }
        onStage(Stage.INITIALIZING)
        if (!verifyLoad(part)) {
            return Result.Failed("The model was downloaded but could not be loaded (not enough memory?). Close other apps and tap Retry.", true)
        }
        if (target.exists() && !target.delete()) return Result.Failed("The previous model file could not be replaced.", true)
        if (!part.renameTo(target)) return Result.Failed("The model could not be saved.", true)
        return Result.Installed(target, sha256, info)
    }

    private fun digests(file: File, isCancelled: () -> Boolean): Pair<String, String> {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                if (isCancelled()) throw CancellationException("Download cancelled")
                val n = input.read(buffer)
                if (n < 0) break
                sha1.update(buffer, 0, n)
                sha256.update(buffer, 0, n)
            }
        }
        fun hex(d: MessageDigest) = d.digest().joinToString("") { "%02x".format(it) }
        return hex(sha1) to hex(sha256)
    }

    companion object {
        const val SPACE_MARGIN = 50_000_000L
        const val MAX_BYTES = 400_000_000L
        private const val REPORT_EVERY = 512L * 1024
    }
}
