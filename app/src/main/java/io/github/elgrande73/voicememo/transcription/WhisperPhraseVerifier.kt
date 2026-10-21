package io.github.elgrande73.voicememo.transcription

import android.content.Context
import io.github.elgrande73.voicememo.wakephrase.PhraseVerifier
import io.github.elgrande73.voicememo.wakephrase.SpeechCheckedKeywordDetector
import io.github.elgrande73.voicememo.wakephrase.WakePhraseText

/**
 * The speech model used by the wake-phrase speech check: the bundled Whisper base model (never an imported
 * transcription model, so latency and accuracy stay as documented). One instance per process is shared by monitoring
 * and the Settings phrase test and freed when the last user releases it. Separate from the transcription engine,
 * which loads and unloads its own model.
 */
object SpeechCheckModel {
    private var handle = 0L
    private var users = 0
    private val lock = Any()

    /** A use of the shared model; [close] releases it. */
    class Lease internal constructor() : AutoCloseable {
        private var open = true

        /** Runs [block] with the native handle; calls are serialized (a whisper context is single-threaded). */
        fun <T> use(block: (Long) -> T): T = synchronized(lock) {
            check(open && handle != 0L) { "Speech check model released" }
            block(handle)
        }

        override fun close() = synchronized(lock) {
            if (!open) return
            open = false
            if (--users == 0 && handle != 0L) {
                WhisperNative.free(handle)
                handle = 0L
            }
        }
    }

    /** Loads (or shares) the bundled model; throws with a user-readable message if that is impossible. */
    fun acquire(context: Context): Lease = acquire {
        if (!WhisperNative.load(context)) throw IllegalStateException("The speech engine is not available on this device.")
        WhisperNative.initFromAsset(context.assets, SpeechModelStore.BUNDLED_ASSET_PATH)
    }

    /** Variant for the build machine's tests: [load] returns a native handle (0 = failure). */
    fun acquire(load: () -> Long): Lease = synchronized(lock) {
        if (handle == 0L) {
            handle = try {
                load()
            } catch (e: OutOfMemoryError) {
                0L
            }
            if (handle == 0L) throw IllegalStateException("The speech model could not be loaded (not enough memory?).")
        }
        users++
        Lease()
    }
}

/**
 * [PhraseVerifier] backed by Whisper forced decoding ([WhisperNative.phraseLogProb]): the snippet is encoded with an
 * encoder context reduced to its length, and the decoder's mean token log-probability of the phrase's words (best of a
 * few spellings) is turned into a score with [SpeechCheckedKeywordDetector.scoreFromLogProb].
 */
class WhisperPhraseVerifier(
    private val lease: SpeechCheckModel.Lease,
    phraseText: String,
    private val language: String = "en",
    private val threads: Int = 2
) : PhraseVerifier {

    private val spellings = WakePhraseText.spellings(phraseText).toTypedArray()

    override fun score(samples: FloatArray): Float {
        val audioCtx = (samples.size / SAMPLES_PER_ENCODER_FRAME + AUDIO_CTX_MARGIN).coerceAtMost(FULL_AUDIO_CTX)
        val logProbs = lease.use { h -> WhisperNative.phraseLogProb(h, samples, language, threads, audioCtx, spellings) }
            ?: throw IllegalStateException("Speech check failed")
        return SpeechCheckedKeywordDetector.scoreFromLogProb(logProbs.drop(1).max())
    }

    override fun close() = lease.close()

    companion object {
        private const val SAMPLES_PER_ENCODER_FRAME = 320 // 20 ms at 16 kHz
        private const val AUDIO_CTX_MARGIN = 32
        private const val FULL_AUDIO_CTX = 1500
    }
}
