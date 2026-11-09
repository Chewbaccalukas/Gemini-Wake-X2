package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ChatSession
import com.example.ui.MainViewModel
import com.example.ui.WakeWordViewModel
import com.example.ui.components.CreateEditChatDialog
import com.example.ui.components.GeminiCouplerDialog
import com.example.ui.components.ImportGeminiChatDialog
import com.example.ui.components.MemoryBufferBridgeDialog
import com.example.ui.components.WakeWordSettingsDialog
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.GoogleAuthScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.WakeWordRecordingScreen
import com.example.ui.auth.GoogleAuthManager
import com.example.ui.theme.AppTheme
import com.example.ui.theme.GeminiWakeTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.example.importer.FileProcessingService
import com.example.ui.components.AudioPermissionRationaleDialog
import com.example.util.WebLinkHistoryManager
import com.example.util.configureAppCheck
import com.example.voice.WakeWordVoiceService

sealed class Screen {
    object Home : Screen()
    object History : Screen()
    object WakeWordStudio : Screen()
    data class Chat(val chatId: Long) : Screen()
}

sealed interface PendingAudioAction {
    object ToggleListening : PendingAudioAction
    object StartVoiceRecording : PendingAudioAction
    data class RecordWakeSnippet(val chatId: Long, val slotIndex: Int) : PendingAudioAction
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val wakeWordViewModel: WakeWordViewModel by viewModels {
        WakeWordViewModel.provideFactory(viewModel.wakeWordRepository)
    }
    private val incomingSharedText = mutableStateOf<String?>(null)
    private val incomingExternalWebUrl = mutableStateOf<String?>(null)
    private var currentScreen by mutableStateOf<Screen>(Screen.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mandatory App Check configuration with launch intent before any UI or Firebase operations
        configureAppCheck(this, intent)

        handleIncomingIntent(intent)

        enableEdgeToEdge()

        setContent {
            val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
            val currentChat by viewModel.currentChat.collectAsStateWithLifecycle()

            val effectiveTheme = if (currentChat != null && currentChat!!.themeMode != AppTheme.SYSTEM) {
                currentChat!!.themeMode
            } else {
                appTheme
            }

            GeminiWakeTheme(appTheme = effectiveTheme) {
                val authFlow = remember { GoogleAuthManager.observeAuthState() }
                val currentUser by authFlow.collectAsStateWithLifecycle(
                    initialValue = try {
                        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                    } catch (_: Exception) {
                        null
                    }
                )
                LaunchedEffect(currentUser) {
                    viewModel.onAuthStateChanged(currentUser)
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    if (currentUser == null) {
                        GoogleAuthScreen(
                            onSignedIn = {
                                val signedInUser = try {
                                    com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                                } catch (_: Exception) {
                                    null
                                }
                                viewModel.onAuthStateChanged(signedInUser)
                            }
                        )
                    } else {
                        MainApp(
                            viewModel = viewModel,
                            wakeWordViewModel = wakeWordViewModel,
                            currentScreen = currentScreen,
                            onNavigate = { currentScreen = it },
                            incomingSharedText = incomingSharedText.value,
                            incomingExternalWebUrl = incomingExternalWebUrl.value,
                            onClearIncomingSharedText = { incomingSharedText.value = null },
                            onClearIncomingExternalWebUrl = { incomingExternalWebUrl.value = null }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppBackgrounded()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: android.content.Intent?) {
        val targetChatId = intent?.getLongExtra(com.example.heartbeat.HeartbeatAlarmReceiver.EXTRA_TARGET_CHAT_ID, -1L) ?: -1L
        val wakeTargetChatId = intent?.getLongExtra(WakeWordVoiceService.EXTRA_WAKE_TARGET_CHAT_ID, -1L) ?: -1L
        val resolvedTargetChatId = when {
            wakeTargetChatId != -1L -> wakeTargetChatId
            targetChatId != -1L -> targetChatId
            else -> -1L
        }
        if (resolvedTargetChatId != -1L) {
            viewModel.selectChat(resolvedTargetChatId)
            currentScreen = Screen.Chat(resolvedTargetChatId)
        }

        if (intent?.action == android.content.Intent.ACTION_SEND) {
            if (intent.type?.startsWith("text/") == true) {
                val sharedUri = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
                if (sharedUri != null) {
                    handlePickedFileUri(
                        uri = sharedUri,
                        targetChatId = viewModel.currentChatId.value,
                        action = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
                    )
                } else {
                    val sharedText = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
                    if (!sharedText.isNullOrBlank()) {
                        val trimmed = sharedText.trim()
                        if (WebLinkHistoryManager.isDirectUrlOrDomain(trimmed)) {
                            val normalizedUrl = WebLinkHistoryManager.normalizeDirectUrl(trimmed)
                            viewModel.recordIncomingExternalLink(normalizedUrl)
                            incomingExternalWebUrl.value = normalizedUrl
                        } else {
                            incomingSharedText.value = sharedText
                        }
                    }
                }
            } else if (intent.type?.startsWith("image/") == true) {
                val imageUri = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
                if (imageUri != null) {
                    viewModel.stageImage(imageUri)
                    Toast.makeText(this, "Incoming screenshot staged for Coupler!", Toast.LENGTH_SHORT).show()
                }
            } else {
                val streamUri = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
                if (streamUri != null) {
                    handlePickedFileUri(
                        uri = streamUri,
                        targetChatId = viewModel.currentChatId.value,
                        action = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
                    )
                }
            }
        } else if (intent?.action == android.content.Intent.ACTION_VIEW && intent.data != null) {
            val dataUri = intent.data!!
            val scheme = dataUri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") {
                val urlString = dataUri.toString()
                viewModel.recordIncomingExternalLink(urlString)
                incomingExternalWebUrl.value = urlString
            } else {
                handlePickedFileUri(
                    uri = dataUri,
                    targetChatId = viewModel.currentChatId.value,
                    action = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
                )
            }
        }
    }

    /**
     * Builds an ACTION_OPEN_DOCUMENT file picker Intent filtered specifically for
     * .md, .txt, .pdf, .doc, .html, and .json files.
     */
    fun createSupportedFilePickerIntent(): Intent = Companion.createSupportedFilePickerIntent()

    /**
     * File picker intent result handler that validates the selected URI against
     * supported formats (.md, .txt, .pdf, .doc, .html, .json), persists read URI permission,
     * and dispatches the URI to the background FileProcessingService.
     */
    fun handlePickedFileUri(
        uri: Uri?,
        targetChatId: Long? = viewModel.currentChatId.value,
        action: String = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
    ): Boolean = Companion.handlePickedFileUri(this, uri, targetChatId, action)

    companion object {
        /**
         * Builds an ACTION_OPEN_DOCUMENT file picker Intent filtered specifically for
         * .md, .txt, .pdf, .doc, .html, and .json files.
         */
        fun createSupportedFilePickerIntent(): Intent {
            return FileProcessingService.createFilePickerIntent()
        }

        /**
         * Validates the selected file URI against .md, .txt, .pdf, .doc, .html, and .json formats,
         * persists read URI permission, and passes the URI to the background FileProcessingService.
         */
        fun handlePickedFileUri(
            context: Context,
            uri: Uri?,
            targetChatId: Long? = null,
            action: String = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
        ): Boolean {
            if (uri == null) return false

            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val fileName = FileProcessingService.resolveFileName(context, uri)
            val mimeType = FileProcessingService.resolveMimeType(context, uri, fileName)

            if (!FileProcessingService.isSupportedFile(fileName, mimeType)) {
                Toast.makeText(
                    context,
                    "Unsupported file type ($fileName). Please select a .md, .txt, .pdf, .doc, .html, or .json file.",
                    Toast.LENGTH_SHORT
                ).show()
                return false
            }

            FileProcessingService.startServiceForUri(
                context = context,
                uri = uri,
                targetChatId = targetChatId,
                action = action
            )
            return true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(
    viewModel: MainViewModel,
    wakeWordViewModel: WakeWordViewModel = remember(viewModel) {
        WakeWordViewModel(viewModel.wakeWordRepository)
    },
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    incomingSharedText: String? = null,
    incomingExternalWebUrl: String? = null,
    onClearIncomingSharedText: () -> Unit = {},
    onClearIncomingExternalWebUrl: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val chats by viewModel.allChats.collectAsStateWithLifecycle()
    val currentChatId by viewModel.currentChatId.collectAsStateWithLifecycle()
    val currentChat by viewModel.currentChat.collectAsStateWithLifecycle()

    // Persistence & Custom Prompts
    val defaultPersistenceEnabled by viewModel.defaultPersistenceEnabled.collectAsStateWithLifecycle()
    val preserveCacheAcrossSessions by viewModel.preserveCacheAcrossSessions.collectAsStateWithLifecycle()
    val heartbeatPingOnlyMode by viewModel.heartbeatPingOnlyMode.collectAsStateWithLifecycle()
    val customPrompts by viewModel.customPrompts.collectAsStateWithLifecycle()
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()

    // Hands-Free Voice Dictation & Triggers
    val dictateTrigger by viewModel.dictateTrigger.collectAsStateWithLifecycle()
    val sendTrigger by viewModel.sendTrigger.collectAsStateWithLifecycle()
    val heartbeatTrigger by viewModel.heartbeatTrigger.collectAsStateWithLifecycle()
    val isHandsFreeActive by viewModel.isHandsFreeActive.collectAsStateWithLifecycle()
    val handsFreeVoiceState by viewModel.handsFreeVoiceState.collectAsStateWithLifecycle()
    val currentDraftText by viewModel.currentDraftText.collectAsStateWithLifecycle()
    val wakeRetriggerEventId by viewModel.wakeRetriggerEventId.collectAsStateWithLifecycle()

    // Active stopwatch/timer tracking state for listed chats
    val activeTimerChatIds by viewModel.activeTimerChatIds.collectAsStateWithLifecycle()
    val liveTrackedSeconds by viewModel.liveTrackedSeconds.collectAsStateWithLifecycle()
    val openChatTimerSeconds by viewModel.openChatTimerSeconds.collectAsStateWithLifecycle()
    val isOpenChatTimerRunning by viewModel.isOpenChatTimerRunning.collectAsStateWithLifecycle()
    val showUsageLimitsComparison by viewModel.showUsageLimitsComparison.collectAsStateWithLifecycle()

    // Buffered hot memory messages to prevent freezing
    val hotMessages by viewModel.hotMessages.collectAsStateWithLifecycle()
    val allMessages by viewModel.allMessages.collectAsStateWithLifecycle()
    val bufferMetrics by viewModel.bufferMetrics.collectAsStateWithLifecycle()
    val relayServerState by viewModel.relayServerState.collectAsStateWithLifecycle()
    val hasMoreOlderMessages by viewModel.hasMoreOlderMessages.collectAsStateWithLifecycle()
    val isLoadingOlder by viewModel.isLoadingOlder.collectAsStateWithLifecycle()

    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val triggerBanner by viewModel.triggerBanner.collectAsStateWithLifecycle()
    val isAutoSpeakEnabled by viewModel.isAutoSpeakEnabled.collectAsStateWithLifecycle()
    val wakeWordSensitivity by viewModel.wakeWordSensitivity.collectAsStateWithLifecycle()
    val isSleepMode by viewModel.isSleepMode.collectAsStateWithLifecycle()
    val sleepModeReason by viewModel.sleepModeReason.collectAsStateWithLifecycle()
    val backgroundCueOnlyMode by viewModel.backgroundCueOnlyMode.collectAsStateWithLifecycle()
    val wakeReadyVisualCue by viewModel.wakeReadyVisualCue.collectAsStateWithLifecycle()
    val allWakeWordSamples by viewModel.allWakeWordSamples.collectAsStateWithLifecycle()
    val currentUserEmail by viewModel.currentUserEmail.collectAsStateWithLifecycle()
    val currentUserDisplayName by viewModel.currentUserDisplayName.collectAsStateWithLifecycle()
    val cloudSyncStatus by viewModel.cloudSyncStatus.collectAsStateWithLifecycle()
    val authScope = rememberCoroutineScope()

    var showControlSheet by remember { mutableStateOf(false) }
    var showPowerConfirmDialog by remember { mutableStateOf(false) }

    val exportDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val content = viewModel.exportCurrentChatAsPlainText()
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(content.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Chat exported successfully!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val isListening by viewModel.wakeWordManager.isListening.collectAsStateWithLifecycle()
    val isChatTurnRecording by viewModel.isChatTurnRecording.collectAsStateWithLifecycle()
    val isUserVoiceTurn by viewModel.isUserVoiceTurn.collectAsStateWithLifecycle()
    val audioRmsDb by viewModel.wakeWordManager.audioRmsDb.collectAsStateWithLifecycle()
    val statusMessage by viewModel.wakeWordManager.statusMessage.collectAsStateWithLifecycle()
    val lastHeardText by viewModel.wakeWordManager.lastHeardText.collectAsStateWithLifecycle()
    val stagedAttachment by viewModel.stagedAttachment.collectAsStateWithLifecycle()
    val stagedImageUri by viewModel.stagedImageUri.collectAsStateWithLifecycle()
    val stagedImageInfo by viewModel.stagedImageInfo.collectAsStateWithLifecycle()
    val screenshotBanner by viewModel.screenshotBanner.collectAsStateWithLifecycle()
    val isOrionVoicePlaying by viewModel.isOrionVoicePlaying.collectAsStateWithLifecycle()
    val voiceEngineMode by viewModel.voiceEngineMode.collectAsStateWithLifecycle()
    val selectedVoice by viewModel.selectedVoice.collectAsStateWithLifecycle()
    val wakeWordLogs by viewModel.wakeWordLogs.collectAsStateWithLifecycle()
    val importProgressState by viewModel.importProgressState.collectAsStateWithLifecycle()
    val activeKeyType by viewModel.activeKeyType.collectAsStateWithLifecycle()
    val isAudioSpeaking = isOrionVoicePlaying
    val masterVolume by viewModel.masterVolume.collectAsStateWithLifecycle()
    val autoStopLimitSec by viewModel.autoStopLimitSec.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()
    val notifyActiveChatReplies by viewModel.notifyActiveChatReplies.collectAsStateWithLifecycle()
    val permissionsResetCount by viewModel.permissionsResetCount.collectAsStateWithLifecycle()
    val permissionRationaleEnabled by viewModel.permissionRationaleEnabled.collectAsStateWithLifecycle()
    val webLinkHistory by viewModel.webLinkHistory.collectAsStateWithLifecycle()
    val voiceCommandHistory by viewModel.voiceCommandHistory.collectAsStateWithLifecycle()
    val requiresAudioPermission by viewModel.requiresAudioPermission.collectAsStateWithLifecycle()

    var showCreateEditDialog by remember { mutableStateOf(false) }
    var chatToEdit by remember { mutableStateOf<ChatSession?>(null) }
    var showWakeSettingsDialog by remember { mutableStateOf(false) }
    var showBufferBridgeDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importInitialText by remember { mutableStateOf("") }
    var pendingArchiveUri by remember { mutableStateOf<Uri?>(null) }
    var showArchivePromptDialog by remember { mutableStateOf(false) }

    var showCouplerDialog by remember { mutableStateOf(false) }
    var couplerInitialPayload by remember { mutableStateOf("") }
    var couplerInitialTab by remember { mutableIntStateOf(0) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val activity = context as? MainActivity
            val started = activity?.handlePickedFileUri(
                uri = uri,
                targetChatId = currentChatId,
                action = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
            ) ?: run {
                FileProcessingService.startServiceForUri(
                    context = context,
                    uri = uri,
                    targetChatId = currentChatId,
                    action = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
                )
                true
            }
            if (started) {
                Toast.makeText(context, "Processing file in background service...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Handle incoming share intents (e.g. from standard Gemini app share button or web browser)
    LaunchedEffect(incomingSharedText) {
        if (!incomingSharedText.isNullOrBlank()) {
            couplerInitialPayload = incomingSharedText
            couplerInitialTab = 1 // Open Piggyback Load tab
            showCouplerDialog = true
            onClearIncomingSharedText()
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            // Save bitmap to file and stage it
            val file = java.io.File(context.cacheDir, "staged_camera_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            viewModel.stageFile(uri, context)
            com.example.coupler.GeminiCouplerBridge.stageImage(context, uri)
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val fileName = FileProcessingService.resolveFileName(context, uri)
            val mimeType = FileProcessingService.resolveMimeType(context, uri, fileName)

            if (FileProcessingService.isSupportedFile(fileName, mimeType)) {
                pendingArchiveUri = uri
                showArchivePromptDialog = true
            } else {
                Toast.makeText(
                    context,
                    "Unsupported file ($fileName). Allowed: .md, .txt, .pdf, .doc, .html, .json",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Audio Permission Management & Pending Action Execution
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var pendingAudioAction by remember { mutableStateOf<PendingAudioAction?>(null) }
    var showPermissionRationaleDialog by remember { mutableStateOf(false) }

    fun executePendingAction(action: PendingAudioAction) {
        when (action) {
            is PendingAudioAction.ToggleListening -> {
                if (isListening) {
                    viewModel.stopContinuousWakeWordListening()
                } else {
                    viewModel.startContinuousWakeWordListening()
                    Toast.makeText(context, "Microphone enabled. Listening for wake word!", Toast.LENGTH_SHORT).show()
                }
            }
            is PendingAudioAction.StartVoiceRecording -> {
                viewModel.startVoiceRecording()
                Toast.makeText(context, "Recording voice message...", Toast.LENGTH_SHORT).show()
            }
            is PendingAudioAction.RecordWakeSnippet -> {
                viewModel.startRecordingWakeSnippet(action.chatId, action.slotIndex)
                Toast.makeText(context, "Recording snippet #${action.slotIndex + 1}...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        viewModel.onAudioPermissionResult(granted)
        val action = pendingAudioAction
        pendingAudioAction = null

        if (granted) {
            if (action != null) {
                executePendingAction(action)
            } else {
                Toast.makeText(context, "Microphone access enabled.", Toast.LENGTH_SHORT).show()
            }
        } else if (permissionRationaleEnabled) {
            showPermissionRationaleDialog = true
        }
    }

    val requiredPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        hasAudioPermission = audioGranted
        viewModel.onAudioPermissionResult(audioGranted)
        val grantedCount = results.values.count { it }
        val totalRequested = results.size
        if (totalRequested > 0) {
            Toast.makeText(
                context,
                "Permissions updated ($grantedCount/$totalRequested granted).",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun launchRequiredPermissionsRequest() {
        val permsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requiredPermissionsLauncher.launch(permsToRequest.toTypedArray())
    }

    fun handleResetPermissions() {
        viewModel.resetPermissionsState()
        showPermissionRationaleDialog = false
        pendingAudioAction = null
        hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Toast.makeText(
            context,
            "In-app permission flags reset & rationale prompts re-enabled",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun requestAudioPermissionAndExecute(action: PendingAudioAction) {
        hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasAudioPermission) {
            executePendingAction(action)
        } else {
            pendingAudioAction = action
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Lifecycle Observer: Re-check permission on resume (e.g. returning from System App Settings)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted && !hasAudioPermission) {
                    hasAudioPermission = true
                    val action = pendingAudioAction
                    if (action != null) {
                        pendingAudioAction = null
                        executePendingAction(action)
                    }
                } else {
                    hasAudioPermission = granted
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun handleToggleListening() {
        requestAudioPermissionAndExecute(PendingAudioAction.ToggleListening)
    }

    // Observe ViewModel microphone permission requests (e.g., when SpeechRecognizer or Hands-Free requires RECORD_AUDIO)
    LaunchedEffect(requiresAudioPermission) {
        if (requiresAudioPermission) {
            viewModel.consumeAudioPermissionRequest()
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Auto-start listening on launch only when Hands-Free mode is active, not in sleep mode, and no user voice turn is active
    LaunchedEffect(hasAudioPermission, isHandsFreeActive, isUserVoiceTurn, isSleepMode) {
        if (hasAudioPermission &&
            isHandsFreeActive &&
            !isSleepMode &&
            !isListening &&
            !isUserVoiceTurn &&
            !viewModel.wakeWordManager.isTransitioningAudioState
        ) {
            viewModel.startContinuousWakeWordListening()
        }
    }

    // Automatically open or refresh the corresponding chat screen when a wake word triggers a chat turn
    LaunchedEffect(wakeRetriggerEventId, triggerBanner, currentChatId) {
        val targetId = currentChatId
        if (triggerBanner != null && targetId != null && targetId > 0L) {
            showWakeSettingsDialog = false
            showControlSheet = false
            if (currentScreen != Screen.Chat(targetId)) {
                onNavigate(Screen.Chat(targetId))
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(heartbeatPingOnlyMode) {
        if (heartbeatPingOnlyMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    if (currentScreen is Screen.Chat && currentChat != null) {
        androidx.compose.runtime.key(currentChatId) {
            ChatDetailScreen(
                chat = currentChat!!,
                messages = hotMessages,
                streamingText = streamingText,
                isGenerating = isGenerating,
                errorMessage = errorMessage,
                triggerBanner = triggerBanner,
                isAutoSpeakEnabled = isAutoSpeakEnabled,
                isSpeaking = isAudioSpeaking,
                bufferMetrics = bufferMetrics,
                hasMoreOlderMessages = hasMoreOlderMessages,
                isLoadingOlder = isLoadingOlder,
                customPrompts = customPrompts,
                onBack = { onNavigate(Screen.Home) },
                onSendMessage = { text ->
                    viewModel.sendMessage(currentChat!!.id, text)
                },
                onToggleAutoSpeak = { viewModel.toggleAutoSpeak() },
                onSpeakText = { text -> viewModel.speakMessage(text) },
                onStopSpeaking = { viewModel.stopSpeaking() },
                onClearHistory = { viewModel.clearChatHistory(currentChat!!.id) },
                onEditChat = {
                    chatToEdit = currentChat
                    showCreateEditDialog = true
                },
                onLoadOlderMessages = { viewModel.loadOlderMessages() },
                onOpenBufferBridgeDialog = { showBufferBridgeDialog = true },
                onOpenCouplerDialog = {
                    couplerInitialPayload = ""
                    couplerInitialTab = 0
                    showCouplerDialog = true
                },
                onDismissTriggerBanner = { viewModel.clearTriggerBanner() },
                onToggleChatPersistence = { viewModel.toggleChatPersistence(currentChat!!.id) },
                openChatTimerSeconds = openChatTimerSeconds,
                isOpenChatTimerRunning = isOpenChatTimerRunning,
                showUsageLimitsComparison = showUsageLimitsComparison,
                onToggleOpenChatTimer = { viewModel.toggleOpenChatTimer() },
                onResetOpenChatTimer = { viewModel.resetOpenChatTimer() },
                onToggleUsageLimitsComparison = { enabled -> viewModel.toggleUsageLimitsComparison(enabled) },
                onAddCustomPrompt = { pTitle, pText -> viewModel.addCustomPrompt(pTitle, pText) },
                onDeleteCustomPrompt = { id -> viewModel.deleteCustomPrompt(id) },
                isHandsFreeActive = isHandsFreeActive,
                handsFreeVoiceState = handsFreeVoiceState,
                dictateTrigger = dictateTrigger,
                sendTrigger = sendTrigger,
                heartbeatTrigger = heartbeatTrigger,
                draftSpokenText = currentDraftText,
                onToggleHandsFree = { viewModel.toggleHandsFreeMode() },
                stagedImageUri = stagedImageUri,
                stagedImageInfo = stagedImageInfo,
                screenshotBanner = screenshotBanner,
                onDismissScreenshotBanner = { viewModel.dismissScreenshotBanner() },
                onRemoveStagedImage = { viewModel.clearStagedImage() },
                onDispatchScreenshotToCoupler = { prompt ->
                    currentChat?.id?.let { id ->
                        viewModel.dispatchScreenshotToCoupler(id, prompt)
                        viewModel.clearStagedImage()
                    }
                },
                importProgressState = importProgressState,
                onCancelImport = { viewModel.cancelChatImport() },
                activeKeyType = activeKeyType,
                onToggleActiveKey = { viewModel.toggleActiveKey() },
                onTakePhoto = { cameraLauncher.launch(null) },
                onBrowseFiles = { documentPickerLauncher.launch(FileProcessingService.SUPPORTED_MIME_TYPES) },
                stagedAttachment = stagedAttachment,
                onClearStagedAttachment = { viewModel.clearStagedAttachment() },
                onUpdateChatTheme = { theme ->
                    currentChat?.id?.let { id ->
                        viewModel.updateChatTheme(id, theme)
                    }
                },
                onOpenControlSheet = { showControlSheet = true },
                onOpenImportDialog = {
                    importInitialText = ""
                    showImportDialog = true
                },
                onPowerExit = { showPowerConfirmDialog = true },
                isVoiceRecording = isChatTurnRecording,
                audioRmsDb = audioRmsDb,
                onStartVoiceRecording = {
                    requestAudioPermissionAndExecute(PendingAudioAction.StartVoiceRecording)
                },
                onSendVoiceRecording = { promptText ->
                    viewModel.finishVoiceRecordingAndSend(promptText)
                },
                onCancelVoiceRecording = {
                    viewModel.cancelVoiceRecording()
                },
                networkState = networkState,
                onRefreshNetwork = { viewModel.refreshNetworkStatus() },
                webLinkHistory = webLinkHistory,
                onOpenChatLink = { url, label ->
                    val entry = viewModel.recordChatLinkOpened(url, label, currentChat?.title)
                    val targetUrl = entry?.url ?: url
                    WebLinkHistoryManager.openUrlInDefaultBrowser(context, targetUrl)
                },
                onSubmitWebSearchOrUrl = { queryOrUrl ->
                    val entry = viewModel.submitWebSearchOrUrl(queryOrUrl)
                    if (entry != null) {
                        WebLinkHistoryManager.openUrlInDefaultBrowser(context, entry.url)
                    }
                },
                onOpenWebHistoryEntry = { entry ->
                    viewModel.submitWebSearchOrUrl(entry.url)
                    WebLinkHistoryManager.openUrlInDefaultBrowser(context, entry.url)
                },
                onDeleteWebHistoryEntry = { id -> viewModel.deleteWebHistoryEntry(id) },
                onClearAllWebHistory = { viewModel.clearAllWebHistory() },
                voiceCommandHistory = voiceCommandHistory,
                onDeleteVoiceCommandEntry = { id -> viewModel.deleteVoiceCommandEntry(id) },
                onClearAllVoiceCommands = { viewModel.clearAllVoiceCommandHistory() },
                onClearCorruptedHistory = {
                    val count = viewModel.clearCorruptedHistory()
                    Toast.makeText(context, "History verified & repaired ($count entries cleaned).", Toast.LENGTH_SHORT).show()
                },
                onTextInputFocusChanged = { focused -> viewModel.onTextInputFocusChanged(focused) },
                wakeRetriggerEventId = wakeRetriggerEventId,
                isSleepMode = isSleepMode,
                sleepModeReason = sleepModeReason,
                onEnterSleepMode = { viewModel.enterSleepMode("Manual sleep mode enabled") },
                onExitSleepMode = { viewModel.exitSleepModeAndResumeListening() }
            )
        }
    } else if (currentScreen is Screen.WakeWordStudio) {
        val activeRecordingSlot by viewModel.activeRecordingSlot.collectAsStateWithLifecycle()
        val recordingDurationMs by viewModel.snippetRecordingDurationMs.collectAsStateWithLifecycle()
        val currentlyPlayingPath by viewModel.currentlyPlayingSnippetPath.collectAsStateWithLifecycle()
        val isSnippetPaused by viewModel.isSnippetPaused.collectAsStateWithLifecycle()
        val configuredWakeWords by wakeWordViewModel.chatWakeWords.collectAsStateWithLifecycle()

        WakeWordRecordingScreen(
            chats = chats,
            configuredWakeWords = configuredWakeWords,
            allSamples = allWakeWordSamples,
            isListening = isListening,
            isSleepMode = isSleepMode,
            sleepModeReason = sleepModeReason,
            backgroundCueOnlyMode = backgroundCueOnlyMode,
            wakeReadyVisualCue = wakeReadyVisualCue,
            audioRmsDb = audioRmsDb,
            statusMessage = statusMessage,
            wakeWordSensitivity = wakeWordSensitivity,
            activeRecordingSlot = activeRecordingSlot,
            recordingDurationMs = recordingDurationMs,
            currentlyPlayingPath = currentlyPlayingPath,
            isSnippetPaused = isSnippetPaused,
            getSamplesFlow = { chatId -> viewModel.getSamplesForChatFlow(chatId) },
            onBack = { onNavigate(Screen.Home) },
            onToggleListening = { handleToggleListening() },
            onEnterSleepMode = { viewModel.enterSleepMode("Manual sleep mode enabled") },
            onExitSleepMode = { viewModel.exitSleepModeAndResumeListening() },
            onToggleBackgroundCueOnlyMode = { viewModel.setBackgroundCueOnlyMode(it) },
            onDismissWakeReadyCue = { viewModel.dismissWakeReadyVisualCue() },
            onSetWakeWordSensitivity = { viewModel.setWakeWordSensitivity(it) },
            onSetWakeWordSensitivityLevel = { viewModel.setWakeWordSensitivityLevel(it) },
            onStartSnippetRecording = { chatId, slotIndex ->
                requestAudioPermissionAndExecute(PendingAudioAction.RecordWakeSnippet(chatId, slotIndex))
            },
            onStopSnippetRecording = { chatId, slotIndex ->
                viewModel.stopRecordingWakeSnippet(chatId, slotIndex)
            },
            onPlaySnippetPreview = { filePath -> viewModel.playSnippetPreview(filePath) },
            onPauseSnippetPreview = { viewModel.pauseSnippetPreview() },
            onResumeSnippetPreview = { viewModel.resumeSnippetPreview() },
            onDeleteSampleById = { chatId, sampleId, filePath ->
                viewModel.deleteWakeWordSampleById(chatId, sampleId, filePath)
            },
            onDeleteAllSamplesForChat = { chatId -> viewModel.deleteWakeWordSamples(chatId) },
            onSaveCustomWakeWordWithSamples = { phrase, chatId, sensitivity ->
                viewModel.saveCustomWakeWordWithSamples(phrase, chatId, sensitivity)
            },
            onSaveCustomWakeWordWithNewChat = { phrase, newChatTitle, sensitivity ->
                viewModel.addCustomWakeWordWithNewChat(
                    phrase = phrase,
                    newChatTitle = newChatTitle,
                    sensitivity = sensitivity
                )
            },
            onSetIndividualWakeWordSensitivity = { id, sensitivity ->
                viewModel.setIndividualWakeWordSensitivity(id, sensitivity)
            },
            onReassignWakeWordToChat = { wakeWordId, targetChatId ->
                viewModel.reassignWakeWordToChat(wakeWordId, targetChatId)
            },
            onToggleWakeWordEnabled = { id, enabled -> wakeWordViewModel.toggleWakeWordEnabled(id, enabled) },
            onDeleteWakeWord = { id -> wakeWordViewModel.removeWakeWordById(id) },
            onTriggerWakeWordAndOpenChat = { wakeWord ->
                val targetId = wakeWord.effectiveTargetChatId
                    ?: chats.firstOrNull { it.wakeWord.equals(wakeWord.phrase, ignoreCase = true) }?.id
                    ?: chats.firstOrNull()?.id
                    ?: 0L
                if (targetId > 0L) {
                    viewModel.selectChat(targetId)
                    viewModel.wakeWordManager.onKeywordHit(
                        keyword = wakeWord.phrase,
                        initialQuery = "",
                        targetChatIdOverride = targetId
                    )
                    onNavigate(Screen.Chat(targetId))
                }
            },
            onOpenChat = { chatId ->
                viewModel.selectChat(chatId)
                onNavigate(Screen.Chat(chatId))
            }
        )
    } else if (currentScreen is Screen.History) {
        HistoryScreen(
            messages = allMessages,
            chats = chats,
            onBack = { onNavigate(Screen.Home) },
            onDeleteMessage = { viewModel.deleteMessage(it) },
            onClearCorruptedHistory = {
                val count = viewModel.clearCorruptedHistory()
                Toast.makeText(context, "History verified & repaired ($count entries cleaned).", Toast.LENGTH_SHORT).show()
            },
            voiceCommands = voiceCommandHistory,
            onDeleteVoiceCommand = { id -> viewModel.deleteVoiceCommandEntry(id) },
            onNavigateToChat = { chatId ->
                viewModel.selectChat(chatId)
                onNavigate(Screen.Chat(chatId))
            }
        )
    } else {
        HomeScreen(
            chats = chats,
            isListening = isListening,
            audioRmsDb = audioRmsDb,
            statusMessage = statusMessage,
            lastHeardText = lastHeardText,
            defaultPersistenceEnabled = defaultPersistenceEnabled,
            preserveCacheAcrossSessions = preserveCacheAcrossSessions,
            heartbeatPingOnlyMode = heartbeatPingOnlyMode,
            isHandsFreeActive = isHandsFreeActive,
            dictateTrigger = dictateTrigger,
            sendTrigger = sendTrigger,
            heartbeatTrigger = heartbeatTrigger,
            activeTimerChatIds = activeTimerChatIds,
            liveTrackedSeconds = liveTrackedSeconds,
            voiceEngineMode = voiceEngineMode,
            isOrionVoicePlaying = isOrionVoicePlaying,
            onSetVoiceEngineMode = { viewModel.setVoiceEngineMode(it) },
            onPlayOrionTestSample = { viewModel.playOrionTestSample() },
            onToggleDefaultPersistence = { enabled -> viewModel.setDefaultPersistenceEnabled(enabled) },
            onTogglePreserveCacheAcrossSessions = { enabled -> viewModel.setPreserveCacheAcrossSessions(enabled) },
            onToggleHeartbeatPingOnlyMode = { enabled -> viewModel.setHeartbeatPingOnlyMode(enabled) },
            onToggleHandsFree = { enabled -> viewModel.toggleHandsFreeMode(enabled) },
            onToggleListening = { handleToggleListening() },
            onSelectChat = { id ->
                viewModel.selectChat(id)
                onNavigate(Screen.Chat(id))
            },
            onCreateChat = {
                chatToEdit = null
                showCreateEditDialog = true
            },
            onEditChat = { chat ->
                chatToEdit = chat
                showCreateEditDialog = true
            },
            onDeleteChat = { id -> viewModel.deleteChat(id) },
            onSetDefaultWakeChat = { id -> viewModel.setDefaultWakeChat(id) },
            onOpenWakeSettings = { showWakeSettingsDialog = true },
            onNavigateToWakeWordStudio = { onNavigate(Screen.WakeWordStudio) },
            isSleepMode = isSleepMode,
            sleepModeReason = sleepModeReason,
            wakeReadyVisualCue = wakeReadyVisualCue,
            onEnterSleepMode = { viewModel.enterSleepMode("Manual sleep mode enabled") },
            onExitSleepMode = { viewModel.exitSleepModeAndResumeListening() },
            onDismissWakeReadyCue = { viewModel.dismissWakeReadyVisualCue() },
            onOpenImportDialog = {
                importInitialText = ""
                showImportDialog = true
            },
            onOpenCouplerDialog = {
                couplerInitialPayload = ""
                couplerInitialTab = 1
                showCouplerDialog = true
            },
            onToggleChatTimer = { id -> viewModel.toggleChatTimer(id) },
            onResetChatTimer = { id -> viewModel.resetChatTimer(id) },
            importProgressState = importProgressState,
            onCancelImport = { viewModel.cancelChatImport() },
            appTheme = appTheme,
            onSetAppTheme = { viewModel.setAppTheme(it) },
            onNavigateToHistory = { onNavigate(Screen.History) },
            onOpenControlSheet = { showControlSheet = true },
            onPowerExit = { showPowerConfirmDialog = true },
            networkState = networkState,
            onRefreshNetwork = { viewModel.refreshNetworkStatus() },
            webLinkHistory = webLinkHistory,
            onSubmitWebSearchOrUrl = { queryOrUrl ->
                val entry = viewModel.submitWebSearchOrUrl(queryOrUrl)
                if (entry != null) {
                    WebLinkHistoryManager.openUrlInDefaultBrowser(context, entry.url)
                }
            },
            onOpenWebHistoryEntry = { entry ->
                viewModel.submitWebSearchOrUrl(entry.url)
                WebLinkHistoryManager.openUrlInDefaultBrowser(context, entry.url)
            },
            onDeleteWebHistoryEntry = { id -> viewModel.deleteWebHistoryEntry(id) },
            onClearAllWebHistory = { viewModel.clearAllWebHistory() },
            voiceCommandHistory = voiceCommandHistory,
            onUseVoiceCommandTranscript = { voiceEntry ->
                Toast.makeText(
                    context,
                    "Loaded transcript: \"${voiceEntry.recognizedTranscript}\"",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onDeleteVoiceCommandEntry = { id -> viewModel.deleteVoiceCommandEntry(id) },
            onClearAllVoiceCommands = { viewModel.clearAllVoiceCommandHistory() },
            onClearCorruptedHistory = {
                val count = viewModel.clearCorruptedHistory()
                Toast.makeText(context, "History verified & repaired ($count entries cleaned).", Toast.LENGTH_SHORT).show()
            },
            onTextInputFocusChanged = { focused -> viewModel.onTextInputFocusChanged(focused) },
            userEmail = currentUserEmail,
            userDisplayName = currentUserDisplayName,
            cloudSyncStatus = cloudSyncStatus,
            onSyncNow = {
                viewModel.triggerManualCloudSync()
                Toast.makeText(context, "Syncing with Cloud Firestore...", Toast.LENGTH_SHORT).show()
            },
            onSignOut = {
                authScope.launch {
                    GoogleAuthManager.signOut(context)
                    viewModel.onAuthStateChanged(null)
                    Toast.makeText(context, "Signed out of Google Account.", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (!incomingExternalWebUrl.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { onClearIncomingExternalWebUrl() },
            modifier = Modifier.testTag("incoming_web_link_dialog"),
            title = { Text("Web Link Opened") },
            text = {
                Text(
                    "Saved to your Web Link History:\n$incomingExternalWebUrl\n\nWould you like to analyze this link in Gemini Chat or open it in your default browser?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val urlToAnalyze = incomingExternalWebUrl
                        onClearIncomingExternalWebUrl()
                        val targetChat = currentChat
                            ?: chats.firstOrNull { it.isDefaultWakeChat }
                            ?: chats.firstOrNull()
                        if (targetChat != null) {
                            viewModel.selectChat(targetChat.id)
                            onNavigate(Screen.Chat(targetChat.id))
                            viewModel.sendMessage(
                                targetChat.id,
                                "Please analyze and summarize this web link: $urlToAnalyze"
                            )
                        } else {
                            Toast.makeText(context, "Create a chat first to analyze links.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("analyze_incoming_link_button")
                ) {
                    Text("Analyze in Gemini")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val urlToOpen = incomingExternalWebUrl
                        onClearIncomingExternalWebUrl()
                        WebLinkHistoryManager.openUrlInDefaultBrowser(context, urlToOpen)
                    },
                    modifier = Modifier.testTag("open_incoming_link_browser_button")
                ) {
                    Text("Open in Browser")
                }
            }
        )
    }

    if (showArchivePromptDialog && pendingArchiveUri != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                showArchivePromptDialog = false
                pendingArchiveUri = null
            },
            title = { Text("Chat Archive Detected") },
            text = { Text("Would you like to import this archive as a new chat session, or attach it to the current chat for analysis?") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val uri = pendingArchiveUri
                        showArchivePromptDialog = false
                        pendingArchiveUri = null
                        if (uri != null) {
                            val activity = context as? MainActivity
                            if (activity != null) {
                                activity.handlePickedFileUri(
                                    uri = uri,
                                    targetChatId = currentChatId,
                                    action = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
                                )
                            } else {
                                viewModel.importChatArchive(uri, currentChatId)
                            }
                            Toast.makeText(context, "Started background archive import...", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("import_as_new_chat_button")
                ) {
                    Text("Import as New Chat")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val uri = pendingArchiveUri
                        showArchivePromptDialog = false
                        pendingArchiveUri = null
                        if (uri != null) {
                            val activity = context as? MainActivity
                            if (activity != null) {
                                activity.handlePickedFileUri(
                                    uri = uri,
                                    targetChatId = currentChatId,
                                    action = FileProcessingService.ACTION_PROCESS_FILE_CONTEXT
                                )
                            } else {
                                viewModel.stageFile(uri, context)
                            }
                            Toast.makeText(context, "File attached to current chat via background service.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("attach_to_current_chat_button")
                ) {
                    Text("Attach to Current Chat")
                }
            }
        )
    }

    if (showCreateEditDialog) {
        val enrollmentRecording by viewModel.isWakeWordEnrollmentRecording.collectAsStateWithLifecycle()
        val activeRecordingSlot by viewModel.activeRecordingSlot.collectAsStateWithLifecycle()
        val recordingDurationMs by viewModel.snippetRecordingDurationMs.collectAsStateWithLifecycle()
        val currentlyPlayingPath by viewModel.currentlyPlayingSnippetPath.collectAsStateWithLifecycle()
        val playbackProgress by viewModel.snippetPlaybackProgress.collectAsStateWithLifecycle()
        val playbackPositionMs by viewModel.snippetPlaybackPositionMs.collectAsStateWithLifecycle()
        val playbackDurationMs by viewModel.snippetPlaybackDurationMs.collectAsStateWithLifecycle()
        val isSnippetPaused by viewModel.isSnippetPaused.collectAsStateWithLifecycle()

        CreateEditChatDialog(
            initialChat = chatToEdit,
            existingChats = chats,
            defaultPersistence = defaultPersistenceEnabled,
            isEnrollmentRecording = enrollmentRecording,
            activeRecordingSlot = activeRecordingSlot,
            recordingDurationMs = recordingDurationMs,
            currentlyPlayingPath = currentlyPlayingPath,
            playbackProgress = playbackProgress,
            playbackPositionMs = playbackPositionMs,
            playbackDurationMs = playbackDurationMs,
            isSnippetPaused = isSnippetPaused,
            autoStopLimitSec = autoStopLimitSec,
            onSetAutoStopLimit = { viewModel.setAutoStopLimit(it) },
            onStartSnippetRecording = { chatId, slotIndex ->
                requestAudioPermissionAndExecute(PendingAudioAction.RecordWakeSnippet(chatId, slotIndex))
            },
            onStopSnippetRecording = { chatId, slotIndex ->
                viewModel.stopRecordingWakeSnippet(chatId, slotIndex)
            },
            onPlaySnippetPreview = { filePath ->
                viewModel.playSnippetPreview(filePath)
            },
            onPauseSnippetPreview = {
                viewModel.pauseSnippetPreview()
            },
            onResumeSnippetPreview = {
                viewModel.resumeSnippetPreview()
            },
            onStopSnippetPreview = {
                viewModel.stopSnippetPreview()
            },
            onPlayAllSnippets = {
                chatToEdit?.let { viewModel.playAllWakeSnippets(it.id) }
            },
            onDeleteSampleById = { chatId, sampleId, filePath ->
                viewModel.deleteWakeWordSampleById(chatId, sampleId, filePath)
            },
            onStartEnrollment = { viewModel.startWakeWordEnrollment(it) },
            onStopEnrollment = { viewModel.stopWakeWordEnrollment() },
            onSaveSample = { chatId, index, pcm -> viewModel.saveWakeWordSample(chatId, index, pcm) },
            onDeleteSamples = { chatId -> viewModel.deleteWakeWordSamples(chatId) },
            getSamplesFlow = { chatId -> viewModel.getSamplesForChatFlow(chatId) },
            onDismiss = {
                viewModel.cancelRecordingWakeSnippet()
                viewModel.stopSnippetPreview()
                showCreateEditDialog = false
                chatToEdit = null
            },
            onSave = { savedChat ->
                viewModel.cancelRecordingWakeSnippet()
                viewModel.stopSnippetPreview()
                viewModel.saveChat(savedChat) { newId ->
                    showCreateEditDialog = false
                    chatToEdit = null
                }
            }
        )
    }

    if (showPermissionRationaleDialog) {
        AudioPermissionRationaleDialog(
            onDismiss = {
                showPermissionRationaleDialog = false
                pendingAudioAction = null
            }
        )
    }

    if (showWakeSettingsDialog) {
        val activeRecordingSlot by viewModel.activeRecordingSlot.collectAsStateWithLifecycle()
        val recordingDurationMs by viewModel.snippetRecordingDurationMs.collectAsStateWithLifecycle()
        val currentlyPlayingPath by viewModel.currentlyPlayingSnippetPath.collectAsStateWithLifecycle()
        val isSnippetPaused by viewModel.isSnippetPaused.collectAsStateWithLifecycle()
        val configuredWakeWords by wakeWordViewModel.chatWakeWords.collectAsStateWithLifecycle()

        WakeWordSettingsDialog(
            chats = chats,
            isListening = isListening,
            audioRmsDb = audioRmsDb,
            statusMessage = statusMessage,
            lastHeardText = lastHeardText,
            dictateTrigger = dictateTrigger,
            sendTrigger = sendTrigger,
            heartbeatTrigger = heartbeatTrigger,
            isHandsFreeActive = isHandsFreeActive,
            voiceEngineMode = voiceEngineMode,
            isOrionVoicePlaying = isOrionVoicePlaying,
            selectedVoice = selectedVoice,
            masterVolume = masterVolume,
            autoStopLimitSec = autoStopLimitSec,
            wakeWordSensitivity = wakeWordSensitivity,
            activeRecordingSlot = activeRecordingSlot,
            recordingDurationMs = recordingDurationMs,
            currentlyPlayingPath = currentlyPlayingPath,
            isSnippetPaused = isSnippetPaused,
            wakeWordLogs = wakeWordLogs,
            configuredWakeWords = configuredWakeWords,
            onAddCustomWakeWord = { chatId, phrase ->
                viewModel.addCustomWakeWord(chatId = chatId, phrase = phrase)
            },
            onAddCustomWakeWordWithSensitivity = { chatId, phrase, sensitivity ->
                viewModel.addCustomWakeWord(chatId = chatId, phrase = phrase, sensitivity = sensitivity)
            },
            onAddCustomWakeWordWithNewChat = { phrase, newChatTitle, sensitivity ->
                viewModel.addCustomWakeWordWithNewChat(
                    phrase = phrase,
                    newChatTitle = newChatTitle,
                    sensitivity = sensitivity
                )
            },
            onUpdateConfiguredWakeWord = { id, phrase -> wakeWordViewModel.updateWakeWordPhrase(id, phrase) },
            onToggleConfiguredWakeWord = { id, enabled -> wakeWordViewModel.toggleWakeWordEnabled(id, enabled) },
            onDeleteConfiguredWakeWord = { id -> wakeWordViewModel.removeWakeWordById(id) },
            onSetIndividualWakeWordSensitivity = { id, sensitivity ->
                viewModel.setIndividualWakeWordSensitivity(id, sensitivity)
            },
            onReassignWakeWordToChat = { wakeWordId, targetChatId ->
                viewModel.reassignWakeWordToChat(wakeWordId, targetChatId)
            },
            onReassignWakeWordToNewChat = { wakeWordId, newChatTitle ->
                viewModel.reassignWakeWordToNewChat(wakeWordId, newChatTitle)
            },
            onOpenChat = { chatId ->
                showWakeSettingsDialog = false
                viewModel.selectChat(chatId)
                onNavigate(Screen.Chat(chatId))
            },
            getSamplesFlow = { chatId -> viewModel.getSamplesForChatFlow(chatId) },
            onStartSnippetRecording = { chatId, slotIndex ->
                requestAudioPermissionAndExecute(PendingAudioAction.RecordWakeSnippet(chatId, slotIndex))
            },
            onStopSnippetRecording = { chatId, slotIndex ->
                viewModel.stopRecordingWakeSnippet(chatId, slotIndex)
            },
            onPlaySnippetPreview = { filePath -> viewModel.playSnippetPreview(filePath) },
            onPauseSnippetPreview = { viewModel.pauseSnippetPreview() },
            onResumeSnippetPreview = { viewModel.resumeSnippetPreview() },
            onStopSnippetPreview = { viewModel.stopSnippetPreview() },
            onDeleteSampleById = { chatId, sampleId, filePath ->
                viewModel.deleteWakeWordSampleById(chatId, sampleId, filePath)
            },
            onDeleteSamples = { chatId -> viewModel.deleteWakeWordSamples(chatId) },
            onSetWakeWordSensitivity = { viewModel.setWakeWordSensitivity(it) },
            onSetVoiceEngineMode = { viewModel.setVoiceEngineMode(it) },
            onSelectVoice = { viewModel.selectVoice(it) },
            onPlayOrionTestSample = { viewModel.testVoice(selectedVoice) },
            onSetMasterVolume = { viewModel.setMasterVolume(it) },
            onSetAutoStopLimit = { viewModel.setAutoStopLimit(it) },
            onUpdateDictateTrigger = { viewModel.setDictateTrigger(it) },
            onUpdateSendTrigger = { viewModel.setSendTrigger(it) },
            onUpdateHeartbeatTrigger = { viewModel.setHeartbeatTrigger(it) },
            onToggleHandsFree = { viewModel.toggleHandsFreeMode(it) },
            onResetTriggers = { viewModel.resetTriggersToDefaults() },
            onManualHeartbeatPing = { viewModel.triggerManualHeartbeatPing() },
            onToggleListening = { handleToggleListening() },
            notifyActiveChatReplies = notifyActiveChatReplies,
            permissionsResetCount = permissionsResetCount,
            onToggleNotifyActiveChatReplies = { enabled ->
                viewModel.setNotifyActiveChatReplies(enabled)
                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            },
            onResetPermissions = { handleResetPermissions() },
            onRequestRequiredPermissions = { launchRequiredPermissionsRequest() },
            onOpenWakeWordStudio = {
                showWakeSettingsDialog = false
                onNavigate(Screen.WakeWordStudio)
            },
            onDismiss = {
                viewModel.cancelRecordingWakeSnippet()
                viewModel.stopSnippetPreview()
                showWakeSettingsDialog = false
            }
        )
    }

    if (showImportDialog) {
        ImportGeminiChatDialog(
            initialInput = importInitialText,
            defaultPersistence = defaultPersistenceEnabled,
            onDismiss = {
                showImportDialog = false
                importInitialText = ""
            },
            onImportComplete = { session, messages ->
                viewModel.importParsedChat(session, messages) { newId ->
                    showImportDialog = false
                    importInitialText = ""
                    Toast.makeText(
                        context,
                        "Imported \"${session.title}\" with wake word \"${session.wakeWord}\"!",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            onChunkedImportStart = { session, messages, pacing, onProgress, onComplete ->
                viewModel.importParsedChatChunked(
                    session = session,
                    messages = messages,
                    pacing = pacing,
                    onProgress = onProgress,
                    onComplete = { newId ->
                        onComplete(newId)
                        showImportDialog = false
                        importInitialText = ""
                        Toast.makeText(
                            context,
                            "Imported \"${session.title}\" (${messages.size} messages) safely via chunked ingestion!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            },
            onBrowseImportFile = {
                documentPickerLauncher.launch(FileProcessingService.SUPPORTED_MIME_TYPES)
            }
        )
    }

    if (showBufferBridgeDialog && currentChat != null) {
        MemoryBufferBridgeDialog(
            chat = currentChat!!,
            metrics = bufferMetrics,
            relayState = relayServerState,
            onSetBufferSizeLimit = { limit -> viewModel.setBufferSizeLimit(limit) },
            onPurgeDiskCache = {
                viewModel.purgeDiskCache()
                Toast.makeText(context, "All disk caches purged & re-indexed", Toast.LENGTH_SHORT).show()
            },
            onClearIncomingDiskCache = {
                viewModel.clearIncomingDiskCache()
                Toast.makeText(context, "Cleared incoming (AI responses) disk buffer", Toast.LENGTH_SHORT).show()
            },
            onClearOutgoingDiskCache = {
                viewModel.clearOutgoingDiskCache()
                Toast.makeText(context, "Cleared outgoing (User queries) disk buffer", Toast.LENGTH_SHORT).show()
            },
            onOpenInChrome = {
                viewModel.openCurrentChatInChrome()
            },
            onCopyBridgePayload = {
                val payload = viewModel.getChromeBridgePayload()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Gemini Chrome Bridge", payload)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Sync package copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            onImportFromChrome = { payload ->
                viewModel.importFromChrome(payload) { success ->
                    if (success) {
                        Toast.makeText(context, "Synced successfully from Chrome!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Invalid Chrome sync package", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onOpenCouplerDialog = {
                couplerInitialPayload = ""
                couplerInitialTab = 0
                showCouplerDialog = true
            },
            onDismiss = { showBufferBridgeDialog = false }
        )
    }

    if (showCouplerDialog) {
        val rawMessages by viewModel.rawCurrentMessages.collectAsStateWithLifecycle()
        GeminiCouplerDialog(
            currentChat = currentChat,
            messages = rawMessages,
            initialPayload = couplerInitialPayload,
            initialTab = couplerInitialTab,
            onDismiss = {
                showCouplerDialog = false
                couplerInitialPayload = ""
            },
            onAppendToCurrentChat = { incoming ->
                currentChat?.let { chat ->
                    viewModel.appendPiggybackMessages(chat.id, incoming)
                }
            },
            onCreateCoupledChat = { title, wakeWord, messages ->
                viewModel.createCoupledChat(title, wakeWord, messages) { newId ->
                    viewModel.selectChat(newId)
                }
            },
            onRecordHandoff = {
                viewModel.recordCouplerHandoff()
            }
        )
    }

    if (showPowerConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showPowerConfirmDialog = false },
            title = { Text("Exit Application") },
            text = { Text("Are you sure you want to release microphone handles, stop voice listening, and exit Gemini Wake?") },
            confirmButton = {
                Button(
                    onClick = {
                        showPowerConfirmDialog = false
                        viewModel.cleanShutdown(context as? android.app.Activity)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Exit Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPowerConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showControlSheet) {
        ModalBottomSheet(
            onDismissRequest = { showControlSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            com.example.ui.components.GeminiControlSheet(
                chat = currentChat,
                bufferMetrics = bufferMetrics,
                appTheme = appTheme,
                onSetAppTheme = { viewModel.setAppTheme(it) },
                isHandsFreeActive = isHandsFreeActive,
                handsFreeVoiceState = handsFreeVoiceState,
                onToggleHandsFree = { viewModel.toggleHandsFreeMode(it) },
                wakeWordSensitivity = wakeWordSensitivity,
                onSetWakeWordSensitivity = { viewModel.setWakeWordSensitivity(it) },
                stopwatchSeconds = openChatTimerSeconds,
                isStopwatchRunning = isOpenChatTimerRunning,
                onToggleStopwatch = { viewModel.toggleOpenChatTimer() },
                onResetStopwatch = { viewModel.resetOpenChatTimer() },
                onOpenCoupler = {
                    showControlSheet = false
                    couplerInitialPayload = ""
                    couplerInitialTab = 0
                    showCouplerDialog = true
                },
                onOpenBufferBridge = {
                    showControlSheet = false
                    showBufferBridgeDialog = true
                },
                onEditChat = {
                    showControlSheet = false
                    chatToEdit = currentChat
                    showCreateEditDialog = true
                },
                onExportConversation = {
                    showControlSheet = false
                    val title = currentChat?.title ?: "chat"
                    val sanitized = title.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                    exportDocLauncher.launch("${sanitized}_export.txt")
                },
                notifyActiveChatReplies = notifyActiveChatReplies,
                permissionsResetCount = permissionsResetCount,
                onToggleNotifyActiveChatReplies = { enabled ->
                    viewModel.setNotifyActiveChatReplies(enabled)
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                },
                onResetPermissions = { handleResetPermissions() },
                onRequestRequiredPermissions = { launchRequiredPermissionsRequest() },
                onDismiss = { showControlSheet = false }
            )
        }
    }
}
