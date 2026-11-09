package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Tune
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.graphics.BitmapFactory
import com.example.voice.HandsFreeVoiceState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.importer.ImportProgressState
import com.example.ui.components.ChatImportProgressBanner
import com.example.ai.ActiveKeyType
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Folder
import com.example.ui.components.VoiceWaveformVisualizer
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.coupler.StagedImageInfo
import android.net.Uri
import com.example.ui.components.StagedImageTray
import com.example.ui.components.ScreenshotHeadsUpBanner
import com.example.ui.TriggerBanner
import com.example.ui.components.getIconForName
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import com.example.ui.components.ChatStopwatchDial
import com.example.ui.components.ScreenClockCard
import com.example.ui.components.UsageLimitVsCacheComparisonCard
import com.example.ui.components.formatTrackedTime
import com.example.data.model.PromptTemplate
import com.example.ui.components.BottomBarNetworkStatusIcon
import com.example.ui.components.CreatePromptDialog
import com.example.ui.components.FloatingMovableChatInput
import com.example.ui.components.WebSearchAndHistoryBar
import com.example.util.NetworkConnectionState
import com.example.util.VoiceCommandHistoryEntry
import com.example.util.WebLinkHistoryEntry
import com.example.util.WebLinkHistoryManager
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    chat: ChatSession,
    messages: List<ChatMessage>,
    streamingText: String?,
    isGenerating: Boolean,
    errorMessage: String?,
    triggerBanner: TriggerBanner?,
    isAutoSpeakEnabled: Boolean,
    isSpeaking: Boolean,
    bufferMetrics: com.example.cache.BufferMetrics,
    hasMoreOlderMessages: Boolean,
    isLoadingOlder: Boolean,
    openChatTimerSeconds: Long = 0L,
    isOpenChatTimerRunning: Boolean = false,
    showUsageLimitsComparison: Boolean = true,
    customPrompts: List<PromptTemplate> = emptyList(),
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onToggleAutoSpeak: () -> Unit,
    onSpeakText: (String) -> Unit,
    onStopSpeaking: () -> Unit,
    onClearHistory: () -> Unit,
    onEditChat: () -> Unit,
    onLoadOlderMessages: () -> Unit,
    onOpenBufferBridgeDialog: () -> Unit,
    onOpenCouplerDialog: () -> Unit = {},
    onDismissTriggerBanner: () -> Unit,
    onToggleChatPersistence: () -> Unit = {},
    onToggleOpenChatTimer: () -> Unit = {},
    onResetOpenChatTimer: () -> Unit = {},
    onToggleUsageLimitsComparison: (Boolean) -> Unit = {},
    onAddCustomPrompt: (String, String) -> Unit = { _, _ -> },
    onDeleteCustomPrompt: (String) -> Unit = {},
    isHandsFreeActive: Boolean = false,
    handsFreeVoiceState: HandsFreeVoiceState = HandsFreeVoiceState.IDLE,
    dictateTrigger: String = "Dictate",
    sendTrigger: String = "Send",
    heartbeatTrigger: String = "Heartbeat",
    draftSpokenText: String = "",
    onToggleHandsFree: () -> Unit = {},
    stagedImageUri: Uri? = null,
    stagedImageInfo: StagedImageInfo? = null,
    screenshotBanner: StagedImageInfo? = null,
    onDismissScreenshotBanner: () -> Unit = {},
    onRemoveStagedImage: () -> Unit = {},
    onDispatchScreenshotToCoupler: (String) -> Unit = {},
    importProgressState: ImportProgressState = ImportProgressState(),
    onCancelImport: () -> Unit = {},
    activeKeyType: ActiveKeyType = ActiveKeyType.PRIMARY,
    onToggleActiveKey: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onBrowseFiles: () -> Unit = {},
    stagedAttachment: com.example.ui.AttachmentPayload? = null,
    onClearStagedAttachment: () -> Unit = {},
    onUpdateChatTheme: (com.example.ui.theme.AppTheme) -> Unit = {},
    isVoiceRecording: Boolean = false,
    audioRmsDb: Float = 0f,
    onStartVoiceRecording: () -> Unit = {},
    onSendVoiceRecording: (String) -> Unit = {},
    onCancelVoiceRecording: () -> Unit = {},
    onOpenControlSheet: () -> Unit = {},
    onOpenImportDialog: () -> Unit = {},
    onPowerExit: () -> Unit = {},
    networkState: NetworkConnectionState = NetworkConnectionState.ONLINE_WIFI,
    onRefreshNetwork: () -> Unit = {},
    webLinkHistory: List<WebLinkHistoryEntry> = emptyList(),
    onOpenChatLink: (String, String?) -> Unit = { _, _ -> },
    onSubmitWebSearchOrUrl: (String) -> Unit = {},
    onOpenWebHistoryEntry: (WebLinkHistoryEntry) -> Unit = {},
    onDeleteWebHistoryEntry: (String) -> Unit = {},
    onClearAllWebHistory: () -> Unit = {},
    voiceCommandHistory: List<VoiceCommandHistoryEntry> = emptyList(),
    onDeleteVoiceCommandEntry: (String) -> Unit = {},
    onClearAllVoiceCommands: () -> Unit = {},
    onClearCorruptedHistory: () -> Unit = {},
    onTextInputFocusChanged: (Boolean) -> Unit = {},
    wakeRetriggerEventId: Long = 0L,
    isSleepMode: Boolean = false,
    sleepModeReason: String = "",
    onEnterSleepMode: () -> Unit = {},
    onExitSleepMode: () -> Unit = {}
) {
    BackHandler {
        onBack()
    }

    val context = LocalContext.current
    var inputText by remember(chat.id) { mutableStateOf(TextFieldValue("")) }
    var isSending by remember { mutableStateOf(false) }
    var menuExpanded by remember(chat.id) { mutableStateOf(false) }
    var showPromptsBar by remember(chat.id) { mutableStateOf(false) }
    var showCreatePromptDialog by remember(chat.id) { mutableStateOf(false) }
    var showWebHistoryBar by remember(chat.id) { mutableStateOf(false) }
    var isInputFloating by remember(chat.id) { mutableStateOf(false) }
    var isFloatingCollapsedToDot by remember(chat.id) { mutableStateOf(false) }
    var floatingOffsetX by remember(chat.id) { mutableStateOf(24f) }
    var floatingOffsetY by remember(chat.id) { mutableStateOf(160f) }
    val listState = rememberLazyListState()

    val accentColor = remember(chat.themeColorHex) {
        try {
            Color(android.graphics.Color.parseColor(chat.themeColorHex.trim()))
        } catch (_: Throwable) {
            Color(0xFF38BDF8)
        }
    }

    // STRICT SESSION ISOLATION: Filter messages strictly to this chat.id to prevent cross-session bleeding
    val isolatedMessages = remember(chat.id, messages) {
        messages.filter { it.chatId == chat.id }
    }

    // Re-key LaunchedEffect(chat.id) so switching between different chats immediately purges active state & scrolls to fresh thread
    LaunchedEffect(chat.id) {
        inputText = TextFieldValue("")
        isSending = false
        menuExpanded = false
        showPromptsBar = false
        if (isolatedMessages.isNotEmpty()) {
            listState.scrollToItem(isolatedMessages.size - 1)
        } else {
            listState.scrollToItem(0)
        }
    }

    // Force reset stale draft text when a wake-word re-triggers while already inside ChatDetailScreen
    LaunchedEffect(wakeRetriggerEventId) {
        if (wakeRetriggerEventId > 0L) {
            inputText = TextFieldValue(
                text = draftSpokenText,
                selection = androidx.compose.ui.text.TextRange(draftSpokenText.length)
            )
            isSending = false
        }
    }

    // Real-time partial streaming: types hands-free voice input directly into message input field
    LaunchedEffect(draftSpokenText) {
        if (draftSpokenText.isNotBlank()) {
            inputText = TextFieldValue(
                text = draftSpokenText,
                selection = androidx.compose.ui.text.TextRange(draftSpokenText.length)
            )
        } else if (isVoiceRecording || handsFreeVoiceState == HandsFreeVoiceState.TRANSCRIBING) {
            inputText = TextFieldValue("")
        }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)

    // Scroll to bottom on new message, streaming chunk, or when the virtual keyboard (IME) opens
    LaunchedEffect(isolatedMessages.size, streamingText, imeBottomPx > 0) {
        if (isolatedMessages.isNotEmpty() || streamingText != null) {
            val totalItems = isolatedMessages.size + (if (streamingText != null) 1 else 0)
            if (totalItems > 0) {
                listState.animateScrollToItem(totalItems - 1)
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("chat_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(accentColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = getIconForName(chat.iconName),
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = chat.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1
                                )
                                // API Key Status Pill
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (activeKeyType == ActiveKeyType.PRIMARY) MaterialTheme.colorScheme.surfaceVariant
                                            else MaterialTheme.colorScheme.tertiaryContainer
                                        )
                                        .clickable(onClick = onToggleActiveKey)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                        .testTag("api_key_status_pill")
                                ) {
                                    Text(
                                        text = activeKeyType.shortLabel,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (activeKeyType == ActiveKeyType.PRIMARY) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }

                                if (chat.isDefaultWakeChat) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = "Default Wake Target",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Wake: \"${chat.wakeWord}\"",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onToggleAutoSpeak,
                        modifier = Modifier.testTag("toggle_auto_speak_button")
                    ) {
                        Icon(
                            imageVector = if (isAutoSpeakEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = "Toggle Read Aloud",
                            tint = if (isAutoSpeakEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (isSpeaking) {
                        IconButton(
                            onClick = onStopSpeaking,
                            modifier = Modifier.testTag("stop_speaking_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop Speech",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    IconButton(
                        onClick = { showWebHistoryBar = !showWebHistoryBar },
                        modifier = Modifier.testTag("chat_web_history_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Public,
                            contentDescription = "Web Search & Link History",
                            tint = if (showWebHistoryBar) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(
                        onClick = onOpenImportDialog,
                        modifier = Modifier.testTag("chat_import_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Import Chat or Context",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(
                        onClick = onOpenControlSheet,
                        modifier = Modifier.testTag("chat_control_sheet_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Gemini Control & Settings",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(
                        onClick = onPowerExit,
                        modifier = Modifier.testTag("chat_power_exit_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = "Power Off / Exit App",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.testTag("chat_detail_menu_button")
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Options")
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Gemini Coupler & Piggyback") },
                                leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenCouplerDialog()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Memory Buffer & Chrome Bridge") },
                                leadingIcon = { Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenBufferBridgeDialog()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Edit Chat, Greeting & Wake Word") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onEditChat()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isSleepMode) "Wake Up Listener (\"Gemini, wake up\")"
                                        else "Sleep Listener (\"Gemini, sleep\")"
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isSleepMode) Icons.Default.Mic else Icons.Default.MicOff,
                                        contentDescription = null,
                                        tint = if (isSleepMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    if (isSleepMode) onExitSleepMode() else onEnterSleepMode()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear Chat History") },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onClearHistory()
                                }
                            )

                            androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                text = "Chat Theme",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            com.example.ui.theme.AppTheme.values().forEach { theme ->
                                val isSelected = chat.themeMode == theme
                                DropdownMenuItem(
                                    text = { Text(theme.displayName) },
                                    leadingIcon = {
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        } else {
                                            Spacer(modifier = Modifier.size(24.dp))
                                        }
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onUpdateChatTheme(theme)
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                AnimatedVisibility(
                    visible = screenshotBanner != null,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it }
                ) {
                    if (screenshotBanner != null) {
                        ScreenshotHeadsUpBanner(
                            imageInfo = screenshotBanner,
                            onDismiss = onDismissScreenshotBanner,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = stagedImageUri != null,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it }
                ) {
                    if (stagedImageUri != null) {
                        StagedImageTray(
                            stagedImageUri = stagedImageUri,
                            stagedImageInfo = stagedImageInfo,
                            onRemove = onRemoveStagedImage,
                            onDispatchToCoupler = { onDispatchScreenshotToCoupler(inputText.text) },
                            promptText = inputText.text,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }

                if (isInputFloating) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .imePadding()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BottomBarNetworkStatusIcon(
                                networkState = networkState,
                                onRefreshNetwork = onRefreshNetwork
                            )
                            FilledTonalButton(
                                onClick = {
                                    isInputFloating = false
                                    isFloatingCollapsedToDot = false
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(32.dp)
                                    .testTag("bottom_bar_redock_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VerticalAlignBottom,
                                    contentDescription = "Dock Input Box",
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Dock Input Box",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                    }
                } else {
                    ChatBottomInputBar(
                        inputText = inputText,
                        onInputChange = { inputText = it },
                        onSend = {
                            val messageToSend = inputText.text.trim().ifBlank { draftSpokenText.trim() }
                            if ((messageToSend.isNotBlank() || stagedAttachment != null || isVoiceRecording) && !isSending) {
                                isSending = true
                                inputText = TextFieldValue("")
                                onSendMessage(messageToSend)
                                isSending = false
                            }
                        },
                        isGenerating = isGenerating,
                        isSending = isSending,
                        customPrompts = customPrompts,
                        showPromptsBar = showPromptsBar,
                        onTogglePromptsBar = { showPromptsBar = !showPromptsBar },
                        onSelectPrompt = { prompt ->
                            val currentText = inputText.text
                            val newText = if (currentText.isBlank()) prompt.promptText else "$currentText\n${prompt.promptText}"
                            inputText = TextFieldValue(
                                text = newText,
                                selection = androidx.compose.ui.text.TextRange(newText.length)
                            )
                        },
                        onOpenCreatePrompt = { showCreatePromptDialog = true },
                        onDeletePrompt = onDeleteCustomPrompt,
                        onTakePhoto = onTakePhoto,
                        onBrowseFiles = onBrowseFiles,
                        stagedAttachment = stagedAttachment,
                        onClearStagedAttachment = onClearStagedAttachment,
                        isVoiceRecording = isVoiceRecording,
                        audioRmsDb = audioRmsDb,
                        onStartVoiceRecording = onStartVoiceRecording,
                        onSendVoiceRecording = {
                            val textToInclude = inputText.text.trim().ifBlank { draftSpokenText.trim() }
                            if (!isSending) {
                                isSending = true
                                inputText = TextFieldValue("")
                                onSendVoiceRecording(textToInclude)
                                isSending = false
                            }
                        },
                        onCancelVoiceRecording = onCancelVoiceRecording,
                        networkState = networkState,
                        onRefreshNetwork = onRefreshNetwork,
                        onUndockToFloating = {
                            isInputFloating = true
                            isFloatingCollapsedToDot = false
                        },
                        onTextInputFocusChanged = onTextInputFocusChanged,
                        hasBufferedSpeech = draftSpokenText.isNotBlank()
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
            ChatImportProgressBanner(
                progressState = importProgressState,
                onCancelImport = onCancelImport
            )

            AnimatedVisibility(visible = showWebHistoryBar) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    WebSearchAndHistoryBar(
                        historyEntries = webLinkHistory,
                        onSubmitSearchOrUrl = onSubmitWebSearchOrUrl,
                        onOpenHistoryEntry = onOpenWebHistoryEntry,
                        onDeleteHistoryEntry = onDeleteWebHistoryEntry,
                        onClearAllHistory = onClearAllWebHistory,
                        voiceCommandEntries = voiceCommandHistory,
                        onUseVoiceCommandTranscript = { voiceEntry ->
                            inputText = TextFieldValue(
                                text = voiceEntry.recognizedTranscript,
                                selection = androidx.compose.ui.text.TextRange(voiceEntry.recognizedTranscript.length)
                            )
                        },
                        onDeleteVoiceCommandEntry = onDeleteVoiceCommandEntry,
                        onClearAllVoiceCommands = onClearAllVoiceCommands,
                        onClearCorruptedHistory = onClearCorruptedHistory,
                        isHistoryExpandedExternal = showWebHistoryBar,
                        onSetHistoryExpandedExternal = { showWebHistoryBar = it },
                        onTextInputFocusChanged = onTextInputFocusChanged
                    )
                }
            }

            SleepModeVisualBanner(
                isSleepMode = isSleepMode,
                sleepReason = sleepModeReason,
                onWakeUpTap = onExitSleepMode
            )

            // Wake Word Triggered Banner
            AnimatedVisibility(
                visible = triggerBanner != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                if (triggerBanner != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Opened by wake word: \"${triggerBanner.matchedWord}\"",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            IconButton(
                                onClick = onDismissTriggerBanner,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }



            // Pinned Custom Chat Greeting Banner
            if (chat.greetingMessage.isNotBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("chat_greeting_banner"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = accentColor.copy(alpha = 0.12f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = accentColor.copy(alpha = 0.35f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(accentColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = getIconForName(chat.iconName),
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${chat.title} Greeting",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = accentColor
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = chat.greetingMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("chat_greeting_text")
                            )
                        }
                        IconButton(
                            onClick = { onSpeakText(chat.greetingMessage) },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("speak_chat_greeting_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Read greeting aloud",
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onEditChat,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("edit_chat_greeting_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit custom greeting",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            if (showCreatePromptDialog) {
                CreatePromptDialog(
                    onDismiss = { showCreatePromptDialog = false },
                    onSave = { pTitle, pText ->
                        onAddCustomPrompt(pTitle, pText)
                        showCreatePromptDialog = false
                        Toast.makeText(context, "Added shortcut \"$pTitle\"", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // Message List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = if (isInputFloating) 96.dp else 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (hasMoreOlderMessages) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            OutlinedButton(
                                onClick = onLoadOlderMessages,
                                enabled = !isLoadingOlder,
                                modifier = Modifier.testTag("load_older_messages_button")
                            ) {
                                if (isLoadingOlder) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Paging from Disk Cache...")
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Load Older History from Disk Cache")
                                }
                            }
                        }
                    }
                }

                itemsIndexed(
                    items = isolatedMessages,
                    key = { index, item -> "${item.id}_${item.chatId}_$index" }
                ) { _, message ->
                    ChatMessageBubble(
                        message = message,
                        onCopy = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Gemini Message", message.content)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        onSpeak = {
                            onSpeakText(message.content)
                        },
                        onOpenLink = { url, label ->
                            onOpenChatLink(url, label)
                        }
                    )
                }

                // Streaming in-progress message
                if (streamingText != null) {
                    item {
                        GeminiStreamingBubble(text = streamingText)
                    }
                }

                // Error message banner
                if (errorMessage != null) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Generation Error",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = errorMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }

            if (isInputFloating) {
                FloatingMovableChatInput(
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    onSend = {
                        val messageToSend = inputText.text.trim().ifBlank { draftSpokenText.trim() }
                        if ((messageToSend.isNotBlank() || stagedAttachment != null || isVoiceRecording) && !isSending) {
                            isSending = true
                            inputText = TextFieldValue("")
                            onSendMessage(messageToSend)
                            isSending = false
                        }
                    },
                    isGenerating = isGenerating,
                    isSending = isSending,
                    isCollapsedToDot = isFloatingCollapsedToDot,
                    onToggleCollapseToDot = { isFloatingCollapsedToDot = !isFloatingCollapsedToDot },
                    onDockToBottomBar = {
                        isInputFloating = false
                        isFloatingCollapsedToDot = false
                    },
                    offsetX = floatingOffsetX,
                    offsetY = floatingOffsetY,
                    onDragDelta = { dx, dy ->
                        floatingOffsetX = (floatingOffsetX + dx).coerceAtLeast(0f)
                        floatingOffsetY = (floatingOffsetY + dy).coerceAtLeast(0f)
                    },
                    accentColor = accentColor,
                    networkState = networkState,
                    onRefreshNetwork = onRefreshNetwork,
                    stagedAttachment = stagedAttachment,
                    onTakePhoto = onTakePhoto,
                    onBrowseFiles = onBrowseFiles,
                    onStartVoiceRecording = onStartVoiceRecording,
                    onTextInputFocusChanged = onTextInputFocusChanged
                )
            }
        }
    }
}

@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onOpenLink: (String, String?) -> Unit = { _, _ -> }
) {
    val isUser = message.sender == "user"
    val extractedLinks = remember(message.content) {
        WebLinkHistoryManager.extractLinksFromText(message.content)
    }
    val timeFormatted = remember(message.timestamp) {
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        sdf.format(Date(message.timestamp))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            val context = LocalContext.current
            val hasTranscribedText = message.hasTranscribedText
            val isAudioPlaceholder = message.isAudioPlaceholderAttachment || ChatMessage.isLegacyAudioPlaceholder(message.content)
            // Hide legacy audio attachment placeholders when actual transcribed text is available
            val hasVisibleAttachment = (!message.attachmentName.isNullOrBlank() || !message.attachmentUri.isNullOrBlank()) &&
                !(hasTranscribedText && message.isAudioPlaceholderAttachment) &&
                !message.isAudioPlaceholderAttachment
            val isImageAttachment = message.attachmentMimeType?.startsWith("image/") == true

            // User message bubble
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .testTag("user_message_bubble_${message.id}")
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        if (hasVisibleAttachment) {
                            if (isImageAttachment) {
                                val decodedBitmap = remember(message.attachmentUri) {
                                    try {
                                        message.attachmentUri?.let { uriStr ->
                                            val uri = Uri.parse(uriStr)
                                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                                BitmapFactory.decodeStream(stream)?.asImageBitmap()
                                            }
                                        }
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = if (hasTranscribedText) 8.dp else 4.dp)
                                        .testTag("message_attachment_image_preview")
                                ) {
                                    Column {
                                        if (decodedBitmap != null) {
                                            Image(
                                                bitmap = decodedBitmap,
                                                contentDescription = message.attachmentName ?: "Attached photo",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(150.dp)
                                                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(96.dp)
                                                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.1f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.CameraAlt,
                                                    contentDescription = "Photo preview",
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(32.dp)
                                                )
                                            }
                                        }
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CameraAlt,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = message.attachmentName ?: "Image",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = if (hasTranscribedText) 8.dp else 4.dp)
                                        .testTag("message_attachment_file_chip")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Description,
                                            contentDescription = "Attached document",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = message.attachmentName ?: "Document",
                                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            if (message.attachmentSizeBytes != null) {
                                                Text(
                                                    text = formatFileSize(message.attachmentSizeBytes),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (hasTranscribedText) {
                            Text(
                                text = message.content.trim(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.testTag("user_message_text_${message.id}")
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        } else if (!hasVisibleAttachment && !isAudioPlaceholder && message.content.isNotBlank()) {
                            Text(
                                text = message.content.trim(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.testTag("user_message_text_${message.id}")
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Text(
                            text = timeFormatted,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                }
            }
        } else {
            // Gemini message bubble
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(
                            androidx.compose.ui.graphics.Brush.linearGradient(
                                listOf(Color(0xFF38BDF8), Color(0xFFA855F7))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Gemini",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.widthIn(max = 300.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        // Simple Markdown/Code Block Rendering
                        val contentParts = message.content.split("```")
                        contentParts.forEachIndexed { index, part ->
                            if (index % 2 == 1) {
                                // Code Block
                                Surface(
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    Text(
                                        text = part,
                                        style = androidx.compose.ui.text.TextStyle(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            fontSize = 13.sp
                                        ),
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            } else {
                                // Regular Text with clickable web links
                                LinkifiedMessageText(
                                    text = part,
                                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    linkColor = MaterialTheme.colorScheme.primary,
                                    onOpenLink = onOpenLink
                                )
                            }
                        }

                        if (extractedLinks.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                extractedLinks.forEachIndexed { linkIdx, link ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { onOpenLink(link.url, link.label) }
                                            .testTag("chat_message_link_chip_${message.id}_$linkIdx")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Public,
                                                    contentDescription = "Web link",
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Column {
                                                    Text(
                                                        text = link.label,
                                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        maxLines = 1,
                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = link.url,
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                                                        maxLines = 1,
                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Icon(
                                                imageVector = Icons.Default.OpenInNew,
                                                contentDescription = "Open link in default browser",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        var copied by remember(message.id) { mutableStateOf(false) }
                        LaunchedEffect(copied) {
                            if (copied) {
                                delay(1800)
                                copied = false
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = timeFormatted,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                IconButton(
                                    onClick = onSpeak,
                                    modifier = Modifier
                                        .size(26.dp)
                                        .testTag("speak_message_button_${message.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                        contentDescription = "Read message aloud",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (copied) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                                    },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            copied = true
                                            onCopy()
                                        }
                                        .testTag("copy_message_button_${message.id}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                            contentDescription = "Copy model response to clipboard",
                                            tint = if (copied) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (copied) "Copied" else "Copy",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = if (copied) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
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

@Composable
fun LinkifiedMessageText(
    text: String,
    textColor: Color,
    linkColor: Color,
    onOpenLink: (String, String?) -> Unit
) {
    val links = remember(text) { WebLinkHistoryManager.extractLinksFromText(text) }
    if (links.isEmpty()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = textColor
        )
        return
    }

    val annotatedString = remember(text, links, textColor, linkColor) {
        buildAnnotatedString {
            append(text)
            links.forEach { link ->
                val safeStart = link.startIndex.coerceIn(0, text.length)
                val safeEnd = link.endIndex.coerceIn(safeStart, text.length)
                if (safeStart < safeEnd) {
                    addStyle(
                        style = SpanStyle(
                            color = linkColor,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = TextDecoration.Underline
                        ),
                        start = safeStart,
                        end = safeEnd
                    )
                    addStringAnnotation(
                        tag = "URL",
                        annotation = link.url,
                        start = safeStart,
                        end = safeEnd
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    ClickableText(
        text = annotatedString,
        style = MaterialTheme.typography.bodyLarge.copy(color = textColor),
        onClick = { offset ->
            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    val matchedLink = links.firstOrNull { it.url == annotation.item }
                    onOpenLink(annotation.item, matchedLink?.label)
                }
        }
    )
}

@Composable
fun GeminiStreamingBubble(text: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(Color(0xFF38BDF8), Color(0xFFA855F7))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "Gemini",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Card(
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (text.isNotBlank()) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Gemini is replying...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun ChatBottomInputBar(
    inputText: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    isGenerating: Boolean,
    isSending: Boolean = false,
    customPrompts: List<PromptTemplate> = emptyList(),
    showPromptsBar: Boolean = false,
    onTogglePromptsBar: () -> Unit = {},
    onSelectPrompt: (PromptTemplate) -> Unit = {},
    onOpenCreatePrompt: () -> Unit = {},
    onDeletePrompt: (String) -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onBrowseFiles: () -> Unit = {},
    stagedAttachment: com.example.ui.AttachmentPayload? = null,
    onClearStagedAttachment: () -> Unit = {},
    isVoiceRecording: Boolean = false,
    audioRmsDb: Float = 0f,
    onStartVoiceRecording: () -> Unit = {},
    onSendVoiceRecording: () -> Unit = {},
    onCancelVoiceRecording: () -> Unit = {},
    networkState: NetworkConnectionState = NetworkConnectionState.ONLINE_WIFI,
    onRefreshNetwork: () -> Unit = {},
    onUndockToFloating: () -> Unit = {},
    onTextInputFocusChanged: (Boolean) -> Unit = {},
    hasBufferedSpeech: Boolean = false
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Compact Bottom Bar Utility Strip: Network Status Icon + Floating Input Anchor Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomBarNetworkStatusIcon(
                    networkState = networkState,
                    onRefreshNetwork = onRefreshNetwork
                )
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onUndockToFloating() }
                        .testTag("toggle_floating_input_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.OpenWith,
                            contentDescription = "Float Input Box with Anchor Dot",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Float Input",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            // Compact Staged Attachment Thumbnail / Chip above text field
            AnimatedVisibility(
                visible = stagedAttachment != null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
            ) {
                if (stagedAttachment != null) {
                    val isImage = stagedAttachment.mimeType.startsWith("image/")
                    val thumbnailBitmap = remember(stagedAttachment.bytes, isImage) {
                        if (isImage && stagedAttachment.bytes.isNotEmpty()) {
                            try {
                                BitmapFactory.decodeByteArray(stagedAttachment.bytes, 0, stagedAttachment.bytes.size)?.asImageBitmap()
                            } catch (_: Exception) {
                                null
                            }
                        } else null
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("staged_attachment_chip"),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isImage) {
                                    if (thumbnailBitmap != null) {
                                        Image(
                                            bitmap = thumbnailBitmap,
                                            contentDescription = stagedAttachment.fileName,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .testTag("staged_attachment_thumbnail")
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.primaryContainer)
                                                .testTag("staged_attachment_thumbnail"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CameraAlt,
                                                contentDescription = "Photo attachment",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Description,
                                        contentDescription = "Document attachment",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stagedAttachment.fileName,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = formatFileSize(stagedAttachment.sizeBytes),
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = onClearStagedAttachment,
                                modifier = Modifier
                                    .size(28.dp)
                                    .testTag("remove_attachment_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove attachment",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Quick Prompts Carousel Toggle (Now inside the Box below)

            AnimatedVisibility(visible = showPromptsBar) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "QUICK PROMPTS",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Tap to insert into chat",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // + Add New Prompt Button
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.testTag("add_custom_prompt_chip"),
                                onClick = onOpenCreatePrompt
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Add custom prompt",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "New Shortcut",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        items(customPrompts, key = { it.id }) { prompt ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                modifier = Modifier.testTag("prompt_chip_${prompt.id}"),
                                onClick = { onSelectPrompt(prompt) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = when (prompt.iconName) {
                                            "Code" -> Icons.Default.Code
                                            "Lightbulb" -> Icons.Default.Lightbulb
                                            else -> Icons.Default.AutoAwesome
                                        },
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = prompt.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (prompt.isCustom) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        IconButton(
                                            onClick = { onDeletePrompt(prompt.id) },
                                            modifier = Modifier.size(16.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Delete shortcut",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Combined Input & Voice Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                if (isVoiceRecording) {
                    // COMPACT RECORDING ROW
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f))
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onCancelVoiceRecording,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .testTag("cancel_voice_button")
                        ) {
                            Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                        }

                        // Compact Waveform
                        VoiceWaveformVisualizer(
                            isListening = true,
                            audioRmsDb = audioRmsDb,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            height = 28.dp,
                            barCount = 14
                        )

                        IconButton(
                            onClick = onSendVoiceRecording,
                            enabled = !isSending,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .testTag("send_voice_button")
                        ) {
                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                        }
                    }
                } else {
                    // STANDARD INPUT ROW
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onTogglePromptsBar,
                            enabled = !isSending,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (showPromptsBar) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .size(42.dp)
                                .testTag("toggle_custom_prompts_button")
                        ) {
                            Icon(Icons.Default.Tune, null, tint = if (showPromptsBar) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Box {
                            var showAttachmentMenu by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = { showAttachmentMenu = true },
                                enabled = !isSending,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .size(42.dp)
                                    .testTag("attachment_button")
                            ) {
                                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                            }
                            DropdownMenu(expanded = showAttachmentMenu, onDismissRequest = { showAttachmentMenu = false }) {
                                DropdownMenuItem(text = { Text("Take Photo") }, leadingIcon = { Icon(Icons.Default.CameraAlt, null) }, onClick = { showAttachmentMenu = false; onTakePhoto() })
                                DropdownMenuItem(text = { Text("Browse Files") }, leadingIcon = { Icon(Icons.Default.Folder, null) }, onClick = { showAttachmentMenu = false; onBrowseFiles() })
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        OutlinedTextField(
                            value = inputText,
                            onValueChange = onInputChange,
                            enabled = !isSending,
                            placeholder = { Text("Ask Gemini...") },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { onTextInputFocusChanged(it.isFocused) }
                                .testTag("chat_input_textfield"),
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Send,
                                autoCorrectEnabled = false
                            ),
                            keyboardActions = KeyboardActions(onSend = { onSend() })
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        IconButton(
                            onClick = onStartVoiceRecording,
                            enabled = !isGenerating && !isSending,
                            modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).size(42.dp).testTag("start_voice_button")
                        ) {
                            Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.primary)
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        val canSend = (inputText.text.isNotBlank() || hasBufferedSpeech || stagedAttachment != null) && !isGenerating && !isSending
                        IconButton(
                            onClick = onSend,
                            enabled = canSend,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .size(42.dp)
                                .testTag("send_message_button")
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, null, tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HandsFreeListeningPill(
    isActive: Boolean,
    state: HandsFreeVoiceState,
    dictateTrigger: String,
    sendTrigger: String,
    heartbeatTrigger: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "handsFreePulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val (bgColor, borderColor, icon, statusTitle, statusSubtext) = when {
        !isActive -> {
            val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            val border = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            listOf(bg, border, Icons.Default.MicOff, "Hands-Free Standby", "Tap mic to activate voice triggers")
        }
        state == HandsFreeVoiceState.HEARTBEAT_PING -> {
            val bg = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)
            val border = MaterialTheme.colorScheme.secondary
            listOf(bg, border, Icons.Default.Bolt, "Heartbeat Ping", "Ping intercepted & confirmation returned")
        }
        state == HandsFreeVoiceState.TRANSCRIBING -> {
            val bg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
            val border = MaterialTheme.colorScheme.primary
            listOf(bg, border, Icons.Default.Mic, "Transcribing Voice", "Say \"$sendTrigger\" to dispatch message")
        }
        else -> { // LISTENING
            val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            val border = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            listOf(
                bg,
                border,
                Icons.Default.Hearing,
                "Listening",
                "Triggers: \"$dictateTrigger\" to type • \"$sendTrigger\" to send • \"$heartbeatTrigger\" to ping"
            )
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("hands_free_listening_pill"),
        shape = RoundedCornerShape(12.dp),
        color = bgColor as Color,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor as Color)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
                            else MaterialTheme.colorScheme.outline
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = statusTitle as String,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = if (isActive) "ACTIVE" else "OFF",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
                                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    Text(
                        text = statusSubtext as String,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            IconButton(
                onClick = onToggle,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .testTag("toggle_hands_free_button")
            ) {
                Icon(
                    imageVector = icon as androidx.compose.ui.graphics.vector.ImageVector,
                    contentDescription = "Toggle Hands-Free Mode",
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
        else -> "$bytes B"
    }
}
