package io.github.elgrande73.voicememo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import io.github.elgrande73.voicememo.memo.MemoSource
import io.github.elgrande73.voicememo.transcription.ModelDownloadState
import io.github.elgrande73.voicememo.transcription.ModelSource
import io.github.elgrande73.voicememo.playback.PlaybackMode
import io.github.elgrande73.voicememo.ui.PlayingMemoAutoScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.focus.onFocusChanged
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.elgrande73.voicememo.memo.VoiceNote
import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.KeywordBehavior
import io.github.elgrande73.voicememo.audio.PhraseCaptureKind
import io.github.elgrande73.voicememo.audio.PhraseCaptureState
import io.github.elgrande73.voicememo.audio.kind
import io.github.elgrande73.voicememo.monitoring.AudioMonitoringService
import io.github.elgrande73.voicememo.monitoring.MonitoringEngineState
import io.github.elgrande73.voicememo.ui.GuideTab
import io.github.elgrande73.voicememo.ui.theme.MyApplicationTheme
import io.github.elgrande73.voicememo.ui.theme.ThemeMode
import io.github.elgrande73.voicememo.viewmodel.VoiceMemoViewModel
import io.github.elgrande73.voicememo.memo.VoiceNoteFilter
import io.github.elgrande73.voicememo.settings.MatchConfidenceThreshold
import io.github.elgrande73.voicememo.settings.WakePhraseLevel
import io.github.elgrande73.voicememo.transcription.LanguageMode
import io.github.elgrande73.voicememo.transcription.TranscriptionJobState
import io.github.elgrande73.voicememo.transcription.WhisperLanguages
import io.github.elgrande73.voicememo.transcription.TranscriptionQuality
import io.github.elgrande73.voicememo.transcription.TranscriptionSettings
import io.github.elgrande73.voicememo.transcription.TranscriptionStatus
import io.github.elgrande73.voicememo.shortcuts.KeyboardShortcut
import io.github.elgrande73.voicememo.shortcuts.RecordingEntryPoints
import io.github.elgrande73.voicememo.shortcuts.ShortcutKeyHost
import io.github.elgrande73.voicememo.shortcuts.ShortcutRuntime
import io.github.elgrande73.voicememo.wakephrase.BuiltInWakePhrase
import io.github.elgrande73.voicememo.wakephrase.WakePhraseStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: VoiceMemoViewModel by viewModels()

    /** Keyboard shortcut while this activity has focus. */
    private val shortcutHost by lazy { ShortcutKeyHost(this) { viewModel.toggleManualRecording() } }

    override fun onResume() {
        super.onResume()
        shortcutHost.start()
    }

    override fun onPause() {
        shortcutHost.stop()
        super.onPause()
    }

    // Overrides the public platform Activity.dispatchKeyEvent; lint attributes it to androidx.core's restricted
    // ComponentActivity override (known false positive)
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (viewModel.keyboardShortcutCapture.value) {
            // Settings is recording a new shortcut: the key press is the answer, not an action
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == KeyEvent.ACTION_UP) viewModel.cancelKeyboardShortcutCapture()
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                viewModel.onShortcutKeyCaptured(event.keyCode, KeyboardShortcut.modifiersOf(event.metaState))
            }
            return true
        }
        if (shortcutHost.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRecordIntent(intent)
    }

    /**
     * Tile, widget or launcher shortcut asked to record while monitoring was off ([RecordingEntryPoints]): start the
     * memo like the dashboard's Quick Memo button, after the microphone permission if it is missing. Handled once:
     * the intent is cleared so a recreated activity does not start another memo.
     */
    private fun handleRecordIntent(intent: Intent?) {
        if (intent?.action != RecordingEntryPoints.ACTION_RECORD_MEMO) return
        setIntent(Intent(intent).setAction(Intent.ACTION_MAIN))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.startManualRecording()
        } else {
            viewModel.requestRecordingAfterPermission()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleRecordIntent(intent)
        enableEdgeToEdge()
        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val dynamicColor by viewModel.dynamicColor.collectAsState()

            MyApplicationTheme(
                themeMode = themeMode,
                dynamicColor = dynamicColor
            ) {
                var selectedTab by remember { mutableStateOf(0) }
                val context = LocalContext.current

                // Permission Requests
                var hasMicPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    )
                }

                var hasNotificationPermission by remember {
                    mutableStateOf(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                        } else true
                    )
                }

                var hasBluetoothPermission by remember {
                    mutableStateOf(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED
                        } else true
                    )
                }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    hasMicPermission = permissions[Manifest.permission.RECORD_AUDIO] == true
                    if (permissions.containsKey(Manifest.permission.RECORD_AUDIO)) viewModel.onMicrophonePermissionResult(hasMicPermission)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        hasNotificationPermission = permissions[Manifest.permission.POST_NOTIFICATIONS] == true
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        hasBluetoothPermission = permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
                    }
                    viewModel.refreshAvailableInputDevices()
                }

                // A recording request (tile, widget, launcher shortcut) without the microphone permission asks for it
                val recordPermissionPrompt by viewModel.recordPermissionPrompt.collectAsState()
                LaunchedEffect(recordPermissionPrompt) {
                    if (recordPermissionPrompt > 0) permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                }

                LaunchedEffect(Unit) {
                    // Already being asked for the microphone by a recording request: one dialog at a time
                    if (recordPermissionPrompt > 0) return@LaunchedEffect
                    val list = mutableListOf<String>()
                    if (!hasMicPermission) list.add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                        list.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission) {
                        list.add(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                    if (list.isNotEmpty()) {
                        permissionLauncher.launch(list.toTypedArray())
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 8.dp,
                            modifier = Modifier.testTag("app_bottom_bar")
                        ) {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                label = { Text("Dashboard", fontWeight = FontWeight.SemiBold) },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Home,
                                        contentDescription = "Dashboard"
                                    )
                                },
                                modifier = Modifier.testTag("nav_dashboard")
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                label = { Text("History", fontWeight = FontWeight.SemiBold) },
                                icon = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.List,
                                        contentDescription = "Notes History"
                                    )
                                },
                                modifier = Modifier.testTag("nav_history")
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                label = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "Settings"
                                    )
                                },
                                modifier = Modifier.testTag("nav_settings")
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                label = { Text("Guide", fontWeight = FontWeight.SemiBold) },
                                icon = {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Guide"
                                    )
                                },
                                modifier = Modifier.testTag("nav_guide")
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (selectedTab) {
                            0 -> DashboardScreen(
                                viewModel = viewModel,
                                hasMicPermission = hasMicPermission,
                                onRequestPermission = {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                                }
                            )
                            1 -> HistoryScreen(viewModel = viewModel)
                            2 -> SettingsScreen(viewModel = viewModel)
                            3 -> GuideTab()
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// DASHBOARD SCREEN
// -------------------------------------------------------------

@Composable
fun DashboardScreen(
    viewModel: VoiceMemoViewModel,
    hasMicPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    val isServiceRunning by AudioMonitoringService.isServiceRunning.collectAsState()
    val engineState by AudioMonitoringService.engineState.collectAsState()
    val phraseCaptureState by viewModel.phraseCaptureState.collectAsState()
    val isCurrentlyRecording by AudioMonitoringService.isCurrentlyRecording.collectAsState()
    val recordingDurationSeconds by AudioMonitoringService.recordingDurationSeconds.collectAsState()
    val liveLevelDbfs by AudioMonitoringService.liveLevelDbfs.collectAsState()
    val wakePhraseMinLevelEnabled by viewModel.wakePhraseMinLevelEnabled.collectAsState()
    val wakePhraseMinLevelDbfs by viewModel.wakePhraseMinLevelDbfs.collectAsState()
    val liveNoiseFloorDb by AudioMonitoringService.liveNoiseFloorDb.collectAsState()
    val isVoiceDetected by AudioMonitoringService.isVoiceDetected.collectAsState()
    val activeInputDevice by AudioMonitoringService.activeInputDeviceName.collectAsState()
    val isFallbackActive by AudioMonitoringService.isFallbackActive.collectAsState()
    val sessionCount by AudioMonitoringService.sessionEventCount.collectAsState()
    val lastRecordedNote by AudioMonitoringService.lastRecordedNote.collectAsState()
    val detectionMode by viewModel.detectionMode.collectAsState()
    val thresholdDb by viewModel.thresholdDb.collectAsState()
    val keywordConfidence by AudioMonitoringService.liveKeywordConfidence.collectAsState()
    // Same persisted value the wake-phrase detector uses (Settings > Match confidence threshold)
    val matchConfidenceThresholdPercent by viewModel.matchConfidenceThresholdPercent.collectAsState()
    val usesRecordedWakePhrase by viewModel.hasKeywordTemplate.collectAsState()
    val wakePhraseChangedNotice by viewModel.wakePhraseChangedNotice.collectAsState()

    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // Room below the content for the record button, so nothing ends up hidden behind it
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = RECORD_BAR_HEIGHT + 16.dp)
                .testTag("dashboard_screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.dashboard_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("dashboard_title")
            )

            // Permission Warning Card
            if (!hasMicPermission) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Microphone Permission Required",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Voice Memo needs microphone access to record memos and listen for the wake phrase.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onRequestPermission,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Grant Microphone Access")
                        }
                    }
                }
            }

            // Monitoring Status Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        isCurrentlyRecording -> MaterialTheme.colorScheme.errorContainer
                        engineState == MonitoringEngineState.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isCurrentlyRecording -> Color.Red
                                            engineState == MonitoringEngineState.ACTIVE -> Color(0xFF4CAF50)
                                            engineState == MonitoringEngineState.UNAVAILABLE -> Color(0xFFFF9800)
                                            phraseCaptureState != PhraseCaptureState.Idle -> Color(0xFFFF9800)
                                            else -> Color.Gray
                                        }
                                    )
                            )
                            Column {
                                Text(
                                    text = monitoringStatusText(isCurrentlyRecording, engineState, phraseCaptureState),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("monitoring_status")
                                )
                                Text(
                                    text = "Mode: ${detectionMode.displayName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (isCurrentlyRecording) {
                            Surface(
                                color = MaterialTheme.colorScheme.error,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "${recordingDurationSeconds}s",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Hardware Input Source info
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Microphone:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (isFallbackActive) "$activeInputDevice (Default)" else activeInputDevice,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Live Audio Level & Threshold Visualizer (digital level in dBFS from the monitoring stream)
            val levelMarkerDbfs: Float? = when (detectionMode) {
                DetectionMode.KEYWORD_ONLY -> if (wakePhraseMinLevelEnabled) wakePhraseMinLevelDbfs else null
                DetectionMode.AUTO_SOUND -> WakePhraseLevel.fromDetectorDb(thresholdDb)
            }
            LiveLevelCard(
                levelDbfs = liveLevelDbfs,
                noiseFloorDbfs = if (liveLevelDbfs != null) WakePhraseLevel.fromDetectorDb(liveNoiseFloorDb) else null,
                markerDbfs = levelMarkerDbfs,
                markerLabel = when (detectionMode) {
                    DetectionMode.KEYWORD_ONLY -> "Wake-phrase minimum"
                    DetectionMode.AUTO_SOUND -> "Activation threshold"
                },
                engineState = engineState
            )

            if (wakePhraseChangedNotice) {
                WakePhraseChangedNotice(hasRecordedSamples = usesRecordedWakePhrase, onDismiss = { viewModel.dismissWakePhraseChangedNotice() })
            }

            // Live Wake-Phrase Match Confidence Gauge
            if (detectionMode == DetectionMode.KEYWORD_ONLY) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.padding(20.dp)
                    ) {
                        WakePhraseConfidenceGauge(
                            confidence = keywordConfidence,
                            threshold = MatchConfidenceThreshold.toDetectorConfidence(matchConfidenceThresholdPercent),
                            modifier = Modifier
                                .size(120.dp)
                                .testTag("keyword_confidence_gauge")
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Wake Phrase Match",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (usesRecordedWakePhrase) "Live similarity to your recorded wake phrase"
                                else "Say \"${BuiltInWakePhrase.DISPLAY_NAME}\" to start and stop a memo",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("dashboard_wake_phrase_hint")
                            )
                            Text(
                                text = "Trigger threshold: $matchConfidenceThresholdPercent%",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.Red,
                                modifier = Modifier.testTag("dashboard_trigger_threshold")
                            )
                            if (!isServiceRunning) {
                                Text(
                                    text = "Start monitoring to see live values",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Monitoring control (the record button is at the bottom of the screen)
            val oneMemoSession by AudioMonitoringService.oneMemoSession.collectAsState()
            if (isServiceRunning && oneMemoSession) {
                Text(
                    text = "Monitoring is on only for this quick memo and stops when it is saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("one_memo_session_hint")
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // While monitoring runs only for a quick memo, the button keeps it on instead of stopping it
                val monitoringOn = isServiceRunning && !oneMemoSession
                Button(
                    onClick = { viewModel.setMonitoringEnabledByUser(!monitoringOn) },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (monitoringOn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .testTag("toggle_monitoring_button")
                ) {
                    Text(
                        text = when {
                            monitoringOn -> "Stop Monitoring"
                            isServiceRunning -> "Keep Monitoring On"
                            else -> "Start Monitoring"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Today's Session Summary Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Session Stats",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceAround,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "$sessionCount",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Memos Captured",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (engineState == MonitoringEngineState.ACTIVE) "Active" else "Idle",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (engineState == MonitoringEngineState.ACTIVE) Color(0xFF4CAF50) else Color.Gray
                            )
                            Text(
                                text = "Service State",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // Last Captured Memo Quick Preview Card
            lastRecordedNote?.let { note ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Last Captured Memo",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = note.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Duration: ${String.format(Locale.US, "%.1f s", note.durationSeconds)} • Peak: ${Math.round(WakePhraseLevel.fromDetectorDb(note.peakDb))} dBFS",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.playVoiceNote(note) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Play Memo")
                        }
                    }
                }
            }
        }

        // Large record button at the bottom centre: reachable with the thumb, away from the other controls
        MemoRecordBar(
            isRecording = isCurrentlyRecording,
            onClick = {
                if (isCurrentlyRecording) {
                    viewModel.stopManualRecording()
                    Toast.makeText(context, "Voice memo saved", Toast.LENGTH_SHORT).show()
                } else {
                    viewModel.startManualRecording()
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

/** Settings > Shortcuts & lock screen: keyboard shortcut and the recording controls in the notification and on the lock screen. */
@Composable
fun ShortcutSettingsCard(viewModel: VoiceMemoViewModel) {
    val config by viewModel.shortcutConfig.collectAsState()
    val capturing by viewModel.keyboardShortcutCapture.collectAsState()
    val keyboardMessage by viewModel.keyboardShortcutMessage.collectAsState()
    val lockScreenControls by viewModel.lockScreenControlsEnabled.collectAsState()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        onPauseOrDispose { viewModel.cancelKeyboardShortcutCapture() }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("shortcut_settings")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Shortcuts & Lock Screen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = "Start a memo, or stop the one being recorded, without the dashboard button.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // External keyboard
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Keyboard shortcut", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = config.keyboardShortcut?.let { "Bluetooth or USB keyboard: ${it.label}" } ?: "Not set",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("keyboard_shortcut_label")
                    )
                }
                Switch(
                    checked = config.keyboardEnabled,
                    onCheckedChange = { viewModel.setKeyboardShortcutEnabled(it) },
                    modifier = Modifier.testTag("keyboard_shortcut_switch")
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (capturing) {
                    OutlinedButton(onClick = { viewModel.cancelKeyboardShortcutCapture() }) { Text("Cancel") }
                } else {
                    OutlinedButton(
                        onClick = { viewModel.startKeyboardShortcutCapture() },
                        modifier = Modifier.testTag("keyboard_shortcut_set")
                    ) { Text(if (config.keyboardShortcut == null) "Set Shortcut" else "Change Shortcut") }
                    if (config.keyboardShortcut != null) {
                        TextButton(onClick = { viewModel.clearKeyboardShortcut() }) { Text("Remove") }
                    }
                }
            }
            if (capturing) {
                Text(
                    text = "Press the key combination on your keyboard now (for example Ctrl+Shift+R, or F9). Back or Esc cancels.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("keyboard_shortcut_capture_hint")
                )
            }
            keyboardMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("keyboard_shortcut_message")
                )
            }

            // Lock screen
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Recording controls in the notification and on the lock screen", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "On: while monitoring runs, the notification has a Start/Stop memo button, and it and the " +
                            "\"Voice memo\" Quick Settings tile work without unlocking, so anyone holding your phone can " +
                            "start or stop a memo. Off: no button in the notification (on the lock screen only the app " +
                            "name), and the tile asks you to unlock first. Memos can only be played after unlocking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = lockScreenControls,
                    onCheckedChange = { viewModel.setLockScreenControlsEnabled(it) },
                    modifier = Modifier.testTag("lock_screen_controls_switch")
                )
            }
        }
    }
}

/** Lowest level shown on the live meter; quieter input (including digital silence) shows an empty bar. */
private const val METER_FLOOR_DBFS = -80f

/**
 * Live microphone level. Values are dBFS (digital level relative to full scale), not sound pressure: the same voice
 * reads differently on different phones and microphones.
 */
@Composable
fun LiveLevelCard(
    levelDbfs: Float?,
    noiseFloorDbfs: Float?,
    markerDbfs: Float?,
    markerLabel: String,
    engineState: MonitoringEngineState
) {
    fun fraction(dbfs: Float) = ((dbfs - METER_FLOOR_DBFS) / -METER_FLOOR_DBFS).coerceIn(0f, 1f)
    val aboveMarker = levelDbfs != null && markerDbfs != null && levelDbfs >= markerDbfs
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "Live Audio Level", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = levelDbfs?.let { "${Math.round(it)} dBFS" } ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (aboveMarker) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag("live_level_value")
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .testTag("live_level_bar")
            ) {
                if (levelDbfs != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = fraction(levelDbfs))
                            .height(24.dp)
                            .background(if (aboveMarker) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary)
                    )
                }
                if (markerDbfs != null) {
                    Box(modifier = Modifier.fillMaxWidth(fraction = fraction(markerDbfs)).height(24.dp)) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(3.dp)
                                .height(24.dp)
                                .background(Color.Red)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when {
                        levelDbfs == null && engineState == MonitoringEngineState.UNAVAILABLE -> "Microphone unavailable"
                        levelDbfs == null -> "No live input while monitoring is off"
                        noiseFloorDbfs != null -> "Noise floor: ${Math.round(noiseFloorDbfs)} dBFS"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("live_level_status")
                )
                Text(
                    text = markerDbfs?.let { "$markerLabel: ${Math.round(it)} dBFS" } ?: "$markerLabel: off",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Red,
                    modifier = Modifier.testTag("live_level_marker")
                )
            }
        }
    }
}

/** Duration as m:ss. */
private fun clockText(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) + 500) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * Status line of a memo's transcription: the running stage (as reported by the queue, never estimated) with elapsed
 * time and the memo's length, the outcome of the last attempt, or null when there is nothing to report.
 */
fun transcriptionStatusText(note: VoiceNote, job: TranscriptionJobState?, nowMs: Long): String? {
    val audio = clockText((note.durationSeconds * 1000).toLong())
    return when {
        job is TranscriptionJobState.Running -> buildString {
            append(job.stage.label)
            job.progress?.let { append(" $it%") }
            if (job.startedAtMs > 0) append(" · ${clockText(nowMs - job.startedAtMs)} elapsed")
            append(" · $audio of audio")
        }
        job == TranscriptionJobState.Queued || (job == null && note.transcriptionStatus == TranscriptionStatus.PENDING) -> "Waiting to be transcribed…"
        job is TranscriptionJobState.Failed -> "Transcription failed: ${job.message}"
        job is TranscriptionJobState.Completed -> "Transcribed ${clockText(job.audioMs)} of audio in ${clockText(job.elapsedMs)}."
        job == TranscriptionJobState.Cancelled ->
            if (note.transcriptionText != null) "Transcription cancelled; the previous text was kept." else "Transcription cancelled."
        note.transcriptionStatus == TranscriptionStatus.FAILED -> "The last transcription failed. Tap Retry."
        else -> null
    }
}

/** Transcript of a memo with its on-device transcription status and actions. */
@Composable
fun TranscriptSection(
    note: VoiceNote,
    job: TranscriptionJobState?,
    onTranscribe: () -> Unit,
    onCancel: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    /** Shown when the transcription failed because no speech model is installed. */
    onDownloadModel: () -> Unit = {}
) {
    val transcript = note.transcriptionText
    Column(modifier = Modifier.fillMaxWidth().testTag("transcript_section_${note.id}")) {
        if (transcript != null) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = transcript.ifBlank { "(no speech recognised)" },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp).testTag("transcript_text_${note.id}")
                )
            }
        }
        // In-memory job state first; after a restart the stored status tells whether the memo is still queued
        val queued = job == TranscriptionJobState.Queued || (job == null && note.transcriptionStatus == TranscriptionStatus.PENDING)
        val running = job is TranscriptionJobState.Running
        // Elapsed time ticks once a second while the job runs
        val now by produceState(System.currentTimeMillis(), job) {
            while (job is TranscriptionJobState.Running) {
                value = System.currentTimeMillis()
                kotlinx.coroutines.delay(1000)
            }
        }
        val status = transcriptionStatusText(note, job, now)
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (job is TranscriptionJobState.Failed || note.transcriptionStatus == TranscriptionStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 4.dp).testTag("transcript_status_${note.id}")
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (running || queued) {
                TextButton(onClick = onCancel, modifier = Modifier.testTag("transcript_cancel_${note.id}")) { Text("Cancel") }
            } else {
                val failed = job is TranscriptionJobState.Failed || note.transcriptionStatus == TranscriptionStatus.FAILED
                TextButton(onClick = onTranscribe, modifier = Modifier.testTag("transcript_start_${note.id}")) {
                    Text(
                        when {
                            failed -> "Retry Transcription"
                            transcript != null -> "Transcribe Again"
                            else -> "Transcribe"
                        }
                    )
                }
                if ((job as? TranscriptionJobState.Failed)?.modelMissing == true) {
                    TextButton(onClick = onDownloadModel, modifier = Modifier.testTag("transcript_download_model_${note.id}")) {
                        Text("Download speech model")
                    }
                }
                if (transcript != null) {
                    TextButton(onClick = onEdit, modifier = Modifier.testTag("transcript_edit_${note.id}")) { Text("Edit") }
                    TextButton(onClick = onDelete, modifier = Modifier.testTag("transcript_delete_${note.id}")) { Text("Delete Text") }
                }
            }
        }
    }
}

/**
 * Settings → Transcription language: a fixed language (default, faster) or automatic detection, the language selector
 * (every language the selected model supports) and the read-only effective configuration.
 */
@Composable
fun TranscriptionLanguageSettings(viewModel: VoiceMemoViewModel) {
    val mode by viewModel.transcriptionLanguageMode.collectAsState()
    val fixed by viewModel.transcriptionFixedLanguage.collectAsState()
    val languages by viewModel.transcriptionLanguages.collectAsState()
    val effective by viewModel.effectiveTranscriptionConfig.collectAsState()
    var languageMenu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.selectableGroup()) {
        Text(text = "Transcription language", fontWeight = FontWeight.SemiBold)
        listOf(
            LanguageMode.FIXED to "Fixed language (faster, recommended)",
            LanguageMode.AUTO to "Detect language automatically"
        ).forEach { (option, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = mode == option, role = Role.RadioButton) { viewModel.setTranscriptionLanguageMode(option) }
                    .testTag("transcription_language_mode_${option.name}")
            ) {
                RadioButton(selected = mode == option, onClick = null)
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (mode == LanguageMode.FIXED) {
            Text(
                text = "Always use the selected language instead of detecting it for each recording.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("transcription_fixed_language_help")
            )
            Box {
                OutlinedButton(
                    onClick = { languageMenu = true },
                    modifier = Modifier
                        .testTag("transcription_language")
                        .semantics { contentDescription = "Transcription language: ${WhisperLanguages.find(fixed)?.label ?: fixed}. Change" }
                ) {
                    Text(WhisperLanguages.find(fixed)?.label ?: fixed)
                }
                DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                    languages.forEach { language ->
                        DropdownMenuItem(
                            text = { Text(language.label) },
                            onClick = { viewModel.setTranscriptionFixedLanguage(language.code); languageMenu = false },
                            modifier = Modifier.testTag("transcription_language_${language.code}")
                        )
                    }
                }
            }
        } else {
            Text(
                text = "Detects the language of each recording. Slower, and short memos may be detected as the wrong language.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().testTag("effective_transcription_config")
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("Effective configuration", style = MaterialTheme.typography.labelLarge)
                Text("Model: ${effective.model}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("effective_model"))
                Text("Language: ${effective.language}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("effective_language"))
                effective.problem?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("effective_problem"))
                }
            }
        }
    }
}

/** Settings > Speech-to-Text: automatic transcription, language, quality and the speech model. */
@Composable
fun TranscriptionSettingsCard(viewModel: VoiceMemoViewModel, autoTranscribe: Boolean) {
    val quality by viewModel.transcriptionQuality.collectAsState()
    val models by viewModel.speechModels.collectAsState()
    val activeModel by viewModel.activeSpeechModelId.collectAsState()
    val modelMessage by viewModel.modelMessage.collectAsState()
    val importing by viewModel.modelImportRunning.collectAsState()
    val downloadState by viewModel.modelDownloadState.collectAsState()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importSpeechModel(it) }
    }
    LaunchedEffect(Unit) { viewModel.refreshSpeechModels() }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().testTag("transcription_settings")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Speech-to-Text", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = "Memos are transcribed on this phone; no audio or text is sent anywhere. The speech model is not " +
                    "part of the app: download it once below (the only time the app uses the internet). Then tap " +
                    "Transcribe on a memo in History.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Transcribe new memos automatically", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "Off: only memos you choose are transcribed. Transcribing uses battery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoTranscribe,
                    onCheckedChange = { viewModel.setSpeechToTextEnabled(it) },
                    modifier = Modifier.testTag("auto_transcribe_switch")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            TranscriptionLanguageSettings(viewModel)

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Quality", fontWeight = FontWeight.SemiBold)
            TranscriptionQuality.entries.forEach { q ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.setTranscriptionQuality(q) }
                ) {
                    RadioButton(selected = quality == q, onClick = { viewModel.setTranscriptionQuality(q) })
                    Text(q.title, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Speech model", fontWeight = FontWeight.SemiBold)
            SpeechModelDownloadSection(
                state = downloadState,
                onDownload = { viewModel.downloadSpeechModel() },
                onCancel = { viewModel.cancelSpeechModelDownload() },
                onRemove = { viewModel.removeDownloadedSpeechModel() }
            )
            if (models.size > 1) Text(text = "Model used for transcription", style = MaterialTheme.typography.labelLarge)
            models.forEach { model ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { viewModel.selectSpeechModel(model.id) }.testTag("speech_model_${model.id}")
                ) {
                    RadioButton(selected = activeModel == model.id, onClick = { viewModel.selectSpeechModel(model.id) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(model.name, style = MaterialTheme.typography.bodyMedium)
                        Text(model.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!model.downloaded) {
                        TextButton(onClick = { viewModel.deleteSpeechModel(model.id) }) { Text("Remove") }
                    }
                }
            }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                enabled = !importing,
                modifier = Modifier.testTag("import_speech_model")
            ) { Text(if (importing) "Checking model…" else "Import Model File…") }
            Text(
                text = "Optional: import another whisper.cpp model file (ggml .bin). Larger models such as \"small\" are " +
                    "more accurate but slower and need more memory; \"tiny\" is faster but less accurate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            modelMessage?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("speech_model_message"))
            }
        }
    }
}

/** Version name of the installed package (what the user actually runs), or null if it cannot be read. */
fun installedVersionName(context: Context): String? = try {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    info.versionName
} catch (e: PackageManager.NameNotFoundException) {
    null
}

/** Settings > About: installed version, license, source code and the third-party license texts. */
@Composable
fun AboutCard() {
    val context = LocalContext.current
    val version = remember { installedVersionName(context) }
    var showLicenses by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().testTag("about_card")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "About", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Version: ${version ?: "unknown"}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("app_version")
            )
            Text(
                text = "Free and open-source software (Apache License 2.0).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { showLicenses = true }, modifier = Modifier.testTag("open_source_licenses")) { Text("Open-source licenses") }
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SOURCE_CODE_URL)))
                    } catch (e: android.content.ActivityNotFoundException) {
                        Toast.makeText(context, SOURCE_CODE_URL, Toast.LENGTH_LONG).show()
                    }
                }) { Text("Source code") }
            }
        }
    }

    if (showLicenses) {
        val notices = remember {
            try {
                context.assets.open("licenses/THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                "License information is not available."
            }
        }
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Open-source licenses") },
            text = {
                Text(
                    text = notices,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close") } }
        )
    }
}

/** Source code and issue tracker (opened in the browser; the app itself does not use the network for this). */
const val SOURCE_CODE_URL = "https://github.com/elgrande73/FOSS-voice-memo-android-app"

/**
 * Download of the Whisper base model: what it is, its size and storage, progress with Cancel, validation, ready with a
 * Remove action (confirmed), and failures with Retry.
 */
@Composable
fun SpeechModelDownloadSection(state: ModelDownloadState, onDownload: () -> Unit, onCancel: () -> Unit, onRemove: () -> Unit) {
    var confirmRemove by remember { mutableStateOf(false) }
    val sizeMb = ModelSource.WHISPER_BASE.approxBytes / 1_000_000
    Column(modifier = Modifier.fillMaxWidth().testTag("speech_model_download"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val status = when (state) {
            ModelDownloadState.Missing -> "Not installed. Transcription needs the Whisper base model: a one-time download of about " +
                "$sizeMb MB (Wi-Fi recommended), stored on this device and kept until you remove it. Recording and wake-phrase " +
                "detection do not need it."
            is ModelDownloadState.Downloading -> "Downloading… ${state.downloadedBytes / 1_000_000} of ${state.totalBytes / 1_000_000} MB (${state.percent}%)"
            ModelDownloadState.Validating -> "Checking the download (checksum)…"
            ModelDownloadState.Initializing -> "Preparing the model…"
            ModelDownloadState.Ready -> "Whisper base is installed (about $sizeMb MB on this device). Transcription works offline."
            is ModelDownloadState.Failed -> state.message
        }
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = if (state is ModelDownloadState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("speech_model_download_status")
        )
        when (state) {
            is ModelDownloadState.Downloading -> {
                LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("cancel_model_download")) { Text("Cancel download") }
            }
            ModelDownloadState.Validating, ModelDownloadState.Initializing -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            ModelDownloadState.Ready -> TextButton(onClick = { confirmRemove = true }, modifier = Modifier.testTag("remove_speech_model")) {
                Text("Remove Whisper base model")
            }
            ModelDownloadState.Missing -> Button(onClick = onDownload, modifier = Modifier.testTag("download_speech_model")) {
                Text("Download Whisper Base model ($sizeMb MB)")
            }
            is ModelDownloadState.Failed -> if (state.retryable) {
                Button(onClick = onDownload, modifier = Modifier.testTag("download_speech_model")) { Text("Retry download") }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove the speech model?") },
            text = {
                Text(
                    "This frees about $sizeMb MB. Transcription stops working until you download the model again; " +
                        "running transcriptions are cancelled. Your transcripts and recordings are kept."
                )
            },
            confirmButton = {
                Button(onClick = { confirmRemove = false; onRemove() }, modifier = Modifier.testTag("confirm_remove_speech_model")) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }
}

/** Height reserved at the bottom of the dashboard for [MemoRecordBar]. */
val RECORD_BAR_HEIGHT = 140.dp

/**
 * Quick Memo / Stop Memo: a 96 dp round button, bottom centre, on an opaque bar so taps near it cannot reach the
 * scrolled content behind. Start and stop differ in colour, symbol (dot / square) and label; TalkBack announces the
 * action and the recording state.
 */
@Composable
fun MemoRecordBar(isRecording: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = if (isRecording) "Stop Memo" else "Quick Memo"
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        modifier = modifier
            .fillMaxWidth()
            .height(RECORD_BAR_HEIGHT)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Button(
                onClick = onClick,
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                modifier = Modifier
                    .size(96.dp)
                    .semantics {
                        contentDescription = if (isRecording) "Stop memo and save" else "Start quick memo"
                        stateDescription = if (isRecording) "Recording" else "Not recording"
                    }
                    .testTag("manual_record_button")
            ) {
                val symbolColor = if (isRecording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                Canvas(modifier = Modifier.size(34.dp)) {
                    if (isRecording) {
                        drawRoundRect(color = symbolColor, cornerRadius = CornerRadius(size.minDimension * 0.15f))
                    } else {
                        drawCircle(color = symbolColor)
                    }
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("manual_record_label")
            )
        }
    }
}

/** Monitoring status derived from the real engine state, never from the service's mere existence. */
fun monitoringStatusText(
    isRecordingMemo: Boolean,
    engineState: MonitoringEngineState,
    phraseCaptureState: PhraseCaptureState
): String = when {
    isRecordingMemo -> "Recording memo…"
    phraseCaptureState is PhraseCaptureState.StoppingMonitoring -> "Stopping monitoring…"
    phraseCaptureState != PhraseCaptureState.Idle -> "Paused: recording a phrase in Settings"
    else -> when (engineState) {
        MonitoringEngineState.ACTIVE -> "Monitoring Active"
        MonitoringEngineState.STARTING -> "Starting monitoring…"
        MonitoringEngineState.WAITING_FOR_MICROPHONE -> "Waiting for the microphone…"
        MonitoringEngineState.UNAVAILABLE -> "Microphone unavailable"
        MonitoringEngineState.STOPPING -> "Stopping monitoring…"
        MonitoringEngineState.STOPPED -> "Monitoring Stopped"
    }
}

/**
 * Circular gauge showing the live wake-phrase match confidence (0..1) with a tick at the trigger threshold.
 * The arc spans 270 degrees, opening at the bottom.
 */
@Composable
fun WakePhraseConfidenceGauge(
    confidence: Float,
    threshold: Float,
    modifier: Modifier = Modifier
) {
    val animatedConfidence by animateFloatAsState(
        targetValue = confidence.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 120),
        label = "keywordConfidence"
    )
    val isAboveThreshold = confidence >= threshold
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = if (isAboveThreshold) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
    val thresholdColor = Color.Red

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 12.dp.toPx()
            val inset = strokeWidth / 2f + 4.dp.toPx()
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)
            val startAngle = 135f
            val totalSweep = 270f

            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = totalSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            if (animatedConfidence > 0.005f) {
                drawArc(
                    color = progressColor,
                    startAngle = startAngle,
                    sweepAngle = totalSweep * animatedConfidence,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }

            // Threshold tick crossing the arc
            val thresholdAngle = Math.toRadians((startAngle + totalSweep * threshold.coerceIn(0f, 1f)).toDouble())
            val radius = arcSize.width / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val tickHalf = strokeWidth / 2f + 4.dp.toPx()
            val dirX = kotlin.math.cos(thresholdAngle).toFloat()
            val dirY = kotlin.math.sin(thresholdAngle).toFloat()
            drawLine(
                color = thresholdColor,
                start = Offset(center.x + dirX * (radius - tickHalf), center.y + dirY * (radius - tickHalf)),
                end = Offset(center.x + dirX * (radius + tickHalf), center.y + dirY * (radius + tickHalf)),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${(animatedConfidence * 100).toInt()}%",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (isAboveThreshold) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "match",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// -------------------------------------------------------------
// HISTORY SCREEN & INLINE AUDIO PLAYER
// -------------------------------------------------------------

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun HistoryScreen(viewModel: VoiceMemoViewModel) {
    val transcriptionJobs by viewModel.transcriptionJobs.collectAsState()
    var noteToEditTranscript by remember { mutableStateOf<VoiceNote?>(null) }
    var transcriptDraft by remember { mutableStateOf("") }
    val notes by viewModel.filteredVoiceNotes.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val activeFilters by viewModel.activeFilters.collectAsState()
    val playbackMode by viewModel.playbackMode.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val deleteInProgress by viewModel.deleteInProgress.collectAsState()
    val historyMessage by viewModel.historyMessage.collectAsState()
    val playingNoteId by viewModel.playingNoteId.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playbackPos by viewModel.playbackPositionMs.collectAsState()
    val playbackDur by viewModel.playbackDurationMs.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val exportInProgress by viewModel.exportInProgress.collectAsState()

    val context = LocalContext.current
    var noteToRename by remember { mutableStateOf<VoiceNote?>(null) }
    var renameText by remember { mutableStateOf("") }
    var noteToDelete by remember { mutableStateOf<VoiceNote?>(null) }
    // Memos to delete, captured when the user asked: confirming deletes exactly these, whatever changes meanwhile
    var pendingDeleteIds by remember { mutableStateOf<Set<Int>?>(null) }
    var exportIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val zipExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.exportMemosTo(exportIds, uri)
    }
    val wavExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        if (uri != null) viewModel.exportMemosTo(exportIds, uri)
    }
    fun exportTo(ids: Set<Int>) {
        if (ids.isEmpty()) return
        exportIds = ids
        val (name, mime) = viewModel.exportTarget(ids)
        try {
            if (mime == "audio/wav") wavExportLauncher.launch(name) else zipExportLauncher.launch(name)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "No file picker available. Use Share instead.", Toast.LENGTH_LONG).show()
        }
    }
    fun share(ids: Set<Int>) {
        viewModel.shareMemos(ids) { intent ->
            if (intent != null) context.startActivity(Intent.createChooser(intent, "Share voice memos"))
            else Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(historyMessage) {
        historyMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeHistoryMessage()
        }
    }
    val listState = rememberLazyListState()
    PlayingMemoAutoScroll(
        listState = listState,
        playingKey = playingNoteId,
        playingIndex = notes.indexOfFirst { it.id == playingNoteId },
        enabled = playbackMode == PlaybackMode.AUTOPLAY,
        marginPx = with(LocalDensity.current) { 8.dp.roundToPx() }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("history_screen")
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { viewModel.setSearchQuery(it) },
            placeholder = { Text("Search voice memos...") },
            leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = "Search") },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            shape = RoundedCornerShape(16.dp),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { ShortcutRuntime.textInputFocused = it.isFocused }
                .testTag("search_notes_input")
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Filter chips (scrollable on narrow screens). Today/Favorites narrow the list; Wake Phrase and Manual can be
        // combined (memos started either way); "All Memos" clears every filter. See MemoFilter.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            VoiceNoteFilter.entries.forEach { filter ->
                FilterChip(
                    selected = if (filter == VoiceNoteFilter.ALL) activeFilters.isEmpty() else filter in activeFilters,
                    onClick = { viewModel.toggleFilter(filter) },
                    label = { Text(filter.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.testTag("filter_${filter.name.lowercase()}")
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (selectionMode) {
            HistorySelectionBar(
                selectedCount = selectedIds.size,
                hiddenSelectedCount = (selectedIds - notes.map { it.id }.toSet()).size,
                shownCount = notes.size,
                busy = exportInProgress || deleteInProgress,
                exportInProgress = exportInProgress,
                onSelectAllShown = { viewModel.selectAllShown() },
                onSelectLibrary = { viewModel.selectWholeLibrary() },
                onClear = { viewModel.clearSelection() },
                onExit = { viewModel.exitSelection() },
                onExport = { exportTo(selectedIds) },
                onShare = { share(selectedIds) },
                onCancelExport = { viewModel.cancelExport() },
                onDelete = { pendingDeleteIds = selectedIds }
            )
        } else {
            // Header: count, selection and batch export
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${notes.size} Voice Memos",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (notes.isNotEmpty()) {
                        TextButton(onClick = { viewModel.startSelection() }, modifier = Modifier.testTag("start_selection")) {
                            Text("Select")
                        }
                        TextButton(
                            onClick = { share(notes.map { it.id }.toSet()) },
                            enabled = !exportInProgress
                        ) {
                            if (exportInProgress) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            } else {
                                Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share shown")
                            }
                        }
                    }
                }
            }

            // Playback mode: what happens when a memo ends (persisted)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
            ) {
                Text("Playback:", style = MaterialTheme.typography.labelLarge)
                PlaybackMode.entries.forEach { mode ->
                    FilterChip(
                        selected = playbackMode == mode,
                        onClick = { viewModel.setPlaybackMode(mode) },
                        label = { Text(mode.label) },
                        modifier = Modifier
                            .semantics {
                                role = Role.RadioButton
                                stateDescription = mode.description
                            }
                            .testTag("playback_mode_${mode.id}")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (notes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (searchQuery.isNotEmpty() || activeFilters.isNotEmpty()) "No matching memos" else "No voice memos yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (searchQuery.isNotEmpty() || activeFilters.isNotEmpty()) "Change the search or filters to see other memos."
                            else "Tap Quick Memo on the dashboard, or start monitoring.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("history_list")
            ) {
                items(notes, key = { it.id }) { note ->
                    val isCurrentPlaying = playingNoteId == note.id
                    val isSelected = note.id in selectedIds

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrentPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { if (selectionMode) viewModel.toggleSelected(note.id) },
                                onClickLabel = if (selectionMode) (if (isSelected) "Deselect" else "Select") else null,
                                onLongClick = { if (selectionMode) viewModel.toggleSelected(note.id) else viewModel.startSelection(note.id) },
                                onLongClickLabel = "Select memos"
                            )
                            .testTag("voice_note_card_${note.id}")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (selectionMode) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { viewModel.toggleSelected(note.id) },
                                        modifier = Modifier.testTag("select_memo_${note.id}")
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = note.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(note.timestamp)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { viewModel.toggleFavorite(note) }) {
                                        Icon(
                                            imageVector = if (note.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                            contentDescription = "Favorite",
                                            tint = if (note.isFavorite) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = {
                                        noteToRename = note
                                        renameText = note.title
                                    }) {
                                        Icon(imageVector = Icons.Default.Edit, contentDescription = "Rename")
                                    }
                                    IconButton(onClick = { noteToDelete = note }) {
                                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete")
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Badges: Duration & Trigger Type
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "%.1fs", note.durationSeconds),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = when (MemoSource.of(note.triggerType)) {
                                            MemoSource.WAKE_PHRASE -> "🎙️ Wake Phrase"
                                            MemoSource.MANUAL -> "👆 Manual"
                                            MemoSource.SOUND -> "🤖 Auto Sound"
                                            MemoSource.UNKNOWN -> "Other"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                Text(
                                    text = "Peak: ${Math.round(WakePhraseLevel.fromDetectorDb(note.peakDb))} dBFS",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // On-device transcript: status, text and actions
                            Spacer(modifier = Modifier.height(8.dp))
                            TranscriptSection(
                                note = note,
                                job = transcriptionJobs[note.id],
                                onTranscribe = { viewModel.transcribeNote(note) },
                                onCancel = { viewModel.cancelTranscription(note) },
                                onEdit = { noteToEditTranscript = note; transcriptDraft = note.transcriptionText ?: "" },
                                onDelete = { viewModel.deleteTranscript(note) },
                                onDownloadModel = { viewModel.downloadSpeechModel() }
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Inline Audio Player Controls
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = { viewModel.playVoiceNote(note, fromDisplayedList = true) },
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(0.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isCurrentPlaying && isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                    ),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("play_memo_${note.id}")
                                ) {
                                    Icon(
                                        imageVector = if (isCurrentPlaying && isPlaying) Icons.Default.Check else Icons.Default.PlayArrow,
                                        contentDescription = if (isCurrentPlaying && isPlaying) "Pause" else "Play",
                                        tint = Color.White
                                    )
                                }

                                if (isCurrentPlaying) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Slider(
                                            value = playbackPos.toFloat(),
                                            onValueChange = { viewModel.seekTo(it.toInt()) },
                                            valueRange = 0f..(playbackDur.toFloat().coerceAtLeast(1f)),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Row(
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = formatMillis(playbackPos),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            Text(
                                                text = formatMillis(playbackDur),
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }

                                    // Speed toggle button
                                    TextButton(
                                        onClick = {
                                            val nextSpeed = when (playbackSpeed) {
                                                1.0f -> 1.25f
                                                1.25f -> 1.5f
                                                1.5f -> 2.0f
                                                else -> 1.0f
                                            }
                                            viewModel.setPlaybackSpeed(nextSpeed)
                                        }
                                    ) {
                                        Text("${playbackSpeed}x", fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                    IconButton(
                                        onClick = {
                                            LaunchedEffectScopeCoroutine(viewModel, note, context)
                                        }
                                    ) {
                                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share")
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    // Transcript editor
    noteToEditTranscript?.let { note ->
        AlertDialog(
            onDismissRequest = { noteToEditTranscript = null },
            title = { Text("Edit Transcript") },
            text = {
                OutlinedTextField(
                    value = transcriptDraft,
                    onValueChange = { transcriptDraft = it },
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { ShortcutRuntime.textInputFocused = it.isFocused }
                        .testTag("transcript_editor")
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.editTranscript(note, transcriptDraft); noteToEditTranscript = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { noteToEditTranscript = null }) { Text("Cancel") } }
        )
    }

    // Rename Dialog
    noteToRename?.let { note ->
        AlertDialog(
            onDismissRequest = { noteToRename = null },
            title = { Text("Rename Voice Memo") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { ShortcutRuntime.textInputFocused = it.isFocused }
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.renameVoiceNote(note.id, renameText)
                    noteToRename = null
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { noteToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Bulk delete confirmation: deletes exactly the snapshot taken when Delete was pressed
    pendingDeleteIds?.let { ids ->
        val hidden = (ids - notes.map { it.id }.toSet()).size
        AlertDialog(
            onDismissRequest = { pendingDeleteIds = null },
            title = { Text(if (ids.size == 1) "Delete 1 memo?" else "Delete ${ids.size} memos?") },
            text = {
                Text(
                    "The recordings (audio files), titles and transcripts of the ${ids.size} selected " +
                        (if (ids.size == 1) "memo are" else "memos are") + " permanently removed from this device." +
                        (if (hidden > 0) " $hidden of them are not shown by the current filter." else "") +
                        " Other memos, wake-phrase samples and settings are not affected. This cannot be undone.",
                    modifier = Modifier.testTag("bulk_delete_message")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteMemos(ids)
                        pendingDeleteIds = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_bulk_delete")
                ) {
                    Text(if (ids.size == 1) "Delete 1 memo" else "Delete ${ids.size} memos")
                }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteIds = null }) { Text("Cancel") } }
        )
    }

    // Delete Confirmation Dialog
    noteToDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text("Delete Voice Memo") },
            text = {
                Text(
                    "Delete \"${note.title}\"? Its recording (audio file), title and transcript are permanently removed from " +
                        "this device. Other memos, wake-phrase samples and settings are not affected. This cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteVoiceNote(note)
                        noteToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { noteToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun HistorySelectionBar(
    selectedCount: Int,
    hiddenSelectedCount: Int,
    shownCount: Int,
    busy: Boolean,
    exportInProgress: Boolean,
    onSelectAllShown: () -> Unit,
    onSelectLibrary: () -> Unit,
    onClear: () -> Unit,
    onExit: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onCancelExport: () -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().testTag("selection_bar")) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onExit) { Icon(Icons.Default.Clear, contentDescription = "Leave selection") }
            Text(
                text = "$selectedCount selected" + if (hiddenSelectedCount > 0) " ($hiddenSelectedCount not shown by the current filter)" else "",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .testTag("selection_count")
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            TextButton(onClick = onSelectAllShown, enabled = shownCount > 0, modifier = Modifier.testTag("select_all_shown")) {
                Text("Select all shown ($shownCount)")
            }
            TextButton(onClick = onSelectLibrary, modifier = Modifier.testTag("select_library")) { Text("Select entire library") }
            TextButton(onClick = onClear, enabled = selectedCount > 0) { Text("Clear") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (exportInProgress) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text("Exporting…", modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onCancelExport, modifier = Modifier.testTag("cancel_export")) { Text("Cancel") }
            } else {
                Button(onClick = onExport, enabled = selectedCount > 0 && !busy, modifier = Modifier.testTag("export_selected")) { Text("Save to…") }
                OutlinedButton(onClick = onShare, enabled = selectedCount > 0 && !busy) { Text("Share") }
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = onDelete,
                    enabled = selectedCount > 0 && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("delete_selected")
                ) { Text("Delete") }
            }
        }
    }
}

/** One-time notice after an update that changed the built-in wake phrase (SettingsMigrator v4). */
@Composable
fun WakePhraseChangedNotice(hasRecordedSamples: Boolean, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("wake_phrase_changed_notice")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "The built-in wake phrase is now \"${BuiltInWakePhrase.DISPLAY_NAME}\". " +
                    if (hasRecordedSamples) "Your recorded phrase is unchanged and is still used."
                    else "Record your own samples in Settings → Wake Phrase for the most reliable detection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).testTag("dismiss_wake_phrase_notice")) { Text("OK") }
        }
    }
}

private fun LaunchedEffectScopeCoroutine(viewModel: VoiceMemoViewModel, note: VoiceNote, context: Context) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
        val shareIntent = viewModel.prepareSingleNoteShare(note)
        if (shareIntent != null) {
            context.startActivity(Intent.createChooser(shareIntent, "Share Voice Memo"))
        } else {
            Toast.makeText(context, "Failed to prepare audio clip", Toast.LENGTH_SHORT).show()
        }
    }
}

// -------------------------------------------------------------
// SETTINGS SCREEN
// -------------------------------------------------------------

@Composable
fun SettingsScreen(viewModel: VoiceMemoViewModel) {
    val detectionMode by viewModel.detectionMode.collectAsState()
    val wakePhraseMinLevelEnabled by viewModel.wakePhraseMinLevelEnabled.collectAsState()
    val wakePhraseMinLevelDbfs by viewModel.wakePhraseMinLevelDbfs.collectAsState()
    val thresholdDb by viewModel.thresholdDb.collectAsState()
    val silenceTimeoutMs by viewModel.silenceTimeoutMs.collectAsState()
    val preTriggerMs by viewModel.preTriggerDurationMs.collectAsState()
    val maxMemoDurationMs by viewModel.maxMemoDurationMs.collectAsState()
    val noiseFilterEnabled by viewModel.noiseFilterEnabled.collectAsState()
    val feedbackSoundsEnabled by viewModel.feedbackSoundsEnabled.collectAsState()

    val matchConfidenceThresholdPercent by viewModel.matchConfidenceThresholdPercent.collectAsState()
    val wakePhraseChangedNotice by viewModel.wakePhraseChangedNotice.collectAsState()
    val keywordBehavior by viewModel.keywordBehavior.collectAsState()
    val hasKeywordTemplate by viewModel.hasKeywordTemplate.collectAsState()
    val wakePhraseSamples by viewModel.wakePhraseSamples.collectAsState()
    var replacingSampleId by remember { mutableStateOf<Long?>(null) }
    val isRecordingKeyword by viewModel.isRecordingKeyword.collectAsState()
    val isTestingKeyword by viewModel.isTestingKeyword.collectAsState()
    val keywordTestResult by viewModel.keywordTestResult.collectAsState()
    val lastTestEvaluation by viewModel.lastTestEvaluation.collectAsState()
    val phraseCaptureState by viewModel.phraseCaptureState.collectAsState()
    val phraseCaptureError by viewModel.phraseCaptureError.collectAsState()
    val phraseMessage by viewModel.phraseMessage.collectAsState()
    val unusableSampleCount by viewModel.unusableSampleCount.collectAsState()
    val hasSavedTestRecording by viewModel.hasSavedTestRecording.collectAsState()

    val speechToTextEnabled by viewModel.speechToTextEnabled.collectAsState()
    val availableDevices by viewModel.availableInputDevices.collectAsState()
    val selectedDeviceId by viewModel.selectedAudioInputId.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()
    val dynamicColor by viewModel.dynamicColor.collectAsState()

    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section: Detection Modes
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Detection Mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                DetectionMode.values().forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setDetectionMode(mode) }
                            .padding(vertical = 6.dp)
                    ) {
                        RadioButton(
                            selected = detectionMode == mode,
                            onClick = { viewModel.setDetectionMode(mode) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = mode.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = mode.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Section: Wake Phrase (built-in phrase or enrolled samples) Keyword Spotter
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Wake Phrase",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        color = if (hasKeywordTemplate) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (hasKeywordTemplate) "${wakePhraseSamples.size} of ${WakePhraseStore.MAX_SAMPLES} samples" else "Default phrase",
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("wake_phrase_sample_count"),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (hasKeywordTemplate) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Say \"Mango Tango\" to start a memo (and again to stop it, if set below). The built-in phrase " +
                        "works without setup but was made from synthetic voices, so it does not recognise every voice. For " +
                        "reliable detection, record the phrase in your own voice, or record any other phrase instead. One sample " +
                        "is enough; up to ${WakePhraseStore.MAX_SAMPLES} help when your pace, volume or distance varies. Speak " +
                        "naturally. Samples stay on this device; deleting all of them restores the built-in phrase.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (wakePhraseChangedNotice) {
                    Spacer(modifier = Modifier.height(10.dp))
                    WakePhraseChangedNotice(hasRecordedSamples = hasKeywordTemplate, onDismiss = { viewModel.dismissWakePhraseChangedNotice() })
                }

                Spacer(modifier = Modifier.height(12.dp))

                val captureKind = phraseCaptureState.kind()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (isRecordingKeyword) {
                                viewModel.stopRecordingKeywordTemplate()
                            } else {
                                replacingSampleId = null
                                viewModel.startRecordingKeywordTemplate()
                            }
                        },
                        // Disabled while monitoring is being stopped, another recording is running, or 5 samples exist
                        enabled = isRecordingKeyword || (phraseCaptureState == PhraseCaptureState.Idle &&
                            wakePhraseSamples.size < WakePhraseStore.MAX_SAMPLES),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecordingKeyword) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("record_wake_phrase_button")
                    ) {
                        Text(
                            when {
                                isRecordingKeyword -> "Stop Recording"
                                captureKind == PhraseCaptureKind.WAKE_PHRASE &&
                                    phraseCaptureState is PhraseCaptureState.StoppingMonitoring -> "Stopping monitoring…"
                                captureKind == PhraseCaptureKind.WAKE_PHRASE -> "Starting…"
                                hasKeywordTemplate -> "Add Sample"
                                else -> "Record Phrase"
                            }
                        )
                    }

                    if (hasKeywordTemplate) {
                        OutlinedButton(
                            onClick = { viewModel.testKeywordAudio() },
                            enabled = !isTestingKeyword && phraseCaptureState == PhraseCaptureState.Idle,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("test_phrase_button")
                        ) {
                            Text(
                                when {
                                    phraseCaptureState == PhraseCaptureState.Recording(PhraseCaptureKind.TEST_PHRASE) -> "Listening..."
                                    phraseCaptureState is PhraseCaptureState.StoppingMonitoring &&
                                        captureKind == PhraseCaptureKind.TEST_PHRASE -> "Stopping monitoring…"
                                    isTestingKeyword -> "Evaluating…"
                                    else -> "Test Phrase"
                                }
                            )
                        }
                    }
                }

                if (captureKind != null) {
                    Text(
                        text = "Monitoring is paused while a phrase is recorded and resumes afterwards.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                phraseCaptureError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("phrase_capture_error")
                    )
                }
                if (phraseCaptureError == null) phraseMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("phrase_message")
                    )
                }
                if (unusableSampleCount > 0) {
                    Text(
                        text = "$unusableSampleCount sample(s) recorded with a much older version of the app can no longer be used. " +
                            "Record the phrase again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (hasKeywordTemplate) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
                    wakePhraseSamples.forEachIndexed { index, sample ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wake_phrase_sample_$index")
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Sample ${index + 1}" +
                                        if (isRecordingKeyword && replacingSampleId == sample.id) " — recording replacement…" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = dateFormat.format(Date(sample.createdAt)) +
                                        (if (sample.phraseDurationMs > 0) " · %.1f s".format(sample.phraseDurationMs / 1000f) else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = {
                                    replacingSampleId = sample.id
                                    viewModel.startRecordingKeywordTemplate(replaceSampleId = sample.id)
                                },
                                enabled = phraseCaptureState == PhraseCaptureState.Idle
                            ) { Text("Replace") }
                            TextButton(
                                onClick = { viewModel.deleteWakePhraseSample(sample.id) },
                                enabled = phraseCaptureState == PhraseCaptureState.Idle,
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) { Text("Delete") }
                        }
                    }
                    TextButton(
                        onClick = { viewModel.clearKeywordTemplate() },
                        enabled = phraseCaptureState == PhraseCaptureState.Idle,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete All Samples")
                    }
                }

                keywordTestResult?.let { result ->
                    val scorePercent = (result.confidence * 100).toInt()
                    val reachesThreshold = result.confidence >= MatchConfidenceThreshold.toDetectorConfidence(matchConfidenceThresholdPercent)
                    // The loudness check is known only for tests evaluated in this session
                    val tooQuiet = wakePhraseMinLevelEnabled &&
                        lastTestEvaluation?.let { it.peakLevelDb < WakePhraseLevel.toDetectorDb(wakePhraseMinLevelDbfs) } == true
                    val testedAt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(result.timestamp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Last test ($testedAt): $scorePercent% match — " +
                            if (reachesThreshold) "reaches the trigger threshold" else "below the trigger threshold",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (reachesThreshold && !tooQuiet) Color(0xFF4CAF50) else Color.Red,
                        modifier = Modifier.testTag("wake_phrase_test_result")
                    )
                    if (tooQuiet) {
                        Text(
                            text = "The phrase was quieter than the minimum audio level (${Math.round(wakePhraseMinLevelDbfs)} dBFS), " +
                                "so monitoring would not react to it. Speak louder, move closer, or lower or turn off the minimum audio level.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (hasSavedTestRecording) {
                    TextButton(
                        onClick = { viewModel.retestSavedRecording() },
                        enabled = !isTestingKeyword
                    ) {
                        Text("Re-test Saved Test Recording")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Match confidence threshold: $matchConfidenceThresholdPercent%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("match_confidence_threshold_label")
                )
                Text(
                    text = "How closely speech must match the wake phrase to start or stop a memo. Higher values " +
                        "mean fewer false starts; lower values detect the phrase more easily.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = matchConfidenceThresholdPercent.toFloat(),
                    onValueChange = { viewModel.setMatchConfidenceThresholdPercent(Math.round(it)) },
                    valueRange = MatchConfidenceThreshold.MIN_PERCENT.toFloat()..MatchConfidenceThreshold.MAX_PERCENT.toFloat(),
                    steps = MatchConfidenceThreshold.MAX_PERCENT - MatchConfidenceThreshold.MIN_PERCENT - 1,
                    modifier = Modifier.testTag("match_confidence_threshold_slider")
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (wakePhraseMinLevelEnabled) "Minimum audio level: ${Math.round(wakePhraseMinLevelDbfs)} dBFS" else "Minimum audio level: off",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("wake_phrase_min_level_label")
                        )
                        Text(
                            text = "The wake phrase only counts if it is at least this loud, so distant voices (TV, other " +
                                "rooms) are ignored. Applies to starting and stopping; it never cuts audio from a memo. Compare " +
                                "with the live level on the dashboard, and lower it if the phrase is missed when you speak softly.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = wakePhraseMinLevelEnabled,
                        onCheckedChange = { viewModel.setWakePhraseMinLevelEnabled(it) },
                        modifier = Modifier.testTag("wake_phrase_min_level_switch")
                    )
                }
                if (wakePhraseMinLevelEnabled) {
                    Slider(
                        value = wakePhraseMinLevelDbfs,
                        onValueChange = { viewModel.setWakePhraseMinLevelDbfs(Math.round(it).toFloat()) },
                        valueRange = WakePhraseLevel.MIN_DBFS..WakePhraseLevel.MAX_DBFS,
                        modifier = Modifier.testTag("wake_phrase_min_level_slider")
                    )
                }
                if (matchConfidenceThresholdPercent < MatchConfidenceThreshold.DEFAULT_PERCENT) {
                    Text(
                        text = "Below ${MatchConfidenceThreshold.DEFAULT_PERCENT}%, similar-sounding words and other speech start " +
                            "memos much more often. Use low values only if the phrase is otherwise not detected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("match_confidence_threshold_warning")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "What the wake phrase does",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                KeywordBehavior.values().forEach { behavior ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setKeywordBehavior(behavior) }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = keywordBehavior == behavior,
                            onClick = { viewModel.setKeywordBehavior(behavior) }
                        )
                        Text(text = behavior.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // Section: Thresholds & Durations
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Recording",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Activation threshold (Loudness Only mode): ${Math.round(WakePhraseLevel.fromDetectorDb(thresholdDb))} dBFS",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "In Loudness Only mode, a memo starts when the microphone level stays above this for a quarter " +
                        "of a second. dBFS is the digital level (0 = loudest possible); the same voice reads differently on " +
                        "different phones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = thresholdDb,
                    onValueChange = { viewModel.setThresholdDb(it) },
                    valueRange = 35f..75f,
                    modifier = Modifier.testTag("activation_threshold_slider")
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Silence timeout: ${String.format(Locale.US, "%.1f s", silenceTimeoutMs / 1000.0)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "A memo ends after this much silence. Memos also end after 5 minutes at most.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = silenceTimeoutMs.toFloat(),
                    onValueChange = { viewModel.setSilenceTimeoutMs(it.toLong()) },
                    valueRange = 1000f..5000f,
                    steps = 7
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Pre-trigger buffer: ${preTriggerMs} ms",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Audio from just before a memo starts is kept, so the first word is not cut off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = preTriggerMs.toFloat(),
                    onValueChange = { viewModel.setPreTriggerDurationMs(it.toLong()) },
                    valueRange = 400f..2000f,
                    steps = 7
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Ignore while media plays", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "While music, videos or games play on this phone, sound and the wake phrase do not start " +
                                "memos. Quick Memo and stopping a memo still work.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = noiseFilterEnabled,
                        onCheckedChange = { viewModel.setNoiseFilterEnabled(it) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Recording sounds", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "A rising chime when a memo starts and a falling one when it stops (media volume).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { viewModel.previewFeedbackSounds() }) {
                        Text("Test")
                    }
                    Switch(
                        checked = feedbackSoundsEnabled,
                        onCheckedChange = { viewModel.setFeedbackSoundsEnabled(it) }
                    )
                }
            }
        }

        TranscriptionSettingsCard(viewModel, speechToTextEnabled)

        ShortcutSettingsCard(viewModel)

        // Section: Hardware Microphones
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Microphone",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                availableDevices.forEach { dev ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setSelectedAudioInput(dev) }
                            .padding(vertical = 6.dp)
                    ) {
                        RadioButton(
                            selected = selectedDeviceId == dev.id,
                            onClick = { viewModel.setSelectedAudioInput(dev) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = dev.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // Section: Appearance & Theming
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                ThemeMode.values().forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setThemeMode(mode) }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = mode.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = "Use wallpaper colors", fontWeight = FontWeight.SemiBold)
                        Switch(
                            checked = dynamicColor,
                            onCheckedChange = { viewModel.setDynamicColor(it) }
                        )
                    }
                }
            }
        }

        // Section: Danger Zone (Clear All Data)
        var showDeleteAllDialog by remember { mutableStateOf(false) }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Storage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { showDeleteAllDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Delete All Voice Memos")
                }
            }
        }

        if (showDeleteAllDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteAllDialog = false },
                title = { Text("Delete All Voice Memos") },
                text = { Text("All memos (recordings, titles and transcripts) are permanently removed from this device. Wake-phrase samples and settings are kept. This cannot be undone.") },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteAllVoiceNotes()
                            showDeleteAllDialog = false
                            Toast.makeText(context, "All voice memos deleted", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete All")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteAllDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        AboutCard()

        Spacer(modifier = Modifier.height(24.dp))
    }
}

private fun formatMillis(ms: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

