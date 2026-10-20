package com.aistudio.ciaolinda.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
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

