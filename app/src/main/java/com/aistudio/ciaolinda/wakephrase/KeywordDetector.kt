package com.aistudio.ciaolinda.wakephrase

import com.aistudio.ciaolinda.detection.FFT
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
    val distance: Float
)

/**
 * Interface for Wake-Phrase / Keyword Spotting (e.g. "Ciao Linda").
 */
interface KeywordDetector {
    fun processFrame(frame: ShortArray, rms: Float, db: Float): KeywordMatchResult
    fun setSensitivity(sensitivity: Float)
    fun loadTemplate(templateBytes: ByteArray): Boolean
    fun hasTemplate(): Boolean
    fun hasCustomTemplate(): Boolean
    fun resetWindow()
}

/**
 * High-performance, 100% offline Dynamic Time Warping (DTW) and Filterbank Energy Keyword Spotter.
 * Supports built-in "Ciao Linda" reference model, custom voice template learning, persistence,
 * and continuous sliding-window phrase matching.
 */
class TemplateKeywordDetector(
    private val frameSize: Int = 800, // 50ms at 16kHz
    private val fftSize: Int = 512,
    private val numBands: Int = 16
) : KeywordDetector {

    private val fft = FFT(fftSize)
    private var sensitivity: Float = 0.60f // 0.1 to 0.95 (Higher = easier trigger)

    // Sliding window of feature vectors
    private val slidingWindow = mutableListOf<FloatArray>()
    private val maxWindowFrames = 50 // Up to 2.5 seconds history

    // Custom user voice template (if enrolled)
    private var referenceTemplate: List<FloatArray>? = null

    // Canonical default reference template for "Ciao Linda"
    private val defaultTemplate: List<FloatArray> by lazy {
        buildDefaultCiaoLindaTemplate()
    }

    @Synchronized
    override fun setSensitivity(sensitivity: Float) {
        this.sensitivity = sensitivity.coerceIn(0.1f, 0.95f)
    }

    @Synchronized
    override fun hasTemplate(): Boolean {
        return true // Default built-in "Ciao Linda" template is always available
    }

    @Synchronized
    override fun hasCustomTemplate(): Boolean {
        return referenceTemplate != null && referenceTemplate!!.isNotEmpty()
    }

    @Synchronized
    override fun loadTemplate(templateBytes: ByteArray): Boolean {
        try {
            if (templateBytes.size < 12) return false
            val buffer = ByteBuffer.wrap(templateBytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buffer.int
            if (magic != 0x4349414F) return false // 'CIAO'

            val frameCount = buffer.int
            val bands = buffer.int
            if (frameCount <= 0 || frameCount > 200 || bands != numBands) return false

            val template = mutableListOf<FloatArray>()
            for (f in 0 until frameCount) {
                val vec = FloatArray(numBands)
                for (b in 0 until numBands) {
                    vec[b] = buffer.float
                }
                template.add(vec)
            }
            referenceTemplate = template
            return true
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Serializes the current learned template into a portable byte array.
     */
    @Synchronized
    fun getTemplateData(): ByteArray? {
        val template = referenceTemplate ?: return null
        val buffer = ByteBuffer.allocate(12 + template.size * numBands * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x4349414F) // Magic
        buffer.putInt(template.size)
        buffer.putInt(numBands)
        for (vec in template) {
            for (valItem in vec) {
                buffer.putFloat(valItem)
            }
        }
        return buffer.array()
    }

    /**
     * Records a new template from a sequence of training PCM audio chunks.
     */
    @Synchronized
    fun recordTemplate(chunks: List<ShortArray>): Boolean {
        val features = mutableListOf<FloatArray>()
        for (chunk in chunks) {
            val vec = extractFeatureVector(chunk)
            val energy = vec.sum()
            if (energy > 0.05f) {
                features.add(vec)
            }
        }

        if (features.size < 6) return false // Too short to be a valid "Ciao Linda" phrase

        referenceTemplate = features
        return true
    }

    @Synchronized
    fun clearTemplate() {
        referenceTemplate = null
        slidingWindow.clear()
    }

    @Synchronized
    override fun resetWindow() {
        slidingWindow.clear()
    }

    @Synchronized
    override fun processFrame(frame: ShortArray, rms: Float, db: Float): KeywordMatchResult {
        val featureVec = extractFeatureVector(frame)
        slidingWindow.add(featureVec)
        if (slidingWindow.size > maxWindowFrames) {
            slidingWindow.removeAt(0)
        }

        val template = referenceTemplate ?: defaultTemplate
        val minLen = (template.size * 0.70).toInt().coerceAtLeast(6)
        if (slidingWindow.size < minLen) {
            return KeywordMatchResult(matched = false, confidence = 0f, distance = 1.0f)
        }

        // Test candidate lengths in sliding window to support different speaking rates
        val maxLen = min(slidingWindow.size, (template.size * 1.35).toInt())
        val candidateLengths = mutableListOf<Int>()
        var cl = minLen
        while (cl <= maxLen) {
            candidateLengths.add(cl)
            cl += 2
        }
        if (!candidateLengths.contains(maxLen)) {
            candidateLengths.add(maxLen)
        }

        var bestNormalizedDist = 1.0f
        var bestConfidence = 0.0f
        var bestMatched = false

        for (candLen in candidateLengths) {
            val testSlice = slidingWindow.takeLast(candLen)

            // Dynamic acoustic trajectory verification:
            // "Ciao Linda" has distinctive phonemic transitions (affricate -> vowel -> nasal -> stop -> vowel).
            // Monotone/stationary sounds ("hah", breath, constant noise, single vowel) have near-zero spectral flux.
            var fluxSum = 0f
            for (k in 1 until testSlice.size) {
                fluxSum += euclideanDistance(testSlice[k], testSlice[k - 1])
            }
            val avgFlux = fluxSum / (testSlice.size - 1).coerceAtLeast(1)
            if (avgFlux < 0.040f) {
                // Reject stationary sound/noise
                continue
            }

            val (cost, stepCount) = computeDtwDistance(template, testSlice)
            if (cost >= Float.MAX_VALUE / 4f) continue

            val normalizedDist = cost / stepCount
            val conf = (1.0f - (normalizedDist / 0.40f)).coerceIn(0.0f, 1.0f)

            val maxAcceptableDistance = maxAcceptableDistance(sensitivity)
            val minAcceptableConfidence = minAcceptableConfidence(sensitivity)

            val isMatch = normalizedDist <= maxAcceptableDistance && conf >= minAcceptableConfidence

            if (conf > bestConfidence) {
                bestConfidence = conf
                bestNormalizedDist = normalizedDist
                bestMatched = isMatch
            } else if (isMatch && !bestMatched) {
                bestMatched = true
            }
        }

        return KeywordMatchResult(
            matched = bestMatched,
            confidence = bestConfidence,
            distance = bestNormalizedDist
        )
    }

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
        val template = mutableListOf<FloatArray>()
        for (chunk in audioChunks) {
            template.add(extractFeatureVector(chunk))
        }
        return template
    }

    companion object {
        // Sensitivity tuning:
        // sensitivity ranges from 0.10 to 0.95 (default 0.60).
        // Modulates the maximum allowable DTW distance and minimum confidence threshold.
        private fun maxAcceptableDistance(sensitivity: Float): Float = 0.28f + (sensitivity - 0.50f) * 0.12f

        private fun minAcceptableConfidence(sensitivity: Float): Float =
            (0.42f - (sensitivity - 0.50f) * 0.20f).coerceIn(0.25f, 0.75f)

        /** Minimum match confidence (0..1) required to trigger at the given sensitivity. */
        fun confidenceThreshold(sensitivity: Float): Float {
            val s = sensitivity.coerceIn(0.1f, 0.95f)
            // A match needs both conditions; the distance limit maps onto confidence via conf = 1 - dist / 0.40
            val distanceAsConfidence = 1.0f - maxAcceptableDistance(s) / 0.40f
            return maxOf(minAcceptableConfidence(s), distanceAsConfidence).coerceIn(0f, 1f)
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


