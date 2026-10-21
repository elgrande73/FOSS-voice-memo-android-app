package io.github.elgrande73.voicememo

import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.MemoDetectionEvent
import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import io.github.elgrande73.voicememo.detection.RecordingLifecycleState
import io.github.elgrande73.voicememo.detection.VoiceActivityDetector
import io.github.elgrande73.voicememo.wakephrase.KeywordDetector
import io.github.elgrande73.voicememo.wakephrase.KeywordMatchResult
import io.github.elgrande73.voicememo.wakephrase.BuiltInWakePhrase
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/** Detection engine tests; "Ciao Linda" is the default wake phrase the engine must keep recognizing. */
class DetectionEngineTest {

    @Test
    fun testVoiceActivityDetectorSilence() {
        val vad = VoiceActivityDetector()
        val silence = ShortArray(800) { 0 }
        val result = vad.processFrame(silence, thresholdDb = 45f)
        assertFalse("Silence should not trigger speech", result.isSpeechLikely)
        assertTrue("dB should be low for silence", result.db < 30f)
    }

    @Test
    fun testVoiceActivityDetectorSineWaveSpeech() {
        val vad = VoiceActivityDetector()
        val sampleRate = 16000
        val freq = 1000.0 // 1kHz voice tone
        val tone = ShortArray(800) { i ->
            (sin(2.0 * Math.PI * freq * i / sampleRate) * 16000.0).toInt().toShort()
        }
        val result = vad.processFrame(tone, thresholdDb = 45f)
        assertTrue("Loud 1kHz tone should exceed threshold", result.db > 60f)
        assertTrue("Threshold should be met for 1kHz tone", result.isAboveThreshold)
    }

    @Test
    fun testKeywordDetectorBuiltInTemplateAndMatching() {
        val keywordDetector = TemplateKeywordDetector(builtInPhrase = BuiltInWakePhrase.CIAO_LINDA)
        assertTrue("Default built-in template should be available", keywordDetector.hasTemplate())
        assertFalse("Custom user template should not exist initially", keywordDetector.hasCustomTemplate())

        val ciaoLindaAudio = TemplateKeywordDetector.generateCanonicalCiaoLindaAudio(amplitude = 15000.0)
        assertTrue("Audio chunks generated", ciaoLindaAudio.isNotEmpty())

        var detected = false
        for (chunk in ciaoLindaAudio) {
            val result = keywordDetector.processFrame(chunk, rms = 0.5f, db = 65f)
            if (result.matched) {
                detected = true
                break
            }
        }
        assertTrue("Canonical Ciao Linda audio above threshold should match built-in template", detected)
    }

    @Test
    fun testKeywordDetectorRejectsUnrelatedSpeech() {
        val keywordDetector = TemplateKeywordDetector(builtInPhrase = BuiltInWakePhrase.CIAO_LINDA)
        // Generate 25 chunks of unrelated monotone tone (e.g. 440 Hz dial tone / siren)
        val sampleRate = 16000
        var falseTrigger = false
        for (c in 0 until 25) {
            val chunk = ShortArray(800) { i ->
                (sin(2.0 * Math.PI * 440.0 * (c * 800 + i) / sampleRate) * 16000.0).toInt().toShort()
            }
            val result = keywordDetector.processFrame(chunk, rms = 0.5f, db = 70f)
            if (result.matched) {
                falseTrigger = true
            }
        }
        assertFalse("Loud unrelated 440Hz tone should NOT trigger Ciao Linda detector", falseTrigger)
    }

    @Test
    fun testWakePhraseMatcherSeesEveryFrameButQuietAudioNeverTriggers() {
        // Spy matcher that always reports a match: only the loudness criterion can prevent a trigger
        var invocationCount = 0
        val spyKeywordDetector = object : KeywordDetector {
            override fun processFrame(frame: ShortArray, rms: Float, db: Float): KeywordMatchResult {
                invocationCount++
                return KeywordMatchResult(matched = true, confidence = 0.99f, distance = 0.01f)
            }
            override fun setMatchConfidenceThreshold(threshold: Float) {}
            override fun clearTemplate() {}
            override fun loadTemplate(templateBytes: ByteArray): Boolean = true
            override fun hasTemplate(): Boolean = true
            override fun hasCustomTemplate(): Boolean = false
            override fun resetWindow() {}
        }

        val triggerDetector = MemoTriggerDetector(keywordDetector = spyKeywordDetector)
        triggerDetector.detectionMode = DetectionMode.KEYWORD_ONLY
        triggerDetector.wakePhraseMinLevelDb = 50.0f
        triggerDetector.reset(startMonitoring = true)

        val silence = ShortArray(800) { 0 }
        for (i in 0 until 10) {
            val events = triggerDetector.processFrame(silence)
            assertFalse(events.any { it is MemoDetectionEvent.TriggerStarted })
        }

        // Contiguous context: the matcher receives every frame, like the Settings phrase test
        assertEquals(10, invocationCount)
        assertEquals(RecordingLifecycleState.MONITORING, triggerDetector.currentState)
    }

    @Test
    fun testWakePhraseOnly_LoudSpeechWithoutWakePhrase_DoesNotRecord() {
        val triggerDetector = MemoTriggerDetector(keywordDetector = TemplateKeywordDetector(builtInPhrase = BuiltInWakePhrase.CIAO_LINDA))
        triggerDetector.detectionMode = DetectionMode.KEYWORD_ONLY
        triggerDetector.thresholdDb = 45.0f
        triggerDetector.reset(startMonitoring = true)

        // Feed loud unrelated sound frames (1000 Hz tone)
        val loudTone = ShortArray(800) { i ->
            (sin(2.0 * Math.PI * 1000.0 * i / 16000.0) * 15000.0).toInt().toShort()
        }

        for (i in 0 until 15) {
            val events = triggerDetector.processFrame(loudTone)
            assertFalse(
                "Loud speech without Ciao Linda must NOT start recording in KEYWORD_ONLY mode",
                events.any { it is MemoDetectionEvent.TriggerStarted }
            )
        }

        assertEquals(
            "State must remain MONITORING when wake phrase is absent",
            RecordingLifecycleState.MONITORING,
            triggerDetector.currentState
        )
    }

    @Test
    fun testWakePhraseOnly_WakePhraseBelowThreshold_DoesNotRecord() {
        val triggerDetector = MemoTriggerDetector(keywordDetector = TemplateKeywordDetector(builtInPhrase = BuiltInWakePhrase.CIAO_LINDA))
        triggerDetector.detectionMode = DetectionMode.KEYWORD_ONLY
        triggerDetector.wakePhraseMinLevelDb = 55.0f // Optional minimum phrase level (dBFS + 90), set high
        triggerDetector.reset(startMonitoring = true)

        // Ciao Linda phrase spoken as quiet whisper (amplitude 100, dB ~ 22dB)
        val quietCiaoLinda = TemplateKeywordDetector.generateCanonicalCiaoLindaAudio(amplitude = 100.0)

        for (chunk in quietCiaoLinda) {
            val events = triggerDetector.processFrame(chunk)
            assertFalse(
                "Wake phrase below loudness threshold must NOT trigger recording",
                events.any { it is MemoDetectionEvent.TriggerStarted }
            )
        }

        assertEquals(
            "State must remain MONITORING for quiet phrase below threshold",
            RecordingLifecycleState.MONITORING,
            triggerDetector.currentState
        )
    }

    @Test
    fun testWakePhraseOnly_WakePhraseAboveThreshold_StartsRecording() {
        val triggerDetector = MemoTriggerDetector(keywordDetector = TemplateKeywordDetector(builtInPhrase = BuiltInWakePhrase.CIAO_LINDA))
        triggerDetector.detectionMode = DetectionMode.KEYWORD_ONLY
        triggerDetector.thresholdDb = 45.0f
        triggerDetector.reset(startMonitoring = true)

        // Ciao Linda spoken clearly above threshold (~65 dB)
        val ciaoLinda = TemplateKeywordDetector.generateCanonicalCiaoLindaAudio(amplitude = 15000.0)

        var triggerStarted = false
        var preTriggerCaptured = false
        for (chunk in ciaoLinda) {
            val events = triggerDetector.processFrame(chunk)
            for (ev in events) {
                if (ev is MemoDetectionEvent.TriggerStarted) {
                    triggerStarted = true
                    if (ev.preTriggerChunks.isNotEmpty()) {
                        preTriggerCaptured = true
                    }
                }
            }
            if (triggerStarted) break
        }

        assertTrue("Wake phrase above threshold MUST trigger recording", triggerStarted)
        assertTrue("Pre-trigger buffer MUST contain buffered chunks before trigger", preTriggerCaptured)
        assertEquals(RecordingLifecycleState.RECORDING, triggerDetector.currentState)
    }

    @Test
    fun testLoudnessOnly_LoudSpeechStartsRecordingWithoutWakePhrase() {
        val triggerDetector = MemoTriggerDetector(keywordDetector = TemplateKeywordDetector(builtInPhrase = BuiltInWakePhrase.CIAO_LINDA))
        triggerDetector.detectionMode = DetectionMode.AUTO_SOUND
        triggerDetector.thresholdDb = 45.0f
        triggerDetector.minTriggerDurationMs = 50L
        triggerDetector.reset(startMonitoring = true)

        // Feed arbitrary loud sound
        val loudTone = ShortArray(800) { i ->
            (sin(2.0 * Math.PI * 600.0 * i / 16000.0) * 15000.0).toInt().toShort()
        }

        val events = triggerDetector.processFrame(loudTone)
        assertTrue(
            "Loudness Only mode must start recording on sound exceeding threshold without wake phrase",
            events.any { it is MemoDetectionEvent.TriggerStarted }
        )
        assertEquals(RecordingLifecycleState.RECORDING, triggerDetector.currentState)
    }

    @Test
    fun testPreTriggerBufferCapturesFirstWords() {
        val triggerDetector = MemoTriggerDetector(keywordDetector = TemplateKeywordDetector(builtInPhrase = BuiltInWakePhrase.CIAO_LINDA))
        triggerDetector.detectionMode = DetectionMode.KEYWORD_ONLY
        triggerDetector.thresholdDb = 40.0f
        triggerDetector.preTriggerDurationMs = 1000L
        triggerDetector.reset(startMonitoring = true)

        // 1. Feed pre-phrase background padding
        val prePhraseNoise = ShortArray(800) { (it % 10).toShort() }
        for (i in 0 until 5) {
            triggerDetector.processFrame(prePhraseNoise)
        }

        // 2. Feed "Ciao Linda"
        val ciaoLinda = TemplateKeywordDetector.generateCanonicalCiaoLindaAudio(amplitude = 16000.0)
        var triggerEvent: MemoDetectionEvent.TriggerStarted? = null

        for (chunk in ciaoLinda) {
            val events = triggerDetector.processFrame(chunk)
            val started = events.filterIsInstance<MemoDetectionEvent.TriggerStarted>().firstOrNull()
            if (started != null) {
                triggerEvent = started
                break
            }
        }

        assertNotNull("Recording must be triggered", triggerEvent)
        assertTrue(
            "Pre-trigger buffer must capture at least 4 preceding chunks so initial speech is not cut off",
            triggerEvent!!.preTriggerChunks.size >= 4
        )

        // 3. Subsequent words immediately streamed into recording
        val firstWordsChunk = ShortArray(800) { (sin(it.toDouble()) * 8000.0).toInt().toShort() }
        val streamEvents = triggerDetector.processFrame(firstWordsChunk)
        assertTrue(
            "First words after wake phrase must be immediately recorded as ChunkRecorded events",
            streamEvents.any { it is MemoDetectionEvent.ChunkRecorded }
        )
    }

    @Test
    fun testCustomTemplateTrainingAndClearing() {
        val keywordDetector = TemplateKeywordDetector(builtInPhrase = BuiltInWakePhrase.CIAO_LINDA)
        val trainingChunks = mutableListOf<ShortArray>()
        for (c in 0 until 10) {
            val chunk = ShortArray(800) { i ->
                (sin(2.0 * Math.PI * (400.0 + c * 50.0) * i / 16000.0) * 12000.0).toInt().toShort()
            }
            trainingChunks.add(chunk)
        }

        val success = keywordDetector.recordTemplate(trainingChunks)
        assertTrue("Custom voice template enrollment should succeed", success)
        assertTrue("Custom template flag should be true", keywordDetector.hasCustomTemplate())

        val data = keywordDetector.getTemplateData()
        assertNotNull("Template data should be serializable", data)

        keywordDetector.clearTemplate()
        assertFalse("Custom template should be cleared", keywordDetector.hasCustomTemplate())
        assertTrue("Default template is still available after clearing custom", keywordDetector.hasTemplate())
    }

    @Test
    fun testRecordingDoesNotStopOnShortPauses() {
        val triggerDetector = MemoTriggerDetector(keywordDetector = TemplateKeywordDetector(builtInPhrase = BuiltInWakePhrase.CIAO_LINDA))
        triggerDetector.detectionMode = DetectionMode.AUTO_SOUND
        triggerDetector.thresholdDb = 45.0f
        triggerDetector.minTriggerDurationMs = 50L
        triggerDetector.silenceTimeoutMs = 1500L // 30 chunks of 50ms
        triggerDetector.reset(startMonitoring = true)

        val loudSpeechChunk = ShortArray(800) { i ->
            (sin(2.0 * Math.PI * 800.0 * i / 16000.0) * 15000.0).toInt().toShort()
        }
        val silentChunk = ShortArray(800) { 0 }

        // 1. Trigger recording
        val startEvents = triggerDetector.processFrame(loudSpeechChunk)
        assertTrue(startEvents.any { it is MemoDetectionEvent.TriggerStarted })
        assertEquals(RecordingLifecycleState.RECORDING, triggerDetector.currentState)

        // 2. Simulate short pause (8 silent chunks = 400ms pause)
        for (i in 0 until 8) {
            val pauseEvents = triggerDetector.processFrame(silentChunk)
            assertEquals("Must stay in RECORDING during short pause", RecordingLifecycleState.RECORDING, triggerDetector.currentState)
            assertFalse("Must NOT emit RecordingCompleted during short pause", pauseEvents.any { it is MemoDetectionEvent.RecordingCompleted })
        }

        // 3. User speaks next word -> Resets silence counter
        val resumeEvents = triggerDetector.processFrame(loudSpeechChunk)
        assertEquals(RecordingLifecycleState.RECORDING, triggerDetector.currentState)
        assertFalse(resumeEvents.any { it is MemoDetectionEvent.RecordingCompleted })

        // 4. Another short pause (10 silent chunks = 500ms pause)
        for (i in 0 until 10) {
            val pauseEvents2 = triggerDetector.processFrame(silentChunk)
            assertEquals("Must stay in RECORDING during second short pause", RecordingLifecycleState.RECORDING, triggerDetector.currentState)
            assertFalse("Must NOT emit RecordingCompleted", pauseEvents2.any { it is MemoDetectionEvent.RecordingCompleted })
        }

        // 5. User speaks final word
        triggerDetector.processFrame(loudSpeechChunk)
        assertEquals(RecordingLifecycleState.RECORDING, triggerDetector.currentState)
    }

    @Test
    fun testRecordingDoesNotStopOnSpeechNearThreshold() {
        val triggerDetector = MemoTriggerDetector(keywordDetector = TemplateKeywordDetector(builtInPhrase = BuiltInWakePhrase.CIAO_LINDA))
        triggerDetector.detectionMode = DetectionMode.AUTO_SOUND
        triggerDetector.thresholdDb = 48.0f
        triggerDetector.minTriggerDurationMs = 50L
        triggerDetector.silenceTimeoutMs = 1000L // 20 chunks of 50ms
        triggerDetector.reset(startMonitoring = true)

        val loudStartChunk = ShortArray(800) { i ->
            (sin(2.0 * Math.PI * 700.0 * i / 16000.0) * 16000.0).toInt().toShort()
        }
        // Soft conversational speech in human voice frequency band (800Hz) at ~38-42 dB (amplitude ~ 500-1000)
        val softSpeechChunk = ShortArray(800) { i ->
            (sin(2.0 * Math.PI * 800.0 * i / 16000.0) * 800.0).toInt().toShort()
        }

        // 1. Trigger recording
        triggerDetector.processFrame(loudStartChunk)
        assertEquals(RecordingLifecycleState.RECORDING, triggerDetector.currentState)

        // 2. Feed soft speech frames near silence floor for 30 consecutive chunks (1500ms > 1000ms timeout)
        for (i in 0 until 30) {
            val events = triggerDetector.processFrame(softSpeechChunk)
            assertEquals("Soft voice near threshold must sustain recording", RecordingLifecycleState.RECORDING, triggerDetector.currentState)
            assertFalse("Soft voice must NOT trigger silence timeout", events.any { it is MemoDetectionEvent.RecordingCompleted })
        }
    }

    @Test
    fun testRecordingStopsOnlyAfterFullContinuousSilenceTimeout() {
        val triggerDetector = MemoTriggerDetector(keywordDetector = TemplateKeywordDetector(builtInPhrase = BuiltInWakePhrase.CIAO_LINDA))
        triggerDetector.detectionMode = DetectionMode.AUTO_SOUND
        triggerDetector.thresholdDb = 45.0f
        triggerDetector.minTriggerDurationMs = 50L
        triggerDetector.silenceTimeoutMs = 1000L // Exactly 20 chunks of 50ms
        triggerDetector.reset(startMonitoring = true)

        val loudChunk = ShortArray(800) { i ->
            (sin(2.0 * Math.PI * 600.0 * i / 16000.0) * 15000.0).toInt().toShort()
        }
        val silentChunk = ShortArray(800) { 0 }

        // 1. Start recording
        triggerDetector.processFrame(loudChunk)
        assertEquals(RecordingLifecycleState.RECORDING, triggerDetector.currentState)

        // 2. Feed 19 silent chunks (950ms < 1000ms)
        for (i in 1..19) {
            val events = triggerDetector.processFrame(silentChunk)
            assertEquals("At chunk $i (under timeout), state must remain RECORDING", RecordingLifecycleState.RECORDING, triggerDetector.currentState)
            assertFalse(events.any { it is MemoDetectionEvent.RecordingCompleted })
        }

        // 3. Feed the 20th silent chunk (reaches 1000ms)
        val finalEvents = triggerDetector.processFrame(silentChunk)
        assertTrue(
            "On reaching the FULL 1000ms silence timeout, RecordingCompleted must be emitted",
            finalEvents.any { it is MemoDetectionEvent.RecordingCompleted }
        )
        assertEquals(RecordingLifecycleState.MONITORING, triggerDetector.currentState)
    }

    @Test
    fun testRecordingPreservesFinalSpokenWordsAndPostSpeechBuffer() {
        val triggerDetector = MemoTriggerDetector(keywordDetector = TemplateKeywordDetector(builtInPhrase = BuiltInWakePhrase.CIAO_LINDA))
        triggerDetector.detectionMode = DetectionMode.AUTO_SOUND
        triggerDetector.thresholdDb = 45.0f
        triggerDetector.minTriggerDurationMs = 50L
        triggerDetector.silenceTimeoutMs = 500L // 10 chunks of 50ms
        triggerDetector.reset(startMonitoring = true)

        val word1 = ShortArray(800) { i -> (sin(2.0 * Math.PI * 500.0 * i / 16000.0) * 14000.0).toInt().toShort() }
        val word2 = ShortArray(800) { i -> (sin(2.0 * Math.PI * 900.0 * i / 16000.0) * 14000.0).toInt().toShort() }
        val finalWord = ShortArray(800) { i -> (sin(2.0 * Math.PI * 1200.0 * i / 16000.0) * 14000.0).toInt().toShort() }
        val silentChunk = ShortArray(800) { 0 }

        // Start with word 1
        triggerDetector.processFrame(word1)

        // Pause 2 chunks
        triggerDetector.processFrame(silentChunk)
        triggerDetector.processFrame(silentChunk)

        // Word 2
        triggerDetector.processFrame(word2)

        // Pause 2 chunks
        triggerDetector.processFrame(silentChunk)
        triggerDetector.processFrame(silentChunk)

        // Final word
        triggerDetector.processFrame(finalWord)

        // Continuous silence to finish recording (10 chunks)
        var completedEvent: MemoDetectionEvent.RecordingCompleted? = null
        for (i in 0 until 10) {
            val events = triggerDetector.processFrame(silentChunk)
            val done = events.filterIsInstance<MemoDetectionEvent.RecordingCompleted>().firstOrNull()
            if (done != null) {
                completedEvent = done
            }
        }

        assertNotNull("Recording must complete after continuous silence", completedEvent)
        // Total chunks recorded: pre-trigger chunks + word1 (1) + pause (2) + word2 (1) + pause (2) + finalWord (1) + silence (10) >= 17
        assertTrue(
            "Completed audio must retain all spoken words and trailing post-speech buffer without cutoff",
            completedEvent!!.audioChunks.size >= 17
        )
    }
}


