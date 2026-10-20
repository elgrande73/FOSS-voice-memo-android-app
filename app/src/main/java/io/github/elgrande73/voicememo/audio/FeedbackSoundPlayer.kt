package io.github.elgrande73.voicememo.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import io.github.elgrande73.voicememo.memo.ErrorLogger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Plays short, synthesized feedback chimes. Tones are generated in code at runtime (no bundled or downloaded
 * audio assets, original work released under the project license) and streamed through an AudioTrack
 * on the media volume stream (the system/sonification stream is often muted on devices).
 *
 * - Recording started: rising two-note chime (E5 -> A5)
 * - Recording stopped: falling two-note chime (A5 -> E5)
 * - Memo playback begins: soft, quick rising major third (C6 -> E6)
 * - Memo playback ends: soft, quick falling fourth (C6 -> G5)
 */
class FeedbackSoundPlayer(context: Context) {

    enum class Cue { RECORDING_STARTED, RECORDING_STOPPED, MEMO_BEGIN, MEMO_END }

    private val appContext = context.applicationContext
    private val sampleRate = 44100
    private val noteDurationMs = 110
    private val noteGapMs = 30
    private val trailingSilenceMs = 120 // Lets the device flush the tone fully before the track stops
    private val amplitude = 0.5

    private val startTone: ShortArray by lazy { buildChime(659.25, 880.0) }
    private val stopTone: ShortArray by lazy { buildChime(880.0, 659.25) }
    private val memoBeginTone: ShortArray by lazy { buildChime(1046.5, 1318.5, noteMs = 80, gapMs = 20, level = 0.3) }
    private val memoEndTone: ShortArray by lazy { buildChime(1046.5, 783.99, noteMs = 80, gapMs = 20, level = 0.3) }

    // Single worker so cues never overlap and the caller (audio capture loop) is never blocked
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "VoiceMemo-FeedbackSound").apply { isDaemon = true }
    }

    /** Audible length of one chime in milliseconds. */
    val cueDurationMs: Long = (noteDurationMs * 2 + noteGapMs).toLong()

    /**
     * Queues [cue] for playback. Cues never overlap. [onFinished] is invoked on the player's worker thread once the
     * cue has been played completely, or immediately if it could not be played, so callers never get stuck.
     */
    fun play(cue: Cue, onFinished: (() -> Unit)? = null) {
        try {
            executor.execute {
                try {
                    playBlocking(cue, samplesFor(cue))
                } finally {
                    onFinished?.invoke()
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Player already released (service or screen is shutting down)
            onFinished?.invoke()
        }
    }

    private fun samplesFor(cue: Cue): ShortArray = when (cue) {
        Cue.RECORDING_STARTED -> startTone
        Cue.RECORDING_STOPPED -> stopTone
        Cue.MEMO_BEGIN -> memoBeginTone
        Cue.MEMO_END -> memoEndTone
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

    private fun buildChime(
        firstHz: Double,
        secondHz: Double,
        noteMs: Int = noteDurationMs,
        gapMs: Int = noteGapMs,
        level: Double = amplitude
    ): ShortArray {
        val noteSamples = sampleRate * noteMs / 1000
        val gapSamples = sampleRate * gapMs / 1000
        val tailSamples = sampleRate * trailingSilenceMs / 1000
        val output = ShortArray(noteSamples * 2 + gapSamples + tailSamples)
        writeNote(output, 0, noteSamples, firstHz, level)
        writeNote(output, noteSamples + gapSamples, noteSamples, secondHz, level)
        return output
    }

    private fun writeNote(target: ShortArray, offset: Int, length: Int, frequencyHz: Double, level: Double) {
        val attackSamples = sampleRate * 5 / 1000
        for (i in 0 until length) {
            val t = i.toDouble() / sampleRate
            // Short linear attack, then quadratic decay for a soft "bell" character
            val attack = min(1.0, i.toDouble() / attackSamples)
            val decay = 1.0 - (i.toDouble() / length)
            val envelope = attack * decay * decay
            val fundamental = sin(2.0 * PI * frequencyHz * t)
            val overtone = 0.25 * sin(2.0 * PI * frequencyHz * 2.0 * t)
            val value = (fundamental + overtone) / 1.25 * envelope * level
            target[offset + i] = (value * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
