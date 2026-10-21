package io.github.elgrande73.voicememo.wakephrase

import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Wake-word detection backends. [id] is persisted (PreferenceKeys.WAKE_WORD_MODEL): never change it. Absent means
 * [STANDARD], so existing installations keep their behaviour.
 */
enum class WakeWordModel(val id: String, val label: String, val summary: String) {
    STANDARD(
        "standard", "Standard (template matching)",
        "Lightweight and always available. Compares the sound of speech with the built-in phrase or your recorded " +
            "samples. Works best with samples of your own voice."
    ),
    SPEECH_CHECK(
        "speech_check", "Speech-checked (on-device speech model)",
        "Every short utterance is checked by the bundled offline speech model (Whisper base) for the exact words of " +
            "the phrase, so it does not depend on recorded samples. Uses about 250 MB more memory while monitoring, more " +
            "battery during conversation, and decides about a second after you finish the phrase. Say the phrase with " +
            "a short pause before and after it."
    );

    companion object {
        val DEFAULT = STANDARD
        fun fromId(id: String?): WakeWordModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** What monitoring actually uses (the requested model may have fallen back). */
data class WakeWordStatus(val requested: WakeWordModel, val active: WakeWordModel, val message: String? = null) {
    val isFallback: Boolean get() = requested != active
}

/**
 * Scores how well a short audio snippet (16 kHz mono, -1..1) matches the wake phrase. Implementations may be slow
 * (hundreds of milliseconds) and are always called from one worker thread.
 */
interface PhraseVerifier : Closeable {
    /** Similarity score 0..1 (see [SpeechCheckedKeywordDetector] for its meaning); throws if the check failed. */
    fun score(samples: FloatArray): Float
}

/** Validation of the phrase text required by [WakeWordModel.SPEECH_CHECK]. */
object WakePhraseText {
    const val MAX_WORDS = 4
    const val MAX_LETTERS = 40
    const val MIN_LETTERS = 4

    /** Null if [text] can be checked by the speech model, otherwise a message for the user. */
    fun problem(text: String?): String? {
        val words = normalizeWords(text.orEmpty())
        val letters = words.sumOf { it.length }
        return when {
            words.isEmpty() -> "The speech check needs the words of your recorded phrase. Type them in, or use the Standard model."
            letters < MIN_LETTERS -> "The phrase is too short to be checked reliably. Use at least two syllables, e.g. \"Mango Tango\"."
            words.size > MAX_WORDS || letters > MAX_LETTERS -> "Use a phrase of at most $MAX_WORDS words for the speech check."
            else -> null
        }
    }

    /** Lower-case words without accents and punctuation. */
    fun normalizeWords(text: String): List<String> {
        val decomposed = java.text.Normalizer.normalize(text.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return decomposed.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    }

    /** Spellings the speech model is asked about (case and punctuation variants of the same words). */
    fun spellings(text: String): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val title = words.joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
        val sentence = words.joinToString(" ").lowercase().replaceFirstChar { it.titlecase() }
        val comma = words.joinToString(", ") { w -> w.replaceFirstChar { it.titlecase() } }
        return listOf(title, sentence, words.joinToString(" ").lowercase(), comma).distinct().map { " $it" }
    }
}

/**
 * Wake-phrase detection checked by a speech model ([WakeWordModel.SPEECH_CHECK]).
 *
 * Every frame still reaches the template matcher [template] (front end, noise floor, diagnostics stay identical), but
 * the decision comes from [verifier]: speech segments of phrase length (0.35–2.5 s, ended by at least 250 ms below the
 * speech level) are cut out and scored; a score ≥ the match confidence threshold is a match. The result is reported on
 * the first frame after the check finished, with [KeywordMatchResult.windowFrames] covering the segment and the time
 * the check took, so the stop phrase and everything after it is what gets removed when it stops a memo.
 *
 * With [synchronous] the check runs inside [processFrame] (Settings phrase test, tests); otherwise on a worker thread,
 * so the audio thread never waits. [resetWindow]/[resetStream] discard checks still running.
 */
class SpeechCheckedKeywordDetector(
    val template: TemplateKeywordDetector,
    private val verifier: PhraseVerifier,
    private val synchronous: Boolean = false
) : KeywordDetector, Closeable {

    private class Result(val score: Float, val startFrame: Long, val generation: Int)

    private var threshold: Float = 0.5f
    private val generation = AtomicInteger(0)
    private val pending = AtomicReference<Result?>(null)
    private val inFlight = AtomicInteger(0)
    private val worker: ExecutorService? = if (synchronous) null else Executors.newSingleThreadExecutor { r ->
        Thread(r, "WakePhraseSpeechCheck").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }

    private val ring = arrayOfNulls<ShortArray>(RING_FRAMES)
    private var frameIndex = 0L
    private var inSpeech = false
    private var segmentStart = 0L
    private var lastSpeech = 0L

    /** Number of checks run and how long they took in total (diagnostics and measurements). */
    @Volatile var checks = 0; private set
    @Volatile var checkNanos = 0L; private set
    @Volatile var failures = 0; private set

    /** Maximum time a decision can lag behind the end of the phrase (used to size the pre-trigger buffer). */
    override val decisionLatencyMs: Long get() = if (synchronous) 0L else MAX_DECISION_LATENCY_MS

    @Synchronized
    override fun setMatchConfidenceThreshold(threshold: Float) {
        this.threshold = threshold.coerceIn(0f, 1f)
        template.setMatchConfidenceThreshold(threshold)
    }

    override fun loadTemplate(templateBytes: ByteArray): Boolean = template.loadTemplate(templateBytes)
    override fun loadTemplates(templates: List<ByteArray>): Int = template.loadTemplates(templates)
    override fun hasTemplate(): Boolean = true
    override fun hasCustomTemplate(): Boolean = template.hasCustomTemplate()
    override fun clearTemplate() = template.clearTemplate()

    @Synchronized
    override fun resetWindow() {
        generation.incrementAndGet()
        pending.set(null)
        inSpeech = false
        template.resetWindow()
    }

    @Synchronized
    override fun resetStream() {
        resetWindow()
        template.resetStream()
    }

    @Synchronized
    override fun processFrame(frame: ShortArray, rms: Float, db: Float): KeywordMatchResult {
        template.processFrame(frame, rms, db) // keeps the front end, noise floor and diagnostics running
        ring[(frameIndex % RING_FRAMES).toInt()] = frame.copyOf()
        segment(frame)
        val result = pending.getAndSet(null)
        frameIndex++
        if (result == null || result.generation != generation.get()) return KeywordMatchResult(false, 0f, 1f)
        val window = (frameIndex - result.startFrame).toInt().coerceIn(1, MAX_WINDOW_FRAMES)
        return KeywordMatchResult(result.score > 0f && result.score >= threshold, result.score, 1f - result.score, window)
    }

    private fun segment(frame: ShortArray) {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        val dbfs = (20 * log10(maxOf(sqrt(sum / frame.size) / 32768.0, 1e-9))).toFloat()
        val speechLevel = maxOf((template.noiseFloorDbfs ?: -70f) + SPEECH_ABOVE_NOISE_DB, MIN_SPEECH_DBFS)
        if (dbfs >= speechLevel) {
            if (!inSpeech) {
                inSpeech = true
                segmentStart = frameIndex
            }
            lastSpeech = frameIndex
        } else if (inSpeech && frameIndex - lastSpeech >= END_PAUSE_FRAMES) {
            inSpeech = false
            val length = lastSpeech - segmentStart + 1
            if (length in MIN_SEGMENT_FRAMES..MAX_SEGMENT_FRAMES) submit(segmentStart - PAD_FRAMES, lastSpeech + PAD_FRAMES)
        }
    }

    private fun submit(first: Long, last: Long) {
        val from = maxOf(first, frameIndex - RING_FRAMES + 1, 0L)
        val to = minOf(last, frameIndex)
        val samples = FloatArray(((to - from + 1) * FRAME_SAMPLES).toInt())
        var offset = 0
        for (i in from..to) {
            val frame = ring[(i % RING_FRAMES).toInt()] ?: ShortArray(FRAME_SAMPLES)
            for (s in frame) samples[offset++] = s / 32768f
        }
        val gen = generation.get()
        val task = Runnable {
            try {
                val start = System.nanoTime()
                val score = verifier.score(samples)
                checkNanos += System.nanoTime() - start
                checks++
                if (gen == generation.get()) pending.set(Result(score, from, gen))
            } catch (_: Exception) {
                failures++
            } finally {
                inFlight.decrementAndGet()
            }
        }
        if (worker == null) {
            inFlight.incrementAndGet()
            task.run()
        } else if (inFlight.incrementAndGet() <= MAX_QUEUED_CHECKS) {
            worker.execute(task)
        } else {
            inFlight.decrementAndGet() // busy (continuous conversation): skip this utterance
        }
    }

    /** Stops the worker and releases the verifier (the speech model). */
    override fun close() {
        generation.incrementAndGet()
        worker?.shutdownNow()
        verifier.close()
    }

    companion object {
        private const val FRAME_SAMPLES = 800
        private const val RING_FRAMES = 80 // 4 s
        const val MIN_SEGMENT_FRAMES = 7L // 0.35 s
        const val MAX_SEGMENT_FRAMES = 50L // 2.5 s
        const val END_PAUSE_FRAMES = 5L // 250 ms
        private const val PAD_FRAMES = 3L
        private const val SPEECH_ABOVE_NOISE_DB = 10f
        private const val MIN_SPEECH_DBFS = -60f
        private const val MAX_QUEUED_CHECKS = 2
        private const val MAX_WINDOW_FRAMES = 200
        const val MAX_DECISION_LATENCY_MS = 3_000L

        /**
         * Score of the speech model's verdict: `clamp(1 + meanLogProb / 8, 0, 1)`, where meanLogProb is the mean
         * log-probability per token the model gives to the phrase's words for the snippet. A similarity score, not a
         * probability; 0.5 (the default threshold) corresponds to a mean token log-probability of −4, measured to
         * separate the phrase from ordinary speech on the test fixtures (docs/WAKE_PHRASE_DETECTION.md).
         */
        fun scoreFromLogProb(meanLogProb: Float): Float = (1f + meanLogProb / 8f).coerceIn(0f, 1f)
    }
}

/**
 * Creates the wake-phrase backend for [requested], falling back to [WakeWordModel.STANDARD] (with a reason in the
 * status) when the speech check cannot be used: no phrase text for recorded samples, an unsupported phrase, or a speech
 * model that fails to load.
 */
object WakeWordEngines {
    class Engine(val detector: KeywordDetector, val status: WakeWordStatus)

    fun create(
        requested: WakeWordModel,
        builtInPhrase: BuiltInWakePhrase,
        samples: List<ByteArray>,
        /** Words of the recorded phrase; ignored while the built-in phrase is used. */
        recordedPhraseText: String?,
        verifierFactory: (phraseText: String) -> PhraseVerifier,
        synchronous: Boolean = false,
        template: TemplateKeywordDetector = TemplateKeywordDetector(builtInPhrase = builtInPhrase)
    ): Engine {
        template.loadTemplates(samples)
        if (requested == WakeWordModel.STANDARD) return Engine(template, WakeWordStatus(requested, WakeWordModel.STANDARD))
        val text = if (template.hasCustomTemplate()) recordedPhraseText?.trim() else builtInPhrase.displayName
        WakePhraseText.problem(text)?.let { problem ->
            return Engine(template, WakeWordStatus(requested, WakeWordModel.STANDARD, "$problem Standard is used until then."))
        }
        val verifier = try {
            verifierFactory(text!!)
        } catch (e: Exception) {
            return Engine(template, WakeWordStatus(requested, WakeWordModel.STANDARD, "${e.message ?: "The speech model could not be started."} Standard is used instead."))
        }
        return Engine(SpeechCheckedKeywordDetector(template, verifier, synchronous), WakeWordStatus(requested, WakeWordModel.SPEECH_CHECK))
    }
}
