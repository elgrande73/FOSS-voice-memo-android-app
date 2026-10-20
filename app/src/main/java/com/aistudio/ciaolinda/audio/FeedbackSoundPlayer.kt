package com.aistudio.ciaolinda.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.aistudio.ciaolinda.memo.ErrorLogger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Plays short, synthesized feedback chimes when a memo recording starts or stops.
 * Tones are generated in code (no bundled audio assets) and streamed through an AudioTrack
 * on the media volume stream (the system/sonification stream is often muted on devices).
 *
 * - Start: rising two-note chime (E5 -> A5)
 * - Stop:  falling two-note chime (A5 -> E5)
 */
class FeedbackSoundPlayer(context: Context) {

    enum class Cue { RECORDING_STARTED, RECORDING_STOPPED }

    private val appContext = context.applicationContext
    private val sampleRate = 44100
    private val noteDurationMs = 110
    private val noteGapMs = 30
    private val trailingSilenceMs = 120 // Lets the device flush the tone fully before the track stops
    private val amplitude = 0.5

    private val startTone: ShortArray by lazy { buildChime(659.25, 880.0) }
    private val stopTone: ShortArray by lazy { buildChime(880.0, 659.25) }

    // Single worker so cues never overlap and the caller (audio capture loop) is never blocked
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CiaoLinda-FeedbackSound").apply { isDaemon = true }
    }

    /** Audible length of one chime in milliseconds. */
    val cueDurationMs: Long = (noteDurationMs * 2 + noteGapMs).toLong()

    fun play(cue: Cue) {
        val samples = when (cue) {
            Cue.RECORDING_STARTED -> startTone
            Cue.RECORDING_STOPPED -> stopTone
        }
        try {
            executor.execute { playBlocking(cue, samples) }
        } catch (_: Exception) {
            // Executor already shut down; service is stopping
        }
    }

    fun release() {
        executor.shutdown()
    }

    private fun playBlocking(cue: Cue, samples: ShortArray) {
        var track: AudioTrack? = null
        try {
            val minBufferBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)
            track = buildTrack(minBufferBytes)
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                ErrorLogger.log(appContext, "FeedbackSound", "AudioTrack failed to initialize for cue $cue")
                return
            }
            track.play()
            var offset = 0
            while (offset < samples.size) {
                val written = track.write(samples, offset, samples.size - offset)
                if (written <= 0) {
                    ErrorLogger.log(appContext, "FeedbackSound", "AudioTrack write failed ($written) for cue $cue")
                    break
                }
                offset += written
            }
            // Blocking stream writes return before the last buffer is audible; wait for it to drain
            val drainMs = minBufferBytes / 2 * 1000L / sampleRate + trailingSilenceMs
            Thread.sleep(drainMs)
            track.stop()
        } catch (e: Exception) {
            ErrorLogger.log(appContext, "FeedbackSound", "Unable to play feedback cue $cue", e)
        } finally {
            try {
                track?.release()
            } catch (_: Exception) {}
        }
    }

    private fun buildTrack(bufferSizeBytes: Int): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
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
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSizeBytes)
                .build()
        } else {
            AudioTrack(attributes, format, bufferSizeBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
        }
    }

    private fun buildChime(firstHz: Double, secondHz: Double): ShortArray {
        val noteSamples = sampleRate * noteDurationMs / 1000
        val gapSamples = sampleRate * noteGapMs / 1000
        val tailSamples = sampleRate * trailingSilenceMs / 1000
        val output = ShortArray(noteSamples * 2 + gapSamples + tailSamples)
        writeNote(output, 0, noteSamples, firstHz)
        writeNote(output, noteSamples + gapSamples, noteSamples, secondHz)
        return output
    }

    private fun writeNote(target: ShortArray, offset: Int, length: Int, frequencyHz: Double) {
        val attackSamples = sampleRate * 5 / 1000
        for (i in 0 until length) {
            val t = i.toDouble() / sampleRate
            // Short linear attack, then quadratic decay for a soft "bell" character
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
