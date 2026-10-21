package io.github.elgrande73.voicememo.monitoring

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.elgrande73.voicememo.MainActivity
import io.github.elgrande73.voicememo.BuildConfig
import io.github.elgrande73.voicememo.R
import io.github.elgrande73.voicememo.audio.AmplitudePoint
import io.github.elgrande73.voicememo.audio.AudioInputDevice
import io.github.elgrande73.voicememo.audio.AudioInputManager
import io.github.elgrande73.voicememo.audio.FeedbackSoundPlayer
import io.github.elgrande73.voicememo.audio.LevelMeter
import io.github.elgrande73.voicememo.audio.MediaPlaybackDetector
import io.github.elgrande73.voicememo.audio.MicrophoneArbiter
import io.github.elgrande73.voicememo.audio.MicrophoneOwner
import io.github.elgrande73.voicememo.audio.PcmFrameAssembler
import io.github.elgrande73.voicememo.audio.WavWriter
import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.KeywordBehavior
import io.github.elgrande73.voicememo.detection.MemoDetectionEvent
import io.github.elgrande73.voicememo.detection.MemoTriggerDetector
import io.github.elgrande73.voicememo.detection.RecordingLifecycleState
import io.github.elgrande73.voicememo.detection.RecordingStopReason
import io.github.elgrande73.voicememo.memo.AppDatabase
import io.github.elgrande73.voicememo.memo.ErrorLogger
import io.github.elgrande73.voicememo.memo.VoiceNote
import io.github.elgrande73.voicememo.settings.MatchConfidenceThreshold
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import io.github.elgrande73.voicememo.settings.SettingsMigrator
import io.github.elgrande73.voicememo.shortcuts.RecordingShortcutHandler
import io.github.elgrande73.voicememo.shortcuts.RecordingTileService
import io.github.elgrande73.voicememo.shortcuts.ShortcutSettings
import io.github.elgrande73.voicememo.shortcuts.ToggleDebouncer
import io.github.elgrande73.voicememo.transcription.Transcription
import io.github.elgrande73.voicememo.transcription.TranscriptionStatus
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import io.github.elgrande73.voicememo.wakephrase.WakePhraseStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground Service running high-priority, low-latency audio capture and automatic voice memo recording.
 */
class AudioMonitoringService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var recordingJob: Job? = null
    @Volatile
    private var currentAudioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var memoDetector: MemoTriggerDetector
    private lateinit var mediaPlaybackDetector: MediaPlaybackDetector
    private lateinit var database: AppDatabase
    private lateinit var feedbackSoundPlayer: FeedbackSoundPlayer

    private var configuredInputId: Int = -1
    private var configuredInputName: String = ""
    private var speechToTextEnabled: Boolean = false
    @Volatile
    private var feedbackSoundsEnabled: Boolean = true
    // Mic frames captured until this time are muted so feedback chimes don't leak into memos or re-trigger detection
    @Volatile
    private var feedbackMuteUntilMs: Long = 0L

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val chunkSize = 800 // 50ms at 16kHz

    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var headsetReceiver: BroadcastReceiver? = null
    private val noteNotificationCounter = AtomicInteger(2000)
    private val toggleDebouncer = ToggleDebouncer(RecordingShortcutHandler.MIN_TOGGLE_INTERVAL_MS)
    private val levelMeter = LevelMeter()

    companion object {
        /** Channel of earlier versions (silent, so hidden on most lock screens); deleted on start. */
        const val CHANNEL_ID = "voice_memo_monitoring_channel"
        /** Ongoing notification with the recording controls; default importance (no sound) so it is shown on the lock screen. */
        const val CONTROLS_CHANNEL_ID = "voice_memo_controls_channel"
        const val CHANNEL_NOTE_ID = "voice_memo_note_channel"
        const val NOTIFICATION_ID = 1001
        // Covers thread hand-off, AudioTrack output latency and room echo after the chime
        private const val FEEDBACK_MUTE_TAIL_MS = 450L
        // Time the capture thread gets to apply a Stop request before it is finalized directly
        private const val MANUAL_STOP_GRACE_MS = 400L
        private const val RECORDING_STATUS_TEXT = "🔴 Recording voice memo..."

        const val ACTION_START_MANUAL_RECORDING = "${BuildConfig.APPLICATION_ID}.ACTION_START_MANUAL_RECORDING"
        const val ACTION_STOP_MANUAL_RECORDING = "${BuildConfig.APPLICATION_ID}.ACTION_STOP_MANUAL_RECORDING"
        /** Starts a memo if none is being recorded, otherwise stops it (shortcuts and Quick Settings tile). */
        const val ACTION_TOGGLE_MANUAL_RECORDING = "${BuildConfig.APPLICATION_ID}.ACTION_TOGGLE_MANUAL_RECORDING"
        /** Rebuilds the notification after the lock-screen controls setting changed. */
        const val ACTION_REFRESH_NOTIFICATION = "${BuildConfig.APPLICATION_ID}.ACTION_REFRESH_NOTIFICATION"
        const val ACTION_RELOAD_TEMPLATE = "${BuildConfig.APPLICATION_ID}.ACTION_RELOAD_TEMPLATE"
        const val ACTION_UPDATE_MATCH_CONFIDENCE_THRESHOLD = "${BuildConfig.APPLICATION_ID}.ACTION_UPDATE_MATCH_CONFIDENCE_THRESHOLD"
        const val EXTRA_MATCH_CONFIDENCE_THRESHOLD_PERCENT = "matchConfidenceThresholdPercent"
        /** Minimum wake-phrase level on the detector's scale (dBFS + 90); NaN = condition off. */
        const val EXTRA_WAKE_PHRASE_MIN_LEVEL_DB = "wakePhraseMinLevelDb"

        // Public Observable States for UI
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning = _isServiceRunning.asStateFlow()

        private val _currentRecordingState = MutableStateFlow(RecordingLifecycleState.IDLE)
        val currentRecordingState = _currentRecordingState.asStateFlow()

        private val _isCurrentlyRecording = MutableStateFlow(false)
        val isCurrentlyRecording = _isCurrentlyRecording.asStateFlow()

        private val _recordingDurationSeconds = MutableStateFlow(0)
        val recordingDurationSeconds = _recordingDurationSeconds.asStateFlow()

        private val _liveLevelDbfs = MutableStateFlow<Float?>(null)
        /**
         * Live microphone level in dBFS, about 10 updates per second from the monitoring capture stream (before any
         * chime muting); null while there is no live input (monitoring off, microphone unavailable or released).
         */
        val liveLevelDbfs = _liveLevelDbfs.asStateFlow()

        private val _liveNoiseFloorDb = MutableStateFlow(25f)
        val liveNoiseFloorDb = _liveNoiseFloorDb.asStateFlow()

        private val _isVoiceDetected = MutableStateFlow(false)
        val isVoiceDetected = _isVoiceDetected.asStateFlow()

        private val _liveKeywordConfidence = MutableStateFlow(0f)
        val liveKeywordConfidence = _liveKeywordConfidence.asStateFlow()

        private val _lastCapturedNoteTitle = MutableStateFlow<String?>(null)
        val lastCapturedNoteTitle = _lastCapturedNoteTitle.asStateFlow()

        private val _lastRecordedNote = MutableStateFlow<VoiceNote?>(null)
        val lastRecordedNote = _lastRecordedNote.asStateFlow()

        private val _sessionEventCount = MutableStateFlow(0)
        val sessionEventCount = _sessionEventCount.asStateFlow()

        private val _currentSessionData = MutableStateFlow<List<AmplitudePoint>>(emptyList())
        val currentSessionData = _currentSessionData.asStateFlow()

        private val _activeAudioDeviceName = MutableStateFlow("Phone microphone")
        val activeAudioDeviceName = _activeAudioDeviceName.asStateFlow()
        val activeInputDeviceName = _activeAudioDeviceName.asStateFlow()

        private val _isFallbackActive = MutableStateFlow(false)
        val isFallbackActive = _isFallbackActive.asStateFlow()

        private val _engineState = MutableStateFlow(MonitoringEngineState.STOPPED)
        /** Real state of the capture/detection engine (not just whether the service exists). */
        val engineState = _engineState.asStateFlow()

        /**
         * Toggles the memo of the running monitoring service. Returns false (and does nothing) if monitoring is not
         * running: from the background the app may not start using the microphone.
         */
        fun requestToggle(context: Context): Boolean {
            if (!_isServiceRunning.value) return false
            return try {
                context.startService(Intent(context, AudioMonitoringService::class.java).setAction(ACTION_TOGGLE_MANUAL_RECORDING))
                true
            } catch (e: Exception) {
                Log.w("AudioService", "Toggle request rejected", e)
                false
            }
        }

        /** Microphone ownership shared with the Settings phrase recordings. */
        val microphoneArbiter: MicrophoneArbiter get() = MicrophoneArbiter.shared
    }

    override fun onCreate() {
        super.onCreate()
        memoDetector = MemoTriggerDetector()
        mediaPlaybackDetector = MediaPlaybackDetector(this)
        database = AppDatabase.getDatabase(this)
        feedbackSoundPlayer = FeedbackSoundPlayer(this)

        createNotificationChannels()
        loadSavedKeywordTemplate()

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceMemo::MonitoringWakeLock")?.apply {
            setReferenceCounted(false)
        }

        registerAudioDeviceListeners()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ensure foreground notification is active immediately for FGS requirements
        ensureForegroundNotification()

        if (intent == null) return START_STICKY

        when (intent.action) {
            ACTION_START_MANUAL_RECORDING -> {
                handleStartManualRecording()
                return START_STICKY
            }
            ACTION_STOP_MANUAL_RECORDING -> {
                handleStopManualRecording()
                return START_STICKY
            }
            ACTION_TOGGLE_MANUAL_RECORDING -> {
                // Several inputs (keys, tile, notification) can arrive at once: one toggle per interval
                if (toggleDebouncer.tryAcquire(SystemClock.elapsedRealtime())) {
                    if (memoDetector.currentState == RecordingLifecycleState.RECORDING) handleStopManualRecording()
                    else if (_isServiceRunning.value) handleStartManualRecording()
                }
                return START_STICKY
            }
            ACTION_REFRESH_NOTIFICATION -> {
                updateNotificationStatus(memoDetector.currentState)
                return START_STICKY
            }
            ACTION_RELOAD_TEMPLATE -> {
                // Never change the phrase during a memo: its stop phrase must be the phrase that started it
                if (memoDetector.currentState == RecordingLifecycleState.RECORDING) wakePhraseReloadPending = true
                else loadSavedKeywordTemplate()
                return START_STICKY
            }
            ACTION_UPDATE_MATCH_CONFIDENCE_THRESHOLD -> {
                // Applied live: no detector reset, so an ongoing recording is not interrupted
                applyMatchConfidenceThreshold(intent)
                return START_STICKY
            }
        }

        // Parse configurations
        configuredInputId = intent.getIntExtra("audioInputId", -1)
        configuredInputName = intent.getStringExtra("audioInputName") ?: ""
        speechToTextEnabled = intent.getBooleanExtra("speechToTextEnabled", false)
        feedbackSoundsEnabled = intent.getBooleanExtra("feedbackSoundsEnabled", true)

        val modeStr = intent.getStringExtra("detectionMode") ?: DetectionMode.AUTO_SOUND.name
        memoDetector.detectionMode = try {
            DetectionMode.valueOf(modeStr)
        } catch (_: Exception) {
            DetectionMode.AUTO_SOUND
        }

        memoDetector.thresholdDb = intent.getFloatExtra("thresholdDb", 48.0f)
        memoDetector.wakePhraseMinLevelDb = intent.getFloatExtra(EXTRA_WAKE_PHRASE_MIN_LEVEL_DB, Float.NaN).takeUnless { it.isNaN() }
        memoDetector.minTriggerDurationMs = intent.getLongExtra("minTriggerDurationMs", 250L)
        memoDetector.silenceTimeoutMs = intent.getLongExtra("silenceTimeoutMs", 2000L)
        memoDetector.preTriggerDurationMs = intent.getLongExtra("preTriggerDurationMs", 800L)
        memoDetector.maxMemoDurationMs = intent.getLongExtra("maxMemoDurationMs", 300_000L)
        memoDetector.noiseFilterEnabled = intent.getBooleanExtra("noiseFilterEnabled", true)
        applyMatchConfidenceThreshold(intent)

        val kwBehaviorStr = intent.getStringExtra("keywordBehavior") ?: KeywordBehavior.START_AND_STOP.name
        memoDetector.keywordBehavior = try {
            KeywordBehavior.valueOf(kwBehaviorStr)
        } catch (_: Exception) {
            KeywordBehavior.START_AND_STOP
        }

        startForegroundMonitoring()
        return START_STICKY
    }

    private fun ensureForegroundNotification() {
        try {
            // Reflect the current state: this runs for every service request, also while a memo is recorded
            val recording = memoDetector.currentState == RecordingLifecycleState.RECORDING
            val notification = buildForegroundNotification(if (recording) RECORDING_STATUS_TEXT else "Monitoring audio...", recording)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (hasMic) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("AudioService", "startForeground error", e)
        }
    }

    private fun startForegroundMonitoring() {
        ensureForegroundNotification()
        _isServiceRunning.value = true
        RecordingTileService.requestUpdate(this)

        try {
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes rolling
        } catch (_: Exception) {}

        startAudioCaptureLoop()
    }

    private fun startAudioCaptureLoop() {
        recordingJob?.cancel()
        recordingJob = serviceScope.launch(Dispatchers.Default) {
            var consecutiveReadErrors = 0
            memoDetector.reset(startMonitoring = true)
            _currentRecordingState.value = RecordingLifecycleState.MONITORING
            val historyPoints = mutableListOf<AmplitudePoint>()
            val frameAssembler = PcmFrameAssembler(chunkSize)
            val diagnostics = if (BuildConfig.DEBUG) WakePhraseDiagnosticsLog() else null

            while (isActive) {
                // Never capture while a Settings phrase recording owns the microphone
                if (!microphoneArbiter.tryAcquire(MicrophoneOwner.MONITORING)) {
                    _engineState.value = MonitoringEngineState.WAITING_FOR_MICROPHONE
                    microphoneArbiter.awaitAvailableFor(MicrophoneOwner.MONITORING)
                    continue
                }
                _engineState.value = MonitoringEngineState.STARTING
                var activeRecord: AudioRecord? = null
                try {
                    val record = initializeAudioRecord()
                    if (record == null) {
                        ErrorLogger.log(this@AudioMonitoringService, "AudioService", "Failed to initialize AudioRecord hardware")
                        microphoneArbiter.release(MicrophoneOwner.MONITORING)
                        _engineState.value = MonitoringEngineState.UNAVAILABLE
                        delay(2000)
                        continue
                    }
                    activeRecord = record
                    currentAudioRecord = record

                    try {
                        record.startRecording()
                    } catch (e: IllegalStateException) {
                        ErrorLogger.log(this@AudioMonitoringService, "AudioService", "startRecording failed with IllegalStateException", e)
                        releaseAudioRecord(record)
                        _engineState.value = MonitoringEngineState.UNAVAILABLE
                        delay(1500)
                        continue
                    }

                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        ErrorLogger.log(this@AudioMonitoringService, "AudioService", "AudioRecord not recording after startRecording()")
                        releaseAudioRecord(record)
                        _engineState.value = MonitoringEngineState.UNAVAILABLE
                        delay(1500)
                        continue
                    }

                    _engineState.value = MonitoringEngineState.ACTIVE
                    // A new capture stream: re-learn the background (the input device may have changed)
                    memoDetector.restartStream()
                    frameAssembler.reset()
                    levelMeter.reset()
                    val buffer = ShortArray(chunkSize)

                    while (isActive && record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val readResult = try {
                            record.read(buffer, 0, chunkSize)
                        } catch (e: Exception) {
                            ErrorLogger.log(this@AudioMonitoringService, "AudioService", "AudioRecord read exception", e)
                            -1
                        }

                        if (readResult <= 0) {
                            consecutiveReadErrors++
                            if (consecutiveReadErrors > 5 || readResult == AudioRecord.ERROR_DEAD_OBJECT) {
                                ErrorLogger.log(this@AudioMonitoringService, "AudioService", "AudioRecord read error ($readResult), restarting stream")
                                break
                            }
                            delay(50)
                            continue
                        }

                        consecutiveReadErrors = 0
                        // Partial reads are completed into exact 50 ms frames (never padded with stale samples)
                        frameAssembler.push(buffer, readResult) { frame -> processCapturedFrame(frame, historyPoints, diagnostics) }
                    }
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        ErrorLogger.log(this@AudioMonitoringService, "AudioService", "Unexpected exception in audio loop", e)
                        finalizeAfterStreamFailure()
                        delay(1500)
                    }
                } finally {
                    // No stale level while the stream is down (restart, microphone handed to Settings, shutdown)
                    _liveLevelDbfs.value = null
                    releaseAudioRecord(activeRecord)
                    microphoneArbiter.release(MicrophoneOwner.MONITORING)
                }
                // The stream ended (read errors, device lost): a memo must not continue across the gap
                if (isActive) finalizeAfterStreamFailure()
            }
        }
    }

    private fun processCapturedFrame(
        frame: ShortArray,
        historyPoints: MutableList<AmplitudePoint>,
        diagnostics: WakePhraseDiagnosticsLog?
    ) {
        // Check system media playback status
        val mediaStatus = mediaPlaybackDetector.checkPlaybackStatus()

        // Live level from the real microphone input (also while only monitoring), before chime muting
        levelMeter.update(frame)?.let { _liveLevelDbfs.value = it }

        // Suppress our own feedback chime (plus output latency tail) from the mic stream
        val isFeedbackMuted = System.currentTimeMillis() < feedbackMuteUntilMs
        if (isFeedbackMuted) {
            frame.fill(0)
        }

        // Process frame via State Machine
        val startNanos = System.nanoTime()
        val events = memoDetector.processFrame(
            frame,
            isMediaPlaying = mediaStatus.isMediaPlaying,
            isFeedbackMuted = isFeedbackMuted
        )
        diagnostics?.record(frame, memoDetector, System.nanoTime() - startNanos)
        _isVoiceDetected.value = memoDetector.isVoiceDetected
        _liveNoiseFloorDb.value = memoDetector.noiseFloorDb
        _liveKeywordConfidence.value = memoDetector.keywordConfidence

        for (event in events) {
            when (event) {
                is MemoDetectionEvent.StateChanged -> onRecordingStateChanged(event.newState)
                is MemoDetectionEvent.TriggerStarted -> {
                    playFeedbackCue(FeedbackSoundPlayer.Cue.RECORDING_STARTED)
                }
                is MemoDetectionEvent.ChunkRecorded -> {
                    if (_isCurrentlyRecording.value) {
                        _recordingDurationSeconds.value = (memoDetector.currentRecordingDurationMs() / 1000L).toInt()
                    }
                    val point = AmplitudePoint(
                        timestamp = System.currentTimeMillis(),
                        amplitudeDb = event.peakDb,
                        isTriggered = _currentRecordingState.value == RecordingLifecycleState.RECORDING,
                        isKeyword = memoDetector.detectionMode == DetectionMode.KEYWORD_ONLY
                    )
                    historyPoints.add(point)
                    if (historyPoints.size > 80) {
                        historyPoints.removeAt(0)
                    }
                    _currentSessionData.value = historyPoints.toList()
                }
                is MemoDetectionEvent.RecordingCompleted -> onRecordingCompleted(event)
                else -> {}
            }
        }
    }

    private fun playFeedbackCue(cue: FeedbackSoundPlayer.Cue) {
        if (!feedbackSoundsEnabled) return
        feedbackMuteUntilMs = System.currentTimeMillis() + feedbackSoundPlayer.cueDurationMs + FEEDBACK_MUTE_TAIL_MS
        feedbackSoundPlayer.play(cue)
    }

    private fun initializeAudioRecord(): AudioRecord? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ErrorLogger.log(this, "AudioService", "RECORD_AUDIO permission not granted")
            return null
        }
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize * 2
            )

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }

            // Route to preferred audio input device if selected
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && configuredInputId != -1) {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val inputDevices = audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS) ?: emptyArray()
                val target = inputDevices.find { it.id == configuredInputId }
                if (target != null) {
                    try {
                        record.setPreferredDevice(target)
                    } catch (_: Exception) {}
                }
            }

            // Evaluate routing description
            updateAudioRoutingStatus(record)

            return record
        } catch (e: SecurityException) {
            ErrorLogger.log(this, "AudioService", "SecurityException initializing AudioRecord", e)
            return null
        } catch (e: Exception) {
            ErrorLogger.log(this, "AudioService", "AudioRecord init exception", e)
            return null
        }
    }

    private fun updateAudioRoutingStatus(record: AudioRecord?) {
        try {
            val routedDevice: AudioDeviceInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && record != null) {
                record.routedDevice
            } else null
            val routingEval = AudioInputManager.evaluateAudioRouting(configuredInputId, configuredInputName, routedDevice)
            _activeAudioDeviceName.value = routingEval.activeDisplayName
            _isFallbackActive.value = routingEval.isFallback
        } catch (_: Exception) {}
    }

    /**
     * Stops and releases [record]. Called from the capture loop and from [onDestroy]; once the active AudioRecord is
     * released, the microphone is handed back to [microphoneArbiter] so a Settings phrase recording may start.
     */
    @Synchronized
    private fun releaseAudioRecord(record: AudioRecord?) {
        if (record == null) return
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
            record.release()
        } catch (e: Exception) {
            Log.w("AudioService", "AudioRecord release failed", e)
        }
        if (currentAudioRecord == record) {
            currentAudioRecord = null
            microphoneArbiter.release(MicrophoneOwner.MONITORING)
        }
    }

    private fun handleStartManualRecording() {
        // Already recording: keep the memo in progress
        memoDetector.startManualRecording() ?: return
        playFeedbackCue(FeedbackSoundPlayer.Cue.RECORDING_STARTED)
        _currentRecordingState.value = RecordingLifecycleState.RECORDING
        _isCurrentlyRecording.value = true
        updateNotificationStatus(RecordingLifecycleState.RECORDING)
    }

    /**
     * Stop button: the capture thread ends the memo after its next frame, so audio already captured but still in the
     * AudioRecord/frame pipeline is saved too. If capture has stalled, the watchdog finalizes it after a short grace
     * period. Either way the memo is finalized exactly once (the detector ignores duplicates).
     */
    private fun handleStopManualRecording() {
        if (!memoDetector.requestStop(RecordingStopReason.USER)) return
        serviceScope.launch {
            delay(MANUAL_STOP_GRACE_MS)
            memoDetector.finalizePendingStop()?.let { completed ->
                onRecordingCompleted(completed)
                onRecordingStateChanged(RecordingLifecycleState.MONITORING)
            }
        }
    }

    /** Saves a memo interrupted by a microphone failure (no-op when not recording). */
    private fun finalizeAfterStreamFailure() {
        memoDetector.stopManualRecording(RecordingStopReason.ERROR)?.let { completed ->
            onRecordingCompleted(completed)
            onRecordingStateChanged(RecordingLifecycleState.MONITORING)
        }
    }

    /** Single completion path for every stop reason: state, stop cue and saving. */
    private fun onRecordingCompleted(event: MemoDetectionEvent.RecordingCompleted) {
        _isCurrentlyRecording.value = false
        _recordingDurationSeconds.value = 0
        playFeedbackCue(FeedbackSoundPlayer.Cue.RECORDING_STOPPED)
        if (BuildConfig.DEBUG) {
            Log.d("AudioService", "Memo finished: reason=${event.stopReason} removedStopPhraseFrames=${event.removedStopPhraseFrames} durationMs=${event.durationMs}")
        }
        saveCompletedVoiceNote(event)
    }

    private fun onRecordingStateChanged(state: RecordingLifecycleState) {
        if (state != RecordingLifecycleState.RECORDING && wakePhraseReloadPending) {
            wakePhraseReloadPending = false
            loadSavedKeywordTemplate()
        }
        _currentRecordingState.value = state
        _isCurrentlyRecording.value = state == RecordingLifecycleState.RECORDING
        if (state != RecordingLifecycleState.RECORDING) _recordingDurationSeconds.value = 0
        updateNotificationStatus(state)
    }

    private fun saveCompletedVoiceNote(event: MemoDetectionEvent.RecordingCompleted) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val timestamp = System.currentTimeMillis()
                val dateFormatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                val displayFormatter = SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault())
                val dateStr = dateFormatter.format(Date(timestamp))
                val displayDate = displayFormatter.format(Date(timestamp))

                val prefix = when {
                    event.triggerType.contains("KEYWORD") -> "Wake Phrase Memo"
                    event.triggerType.contains("VOICE") -> "Voice Memo"
                    event.triggerType.contains("MANUAL") -> "Manual Memo"
                    else -> "Auto Memo"
                }
                val title = "$prefix ($displayDate)"

                val memosDir = File(filesDir, "memos").apply { mkdirs() }
                val wavFile = File(memosDir, "memo_${dateStr}_${timestamp}.wav")

                // Write the WAV file atomically (temporary file, verified, then renamed); retry once on failure
                try {
                    WavWriter.writeWavFileAtomic(wavFile, event.audioChunks, sampleRate, 1)
                } catch (e: java.io.IOException) {
                    ErrorLogger.log(this@AudioMonitoringService, "AudioService", "Writing memo failed, retrying", e)
                    WavWriter.writeWavFileAtomic(wavFile, event.audioChunks, sampleRate, 1)
                }

                val durationSeconds = (event.durationMs / 1000.0).coerceAtLeast(0.5)

                val note = VoiceNote(
                    title = title,
                    timestamp = timestamp,
                    durationSeconds = durationSeconds,
                    audioFilePath = wavFile.absolutePath,
                    triggerType = event.triggerType,
                    transcriptionText = null,
                    transcriptionStatus = if (speechToTextEnabled) TranscriptionStatus.PENDING else TranscriptionStatus.SKIPPED,
                    peakDb = event.peakDb,
                    avgDb = event.avgDb
                )

                val insertedId = database.voiceNoteDao().insertVoiceNote(note).toInt()
                val savedNoteWithId = note.copy(id = insertedId)
                _lastCapturedNoteTitle.value = title
                _lastRecordedNote.value = savedNoteWithId
                _sessionEventCount.value = _sessionEventCount.value + 1

                // Notify user safely
                showNoteSavedNotification(title, durationSeconds)

                // Automatic on-device transcription (opt-in), from the saved file, on the transcription worker
                if (speechToTextEnabled) Transcription.manager(this@AudioMonitoringService).enqueue(insertedId)
            } catch (e: Exception) {
                ErrorLogger.log(this@AudioMonitoringService, "AudioService", "Error saving voice note", e)
            }
        }
    }

    private fun applyMatchConfidenceThreshold(intent: Intent) {
        val percent = intent.getIntExtra(EXTRA_MATCH_CONFIDENCE_THRESHOLD_PERCENT, MatchConfidenceThreshold.DEFAULT_PERCENT)
        memoDetector.matchConfidenceThreshold = MatchConfidenceThreshold.toDetectorConfidence(percent)
    }

    /** Wake-phrase settings changed during a memo; applied when it ends (see ACTION_RELOAD_TEMPLATE). */
    @Volatile
    private var wakePhraseReloadPending = false

    private fun loadSavedKeywordTemplate() {
        try {
            val templates = WakePhraseStore(filesDir).loadSampleTemplates()
            // No enrolled samples (or all deleted): the built-in default wake phrase is used
            val loaded = memoDetector.keywordDetector.loadTemplates(templates)
            if (loaded < templates.size) {
                ErrorLogger.log(this, "AudioService", "${templates.size - loaded} enrolled wake-phrase sample(s) are invalid and were skipped")
            }
            Log.d("AudioService", "Loaded $loaded wake-phrase sample(s)")
        } catch (e: Exception) {
            ErrorLogger.log(this, "AudioService", "Failed to load wake-phrase samples", e)
        }
    }

    private fun registerAudioDeviceListeners() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    updateAudioRoutingStatus(currentAudioRecord)
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    updateAudioRoutingStatus(currentAudioRecord)
                }
            }
            audioManager?.registerAudioDeviceCallback(callback, null)
            audioDeviceCallback = callback
        }

        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    updateAudioRoutingStatus(currentAudioRecord)
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            }
            registerReceiver(receiver, filter)
            headsetReceiver = receiver
        } catch (_: Exception) {}
    }

    private fun unregisterAudioDeviceListeners() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
            } catch (_: Exception) {}
            audioDeviceCallback = null
        }

        if (headsetReceiver != null) {
            try {
                unregisterReceiver(headsetReceiver)
            } catch (_: Exception) {}
            headsetReceiver = null
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val serviceChannel = NotificationChannel(
                CONTROLS_CHANNEL_ID,
                "Monitoring and recording controls",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Monitoring status with a Start/Stop memo button, also on the lock screen. Never makes a sound."
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.deleteNotificationChannel(CHANNEL_ID)

            val noteChannel = NotificationChannel(
                CHANNEL_NOTE_ID,
                "Voice Note Captured",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications when an automatic voice memo is saved"
            }
            notificationManager.createNotificationChannel(noteChannel)
        }
    }

    private fun buildForegroundNotification(statusText: String, recording: Boolean = false): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val lockScreenControls = ShortcutSettings.lockScreenControlsEnabled(
            getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
        )
        val action = if (recording) {
            NotificationCompat.Action.Builder(R.drawable.ic_stop_memo, "Stop memo", servicePendingIntent(ACTION_STOP_MANUAL_RECORDING, 10))
        } else {
            NotificationCompat.Action.Builder(R.drawable.ic_record_memo, "Start memo", servicePendingIntent(ACTION_START_MANUAL_RECORDING, 11))
        }
            // Android 12+: with lock-screen controls off, the action asks for the device to be unlocked first
            .setAuthenticationRequired(!lockScreenControls)
            .build()

        val builder = NotificationCompat.Builder(this, CONTROLS_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(action)
        if (lockScreenControls) {
            // Only the status and the Start/Stop button: nothing private is shown on the lock screen
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        } else {
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(this, CONTROLS_CHANNEL_ID)
                        .setContentTitle(getString(R.string.app_name))
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .build()
                )
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, AudioMonitoringService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
    )

    private fun updateNotificationStatus(state: RecordingLifecycleState) {
        val text = when (state) {
            RecordingLifecycleState.MONITORING -> "Listening hands-free • Mode: ${memoDetector.detectionMode.title}"
            RecordingLifecycleState.RECORDING -> RECORDING_STATUS_TEXT
            else -> "Audio monitoring active"
        }
        try {
            val notification = buildForegroundNotification(text, state == RecordingLifecycleState.RECORDING)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
        RecordingTileService.requestUpdate(this)
    }

    private fun showNoteSavedNotification(title: String, durationSec: Double) {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_NOTE_ID)
                .setContentTitle("Voice Memo Saved")
                .setContentText("$title (${String.format(Locale.US, "%.1fs", durationSec)})")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val notifId = noteNotificationCounter.getAndIncrement()
            notificationManager?.notify(notifId, notification)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        _engineState.value = MonitoringEngineState.STOPPING
        _isServiceRunning.value = false
        RecordingTileService.requestUpdate(this)
        _liveKeywordConfidence.value = 0f
        _liveLevelDbfs.value = null
        _currentRecordingState.value = RecordingLifecycleState.IDLE
        _isCurrentlyRecording.value = false

        // If recording was active, finalize and save before stopping
        try {
            val pendingMemo = memoDetector.stopManualRecording(RecordingStopReason.SERVICE_STOPPED)
            if (pendingMemo != null && pendingMemo.audioChunks.isNotEmpty()) {
                saveCompletedVoiceNote(pendingMemo)
            }
        } catch (_: Exception) {}

        recordingJob?.cancel()
        releaseAudioRecord(currentAudioRecord)
        _engineState.value = MonitoringEngineState.STOPPED
        feedbackSoundPlayer.release()

        unregisterAudioDeviceListeners()

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

