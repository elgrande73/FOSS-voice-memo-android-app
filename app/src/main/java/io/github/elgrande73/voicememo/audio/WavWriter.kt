package io.github.elgrande73.voicememo.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility for saving raw 16-bit PCM audio chunks into a standard RIFF/WAVE (.wav) file.
 */
object WavWriter {

    /**
     * Writes a sequence of 16-bit PCM audio chunks to a WAV file with standard 44-byte RIFF header.
     *
     * @param outputFile The destination .wav file.
     * @param audioChunks List of ShortArray PCM samples.
     * @param sampleRate Sampling rate in Hz (default: 16000).
     * @param channels Channel count (default: 1 - MONO).
     */
    fun writeWavFile(
        outputFile: File,
        audioChunks: List<ShortArray>,
        sampleRate: Int = 16000,
        channels: Int = 1
    ) {
        val totalSamples = audioChunks.sumOf { it.size }
        val totalAudioBytes = totalSamples * 2 // 16-bit = 2 bytes per sample
        val byteRate = sampleRate * channels * 2

        outputFile.parentFile?.mkdirs()

        BufferedOutputStream(FileOutputStream(outputFile)).use { bos ->
            // Write RIFF Header (44 bytes)
            val header = ByteArray(44)
            val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

            // "RIFF" chunk descriptor
            headerBuffer.put("RIFF".toByteArray(Charsets.US_ASCII))
            headerBuffer.putInt(36 + totalAudioBytes) // ChunkSize = 36 + SubChunk2Size
            headerBuffer.put("WAVE".toByteArray(Charsets.US_ASCII))

            // "fmt " sub-chunk
            headerBuffer.put("fmt ".toByteArray(Charsets.US_ASCII))
            headerBuffer.putInt(16) // Subchunk1Size for PCM
            headerBuffer.putShort(1.toShort()) // AudioFormat 1 = PCM
            headerBuffer.putShort(channels.toShort()) // NumChannels
            headerBuffer.putInt(sampleRate) // SampleRate
            headerBuffer.putInt(byteRate) // ByteRate
            headerBuffer.putShort((channels * 2).toShort()) // BlockAlign (channels * bitsPerSample / 8)
            headerBuffer.putShort(16.toShort()) // BitsPerSample

            // "data" sub-chunk
            headerBuffer.put("data".toByteArray(Charsets.US_ASCII))
            headerBuffer.putInt(totalAudioBytes) // Subchunk2Size

            bos.write(header)

            // Write PCM audio data safely
            for (chunk in audioChunks) {
                if (chunk.isEmpty()) continue
                val byteBuffer = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (sample in chunk) {
                    byteBuffer.putShort(sample)
                }
                bos.write(byteBuffer.array(), 0, byteBuffer.position())
            }
            bos.flush()
        }
    }

    /**
     * Writes the WAV file to a temporary file next to [outputFile], verifies its size and header, then renames it into
     * place. An existing [outputFile] is only replaced by a complete, verified file; on failure the temporary file is
     * removed and an [IOException] is thrown.
     */
    fun writeWavFileAtomic(outputFile: File, audioChunks: List<ShortArray>, sampleRate: Int = 16000, channels: Int = 1) {
        outputFile.parentFile?.mkdirs()
        val temp = File(outputFile.parentFile, outputFile.name + ".tmp")
        try {
            writeWavFile(temp, audioChunks, sampleRate, channels)
            val expectedBytes = 44L + audioChunks.sumOf { it.size.toLong() } * 2
            if (temp.length() != expectedBytes || !hasValidHeader(temp)) {
                throw IOException("Written WAV file is incomplete (${temp.length()} of $expectedBytes bytes)")
            }
            if (!temp.renameTo(outputFile)) throw IOException("Could not move ${temp.name} into place")
        } catch (e: IOException) {
            temp.delete()
            throw e
        } catch (e: RuntimeException) {
            temp.delete()
            throw IOException("Writing ${outputFile.name} failed", e)
        }
    }

    private fun hasValidHeader(file: File): Boolean {
        val header = ByteArray(12)
        file.inputStream().use { if (it.read(header) != 12) return false }
        return String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
    }

    /**
     * Updates an existing WAV header with updated file length in case of streaming writes.
     */
    fun updateWavHeader(file: File, sampleRate: Int = 16000, channels: Int = 1) {
        if (!file.exists() || file.length() < 44) return
        val totalAudioBytes = (file.length() - 44).toInt()

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(36 + totalAudioBytes).array())

            raf.seek(40)
            raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalAudioBytes).array())
        }
    }
}

