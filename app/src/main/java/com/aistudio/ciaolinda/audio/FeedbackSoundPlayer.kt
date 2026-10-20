package com.aistudio.ciaolinda.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Plays short, synthesized feedback chimes when a memo recording starts or stops.
 * Tones are generated in code (no bundled audio assets) and played through a static AudioTrack.
 *
 * - Start: rising two-note chime (E5 -> A5)
 * - Stop:  falling two-note chime (A5 -> E5)
 */
class FeedbackSoundPlayer {

    enum class Cue { RECORDING_STARTED, RECORDING_STOPPED }

    private val sampleRate = 44100
    private val noteDurationMs = 110
    private val noteGapMs = 30
    private val amplitude = 0.35

    private val startTone: ShortArray by lazy { buildChime(659.25, 880.0) }
    private val stopTone: ShortArray by lazy { buildChime(880.0, 659.25) }

    private var activeTrack: AudioTrack? = null

    /** Total playback length of one chime in milliseconds. */
    val cueDurationMs: Long = (noteDurationMs * 2 + noteGapMs).toLong()

    @Synchronized
    fun play(cue: Cue) {
        val samples = when (cue) {
            Cue.RECORDING_STARTED -> startTone
            Cue.RECORDING_STOPPED -> stopTone
        }
        releaseActiveTrack()
        try {
            val track = buildTrack(samples.size * 2)
            track.write(samples, 0, samples.size)
            track.play()
            activeTrack = track
        } catch (e: Exception) {
            Log.w("FeedbackSoundPlayer", "Unable to play feedback cue $cue", e)
        }
    }

    @Synchronized
    fun release() {
        releaseActiveTrack()
    }

    private fun releaseActiveTrack() {
        activeTrack?.let { track ->
            try {
                track.stop()
            } catch (_: Exception) {}
            try {
                track.release()
            } catch (_: Exception) {}
        }
        activeTrack = null
    }

    private fun buildTrack(bufferSizeBytes: Int): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bufferSizeBytes)
                .build()
        } else {
            AudioTrack(attributes, format, bufferSizeBytes, AudioTrack.MODE_STATIC, 0)
        }
    }

    private fun buildChime(firstHz: Double, secondHz: Double): ShortArray {
        val noteSamples = sampleRate * noteDurationMs / 1000
        val gapSamples = sampleRate * noteGapMs / 1000
        val output = ShortArray(noteSamples * 2 + gapSamples)
        writeNote(output, 0, noteSamples, firstHz)
        writeNote(output, noteSamples + gapSamples, noteSamples, secondHz)
        return output
    }

    private fun writeNote(target: ShortArray, offset: Int, length: Int, frequencyHz: Double) {
        val attackSamples = sampleRate * 5 / 1000
        for (i in 0 until length) {
            val t = i.toDouble() / sampleRate
            // Short linear attack, then exponential-ish decay for a soft "bell" character
            val attack = min(1.0, i.toDouble() / attackSamples)
            val decay = 1.0 - (i.toDouble() / length)
            val envelope = attack * decay * decay
            val fundamental = sin(2.0 * PI * frequencyHz * t)
            val overtone = 0.25 * sin(2.0 * PI * frequencyHz * 2.0 * t)
            val value = (fundamental + overtone) / 1.25 * envelope * amplitude
            target[offset + i] = (value * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
