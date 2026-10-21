package io.github.elgrande73.voicememo.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.elgrande73.voicememo.audio.AmplitudePoint
import io.github.elgrande73.voicememo.audio.AudioInputDevice
import io.github.elgrande73.voicememo.audio.AudioInputManager
import io.github.elgrande73.voicememo.audio.CaptureOutcome
import io.github.elgrande73.voicememo.audio.CaptureSession
import io.github.elgrande73.voicememo.audio.FeedbackSoundPlayer
import io.github.elgrande73.voicememo.audio.MicrophoneArbiter
import io.github.elgrande73.voicememo.audio.MonitoringControl
import io.github.elgrande73.voicememo.audio.PcmFrameAssembler
import io.github.elgrande73.voicememo.audio.PhraseCaptureCoordinator
import io.github.elgrande73.voicememo.audio.PhraseCaptureKind
import io.github.elgrande73.voicememo.audio.PhraseCaptureState
import io.github.elgrande73.voicememo.audio.kind
import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.KeywordBehavior
import io.github.elgrande73.voicememo.memo.AppDatabase
import io.github.elgrande73.voicememo.memo.ErrorLog
import io.github.elgrande73.voicememo.memo.VoiceNote
import io.github.elgrande73.voicememo.memo.VoiceNoteExportManager
import io.github.elgrande73.voicememo.memo.VoiceNoteRepository
import io.github.elgrande73.voicememo.monitoring.AudioMonitoringService
import io.github.elgrande73.voicememo.playback.ChimePlayer
import io.github.elgrande73.voicememo.playback.MemoAudioOutput
import io.github.elgrande73.voicememo.memo.DeleteReport
import io.github.elgrande73.voicememo.memo.MemoArchive
import io.github.elgrande73.voicememo.memo.MemoDeleter
import io.github.elgrande73.voicememo.memo.MemoFilter
import io.github.elgrande73.voicememo.memo.VoiceNoteDao
import io.github.elgrande73.voicememo.memo.VoiceNoteFilter
import io.github.elgrande73.voicememo.playback.MemoPlaybackSequencer
import io.github.elgrande73.voicememo.playback.PlaybackMode
import io.github.elgrande73.voicememo.playback.PlaybackCue
import io.github.elgrande73.voicememo.settings.MatchConfidenceThreshold
import io.github.elgrande73.voicememo.transcription.ModelDownloadState
import io.github.elgrande73.voicememo.transcription.ModelDownloads
import io.github.elgrande73.voicememo.transcription.ModelSource
import io.github.elgrande73.voicememo.transcription.SpeechModelStore
import io.github.elgrande73.voicememo.transcription.Transcription
import io.github.elgrande73.voicememo.transcription.TranscriptionJobState
import io.github.elgrande73.voicememo.transcription.TranscriptionQuality
import io.github.elgrande73.voicememo.transcription.TranscriptionSettings
import io.github.elgrande73.voicememo.transcription.TranscriptionStatus
import io.github.elgrande73.voicememo.settings.WakePhraseLevel
import io.github.elgrande73.voicememo.shortcuts.HardwareGesture
import io.github.elgrande73.voicememo.shortcuts.KeyboardShortcut
import io.github.elgrande73.voicememo.shortcuts.ShortcutConfig
import io.github.elgrande73.voicememo.shortcuts.ShortcutRuntime
import io.github.elgrande73.voicememo.shortcuts.ShortcutSettings
import io.github.elgrande73.voicememo.settings.PreferenceKeys
import io.github.elgrande73.voicememo.settings.SettingsMigrator
import io.github.elgrande73.voicememo.ui.theme.ThemeMode
import io.github.elgrande73.voicememo.wakephrase.TemplateKeywordDetector
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEnrollment
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEvaluation
import io.github.elgrande73.voicememo.wakephrase.WakePhraseSample
import io.github.elgrande73.voicememo.wakephrase.WakePhraseEvaluator
import io.github.elgrande73.voicememo.wakephrase.WakePhraseStore
import io.github.elgrande73.voicememo.wakephrase.WakePhraseTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/**
 * ViewModel for Voice Memo - Automatic Voice-Note App.
 * Manages voice memo persistence, live audio monitoring status, keyword template training,
 * playback lifecycle, and export operations.
 */
class VoiceMemoViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "VoiceMemoVM"
        const val ENROLLMENT_DURATION_MS = 2500L
        const val TEST_DURATION_MS = 3000L
    }

    private val context = application.applicationContext
    // Migrations run before any setting is read, so legacy or corrupt values never reach the UI or detector
    private val prefs = SettingsMigrator.preferences(context)
    private val wakePhraseStore = WakePhraseStore(context.filesDir)
    private val voiceNoteRepository: VoiceNoteRepository
    private val database = AppDatabase.getDatabase(context)
    private val exportManager = VoiceNoteExportManager(context)
    private val keywordDetector = TemplateKeywordDetector()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    // Voice Notes stream
    val allVoiceNotes: StateFlow<List<VoiceNote>>
    val errorLogs: StateFlow<List<ErrorLog>>

    // Search and Filter State
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    // Active filter chips (empty = all memos); combined as described in MemoFilter
    private val _activeFilters = MutableStateFlow<Set<VoiceNoteFilter>>(emptySet())
    val activeFilters: StateFlow<Set<VoiceNoteFilter>> = _activeFilters.asStateFlow()

    // Filtered Voice Notes
    val filteredVoiceNotes: StateFlow<List<VoiceNote>>

    // Media Player state
    private var mediaPlayer: MediaPlayer? = null
    private var playbackProgressJob: Job? = null

    private val _playingNoteId = MutableStateFlow<Int?>(null)
    val playingNoteId = _playingNoteId.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0)
    val playbackPositionMs = _playbackPositionMs.asStateFlow()

    private val _playbackDurationMs = MutableStateFlow(0)
    val playbackDurationMs = _playbackDurationMs.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed = _playbackSpeed.asStateFlow()

    // Audio Input Device selection state
    private val _availableInputDevices = MutableStateFlow<List<AudioInputDevice>>(listOf(AudioInputDevice.PHONE_MIC))
    val availableInputDevices = _availableInputDevices.asStateFlow()

    private val _selectedAudioInputId = MutableStateFlow(prefs.getInt("selectedAudioInputId", -1))
    val selectedAudioInputId = _selectedAudioInputId.asStateFlow()

    private val _selectedAudioInputName = MutableStateFlow(
        prefs.getString("selectedAudioInputName", AudioInputDevice.PHONE_MIC.name) ?: AudioInputDevice.PHONE_MIC.name
    )
    val selectedAudioInputName = _selectedAudioInputName.asStateFlow()

    // Export progress & status state
    private val _exportInProgress = MutableStateFlow(false)
    val exportInProgress = _exportInProgress.asStateFlow()

    // Detection & Threshold Configurations
    private val _detectionMode = MutableStateFlow(
        try {
            DetectionMode.valueOf(prefs.getString("detectionMode", DetectionMode.KEYWORD_ONLY.name) ?: DetectionMode.KEYWORD_ONLY.name)
        } catch (_: Exception) {
            DetectionMode.KEYWORD_ONLY
        }
    )
    val detectionMode = _detectionMode.asStateFlow()

    private val _thresholdDb = MutableStateFlow(prefs.getFloat("thresholdDb", 48.0f))

    // Optional minimum audio level of the wake phrase (dBFS); written by SettingsMigrator for every installation
    private val _wakePhraseMinLevelEnabled = MutableStateFlow<Boolean>(
        prefs.getBoolean(PreferenceKeys.WAKE_PHRASE_MIN_LEVEL_ENABLED, WakePhraseLevel.DEFAULT_ENABLED)
    )
    val wakePhraseMinLevelEnabled: StateFlow<Boolean> = _wakePhraseMinLevelEnabled.asStateFlow()

    private val _wakePhraseMinLevelDbfs = MutableStateFlow<Float>(
        WakePhraseLevel.clampDbfs(prefs.getFloat(PreferenceKeys.WAKE_PHRASE_MIN_LEVEL_DBFS, WakePhraseLevel.DEFAULT_DBFS))
    )
    val wakePhraseMinLevelDbfs: StateFlow<Float> = _wakePhraseMinLevelDbfs.asStateFlow()

    /** The level condition as the detector uses it (VAD dB scale), or null when it is off. */
    private fun wakePhraseMinLevelDb(): Float? =
        if (_wakePhraseMinLevelEnabled.value) WakePhraseLevel.toDetectorDb(_wakePhraseMinLevelDbfs.value) else null
    val thresholdDb = _thresholdDb.asStateFlow()

    private val _minTriggerDurationMs = MutableStateFlow(prefs.getLong("minTriggerDurationMs", 250L))
    val minTriggerDurationMs = _minTriggerDurationMs.asStateFlow()

    private val _silenceTimeoutMs = MutableStateFlow(prefs.getLong("silenceTimeoutMs", 2000L))
    val silenceTimeoutMs = _silenceTimeoutMs.asStateFlow()

    private val _preTriggerDurationMs = MutableStateFlow(prefs.getLong("preTriggerDurationMs", 800L))
    val preTriggerDurationMs = _preTriggerDurationMs.asStateFlow()

    private val _maxMemoDurationMs = MutableStateFlow(prefs.getLong("maxMemoDurationMs", 300_000L))
    val maxMemoDurationMs = _maxMemoDurationMs.asStateFlow()

    private val _noiseFilterEnabled = MutableStateFlow(prefs.getBoolean("noiseFilterEnabled", true))
    val noiseFilterEnabled = _noiseFilterEnabled.asStateFlow()

    private val _feedbackSoundsEnabled = MutableStateFlow(prefs.getBoolean("feedbackSoundsEnabled", true))
    val feedbackSoundsEnabled = _feedbackSoundsEnabled.asStateFlow()

    // Single authoritative wake-phrase threshold (percent); also sent to the detector in AudioMonitoringService
    private val _matchConfidenceThresholdPercent = MutableStateFlow(
        MatchConfidenceThreshold.clampPercent(
            prefs.getInt(PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT, MatchConfidenceThreshold.DEFAULT_PERCENT)
        )
    )
    val matchConfidenceThresholdPercent = _matchConfidenceThresholdPercent.asStateFlow()

    // One-time notice for installations whose built-in wake phrase changed to "Mango Tango" (SettingsMigrator v4)
    private val _wakePhraseChangedNotice = MutableStateFlow<Boolean>(SettingsMigrator.wakePhraseChangedNotice(prefs))
    val wakePhraseChangedNotice: StateFlow<Boolean> = _wakePhraseChangedNotice.asStateFlow()

    fun dismissWakePhraseChangedNotice() {
        _wakePhraseChangedNotice.value = false
        prefs.edit().remove(PreferenceKeys.WAKE_PHRASE_CHANGED_NOTICE).apply()
    }

    // Recording shortcuts (hardware buttons, external keyboard) and lock-screen controls
    private val _shortcutConfig = MutableStateFlow<ShortcutConfig>(ShortcutSettings.load(prefs))
    val shortcutConfig = _shortcutConfig.asStateFlow()

    private val _lockScreenControlsEnabled = MutableStateFlow<Boolean>(ShortcutSettings.lockScreenControlsEnabled(prefs))
    val lockScreenControlsEnabled: StateFlow<Boolean> = _lockScreenControlsEnabled.asStateFlow()

    /** True while Settings waits for the user to press a new keyboard shortcut. */
    private val _keyboardShortcutCapture = MutableStateFlow(false)
    val keyboardShortcutCapture = _keyboardShortcutCapture.asStateFlow()

    private val _keyboardShortcutMessage = MutableStateFlow<String?>(null)
    val keyboardShortcutMessage = _keyboardShortcutMessage.asStateFlow()

    private val _keywordBehavior = MutableStateFlow(
        try {
            KeywordBehavior.valueOf(prefs.getString("keywordBehavior", KeywordBehavior.START_AND_STOP.name) ?: KeywordBehavior.START_AND_STOP.name)
        } catch (_: Exception) {
            KeywordBehavior.START_AND_STOP
        }
    )
    val keywordBehavior = _keywordBehavior.asStateFlow()

    private val _speechToTextEnabled = MutableStateFlow(prefs.getBoolean("speechToTextEnabled", false))
    val speechToTextEnabled = _speechToTextEnabled.asStateFlow()

    // Keyword Training state
    // Settings phrase recordings (enrollment and test) coordinated with monitoring over the shared microphone
    private val phraseCapture = PhraseCaptureCoordinator(
        arbiter = MicrophoneArbiter.shared,
        monitoring = object : MonitoringControl {
            override val isRunning: Boolean get() = AudioMonitoringService.isServiceRunning.value
            override fun stop() = stopServiceDetection()
            override fun start() = startServiceDetection()
        },
        hasPermission = {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
    )
    val phraseCaptureState: StateFlow<PhraseCaptureState> = phraseCapture.state
    val phraseCaptureError: StateFlow<String?> = phraseCapture.lastError
    @Volatile
    private var stopPhraseRecordingRequested = false

    /** True only while the wake-phrase AudioRecord is really capturing. */
    val isRecordingKeyword: StateFlow<Boolean> = phraseCapture.state
        .map { it == PhraseCaptureState.Recording(PhraseCaptureKind.WAKE_PHRASE) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Result messages of the phrase workflows (e.g. no phrase found)
    private val _phraseMessage = MutableStateFlow<String?>(null)
    val phraseMessage = _phraseMessage.asStateFlow()

    private val _lastTestEvaluation = MutableStateFlow<WakePhraseEvaluation?>(null)
    /** Full evaluation of the latest phrase test in this session (includes the loudness check). */
    val lastTestEvaluation = _lastTestEvaluation.asStateFlow()

    // Samples on disk that cannot be used any more (unsupported format of the first app version)
    private val _unusableSampleCount = MutableStateFlow(0)
    val unusableSampleCount = _unusableSampleCount.asStateFlow()

    // Enrolled wake-phrase samples (1-5, optional); read synchronously so the screen starts with the stored state
    private val _wakePhraseSamples = MutableStateFlow(wakePhraseStore.loadSamples().map { it.toSummary() })
    val wakePhraseSamples = _wakePhraseSamples.asStateFlow()

    private val _hasKeywordTemplate = MutableStateFlow(_wakePhraseSamples.value.isNotEmpty())
    val hasKeywordTemplate = _hasKeywordTemplate.asStateFlow()

    // Last persisted "Test Phrase" result; survives restarts and app updates
    private val _keywordTestResult = MutableStateFlow<WakePhraseTestResult?>(null)
    val keywordTestResult = _keywordTestResult.asStateFlow()

    private val _hasSavedTestRecording = MutableStateFlow(false)
    val hasSavedTestRecording = _hasSavedTestRecording.asStateFlow()

    // Re-scoring the saved test recording (no microphone involved)
    private val _isTestingKeyword = MutableStateFlow(false)
    val isTestingKeyword: StateFlow<Boolean> = combine(_isTestingKeyword, phraseCapture.state) { retesting, capture ->
        retesting || (capture !is PhraseCaptureState.Idle && capture.kind() == PhraseCaptureKind.TEST_PHRASE)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Theming preferences
    private val savedThemeModeStr = prefs.getString("themeMode", "SYSTEM") ?: "SYSTEM"
    private val _themeMode = MutableStateFlow(
        try {
            ThemeMode.valueOf(savedThemeModeStr)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }
    )
    val themeMode = _themeMode.asStateFlow()

    private val _dynamicColor = MutableStateFlow(prefs.getBoolean("dynamicColor", true))
    val dynamicColor = _dynamicColor.asStateFlow()

    // Hardware Audio Device Callbacks
    private val audioDeviceCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                refreshAvailableInputDevices()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                refreshAvailableInputDevices()
            }
        }
    } else null

    val timelineDisplayState: StateFlow<List<AmplitudePoint>>

    init {
        voiceNoteRepository = VoiceNoteRepository(database.voiceNoteDao())
        // Creating the manager resumes memos that were still queued when the app was last stopped
        Transcription.manager(context)

        allVoiceNotes = voiceNoteRepository.allNotes.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        errorLogs = database.errorLogDao().getAllLogs().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Filtered notes stream
        filteredVoiceNotes = combine(allVoiceNotes, _searchQuery, _activeFilters) { notes, query, filters ->
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            MemoFilter.apply(notes, query, filters, calendar.timeInMillis)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        timelineDisplayState = AudioMonitoringService.currentSessionData.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        keywordDetector.setMatchConfidenceThreshold(
            MatchConfidenceThreshold.toDetectorConfidence(_matchConfidenceThresholdPercent.value)
        )
        viewModelScope.launch(Dispatchers.IO) {
            // Restore the enrolled wake phrase so "Test Phrase" uses it after a restart
            reloadWakePhraseSamples()
            _keywordTestResult.value = wakePhraseStore.loadTestResult()
            _hasSavedTestRecording.value = wakePhraseStore.loadTestRecording() != null
        }

        refreshAvailableInputDevices()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
            try {
                audioManager?.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register audio device callback: ${e.message}")
            }
        }
    }

    // --- SERVICE MONITORING & RECORDING CONTROLS ---

    /** Monitoring started or stopped by the user (dashboard); an ongoing phrase recording will respect this choice. */
    fun setMonitoringEnabledByUser(enabled: Boolean) {
        phraseCapture.onUserChangedMonitoring()
        if (enabled) startServiceDetection() else stopServiceDetection()
    }

    fun startServiceDetection() {
        val intent = Intent(context, AudioMonitoringService::class.java).apply {
            putExtra("audioInputId", _selectedAudioInputId.value)
            putExtra("audioInputName", _selectedAudioInputName.value)
            putExtra("detectionMode", _detectionMode.value.name)
            putExtra("thresholdDb", _thresholdDb.value)
            putExtra("minTriggerDurationMs", _minTriggerDurationMs.value)
            putExtra("silenceTimeoutMs", _silenceTimeoutMs.value)
            putExtra("preTriggerDurationMs", _preTriggerDurationMs.value)
            putExtra("maxMemoDurationMs", _maxMemoDurationMs.value)
            putExtra("noiseFilterEnabled", _noiseFilterEnabled.value)
            putExtra(AudioMonitoringService.EXTRA_MATCH_CONFIDENCE_THRESHOLD_PERCENT, _matchConfidenceThresholdPercent.value)
            putExtra(AudioMonitoringService.EXTRA_WAKE_PHRASE_MIN_LEVEL_DB, wakePhraseMinLevelDb() ?: Float.NaN)
            putExtra("keywordBehavior", _keywordBehavior.value.name)
            putExtra("speechToTextEnabled", _speechToTextEnabled.value)
            putExtra("feedbackSoundsEnabled", _feedbackSoundsEnabled.value)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioMonitoringService", e)
        }
    }

    fun stopServiceDetection() {
        try {
            val intent = Intent(context, AudioMonitoringService::class.java)
            context.stopService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop AudioMonitoringService", e)
        }
    }

    fun startManualRecording() {
        if (!AudioMonitoringService.isServiceRunning.value) {
            phraseCapture.onUserChangedMonitoring()
            startServiceDetection()
        }
        try {
            val intent = Intent(context, AudioMonitoringService::class.java).apply {
                action = AudioMonitoringService.ACTION_START_MANUAL_RECORDING
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startManualRecording failed", e)
        }
    }

    /**
     * Starts a memo, or stops the one being recorded (dashboard-equivalent action for keyboard and hardware
     * shortcuts while the app is in the foreground). Starts monitoring if needed, like Quick Memo.
     */
    fun toggleManualRecording() {
        if (AudioMonitoringService.isServiceRunning.value) {
            AudioMonitoringService.requestToggle(context)
        } else {
            startManualRecording()
        }
    }

    fun setHardwareShortcutEnabled(enabled: Boolean) {
        updateShortcutConfig(_shortcutConfig.value.copy(hardwareEnabled = enabled))
    }

    fun setHardwareGesture(gesture: HardwareGesture) {
        updateShortcutConfig(_shortcutConfig.value.copy(hardwareGesture = gesture))
    }

    fun setKeyboardShortcutEnabled(enabled: Boolean) {
        if (enabled && _shortcutConfig.value.keyboardShortcut == null) {
            _keyboardShortcutMessage.value = "Set a key combination first."
            return
        }
        updateShortcutConfig(_shortcutConfig.value.copy(keyboardEnabled = enabled))
    }

    fun startKeyboardShortcutCapture() {
        _keyboardShortcutMessage.value = null
        _keyboardShortcutCapture.value = true
        ShortcutRuntime.captureActive = true
    }

    fun cancelKeyboardShortcutCapture() {
        _keyboardShortcutCapture.value = false
        ShortcutRuntime.captureActive = false
    }

    /**
     * Called with each key press while capturing. Modifier keys alone are ignored (the user is still building the
     * combination); anything else either becomes the new shortcut (and enables it) or is rejected with a reason.
     * @return true if the key was used by the capture.
     */
    fun onShortcutKeyCaptured(keyCode: Int, modifiers: Int): Boolean {
        if (!_keyboardShortcutCapture.value) return false
        val problem = KeyboardShortcut.validate(keyCode, modifiers)
        if (problem == KeyboardShortcut.Problem.MODIFIER_ONLY) return true
        if (problem != null) {
            _keyboardShortcutMessage.value = problem.message
            return true
        }
        val shortcut = KeyboardShortcut(keyCode, modifiers)
        cancelKeyboardShortcutCapture()
        updateShortcutConfig(_shortcutConfig.value.copy(keyboardShortcut = shortcut, keyboardEnabled = true))
        _keyboardShortcutMessage.value = "Shortcut set to ${shortcut.label}."
        return true
    }

    fun clearKeyboardShortcut() {
        updateShortcutConfig(_shortcutConfig.value.copy(keyboardShortcut = null, keyboardEnabled = false))
        _keyboardShortcutMessage.value = null
    }

    private fun updateShortcutConfig(config: ShortcutConfig) {
        ShortcutSettings.save(prefs, config)
        _shortcutConfig.value = ShortcutSettings.load(prefs)
    }

    fun setLockScreenControlsEnabled(enabled: Boolean) {
        _lockScreenControlsEnabled.value = enabled
        ShortcutSettings.setLockScreenControlsEnabled(prefs, enabled)
        if (AudioMonitoringService.isServiceRunning.value) {
            try {
                context.startService(Intent(context, AudioMonitoringService::class.java).setAction(AudioMonitoringService.ACTION_REFRESH_NOTIFICATION))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh notification", e)
            }
        }
    }

    fun stopManualRecording() {
        try {
            val intent = Intent(context, AudioMonitoringService::class.java).apply {
                action = AudioMonitoringService.ACTION_STOP_MANUAL_RECORDING
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "stopManualRecording failed", e)
        }
    }

    // --- VOICE NOTE CRUD & PLAYBACK ---

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** Toggles a filter chip; [VoiceNoteFilter.ALL] clears all filters. */
    fun toggleFilter(filter: VoiceNoteFilter) {
        _activeFilters.value = MemoFilter.toggle(_activeFilters.value, filter)
    }

    fun renameVoiceNote(id: Int, newTitle: String) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            voiceNoteRepository.updateTitle(id, newTitle.trim())
        }
    }

    fun toggleFavorite(note: VoiceNote) {
        viewModelScope.launch {
            voiceNoteRepository.toggleFavorite(note.id, note.isFavorite)
        }
    }

    fun deleteVoiceNote(note: VoiceNote) = deleteMemos(setOf(note.id))

    /** Settings → "Delete All Voice Memos": every memo in the database, through the same safe path. */
    fun deleteAllVoiceNotes() {
        viewModelScope.launch {
            val ids = withContext(Dispatchers.IO) { database.voiceNoteDao().getAllNoteIds() }.toSet()
            deleteMemos(ids)
        }
    }

    // --- SELECTION (multi-select delete / export) ---

    private val memoDeleter = MemoDeleter(database.voiceNoteDao(), File(context.filesDir, "memos"))

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Int>>(emptySet())
    /** Selected memo ids; may include memos hidden by the current filter (see [selectWholeLibrary]). */
    val selectedIds: StateFlow<Set<Int>> = _selectedIds.asStateFlow()

    private val _deleteInProgress = MutableStateFlow(false)
    val deleteInProgress: StateFlow<Boolean> = _deleteInProgress.asStateFlow()

    /** One-off result messages of bulk operations (shown once by the UI, then cleared with [consumeHistoryMessage]). */
    private val _historyMessage = MutableStateFlow<String?>(null)
    val historyMessage: StateFlow<String?> = _historyMessage.asStateFlow()

    fun consumeHistoryMessage() {
        _historyMessage.value = null
    }

    fun startSelection(initialId: Int? = null) {
        _selectionMode.value = true
        _selectedIds.value = setOfNotNull(initialId)
    }

    fun exitSelection() {
        _selectionMode.value = false
        _selectedIds.value = emptySet()
    }

    fun toggleSelected(id: Int) {
        val current = _selectedIds.value
        _selectedIds.value = if (id in current) current - id else current + id
    }

    /** Selects every memo currently shown (filters and search applied); others stay as they are. */
    fun selectAllShown() {
        _selectedIds.value = _selectedIds.value + filteredVoiceNotes.value.map { it.id }
    }

    /** Selects every memo in the library, including those hidden by filters or search. */
    fun selectWholeLibrary() {
        viewModelScope.launch {
            _selectedIds.value = withContext(Dispatchers.IO) { database.voiceNoteDao().getAllNoteIds() }.toSet()
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    /**
     * Deletes exactly [ids] (a snapshot taken when the user confirmed, so later selection changes cannot widen it):
     * audio file and database entry of each memo, nothing else. Playback of a deleted memo is stopped first and its
     * transcription is cancelled. The result (including partial failures) is reported via [historyMessage].
     */
    fun deleteMemos(ids: Set<Int>, onDone: ((DeleteReport) -> Unit)? = null) {
        if (ids.isEmpty() || _deleteInProgress.value) return
        if (playbackSequencer.currentNoteId in ids) stopPlayback()
        _deleteInProgress.value = true
        viewModelScope.launch {
            ids.forEach { transcriptionManager.cancel(it) }
            val report = try {
                memoDeleter.delete(ids)
            } catch (e: Exception) {
                Log.e(TAG, "Deleting memos failed", e)
                DeleteReport(emptySet(), emptySet(), ids)
            }
            _selectedIds.value = _selectedIds.value - report.deleted - report.alreadyGone
            if (_selectedIds.value.isEmpty()) _selectionMode.value = false
            _deleteInProgress.value = false
            _historyMessage.value = describe(report)
            onDone?.invoke(report)
        }
    }

    private fun describe(report: DeleteReport): String = buildString {
        append(if (report.deleted.size == 1) "1 memo deleted." else "${report.deleted.size} memos deleted.")
        if (report.alreadyGone.isNotEmpty()) append(" ${report.alreadyGone.size} had already been deleted.")
        if (report.failed.isNotEmpty()) append(" ${report.failed.size} could not be deleted and were kept unchanged.")
    }

    // --- PLAYBACK: displayed-list queue framed by beginning/ending chimes ---

    private val mainHandler = Handler(Looper.getMainLooper())
    private val playbackChimePlayer = FeedbackSoundPlayer(context)
    private var mediaPlayerPrepared = false
    // False when the user paused (or focus was lost) before the player finished preparing
    private var startWhenPrepared = true
    // Last note explicitly requested, so a memo shown outside the history list (dashboard) can be resolved
    private var requestedNote: VoiceNote? = null

    private val _playbackMode = MutableStateFlow<PlaybackMode>(
        PlaybackMode.fromId(try { prefs.getString(PreferenceKeys.HISTORY_PLAYBACK_MODE, null) } catch (_: ClassCastException) { null })
    )
    /** Autoplay or Single; persisted. */
    val playbackMode: StateFlow<PlaybackMode> = _playbackMode.asStateFlow()

    /** Changes the playback mode without touching the memo that is playing (it applies when that memo ends). */
    fun setPlaybackMode(mode: PlaybackMode) {
        if (mode == _playbackMode.value) return
        _playbackMode.value = mode
        playbackSequencer.autoAdvance = mode == PlaybackMode.AUTOPLAY
        prefs.edit().putString(PreferenceKeys.HISTORY_PLAYBACK_MODE, mode.id).apply()
    }

    private val playbackSequencer = MemoPlaybackSequencer(
        chimes = object : ChimePlayer {
            override fun play(cue: PlaybackCue, onFinished: () -> Unit) {
                val soundCue = when (cue) {
                    PlaybackCue.MEMO_BEGIN -> FeedbackSoundPlayer.Cue.MEMO_BEGIN
                    PlaybackCue.MEMO_END -> FeedbackSoundPlayer.Cue.MEMO_END
                }
                playbackChimePlayer.play(soundCue) { mainHandler.post(onFinished) }
            }
        },
        output = object : MemoAudioOutput {
            override fun start(noteId: Int, token: Long) = startMemoAudio(noteId, token)
            override fun stop() = releaseMediaPlayer()
        },
        isPlayable = { noteId -> findNote(noteId)?.let { File(it.audioFilePath).isFile } == true }
    ).apply {
        autoAdvance = _playbackMode.value == PlaybackMode.AUTOPLAY
        onStateChanged = { state -> onPlaybackStateChanged(state) }
        onUnplayable = { noteId -> Log.w(TAG, "Voice memo $noteId is not playable") }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Interruption (call, other app): pause the memo; a chime in progress cancels the sequence
                if (playbackSequencer.state is MemoPlaybackSequencer.State.PlayingMemo) pausePlayback() else stopPlayback()
            }
        }
    }
    private var audioFocusRequest: AudioFocusRequest? = null

    /**
     * Plays [note], or toggles pause/resume if it is the memo currently playing.
     *
     * @param fromDisplayedList true when started from the history list: after natural completion the next memo of
     *   that list (current filter, search and order) plays automatically. False plays only this memo.
     */
    fun playVoiceNote(note: VoiceNote, fromDisplayedList: Boolean = false) {
        if (playbackSequencer.currentNoteId == note.id) {
            when (playbackSequencer.state) {
                is MemoPlaybackSequencer.State.PlayingMemo -> {
                    if (_isPlaying.value) pausePlayback() else resumePlayback()
                    return
                }
                is MemoPlaybackSequencer.State.BeginChime, is MemoPlaybackSequencer.State.EndChime -> {
                    stopPlayback()
                    return
                }
                MemoPlaybackSequencer.State.Idle -> {}
            }
        }

        if (playbackSequencer.state == MemoPlaybackSequencer.State.Idle && !requestAudioFocus()) {
            Log.w(TAG, "Audio focus denied; not starting playback")
            return
        }
        requestedNote = note
        val queueProvider: (() -> List<Int>)? = if (fromDisplayedList) {
            { filteredVoiceNotes.value.map { it.id } }
        } else null
        playbackSequencer.play(note.id, queueProvider)
    }

    fun pausePlayback() {
        if (playbackSequencer.state !is MemoPlaybackSequencer.State.PlayingMemo) {
            stopPlayback()
            return
        }
        if (mediaPlayerPrepared) {
            try {
                mediaPlayer?.pause()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Pause failed", e)
            }
        } else {
            startWhenPrepared = false
        }
        _isPlaying.value = false
    }

    private fun resumePlayback() {
        if (!requestAudioFocus()) return
        if (!mediaPlayerPrepared) {
            startWhenPrepared = true
            _isPlaying.value = true
            return
        }
        try {
            mediaPlayer?.start()
            _isPlaying.value = true
            startPlaybackProgressTracker()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Resume failed", e)
        }
    }

    fun seekTo(positionMs: Int) {
        if (!mediaPlayerPrepared) return
        mediaPlayer?.seekTo(positionMs)
        _playbackPositionMs.value = positionMs
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && mediaPlayer != null && _isPlaying.value) {
            try {
                mediaPlayer?.playbackParams = mediaPlayer!!.playbackParams.setSpeed(speed)
            } catch (_: Exception) {}
        }
    }

    /** Manual stop: no ending chime and no automatic advance. */
    fun stopPlayback() {
        playbackSequencer.stop()
        releaseMediaPlayer()
    }

    private fun findNote(noteId: Int): VoiceNote? =
        allVoiceNotes.value.firstOrNull { it.id == noteId }
            ?: filteredVoiceNotes.value.firstOrNull { it.id == noteId }
            ?: requestedNote?.takeIf { it.id == noteId }

    private fun startMemoAudio(noteId: Int, token: Long) {
        releaseMediaPlayer()
        val note = findNote(noteId)
        if (note == null) {
            mainHandler.post { playbackSequencer.onMemoFailed(token) }
            return
        }
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(note.audioFilePath)
                setOnPreparedListener { mp ->
                    if (mp !== mediaPlayer) return@setOnPreparedListener
                    mediaPlayerPrepared = true
                    _playbackDurationMs.value = mp.duration
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            mp.playbackParams = mp.playbackParams.setSpeed(_playbackSpeed.value)
                        } catch (_: Exception) {}
                    }
                    if (startWhenPrepared) {
                        mp.start()
                        _isPlaying.value = true
                        startPlaybackProgressTracker()
                    }
                }
                setOnCompletionListener { mp ->
                    if (mp === mediaPlayer) playbackSequencer.onMemoCompleted(token)
                }
                setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "Playback error for memo $noteId (what=$what, extra=$extra)")
                    if (mp === mediaPlayer) mainHandler.post { playbackSequencer.onMemoFailed(token) }
                    true // handled: suppresses the completion callback
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio playback", e)
            mainHandler.post { playbackSequencer.onMemoFailed(token) }
        }
    }

    private fun releaseMediaPlayer() {
        playbackProgressJob?.cancel()
        playbackProgressJob = null
        val player = mediaPlayer
        mediaPlayer = null
        mediaPlayerPrepared = false
        startWhenPrepared = true
        try {
            player?.release()
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer release failed", e)
        }
    }

    private fun onPlaybackStateChanged(state: MemoPlaybackSequencer.State) {
        when (state) {
            MemoPlaybackSequencer.State.Idle -> {
                _playingNoteId.value = null
                _isPlaying.value = false
                _playbackPositionMs.value = 0
                _playbackDurationMs.value = 0
                abandonAudioFocus()
            }
            is MemoPlaybackSequencer.State.BeginChime -> {
                _playingNoteId.value = state.noteId
                _isPlaying.value = true
                _playbackPositionMs.value = 0
                _playbackDurationMs.value = 0
            }
            is MemoPlaybackSequencer.State.PlayingMemo -> _playingNoteId.value = state.noteId
            is MemoPlaybackSequencer.State.EndChime -> {
                _isPlaying.value = false
                _playbackPositionMs.value = _playbackDurationMs.value
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val manager = audioManager ?: return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusListener, mainHandler)
                .build()
                .also { audioFocusRequest = it }
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(audioFocusListener)
        }
    }

    private fun startPlaybackProgressTracker() {
        playbackProgressJob?.cancel()
        playbackProgressJob = viewModelScope.launch {
            while (isActive && _isPlaying.value && mediaPlayer != null) {
                try {
                    _playbackPositionMs.value = mediaPlayer?.currentPosition ?: 0
                } catch (_: Exception) {}
                delay(100)
            }
        }
    }

    // --- EXPORT & SHARE ---

    suspend fun prepareSingleNoteShare(note: VoiceNote): Intent? = withContext(Dispatchers.IO) {
        val exportResult = exportManager.prepareSingleNoteForSharing(note) ?: return@withContext null
        exportManager.createShareIntent(exportResult, "Share Memo: ${note.title}")
    }

    private var exportJob: Job? = null

    /**
     * Exports [ids] (one memo: its original WAV; several: a ZIP with the original WAVs and a manifest) to [destination],
     * a document the user created with the system file picker (Storage Access Framework; no storage permission).
     * Source files are only read. Cancel with [cancelExport]; a partial file is then removed.
     */
    fun exportMemosTo(ids: Set<Int>, destination: Uri) {
        if (_exportInProgress.value) return
        _exportInProgress.value = true
        exportJob = viewModelScope.launch {
            val message = try {
                val notes = orderedNotes(ids)
                withContext(Dispatchers.IO) {
                    val job = coroutineContext[Job]
                    val out = requireNotNull(context.contentResolver.openOutputStream(destination, "w")) { "Cannot open the destination" }
                    try {
                        if (notes.size == 1) {
                            if (MemoArchive.writeWav(notes[0], out) { job?.isActive == false }) "Memo exported."
                            else throw java.io.IOException("The memo's audio file is missing")
                        } else {
                            val report = out.use { MemoArchive.writeZip(notes, it, { job?.isActive == false }) }
                            if (report.missing.isEmpty()) "${report.exported} memos exported."
                            else "${report.exported} memos exported; ${report.missing.size} without audio (listed in the manifest)."
                        }
                    } catch (e: Throwable) {
                        // Never leave a truncated export behind
                        try { DocumentsContract.deleteDocument(context.contentResolver, destination) } catch (_: Exception) {}
                        throw e
                    }
                }
            } catch (e: CancellationException) {
                "Export cancelled. Nothing was changed."
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                "Export failed: ${e.message ?: e.javaClass.simpleName}. Your memos were not changed."
            }
            _exportInProgress.value = false
            _historyMessage.value = message
        }
    }

    /** Shares [ids] with another app (one memo: WAV; several: ZIP as in [exportMemosTo]). */
    fun shareMemos(ids: Set<Int>, onResult: (Intent?) -> Unit) {
        if (_exportInProgress.value) return
        _exportInProgress.value = true
        exportJob = viewModelScope.launch {
            try {
                val notes = orderedNotes(ids)
                val result = if (notes.size == 1) exportManager.prepareSingleNoteForSharing(notes[0]) else exportManager.exportNotesAsZip(notes)
                onResult(result?.let { exportManager.createShareIntent(it) })
            } catch (e: CancellationException) {
                _historyMessage.value = "Export cancelled. Nothing was changed."
            } catch (e: Exception) {
                Log.e(TAG, "Share failed", e)
                onResult(null)
            } finally {
                _exportInProgress.value = false
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
    }

    /** Suggested file name and MIME type for exporting [ids]. */
    fun exportTarget(ids: Set<Int>): Pair<String, String> {
        val single = if (ids.size == 1) allVoiceNotes.value.firstOrNull { it.id in ids } else null
        return if (single != null) MemoArchive.baseName(single) + ".wav" to "audio/wav"
        else MemoArchive.zipName(ids.size) to "application/zip"
    }

    /** Current database rows of [ids], newest first (the library order); memos deleted meanwhile are skipped. */
    private suspend fun orderedNotes(ids: Set<Int>): List<VoiceNote> = withContext(Dispatchers.IO) {
        ids.toList().chunked(VoiceNoteDao.SQL_CHUNK).flatMap { database.voiceNoteDao().getNotesByIds(it) }.sortedByDescending { it.timestamp }
    }

    fun exportAllNotesAsZip(onResult: (Intent?) -> Unit) {
        viewModelScope.launch {
            _exportInProgress.value = true
            try {
                val notes = allVoiceNotes.value
                val result = exportManager.exportNotesAsZip(notes)
                if (result != null) {
                    val shareIntent = exportManager.createShareIntent(result, "Share Voice Memo Archive")
                    onResult(shareIntent)
                } else {
                    onResult(null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "ZIP export failed", e)
                onResult(null)
            } finally {
                _exportInProgress.value = false
            }
        }
    }

    // --- KEYWORD TRAINING & TESTING ---

    /**
     * Records one sample of the wake phrase (up to 2.5 s, "Stop Recording" ends it early): a new sample, or a
     * replacement for sample [replaceSampleId]. Monitoring is stopped first and the recording only starts once
     * monitoring has released the microphone; see [PhraseCaptureCoordinator]. Existing samples are kept if the new
     * recording is rejected, fails or is cancelled.
     */
    fun startRecordingKeywordTemplate(replaceSampleId: Long? = null) {
        if (phraseCapture.isBusy) return // repeated taps
        if (replaceSampleId == null && _wakePhraseSamples.value.size >= WakePhraseStore.MAX_SAMPLES) {
            _phraseMessage.value = "You already have ${WakePhraseStore.MAX_SAMPLES} samples. Replace or delete one to record another."
            return
        }
        _phraseMessage.value = null
        stopPhraseRecordingRequested = false
        viewModelScope.launch(Dispatchers.Default) {
            val outcome = phraseCapture.runCapture(PhraseCaptureKind.WAKE_PHRASE) { session ->
                val frames = capturePhrase(session, ENROLLMENT_DURATION_MS) { stopPhraseRecordingRequested }
                // Enrolled and saved before monitoring is restored, so the restarted service loads the new sample
                enrollWakePhraseSample(frames, replaceSampleId)
            }
            if (outcome is CaptureOutcome.Completed) _phraseMessage.value = outcome.value
        }
    }

    /** Explicit user action: deletes one enrolled sample. */
    fun deleteWakePhraseSample(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            wakePhraseStore.deleteSample(id)
            reloadWakePhraseSamples()
            notifyServiceTemplateChanged()
        }
    }

    fun stopRecordingKeywordTemplate() {
        stopPhraseRecordingRequested = true
    }

    /** Explicit user action: deletes all enrolled samples. The saved test recording and result are kept. */
    fun clearKeywordTemplate() {
        wakePhraseStore.clearSamples()
        reloadWakePhraseSamples()
        notifyServiceTemplateChanged()
    }

    private fun reloadWakePhraseSamples() {
        val samples = wakePhraseStore.loadSamples()
        val loaded = keywordDetector.loadTemplates(samples.map { it.templateBytes })
        if (loaded < samples.size) Log.w(TAG, "${samples.size - loaded} wake-phrase sample(s) could not be read")
        _wakePhraseSamples.value = samples.map { it.toSummary() }
        _hasKeywordTemplate.value = samples.isNotEmpty()
        _unusableSampleCount.value = wakePhraseStore.unusableSampleCount()
    }

    /**
     * Records a 3 s test phrase (monitoring is paused exactly as for enrollment) and evaluates it with the monitoring
     * pipeline ([WakePhraseEvaluator]); the recording and the result are saved.
     */
    fun testKeywordAudio() {
        if (phraseCapture.isBusy) return
        _phraseMessage.value = null
        viewModelScope.launch(Dispatchers.Default) {
            val outcome = phraseCapture.runCapture(PhraseCaptureKind.TEST_PHRASE) { session ->
                capturePhrase(session, TEST_DURATION_MS) { false }
            }
            if (outcome is CaptureOutcome.Completed && outcome.value.isNotEmpty()) {
                val evaluation = evaluateTestFrames(outcome.value)
                persistTestRecording(outcome.value, WakePhraseTestResult(evaluation.bestConfidence, System.currentTimeMillis()))
            }
        }
    }

    /** Re-scores the saved test recording against the current wake phrase and settings (no microphone needed). */
    fun retestSavedRecording() {
        if (_isTestingKeyword.value || phraseCapture.isBusy) return
        _isTestingKeyword.value = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val frames = wakePhraseStore.loadTestRecording()
                if (frames == null) {
                    _phraseMessage.value = "The saved test recording could not be read."
                    return@launch
                }
                val evaluation = evaluateTestFrames(frames)
                val result = WakePhraseTestResult(evaluation.bestConfidence, System.currentTimeMillis())
                _keywordTestResult.value = result
                wakePhraseStore.saveTestResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Re-testing saved recording failed", e)
                _phraseMessage.value = "Re-testing failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _isTestingKeyword.value = false
            }
        }
    }

    /**
     * Captures 16 kHz mono PCM from the microphone (the same format as monitoring) into 50 ms frames. Only called by
     * [PhraseCaptureCoordinator] while it holds the microphone; the AudioRecord is released before returning.
     */
    private suspend fun capturePhrase(session: CaptureSession, maxDurationMs: Long, stopRequested: () -> Boolean): List<ShortArray> =
        withContext(Dispatchers.Default) {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("RECORD_AUDIO permission not granted")
            }
            val record = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, minBuf)
            val frames = mutableListOf<ShortArray>()
            try {
                check(record.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not be opened" }
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "The microphone did not start recording" }
                session.recordingStarted()

                val assembler = PcmFrameAssembler()
                val buffer = ShortArray(PcmFrameAssembler.DEFAULT_FRAME_SIZE)
                val startTime = System.currentTimeMillis()
                while (isActive && !stopRequested() && System.currentTimeMillis() - startTime < maxDurationMs) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read < 0) throw IllegalStateException("Microphone read failed ($read)")
                    assembler.push(buffer, read) { frames += it }
                }
            } finally {
                try {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "AudioRecord stop failed", e)
                }
                record.release()
            }
            frames
        }

    /** Validates and stores a recorded sample; returns the message for the user. Existing samples stay on failure. */
    private fun enrollWakePhraseSample(frames: List<ShortArray>, replaceSampleId: Long?): String {
        val others = wakePhraseStore.loadSamples().filter { it.id != replaceSampleId }.map { it.templateBytes }
        val accepted = when (val result = WakePhraseEnrollment.validate(frames, existingSamples = others)) {
            is WakePhraseEnrollment.Result.Rejected -> return result.reason.message
            is WakePhraseEnrollment.Result.Accepted -> result
        }
        val stored = try {
            if (replaceSampleId != null) {
                wakePhraseStore.replaceSample(replaceSampleId, accepted.templateBytes, accepted.info.phraseDurationMs, accepted.info.peakDbfs)
            } else {
                wakePhraseStore.addSample(accepted.templateBytes, accepted.info.phraseDurationMs, accepted.info.peakDbfs) != null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save wake-phrase sample", e)
            false
        }
        if (!stored) return "The sample could not be saved. Your existing samples were kept."
        reloadWakePhraseSamples()
        notifyServiceTemplateChanged()
        val count = _wakePhraseSamples.value.size
        return if (replaceSampleId != null) "Sample replaced ($count of ${WakePhraseStore.MAX_SAMPLES})."
        else "Sample saved ($count of ${WakePhraseStore.MAX_SAMPLES})."
    }

    private fun evaluateTestFrames(frames: List<ShortArray>): WakePhraseEvaluation {
        val evaluation = WakePhraseEvaluator.evaluate(
            frames = frames,
            templates = wakePhraseStore.loadSampleTemplates(),
            matchConfidenceThreshold = MatchConfidenceThreshold.toDetectorConfidence(_matchConfidenceThresholdPercent.value),
            minLevelDb = wakePhraseMinLevelDb()
        )
        _lastTestEvaluation.value = evaluation
        return evaluation
    }

    private fun notifyServiceTemplateChanged() {
        try {
            if (AudioMonitoringService.isServiceRunning.value) {
                context.startService(Intent(context, AudioMonitoringService::class.java).apply {
                    action = AudioMonitoringService.ACTION_RELOAD_TEMPLATE
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ACTION_RELOAD_TEMPLATE", e)
        }
    }

    private fun persistTestRecording(frames: List<ShortArray>, result: WakePhraseTestResult) {
        _keywordTestResult.value = result
        try {
            wakePhraseStore.saveTestRecording(frames)
            wakePhraseStore.saveTestResult(result)
            _hasSavedTestRecording.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save wake-phrase test recording", e)
        }
    }

    // --- SETTINGS PERSISTENCE ---

    fun setDetectionMode(mode: DetectionMode) {
        _detectionMode.value = mode
        prefs.edit().putString("detectionMode", mode.name).apply()
        restartServiceIfRunning()
    }

    fun setThresholdDb(db: Float) {
        _thresholdDb.value = db
        prefs.edit().putFloat("thresholdDb", db).apply()
        restartServiceIfRunning()
    }

    fun setWakePhraseMinLevelEnabled(enabled: Boolean) {
        _wakePhraseMinLevelEnabled.value = enabled
        prefs.edit().putBoolean(PreferenceKeys.WAKE_PHRASE_MIN_LEVEL_ENABLED, enabled).apply()
        restartServiceIfRunning()
    }

    fun setWakePhraseMinLevelDbfs(dbfs: Float) {
        val clamped = WakePhraseLevel.clampDbfs(dbfs)
        _wakePhraseMinLevelDbfs.value = clamped
        prefs.edit().putFloat(PreferenceKeys.WAKE_PHRASE_MIN_LEVEL_DBFS, clamped).apply()
        restartServiceIfRunning()
    }

    fun setMinTriggerDurationMs(ms: Long) {
        _minTriggerDurationMs.value = ms
        prefs.edit().putLong("minTriggerDurationMs", ms).apply()
        restartServiceIfRunning()
    }

    fun setSilenceTimeoutMs(ms: Long) {
        _silenceTimeoutMs.value = ms
        prefs.edit().putLong("silenceTimeoutMs", ms).apply()
        restartServiceIfRunning()
    }

    fun setPreTriggerDurationMs(ms: Long) {
        _preTriggerDurationMs.value = ms
        prefs.edit().putLong("preTriggerDurationMs", ms).apply()
        restartServiceIfRunning()
    }

    fun setMaxMemoDurationMs(ms: Long) {
        _maxMemoDurationMs.value = ms
        prefs.edit().putLong("maxMemoDurationMs", ms).apply()
        restartServiceIfRunning()
    }

    fun setNoiseFilterEnabled(enabled: Boolean) {
        _noiseFilterEnabled.value = enabled
        prefs.edit().putBoolean("noiseFilterEnabled", enabled).apply()
        restartServiceIfRunning()
    }

    fun setFeedbackSoundsEnabled(enabled: Boolean) {
        _feedbackSoundsEnabled.value = enabled
        prefs.edit().putBoolean("feedbackSoundsEnabled", enabled).apply()
        restartServiceIfRunning()
    }

    private val previewSoundPlayer = FeedbackSoundPlayer(context)

    /** Plays the start chime followed by the stop chime so the user can check the volume. */
    fun previewFeedbackSounds() {
        previewSoundPlayer.play(FeedbackSoundPlayer.Cue.RECORDING_STARTED)
        previewSoundPlayer.play(FeedbackSoundPlayer.Cue.RECORDING_STOPPED)
    }

    /**
     * Sets the Match confidence threshold (percent, clamped to 20–80). Persisted as the single authoritative value and
     * applied live to the running detector without restarting monitoring.
     */
    fun setMatchConfidenceThresholdPercent(percent: Int) {
        val clamped = MatchConfidenceThreshold.clampPercent(percent)
        if (clamped == _matchConfidenceThresholdPercent.value) return
        _matchConfidenceThresholdPercent.value = clamped
        keywordDetector.setMatchConfidenceThreshold(MatchConfidenceThreshold.toDetectorConfidence(clamped))
        prefs.edit().putInt(PreferenceKeys.MATCH_CONFIDENCE_THRESHOLD_PERCENT, clamped).apply()
        if (AudioMonitoringService.isServiceRunning.value) {
            try {
                context.startService(Intent(context, AudioMonitoringService::class.java).apply {
                    action = AudioMonitoringService.ACTION_UPDATE_MATCH_CONFIDENCE_THRESHOLD
                    putExtra(AudioMonitoringService.EXTRA_MATCH_CONFIDENCE_THRESHOLD_PERCENT, clamped)
                })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update match confidence threshold in service", e)
            }
        }
    }

    fun setKeywordBehavior(behavior: KeywordBehavior) {
        _keywordBehavior.value = behavior
        prefs.edit().putString("keywordBehavior", behavior.name).apply()
        restartServiceIfRunning()
    }

    fun setSpeechToTextEnabled(enabled: Boolean) {
        _speechToTextEnabled.value = enabled
        prefs.edit().putBoolean(PreferenceKeys.SPEECH_TO_TEXT_ENABLED, enabled).apply()
        restartServiceIfRunning()
    }

    // --- ON-DEVICE TRANSCRIPTION ---

    private val transcriptionManager by lazy { Transcription.manager(context) }
    private val modelStore by lazy { Transcription.modelStore(context) }

    /** Queue/progress state per memo id (memos not listed are idle). */
    val transcriptionJobs: StateFlow<Map<Int, TranscriptionJobState>> get() = transcriptionManager.jobs

    private val _transcriptionLanguage = MutableStateFlow<String>(
        prefs.getString(PreferenceKeys.TRANSCRIPTION_LANGUAGE, null) ?: TranscriptionSettings.AUTO_LANGUAGE
    )
    val transcriptionLanguage: StateFlow<String> = _transcriptionLanguage.asStateFlow()

    private val _transcriptionQuality = MutableStateFlow<TranscriptionQuality>(Transcription.settings(context).quality)
    val transcriptionQuality: StateFlow<TranscriptionQuality> = _transcriptionQuality.asStateFlow()

    private val _speechModels = MutableStateFlow<List<SpeechModelSummary>>(emptyList())
    val speechModels: StateFlow<List<SpeechModelSummary>> = _speechModels.asStateFlow()

    private val _activeSpeechModelId = MutableStateFlow<String>(prefs.getString(SpeechModelStore.PREF_ACTIVE_MODEL, null) ?: ModelSource.WHISPER_BASE.id)
    val activeSpeechModelId: StateFlow<String> = _activeSpeechModelId.asStateFlow()

    private val _modelMessage = MutableStateFlow<String?>(null)
    val modelMessage: StateFlow<String?> = _modelMessage.asStateFlow()

    private val _modelImportRunning = MutableStateFlow(false)
    val modelImportRunning: StateFlow<Boolean> = _modelImportRunning.asStateFlow()

    fun transcribeNote(note: VoiceNote) = transcriptionManager.enqueue(note.id)

    fun cancelTranscription(note: VoiceNote) = transcriptionManager.cancel(note.id)

    /** Saves a corrected transcript (the audio is not changed). */
    fun editTranscript(note: VoiceNote, text: String) {
        transcriptionManager.clearState(note.id)
        viewModelScope.launch(Dispatchers.IO) {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                database.voiceNoteDao().updateTranscription(note.id, null, TranscriptionStatus.SKIPPED)
            } else {
                database.voiceNoteDao().updateTranscription(note.id, trimmed, TranscriptionStatus.COMPLETED)
            }
        }
    }

    /** Removes only the transcript; the recording stays. */
    fun deleteTranscript(note: VoiceNote) {
        transcriptionManager.cancel(note.id)
        transcriptionManager.clearState(note.id)
        viewModelScope.launch(Dispatchers.IO) {
            database.voiceNoteDao().updateTranscription(note.id, null, TranscriptionStatus.SKIPPED)
        }
    }

    fun setTranscriptionLanguage(code: String) {
        _transcriptionLanguage.value = code
        prefs.edit().putString(PreferenceKeys.TRANSCRIPTION_LANGUAGE, code).apply()
    }

    fun setTranscriptionQuality(quality: TranscriptionQuality) {
        _transcriptionQuality.value = quality
        prefs.edit().putString(PreferenceKeys.TRANSCRIPTION_QUALITY, quality.name).apply()
    }

    init {
        // Declared after the model store: reflect an installed or finished download in the model list
        viewModelScope.launch(Dispatchers.IO) {
            ModelDownloads.refresh(context)
            ModelDownloads.state.collect { state ->
                if (state == ModelDownloadState.Ready) {
                    refreshSpeechModels()
                    // Memos that failed only because the model was missing are transcribed now
                    transcriptionManager.jobs.value
                        .filter { (_, job) -> (job as? TranscriptionJobState.Failed)?.modelMissing == true }
                        .keys.forEach { transcriptionManager.enqueue(it) }
                }
            }
        }
    }

    /** Download of the Whisper base model (Settings); the app contains no speech model. */
    val modelDownloadState: StateFlow<ModelDownloadState> = ModelDownloads.state

    /** Starts the download (user action only). A running download is not started twice. */
    fun downloadSpeechModel() {
        _modelMessage.value = null
        ModelDownloads.start(context)
    }

    fun cancelSpeechModelDownload() = ModelDownloads.cancel(context)

    /**
     * Removes the downloaded model. Queued and running transcriptions are cancelled first (their memos keep their
     * previous text); transcripts and recordings are not touched.
     */
    fun removeDownloadedSpeechModel() {
        if (ModelDownloads.isRunning) return
        transcriptionManager.jobs.value.keys.forEach { transcriptionManager.cancel(it) }
        viewModelScope.launch(Dispatchers.IO) {
            transcriptionManager.releaseModel()
            val removed = modelStore.removeDownloaded()
            ModelDownloads.refresh(context)
            _modelMessage.value = if (removed) "Speech model removed. Your transcripts and recordings are kept." else null
            refreshSpeechModels()
        }
    }

    fun refreshSpeechModels() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = modelStore.installedModels().map { SpeechModelSummary(it.id, it.displayName, it.description, downloaded = it.downloaded) }
            _speechModels.value = list
            _activeSpeechModelId.value = modelStore.activeModelOrNull()?.id ?: ModelSource.WHISPER_BASE.id
        }
    }

    fun selectSpeechModel(id: String) {
        modelStore.activeModelId = id
        _activeSpeechModelId.value = id
        transcriptionManager.releaseModel()
    }

    fun deleteSpeechModel(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (modelStore.delete(id)) {
                transcriptionManager.releaseModel()
                _modelMessage.value = "Model removed."
            }
            refreshSpeechModels()
        }
    }

    /** Imports a whisper.cpp model file chosen by the user (no network access involved). */
    fun importSpeechModel(uri: android.net.Uri) {
        if (_modelImportRunning.value) return
        _modelImportRunning.value = true
        _modelMessage.value = "Checking the model…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                var name = ""
                var size: Long? = null
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val n = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val s = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (n >= 0) name = cursor.getString(n) ?: ""
                        if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s)
                    }
                }
                val result = resolver.openInputStream(uri)?.use { input ->
                    modelStore.import(input, name.removeSuffix(".bin"), size) { file -> Transcription.engine(context).canLoad(file) }
                } ?: SpeechModelStore.ImportResult.Rejected("The file could not be opened.")
                _modelMessage.value = when (result) {
                    is SpeechModelStore.ImportResult.Imported -> "Imported ${result.model.displayName} (${result.model.description}). Select it to use it."
                    is SpeechModelStore.ImportResult.Rejected -> result.reason
                }
            } catch (e: Exception) {
                Log.e(TAG, "Model import failed", e)
                _modelMessage.value = "The model could not be imported."
            } finally {
                _modelImportRunning.value = false
                refreshSpeechModels()
            }
        }
    }

    fun setSelectedAudioInput(device: AudioInputDevice) {
        _selectedAudioInputId.value = device.id
        _selectedAudioInputName.value = device.name
        prefs.edit()
            .putInt("selectedAudioInputId", device.id)
            .putString("selectedAudioInputName", device.name)
            .apply()
        restartServiceIfRunning()
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("themeMode", mode.name).apply()
    }

    fun setDynamicColor(enabled: Boolean) {
        _dynamicColor.value = enabled
        prefs.edit().putBoolean("dynamicColor", enabled).apply()
    }

    fun resetAllSettingsToDefaults() {
        setThresholdDb(48.0f)
        setMinTriggerDurationMs(250L)
        setSilenceTimeoutMs(2000L)
        setPreTriggerDurationMs(800L)
        setMaxMemoDurationMs(300_000L)
        setNoiseFilterEnabled(true)
        setFeedbackSoundsEnabled(true)
        setMatchConfidenceThresholdPercent(MatchConfidenceThreshold.DEFAULT_PERCENT)
        setKeywordBehavior(KeywordBehavior.START_AND_STOP)
        setSpeechToTextEnabled(false)
        setDetectionMode(DetectionMode.AUTO_SOUND)
    }

    private fun restartServiceIfRunning() {
        if (AudioMonitoringService.isServiceRunning.value) {
            startServiceDetection()
        }
    }

    fun refreshAvailableInputDevices() {
        val devices = AudioInputManager.getAvailableInputDevices(context)
        _availableInputDevices.value = devices
    }

    fun clearErrorLogs() {
        viewModelScope.launch {
            database.errorLogDao().deleteAllLogs()
        }
    }

    override fun onCleared() {
        cancelKeyboardShortcutCapture()
        stopPlayback()
        playbackChimePlayer.release()
        previewSoundPlayer.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
            try {
                audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
            } catch (_: Exception) {}
        }
        super.onCleared()
    }
}

/** What the Settings screen shows about an enrolled sample (no audio, no features). */
data class WakePhraseSampleSummary(val id: Long, val createdAt: Long, val phraseDurationMs: Long)

private fun WakePhraseSample.toSummary() = WakePhraseSampleSummary(id = id, createdAt = createdAt, phraseDurationMs = phraseDurationMs)

/** A speech-to-text model as shown in Settings. */
data class SpeechModelSummary(val id: String, val name: String, val description: String, val downloaded: Boolean)
