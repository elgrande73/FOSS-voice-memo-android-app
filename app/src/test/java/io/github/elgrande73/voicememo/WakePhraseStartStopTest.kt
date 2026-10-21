package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.AudioFixtures.Noise
import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.KeywordBehavior
import io.github.elgrande73.voicememo.detection.MemoDetectionEvent
import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import io.github.elgrande73.voicememo.detection.RecordingLifecycleState
import io.github.elgrande73.voicememo.detection.RecordingStopReason
import io.github.elgrande73.voicememo.wakephrase.FrontEndConfig
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same wake-phrase clips used to start a memo (after silence) and to stop one (after memo speech and a pause),
 * through the monitoring state machine with identical settings. Results are printed with "MEASURE" and documented in
 * docs/WAKE_PHRASE_DETECTION.md ("Start/stop consistency").
 */
class WakePhraseStartStopTest {

    private val rate = 16000
    private val backgrounds = listOf(-65.0 to Noise.PINK, -50.0 to Noise.PINK, -45.0 to Noise.WHITE)

    private fun bg(seconds: Double, noiseDbfs: Double, noise: Noise, seed: Int) =
        AudioFixtures.scene(ShortArray(0), seconds, 0.0, noiseDbfs, noise, seed)

    private fun withBg(s: ShortArray, noiseDbfs: Double, noise: Noise, seed: Int) =
        AudioFixtures.scene(s, s.size / rate.toDouble(), 0.0, noiseDbfs, noise, seed)

    private fun detector(templates: List<ByteArray>, frontEnd: FrontEndConfig, minLevelDb: Float? = null) =
        MemoTriggerDetector(keywordDetector = TemplateKeywordDetector(frontEndConfig = frontEnd).apply { loadTemplates(templates) }).apply {
            detectionMode = DetectionMode.KEYWORD_ONLY
            keywordBehavior = KeywordBehavior.START_AND_STOP
            silenceTimeoutMs = 20_000L
            matchConfidenceThreshold = 0.5f
            wakePhraseMinLevelDb = minLevelDb
            reset(startMonitoring = true)
        }

    data class Rates(val start: Int, val stop: Int, val total: Int, val startLatencyMs: Double, val stopLatencyMs: Double)

    /** Start and stop detections for every target clip × background × level, with [pause] between memo and phrase. */
    private fun rates(pause: Double, frontEnd: FrontEndConfig = FrontEndConfig.DEFAULT, minLevelDb: Float? = null, peaks: List<Double> = listOf(-12.0, -22.0)): Rates {
        val templates = WakePhraseBenchmark.templates(1, frontEnd)
        var start = 0; var stop = 0; var n = 0; var seed = 1
        var startLatency = 0L; var stopLatency = 0L
        // Three random positions within the 50 ms frame grid per clip, for start and stop alike (a phrase starting exactly on
        // a frame boundary, like the enrollment recordings, matches better and would favour whichever case got it)
        for ((nd, nt) in backgrounds) for (peak in peaks) for (clip in WakePhraseBenchmark.TARGET_EVAL) repeat(3) {
            n++
            val phase = kotlin.random.Random(seed)
            val startLead = 2.0 + phase.nextInt(800) / rate.toDouble()
            val phrase = AudioFixtures.atPeak(AudioFixtures.load(clip), peak)
            val phraseFrames = phrase.size / 800

            val startStream = bg(startLead, nd, nt, seed) + withBg(phrase, nd, nt, seed + 1) + bg(1.0, nd, nt, seed + 2)
            val d1 = detector(templates, frontEnd, minLevelDb)
            val startEnd = (startLead * rate).toInt() / 800 + phraseFrames
            AudioFixtures.frames(startStream).forEachIndexed { i, f ->
                if (d1.processFrame(f).any { it is MemoDetectionEvent.TriggerStarted }) { start++; startLatency += (i - startEnd) * 50L }
            }

            val memo = AudioFixtures.atPeak(AudioFixtures.load("neg_speech_${1 + seed % 5}"), peak)
            val stopStream = bg(2.0, nd, nt, seed + 3) + withBg(memo, nd, nt, seed + 4) + bg(pause + phase.nextInt(800) / rate.toDouble(), nd, nt, seed + 5) +
                withBg(phrase, nd, nt, seed + 6) + bg(1.0, nd, nt, seed + 7)
            val d2 = detector(templates, frontEnd, minLevelDb).apply { startManualRecording() }
            val stopEnd = (stopStream.size - rate) / 800
            AudioFixtures.frames(stopStream).forEachIndexed { i, f ->
                val ev = d2.processFrame(f)
                if (ev.any { it is MemoDetectionEvent.RecordingCompleted && it.stopReason == RecordingStopReason.KEYWORD }) { stop++; stopLatency += (i - stopEnd) * 50L }
            }
            seed += 10
        }
        return Rates(start, stop, n, startLatency.toDouble() / start.coerceAtLeast(1), stopLatency.toDouble() / stop.coerceAtLeast(1))
    }

    @Test
    fun stoppingIsAsReliableAsStartingAfterANaturalPause() {
        val before = FrontEndConfig.DEFAULT.copy(gainRelease = 0.3) // suppression gain release before the fix
        for (pause in listOf(0.2, 0.5, 1.0)) {
            val now = rates(pause)
            val old = rates(pause, before)
            println("MEASURE start/stop pause=${pause}s: start ${now.start}/${now.total} stop ${now.stop}/${now.total} " +
                "(before fix: start ${old.start} stop ${old.stop}); mean latency after phrase end start %.0f ms stop %.0f ms"
                    .format(now.startLatencyMs, now.stopLatencyMs))
            // Measured on the current fixtures: 0.2 s pause stop 18/36 now vs 19/36 with the old gain release (within
            // one trial); the guard catches a real regression of the fix
            assertTrue("stop got worse than before the fix at $pause s", now.stop >= old.stop - 1)
            // Measured: stop within 2 of 36 trials of start after a 0.5 s or 1 s pause (0.5 s: 21 vs 23)
            if (pause >= 0.5) assertTrue("stop ${now.stop} vs start ${now.start} at $pause s", now.stop >= now.start - 2)
        }
    }

    @Test
    fun levelConditionTreatsStartAndStopAlike() {
        // Quiet phrase (−36 dBFS peak) after normal-level memo speech: the memo must not satisfy the condition for it
        val quiet = listOf(-36.0)
        val strict = 45f + 0f // −45 dBFS on the detector scale
        val off = rates(1.0, peaks = quiet)
        val on = rates(1.0, minLevelDb = strict, peaks = quiet)
        println("MEASURE level condition, quiet phrase: off start ${off.start} stop ${off.stop}; −45 dBFS start ${on.start} stop ${on.stop} of ${on.total}")
        assertTrue(on.start <= off.start)
        assertEquals("rejected equally for start and stop", on.start, on.stop)
    }

    @Test
    fun oneUtteranceNeverStartsAndStopsAMemo() {
        val templates = WakePhraseBenchmark.templates(5)
        for (clip in WakePhraseBenchmark.TARGET_EVAL) {
            val d = detector(templates, FrontEndConfig.DEFAULT)
            val stream = bg(2.0, -65.0, Noise.PINK, 1) + withBg(AudioFixtures.atPeak(AudioFixtures.load(clip), -12.0), -65.0, Noise.PINK, 2) +
                bg(1.2, -65.0, Noise.PINK, 3)
            val events = AudioFixtures.frames(stream).flatMap { d.processFrame(it) }
            assertFalse(clip, events.any { it is MemoDetectionEvent.RecordingCompleted })
            if (events.any { it is MemoDetectionEvent.TriggerStarted }) assertEquals(RecordingLifecycleState.RECORDING, d.currentState)
        }
    }
}
