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

    val guideSections = remember {
        listOf(
            GuideSection(
                id = "sec_auto_detect",
                title = "Recording Memos",
                category = "Recording",
                summary = "Three ways to record: Quick Memo, the wake phrase, or loudness.",
                content = """
                    - Quick Memo: tap the big button at the bottom of the dashboard, or use the tile, widget or launcher shortcut. Tap again to stop.
                    - Wake phrase (default): turn on monitoring and say "Mango Tango" to start a memo. Say it again to stop.
                    - Loudness Only: in Settings → Detection Mode. While monitoring, any sound louder than the activation threshold for a quarter of a second starts a memo.

                    A memo also ends after a pause (the silence timeout, 2 seconds by default) and after 5 minutes at most. Memos are saved as WAV files on your phone and appear in History.

                    A short buffer keeps the audio from just before a memo starts, so the first word is not cut off. It is held only in memory and discarded when nothing is recorded.
                """.trimIndent(),
                tips = listOf(
                    "If monitoring was off, Quick Memo turns it on only for that memo and off again afterwards.",
                    "Lengthen the silence timeout in Settings if memos end while you pause to think.",
                    "Raise the activation threshold if Loudness Only starts memos on background noise."
                )
            ),
            GuideSection(
                id = "sec_keyword_spotter",
                title = "The \"Mango Tango\" Wake Phrase",
                category = "Wake phrase",
                summary = "Start and stop memos hands-free; works offline on your phone.",
                content = """
                    While monitoring is on, Voice Memo compares what it hears with the wake phrase. The built-in "Mango Tango" works without setup, but it was made from synthetic voices and does not recognise every voice.

                    For reliable detection, record the phrase in your own voice in Settings → Wake Phrase. You can also record a different phrase. Samples are stored on your phone only, as sound features, not as audio.

                    In Settings you choose whether the phrase only starts memos, or also stops them when you say it again. The phrase that stops a memo is removed from the recording; the phrase that starts it is kept. If a memo has just ended by itself, saying the phrase within 5 seconds counts as "stop" and does not start a new memo.
                """.trimIndent(),
                tips = listOf(
                    "Record samples at your normal speaking distance and volume; one is enough, up to 5 help.",
                    "Use Test Phrase in Settings to see how well you are recognised; it works exactly like monitoring.",
                    "Match confidence threshold (20–80 %, default 50 %): raise it if other words start memos, lower it if the phrase is missed. Below 50 %, similar words and ordinary speech start memos much more often. The dashboard shows the same value as the Trigger threshold.",
                    "Monitoring pauses while you record or test a phrase in Settings and resumes afterwards."
                )
            ),
            GuideSection(
                id = "sec_hardware_mic",
                title = "Monitoring, Shortcuts & Microphones",
                category = "Monitoring",
                summary = "Keep listening with the screen off, and record without opening the app.",
                content = """
                    Monitoring runs in the background with a notification and keeps working with the screen off.

                    While monitoring runs, the notification's Start/Stop memo button and the "Voice memo" Quick Settings tile work from the lock screen without unlocking. You can turn this off in Settings → Shortcuts & Lock Screen.

                    The tile, the "Record memo" home-screen widget and the "Record memo" launcher shortcut (long-press the app icon) also work when monitoring is off: they open Voice Memo and start a memo. A key combination on a Bluetooth or USB keyboard can start and stop memos while the app is open.

                    Voice Memo can record from the phone microphone, a wired headset, USB microphones and Bluetooth headsets (Settings → Microphone). A Bluetooth headset is used like in a phone call: lower sound quality, music on it sounds like a call, and its battery drains faster. The dashboard shows which microphone is in use, and why if it is not the one you selected.
                """.trimIndent(),
                tips = listOf(
                    "Add the tile: pull down Quick Settings, tap the edit (pencil) button and drag \"Voice memo\" in.",
                    "Add the widget: long-press the home screen, choose Widgets and drag Voice Memo onto it.",
                    "If monitoring stops on its own, exclude Voice Memo from battery optimisation in Android's settings."
                )
            ),
            GuideSection(
                id = "sec_transcription",
                title = "Speech-to-Text on Your Phone",
                category = "Transcription",
                summary = "Turn memos into text on your phone; the speech model is downloaded once.",
                content = """
                    Voice Memo transcribes memos on your phone with whisper.cpp and the OpenAI Whisper "base" speech model. The model is not part of the app: download it once in Settings → Speech-to-Text (about 148 MB, Wi-Fi recommended). After that, transcription works without an internet connection.

                    Tap Transcribe on a memo in History. You can edit or delete the text afterwards; the recording itself is never changed. Transcription only runs when you ask for it, unless you turn on "Transcribe new memos automatically". It continues in the background with a notification; long memos can take a while on older phones.

                    Memos are transcribed as English unless you choose another language in Settings → Speech-to-Text. Automatic language detection is also available, but it is slower and can pick the wrong language for short memos.
                """.trimIndent(),
                tips = listOf(
                    "\"Fast\" quality (default) is quicker; \"Accurate\" takes about twice as long and can make fewer mistakes.",
                    "Optional: import another whisper.cpp model file (ggml .bin) in Settings.",
                    "Removing the downloaded model frees space; your transcripts and recordings are kept."
                )
            ),
            GuideSection(
                id = "sec_privacy_foss",
                title = "Privacy & Your Data",
                category = "Privacy",
                summary = "Recording, detection and transcription happen on your phone.",
                content = """
                    - Audio is processed only on your phone: levels, wake phrase and speech-to-text. No audio or text is uploaded.
                    - The only internet use is the speech-model download you start in Settings.
                    - No accounts, ads, analytics or tracking. Voice Memo is free and open-source software.
                    - Memos are standard WAV files in the app's private storage.
                    - If backup is turned on in Android's settings, memos and settings can be included in your phone's backup.
                """.trimIndent(),
                tips = listOf(
                    "Export one memo as a WAV file, or several as a ZIP with the WAV files and a CSV/JSON list: History → Select → Save to… or Share.",
                    "Back up everything: History → Select → Select entire library → Save to…."
                )
            )
        )
    }
    val categories = listOf("All") + guideSections.map { it.category }.distinct()

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
                                text = "Hands-free voice memos",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "How to record memos, set up the wake phrase \"Mango Tango\", and turn memos into text, all on your phone.",
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
                                            text = "Tips",
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
