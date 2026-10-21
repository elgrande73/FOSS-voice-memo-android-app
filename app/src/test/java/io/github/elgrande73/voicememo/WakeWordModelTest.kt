package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.AudioFixtures.Noise
import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.KeywordBehavior
import io.github.elgrande73.voicememo.detection.MemoDetectionEvent
import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import io.github.elgrande73.voicememo.detection.RecordingLifecycleState
import io.github.elgrande73.voicememo.detection.RecordingStopReason
import io.github.elgrande73.voicememo.wakephrase.BuiltInWakePhrase
import io.github.elgrande73.voicememo.wakephrase.PhraseVerifier
import io.github.elgrande73.voicememo.wakephrase.SpeechCheckedKeywordDetector
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import io.github.elgrande73.voicememo.wakephrase.WakePhraseText
import io.github.elgrande73.voicememo.wakephrase.WakeWordEngines
import io.github.elgrande73.voicememo.wakephrase.WakeWordModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Backend selection, fallback and the speech-checked detector's mechanics, with a fake speech model. */
class WakeWordModelTest {

    /** Fake speech model: returns [score] for every snippet and records the snippet lengths (seconds). */
    private class FakeVerifier(var score: Float = 0.9f, val delayMs: Long = 0, val accepts: (seconds: Double) -> Boolean = { true }) : PhraseVerifier {
        val calls = mutableListOf<Double>()
        var closed = false
        override fun score(samples: FloatArray): Float {
            if (delayMs > 0) Thread.sleep(delayMs)
            val seconds = samples.size / 16000.0
            synchronized(calls) { calls += seconds }
            return if (accepts(seconds)) score else 0.1f
        }
        override fun close() { closed = true }
    }

    private fun phrase(clip: String = "mango_target_1", peak: Double = -14.0) = AudioFixtures.atPeak(AudioFixtures.load(clip), peak)
    private fun silence(seconds: Double, seed: Int = 1) = AudioFixtures.scene(ShortArray(0), seconds, 0.0, -60.0, Noise.PINK, seed)
    private fun withBg(s: ShortArray, seed: Int = 2) = AudioFixtures.scene(s, s.size / 16000.0, 0.0, -60.0, Noise.PINK, seed)

    @Test
    fun idsArePersistedValuesAndStandardIsTheDefault() {
        assertEquals(WakeWordModel.STANDARD, WakeWordModel.DEFAULT)
        assertEquals(WakeWordModel.STANDARD, WakeWordModel.fromId(null))
        assertEquals(WakeWordModel.STANDARD, WakeWordModel.fromId("unknown"))
        assertEquals(WakeWordModel.SPEECH_CHECK, WakeWordModel.fromId("speech_check"))
        assertEquals("standard", WakeWordModel.STANDARD.id)
    }

    @Test
    fun standardIsTheTemplateMatcherAndTheSpeechCheckWrapsIt() {
        val standard = WakeWordEngines.create(WakeWordModel.STANDARD, BuiltInWakePhrase.MANGO_TANGO, emptyList(), null, { error("unused") })
        assertTrue(standard.detector is TemplateKeywordDetector)
        assertFalse(standard.status.isFallback)

        var askedFor: String? = null
        val checked = WakeWordEngines.create(WakeWordModel.SPEECH_CHECK, BuiltInWakePhrase.MANGO_TANGO, emptyList(), null, { askedFor = it; FakeVerifier() })
        assertTrue(checked.detector is SpeechCheckedKeywordDetector)
        assertEquals(WakeWordModel.SPEECH_CHECK, checked.status.active)
        assertEquals("Mango Tango", askedFor)
    }

    @Test
    fun recordedSamplesNeedTheirWordsOtherwiseStandardIsUsed() {
        val samples = WakePhraseBenchmark.templates(2)
        val missing = WakeWordEngines.create(WakeWordModel.SPEECH_CHECK, BuiltInWakePhrase.MANGO_TANGO, samples, "", { FakeVerifier() })
        assertTrue(missing.detector is TemplateKeywordDetector)
        assertTrue(missing.status.isFallback)
        assertTrue(missing.status.message!!.contains("needs the words"))
        assertEquals(2, (missing.detector as TemplateKeywordDetector).referenceCount) // samples preserved and used

        var askedFor: String? = null
        val custom = WakeWordEngines.create(WakeWordModel.SPEECH_CHECK, BuiltInWakePhrase.MANGO_TANGO, samples, " Ciao Linda ", { askedFor = it; FakeVerifier() })
        assertEquals("Ciao Linda", askedFor)
        assertEquals(2, (custom.detector as SpeechCheckedKeywordDetector).template.referenceCount)
    }

    @Test
    fun aModelThatFailsToLoadFallsBackToStandardWithTheReason() {
        val engine = WakeWordEngines.create(WakeWordModel.SPEECH_CHECK, BuiltInWakePhrase.MANGO_TANGO, emptyList(), null,
            { throw IllegalStateException("The speech model could not be loaded (not enough memory?).") })
        assertTrue(engine.detector is TemplateKeywordDetector)
        assertEquals(WakeWordModel.STANDARD, engine.status.active)
        assertTrue(engine.status.message!!.startsWith("The speech model could not be loaded"))
    }

    @Test
    fun unsupportedPhrasesAreExplained() {
        assertNull(WakePhraseText.problem("Mango Tango"))
        assertNull(WakePhraseText.problem("Ciao, Linda!"))
        assertNotNull(WakePhraseText.problem(""))
        assertNotNull(WakePhraseText.problem("Hi"))
        assertNotNull(WakePhraseText.problem("please start a new voice memo now"))
        assertEquals(listOf(" Mango Tango", " Mango tango", " mango tango", " Mango, Tango"), WakePhraseText.spellings("mango tango"))
    }

    @Test
    fun onlyUtterancesOfPhraseLengthAreChecked() {
        val verifier = FakeVerifier()
        val detector = SpeechCheckedKeywordDetector(TemplateKeywordDetector(), verifier, synchronous = true)
        val longSpeech = AudioFixtures.atPeak(AudioFixtures.load("neg_speech_3"), -14.0) + AudioFixtures.atPeak(AudioFixtures.load("neg_speech_1"), -14.0)
        val stream = silence(1.5) + withBg(phrase()) + silence(1.0, 3) + withBg(longSpeech, 4) + silence(1.0, 5)
        val results = AudioFixtures.frames(stream).map { detector.processFrame(it, 0f, 0f) }
        assertEquals("one check: the phrase; the 5 s sentence is not a candidate", 1, verifier.calls.size)
        assertTrue(verifier.calls[0] in 0.9..2.0)
        val match = results.single { it.matched }
        assertTrue(match.windowFrames >= AudioFixtures.load("mango_target_1").size / 800)
    }

    @Test
    fun startAndStopWithTheSamePhraseRemovesOnlyTheStopPhrase() {
        // Stands in for the speech model: recognizes the phrase (1.2 s with padding), not the memo sentence
        val verifier = FakeVerifier(accepts = { it < 1.6 })
        val trigger = MemoTriggerDetector(keywordDetector = SpeechCheckedKeywordDetector(TemplateKeywordDetector(), verifier, synchronous = true)).apply {
            detectionMode = DetectionMode.KEYWORD_ONLY
            keywordBehavior = KeywordBehavior.START_AND_STOP
            silenceTimeoutMs = 20_000L
            reset(startMonitoring = true)
        }
        val memoSpeech = AudioFixtures.atPeak(AudioFixtures.load("neg_speech_4"), -14.0)
        verifier.score = 0.9f
        val stream = silence(1.5) + withBg(phrase()) + silence(0.8, 3) + withBg(memoSpeech, 4) + silence(1.0, 5) + withBg(phrase(), 6) + silence(1.0, 7)
        var starts = 0
        var completed: MemoDetectionEvent.RecordingCompleted? = null
        AudioFixtures.frames(stream).forEach { frame ->
            for (e in trigger.processFrame(frame)) {
                if (e is MemoDetectionEvent.TriggerStarted) starts++
                if (e is MemoDetectionEvent.RecordingCompleted) completed = e
            }
        }
        assertEquals(1, starts)
        assertEquals(RecordingStopReason.KEYWORD, completed!!.stopReason)
        assertTrue(completed!!.durationMs >= memoSpeech.size * 1000L / 16000)
        assertEquals("phrase, memo sentence, phrase", 3, verifier.calls.size)
    }

    @Test
    fun asynchronousChecksNeverBlockTheAudioThreadAndStaleResultsAreDropped() {
        val verifier = FakeVerifier(delayMs = 300)
        val detector = SpeechCheckedKeywordDetector(TemplateKeywordDetector(), verifier, synchronous = false)
        val frames = AudioFixtures.frames(silence(1.5) + withBg(phrase()) + silence(0.4, 3))
        val start = System.nanoTime()
        frames.forEach { detector.processFrame(it, 0f, 0f) }
        assertTrue("audio thread waited", (System.nanoTime() - start) / 1e6 < 250)
        detector.resetWindow() // e.g. a trigger by another source: the running check's result is stale
        Thread.sleep(600)
        val after = AudioFixtures.frames(silence(0.5, 9)).map { detector.processFrame(it, 0f, 0f) }
        assertTrue(after.none { it.matched })
        assertEquals(1, verifier.calls.size)
        detector.close()
        assertTrue(verifier.closed)
    }

    @Test
    fun asynchronousResultTriggersExactlyOnce() {
        val verifier = FakeVerifier(delayMs = 100)
        val trigger = MemoTriggerDetector(keywordDetector = SpeechCheckedKeywordDetector(TemplateKeywordDetector(), verifier)).apply {
            detectionMode = DetectionMode.KEYWORD_ONLY
            reset(startMonitoring = true)
        }
        var starts = 0
        val frames = AudioFixtures.frames(silence(1.5) + withBg(phrase()) + silence(2.0, 3))
        for (frame in frames) {
            if (trigger.processFrame(frame).any { it is MemoDetectionEvent.TriggerStarted }) starts++
            Thread.sleep(5) // roughly real time for the worker
        }
        val latch = CountDownLatch(1)
        Thread { Thread.sleep(300); latch.countDown() }.start()
        latch.await(1, TimeUnit.SECONDS)
        for (frame in AudioFixtures.frames(silence(0.5, 5))) if (trigger.processFrame(frame).any { it is MemoDetectionEvent.TriggerStarted }) starts++
        assertEquals(1, starts)
    }

    @Test
    fun backendSwitchingWaitsUntilTheMemoEnds() {
        val trigger = MemoTriggerDetector(keywordDetector = TemplateKeywordDetector()).apply {
            detectionMode = DetectionMode.KEYWORD_ONLY
            matchConfidenceThreshold = 0.62f
            reset(startMonitoring = true)
        }
        val standard = trigger.keywordDetector
        val checked = SpeechCheckedKeywordDetector(TemplateKeywordDetector(), FakeVerifier())
        trigger.startManualRecording()
        assertEquals(RecordingLifecycleState.RECORDING, trigger.currentState)
        assertNull("refused during a memo", trigger.replaceKeywordDetector(checked))
        assertSame(standard, trigger.keywordDetector)
        trigger.stopManualRecording()
        assertSame(standard, trigger.replaceKeywordDetector(checked))
        assertSame(checked, trigger.keywordDetector)
        // The threshold is carried over: a 0.6 score does not match at 62 %
        assertEquals(0.62f, trigger.matchConfidenceThreshold, 0f)
        checked.close()
    }
}
