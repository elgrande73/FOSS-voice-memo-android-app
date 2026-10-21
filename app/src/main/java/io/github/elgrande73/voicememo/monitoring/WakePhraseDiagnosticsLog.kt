package io.github.elgrande73.voicememo.monitoring

import android.util.Log
import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import kotlin.math.max

/**
 * Debug-build diagnostics for the monitoring pipeline (created only when BuildConfig.DEBUG). Once per second it logs a
 * summary to logcat under the tag [TAG]; it never logs or stores audio samples or recognised content.
 *
 * Units: levels in dBFS (0 = 16-bit full scale); "score" is the raw DTW similarity score of the wake-phrase matcher
 * (0..1, not a probability); "shown" is the peak-hold value displayed on the dashboard; "thr" is the configured Match
 * confidence threshold on the same scale; times in microseconds per 50 ms frame.
 */
class WakePhraseDiagnosticsLog(private val framesPerSummary: Int = 20) {

    private var frames = 0
    private var rmsSum = 0.0
    private var peakDbfs = -120f
    private var clipped = 0
    private var nearSilent = 0
    private var suppressionSum = 0.0
    private var featureMin = Float.MAX_VALUE
    private var featureMax = 0f
    private var bestScore = 0f
    private var bestDistance = Float.MAX_VALUE
    private var processingSumNanos = 0L
    private var processingMaxNanos = 0L

    fun record(frame: ShortArray, detector: MemoTriggerDetector, processingNanos: Long) {
        val keyword = detector.keywordDetector as? TemplateKeywordDetector
        val d = keyword?.diagnostics
        frames++
        if (d != null) {
            rmsSum += d.inputRmsDbfs
            peakDbfs = max(peakDbfs, d.inputPeakDbfs)
            clipped += d.clippedSamples
            if (d.nearSilence) nearSilent++
            suppressionSum += d.suppressionDb
            featureMin = minOf(featureMin, d.featureMin)
            featureMax = max(featureMax, d.featureMax)
        }
        detector.lastKeywordResult?.let {
            bestScore = max(bestScore, it.confidence)
            bestDistance = minOf(bestDistance, it.distance)
        }
        processingSumNanos += processingNanos
        processingMaxNanos = max(processingMaxNanos, processingNanos)

        if (frames >= framesPerSummary) {
            Log.d(
                TAG,
                "fmt=16kHz/mono/PCM16 frame=${frame.size} | in rms=%.1f peak=%.1f clipped=%d nearSilent=%d/%d | noise=%.1f supp=%.1fdB | feat=[%.3f..%.3f] tpl=%d | score=%.2f dist=%.3f shown=%.2f thr=%.2f | proc avg=%dus max=%dus".format(
                    rmsSum / frames, peakDbfs, clipped, nearSilent, frames,
                    keyword?.noiseFloorDbfs ?: -120f, suppressionSum / frames,
                    if (featureMin == Float.MAX_VALUE) 0f else featureMin, featureMax,
                    keyword?.templateFrameCount ?: 0,
                    bestScore, if (bestDistance == Float.MAX_VALUE) 0f else bestDistance,
                    detector.keywordConfidence, detector.matchConfidenceThreshold,
                    processingSumNanos / frames / 1000, processingMaxNanos / 1000
                )
            )
            reset()
        }
    }

    private fun reset() {
        frames = 0
        rmsSum = 0.0
        peakDbfs = -120f
        clipped = 0
        nearSilent = 0
        suppressionSum = 0.0
        featureMin = Float.MAX_VALUE
        featureMax = 0f
        bestScore = 0f
        bestDistance = Float.MAX_VALUE
        processingSumNanos = 0L
        processingMaxNanos = 0L
    }

    companion object {
        const val TAG = "WakePhraseDiag"
    }
}
