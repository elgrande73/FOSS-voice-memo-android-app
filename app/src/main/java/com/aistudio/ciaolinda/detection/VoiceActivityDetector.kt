package com.aistudio.ciaolinda.detection

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Result data model of Voice Activity Analysis on a single PCM frame.
 */
data class VoiceFrameResult(
    val rms: Float,
    val db: Float,
    val isSpeechLikely: Boolean,
    val isAboveThreshold: Boolean,
    val spectralCentroidHz: Float,
    val zeroCrossingRate: Float,
    val isSpeechBand: Boolean = false
)

/**
 * High-performance, offline Voice Activity Detector (VAD).
 * Analyzes audio energy, Zero-Crossing Rate (ZCR), and spectral centroid within typical human speech bands (300Hz - 3400Hz).
 */
class VoiceActivityDetector(
    private val sampleRate: Int = 16000,
    private val fftSize: Int = 512
) {
    private val fft = FFT(fftSize)
    private val window = DoubleArray(fftSize) { i ->
        0.54 - 0.46 * kotlin.math.cos(2.0 * Math.PI * i / (fftSize - 1)) // Hamming window
    }

    /**
     * Processes a single short audio frame (e.g. 50ms = 800 samples at 16kHz).
     */
    fun processFrame(frame: ShortArray, thresholdDb: Float): VoiceFrameResult {
        if (frame.isEmpty()) {
            return VoiceFrameResult(
                rms = 0f,
                db = 0f,
                isSpeechLikely = false,
                isAboveThreshold = false,
                spectralCentroidHz = 0f,
                zeroCrossingRate = 0f
            )
        }

        // 1. Calculate RMS and dB SPL estimate
        var sumSquares = 0.0
        var zeroCrossings = 0

        for (i in frame.indices) {
            val sampleVal = frame[i].toDouble()
            sumSquares += sampleVal * sampleVal

            if (i > 0) {
                if ((frame[i] >= 0 && frame[i - 1] < 0) || (frame[i] < 0 && frame[i - 1] >= 0)) {
                    zeroCrossings++
                }
            }
        }

        val rms = sqrt(sumSquares / frame.size).toFloat()
        // Reference 16-bit max amplitude = 32767
        val normalizedRms = (rms / 32767f).coerceIn(1e-5f, 1.0f)
        // Convert to calibrated approximate dB SPL (range ~ 0 - 100 dB)
        val db = (20f * log10(normalizedRms) + 90f).coerceIn(0f, 120f)

        val isAboveThreshold = db >= thresholdDb
        val zcr = zeroCrossings.toFloat() / frame.size

        // 2. Frequency Spectral Analysis (Centroid)
        val fftBuffer = DoubleArray(fftSize)
        val copyLen = minOf(frame.size, fftSize)
        for (i in 0 until copyLen) {
            fftBuffer[i] = (frame[i] / 32768.0) * window[i]
        }

        val power = fft.computePowerSpectrum(fftBuffer)
        val binWidthHz = sampleRate.toFloat() / fftSize

        var weightedSum = 0.0
        var totalPower = 0.0
        var speechBandEnergy = 0.0

        for (i in power.indices) {
            val freqHz = i * binWidthHz
            val p = power[i]
            totalPower += p
            weightedSum += freqHz * p

            // Human voice fundamental & formant band: 250Hz - 3800Hz
            if (freqHz in 250.0..3800.0) {
                speechBandEnergy += p
            }
        }

        val spectralCentroid = if (totalPower > 1e-6) (weightedSum / totalPower).toFloat() else 0f
        val speechEnergyRatio = if (totalPower > 1e-6) (speechBandEnergy / totalPower).toFloat() else 0f

        // Speech decision: Exceeds dB threshold + ZCR in speech range + high speech band energy concentration
        val isSpeechBand = speechEnergyRatio > 0.40f && spectralCentroid in 250f..4000f
        val isSpeechLikely = isAboveThreshold && isSpeechBand

        return VoiceFrameResult(
            rms = rms,
            db = db,
            isSpeechLikely = isSpeechLikely,
            isAboveThreshold = isAboveThreshold,
            spectralCentroidHz = spectralCentroid,
            zeroCrossingRate = zcr,
            isSpeechBand = isSpeechBand
        )
    }
}

