package io.github.elgrande73.voicememo.wakephrase

import io.github.elgrande73.voicememo.settings.MatchConfidenceThreshold
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
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
 * Interface for Wake-Phrase / Keyword Spotting (built-in wake phrase: [BuiltInWakePhrase]).
 */
interface KeywordDetector {
    fun processFrame(frame: ShortArray, rms: Float, db: Float): KeywordMatchResult

    /** Sets the minimum match confidence (0.0..1.0) required for [KeywordMatchResult.matched]. */
    fun setMatchConfidenceThreshold(threshold: Float)
    fun loadTemplate(templateBytes: ByteArray): Boolean
    fun hasTemplate(): Boolean
    fun hasCustomTemplate(): Boolean

    /**
     * Uses all valid [templates] (one per enrolled sample) as references; returns how many were loaded.
     * With none, the built-in default wake phrase is used.
     */
    fun loadTemplates(templates: List<ByteArray>): Int {
        clearTemplate()
        return if (templates.isNotEmpty() && loadTemplate(templates.first())) 1 else 0
    }

    /** Removes an enrolled template so the built-in default wake phrase is used again. */
    fun clearTemplate()

    /** Clears the matching window (e.g. after a trigger) but keeps the adaptive noise-floor state. */
    fun resetWindow()

    /** Starts a new audio stream: clears the matching window and the front end's adaptive state. */
    fun resetStream() = resetWindow()
}

/**
 * Offline Dynamic Time Warping (DTW) keyword spotter over 16-band spectral features.
 * Supports the built-in wake phrase "Mango Tango" ([BuiltInWakePhrase]), enrolled (custom) templates, persistence and
 * continuous sliding-window matching.
 *
 * Score semantics: for each candidate window the DTW path cost between the template and the most recent frames is
 * divided by the path length (mean Euclidean distance between L2-normalised feature vectors, range 0..√2) and mapped to
 * `confidence = clamp(1 − distance / 0.40, 0, 1)`. This is a heuristic *similarity score*, not a calibrated probability;
 * the UI shows it as "match" percentage and compares it with the Match confidence threshold (percent / 100).
 *
 * Every frame of a stream must be passed to [processFrame] in order (the matcher needs contiguous temporal context and
 * the front end tracks the background noise continuously). Monitoring and the Settings phrase test both do this.
 *
 * Template format: [MAGIC_FRONT_END_TEMPLATE] with [WakePhraseFrontEnd] features. Templates in any other format (such
 * as those of the very first app version) are not loaded; the user records the phrase again.
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
    private val numBands: Int = 16,
    private val frontEndConfig: FrontEndConfig = FrontEndConfig.DEFAULT,
    private val aggregation: ReferenceAggregation = DEFAULT_AGGREGATION,
    /**
     * Weight of the worst-matching 3-step segment of the alignment path in the distance (0 = plain mean distance).
     * See docs/WAKE_PHRASE_DETECTION.md for the evaluation.
     */
    private val localMismatchWeight: Float = DEFAULT_LOCAL_MISMATCH_WEIGHT,
    /** Distance that maps to a score of 0 (score = 1 − distance / distanceScale). */
    private val distanceScale: Float = DEFAULT_DISTANCE_SCALE,
    /** References used without enrolled samples; replaced only by evaluation tests. */
    private val builtInPhrase: () -> List<List<FloatArray>> = { BuiltInWakePhrase.referenceFeatures },
    /**
     * Compare spectral shape relative to each sequence's own average (per-band mean subtracted from the reference and
     * from each candidate window before alignment), which removes a speaker's or microphone's constant spectral
     * colouring. Evaluated in docs/WAKE_PHRASE_DETECTION.md (section "Mean-normalised matching"); false only to
     * reproduce the earlier matcher in evaluation tests.
     */
    private val meanNormalization: Boolean = DEFAULT_MEAN_NORMALIZATION
) : KeywordDetector {

    /** One enrolled sample (or built-in reference): its feature sequence, and the sequence used for alignment. */
    private inner class Reference(val features: List<FloatArray>) {
        val aligned: List<FloatArray> = if (meanNormalization) meanNormalized(features) else features
    }

    private data class ReferenceMatch(val confidence: Float, val distance: Float, val windowFrames: Int)

    private val frontEnd = WakePhraseFrontEnd(frontEndConfig)
    // Minimum confidence for a match; see MatchConfidenceThreshold for the user-facing mapping
    private var matchConfidenceThreshold: Float =
        MatchConfidenceThreshold.toDetectorConfidence(MatchConfidenceThreshold.DEFAULT_PERCENT)

    // Sliding window of feature vectors
    private val slidingWindow = mutableListOf<FloatArray>()
    private val maxWindowFrames = 50 // Up to 2.5 seconds history

    // Enrolled samples (empty: the built-in default wake phrase is used)
    private var references: List<Reference> = emptyList()

    /** Measurements of the most recent frame processed by the front end (levels and statistics only). */
    val diagnostics: FrontEndDiagnostics get() = frontEnd.diagnostics

    /** Background level estimated by the front end, in dBFS, or null before any audio was processed. */
    val noiseFloorDbfs: Float? get() = frontEnd.noiseFloorDbfs

    private val builtInReferences: List<Reference> by lazy {
        builtInPhrase().map { Reference(it) }
    }

    /** Number of enrolled samples in use (0 = built-in default wake phrase). */
    val referenceCount: Int
        @Synchronized get() = references.size

    /** Number of frames (50 ms each) of the longest active reference. */
    val templateFrameCount: Int
        @Synchronized get() = activeReferences().maxOf { it.features.size }

    /** Enrolled samples, or the references of the built-in phrase if none are enrolled. */
    private fun activeReferences(): List<Reference> = references.ifEmpty { builtInReferences }

    @Synchronized
    override fun setMatchConfidenceThreshold(threshold: Float) {
        this.matchConfidenceThreshold = threshold.coerceIn(0f, 1f)
    }

    @Synchronized
    override fun hasTemplate(): Boolean {
        return true // A built-in wake phrase is always available
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
    override fun loadTemplates(templates: List<ByteArray>): Int {
        references = templates.mapNotNull { parseTemplate(it) }.take(MAX_REFERENCES)
        slidingWindow.clear()
        return references.size
    }

    private fun parseTemplate(templateBytes: ByteArray): Reference? {
        try {
            if (templateBytes.size < 12) return null
            val buffer = ByteBuffer.wrap(templateBytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.int != MAGIC_FRONT_END_TEMPLATE) return null
            val frameCount = buffer.int
            val bands = buffer.int
            // 16 shape values, optionally followed by the speech-presence value (17)
            if (frameCount <= 0 || frameCount > 200 || bands !in numBands..numBands + 1) return null
            if (templateBytes.size < 12 + frameCount * bands * 4) return null
            val template = ArrayList<FloatArray>(frameCount)
            for (f in 0 until frameCount) {
                val vec = FloatArray(bands) { buffer.float }
                if (vec.any { it.isNaN() || it.isInfinite() }) return null
                template.add(vec)
            }
            return Reference(template)
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
        return serialize(reference.features)
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
        references = listOf(Reference(features))
        slidingWindow.clear()
        return true
    }

    @Synchronized
    override fun clearTemplate() {
        references = emptyList()
        slidingWindow.clear()
    }

    @Synchronized
    override fun resetWindow() {
        slidingWindow.clear()
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
        // The front end always runs (noise floor, diagnostics)
        appendBounded(slidingWindow, frontEnd.process(frame))
        val matches = activeReferences().mapNotNull { matchReference(it, slidingWindow) }
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
        val template = reference.aligned
        val minLen = (template.size * 0.70).toInt().coerceAtLeast(6)
        if (window.size < minLen) return null

        // Test candidate lengths in sliding window to support different speaking rates
        val maxLen = min(window.size, (template.size * 1.35).toInt())
        var best: ReferenceMatch? = null
        var candLen = minLen
        while (candLen <= maxLen) {
            val testSlice = window.subList(window.size - candLen, window.size)

            // Dynamic acoustic trajectory verification:
            // Wake phrases have distinctive phonemic transitions (e.g. nasal -> vowel -> stop -> vowel).
            // Monotone/stationary sounds ("hah", breath, constant noise, single vowel) have near-zero spectral flux.
            var fluxSum = 0f
            for (k in 1 until testSlice.size) {
                fluxSum += euclideanDistance(testSlice[k], testSlice[k - 1])
            }
            val avgFlux = fluxSum / (testSlice.size - 1).coerceAtLeast(1)
            if (avgFlux >= 0.040f) { // otherwise: reject stationary sound/noise
                val (cost, stepCount) = computeDtwDistance(template, if (meanNormalization) meanNormalized(testSlice) else testSlice)
                if (cost < Float.MAX_VALUE / 4f) {
                    val normalizedDist = cost / stepCount
                    // Confidence scale used by the user-facing match confidence threshold (percent / 100)
                    val conf = (1.0f - (normalizedDist / distanceScale)).coerceIn(0.0f, 1.0f)
                    if (best == null || conf > best.confidence) best = ReferenceMatch(conf, normalizedDist, candLen)
                }
            }
            candLen = if (candLen < maxLen && candLen + 2 > maxLen) maxLen else candLen + 2
        }
        if (best != null && localMismatchWeight > 0f) {
            // Penalise a locally bad alignment (e.g. one different syllable) that the mean distance dilutes
            val slice = window.subList(window.size - best.windowFrames, window.size)
            val path = dtwPathCosts(template, if (meanNormalization) meanNormalized(slice) else slice)
            if (path != null && path.size >= LOCAL_SEGMENT_STEPS) {
                var worst = 0f
                for (i in 0..path.size - LOCAL_SEGMENT_STEPS) {
                    var sum = 0f
                    for (k in 0 until LOCAL_SEGMENT_STEPS) sum += path[i + k]
                    worst = maxOf(worst, sum / LOCAL_SEGMENT_STEPS)
                }
                val distance = best.distance + localMismatchWeight * (worst - best.distance).coerceAtLeast(0f)
                best = ReferenceMatch((1.0f - distance / distanceScale).coerceIn(0f, 1f), distance, best.windowFrames)
            }
        }
        return best
    }

    /** [seq] with the mean of each of the first [numBands] (spectral shape) values subtracted; other values unchanged. */
    private fun meanNormalized(seq: List<FloatArray>): List<FloatArray> {
        val mean = FloatArray(numBands)
        for (v in seq) for (b in 0 until min(numBands, v.size)) mean[b] += v[b]
        for (b in 0 until numBands) mean[b] /= seq.size
        return seq.map { v -> FloatArray(v.size) { b -> if (b < numBands) v[b] - mean[b] else v[b] } }
    }

    private fun computeDtwDistance(template: List<FloatArray>, input: List<FloatArray>): Pair<Float, Int> =
        dtwDistance(template, input)

    private fun euclideanDistance(a: FloatArray, b: FloatArray): Float = featureDistance(a, b)

    companion object {
        /** DTW path cost and path length between two feature sequences (Sakoe-Chiba corridor). */
        fun dtwDistance(template: List<FloatArray>, input: List<FloatArray>): Pair<Float, Int> {
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
                    val cost = featureDistance(template[i - 1], input[j - 1])
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

        fun featureDistance(a: FloatArray, b: FloatArray): Float {
            var sum = 0f
            val len = min(a.size, b.size)
            for (i in 0 until len) {
                val diff = a[i] - b[i]
                sum += diff * diff
            }
            return sqrt(sum)
        }


        /** Per-step frame distances along the optimal DTW path (same recurrence as [dtwDistance]), or null. */
        fun dtwPathCosts(template: List<FloatArray>, input: List<FloatArray>): FloatArray? {
            val n = template.size
            val m = input.size
            val maxDist = Float.MAX_VALUE / 2f
            val dtw = Array(n + 1) { FloatArray(m + 1) { maxDist } }
            val from = Array(n + 1) { ByteArray(m + 1) }
            dtw[0][0] = 0f
            val corridorWidth = maxOf(4, (n * 0.35).toInt())
            for (i in 1..n) {
                val expectedJ = (i.toFloat() / n.toFloat() * m.toFloat()).toInt()
                for (j in maxOf(1, expectedJ - corridorWidth)..minOf(m, expectedJ + corridorWidth)) {
                    var minPrev = dtw[i - 1][j - 1]
                    var dir: Byte = 0
                    if (dtw[i - 1][j] < minPrev) { minPrev = dtw[i - 1][j]; dir = 1 }
                    if (dtw[i][j - 1] < minPrev) { minPrev = dtw[i][j - 1]; dir = 2 }
                    if (minPrev < maxDist) {
                        dtw[i][j] = minPrev + featureDistance(template[i - 1], input[j - 1])
                        from[i][j] = dir
                    }
                }
            }
            if (dtw[n][m] >= maxDist) return null
            val costs = ArrayList<Float>()
            var i = n
            var j = m
            while (i > 0 && j > 0) {
                costs += featureDistance(template[i - 1], input[j - 1])
                when (from[i][j].toInt()) {
                    0 -> { i--; j-- }
                    1 -> i--
                    else -> j--
                }
            }
            return costs.reversed().toFloatArray()
        }

        const val LOCAL_SEGMENT_STEPS = 3
        const val DEFAULT_LOCAL_MISMATCH_WEIGHT = 0f
        const val DEFAULT_DISTANCE_SCALE = 0.40f

        /** Maximum number of enrolled samples (references) of the wake phrase. */
        const val MAX_REFERENCES = 5
        const val DEFAULT_MEAN_NORMALIZATION = true
        val DEFAULT_AGGREGATION = ReferenceAggregation.MAX

        fun serialize(features: List<FloatArray>): ByteArray {
            val bands = features.first().size
            val buffer = ByteBuffer.allocate(12 + features.size * bands * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(MAGIC_FRONT_END_TEMPLATE)
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
            val analysis = analyzeRecording(chunks, config)
            val span = analysis.speechSpan ?: return null
            return analysis.features.subList(span.first, span.last + 1).toList()
        }

        /** Per-frame features and levels of a phrase recording, with the detected speech span (see [findSpeechSpan]). */
        class RecordingAnalysis(
            val features: List<FloatArray>,
            val levelsDbfs: FloatArray,
            val clippedSamples: IntArray,
            val noiseFloorDbfs: Float,
            val speechSpan: IntRange?
        )

        fun analyzeRecording(chunks: List<ShortArray>, config: FrontEndConfig = FrontEndConfig.DEFAULT): RecordingAnalysis {
            val enrollFrontEnd = WakePhraseFrontEnd(config)
            chunks.forEach { enrollFrontEnd.process(it) } // prime the noise floor
            val features = ArrayList<FloatArray>(chunks.size)
            val levels = FloatArray(chunks.size)
            val clipped = IntArray(chunks.size)
            chunks.forEachIndexed { i, chunk ->
                features += enrollFrontEnd.process(chunk)
                levels[i] = enrollFrontEnd.diagnostics.inputRmsDbfs
                clipped[i] = enrollFrontEnd.diagnostics.clippedSamples
            }
            val noise = enrollFrontEnd.noiseFloorDbfs ?: -120f
            return RecordingAnalysis(features, levels, clipped, noise, findSpeechSpan(levels, noise))
        }

        /** Parses a serialized template into its feature sequence, or null if invalid or in another format. */
        fun parseFeatures(templateBytes: ByteArray): List<FloatArray>? = try {
            val buffer = ByteBuffer.wrap(templateBytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buffer.int
            val frames = buffer.int
            val bands = buffer.int
            if (magic != MAGIC_FRONT_END_TEMPLATE || frames !in 1..200 || bands <= 0 ||
                templateBytes.size < 12 + frames * bands * 4
            ) null else List(frames) { FloatArray(bands) { buffer.float } }
        } catch (_: Exception) {
            null
        }

        /** Template format with [WakePhraseFrontEnd] features (stored in enrolled samples): never change it. */
        const val MAGIC_FRONT_END_TEMPLATE = 0x32414943
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
    }
}


