package io.github.elgrande73.voicememo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.elgrande73.voicememo.memo.VoiceNote
import io.github.elgrande73.voicememo.detection.DetectionMode
import io.github.elgrande73.voicememo.detection.KeywordBehavior
import io.github.elgrande73.voicememo.monitoring.AudioMonitoringService
import io.github.elgrande73.voicememo.ui.GuideTab
import io.github.elgrande73.voicememo.ui.theme.MyApplicationTheme
import io.github.elgrande73.voicememo.ui.theme.ThemeMode
import io.github.elgrande73.voicememo.viewmodel.VoiceMemoViewModel
import io.github.elgrande73.voicememo.viewmodel.VoiceNoteFilter
import io.github.elgrande73.voicememo.settings.MatchConfidenceThreshold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val viewModel: VoiceMemoViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        hasNotificationPermission = permissions[Manifest.permission.POST_NOTIFICATIONS] == true
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        hasBluetoothPermission = permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
                    }
                    viewModel.refreshAvailableInputDevices()
                }

                LaunchedEffect(Unit) {
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
    val isCurrentlyRecording by AudioMonitoringService.isCurrentlyRecording.collectAsState()
    val recordingDurationSeconds by AudioMonitoringService.recordingDurationSeconds.collectAsState()
    val liveDb by AudioMonitoringService.liveDbLevel.collectAsState()
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

    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .testTag("dashboard_screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                        text = "Voice Memo needs microphone access to listen for voice memos and wake phrases.",
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
                    isServiceRunning -> MaterialTheme.colorScheme.primaryContainer
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
                                        isServiceRunning -> Color(0xFF4CAF50)
                                        else -> Color.Gray
                                    }
                                )
                        )
                        Column {
                            Text(
                                text = when {
                                    isCurrentlyRecording -> "Recording Voice Note..."
                                    isServiceRunning -> "Monitoring Active"
                                    else -> "Monitoring Paused"
                                },
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
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

        // Live Audio Level & Threshold Visualizer
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
                    Text(
                        text = "Live Audio Level",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${liveDb.toInt()} dB",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (liveDb >= thresholdDb) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Decibel Progress Bar with Threshold Marker
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val progressFraction = (liveDb / 100f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = progressFraction)
                            .height(24.dp)
                            .background(
                                if (liveDb >= thresholdDb) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                            )
                    )

                    // Threshold indicator line
                    val thresholdFraction = (thresholdDb / 100f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = thresholdFraction)
                            .height(24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(3.dp)
                                .height(24.dp)
                                .background(Color.Red)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Noise Floor: ${liveNoiseFloorDb.toInt()} dB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Activation threshold: ${thresholdDb.toInt()} dB",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Red
                    )
                }
            }
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
                            text = "Live similarity to your wake phrase",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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

        // Primary Hero Action Controls
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Button(
                onClick = {
                    if (isServiceRunning) {
                        viewModel.stopServiceDetection()
                    } else {
                        viewModel.startServiceDetection()
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .testTag("toggle_monitoring_button")
            ) {
                Text(
                    text = if (isServiceRunning) "Stop Monitoring" else "Start Monitoring",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            OutlinedButton(
                onClick = {
                    if (isCurrentlyRecording) {
                        viewModel.stopManualRecording()
                        Toast.makeText(context, "Voice memo saved", Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.startManualRecording()
                    }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .testTag("manual_record_button")
            ) {
                Text(
                    text = if (isCurrentlyRecording) "Stop Memo" else "Quick Memo",
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
                            text = if (isServiceRunning) "Active" else "Idle",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isServiceRunning) Color(0xFF4CAF50) else Color.Gray
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
                        text = "Duration: ${String.format(Locale.US, "%.1fs", note.durationSeconds)} • Peak: ${note.peakDb.toInt()} dB",
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
fun HistoryScreen(viewModel: VoiceMemoViewModel) {
    val notes by viewModel.filteredVoiceNotes.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val activeFilter by viewModel.activeFilter.collectAsState()
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
                .testTag("search_notes_input")
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Filter Category Chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            VoiceNoteFilter.values().forEach { filter ->
                FilterChip(
                    selected = activeFilter == filter,
                    onClick = { viewModel.setFilter(filter) },
                    label = { Text(filter.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Header Count & Batch Export Action
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

            if (notes.isNotEmpty()) {
                TextButton(
                    onClick = {
                        viewModel.exportAllNotesAsZip { shareIntent ->
                            if (shareIntent != null) {
                                context.startActivity(shareIntent)
                            } else {
                                Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !exportInProgress
                ) {
                    if (exportInProgress) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    } else {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Export All", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Export All (ZIP)")
                    }
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
                        text = if (searchQuery.isNotEmpty()) "No matching memos found" else "No voice memos recorded yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Start monitoring or tap Quick Memo on Dashboard",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(notes, key = { it.id }) { note ->
                    val isCurrentPlaying = playingNoteId == note.id

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isCurrentPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("voice_note_card_${note.id}")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
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
                                        text = when (note.triggerType) {
                                            "KEYWORD_CIAO_LINDA" -> "🎙️ Wake Phrase"
                                            "MANUAL" -> "👆 Manual"
                                            else -> "🤖 Auto Sound"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                Text(
                                    text = "Peak: ${note.peakDb.toInt()} dB",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Transcription Box if available
                            note.transcriptionText?.let { transcript ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = transcript,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }

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
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isCurrentPlaying && isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                    ),
                                    modifier = Modifier.size(44.dp)
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
                    modifier = Modifier.fillMaxWidth()
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

    // Delete Confirmation Dialog
    noteToDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text("Delete Voice Memo") },
            text = { Text("Are you sure you want to delete \"${note.title}\"? This cannot be undone.") },
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
    val thresholdDb by viewModel.thresholdDb.collectAsState()
    val silenceTimeoutMs by viewModel.silenceTimeoutMs.collectAsState()
    val preTriggerMs by viewModel.preTriggerDurationMs.collectAsState()
    val maxMemoDurationMs by viewModel.maxMemoDurationMs.collectAsState()
    val noiseFilterEnabled by viewModel.noiseFilterEnabled.collectAsState()
    val feedbackSoundsEnabled by viewModel.feedbackSoundsEnabled.collectAsState()

    val matchConfidenceThresholdPercent by viewModel.matchConfidenceThresholdPercent.collectAsState()
    val keywordBehavior by viewModel.keywordBehavior.collectAsState()
    val hasKeywordTemplate by viewModel.hasKeywordTemplate.collectAsState()
    val isRecordingKeyword by viewModel.isRecordingKeyword.collectAsState()
    val isTestingKeyword by viewModel.isTestingKeyword.collectAsState()
    val keywordTestResult by viewModel.keywordTestResult.collectAsState()
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

        // Section: Wake Phrase (default: "Ciao Linda") Keyword Spotter
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
                            text = if (hasKeywordTemplate) "Phrase Trained" else "Not Trained",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (hasKeywordTemplate) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "The default wake phrase is \"Ciao Linda\". Record it in your own voice to train a personal, on-device template. Deleting your recording restores the built-in default.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (isRecordingKeyword) {
                                viewModel.stopRecordingKeywordTemplate()
                            } else {
                                viewModel.startRecordingKeywordTemplate()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecordingKeyword) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isRecordingKeyword) "Stop Recording" else "Record Phrase")
                    }

                    if (hasKeywordTemplate) {
                        OutlinedButton(
                            onClick = { viewModel.testKeywordAudio() },
                            enabled = !isTestingKeyword,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isTestingKeyword) "Listening..." else "Test Phrase")
                        }
                    }
                }

                if (hasKeywordTemplate) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = { viewModel.clearKeywordTemplate() },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete Phrase Template")
                    }
                }

                keywordTestResult?.let { result ->
                    val scorePercent = (result.confidence * 100).toInt()
                    val wouldTrigger = result.confidence >= MatchConfidenceThreshold.toDetectorConfidence(matchConfidenceThresholdPercent)
                    val testedAt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(result.timestamp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Last test ($testedAt): $scorePercent% match — " +
                            if (wouldTrigger) "would trigger" else "below the threshold",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (wouldTrigger) Color(0xFF4CAF50) else Color.Red,
                        modifier = Modifier.testTag("wake_phrase_test_result")
                    )
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
                    text = "How closely speech must match the wake phrase before recording starts. " +
                        "Higher values reduce false triggers; lower values detect the phrase more easily.",
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
                Text(
                    text = "Keyword Action Behavior",
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
                    text = "Audio & Trigger Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Activation Threshold: ${thresholdDb.toInt()} dB",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Slider(
                    value = thresholdDb,
                    onValueChange = { viewModel.setThresholdDb(it) },
                    valueRange = 35f..75f
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Silence Timeout: ${String.format(Locale.US, "%.1fs", silenceTimeoutMs / 1000.0)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Recording stops after this duration of silence.",
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
                    text = "Rolling Pre-Trigger Buffer: ${preTriggerMs} ms",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Preserves audio before trigger so initial words aren't cut off.",
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
                        Text(text = "Voice Formant Filter", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Restricts sound trigger to speech frequencies (300Hz - 3400Hz)",
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
                        Text(text = "Recording Sounds", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Plays a rising chime when a memo starts and a falling chime when it stops (uses media volume)",
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

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Speech-to-Text Transcription", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Runs on-device transcription asynchronously when a memo is saved",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = speechToTextEnabled,
                        onCheckedChange = { viewModel.setSpeechToTextEnabled(it) }
                    )
                }
            }
        }

        // Section: Hardware Microphones
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Hardware Audio Input",
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
                        Text(text = "Dynamic Material You Colors", fontWeight = FontWeight.SemiBold)
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
                    text = "Storage & Reset",
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
                text = { Text("Are you sure you want to permanently delete all voice memos and audio files? This cannot be undone.") },
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

        Spacer(modifier = Modifier.height(24.dp))
    }
}

private fun formatMillis(ms: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

