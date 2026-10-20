package io.github.elgrande73.voicememo.detection

import io.github.elgrande73.voicememo.settings.MatchConfidenceThreshold
import io.github.elgrande73.voicememo.wakephrase.KeywordDetector
import io.github.elgrande73.voicememo.wakephrase.KeywordMatchResult
import io.github.elgrande73.voicememo.wakephrase.NoiseFloorEstimator
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import java.util.ArrayDeque
import kotlin.math.log10

/**
 * Detection mode for triggering voice note recordings.
 */
enum class DetectionMode(val title: String, val description: String) {
    KEYWORD_ONLY("Wake Phrase Only", "Starts recording only when the wake phrase (default 'Ciao Linda') is spoken and exceeds the loudness threshold."),
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
    /** Wake-phrase match confidence threshold on the detector scale (0.0..1.0). */
    var matchConfidenceThreshold: Float =
        MatchConfidenceThreshold.toDetectorConfidence(MatchConfidenceThreshold.DEFAULT_PERCENT)
        set(value) {
            field = value
            keywordDetector.setMatchConfidenceThreshold(value)
        }
    var keywordBehavior: KeywordBehavior = KeywordBehavior.START_AND_STOP

    // Observable DSP States
    var isVoiceDetected: Boolean = false
        private set
    /** Adaptive background level (VAD dB scale: dBFS + 90), see [NoiseFloorEstimator]. */
    var noiseFloorDb: Float = 30.0f
        private set
    // Live wake-phrase match confidence (0..1) with peak-hold decay so short matches stay visible
    var keywordConfidence: Float = 0f
        private set
    /** Raw (undecayed) result of the wake-phrase matcher for the most recent frame, or null if it did not run. */
    var lastKeywordResult: KeywordMatchResult? = null
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
    private val noiseFloorEstimator = NoiseFloorEstimator(1)
    private val frameMeanSquare = DoubleArray(1)
    // Levels of the most recent frames (VAD dB scale), covering the longest window the wake-phrase matcher evaluates
    private val recentFrameDb = FloatArray(RECENT_LEVEL_FRAMES)
    private var recentFrameCount = 0

    private val chunkDurationMs: Long = 50L // 800 samples at 16kHz = 50ms
    private var framesSinceTrigger = 0

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
        noiseFloorEstimator.reset()
        recentFrameCount = 0
        keywordConfidence = 0f
        lastKeywordResult = null
        sessionMaxDb = 0f
        sessionSumDb = 0.0
        sessionDbCount = 0
        keywordDetector.resetStream()

        currentState = if (startMonitoring) RecordingLifecycleState.MONITORING else RecordingLifecycleState.IDLE
    }

    /**
     * Called when the capture stream (re)starts, e.g. after an AudioRecord restart or input-device change: the noise
     * floor and the wake-phrase window are re-learned from the new stream. An ongoing memo is not interrupted.
     */
    @Synchronized
    fun restartStream() {
        noiseFloorEstimator.reset()
        recentFrameCount = 0
        keywordDetector.resetStream()
    }

    /**
     * Processes an incoming 16-bit PCM audio frame (50 ms / 800 samples at 16 kHz).
     * - Level analysis (VAD), adaptive noise floor and pre-trigger ring buffer on every frame.
     * - Wake Phrase mode: every frame is passed to the wake-phrase matcher, exactly as the Settings phrase test does,
     *   so the matcher always sees contiguous audio. A trigger requires a match (confidence ≥ Match confidence
     *   threshold) and that the recent audio reached the activation (loudness) threshold.
     *   (Earlier versions only passed frames above the loudness threshold; the matcher then saw fragments of the phrase
     *   and its confidence stayed near zero.)
     *
     * @param isFeedbackMuted true while the frame is muted for an app feedback chime; such frames
     *   do not count towards the silence timeout so the user can wait for the chime before speaking.
     */
    @Synchronized
    fun processFrame(
        frame: ShortArray,
        isMediaPlaying: Boolean = false,
        isFeedbackMuted: Boolean = false
    ): List<MemoDetectionEvent> {
        val events = mutableListOf<MemoDetectionEvent>()
        if (currentState == RecordingLifecycleState.IDLE) return events

        val now = System.currentTimeMillis()
        keywordConfidence *= KEYWORD_CONFIDENCE_DECAY

        // --- Level analysis ---
        val vadResult = vad.processFrame(frame, thresholdDb)
        isVoiceDetected = vadResult.isSpeechLikely
        updateNoiseFloor(vadResult.rms)
        recentFrameDb[recentFrameCount % RECENT_LEVEL_FRAMES] = vadResult.db
        recentFrameCount++

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
                        // Every frame reaches the matcher (contiguous context, same as the Settings phrase test)
                        val kwResult = matchWakePhrase(frame, vadResult)

                        // Trigger only if the wake phrase matched AND the phrase reached the activation (loudness)
                        // threshold; during media playback (noise filter on) wake-phrase triggers are suppressed
                        if (!shouldSuppressSound && kwResult.matched && recentPeakDb() >= thresholdDb) {
                            currentState = RecordingLifecycleState.RECORDING
                            // Persisted trigger type of wake-phrase memos; stored in the database, so it must not be renamed
                            activeTriggerType = "KEYWORD_CIAO_LINDA"
                            triggerStartTime = now
                            lastSpeechTime = now
                            soundSustainStartTime = 0L
                            consecutiveSilentChunks = 0
                            consecutiveSoundChunks = 0
                            keywordDetector.resetWindow()
                            framesSinceTrigger = 0

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

                if (isSpeechOrAudioActive || isFeedbackMuted) {
                    // Reset continuous silence timer whenever speech/audio is detected
                    lastSpeechTime = now
                    consecutiveSilentChunks = 0
                } else {
                    // Continuous silence in progress
                    consecutiveSilentChunks++
                }

                // Check stop conditions:
                // 1. Keyword stop command (if Wake Phrase mode and configured to START_AND_STOP)
                // The matcher keeps receiving every frame while recording (contiguous context for the stop phrase)
                var keywordStopTriggered = false
                framesSinceTrigger++
                if (detectionMode == DetectionMode.KEYWORD_ONLY) {
                    val kwStopResult = matchWakePhrase(frame, vadResult)
                    if (keywordBehavior == KeywordBehavior.START_AND_STOP &&
                        framesSinceTrigger * chunkDurationMs > STOP_PHRASE_DEBOUNCE_MS && // not the start phrase
                        kwStopResult.matched &&
                        recentPeakDb() >= thresholdDb
                    ) {
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
                    // The window still holds the stop phrase; clear it so it cannot immediately start a new memo
                    keywordDetector.resetWindow()
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
        framesSinceTrigger = 0
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
        keywordDetector.resetWindow()
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

    private fun matchWakePhrase(frame: ShortArray, vadResult: VoiceFrameResult): KeywordMatchResult {
        val result = keywordDetector.processFrame(frame, vadResult.rms, vadResult.db)
        lastKeywordResult = result
        keywordConfidence = maxOf(keywordConfidence, result.confidence)
        return result
    }

    /** Loudest frame (VAD dB scale) within the longest window the wake-phrase matcher evaluates. */
    private fun recentPeakDb(): Float {
        val count = minOf(recentFrameCount, RECENT_LEVEL_FRAMES)
        var peak = 0f
        for (i in 0 until count) peak = maxOf(peak, recentFrameDb[i])
        return peak
    }

    private fun updateNoiseFloor(rms: Float) {
        val normalized = rms / 32767.0
        frameMeanSquare[0] = normalized * normalized
        if (noiseFloorEstimator.update(frameMeanSquare)) {
            val dbfs = 10.0 * log10(maxOf(noiseFloorEstimator.estimate(0), 1e-10))
            noiseFloorDb = (dbfs + 90.0).toFloat().coerceIn(0f, 120f) // same scale as VoiceActivityDetector.db
        }
    }

    companion object {
        private const val KEYWORD_CONFIDENCE_DECAY = 0.92f // per 50ms frame
        private const val STOP_PHRASE_DEBOUNCE_MS = 1500L
        /** 2.5 s: matches the matcher's sliding window (50 frames). */
        private const val RECENT_LEVEL_FRAMES = 50
    }
}

