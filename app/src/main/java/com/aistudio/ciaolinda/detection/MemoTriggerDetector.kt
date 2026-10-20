package com.aistudio.ciaolinda.detection

import com.aistudio.ciaolinda.wakephrase.KeywordDetector
import com.aistudio.ciaolinda.wakephrase.TemplateKeywordDetector
import java.util.ArrayDeque

/**
 * Detection mode for triggering voice note recordings.
 */
enum class DetectionMode(val title: String, val description: String) {
    KEYWORD_ONLY("Wake Phrase Only", "Starts recording only when 'Ciao Linda' is spoken and exceeds the loudness threshold."),
    AUTO_SOUND("Loudness Only", "Automatically starts recording whenever speech or sound exceeds the dB threshold.");

    val displayName: String get() = title
}

/**
 * Lifecycle states of the automatic voice note recorder.
 */
enum class RecordingLifecycleState {
    IDLE,
    MONITORING,
    TRIGGERED,
    RECORDING,
    SAVED
}

/**
 * Keyword behavior settings for stopping active recordings.
 */
enum class KeywordBehavior(val title: String) {
    START_ONLY("Start memo only"),
    START_AND_STOP("Start memo & stop if repeated");

    val displayName: String get() = title
}

/**
 * Events emitted by the MemoTriggerDetector state machine.
 */
sealed class MemoDetectionEvent {
    data class TriggerStarted(
        val triggerType: String,
        val timestamp: Long,
        val preTriggerChunks: List<ShortArray>
    ) : MemoDetectionEvent()

    data class ChunkRecorded(
        val chunk: ShortArray,
        val peakDb: Float
    ) : MemoDetectionEvent()

    data class RecordingCompleted(
        val triggerType: String,
        val durationMs: Long,
        val peakDb: Float,
        val avgDb: Float,
        val audioChunks: List<ShortArray>
    ) : MemoDetectionEvent()

    data class StateChanged(val newState: RecordingLifecycleState) : MemoDetectionEvent()
}

/**
 * Core state machine and audio DSP coordinator that evaluates PCM audio streams in real-time.
 * Manages circular pre-trigger audio buffering, CPU-efficient two-stage wake-phrase spotter,
 * VAD silence timeouts, and memo lifecycle.
 */
class MemoTriggerDetector(
    private val vad: VoiceActivityDetector = VoiceActivityDetector(),
    val keywordDetector: KeywordDetector = TemplateKeywordDetector()
) {
    // Configuration Parameters
    var detectionMode: DetectionMode = DetectionMode.KEYWORD_ONLY
    var thresholdDb: Float = 48.0f
    var minTriggerDurationMs: Long = 250L // Audio must exceed threshold for this duration before trigger in Loudness Only
    var silenceTimeoutMs: Long = 2000L // Silence duration after which recording is auto-finalized
    var preTriggerDurationMs: Long = 800L // Audio kept in buffer BEFORE trigger
    var maxMemoDurationMs: Long = 300_000L // Maximum recording length (5 mins)
    var noiseFilterEnabled: Boolean = true
    var keywordSensitivity: Float = 0.60f
        set(value) {
            field = value
            keywordDetector.setSensitivity(value)
        }
    var keywordBehavior: KeywordBehavior = KeywordBehavior.START_AND_STOP

    // Observable DSP States
    var isVoiceDetected: Boolean = false
        private set
    var noiseFloorDb: Float = 30.0f
        private set

    // State
    var currentState: RecordingLifecycleState = RecordingLifecycleState.IDLE
        private set

    private var activeTriggerType: String = ""
    private var triggerStartTime: Long = 0L
    private var lastSpeechTime: Long = 0L
    private var soundSustainStartTime: Long = 0L
    private var consecutiveSilentChunks: Int = 0
    private var consecutiveSoundChunks: Int = 0

    private val preTriggerBuffer = ArrayDeque<ShortArray>()
    private val activeRecordingChunks = mutableListOf<ShortArray>()

    private var sessionMaxDb: Float = 0f
    private var sessionSumDb: Double = 0.0
    private var sessionDbCount: Int = 0
    private var peakUtteranceDb: Float = 0f
    private var consecutiveQuietFrames: Int = 10

    private val chunkDurationMs: Long = 50L // 800 samples at 16kHz = 50ms

    @Synchronized
    fun reset(startMonitoring: Boolean = true) {
        preTriggerBuffer.clear()
        activeRecordingChunks.clear()
        activeTriggerType = ""
        triggerStartTime = 0L
        lastSpeechTime = 0L
        soundSustainStartTime = 0L
        consecutiveSilentChunks = 0
        consecutiveSoundChunks = 0
        peakUtteranceDb = 0f
        consecutiveQuietFrames = 10
        sessionMaxDb = 0f
        sessionSumDb = 0.0
        sessionDbCount = 0
        keywordDetector.resetWindow()

        currentState = if (startMonitoring) RecordingLifecycleState.MONITORING else RecordingLifecycleState.IDLE
    }

    /**
     * Processes an incoming 16-bit PCM audio frame (e.g. 50ms / 800 samples).
     * Implements a CPU-efficient Two-Stage Detection Pipeline:
     * - Stage 1 (Cheap): Continuous RMS & dB SPL evaluation + pre-trigger ring buffer maintenance.
     * - Stage 2 (Wake-Phrase Spotting): Invoked ONLY if loudness exceeds threshold in Wake Phrase Only mode.
     */
    @Synchronized
    fun processFrame(
        frame: ShortArray,
        isMediaPlaying: Boolean = false
    ): List<MemoDetectionEvent> {
        val events = mutableListOf<MemoDetectionEvent>()
        if (currentState == RecordingLifecycleState.IDLE) return events

        val now = System.currentTimeMillis()

        // --- STAGE 1: Low-CPU Volume & RMS / dB Analysis ---
        val vadResult = vad.processFrame(frame, thresholdDb)
        isVoiceDetected = vadResult.isSpeechLikely
        if (!vadResult.isAboveThreshold && vadResult.db > 10f) {
            noiseFloorDb = noiseFloorDb * 0.95f + vadResult.db * 0.05f
        }

        // Rolling pre-trigger ring buffer maintenance
        // In KEYWORD_ONLY mode, buffer is large enough to retain the full wake phrase plus pre-phrase padding
        val targetPreTriggerMs = if (detectionMode == DetectionMode.KEYWORD_ONLY) {
            maxOf(preTriggerDurationMs + 1500L, 2500L)
        } else {
            preTriggerDurationMs.coerceAtLeast(400L)
        }
        val maxPreTriggerChunks = (targetPreTriggerMs / chunkDurationMs).toInt().coerceAtLeast(2)
        preTriggerBuffer.addLast(frame.copyOf())
        while (preTriggerBuffer.size > maxPreTriggerChunks) {
            preTriggerBuffer.removeFirst()
        }

        // Noise suppression during system media playback
        val shouldSuppressSound = noiseFilterEnabled && isMediaPlaying

        when (currentState) {
            RecordingLifecycleState.MONITORING -> {
                when (detectionMode) {
                    DetectionMode.KEYWORD_ONLY -> {
                        // --- TWO-STAGE GATING FOR WAKE PHRASE ONLY MODE ---
                        // Rule 1: Low CPU - If audio is below loudness threshold and no active utterance in progress,
                        // do NOT invoke the wake-phrase spotter.
                        val isAudioActive = vadResult.isAboveThreshold ||
                                (vadResult.isSpeechBand && vadResult.db >= maxOf(thresholdDb - 8.0f, 32.0f))

                        if (shouldSuppressSound || (!isAudioActive && consecutiveQuietFrames > 3)) {
                            consecutiveQuietFrames++
                            return events
                        }

                        if (isAudioActive) {
                            consecutiveQuietFrames = 0
                            if (vadResult.db > peakUtteranceDb) {
                                peakUtteranceDb = vadResult.db
                            }
                        } else {
                            consecutiveQuietFrames++
                        }

                        // Rule 2: Run Stage 2: Wake-phrase spotter
                        val kwResult = keywordDetector.processFrame(frame, vadResult.rms, vadResult.db)

                        // Rule 3: Trigger ONLY if the phrase "Ciao Linda" was matched AND the loudness criterion is met
                        if (kwResult.matched && (peakUtteranceDb >= thresholdDb || vadResult.isAboveThreshold)) {
                            currentState = RecordingLifecycleState.RECORDING
                            activeTriggerType = "KEYWORD_CIAO_LINDA"
                            triggerStartTime = now
                            lastSpeechTime = now
                            soundSustainStartTime = 0L
                            consecutiveSilentChunks = 0
                            consecutiveSoundChunks = 0
                            peakUtteranceDb = 0f
                            consecutiveQuietFrames = 0
                            keywordDetector.resetWindow()

                            sessionMaxDb = vadResult.db
                            sessionSumDb = vadResult.db.toDouble()
                            sessionDbCount = 1

                            // Prepend the entire pre-trigger ring buffer (containing pre-roll audio + "Ciao Linda")
                            activeRecordingChunks.clear()
                            val preChunks = preTriggerBuffer.toList()
                            activeRecordingChunks.addAll(preChunks)

                            events.add(
                                MemoDetectionEvent.TriggerStarted(
                                    triggerType = activeTriggerType,
                                    timestamp = now,
                                    preTriggerChunks = preChunks
                                )
                            )
                            events.add(MemoDetectionEvent.StateChanged(currentState))
                        }
                    }

                    DetectionMode.AUTO_SOUND -> {
                        // --- LOUDNESS ONLY MODE ---
                        // Do NOT perform expensive wake-phrase recognition.
                        if (!shouldSuppressSound && vadResult.isAboveThreshold) {
                            if (soundSustainStartTime == 0L) {
                                soundSustainStartTime = now
                            }
                            if (minTriggerDurationMs <= chunkDurationMs || (now - soundSustainStartTime >= minTriggerDurationMs)) {
                                currentState = RecordingLifecycleState.RECORDING
                                activeTriggerType = if (vadResult.isSpeechLikely) "AUTO_VOICE" else "AUTO_SOUND"
                                triggerStartTime = now
                                lastSpeechTime = now
                                soundSustainStartTime = 0L
                                consecutiveSilentChunks = 0
                                consecutiveSoundChunks = 0

                                sessionMaxDb = vadResult.db
                                sessionSumDb = vadResult.db.toDouble()
                                sessionDbCount = 1

                                activeRecordingChunks.clear()
                                val preChunks = preTriggerBuffer.toList()
                                activeRecordingChunks.addAll(preChunks)

                                events.add(
                                    MemoDetectionEvent.TriggerStarted(
                                        triggerType = activeTriggerType,
                                        timestamp = now,
                                        preTriggerChunks = preChunks
                                    )
                                )
                                events.add(MemoDetectionEvent.StateChanged(currentState))
                            }
                        } else {
                            soundSustainStartTime = 0L
                        }
                    }
                }
            }

            RecordingLifecycleState.RECORDING -> {
                activeRecordingChunks.add(frame.copyOf())
                events.add(MemoDetectionEvent.ChunkRecorded(frame, vadResult.db))

                if (vadResult.db > sessionMaxDb) {
                    sessionMaxDb = vadResult.db
                }
                sessionSumDb += vadResult.db
                sessionDbCount++

                // Active Speech vs Silence Evaluation with acoustic hysteresis:
                // When actively recording, speech volume fluctuates. Natural word endings, soft vowels,
                // and pauses between words must not prematurely start the silence timer.
                val silenceFloorDb = maxOf(thresholdDb - 10.0f, 34.0f)
                val isSpeechOrAudioActive = vadResult.isAboveThreshold ||
                        vadResult.db >= silenceFloorDb ||
                        (vadResult.isSpeechBand && vadResult.db >= 32.0f)

                if (isSpeechOrAudioActive) {
                    // Reset continuous silence timer whenever speech/audio is detected
                    lastSpeechTime = now
                    consecutiveSilentChunks = 0
                } else {
                    // Continuous silence in progress
                    consecutiveSilentChunks++
                }

                // Check stop conditions:
                // 1. Keyword stop command (if Wake Phrase mode and configured to START_AND_STOP)
                var keywordStopTriggered = false
                if (detectionMode == DetectionMode.KEYWORD_ONLY &&
                    keywordBehavior == KeywordBehavior.START_AND_STOP &&
                    vadResult.isAboveThreshold &&
                    (now - triggerStartTime > 1500L) // Debounce from start phrase
                ) {
                    val kwStopResult = keywordDetector.processFrame(frame, vadResult.rms, vadResult.db)
                    if (kwStopResult.matched) {
                        keywordStopTriggered = true
                    }
                }

                // 2. Continuous silence timeout:
                // Recording stops ONLY after the FULL configured silence timeout has elapsed continuously.
                val silenceElapsedMs = consecutiveSilentChunks * chunkDurationMs
                val isSilenceTimeout = consecutiveSilentChunks > 0 && silenceElapsedMs >= silenceTimeoutMs

                // 3. Max memo length reached
                val totalDuration = maxOf(now - triggerStartTime, activeRecordingChunks.size * chunkDurationMs)
                val isMaxDuration = totalDuration >= maxMemoDurationMs

                if (keywordStopTriggered || isSilenceTimeout || isMaxDuration) {
                    val finalAvgDb = if (sessionDbCount > 0) (sessionSumDb / sessionDbCount).toFloat() else 0f
                    val finishedEvent = MemoDetectionEvent.RecordingCompleted(
                        triggerType = activeTriggerType,
                        durationMs = totalDuration,
                        peakDb = sessionMaxDb,
                        avgDb = finalAvgDb,
                        audioChunks = activeRecordingChunks.toList()
                    )

                    currentState = RecordingLifecycleState.MONITORING
                    events.add(finishedEvent)
                    events.add(MemoDetectionEvent.StateChanged(currentState))

                    // Reset tracking
                    activeRecordingChunks.clear()
                    activeTriggerType = ""
                    triggerStartTime = 0L
                    lastSpeechTime = 0L
                    soundSustainStartTime = 0L
                    consecutiveSilentChunks = 0
                    consecutiveSoundChunks = 0
                    sessionMaxDb = 0f
                    sessionSumDb = 0.0
                    sessionDbCount = 0
                }
            }

            else -> {}
        }

        return events
    }

    /**
     * Manually force starts recording immediately.
     */
    @Synchronized
    fun startManualRecording(): MemoDetectionEvent.TriggerStarted {
        val now = System.currentTimeMillis()
        currentState = RecordingLifecycleState.RECORDING
        activeTriggerType = "MANUAL"
        triggerStartTime = now
        lastSpeechTime = now
        soundSustainStartTime = 0L
        consecutiveSilentChunks = 0
        consecutiveSoundChunks = 0

        sessionMaxDb = 50f
        sessionSumDb = 50.0
        sessionDbCount = 1

        activeRecordingChunks.clear()
        val preChunks = preTriggerBuffer.toList()
        activeRecordingChunks.addAll(preChunks)

        return MemoDetectionEvent.TriggerStarted(
            triggerType = "MANUAL",
            timestamp = now,
            preTriggerChunks = preChunks
        )
    }

    /**
     * Manually force finishes and finalizes the active recording.
     */
    @Synchronized
    fun stopManualRecording(): MemoDetectionEvent.RecordingCompleted? {
        if (currentState != RecordingLifecycleState.RECORDING) return null

        val now = System.currentTimeMillis()
        val totalDuration = if (triggerStartTime > 0L) (now - triggerStartTime) else (activeRecordingChunks.size * chunkDurationMs)
        val finalAvgDb = if (sessionDbCount > 0) (sessionSumDb / sessionDbCount).toFloat() else 0f

        val completed = MemoDetectionEvent.RecordingCompleted(
            triggerType = activeTriggerType.ifEmpty { "MANUAL" },
            durationMs = totalDuration,
            peakDb = sessionMaxDb,
            avgDb = finalAvgDb,
            audioChunks = activeRecordingChunks.toList()
        )

        currentState = RecordingLifecycleState.MONITORING
        activeRecordingChunks.clear()
        activeTriggerType = ""
        triggerStartTime = 0L
        lastSpeechTime = 0L
        soundSustainStartTime = 0L

        return completed
    }

    @Synchronized
    fun currentRecordingDurationMs(): Long {
        if (currentState != RecordingLifecycleState.RECORDING) return 0L
        val now = System.currentTimeMillis()
        return if (triggerStartTime > 0L) (now - triggerStartTime).coerceAtLeast(0L) else (activeRecordingChunks.size * chunkDurationMs)
    }

    fun activeRecordingChunksDurationMs(): Long = currentRecordingDurationMs()

    @Synchronized
    fun loadKeywordTemplate(templateBytes: ByteArray): Boolean {
        return keywordDetector.loadTemplate(templateBytes)
    }
}

