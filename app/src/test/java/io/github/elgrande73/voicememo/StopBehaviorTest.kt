package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.audio.WavWriter
import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.KeywordBehavior
import io.github.elgrande73.voicememo.detection.MemoDetectionEvent
import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import io.github.elgrande73.voicememo.detection.RecordingLifecycleState
import io.github.elgrande73.voicememo.detection.RecordingStopReason
import io.github.elgrande73.voicememo.detection.StopPhraseTrimmer
import io.github.elgrande73.voicememo.wakephrase.KeywordDetector
import io.github.elgrande73.voicememo.wakephrase.KeywordMatchResult
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sin

/** How a memo ends: keyword stops drop the spoken command, timeouts and manual stops keep every captured frame. */
class StopBehaviorTest {

    private val rate = AudioFixtures.SAMPLE_RATE

    /** A stream with known sample positions of its parts. */
    private class Session(val samples: ShortArray, val memoEnd: Int, val stopPhraseStart: Int)

    private fun concat(parts: List<ShortArray>): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var offset = 0
        parts.forEach { it.copyInto(out, offset); offset += it.size }
        return out
    }

    private fun silence(seconds: Double) = ShortArray((seconds * rate).toInt())

    private fun speech(name: String, peak: Double = -15.0) = AudioFixtures.atPeak(AudioFixtures.load(name), peak)

    /** background, start phrase, pause, memo words, [pause], stop phrase, background — with low-level noise added. */
    private fun keywordSession(
        pauseBeforeStop: Double,
        stopPhrase: String = "wake_phrase_b",
        offsetSamples: Int = 0,
        trailingSeconds: Double = 1.5
    ): Session {
        val parts = listOf(
            silence(2.0).copyOf((2.0 * rate).toInt() + offsetSamples),
            speech("wake_phrase_a"),
            silence(0.4),
            speech("other_speech", -12.0),
            speech("other_speech_en", -14.0),
            silence(pauseBeforeStop),
            speech(stopPhrase),
            silence(trailingSeconds)
        )
        val memoEnd = parts.take(5).sumOf { it.size }
        val stopStart = parts.take(6).sumOf { it.size }
        val clean = concat(parts)
        val noise = AudioFixtures.noise(clean.size, -60.0, seed = 3, type = AudioFixtures.Noise.PINK)
        val mixed = ShortArray(clean.size) { (clean[it] + noise[it]).toInt().coerceIn(-32768, 32767).toShort() }
        return Session(mixed, memoEnd, stopStart)
    }

    private fun keywordDetector(template: ByteArray, behavior: KeywordBehavior = KeywordBehavior.START_AND_STOP) =
        MemoTriggerDetector(keywordDetector = TemplateKeywordDetector().apply { loadTemplate(template) }).apply {
            detectionMode = DetectionMode.KEYWORD_ONLY
            thresholdDb = 45f
            // This test measures where the stop phrase is cut, not detection rates (WakePhraseStartStopTest does).
            // "wake_phrase_b" after a 150 ms pause scores 0.48 (0.50 before the gain-release change), so the
            // threshold sits just below the weakest of these stop phrases.
            matchConfidenceThreshold = 0.45f
            silenceTimeoutMs = 3000L
            keywordBehavior = behavior
            reset(startMonitoring = true)
        }

    /** Feeds the stream and returns the completed memo plus the stream index of its first sample. */
    private fun run(detector: MemoTriggerDetector, samples: ShortArray): Pair<MemoDetectionEvent.RecordingCompleted, Int> {
        val frames = AudioFixtures.frames(samples)
        var startFrame = -1
        frames.forEachIndexed { index, frame ->
            for (event in detector.processFrame(frame)) {
                if (event is MemoDetectionEvent.TriggerStarted) startFrame = index - event.preTriggerChunks.size + 1
                if (event is MemoDetectionEvent.RecordingCompleted) return event to startFrame * 800
            }
        }
        throw AssertionError("Recording did not complete")
    }

    @Test
    fun keywordStopRemovesTheStopPhraseButKeepsTheMemo() {
        val template = AudioFixtures.enrollTemplate()
        for (pause in listOf(0.15, 0.3, 0.6)) for (stopPhrase in listOf("wake_phrase_a", "wake_phrase_b")) for (offset in listOf(0, 137, 411)) {
            val session = keywordSession(pause, stopPhrase, offset)
            val (memo, firstSample) = run(keywordDetector(template), session.samples)
            val savedEnd = firstSample + memo.audioChunks.sumOf { it.size }
            val keptCommandMs = (savedEnd - session.stopPhraseStart) * 1000 / rate
            println("MEASURE keyword stop: pause=${(pause * 1000).toInt()}ms phrase=$stopPhrase offset=$offset end−commandStart=${keptCommandMs}ms removed=${memo.removedStopPhraseFrames} frames")

            assertEquals(RecordingStopReason.KEYWORD, memo.stopReason)
            assertTrue("memo words were cut (end $savedEnd < ${session.memoEnd})", savedEnd >= session.memoEnd)
            // At most one 50 ms frame of the command may remain
            assertTrue("kept $keptCommandMs ms of the command", keptCommandMs <= 50)
        }
    }

    @Test
    fun keptMemoAudioIsUnchangedExceptForTheFadeOut() {
        val session = keywordSession(0.3)
        val (memo, firstSample) = run(keywordDetector(AudioFixtures.enrollTemplate()), session.samples)
        val saved = memo.audioChunks.flatMap { it.toList() }
        val fadeStart = saved.size - StopPhraseTrimmer.FADE_SAMPLES
        for (i in 0 until fadeStart) assertEquals("sample $i", session.samples[firstSample + i], saved[i])
        // Fade-out: the last sample is (near) zero, no click
        assertTrue(abs(saved.last().toInt()) <= 1)
    }

    @Test
    fun speechResemblingTheWakePhraseIsNotRemovedWithoutAStopEvent() {
        // START_ONLY: saying the phrase again does not stop the memo, and nothing is trimmed when the timeout ends it
        val session = keywordSession(0.3, trailingSeconds = 4.0)
        val (memo, firstSample) = run(keywordDetector(AudioFixtures.enrollTemplate(), KeywordBehavior.START_ONLY), session.samples)
        assertEquals(RecordingStopReason.SILENCE_TIMEOUT, memo.stopReason)
        assertEquals(0, memo.removedStopPhraseFrames)
        val savedEnd = firstSample + memo.audioChunks.sumOf { it.size }
        assertTrue("phrase must be kept", savedEnd >= session.stopPhraseStart + speech("wake_phrase_b").size)
    }

    private fun loudnessDetector() = MemoTriggerDetector().apply {
        detectionMode = DetectionMode.AUTO_SOUND
        thresholdDb = 45f
        minTriggerDurationMs = 50L
        silenceTimeoutMs = 1000L
        reset(startMonitoring = true)
    }

    @Test
    fun silenceTimeoutKeepsTheFinalWordsAndEveryCapturedFrame() {
        val samples = concat(listOf(silence(1.0), speech("other_speech"), speech("other_speech_en"), silence(2.0)))
        val frames = AudioFixtures.frames(samples)
        val detector = loudnessDetector()
        var completed: MemoDetectionEvent.RecordingCompleted? = null
        var startFrame = -1
        var endFrame = -1
        frames.forEachIndexed { i, frame ->
            detector.processFrame(frame).forEach { e ->
                if (e is MemoDetectionEvent.TriggerStarted) startFrame = i - e.preTriggerChunks.size + 1
                if (e is MemoDetectionEvent.RecordingCompleted && completed == null) { completed = e; endFrame = i }
            }
        }
        val memo = completed!!
        assertEquals(RecordingStopReason.SILENCE_TIMEOUT, memo.stopReason)
        assertEquals(0, memo.removedStopPhraseFrames)
        // Every frame from the first saved one up to and including the frame on which the timeout fired
        assertEquals(endFrame - startFrame + 1, memo.audioChunks.size)
        memo.audioChunks.forEachIndexed { i, chunk -> assertArrayEquals(frames[startFrame + i], chunk) }
        val lastWordsEnd = (1.0 * rate).toInt() + speech("other_speech").size + speech("other_speech_en").size
        assertTrue((startFrame + memo.audioChunks.size) * 800 >= lastWordsEnd)
        assertEquals(memo.audioChunks.size * 50L, memo.durationMs)
    }

    @Test
    fun maxDurationIsMeasuredInCapturedAudioAndKeepsTheLastFrame() {
        val tone = ShortArray(800) { (sin(2 * Math.PI * 700 * it / rate) * 12000).toInt().toShort() }
        val detector = loudnessDetector().apply { maxMemoDurationMs = 2000L }
        var completed: MemoDetectionEvent.RecordingCompleted? = null
        var lastFed: ShortArray? = null
        for (i in 0 until 200) {
            // Distinct content per frame so the last frame can be identified
            val frame = tone.copyOf().also { it[0] = i.toShort() }
            lastFed = frame
            // Fed faster than real time (like a processing backlog): the limit must still count audio, not wall time
            detector.processFrame(frame).filterIsInstance<MemoDetectionEvent.RecordingCompleted>().firstOrNull()?.let { completed = it }
            if (completed != null) break
        }
        val memo = completed!!
        assertEquals(RecordingStopReason.MAX_DURATION, memo.stopReason)
        assertEquals(2000L, memo.durationMs)
        assertArrayEquals(lastFed, memo.audioChunks.last())
        assertEquals(0, memo.removedStopPhraseFrames)
    }

    @Test
    fun manualStopKeepsFramesCapturedBeforeItWasApplied() {
        val detector = loudnessDetector()
        val loud = ShortArray(800) { (sin(2 * Math.PI * 500 * it / rate) * 12000).toInt().toShort() }
        detector.processFrame(loud) // starts recording
        repeat(5) { detector.processFrame(loud) }
        assertTrue(detector.requestStop(RecordingStopReason.USER))
        assertFalse("duplicate stop request", detector.requestStop(RecordingStopReason.USER))
        // The frame that was already in the capture pipeline is still part of the memo
        val pending = loud.copyOf().also { it[0] = 4242 }
        val events = detector.processFrame(pending)
        val memo = events.filterIsInstance<MemoDetectionEvent.RecordingCompleted>().single()
        assertEquals(RecordingStopReason.USER, memo.stopReason)
        assertArrayEquals(pending, memo.audioChunks.last())
        assertEquals(RecordingLifecycleState.MONITORING, detector.currentState)
        assertNull("finalized exactly once", detector.finalizePendingStop())
        assertNull(detector.stopManualRecording())
    }

    @Test
    fun stalledCaptureIsFinalizedByTheWatchdogExactlyOnce() {
        val detector = loudnessDetector()
        val loud = ShortArray(800) { (sin(2 * Math.PI * 500 * it / rate) * 12000).toInt().toShort() }
        repeat(4) { detector.processFrame(loud) }
        detector.requestStop(RecordingStopReason.USER)
        val memo = detector.finalizePendingStop()
        assertNotNull(memo)
        assertEquals("all captured frames", 4, memo!!.audioChunks.size)
        assertNull(detector.finalizePendingStop())
        assertTrue(detector.processFrame(loud).none { it is MemoDetectionEvent.RecordingCompleted })
    }

    @Test
    fun microphoneFailureOrShutdownKeepsEverythingCapturedSoFar() {
        for (reason in listOf(RecordingStopReason.ERROR, RecordingStopReason.SERVICE_STOPPED)) {
            val detector = scriptedDetector(matchOnCall = 1)
            repeat(30) { detector.processFrame(loudFrame) }
            val memo = detector.stopManualRecording(reason)!!
            assertEquals(reason, memo.stopReason)
            assertEquals(30, memo.audioChunks.size)
            assertEquals(0, memo.removedStopPhraseFrames)
            assertNull("exactly once", detector.stopManualRecording(reason))
            assertNull(detector.finalizePendingStop())
        }
    }

    /** Matches on a chosen frame only, reporting a fixed matched-window length. */
    private class ScriptedKeywordDetector(private val matchOnCall: Int, private val windowFrames: Int) : KeywordDetector {
        var calls = 0
        override fun processFrame(frame: ShortArray, rms: Float, db: Float): KeywordMatchResult {
            calls++
            val match = calls == 1 || calls == matchOnCall
            return KeywordMatchResult(match, if (match) 0.9f else 0.1f, 0.05f, if (match) windowFrames else 0)
        }
        override fun setMatchConfidenceThreshold(threshold: Float) {}
        override fun loadTemplate(templateBytes: ByteArray) = true
        override fun hasTemplate() = true
        override fun hasCustomTemplate() = false
        override fun clearTemplate() {}
        override fun resetWindow() {}
    }

    private fun scriptedDetector(matchOnCall: Int, windowFrames: Int = 10) =
        MemoTriggerDetector(keywordDetector = ScriptedKeywordDetector(matchOnCall, windowFrames)).apply {
            detectionMode = DetectionMode.KEYWORD_ONLY
            keywordBehavior = KeywordBehavior.START_AND_STOP
            thresholdDb = 45f
            reset(startMonitoring = true)
        }

    private val loudFrame = ShortArray(800) { (sin(2 * Math.PI * 600 * it / 16000) * 12000).toInt().toShort() }

    @Test
    fun keywordAndMaxDurationOnTheSameFrameFinalizeOnceAsKeywordStop() {
        // Call 1 starts the memo (1 pre-trigger frame); after call n the memo holds n frames, so the 3 s limit and the
        // scripted stop phrase both fall on call 60
        val detector = scriptedDetector(matchOnCall = 60).apply { maxMemoDurationMs = 60 * 50L }
        val completions = (0 until 60).flatMap { detector.processFrame(loudFrame) }.filterIsInstance<MemoDetectionEvent.RecordingCompleted>()
        assertEquals(1, completions.size)
        assertEquals(RecordingStopReason.KEYWORD, completions.single().stopReason)
    }

    @Test
    fun stopButtonPressedWhileTheCommandIsRecognisedWinsAndKeepsTheAudio() {
        val detector = scriptedDetector(matchOnCall = 41)
        repeat(39) { detector.processFrame(loudFrame) }
        detector.requestStop(RecordingStopReason.USER)
        val memo = detector.processFrame(loudFrame).filterIsInstance<MemoDetectionEvent.RecordingCompleted>().single()
        assertEquals(RecordingStopReason.USER, memo.stopReason)
        assertEquals(0, memo.removedStopPhraseFrames)
    }

    @Test
    fun trimmingNeverReachesIntoTheStartOfTheMemoAndDoesNotModifyInput() {
        val frames = List(20) { i -> ShortArray(800) { ((i + 1) * 100).toShort() } }
        val copy = frames.map { it.copyOf() }
        val result = StopPhraseTrimmer.trim(frames, windowFrames = 50, minKeepFrames = 8)
        assertEquals(8, result.frames.size)
        assertEquals(12, result.removedFrames)
        frames.indices.forEach { assertArrayEquals(copy[it], frames[it]) }
        assertEquals(StopPhraseTrimmer.trim(frames, 0, 0).frames.size, 20)
    }

    @Test
    fun failedWavWriteLeavesExistingFileAndNoPartialOutput() {
        val dir = Files.createTempDirectory("wav").toFile()
        val target = File(dir, "memo.wav")
        WavWriter.writeWavFileAtomic(target, listOf(ShortArray(800) { 7 }))
        val original = target.readBytes()

        // A directory in place of the temporary file makes the write fail
        File(dir, "memo.wav.tmp").mkdirs()
        val failed = runCatching { WavWriter.writeWavFileAtomic(target, listOf(ShortArray(1600) { 9 })) }
        assertTrue(failed.isFailure)
        assertArrayEquals(original, target.readBytes())
        assertEquals(44 + 1600, target.length().toInt())
        dir.deleteRecursively()
    }
}
