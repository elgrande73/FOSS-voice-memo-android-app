package io.github.elgrande73.voicememo.transcription

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads a PCM16 WAV file (as written by WavWriter) into 16 kHz mono float samples in -1..1. */
object WavReader {
    const val TARGET_RATE = 16_000

    class Audio(val samples: FloatArray, val sampleRate: Int) {
        val durationSeconds: Double get() = samples.size.toDouble() / sampleRate
    }

    fun read16kMono(file: File): Audio {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") {
            throw IOException("Not a WAV file")
        }
        var channels = 0
        var rate = 0
        var bits = 0
        var dataOffset = -1
        var dataSize = 0
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buffer.getInt(pos + 4)
            if (size < 0) throw IOException("Corrupt WAV chunk")
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    if (buffer.getShort(body).toInt() != 1) throw IOException("Only PCM WAV is supported")
                    channels = buffer.getShort(body + 2).toInt()
                    rate = buffer.getInt(body + 4)
                    bits = buffer.getShort(body + 14).toInt()
                }
                "data" -> {
                    dataOffset = body
                    dataSize = minOf(size, bytes.size - body)
                }
            }
            if (dataOffset >= 0 && channels > 0) break
            pos = body + size + (size and 1)
        }
        if (dataOffset < 0 || channels !in 1..2 || bits != 16 || rate <= 0) throw IOException("Unsupported WAV format")
        val frames = dataSize / (2 * channels)
        val mono = FloatArray(frames) { i ->
            var sum = 0f
            for (c in 0 until channels) sum += buffer.getShort(dataOffset + (i * channels + c) * 2) / 32768f
            sum / channels
        }
        return Audio(if (rate == TARGET_RATE) mono else resample(mono, rate, TARGET_RATE), TARGET_RATE)
    }

    /** Linear-interpolation resampling (only for files not recorded by this app). */
    fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (input.isEmpty()) return input
        val outLength = (input.size.toLong() * to / from).toInt()
        val step = from.toDouble() / to
        return FloatArray(outLength) { i ->
            val x = i * step
            val i0 = x.toInt().coerceAtMost(input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val t = (x - i0).toFloat()
            input[i0] * (1 - t) + input[i1] * t
        }
    }
}
