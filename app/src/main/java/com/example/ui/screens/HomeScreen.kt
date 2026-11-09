package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.importer.ImportProgressState
import com.example.ui.components.ChatImportProgressBanner
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ChatSession
import com.example.ui.components.VoiceWaveformVisualizer
import com.example.ui.components.getIconForName

import android.os.Build
import android.widget.Toast
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.ui.platform.LocalContext
import com.example.ui.components.ActiveChatTimerHUD
import com.example.ui.components.ChatStopwatchItemToggle
import com.example.ui.components.ScreenClockCard
import com.example.voice.VoiceEngineMode
import com.example.ui.components.BottomBarNetworkStatusIcon
import com.example.ui.components.VoiceOutputSettingsCard
import com.example.ui.components.WebSearchAndHistoryBar
import com.example.util.NetworkConnectionState
import com.example.util.VoiceCommandHistoryEntry
import com.example.util.WebLinkHistoryEntry

@Composable
fun CompactSettingRow(
    title: String,
    description: String? = null,
    icon: ImageVector,
    isChecked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = isChecked,
            onCheckedChange = onToggle,
            modifier = Modifier.scale(0.8f)
        )
    }
}

@Composable
fun CollapsibleSection(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null,
                modifier = Modifier.rotate(if (expanded) 180f else 0f))
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                content()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    chats: List<ChatSession>,
    isListening: Boolean,
    audioRmsDb: Float,
    statusMessage: String,
    lastHeardText: String,
    defaultPersistenceEnabled: Boolean = true,
    preserveCacheAcrossSessions: Boolean = true,
    heartbeatPingOnlyMode: Boolean = true,
    isHandsFreeActive: Boolean = false,
    dictateTrigger: String = "Dictate",
    sendTrigger: String = "Send",
    heartbeatTrigger: String = "Heartbeat",
    activeTimerChatIds: Set<Long> = emptySet(),
    liveTrackedSeconds: Map<Long, Long> = emptyMap(),
    voiceEngineMode: VoiceEngineMode = VoiceEngineMode.GEMINI_ORION,
    isOrionVoicePlaying: Boolean = false,
    onSetVoiceEngineMode: (VoiceEngineMode) -> Unit = {},
    onPlayOrionTestSample: () -> Unit = {},
    onToggleDefaultPersistence: (Boolean) -> Unit = {},
    onTogglePreserveCacheAcrossSessions: (Boolean) -> Unit = {},
    onToggleHeartbeatPingOnlyMode: (Boolean) -> Unit = {},
    onToggleHandsFree: (Boolean) -> Unit = {},
    onToggleListening: () -> Unit,
    onSelectChat: (Long) -> Unit,
    onCreateChat: () -> Unit,
    onEditChat: (ChatSession) -> Unit,
    onDeleteChat: (Long) -> Unit,
    onSetDefaultWakeChat: (Long) -> Unit,
    onOpenWakeSettings: () -> Unit,
    onOpenImportDialog: () -> Unit,
    onOpenCouplerDialog: () -> Unit = {},
    onToggleChatTimer: (Long) -> Unit = {},
    onResetChatTimer: (Long) -> Unit = {},
    importProgressState: ImportProgressState = ImportProgressState(),
    onCancelImport: () -> Unit = {},
    appTheme: com.example.ui.theme.AppTheme = com.example.ui.theme.AppTheme.SYSTEM,
    onSetAppTheme: (com.example.ui.theme.AppTheme) -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onOpenControlSheet: () -> Unit = {},
    onPowerExit: () -> Unit = {},
    networkState: NetworkConnectionState = NetworkConnectionState.ONLINE_WIFI,
    onRefreshNetwork: () -> Unit = {},
    webLinkHistory: List<WebLinkHistoryEntry> = emptyList(),
    onSubmitWebSearchOrUrl: (String) -> Unit = {},
    onOpenWebHistoryEntry: (WebLinkHistoryEntry) -> Unit = {},
    onDeleteWebHistoryEntry: (String) -> Unit = {},
    onClearAllWebHistory: () -> Unit = {},
    voiceCommandHistory: List<VoiceCommandHistoryEntry> = emptyList(),
    onUseVoiceCommandTranscript: (VoiceCommandHistoryEntry) -> Unit = {},
    onDeleteVoiceCommandEntry: (String) -> Unit = {},
    onClearAllVoiceCommands: () -> Unit = {},
    onClearCorruptedHistory: () -> Unit = {},
    onTextInputFocusChanged: (Boolean) -> Unit = {},
    isSleepMode: Boolean = false,
    sleepModeReason: String = "",
    wakeReadyVisualCue: com.example.ui.WakeReadyCueState? = null,
    onEnterSleepMode: () -> Unit = {},
    onExitSleepMode: () -> Unit = {},
    onDismissWakeReadyCue: () -> Unit = {},
    onNavigateToWakeWordStudio: () -> Unit = {},
    userEmail: String? = null,
    userDisplayName: String? = null,
    cloudSyncStatus: String = "Synced with Cloud Firestore",
    onSyncNow: () -> Unit = {},
    onSignOut: () -> Unit = {}
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF38BDF8), Color(0xFFA855F7))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Gemini Wake",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = when {
                                    isSleepMode -> "Sleep mode • Say \"Gemini, wake up\""
                                    isListening -> "Listening for wake word..."
                                    else -> "Voice trigger standby"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isListening && !isSleepMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    val context = LocalContext.current
                    IconButton(
                        onClick = onOpenImportDialog,
                        modifier = Modifier.testTag("top_bar_import_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Import Chat or Context",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onOpenControlSheet,
                        modifier = Modifier.testTag("top_bar_control_sheet_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Gemini Control & Settings",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onPowerExit,
                        modifier = Modifier.testTag("home_power_exit_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Power Off / Exit App",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                        )
                    }
                    IconButton(
                        onClick = onOpenCouplerDialog,
                        modifier = Modifier.testTag("home_coupler_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Gemini Coupler & Piggyback",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onNavigateToHistory,
                        modifier = Modifier.testTag("home_history_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Conversation History",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onOpenWakeSettings,
                        modifier = Modifier.testTag("wake_hub_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Hearing,
                            contentDescription = "Wake Word Hub",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateChat,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("create_chat_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Chat")
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BottomBarNetworkStatusIcon(
                        networkState = networkState,
                        onRefreshNetwork = onRefreshNetwork
                    )
                    Text(
                        text = when {
                            isSleepMode -> "Sleep Mode (Paused)"
                            isListening -> "Wake Radar Active"
                            else -> "Wake Radar Standby"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            ChatImportProgressBanner(
                progressState = importProgressState,
                onCancelImport = onCancelImport
            )

            SleepModeVisualBanner(
                isSleepMode = isSleepMode,
                sleepReason = sleepModeReason,
                onWakeUpTap = onExitSleepMode
            )

            WakeReadyVisualCueBanner(
                cueState = wakeReadyVisualCue,
                onOpenChat = onSelectChat,
                onDismiss = onDismissWakeReadyCue
            )

            // Home Screen Web Search Bar + Swipe-Down Link & Search + Voice Command History Dropdown Menu
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                WebSearchAndHistoryBar(
                    historyEntries = webLinkHistory,
                    onSubmitSearchOrUrl = onSubmitWebSearchOrUrl,
                    onOpenHistoryEntry = onOpenWebHistoryEntry,
                    onDeleteHistoryEntry = onDeleteWebHistoryEntry,
                    onClearAllHistory = onClearAllWebHistory,
                    voiceCommandEntries = voiceCommandHistory,
                    onUseVoiceCommandTranscript = onUseVoiceCommandTranscript,
                    onDeleteVoiceCommandEntry = onDeleteVoiceCommandEntry,
                    onClearAllVoiceCommands = onClearAllVoiceCommands,
                    onClearCorruptedHistory = onClearCorruptedHistory,
                    onTextInputFocusChanged = onTextInputFocusChanged
                )
            }

            // Compact Voice Listener / Wake Radar Visualizer (max height 52dp)
            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                WakeWordRadarCard(
                    isListening = isListening && !isSleepMode,
                    audioRmsDb = audioRmsDb,
                    statusMessage = statusMessage,
                    lastHeardText = lastHeardText,
                    onToggleListening = {
                        if (isSleepMode) onExitSleepMode() else onToggleListening()
                    },
                    onOpenWakeSettings = onOpenWakeSettings
                )
            }

            // Quick Bar: Custom Wake Word Recording Studio + Sleep Mode Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.FilledTonalButton(
                    onClick = onNavigateToWakeWordStudio,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_wake_word_studio_button"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Custom Wake Word Studio", style = MaterialTheme.typography.labelMedium)
                }

                androidx.compose.material3.OutlinedButton(
                    onClick = { if (isSleepMode) onExitSleepMode() else onEnterSleepMode() },
                    modifier = Modifier.testTag("home_sleep_mode_toggle_button"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = if (isSleepMode) Icons.Default.Mic else Icons.Default.MicOff,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isSleepMode) "Wake Up" else "Sleep",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (!userEmail.isNullOrBlank() || !userDisplayName.isNullOrBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("cloud_sync_account_bar"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDone,
                            contentDescription = "Cloud Firestore Sync",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = userDisplayName?.takeIf { it.isNotBlank() } ?: userEmail.orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = cloudSyncStatus,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onSyncNow,
                            modifier = Modifier.testTag("cloud_sync_now_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "Sync Now with Cloud Firestore",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onSignOut,
                            modifier = Modifier.testTag("google_sign_out_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Logout,
                                contentDescription = "Sign Out of Google Account",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            // Chat List Header & Items (Full Height Focus)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Your Chosen Chats",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "${chats.size} chats configured",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (chats.isEmpty()) {
                item {
                    EmptyChatsPlaceholder(
                        onCreateChat = onCreateChat,
                        onOpenImportDialog = onOpenImportDialog
                    )
                }
            } else {
                itemsIndexed(
                    items = chats,
                    key = { index, chat -> "${chat.id}_$index" }
                ) { _, chat ->
                    val isRunning = activeTimerChatIds.contains(chat.id)
                    val seconds = liveTrackedSeconds[chat.id] ?: chat.totalTrackedSeconds
                    ChatSessionCard(
                        chat = chat,
                        trackedSeconds = seconds,
                        isTimerRunning = isRunning,
                        onClick = { onSelectChat(chat.id) },
                        onEdit = { onEditChat(chat) },
                        onDelete = { onDeleteChat(chat.id) },
                        onSetDefault = { onSetDefaultWakeChat(chat.id) },
                        onToggleTimer = { onToggleChatTimer(chat.id) },
                        onResetTimer = { onResetChatTimer(chat.id) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(72.dp))
            }
        }
    }
}
}




@Composable
fun WakeWordRadarCard(
    isListening: Boolean,
    audioRmsDb: Float,
    statusMessage: String,
    lastHeardText: String,
    onToggleListening: () -> Unit,
    onOpenWakeSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("wake_word_radar_card"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        border = if (isListening) {
            androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        } else null,
        onClick = onOpenWakeSettings
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Status Section
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(0.8f)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isListening) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = if (isListening) "RADAR ACTIVE" else "RADAR PAUSED",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                        color = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    if (lastHeardText.isNotBlank() && isListening) {
                        Text(
                            text = "Heard: \"$lastHeardText\"",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = "Say wake phrase to jump to chat",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }
                }
            }

            // 2. Waveform Visualizer Section
            VoiceWaveformVisualizer(
                isListening = isListening,
                audioRmsDb = audioRmsDb,
                modifier = Modifier.weight(1.2f).padding(horizontal = 8.dp),
                height = 24.dp,
                barCount = 18
            )

            // 3. Toggle Button Section
            IconButton(
                onClick = onToggleListening,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (isListening) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.primary
                    )
                    .testTag("toggle_listening_hud_button")
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = null,
                    tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun ChatSessionCard(
    chat: ChatSession,
    trackedSeconds: Long = 0L,
    isTimerRunning: Boolean = false,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    onToggleTimer: () -> Unit = {},
    onResetTimer: () -> Unit = {}
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val accentColor = remember(chat.themeColorHex) {
        try {
            Color(android.graphics.Color.parseColor(chat.themeColorHex.trim()))
        } catch (_: Throwable) {
            Color(0xFF38BDF8)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat_card_${chat.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = if (chat.isDefaultWakeChat) {
            androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        },
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getIconForName(chat.iconName),
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = chat.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        if (chat.isDefaultWakeChat) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Default Wake Target",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "\"${chat.wakeWord}\"",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (chat.isPersistent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                    else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (chat.isPersistent) "💾 Disk" else "⚡ RAM",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = if (chat.isPersistent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                            )
                        }

                        if (chat.isDefaultWakeChat) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Default Target",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    if (chat.greetingMessage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Greeting: \"${chat.greetingMessage}\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("home_chat_greeting_preview_${chat.id}")
                        )
                    }
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag("chat_card_menu_${chat.id}")
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Open Chat") },
                            leadingIcon = { Icon(Icons.Default.ArrowForward, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onClick()
                            }
                        )
                        if (!chat.isDefaultWakeChat) {
                            DropdownMenuItem(
                                text = { Text("Set as Default Wake Target") },
                                leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onSetDefault()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Edit Chat, Greeting & Wake Word") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Chat", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Stopwatch / Timer UI graphic toggle to track time on this listed chat
            ChatStopwatchItemToggle(
                chatId = chat.id,
                seconds = trackedSeconds,
                isRunning = isTimerRunning,
                accentColor = accentColor,
                onToggle = onToggleTimer,
                onReset = onResetTimer,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun ImportGeminiBannerCard(onOpenImportDialog: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("import_gemini_banner_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Load Preexisting Gemini Chat",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Import via share link or transcript & assign a voice wake word.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onOpenImportDialog,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("banner_import_button")
            ) {
                Text(
                    text = "Import",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
fun EmptyChatsPlaceholder(
    onCreateChat: () -> Unit,
    onOpenImportDialog: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Hearing,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "No chats yet",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Create a new Gemini chat with a custom wake word, or import a preexisting chat from standard Gemini.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onCreateChat,
                    modifier = Modifier.testTag("empty_create_chat_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("New Chat")
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = onOpenImportDialog,
                    modifier = Modifier.testTag("empty_import_chat_button")
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import Chat")
                }
            }
        }
    }
}



@Composable
fun DefaultPersistenceSettingsCard(
    defaultPersistenceEnabled: Boolean,
    onToggleDefaultPersistence: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("default_persistence_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Default Storage Persistence",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = if (defaultPersistenceEnabled) "New chats default to SQLite disk persistence" else "New chats default to ephemeral RAM buffers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            androidx.compose.material3.Switch(
                checked = defaultPersistenceEnabled,
                onCheckedChange = onToggleDefaultPersistence,
                modifier = Modifier.testTag("global_persistence_switch")
            )
        }
    }
}

@Composable
fun CachePreservationSettingsCard(
    preserveCacheEnabled: Boolean,
    onTogglePreserveCache: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("preserve_cache_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Preserve Cache Across Sessions",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = if (preserveCacheEnabled)
                            "Cleanly closes sockets while preserving staged disk buffers and message history"
                        else
                            "Session switches execute nuclear wipe of local bridge caches",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            androidx.compose.material3.Switch(
                checked = preserveCacheEnabled,
                onCheckedChange = onTogglePreserveCache,
                modifier = Modifier.testTag("preserve_cache_switch")
            )
        }
    }
}

@Composable
fun HeartbeatModeSettingsCard(
    heartbeatEnabled: Boolean,
    onToggleHeartbeat: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("heartbeat_settings_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Heartbeat Ping Only (Background)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = if (heartbeatEnabled)
                            "Tears down heavy HTTP bridge when minimized, maintaining low-power AlarmManager heartbeat & alarm alerts"
                        else
                            "Keeps continuous HTTP bridge running while minimized",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            androidx.compose.material3.Switch(
                checked = heartbeatEnabled,
                onCheckedChange = onToggleHeartbeat,
                modifier = Modifier.testTag("heartbeat_ping_switch")
            )
        }
    }
}

@Composable
fun HandsFreeVoiceSettingsCard(
    isHandsFreeActive: Boolean,
    dictateTrigger: String,
    sendTrigger: String,
    heartbeatTrigger: String,
    onToggleHandsFree: (Boolean) -> Unit,
    onOpenWakeSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hands_free_settings_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = if (isHandsFreeActive) {
            androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        } else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Hearing,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Hands-Free Voice Dictation",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = if (isHandsFreeActive)
                                "Real-time voice stream typing directly into chat input"
                            else
                                "Paused - toggle to activate continuous voice triggers",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                androidx.compose.material3.Switch(
                    checked = isHandsFreeActive,
                    onCheckedChange = onToggleHandsFree,
                    modifier = Modifier.testTag("hands_free_global_switch")
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                onClick = onOpenWakeSettings
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Configured Trigger Words:",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "🎤 \"$dictateTrigger\"",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "🚀 \"$sendTrigger\"",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "⚡ \"$heartbeatTrigger\"",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = "Edit Triggers",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
