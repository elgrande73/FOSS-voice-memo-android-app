package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.AudioFixtures.Noise
import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.KeywordBehavior
import io.github.elgrande73.voicememo.detection.MemoDetectionEvent
import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import io.github.elgrande73.voicememo.detection.RecordingLifecycleState
import io.github.elgrande73.voicememo.detection.RecordingStopReason
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Activation/deactivation parity of the wake phrase, measured the way AudioMonitoringService runs the detector: every
 * frame in order, the start chime muting 0.7 s of frames (zero-filled), memo speech, a pause, the phrase again.
 * Voices: the 32 speaker-split corpus positives with the built-in reference (synthetic speech; docs section 18).
 * Phrases start at a random position within the 50 ms frame grid, as real speech does.
 */
class WakePhraseParityTest {

    private val rate = 16000
    private val voices = WakePhraseCorpus.DEV.positives + WakePhraseCorpus.TEST.positives
    private val conditions = listOf(-65.0 to Noise.PINK, -50.0 to Noise.PINK, -45.0 to Noise.WHITE).flatMap { c -> listOf(-12.0, -22.0).map { c to it } }

    private fun bg(seconds: Double, noiseDbfs: Double, noise: Noise, seed: Int) = AudioFixtures.scene(ShortArray(0), seconds, 0.0, noiseDbfs, noise, seed)

    private fun withBg(s: ShortArray, noiseDbfs: Double, noise: Noise, seed: Int) = AudioFixtures.scene(s, s.size / rate.toDouble(), 0.0, noiseDbfs, noise, seed)

    private fun detector(silenceTimeoutMs: Long = 2000L) = MemoTriggerDetector(keywordDetector = TemplateKeywordDetector()).apply {
        detectionMode = DetectionMode.KEYWORD_ONLY
        keywordBehavior = KeywordBehavior.START_AND_STOP
        this.silenceTimeoutMs = silenceTimeoutMs
        wakePhraseMinLevelDb = WakePhraseBenchmark.Setup().minLevelDb
        reset(startMonitoring = true)
    }

    /** Feeds frames like the service: [CHIME_MUTE_FRAMES] zero-filled frames after every start. Collects all events. */
    private class Session(val d: MemoTriggerDetector) {
        private var mute = 0
        val events = mutableListOf<MemoDetectionEvent>()
        fun feed(samples: ShortArray) {
            for (f in AudioFixtures.frames(samples)) {
                val muted = mute > 0
                if (muted) mute--
                val ev = d.processFrame(if (muted) ShortArray(f.size) else f, isFeedbackMuted = muted)
                if (ev.any { it is MemoDetectionEvent.TriggerStarted }) mute = CHIME_MUTE_FRAMES
                events += ev
            }
        }
        val starts get() = events.count { it is MemoDetectionEvent.TriggerStarted }
        val phraseStops get() = events.count { it is MemoDetectionEvent.RecordingCompleted && it.stopReason == RecordingStopReason.KEYWORD }
        val silenceEnds get() = events.count { it is MemoDetectionEvent.RecordingCompleted && it.stopReason == RecordingStopReason.SILENCE_TIMEOUT }
        val lateStops get() = events.count { it is MemoDetectionEvent.LateStopPhrase }
    }

    data class Parity(val starts: Int, val stops: Int, val trials: Int)

    /**
     * Unconditional parity: the same phrase rendition and conditions, once after silence while monitoring (start) and
     * once during a memo after speech and a pause (stop; the memo was started manually, so the stop trial does not
     * depend on the start trial).
     */
    private fun parity(pause: Double): Parity {
        var starts = 0; var stops = 0; var n = 0; var seed = 101
        for ((c, peak) in conditions) for (voice in voices) {
            val (nd, nt) = c
            n++
            val rnd = Random(seed)
            val phrase = AudioFixtures.atPeak(AudioFixtures.load(voice), peak)
            val start = Session(detector())
            start.feed(bg(2.0 + rnd.nextInt(800) / rate.toDouble(), nd, nt, seed) + withBg(phrase, nd, nt, seed + 1) + bg(1.0, nd, nt, seed + 2))
            if (start.starts > 0) starts++
            val stop = Session(detector(silenceTimeoutMs = 60_000L).apply { startManualRecording() })
            val memo = AudioFixtures.atPeak(AudioFixtures.load("neg_speech_${1 + seed % 5}"), peak)
            stop.feed(bg(1.0, nd, nt, seed + 3) + withBg(memo, nd, nt, seed + 4) + bg(pause + rnd.nextInt(800) / rate.toDouble(), nd, nt, seed + 5) +
                withBg(phrase, nd, nt, seed + 1) + bg(1.0, nd, nt, seed + 6))
            if (stop.phraseStops > 0) stops++
            seed += 10
        }
        return Parity(starts, stops, n)
    }

    @Test
    fun startingAndStoppingAreEquallyReliable() {
        for (pause in listOf(0.5, 1.5)) {
            val p = parity(pause)
            println("MEASURE parity pause=${pause}s: start ${p.starts}/${p.trials}, stop ${p.stops}/${p.trials}, difference ${p.starts - p.stops}")
            // Same detector, preprocessing and threshold for both: the difference is per-trial noise (frame phase), not
            // a systematic bias. Guard: within 5 % of the trials.
            assertTrue("start ${p.starts} vs stop ${p.stops}", abs(p.starts - p.stops) <= p.trials / 20)
        }
    }

    /** Phrase → chime → memo → [pause] → phrase, with the default 2 s silence timeout; only sessions whose memo started. */
    private fun conversation(pause: Double): List<Session> {
        val sessions = mutableListOf<Session>()
        var seed = 7
        for ((c, peak) in conditions) for (voice in voices) {
            val (nd, nt) = c
            val rnd = Random(seed)
            val phrase = AudioFixtures.atPeak(AudioFixtures.load(voice), peak)
            val memo = AudioFixtures.atPeak(AudioFixtures.load("neg_speech_${1 + seed % 5}"), peak)
            val s = Session(detector())
            s.feed(bg(2.0 + rnd.nextInt(800) / rate.toDouble(), nd, nt, seed) + withBg(phrase, nd, nt, seed + 1) + bg(1.0, nd, nt, seed + 2))
            if (s.starts == 1) {
                s.feed(withBg(memo, nd, nt, seed + 3) + bg(pause + rnd.nextInt(800) / rate.toDouble(), nd, nt, seed + 4) +
                    withBg(phrase, nd, nt, seed + 5) + bg(1.5, nd, nt, seed + 6))
                sessions += s
            }
            seed += 10
        }
        return sessions
    }

    @Test
    fun aStopPhraseAfterTheMemoEndedOnItsOwnNeverStartsANewMemo() {
        // Pausing longer than the silence timeout (2 s) before saying the stop phrase: the memo has already ended.
        // Before the fix the stop phrase then started a new memo (measured 31 of 36 such memos).
        val sessions = conversation(pause = 3.0)
        val endedBySilence = sessions.filter { it.silenceEnds > 0 }
        val restarted = sessions.count { it.starts > 1 }
        println("MEASURE late stop pause=3s: memos ${sessions.size}, stopped by the phrase ${sessions.count { it.phraseStops > 0 }}, " +
            "ended by silence first ${endedBySilence.size}, of those stop phrase recognised late ${endedBySilence.count { it.lateStops > 0 }}, " +
            "new memo started $restarted")
        assertTrue(endedBySilence.isNotEmpty())
        assertEquals("the stop phrase must not start a new memo", 0, restarted)
    }

    private fun enrolledDetector(behavior: KeywordBehavior, silenceTimeoutMs: Long) =
        MemoTriggerDetector(keywordDetector = TemplateKeywordDetector().apply { loadTemplates(WakePhraseBenchmark.templates(3)) }).apply {
            detectionMode = DetectionMode.KEYWORD_ONLY
            keywordBehavior = behavior
            this.silenceTimeoutMs = silenceTimeoutMs
            reset(startMonitoring = true)
        }

    @Test
    fun repeatedActivationAndDeactivationNeverGetStuck() {
        val phrase = AudioFixtures.atPeak(AudioFixtures.load("target_eval_1"), -12.0)
        val memo = AudioFixtures.atPeak(AudioFixtures.load("neg_speech_4"), -12.0)
        val d = enrolledDetector(KeywordBehavior.START_AND_STOP, 20_000L)
        val s = Session(d)
        s.feed(bg(2.0, -65.0, Noise.PINK, 1))
        repeat(5) { cycle ->
            s.feed(withBg(phrase, -65.0, Noise.PINK, 10 + cycle) + bg(1.0, -65.0, Noise.PINK, 20 + cycle))
            assertEquals("cycle $cycle started", RecordingLifecycleState.RECORDING, d.currentState)
            s.feed(withBg(memo, -65.0, Noise.PINK, 30 + cycle) + bg(0.8, -65.0, Noise.PINK, 40 + cycle) + withBg(phrase, -65.0, Noise.PINK, 50 + cycle) + bg(1.5, -65.0, Noise.PINK, 60 + cycle))
            assertEquals("cycle $cycle stopped", RecordingLifecycleState.MONITORING, d.currentState)
            // Background noise and ordinary speech in between change nothing
            s.feed(withBg(AudioFixtures.atPeak(AudioFixtures.load("neg_speech_2"), -12.0), -45.0, Noise.WHITE, 70 + cycle) + bg(2.0, -45.0, Noise.WHITE, 80 + cycle))
            assertEquals(RecordingLifecycleState.MONITORING, d.currentState)
        }
        assertEquals(5, s.starts)
        assertEquals(5, s.phraseStops)
        assertEquals("a phrase stop never opens the late-stop window", 0, s.lateStops)
    }

    @Test
    fun aNewMemoCanBeStartedOnceTheLateStopWindowHasPassed() {
        val phrase = AudioFixtures.atPeak(AudioFixtures.load("target_eval_1"), -12.0)
        val d = enrolledDetector(KeywordBehavior.START_AND_STOP, 1000L)
        val s = Session(d)
        s.feed(bg(2.0, -65.0, Noise.PINK, 1) + withBg(phrase, -65.0, Noise.PINK, 2) + bg(2.0, -65.0, Noise.PINK, 3))
        assertEquals("ended by silence", 1, s.silenceEnds)
        s.feed(withBg(phrase, -65.0, Noise.PINK, 4) + bg(1.0, -65.0, Noise.PINK, 5))
        assertEquals("phrase within the window: late stop, no new memo", 1, s.lateStops)
        assertEquals(1, s.starts)
        s.feed(bg(MemoTriggerDetector.LATE_STOP_GRACE_MS / 1000.0, -65.0, Noise.PINK, 6) + withBg(phrase, -65.0, Noise.PINK, 7) + bg(0.5, -65.0, Noise.PINK, 8))
        assertEquals("a phrase after the window starts a new memo", 2, s.starts)
        assertEquals(RecordingLifecycleState.RECORDING, d.currentState)
    }

    @Test
    fun startOnlyModeKeepsItsMeaning() {
        // "Start memo only": the phrase never stops a memo, and the late-stop rule does not apply
        val phrase = AudioFixtures.atPeak(AudioFixtures.load("target_eval_1"), -12.0)
        val d = enrolledDetector(KeywordBehavior.START_ONLY, 1000L)
        val s = Session(d)
        s.feed(bg(2.0, -65.0, Noise.PINK, 1) + withBg(phrase, -65.0, Noise.PINK, 2) + bg(2.0, -65.0, Noise.PINK, 3))
        s.feed(withBg(phrase, -65.0, Noise.PINK, 4) + bg(0.5, -65.0, Noise.PINK, 5))
        assertEquals(0, s.lateStops)
        assertEquals(2, s.starts)
    }

    companion object {
        /** Start chime (250 ms) plus the 450 ms output-latency tail that AudioMonitoringService mutes. */
        const val CHIME_MUTE_FRAMES = 14
    }
}
