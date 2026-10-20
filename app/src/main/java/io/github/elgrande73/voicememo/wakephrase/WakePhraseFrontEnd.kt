package io.github.elgrande73.voicememo.wakephrase

import io.github.elgrande73.voicememo.detection.FFT
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Privacy-safe measurements of the most recent frame (levels and statistics only, never audio).
 * Levels are in dBFS (0 dBFS = 16-bit full scale).
 */
class FrontEndDiagnostics {
    var inputRmsDbfs = -120f
    var inputPeakDbfs = -120f
    var clippedSamples = 0
    var nearSilence = true
    var noiseFloorDbfs = -120f
    var snrDb = 0f
    /** Mean attenuation applied by spectral noise suppression, in dB (0 = none). */
    var suppressionDb = 0f
    var outputLevelDbfs = -120f
    var featureMin = 0f
    var featureMax = 0f
    var processingNanos = 0L
}

/**
 * The single, authoritative audio front end for wake-phrase recognition. Enrollment, live monitoring and the Settings
 * phrase test all turn audio into features through this class, frame by frame, in stream order.
 *
 * Input: 16 kHz, mono, 16-bit PCM frames of [FRAME_SIZE] samples (50 ms). Per frame:
 * 1. Power spectrum: two Hann-windowed 512-point FFTs covering the whole 800-sample frame (0..511 and 288..799),
 *    averaged, grouped into [NUM_BANDS] linear 500 Hz bands (0–8 kHz), then lightly smoothed over time
 *    (0.7 × current + 0.3 × previous frame, causal, no added latency) to reduce sensitivity to frame alignment.
 * 2. Adaptive noise floor: per-band and broadband [NoiseFloorEstimator]s track the background automatically.
 * 3. Conservative spectral noise suppression (spectral subtraction on the 16 band powers):
 *    gain = max(1 − 2·noise/power, −18 dB), smoothed per band with fast attack (0.7/frame) and slower release
 *    (0.3/frame) so onsets and word endings are not chopped. Working on band powers (not the waveform) needs no
 *    resynthesis, adds no latency and acts exactly where the matcher looks.
 * 4. Level-invariant log spectrum: band levels in dB relative to the loudest band, limited to 40 dB.
 *    The feature therefore describes spectral shape, independent of speaking distance or microphone gain.
 * 5. L2 normalisation (as before), so the DTW matcher and its confidence scale are unchanged.
 *
 * Parameter choice (measured on the test fixtures with held-out noise levels and seeds, see
 * docs/WAKE_PHRASE_DETECTION.md): without suppression, unrelated speech matched almost as often as the wake phrase
 * once background noise was present; over-subtraction 2 with a −18 dB floor removed those false matches. 40 dB range
 * and 0.3 temporal smoothing gave ~4 % more detections than 45 dB / none, with no additional false matches.
 * A feature-domain noise gate and per-band SNR masking were evaluated and rejected because they suppressed genuine
 * matches.
 *
 * The adaptive stages can be bypassed ([FrontEndConfig.BYPASS]); the front end also bypasses them on its own
 * while the noise floor is still being initialised (first [NoiseFloorEstimator.INIT_FRAMES] frames).
 * The front end is stateful: feed every frame of a stream in order and call [reset] when a new stream starts.
 */
/**
 * Front-end options. Production uses [DEFAULT]; [BYPASS] disables noise suppression (fallback path, and the baseline
 * in robustness tests).
 */
data class FrontEndConfig(
    val spectralSuppression: Boolean = true,
    /** Maximum attenuation of a noise-dominated band (power gain; 0.0158 = −18 dB). */
    val gainFloor: Double = 0.0158,
    /** Noise over-subtraction factor. */
    val overSubtraction: Double = 2.0,
    /** Range of band levels below the loudest band that the feature represents. */
    val dynamicRangeDb: Double = 40.0,
    /** Weight of the previous frame in causal smoothing of band powers (0 = none). */
    val temporalSmoothing: Double = 0.3,
    /**
     * Weight of the speech-presence feature (frame SNR above the noise floor, 0..1, appended after the 16 shape
     * values); 0 = not appended.
     */
    val speechPresenceWeight: Double = 0.0,
    /** Noise-floor estimator: fraction of the dB gap closed per frame when the level falls (see NoiseFloorEstimator). */
    val noiseFallRate: Double = NoiseFloorEstimator.FALL_RATE,
    /** Noise-floor estimator: maximum rise caused by foreground (speech) frames, dB per second. */
    val noiseMaxRiseDbPerSecond: Double = NoiseFloorEstimator.MAX_RISE_DB_PER_SECOND,
    /** Noise-floor estimator: fraction of the dB gap closed per frame for noise-like frames (< 6 dB above it). */
    val noiseRiseRate: Double = NoiseFloorEstimator.NOISE_RISE_RATE,
    /** Per-frame smoothing of the suppression gain when it rises (opens) and falls (closes); 1.0 = no smoothing. */
    val gainAttack: Double = WakePhraseFrontEnd.GAIN_ATTACK,
    val gainRelease: Double = WakePhraseFrontEnd.GAIN_RELEASE
) {
    /** Number of values per feature vector. */
    val featureSize: Int get() = WakePhraseFrontEnd.NUM_BANDS + if (speechPresenceWeight > 0.0) 1 else 0

    companion object {
        val DEFAULT = FrontEndConfig()
        val BYPASS = FrontEndConfig(spectralSuppression = false)
    }
}

class WakePhraseFrontEnd(private val config: FrontEndConfig = FrontEndConfig.DEFAULT) {

    val diagnostics = FrontEndDiagnostics()

    private val fft = FFT(FFT_SIZE)
    private val hann = DoubleArray(FFT_SIZE) { i -> 0.5 * (1.0 - cos(2.0 * Math.PI * i / (FFT_SIZE - 1))) }
    private val real = DoubleArray(FFT_SIZE)
    private val imag = DoubleArray(FFT_SIZE)
    private val spectrum = DoubleArray(FFT_SIZE / 2 + 1)
    private val bandPower = DoubleArray(NUM_BANDS)
    private val cleanPower = DoubleArray(NUM_BANDS)
    private val previousBandPower = DoubleArray(NUM_BANDS)
    private var hasPreviousFrame = false
    private val smoothedGain = DoubleArray(NUM_BANDS) { 1.0 }
    private val frameMeanSquare = DoubleArray(1)
    private val bandNoise = NoiseFloorEstimator(NUM_BANDS, config.noiseFallRate, config.noiseMaxRiseDbPerSecond, config.noiseRiseRate)
    private val broadbandNoise = NoiseFloorEstimator(1, config.noiseFallRate, config.noiseMaxRiseDbPerSecond, config.noiseRiseRate)

    /** Broadband background level in dBFS, or null before the first non-silent frame. */
    val noiseFloorDbfs: Float?
        get() = if (broadbandNoise.framesObserved == 0) null else toDb(broadbandNoise.estimate(0)).toFloat()

    fun reset() {
        bandNoise.reset()
        broadbandNoise.reset()
        smoothedGain.fill(1.0)
        hasPreviousFrame = false
    }

    /** Converts one PCM frame into an L2-normalised feature vector of [NUM_BANDS] values. */
    fun process(frame: ShortArray): FloatArray {
        val start = System.nanoTime()
        measureInput(frame)
        computeBandPower(frame)
        if (config.temporalSmoothing > 0.0) {
            for (b in 0 until NUM_BANDS) {
                val raw = bandPower[b]
                if (hasPreviousFrame) {
                    bandPower[b] = (1.0 - config.temporalSmoothing) * raw + config.temporalSmoothing * previousBandPower[b]
                }
                previousBandPower[b] = raw
            }
            hasPreviousFrame = true
        }

        val noiseValid = bandNoise.update(bandPower)
        broadbandNoise.update(frameMeanSquare)
        // Adaptive stages only run once the noise floor is initialised (bypass during the first frames)
        val active = noiseValid && bandNoise.framesObserved > NoiseFloorEstimator.INIT_FRAMES
        val suppress = active && config.spectralSuppression

        var inputTotal = 0.0
        var outputTotal = 0.0
        var noiseTotal = 0.0
        for (b in 0 until NUM_BANDS) {
            val p = bandPower[b]
            val n = bandNoise.estimate(b)
            val targetGain = if (suppress && p > 0.0) max(config.gainFloor, 1.0 - config.overSubtraction * n / p) else 1.0
            val rate = if (targetGain > smoothedGain[b]) config.gainAttack else config.gainRelease
            smoothedGain[b] += (targetGain - smoothedGain[b]) * rate
            cleanPower[b] = p * (if (suppress) smoothedGain[b] else 1.0)
            inputTotal += p
            outputTotal += cleanPower[b]
            noiseTotal += n
        }

        val snrDb = if (noiseTotal > 0.0 && inputTotal > 0.0) 10.0 * log10(inputTotal / noiseTotal) else 0.0
        val features = shapeFeatures(cleanPower, snrDb, active)

        diagnostics.noiseFloorDbfs = noiseFloorDbfs ?: -120f
        diagnostics.snrDb = snrDb.toFloat()
        diagnostics.suppressionDb = if (inputTotal > 0.0 && outputTotal > 0.0) (10.0 * log10(outputTotal / inputTotal)).toFloat() else 0f
        diagnostics.outputLevelDbfs = (diagnostics.inputRmsDbfs + diagnostics.suppressionDb).coerceAtLeast(-120f)
        diagnostics.featureMin = features.min()
        diagnostics.featureMax = features.max()
        diagnostics.processingNanos = System.nanoTime() - start
        return features
    }

    private fun measureInput(frame: ShortArray) {
        var sumSquares = 0.0
        var peak = 0
        var clipped = 0
        for (s in frame) {
            val v = s.toInt()
            sumSquares += v.toDouble() * v
            val a = abs(v)
            if (a > peak) peak = a
            if (a >= CLIP_LEVEL) clipped++
        }
        val meanSquare = if (frame.isEmpty()) 0.0 else sumSquares / frame.size / (32768.0 * 32768.0)
        frameMeanSquare[0] = meanSquare
        diagnostics.inputRmsDbfs = toDb(meanSquare).toFloat()
        diagnostics.inputPeakDbfs = (20.0 * log10(max(peak, 1) / 32768.0)).toFloat()
        diagnostics.clippedSamples = clipped
        diagnostics.nearSilence = diagnostics.inputRmsDbfs < NEAR_SILENCE_DBFS
    }

    private fun computeBandPower(frame: ShortArray) {
        bandPower.fill(0.0)
        val windows = if (frame.size > FFT_SIZE) 2 else 1
        val binsPerBand = (FFT_SIZE / 2) / NUM_BANDS
        for (w in 0 until windows) {
            val offset = if (w == 0) 0 else frame.size - FFT_SIZE
            for (i in 0 until FFT_SIZE) {
                val idx = offset + i
                real[i] = if (idx < frame.size) frame[idx] / 32768.0 * hann[i] else 0.0
            }
            fft.powerSpectrumInPlace(real, imag, spectrum)
            for (b in 0 until NUM_BANDS) {
                var sum = 0.0
                for (k in b * binsPerBand until (b + 1) * binsPerBand) sum += spectrum[k]
                bandPower[b] += sum / windows
            }
        }
    }

    private fun shapeFeatures(power: DoubleArray, snrDb: Double, noiseKnown: Boolean): FloatArray {
        val features = FloatArray(config.featureSize)
        var maxDb = Double.NEGATIVE_INFINITY
        for (b in 0 until NUM_BANDS) maxDb = max(maxDb, toDb(power[b]))
        var sumSquares = 0.0
        for (b in 0 until NUM_BANDS) {
            val relative = (toDb(power[b]) - maxDb + config.dynamicRangeDb).coerceAtLeast(0.0)
            val value = relative / config.dynamicRangeDb + FEATURE_EPSILON
            features[b] = value.toFloat()
            sumSquares += value * value
        }
        val norm = sqrt(sumSquares).toFloat()
        for (b in 0 until NUM_BANDS) features[b] /= norm
        if (config.speechPresenceWeight > 0.0) {
            // Not normalised with the shape: the 16 shape values stay identical to the 16-value feature version
            val presence = if (noiseKnown) (snrDb / SPEECH_PRESENCE_FULL_SNR_DB).coerceIn(0.0, 1.0) else 1.0
            features[NUM_BANDS] = (presence * config.speechPresenceWeight).toFloat()
        }
        return features
    }

    private fun toDb(power: Double): Double = 10.0 * log10(max(power, 1e-12))

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SIZE = 800
        const val FFT_SIZE = 512
        const val NUM_BANDS = 16
        /** SNR (dB above the noise floor) at which the speech-presence feature reaches 1. */
        const val SPEECH_PRESENCE_FULL_SNR_DB = 30.0

        const val FEATURE_EPSILON = 0.01

        const val GAIN_ATTACK = 0.7
        /**
         * Was 0.3. With slow closing, the gains opened by memo speech stayed open into a short pause, so a stop phrase
         * following speech was processed differently from a start phrase following silence (measured in
         * WakePhraseStartStopTest; docs/WAKE_PHRASE_DETECTION.md, "Start/stop consistency").
         */
        const val GAIN_RELEASE = 0.5


        const val CLIP_LEVEL = 32700
        const val NEAR_SILENCE_DBFS = -70f
    }
}
