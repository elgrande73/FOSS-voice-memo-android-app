package com.aistudio.ciaolinda.monitoring

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aistudio.ciaolinda.MainActivity
import com.aistudio.ciaolinda.R
import com.aistudio.ciaolinda.audio.AmplitudePoint
import com.aistudio.ciaolinda.audio.AudioInputDevice
import com.aistudio.ciaolinda.audio.AudioInputManager
import com.aistudio.ciaolinda.audio.FeedbackSoundPlayer
import com.aistudio.ciaolinda.audio.MediaPlaybackDetector
import com.aistudio.ciaolinda.audio.WavWriter
import com.aistudio.ciaolinda.detection.DetectionMode
import com.aistudio.ciaolinda.detection.KeywordBehavior
import com.aistudio.ciaolinda.detection.MemoDetectionEvent
import com.aistudio.ciaolinda.detection.MemoTriggerDetector
import com.aistudio.ciaolinda.detection.RecordingLifecycleState
import com.aistudio.ciaolinda.memo.AppDatabase
import com.aistudio.ciaolinda.memo.ErrorLogger
import com.aistudio.ciaolinda.memo.VoiceNote
import com.aistudio.ciaolinda.transcription.AndroidOnDeviceSpeechEngine
import com.aistudio.ciaolinda.transcription.SpeechToTextEngine
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
    private lateinit var speechEngine: SpeechToTextEngine
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

    companion object {
        const val CHANNEL_ID = "ciao_linda_monitoring_channel"
        const val CHANNEL_NOTE_ID = "ciao_linda_note_channel"
        const val NOTIFICATION_ID = 1001
        // Covers thread hand-off, AudioTrack output latency and room echo after the chime
        private const val FEEDBACK_MUTE_TAIL_MS = 450L

        const val ACTION_START_MANUAL_RECORDING = "com.aistudio.ciaolinda.ACTION_START_MANUAL_RECORDING"
        const val ACTION_STOP_MANUAL_RECORDING = "com.aistudio.ciaolinda.ACTION_STOP_MANUAL_RECORDING"
        const val ACTION_RELOAD_TEMPLATE = "com.aistudio.ciaolinda.ACTION_RELOAD_TEMPLATE"

        // Public Observable States for UI
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning = _isServiceRunning.asStateFlow()

        private val _currentRecordingState = MutableStateFlow(RecordingLifecycleState.IDLE)
        val currentRecordingState = _currentRecordingState.asStateFlow()

        private val _isCurrentlyRecording = MutableStateFlow(false)
        val isCurrentlyRecording = _isCurrentlyRecording.asStateFlow()

        private val _recordingDurationSeconds = MutableStateFlow(0)
        val recordingDurationSeconds = _recordingDurationSeconds.asStateFlow()

        private val _liveDbLevel = MutableStateFlow(0f)
        val liveDbLevel = _liveDbLevel.asStateFlow()

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
    }

    override fun onCreate() {
        super.onCreate()
        memoDetector = MemoTriggerDetector()
        mediaPlaybackDetector = MediaPlaybackDetector(this)
        database = AppDatabase.getDatabase(this)
        speechEngine = AndroidOnDeviceSpeechEngine(this)
        feedbackSoundPlayer = FeedbackSoundPlayer(this)

        createNotificationChannels()
        loadSavedKeywordTemplate()

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CiaoLinda::MonitoringWakeLock")?.apply {
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
            ACTION_RELOAD_TEMPLATE -> {
                loadSavedKeywordTemplate()
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
        memoDetector.minTriggerDurationMs = intent.getLongExtra("minTriggerDurationMs", 250L)
        memoDetector.silenceTimeoutMs = intent.getLongExtra("silenceTimeoutMs", 2000L)
        memoDetector.preTriggerDurationMs = intent.getLongExtra("preTriggerDurationMs", 800L)
        memoDetector.maxMemoDurationMs = intent.getLongExtra("maxMemoDurationMs", 300_000L)
        memoDetector.noiseFilterEnabled = intent.getBooleanExtra("noiseFilterEnabled", true)
        memoDetector.keywordSensitivity = intent.getFloatExtra("keywordSensitivity", 0.60f)

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
            val notification = buildForegroundNotification("Monitoring audio...")
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

            while (isActive) {
                var activeRecord: AudioRecord? = null
                try {
                    val record = initializeAudioRecord()
                    if (record == null) {
                        ErrorLogger.log(this@AudioMonitoringService, "AudioService", "Failed to initialize AudioRecord hardware")
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
                        delay(1500)
                        continue
                    }

                    if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        ErrorLogger.log(this@AudioMonitoringService, "AudioService", "AudioRecord not recording after startRecording()")
                        releaseAudioRecord(record)
                        delay(1500)
                        continue
                    }

                    val buffer = ShortArray(chunkSize)
                    val historyPoints = mutableListOf<AmplitudePoint>()

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

                        // Check system media playback status
                        val mediaStatus = mediaPlaybackDetector.checkPlaybackStatus()

                        // Suppress our own feedback chime (plus output latency tail) from the mic stream
                        val isFeedbackMuted = System.currentTimeMillis() < feedbackMuteUntilMs
                        if (isFeedbackMuted) {
                            buffer.fill(0)
                        }

                        // Process frame via State Machine
                        val events = memoDetector.processFrame(
                            buffer,
                            isMediaPlaying = mediaStatus.isMediaPlaying,
                            isFeedbackMuted = isFeedbackMuted
                        )
                        _isVoiceDetected.value = memoDetector.isVoiceDetected
                        _liveNoiseFloorDb.value = memoDetector.noiseFloorDb
                        _liveKeywordConfidence.value = memoDetector.keywordConfidence

                        for (event in events) {
                            when (event) {
                                is MemoDetectionEvent.StateChanged -> {
                                    _currentRecordingState.value = event.newState
                                    _isCurrentlyRecording.value = (event.newState == RecordingLifecycleState.RECORDING)
                                    if (event.newState != RecordingLifecycleState.RECORDING) {
                                        _recordingDurationSeconds.value = 0
                                    }
                                    updateNotificationStatus(event.newState)
                                }
                                is MemoDetectionEvent.TriggerStarted -> {
                                    playFeedbackCue(FeedbackSoundPlayer.Cue.RECORDING_STARTED)
                                }
                                is MemoDetectionEvent.ChunkRecorded -> {
                                    _liveDbLevel.value = event.peakDb
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
                                is MemoDetectionEvent.RecordingCompleted -> {
                                    _isCurrentlyRecording.value = false
                                    _recordingDurationSeconds.value = 0
                                    playFeedbackCue(FeedbackSoundPlayer.Cue.RECORDING_STOPPED)
                                    saveCompletedVoiceNote(event)
                                }
                                else -> {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e !is kotlinx.coroutines.CancellationException) {
                        ErrorLogger.log(this@AudioMonitoringService, "AudioService", "Unexpected exception in audio loop", e)
                        delay(1500)
                    }
                } finally {
                    releaseAudioRecord(activeRecord)
                }
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

    private fun releaseAudioRecord(record: AudioRecord?) {
        if (record == null) return
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
            record.release()
        } catch (_: Exception) {}
        if (currentAudioRecord == record) {
            currentAudioRecord = null
        }
    }

    private fun handleStartManualRecording() {
        memoDetector.startManualRecording()
        playFeedbackCue(FeedbackSoundPlayer.Cue.RECORDING_STARTED)
        _currentRecordingState.value = RecordingLifecycleState.RECORDING
        _isCurrentlyRecording.value = true
        updateNotificationStatus(RecordingLifecycleState.RECORDING)
    }

    private fun handleStopManualRecording() {
        val completed = memoDetector.stopManualRecording()
        _isCurrentlyRecording.value = false
        _recordingDurationSeconds.value = 0
        if (completed != null) {
            playFeedbackCue(FeedbackSoundPlayer.Cue.RECORDING_STOPPED)
            saveCompletedVoiceNote(completed)
        }
        _currentRecordingState.value = RecordingLifecycleState.MONITORING
        updateNotificationStatus(RecordingLifecycleState.MONITORING)
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
                    event.triggerType.contains("KEYWORD") -> "Ciao Linda"
                    event.triggerType.contains("VOICE") -> "Voice Memo"
                    event.triggerType.contains("MANUAL") -> "Manual Memo"
                    else -> "Auto Memo"
                }
                val title = "$prefix ($displayDate)"

                val memosDir = File(filesDir, "memos").apply { mkdirs() }
                val wavFile = File(memosDir, "memo_${dateStr}_${timestamp}.wav")

                // Write WAV file
                WavWriter.writeWavFile(
                    outputFile = wavFile,
                    audioChunks = event.audioChunks,
                    sampleRate = sampleRate,
                    channels = 1
                )

                val durationSeconds = (event.durationMs / 1000.0).coerceAtLeast(0.5)

                val note = VoiceNote(
                    title = title,
                    timestamp = timestamp,
                    durationSeconds = durationSeconds,
                    audioFilePath = wavFile.absolutePath,
                    triggerType = event.triggerType,
                    transcriptionText = null,
                    transcriptionStatus = if (speechToTextEnabled) "PENDING" else "SKIPPED",
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

                // Trigger offline Speech-to-Text transcription if enabled
                if (speechToTextEnabled) {
                    launch(Dispatchers.Main) {
                        try {
                            val text = speechEngine.transcribeAudioFile(wavFile)
                            withContext(Dispatchers.IO) {
                                if (!text.isNullOrBlank()) {
                                    database.voiceNoteDao().updateTranscription(insertedId, text, "COMPLETED")
                                } else {
                                    database.voiceNoteDao().updateTranscription(insertedId, null, "SKIPPED")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("AudioService", "Transcription failed", e)
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.log(this@AudioMonitoringService, "AudioService", "Error saving voice note", e)
            }
        }
    }

    private fun loadSavedKeywordTemplate() {
        val templateFile = File(filesDir, "ciao_linda_template.bin")
        if (templateFile.exists()) {
            try {
                val bytes = templateFile.readBytes()
                memoDetector.loadKeywordTemplate(bytes)
                Log.d("AudioService", "Loaded keyword template (${bytes.size} bytes)")
            } catch (e: Exception) {
                ErrorLogger.log(this, "AudioService", "Failed to load keyword template", e)
            }
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
                CHANNEL_ID,
                "Ciao Linda Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live background voice-note monitoring status"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

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

    private fun buildForegroundNotification(statusText: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ciao Linda")
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotificationStatus(state: RecordingLifecycleState) {
        val text = when (state) {
            RecordingLifecycleState.MONITORING -> "Listening hands-free • Mode: ${memoDetector.detectionMode.title}"
            RecordingLifecycleState.RECORDING -> "🔴 Recording voice memo..."
            else -> "Audio monitoring active"
        }
        try {
            val notification = buildForegroundNotification(text)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.notify(NOTIFICATION_ID, notification)
        } catch (_: Exception) {}
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
        _isServiceRunning.value = false
        _liveKeywordConfidence.value = 0f
        _currentRecordingState.value = RecordingLifecycleState.IDLE
        _isCurrentlyRecording.value = false

        // If recording was active, finalize and save before stopping
        try {
            val pendingMemo = memoDetector.stopManualRecording()
            if (pendingMemo != null && pendingMemo.audioChunks.isNotEmpty()) {
                saveCompletedVoiceNote(pendingMemo)
            }
        } catch (_: Exception) {}

        recordingJob?.cancel()
        releaseAudioRecord(currentAudioRecord)
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

