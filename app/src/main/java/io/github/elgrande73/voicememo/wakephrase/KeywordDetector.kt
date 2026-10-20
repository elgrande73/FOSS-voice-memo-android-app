package io.github.elgrande73.voicememo.wakephrase

import io.github.elgrande73.voicememo.detection.FFT
import io.github.elgrande73.voicememo.settings.MatchConfidenceThreshold
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Result data class from keyword detection evaluation on a frame.
 */
data class KeywordMatchResult(
    val matched: Boolean,
    val confidence: Float,
    val distance: Float,
    /** Length (frames) of the audio window that produced the best match, ending at the newest frame; 0 if none. */
    val windowFrames: Int = 0
)

/**
 * Interface for Wake-Phrase / Keyword Spotting (default wake phrase: "Ciao Linda").
 */
interface KeywordDetector {
    fun processFrame(frame: ShortArray, rms: Float, db: Float): KeywordMatchResult

    /** Sets the minimum match confidence (0.0..1.0) required for [KeywordMatchResult.matched]. */
    fun setMatchConfidenceThreshold(threshold: Float)
    fun loadTemplate(templateBytes: ByteArray): Boolean
    fun hasTemplate(): Boolean
    fun hasCustomTemplate(): Boolean

    /** Removes an enrolled template so the built-in default wake phrase is used again. */
    fun clearTemplate()

    /** Clears the matching window (e.g. after a trigger) but keeps the adaptive noise-floor state. */
    fun resetWindow()

    /** Starts a new audio stream: clears the matching window and the front end's adaptive state. */
    fun resetStream() = resetWindow()
}

/**
 * Offline Dynamic Time Warping (DTW) keyword spotter over 16-band spectral features.
 * Supports the built-in "Ciao Linda" reference, enrolled (custom) templates, persistence and continuous
 * sliding-window matching.
 *
 * Score semantics: for each candidate window the DTW path cost between the template and the most recent frames is
 * divided by the path length (mean Euclidean distance between L2-normalised feature vectors, range 0..√2) and mapped to
 * `confidence = clamp(1 − distance / 0.40, 0, 1)`. This is a heuristic *similarity score*, not a calibrated probability;
 * the UI shows it as "match" percentage and compares it with the Match confidence threshold (percent / 100).
 *
 * Every frame of a stream must be passed to [processFrame] in order (the matcher needs contiguous temporal context and
 * the front end tracks the background noise continuously). Monitoring and the Settings phrase test both do this.
 *
 * Feature versions: templates enrolled with this version ('CIA2') use [WakePhraseFrontEnd]; templates saved by earlier
 * versions ('CIAO') keep being matched with the legacy feature extractor they were recorded with, so existing user
 * templates continue to work (re-recording is recommended to benefit from the new front end).
 */
/**
 * How the scores of several enrolled references (wake-phrase samples) are combined into one match score.
 * The choice is evaluated in WakePhraseEvaluationTest / docs/WAKE_PHRASE_DETECTION.md.
 */
enum class ReferenceAggregation {
    /** Score of the best-matching reference. */
    MAX,
    /** Mean of the two best references (with a single reference: its score). */
    MEAN_OF_TOP_TWO
}

class TemplateKeywordDetector(
    private val frameSize: Int = 800, // 50ms at 16kHz
    private val fftSize: Int = 512,
    private val numBands: Int = 16,
    private val frontEndConfig: FrontEndConfig = FrontEndConfig.DEFAULT,
    private val aggregation: ReferenceAggregation = DEFAULT_AGGREGATION
) : KeywordDetector {

    /** One enrolled sample: its feature sequence and the feature extractor it was recorded with. */
    private class Reference(val features: List<FloatArray>, val legacy: Boolean)

    private data class ReferenceMatch(val confidence: Float, val distance: Float, val windowFrames: Int)

    private val fft = FFT(fftSize)
    private val frontEnd = WakePhraseFrontEnd(frontEndConfig)
    // Minimum confidence for a match; see MatchConfidenceThreshold for the user-facing mapping
    private var matchConfidenceThreshold: Float =
        MatchConfidenceThreshold.toDetectorConfidence(MatchConfidenceThreshold.DEFAULT_PERCENT)

    // Sliding windows of feature vectors (legacy features only while a legacy reference is loaded)
    private val slidingWindow = mutableListOf<FloatArray>()
    private val legacyWindow = mutableListOf<FloatArray>()
    private val maxWindowFrames = 50 // Up to 2.5 seconds history

    // Enrolled samples (empty: the built-in default wake phrase is used)
    private var references: List<Reference> = emptyList()

    /** Measurements of the most recent frame processed by the front end (levels and statistics only). */
    val diagnostics: FrontEndDiagnostics get() = frontEnd.diagnostics

    /** Background level estimated by the front end, in dBFS, or null before any audio was processed. */
    val noiseFloorDbfs: Float? get() = frontEnd.noiseFloorDbfs

    /** True when an enrolled sample was saved by an older version and uses the legacy features. */
    val isLegacyTemplate: Boolean
        @Synchronized get() = references.any { it.legacy }

    /** Number of enrolled samples in use (0 = built-in default wake phrase). */
    val referenceCount: Int
        @Synchronized get() = references.size

    /** Number of frames (50 ms each) of the longest active reference. */
    val templateFrameCount: Int
        @Synchronized get() = references.maxOfOrNull { it.features.size } ?: defaultTemplate.size

    // Canonical default reference template for "Ciao Linda"
    private val defaultTemplate: List<FloatArray> by lazy {
        buildDefaultCiaoLindaTemplate()
    }

    @Synchronized
    override fun setMatchConfidenceThreshold(threshold: Float) {
        this.matchConfidenceThreshold = threshold.coerceIn(0f, 1f)
    }

    @Synchronized
    override fun hasTemplate(): Boolean {
        return true // Default built-in "Ciao Linda" template is always available
    }

    @Synchronized
    override fun hasCustomTemplate(): Boolean = references.isNotEmpty()

    @Synchronized
    override fun loadTemplate(templateBytes: ByteArray): Boolean = loadTemplates(listOf(templateBytes)) == 1

    /**
     * Replaces the enrolled references with the valid entries of [templates] (one per sample, at most
     * [MAX_REFERENCES]). Invalid entries are skipped. Returns the number of references now in use; with 0 the
     * built-in default wake phrase is used.
     */
    @Synchronized
    fun loadTemplates(templates: List<ByteArray>): Int {
        references = templates.mapNotNull { parseTemplate(it) }.take(MAX_REFERENCES)
        slidingWindow.clear()
        legacyWindow.clear()
        return references.size
    }

    private fun parseTemplate(templateBytes: ByteArray): Reference? {
        try {
            if (templateBytes.size < 12) return null
            val buffer = ByteBuffer.wrap(templateBytes).order(ByteOrder.LITTLE_ENDIAN)
            val legacy = when (buffer.int) {
                MAGIC_FRONT_END_TEMPLATE -> false
                MAGIC_LEGACY_TEMPLATE -> true
                else -> return null
            }
            val frameCount = buffer.int
            val bands = buffer.int
            if (frameCount <= 0 || frameCount > 200 || bands != numBands) return null
            if (templateBytes.size < 12 + frameCount * numBands * 4) return null
            val template = ArrayList<FloatArray>(frameCount)
            for (f in 0 until frameCount) {
                val vec = FloatArray(numBands) { buffer.float }
                if (vec.any { it.isNaN() || it.isInfinite() }) return null
                template.add(vec)
            }
            return Reference(template, legacy)
        } catch (_: Exception) {
            return null
        }
    }

    /**
     * Serializes the enrolled template (single-sample use, e.g. right after [recordTemplate]).
     */
    @Synchronized
    fun getTemplateData(): ByteArray? {
        val reference = references.singleOrNull() ?: return null
        return serialize(reference.features, reference.legacy)
    }

    /**
     * Enrolls a new template from a recording (consecutive 50 ms frames, e.g. the 2.5 s Settings recording),
     * replacing all references with this single one. See [WakePhraseEnrollment] for the validated multi-sample flow.
     *
     * @return false if no phrase of at least [MIN_TEMPLATE_FRAMES] frames was found (nothing is changed then).
     */
    @Synchronized
    fun recordTemplate(chunks: List<ShortArray>): Boolean {
        val features = extractPhraseFeatures(chunks, frontEndConfig) ?: return false
        references = listOf(Reference(features, legacy = false))
        slidingWindow.clear()
        legacyWindow.clear()
        return true
    }

    @Synchronized
    override fun clearTemplate() {
        references = emptyList()
        slidingWindow.clear()
        legacyWindow.clear()
    }

    @Synchronized
    override fun resetWindow() {
        slidingWindow.clear()
        legacyWindow.clear()
    }

    @Synchronized
    override fun resetStream() {
        resetWindow()
        frontEnd.reset()
    }

    /**
     * Primes the front end's noise floor with [frames] without matching, so a short recording (Settings test) is
     * evaluated with a converged background estimate, as during continuous monitoring.
     */
    @Synchronized
    fun primeNoiseFloor(frames: List<ShortArray>) {
        frames.forEach { frontEnd.process(it) }
    }

    @Synchronized
    override fun processFrame(frame: ShortArray, rms: Float, db: Float): KeywordMatchResult {
        // The front end always runs (noise floor, diagnostics); legacy references are matched with legacy features
        appendBounded(slidingWindow, frontEnd.process(frame))
        val active = references.ifEmpty { listOf(Reference(defaultTemplate, legacy = false)) }
        if (active.any { it.legacy }) appendBounded(legacyWindow, extractFeatureVector(frame))

        val matches = active.mapNotNull { matchReference(it, if (it.legacy) legacyWindow else slidingWindow) }
        if (matches.isEmpty()) return KeywordMatchResult(matched = false, confidence = 0f, distance = 1.0f)

        val sorted = matches.sortedByDescending { it.confidence }
        val confidence = when (aggregation) {
            ReferenceAggregation.MAX -> sorted[0].confidence
            ReferenceAggregation.MEAN_OF_TOP_TWO ->
                if (sorted.size == 1) sorted[0].confidence else (sorted[0].confidence + sorted[1].confidence) / 2f
        }
        return KeywordMatchResult(
            matched = confidence > 0f && confidence >= matchConfidenceThreshold,
            confidence = confidence,
            distance = sorted[0].distance,
            windowFrames = sorted[0].windowFrames
        )
    }

    private fun appendBounded(window: MutableList<FloatArray>, features: FloatArray) {
        window.add(features)
        if (window.size > maxWindowFrames) window.removeAt(0)
    }

    /** Best match of [reference] against windows ending at the newest frame, or null if none qualifies. */
    private fun matchReference(reference: Reference, window: List<FloatArray>): ReferenceMatch? {
        val template = reference.features
        val minLen = (template.size * 0.70).toInt().coerceAtLeast(6)
        if (window.size < minLen) return null

        // Test candidate lengths in sliding window to support different speaking rates
        val maxLen = min(window.size, (template.size * 1.35).toInt())
        var best: ReferenceMatch? = null
        var candLen = minLen
        while (candLen <= maxLen) {
            val testSlice = window.subList(window.size - candLen, window.size)

            // Dynamic acoustic trajectory verification:
            // "Ciao Linda" has distinctive phonemic transitions (affricate -> vowel -> nasal -> stop -> vowel).
            // Monotone/stationary sounds ("hah", breath, constant noise, single vowel) have near-zero spectral flux.
            var fluxSum = 0f
            for (k in 1 until testSlice.size) {
                fluxSum += euclideanDistance(testSlice[k], testSlice[k - 1])
            }
            val avgFlux = fluxSum / (testSlice.size - 1).coerceAtLeast(1)
            if (avgFlux >= 0.040f) { // otherwise: reject stationary sound/noise
                val (cost, stepCount) = computeDtwDistance(template, testSlice)
                if (cost < Float.MAX_VALUE / 4f) {
                    val normalizedDist = cost / stepCount
                    // Confidence scale used by the user-facing match confidence threshold (percent / 100)
                    val conf = (1.0f - (normalizedDist / 0.40f)).coerceIn(0.0f, 1.0f)
                    if (best == null || conf > best.confidence) best = ReferenceMatch(conf, normalizedDist, candLen)
                }
            }
            candLen = if (candLen < maxLen && candLen + 2 > maxLen) maxLen else candLen + 2
        }
        return best
    }

    /** Legacy (version 1) feature extractor, kept only for templates enrolled with earlier versions. */
    fun extractFeatureVector(frame: ShortArray): FloatArray {
        val fftBuffer = DoubleArray(fftSize)
        val len = min(frame.size, fftSize)
        for (i in 0 until len) {
            // Apply Hann window
            val window = 0.5 * (1.0 - cos(2.0 * Math.PI * i / (fftSize - 1)))
            fftBuffer[i] = (frame[i] / 32768.0) * window
        }

        val power = fft.computePowerSpectrum(fftBuffer)
        val features = FloatArray(numBands)
        val binStep = (power.size - 1) / numBands

        var totalEnergy = 0.0
        for (b in 0 until numBands) {
            var bandSum = 0.0
            val start = b * binStep
            val end = min(start + binStep, power.size)
            for (k in start until end) {
                bandSum += power[k]
            }
            totalEnergy += bandSum
            val logEnergy = log10(bandSum + 1e-6).toFloat()
            features[b] = (logEnergy + 4.0f).coerceAtLeast(0.01f)
        }

        // L2-normalize the 16-band feature vector so matching focuses on spectral shape
        var sumSquares = 0.0
        for (f in features) {
            sumSquares += f * f
        }
        val norm = sqrt(sumSquares).toFloat()
        if (norm > 1e-4f) {
            for (b in 0 until numBands) {
                features[b] /= norm
            }
        }

        return features
    }

    private fun computeDtwDistance(template: List<FloatArray>, input: List<FloatArray>): Pair<Float, Int> {
        val n = template.size
        val m = input.size
        val maxDist = Float.MAX_VALUE / 2f
        val dtw = Array(n + 1) { FloatArray(m + 1) { maxDist } }
        val steps = Array(n + 1) { IntArray(m + 1) { 0 } }
        dtw[0][0] = 0f

        // Sakoe-Chiba corridor prevents pathological warping from single sounds/breaths
        val corridorWidth = maxOf(4, (n * 0.35).toInt())

        for (i in 1..n) {
            val expectedJ = (i.toFloat() / n.toFloat() * m.toFloat()).toInt()
            val minJ = maxOf(1, expectedJ - corridorWidth)
            val maxJ = minOf(m, expectedJ + corridorWidth)

            for (j in minJ..maxJ) {
                val cost = euclideanDistance(template[i - 1], input[j - 1])
                var minPrev = dtw[i - 1][j - 1]
                var prevStep = steps[i - 1][j - 1]

                if (dtw[i - 1][j] < minPrev) {
                    minPrev = dtw[i - 1][j]
                    prevStep = steps[i - 1][j]
                }
                if (dtw[i][j - 1] < minPrev) {
                    minPrev = dtw[i][j - 1]
                    prevStep = steps[i][j - 1]
                }

                if (minPrev < maxDist) {
                    dtw[i][j] = minPrev + cost
                    steps[i][j] = prevStep + 1
                }
            }
        }

        val totalCost = dtw[n][m]
        val totalSteps = maxOf(1, steps[n][m])
        return Pair(totalCost, totalSteps)
    }

    private fun euclideanDistance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val len = min(a.size, b.size)
        for (i in 0 until len) {
            val diff = a[i] - b[i]
            sum += diff * diff
        }
        return sqrt(sum)
    }

    private fun buildDefaultCiaoLindaTemplate(): List<FloatArray> {
        val audioChunks = generateCanonicalCiaoLindaAudio(sampleRate = 16000, amplitude = 12000.0)
        val builtInFrontEnd = WakePhraseFrontEnd(frontEndConfig)
        return audioChunks.map { builtInFrontEnd.process(it) }
    }

    companion object {
        /** Maximum number of enrolled samples (references) of the wake phrase. */
        const val MAX_REFERENCES = 5
        val DEFAULT_AGGREGATION = ReferenceAggregation.MAX

        fun serialize(features: List<FloatArray>, legacy: Boolean = false): ByteArray {
            val bands = features.first().size
            val buffer = ByteBuffer.allocate(12 + features.size * bands * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(if (legacy) MAGIC_LEGACY_TEMPLATE else MAGIC_FRONT_END_TEMPLATE)
            buffer.putInt(features.size)
            buffer.putInt(bands)
            features.forEach { vec -> vec.forEach { buffer.putFloat(it) } }
            return buffer.array()
        }

        /**
         * Features of the spoken phrase in [chunks] (a Settings recording): a fresh front end primed with the same
         * recording so its noise floor is converged as during monitoring, then endpointing ([findSpeechSpan]).
         * Returns null if no phrase was found.
         */
        fun extractPhraseFeatures(chunks: List<ShortArray>, config: FrontEndConfig = FrontEndConfig.DEFAULT): List<FloatArray>? {
            val enrollFrontEnd = WakePhraseFrontEnd(config)
            chunks.forEach { enrollFrontEnd.process(it) } // prime the noise floor
            val features = ArrayList<FloatArray>(chunks.size)
            val levels = FloatArray(chunks.size)
            chunks.forEachIndexed { i, chunk ->
                features += enrollFrontEnd.process(chunk)
                levels[i] = enrollFrontEnd.diagnostics.inputRmsDbfs
            }
            val span = findSpeechSpan(levels, enrollFrontEnd.noiseFloorDbfs ?: -120f) ?: return null
            return features.subList(span.first, span.last + 1).toList()
        }

        const val MAGIC_LEGACY_TEMPLATE = 0x4349414F // 'CIAO': legacy features
        const val MAGIC_FRONT_END_TEMPLATE = 0x32414943 // 'CIA2' (little endian): WakePhraseFrontEnd features
        const val MIN_TEMPLATE_FRAMES = 6

        /** A frame belongs to the phrase if it is this far above the recording's noise floor... */
        const val SPEECH_ABOVE_NOISE_DB = 10f
        /** ...and within this range of the recording's loudest frame. */
        const val SPEECH_BELOW_PEAK_DB = 35f
        const val SPEECH_PADDING_FRAMES = 1
        /** A recording whose loudest frame is below this level contains no usable phrase. */
        const val SILENT_RECORDING_DBFS = -70f

        /**
         * Energy-based endpointing: first..last frame that is clearly above the background, extended by
         * [SPEECH_PADDING_FRAMES] on each side. If the recording has no quiet part to estimate the background from
         * (sound throughout), all frames within [SPEECH_BELOW_PEAK_DB] of the peak are used. Returns null if the span
         * is shorter than [MIN_TEMPLATE_FRAMES] or the recording is silent.
         */
        fun findSpeechSpan(levelsDbfs: FloatArray, noiseFloorDbfs: Float): IntRange? {
            if (levelsDbfs.isEmpty()) return null
            val peak = levelsDbfs.max()
            if (peak < SILENT_RECORDING_DBFS) return null
            val hasBackground = peak >= noiseFloorDbfs + SPEECH_ABOVE_NOISE_DB
            val threshold = if (hasBackground) {
                maxOf(noiseFloorDbfs + SPEECH_ABOVE_NOISE_DB, peak - SPEECH_BELOW_PEAK_DB)
            } else {
                peak - SPEECH_BELOW_PEAK_DB
            }
            val first = levelsDbfs.indexOfFirst { it >= threshold }
            val last = levelsDbfs.indexOfLast { it >= threshold }
            if (first < 0) return null
            val span = (first - SPEECH_PADDING_FRAMES).coerceAtLeast(0)..(last + SPEECH_PADDING_FRAMES).coerceAtMost(levelsDbfs.size - 1)
            return if (span.last - span.first + 1 >= MIN_TEMPLATE_FRAMES) span else null
        }

        /**
         * Synthesizes canonical acoustic PCM sequence representing the phrase "Ciao Linda".
         * 16kHz, 50ms (800 samples) per frame.
         * Segment breakdown:
         * - Frames 0..2 (150ms): "Ch" (/tʃ/) affricate burst with high-frequency energy.
         * - Frames 3..6 (200ms): "a" (/a/) open vowel with F1~750Hz, F2~1300Hz.
         * - Frames 7..9 (150ms): "o" (/oʊ/) rounded vowel with F1~500Hz, F2~900Hz.
         * - Frame 10 (50ms): Brief dip/inter-word boundary.
         * - Frames 11..13 (150ms): "Li" (/lɪ/) lateral vowel with F1~380Hz, F2~2100Hz.
         * - Frames 14..16 (150ms): "n" (/n/) nasal murmur with low F1~280Hz.
         * - Frames 17..18 (100ms): "d" (/d/) alveolar stop closure & release burst.
         * - Frames 19..22 (200ms): "a" (/a/) open vowel with F1~700Hz, F2~1250Hz.
         */
        fun generateCanonicalCiaoLindaAudio(
            sampleRate: Int = 16000,
            amplitude: Double = 12000.0
        ): List<ShortArray> {
            val chunks = mutableListOf<ShortArray>()
            val frameSize = 800 // 50ms

            for (f in 0 until 23) {
                val chunk = ShortArray(frameSize)
                for (i in 0 until frameSize) {
                    val t = (f * frameSize + i).toDouble() / sampleRate
                    val sample = when (f) {
                        in 0..2 -> { // "Ch" - high frequency friction
                            val n1 = sin(2.0 * Math.PI * 3800.0 * t) * 0.5
                            val n2 = sin(2.0 * Math.PI * 5200.0 * t) * 0.5
                            (n1 + n2) * amplitude * 0.75
                        }
                        in 3..6 -> { // "a" (/a/) - F1 750Hz, F2 1300Hz, F0 130Hz
                            val f0 = sin(2.0 * Math.PI * 130.0 * t) * 0.3
                            val f1 = sin(2.0 * Math.PI * 750.0 * t) * 0.4
                            val f2 = sin(2.0 * Math.PI * 1300.0 * t) * 0.3
                            (f0 + f1 + f2) * amplitude
                        }
                        in 7..9 -> { // "o" (/oʊ/) - F1 500Hz, F2 900Hz, F0 125Hz
                            val f0 = sin(2.0 * Math.PI * 125.0 * t) * 0.3
                            val f1 = sin(2.0 * Math.PI * 500.0 * t) * 0.5
                            val f2 = sin(2.0 * Math.PI * 900.0 * t) * 0.2
                            (f0 + f1 + f2) * amplitude * 0.9
                        }
                        10 -> { // Brief transition
                            val f0 = sin(2.0 * Math.PI * 120.0 * t) * 0.2
                            f0 * amplitude * 0.3
                        }
                        in 11..13 -> { // "Li" (/lɪ/) - F1 380Hz, F2 2100Hz, F0 135Hz
                            val f0 = sin(2.0 * Math.PI * 135.0 * t) * 0.3
                            val f1 = sin(2.0 * Math.PI * 380.0 * t) * 0.4
                            val f2 = sin(2.0 * Math.PI * 2100.0 * t) * 0.3
                            (f0 + f1 + f2) * amplitude * 0.95
                        }
                        in 14..16 -> { // "n" (/n/) - F1 280Hz nasal murmur
                            val f0 = sin(2.0 * Math.PI * 130.0 * t) * 0.4
                            val f1 = sin(2.0 * Math.PI * 280.0 * t) * 0.6
                            (f0 + f1) * amplitude * 0.7
                        }
                        in 17..18 -> { // "d" (/d/) - release transient
                            val f0 = sin(2.0 * Math.PI * 2200.0 * t) * 0.7
                            f0 * amplitude * 0.6
                        }
                        else -> { // "a" (/a/) - final vowel
                            val f0 = sin(2.0 * Math.PI * 120.0 * t) * 0.3
                            val f1 = sin(2.0 * Math.PI * 700.0 * t) * 0.4
                            val f2 = sin(2.0 * Math.PI * 1250.0 * t) * 0.3
                            (f0 + f1 + f2) * amplitude * 0.9
                        }
                    }
                    chunk[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                chunks.add(chunk)
            }
            return chunks
        }
    }
}


