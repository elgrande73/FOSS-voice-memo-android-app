package io.github.elgrande73.voicememo.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class GuideSection(
    val id: String,
    val title: String,
    val category: String,
    val summary: String,
    val content: String,
    val icon: ImageVector = Icons.Default.Info,
    val tips: List<String> = emptyList()
)

@Composable
fun GuideTab() {
    var selectedFilterIndex by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    val categories = listOf(
        "All Sections",
        "1. Automatic Voice Memos",
        "2. Wake Phrase",
        "3. Pre-Trigger Buffering",
        "4. Mic & Background Setup",
        "5. Privacy & Offline FOSS"
    )

    val guideSections = remember {
        listOf(
            GuideSection(
                id = "sec_auto_detect",
                title = "Automatic Sound & Speech Detection",
                category = "1. Automatic Voice Memos",
                summary = "How Voice Memo continuously monitors audio and captures memos without touching the screen.",
                content = """
                    Voice Memo operates in the background, listening through your selected microphone for audible speech or relevant sounds.
                    
                    When sound volume exceeds your configured Activation Threshold (e.g. 48 dB) for at least 250 milliseconds, Voice Memo automatically starts recording.
                    
                    As long as you continue speaking, recording continues. Once you stop speaking and silence persists for the configured Silence Timeout (default 2.0 seconds), the memo is safely saved to local storage.
                """.trimIndent(),
                tips = listOf(
                    "Adjust the Activation Threshold in Settings: Lower dB for whispering/quiet rooms, higher dB for noisy environments.",
                    "The Silence Timeout prevents premature cuts if you take brief natural pauses between sentences.",
                    "Maximum memo duration can be set in Settings (default 5 minutes) to prevent accidental infinite recordings."
                )
            ),
            GuideSection(
                id = "sec_keyword_spotter",
                title = "\"Ciao Linda\" Wake Phrase Detection",
                category = "2. Wake Phrase",
                summary = "Train and use the custom offline wake phrase to initiate or stop memos hands-free.",
                content = """
                    In addition to raw sound level detection, Voice Memo features a built-in offline Keyword Spotter. The default wake phrase is "Ciao Linda".
                    
                    You can record your personal reference phrase in Settings → Wake Phrase. When monitoring is active, incoming audio is analyzed using local spectral filterbanks and Dynamic Time Warping (DTW) to match your voice.
                    
                    You can configure the keyword to Start only, Stop only, or both Start & Stop recording.
                """.trimIndent(),
                tips = listOf(
                    "Record the \"Ciao Linda\" template in a quiet room at your normal conversational speaking distance.",
                    "Use the 'Test Phrase' button in Settings to check the match confidence of your voice; the result is saved and is computed exactly as during monitoring.",
                    "Monitoring pauses automatically while you record or test a phrase in Settings and resumes afterwards.",
                    "Raise the Match confidence threshold (50%–80%) if the phrase triggers too easily, lower it if it is hard to trigger. The dashboard shows the same value as the Trigger threshold."
                )
            ),
            GuideSection(
                id = "sec_pre_trigger",
                title = "Rolling Pre-Trigger Audio Buffer",
                category = "3. Pre-Trigger Buffering",
                summary = "Why Voice Memo never clips the first syllable of your voice memos.",
                content = """
                    Traditional voice-activated recorders often miss the first word because recording only starts after a threshold is exceeded.
                    
                    Voice Memo solves this with a Rolling Pre-Trigger Ring Buffer. While passively monitoring, it retains the last 500ms to 1200ms of audio in temporary memory.
                    
                    When a trigger is detected, the buffered pre-roll audio is immediately prepended to the WAV file. Your initial word ("Remember...", "Idea for...", "Ciao Linda...") is captured in full clarity.
                """.trimIndent(),
                tips = listOf(
                    "Configure Pre-Trigger duration in Settings (800ms is optimal for most speech).",
                    "The pre-trigger buffer is kept strictly in volatile RAM and is continuously discarded when no trigger occurs."
                )
            ),
            GuideSection(
                id = "sec_hardware_mic",
                title = "Microphone & Background Monitoring",
                category = "4. Mic & Background Setup",
                summary = "Selecting audio input sources and keeping monitoring active with screen turned off.",
                content = """
                    Voice Memo supports multiple hardware audio input routes:
                    - Built-in Phone Microphones
                    - USB Audio Interfaces & Lavalier Microphones
                    - Bluetooth Headsets & Wireless Earbuds (via Bluetooth SCO)
                    
                    Monitoring runs as an Android Foreground Service with a persistent notification. It can run continuously with your screen off, inside your pocket, or placed on a desk.
                """.trimIndent(),
                tips = listOf(
                    "Exclude Voice Memo from aggressive OS Battery Optimization in Android Settings for uninterrupted overnight listening.",
                    "If plugging in or disconnecting a headset, the audio pipeline automatically re-routes with zero data loss."
                )
            ),
            GuideSection(
                id = "sec_privacy_foss",
                title = "100% Offline, Privacy-First & FOSS",
                category = "5. Privacy & Offline FOSS",
                summary = "Your voice recordings never leave your device. Zero cloud, zero tracking.",
                content = """
                    Voice Memo is built according to strict Free and Open Source Software (FOSS) principles and privacy standards:
                    
                    - All audio processing (RMS, DSP, FFT, Keyword Spotting) runs 100% locally on your device CPU.
                    - No audio is ever uploaded to the cloud or third-party servers.
                    - No telemetry, analytics, or advertising SDKs.
                    - Standard open formats: Audio is stored as standard 16-bit PCM WAV.
                    - Export easily via standard Android Share, or package all memos into a ZIP archive with CSV/JSON metadata.
                """.trimIndent(),
                tips = listOf(
                    "You can backup all your voice notes anytime from Settings → Export All Notes as ZIP.",
                    "Files are stored in private app storage and can be shared to any messaging or cloud backup app of your choice."
                )
            )
        )
    }

    val filteredSections = if (selectedFilterIndex == 0) {
        guideSections
    } else {
        val cat = categories[selectedFilterIndex]
        guideSections.filter { it.category == cat }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("guide_tab"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "guide_hero") {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Guide Icon",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Voice Memo Guide",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Automatic Voice-Note Assistant",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Learn how to configure hands-free voice-note capture, train the wake phrase (default \"Ciao Linda\"), and manage your offline voice memos.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        item(key = "guide_categories") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(categories.size) { index ->
                    FilterChip(
                        selected = selectedFilterIndex == index,
                        onClick = { selectedFilterIndex = index },
                        label = { Text(categories[index]) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }
        }

        items(filteredSections.size) { idx ->
            val section = filteredSections[idx]
            var isExpanded by remember { mutableStateOf(idx == 0) }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = section.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = section.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                    )

                    AnimatedVisibility(visible = isExpanded) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(
                                text = section.content,
                                style = MaterialTheme.typography.bodyMedium,
                                lineHeight = 20.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (section.tips.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            text = "Key Tips & Best Practices:",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        section.tips.forEach { tip ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = tip,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "guide_footer") {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
