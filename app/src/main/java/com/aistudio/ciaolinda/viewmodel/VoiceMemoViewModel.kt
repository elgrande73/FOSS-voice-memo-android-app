package com.aistudio.ciaolinda.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.ciaolinda.audio.AmplitudePoint
import com.aistudio.ciaolinda.audio.AudioInputDevice
import com.aistudio.ciaolinda.audio.AudioInputManager
import com.aistudio.ciaolinda.detection.DetectionMode
import com.aistudio.ciaolinda.detection.KeywordBehavior
import com.aistudio.ciaolinda.memo.AppDatabase
import com.aistudio.ciaolinda.memo.ErrorLog
import com.aistudio.ciaolinda.memo.VoiceNote
import com.aistudio.ciaolinda.memo.VoiceNoteExportManager
import com.aistudio.ciaolinda.memo.VoiceNoteRepository
import com.aistudio.ciaolinda.monitoring.AudioMonitoringService
import com.aistudio.ciaolinda.ui.theme.ThemeMode
import com.aistudio.ciaolinda.wakephrase.TemplateKeywordDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

/**
 * Filter categories for voice note listings.
 */
enum class VoiceNoteFilter(val label: String) {
    ALL("All Memos"),
    TODAY("Today"),
    FAVORITES("Favorites"),
    KEYWORD("Ciao Linda")
}

/**
 * ViewModel for Ciao Linda - Automatic Voice-Note App.
 * Manages voice memo persistence, live audio monitoring status, keyword template training,
 * playback lifecycle, and export operations.
 */
class VoiceMemoViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences("ciao_linda_preferences", Context.MODE_PRIVATE)
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

    private val _activeFilter = MutableStateFlow(VoiceNoteFilter.ALL)
    val activeFilter = _activeFilter.asStateFlow()

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

    private val _keywordSensitivity = MutableStateFlow(prefs.getFloat("keywordSensitivity", 0.60f))
    val keywordSensitivity = _keywordSensitivity.asStateFlow()

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
    private val _isRecordingKeyword = MutableStateFlow(false)
    val isRecordingKeyword = _isRecordingKeyword.asStateFlow()

    private val _hasKeywordTemplate = MutableStateFlow(checkKeywordTemplateExists())
    val hasKeywordTemplate = _hasKeywordTemplate.asStateFlow()

    private val _keywordTestScore = MutableStateFlow<Float?>(null)
    val keywordTestScore = _keywordTestScore.asStateFlow()

    private val _isTestingKeyword = MutableStateFlow(false)
    val isTestingKeyword = _isTestingKeyword.asStateFlow()

    private var keywordAudioRecord: AudioRecord? = null
    private val keywordTrainingChunks = mutableListOf<ShortArray>()
    private var keywordTrainingJob: Job? = null

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
        filteredVoiceNotes = combine(allVoiceNotes, _searchQuery, _activeFilter) { notes, query, filter ->
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startOfToday = calendar.timeInMillis

            notes.filter { note ->
                val matchesQuery = if (query.isBlank()) true else {
                    note.title.contains(query, ignoreCase = true) ||
                            (note.transcriptionText?.contains(query, ignoreCase = true) == true)
                }

                val matchesFilter = when (filter) {
                    VoiceNoteFilter.ALL -> true
                    VoiceNoteFilter.TODAY -> note.timestamp >= startOfToday
                    VoiceNoteFilter.FAVORITES -> note.isFavorite
                    VoiceNoteFilter.KEYWORD -> note.triggerType.contains("KEYWORD", ignoreCase = true)
                }

                matchesQuery && matchesFilter
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        timelineDisplayState = AudioMonitoringService.currentSessionData.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        refreshAvailableInputDevices()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
            try {
                audioManager?.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
            } catch (e: Exception) {
                Log.w("CiaoLindaVM", "Failed to register audio device callback: ${e.message}")
            }
        }
    }

    private fun checkKeywordTemplateExists(): Boolean {
        val file = File(context.filesDir, "ciao_linda_template.bin")
        return file.exists() && file.length() > 0
    }

    // --- SERVICE MONITORING & RECORDING CONTROLS ---

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
            putExtra("keywordSensitivity", _keywordSensitivity.value)
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
            Log.e("CiaoLindaVM", "Failed to start AudioMonitoringService", e)
        }
    }

    fun stopServiceDetection() {
        try {
            val intent = Intent(context, AudioMonitoringService::class.java)
            context.stopService(intent)
        } catch (e: Exception) {
            Log.e("CiaoLindaVM", "Failed to stop AudioMonitoringService", e)
        }
    }

    fun startManualRecording() {
        if (!AudioMonitoringService.isServiceRunning.value) {
            startServiceDetection()
        }
        try {
            val intent = Intent(context, AudioMonitoringService::class.java).apply {
                action = AudioMonitoringService.ACTION_START_MANUAL_RECORDING
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e("CiaoLindaVM", "startManualRecording failed", e)
        }
    }

    fun stopManualRecording() {
        try {
            val intent = Intent(context, AudioMonitoringService::class.java).apply {
                action = AudioMonitoringService.ACTION_STOP_MANUAL_RECORDING
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.e("CiaoLindaVM", "stopManualRecording failed", e)
        }
    }

    // --- VOICE NOTE CRUD & PLAYBACK ---

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilter(filter: VoiceNoteFilter) {
        _activeFilter.value = filter
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

    fun deleteVoiceNote(note: VoiceNote) {
        if (_playingNoteId.value == note.id) {
            stopPlayback()
        }
        viewModelScope.launch {
            voiceNoteRepository.deleteVoiceNote(note)
        }
    }

    fun deleteAllVoiceNotes() {
        stopPlayback()
        viewModelScope.launch {
            val all = allVoiceNotes.value
            for (note in all) {
                try {
                    File(note.audioFilePath).delete()
                } catch (_: Exception) {}
            }
            voiceNoteRepository.deleteAll()
        }
    }

    fun playVoiceNote(note: VoiceNote) {
        val audioFile = File(note.audioFilePath)
        if (!audioFile.exists()) return

        if (_playingNoteId.value == note.id && mediaPlayer != null) {
            if (_isPlaying.value) {
                mediaPlayer?.pause()
                _isPlaying.value = false
            } else {
                mediaPlayer?.start()
                _isPlaying.value = true
            }
            return
        }

        stopPlayback()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                setOnPreparedListener { mp ->
                    _playingNoteId.value = note.id
                    _isPlaying.value = true
                    _playbackDurationMs.value = mp.duration
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            mp.playbackParams = mp.playbackParams.setSpeed(_playbackSpeed.value)
                        } catch (_: Exception) {}
                    }
                    mp.start()
                    startPlaybackProgressTracker()
                }
                setOnCompletionListener {
                    stopPlayback()
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("CiaoLindaVM", "Failed to start audio playback", e)
            stopPlayback()
        }
    }

    fun pausePlayback() {
        mediaPlayer?.pause()
        _isPlaying.value = false
    }

    fun seekTo(positionMs: Int) {
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

    fun stopPlayback() {
        playbackProgressJob?.cancel()
        playbackProgressJob = null
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        _playingNoteId.value = null
        _isPlaying.value = false
        _playbackPositionMs.value = 0
        _playbackDurationMs.value = 0
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

    fun exportAllNotesAsZip(onResult: (Intent?) -> Unit) {
        viewModelScope.launch {
            _exportInProgress.value = true
            try {
                val notes = allVoiceNotes.value
                val result = exportManager.exportNotesAsZip(notes)
                if (result != null) {
                    val shareIntent = exportManager.createShareIntent(result, "Share Ciao Linda Archive")
                    onResult(shareIntent)
                } else {
                    onResult(null)
                }
            } catch (e: Exception) {
                Log.e("CiaoLindaVM", "ZIP export failed", e)
                onResult(null)
            } finally {
                _exportInProgress.value = false
            }
        }
    }

    // --- KEYWORD TRAINING & TESTING ---

    fun startRecordingKeywordTemplate() {
        if (_isRecordingKeyword.value) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("CiaoLindaVM", "RECORD_AUDIO permission not granted for keyword recording")
            _isRecordingKeyword.value = false
            return
        }
        _isRecordingKeyword.value = true
        keywordTrainingChunks.clear()

        keywordTrainingJob = viewModelScope.launch(Dispatchers.Default) {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)

            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("CiaoLindaVM", "RECORD_AUDIO permission not granted")
                    return@launch
                }
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBuf
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("CiaoLindaVM", "AudioRecord failed to initialize for keyword training")
                    record.release()
                    return@launch
                }
                keywordAudioRecord = record
                record.startRecording()

                val buffer = ShortArray(800) // 50ms chunks
                val startTime = System.currentTimeMillis()

                // Record up to 2.5 seconds for "Ciao Linda"
                while (isActive && _isRecordingKeyword.value && (System.currentTimeMillis() - startTime < 2500L)) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        keywordTrainingChunks.add(buffer.copyOf(read))
                    }
                }
            } catch (e: SecurityException) {
                Log.e("CiaoLindaVM", "SecurityException during keyword recording", e)
            } catch (e: Exception) {
                Log.e("CiaoLindaVM", "Keyword recording failed", e)
            } finally {
                stopRecordingKeywordTemplateInternal()
            }
        }
    }

    fun stopRecordingKeywordTemplate() {
        stopRecordingKeywordTemplateInternal()
    }

    private fun stopRecordingKeywordTemplateInternal() {
        _isRecordingKeyword.value = false
        keywordTrainingJob?.cancel()
        keywordTrainingJob = null

        try {
            keywordAudioRecord?.stop()
            keywordAudioRecord?.release()
        } catch (_: Exception) {}
        keywordAudioRecord = null

        if (keywordTrainingChunks.size >= 8) {
            viewModelScope.launch(Dispatchers.IO) {
                val success = keywordDetector.recordTemplate(keywordTrainingChunks)
                if (success) {
                    val bytes = keywordDetector.getTemplateData()
                    if (bytes != null) {
                        val file = File(context.filesDir, "ciao_linda_template.bin")
                        file.writeBytes(bytes)
                        _hasKeywordTemplate.value = true

                        // Notify service to reload template if running
                        try {
                            if (AudioMonitoringService.isServiceRunning.value) {
                                val intent = Intent(context, AudioMonitoringService::class.java).apply {
                                    action = AudioMonitoringService.ACTION_RELOAD_TEMPLATE
                                }
                                context.startService(intent)
                            }
                        } catch (e: Exception) {
                            Log.e("CiaoLindaVM", "Failed to send ACTION_RELOAD_TEMPLATE", e)
                        }
                    }
                }
            }
        }
    }

    fun clearKeywordTemplate() {
        val file = File(context.filesDir, "ciao_linda_template.bin")
        if (file.exists()) {
            file.delete()
        }
        keywordDetector.clearTemplate()
        _hasKeywordTemplate.value = false
        _keywordTestScore.value = null

        try {
            if (AudioMonitoringService.isServiceRunning.value) {
                val intent = Intent(context, AudioMonitoringService::class.java).apply {
                    action = AudioMonitoringService.ACTION_RELOAD_TEMPLATE
                }
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e("CiaoLindaVM", "Failed to send ACTION_RELOAD_TEMPLATE on clear", e)
        }
    }

    fun testKeywordAudio() {
        if (_isTestingKeyword.value) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e("CiaoLindaVM", "RECORD_AUDIO permission not granted for keyword test")
            _keywordTestScore.value = 0f
            return
        }
        _isTestingKeyword.value = true
        _keywordTestScore.value = null

        viewModelScope.launch(Dispatchers.Default) {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)

            var testRecord: AudioRecord? = null
            var maxConfidence = 0.0f

            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("CiaoLindaVM", "RECORD_AUDIO permission not granted")
                    return@launch
                }
                val record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBuf
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("CiaoLindaVM", "AudioRecord failed to initialize for keyword testing")
                    record.release()
                    return@launch
                }
                testRecord = record
                record.startRecording()

                val buffer = ShortArray(800)
                val startTime = System.currentTimeMillis()

                // Test for 3.0 seconds
                while (isActive && System.currentTimeMillis() - startTime < 3000L) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val result = keywordDetector.processFrame(buffer.copyOf(read), 0.5f, 50f)
                        if (result.confidence > maxConfidence) {
                            maxConfidence = result.confidence
                            _keywordTestScore.value = maxConfidence
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.e("CiaoLindaVM", "SecurityException during keyword test", e)
            } catch (e: Exception) {
                Log.e("CiaoLindaVM", "Keyword test error", e)
            } finally {
                try {
                    testRecord?.stop()
                    testRecord?.release()
                } catch (_: Exception) {}
                _isTestingKeyword.value = false
                if (_keywordTestScore.value == null) {
                    _keywordTestScore.value = maxConfidence
                }
            }
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

    fun setKeywordSensitivity(sensitivity: Float) {
        _keywordSensitivity.value = sensitivity
        keywordDetector.setSensitivity(sensitivity)
        prefs.edit().putFloat("keywordSensitivity", sensitivity).apply()
        restartServiceIfRunning()
    }

    fun setKeywordBehavior(behavior: KeywordBehavior) {
        _keywordBehavior.value = behavior
        prefs.edit().putString("keywordBehavior", behavior.name).apply()
        restartServiceIfRunning()
    }

    fun setSpeechToTextEnabled(enabled: Boolean) {
        _speechToTextEnabled.value = enabled
        prefs.edit().putBoolean("speechToTextEnabled", enabled).apply()
        restartServiceIfRunning()
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
        setKeywordSensitivity(0.60f)
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
        stopPlayback()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
            try {
                audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
            } catch (_: Exception) {}
        }
        super.onCleared()
    }
}

