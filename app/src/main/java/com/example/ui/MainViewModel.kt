package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.ActiveKeyType
import com.example.ai.ApiKeyManager
import com.example.ai.GeminiService
import com.example.bridge.ChromeAppBridge
import com.example.cache.BufferMetrics
import com.example.cache.ChatMemoryBufferManager
import com.example.coupler.ChromeBridgeSocketManager
import com.example.coupler.GeminiCouplerBridge
import com.example.coupler.StagedImageInfo
import com.example.data.db.AppDatabase
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.TriggerType
import com.example.data.model.WakeWord
import com.example.data.model.WakeWordSample
import com.example.data.repository.ChatRepository
import com.example.data.repository.FirebaseSyncRepository
import com.example.data.repository.MessageRepository
import com.example.data.repository.WakeWordRepository
import com.example.importer.ChatImportWorker
import com.example.importer.FileProcessingService
import com.example.importer.ImportProgressState
import com.example.screenshot.ScreenshotWatcher
import com.example.voice.WakeWordManager
import com.example.voice.AudioRecorder
import com.example.voice.SnippetAudioPlayer
import com.example.voice.WakeWordStorageManager
import com.example.ui.theme.AppTheme
import com.example.util.NetworkConnectionState
import com.example.util.NetworkStatusTracker
import com.example.util.ReplyNotificationHelper
import com.example.util.VoiceCommandHistoryEntry
import com.example.util.VoiceCommandType
import com.example.util.WebLinkHistoryEntry
import com.example.util.WebLinkHistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.example.voice.HandsFreeVoiceState
import com.example.voice.WakeWordManagerState

data class TriggerBanner(
    val chatTitle: String,
    val matchedWord: String,
    val query: String?
)

data class WakeReadyCueState(
    val wakePhrase: String,
    val chatId: Long,
    val chatTitle: String,
    val followUpQuery: String? = null,
    val detectedInBackground: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class AttachmentPayload(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
    val sizeBytes: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AttachmentPayload
        if (uri != other.uri) return false
        if (fileName != other.fileName) return false
        if (mimeType != other.mimeType) return false
        if (!bytes.contentEquals(other.bytes)) return false
        return sizeBytes == other.sizeBytes
    }
    override fun hashCode(): Int {
        var result = uri.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + sizeBytes.hashCode()
        return result
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application, viewModelScope)
    val chatRepository = ChatRepository(database.chatDao(), database.messageDao())
    val repository = chatRepository
    val messageRepository = MessageRepository(database.messageDao(), database.chatDao())
    val wakeWordRepository = WakeWordRepository(database.wakeWordDao(), database.chatDao())
    val firebaseSyncRepository: FirebaseSyncRepository? = try {
        FirebaseSyncRepository(application)
    } catch (_: Throwable) {
        null
    }
    val wakeWordManager = WakeWordManager(application, database.wakeWordDao())
    val streamingVoicePlayer = com.example.voice.StreamingVoicePlayer(scope = viewModelScope)
    val chromeBridge = ChromeAppBridge(application)
    val bufferManager = ChatMemoryBufferManager(application, database.chatDao())
    val promptRepository = com.example.data.repository.PromptRepository(application)
    val chunkedImporter = com.example.importer.ChunkedChatImporter()
    private val geminiService = GeminiService()

    private val _currentUserUid = MutableStateFlow<String?>(null)
    val currentUserUid: StateFlow<String?> = _currentUserUid.asStateFlow()

    private val _currentUserEmail = MutableStateFlow<String?>(null)
    val currentUserEmail: StateFlow<String?> = _currentUserEmail.asStateFlow()

    private val _currentUserDisplayName = MutableStateFlow<String?>(null)
    val currentUserDisplayName: StateFlow<String?> = _currentUserDisplayName.asStateFlow()

    private val _cloudSyncStatus = MutableStateFlow("Sign in to enable Cloud Firestore sync")
    val cloudSyncStatus: StateFlow<String> = _cloudSyncStatus.asStateFlow()

    private var cloudSyncJob: Job? = null

    fun onAuthStateChanged(user: com.google.firebase.auth.FirebaseUser?) {
        val uid = user?.uid
        val previousUid = _currentUserUid.value
        _currentUserUid.value = uid
        _currentUserEmail.value = user?.email
        _currentUserDisplayName.value = user?.displayName

        if (uid.isNullOrBlank()) {
            cloudSyncJob?.cancel()
            cloudSyncJob = null
            _cloudSyncStatus.value = "Signed out (Local Room cache active)"
            return
        }

        if (previousUid != uid || cloudSyncJob?.isActive != true) {
            startHybridCloudSync(uid)
        }
    }

    fun triggerManualCloudSync() {
        val uid = _currentUserUid.value ?: return
        startHybridCloudSync(uid)
    }

    private fun startHybridCloudSync(userId: String) {
        val syncRepo = firebaseSyncRepository ?: return
        cloudSyncJob?.cancel()
        cloudSyncJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _cloudSyncStatus.value = "Syncing with Cloud Firestore..."
                // Preserve local Room cache and rebind any unowned or switched-account rows to the active Google account
                chatRepository.rebindLocalRecordsForUser(userId)
                messageRepository.rebindLocalRecordsForUser(userId)
                wakeWordRepository.rebindLocalRecordsForUser(userId)

                // 1. Reconcile any soft-deleted local tombstones (`isDeleted = true`) with Cloud Firestore first
                for (deletedChat in chatRepository.getPendingDeletedChats()) {
                    runCatching {
                        syncRepo.deleteChat(userId, deletedChat.id)
                        chatRepository.purgeDeletedChat(deletedChat.id)
                    }
                }
                for (deletedWake in wakeWordRepository.getPendingDeletedWakeWords()) {
                    runCatching {
                        syncRepo.deleteWakeWord(userId, deletedWake.id)
                        wakeWordRepository.purgeDeletedWakeWord(deletedWake.id)
                    }
                }
                for (deletedMsg in messageRepository.getPendingDeletedMessages()) {
                    runCatching {
                        syncRepo.deleteMessage(userId, deletedMsg.id)
                        messageRepository.purgeDeletedMessage(deletedMsg.id)
                    }
                }

                // 2. Push pending/dirty or initial local Room chats, wake words, and messages to Firestore
                val localChats = chatRepository.getPendingSyncChats().ifEmpty { chatRepository.getAllChatsSync() }
                for (chat in localChats) {
                    if (chat.id > 0L && chat.isPersistent) {
                        runCatching {
                            val docId = syncRepo.upsertChat(userId, chat)
                            chatRepository.markChatSynced(chat.id, docId, userId)
                        }
                    }
                }
                val localWakeWords = wakeWordRepository.getPendingSyncWakeWords().ifEmpty { wakeWordRepository.getAllWakeWordsSync() }
                for (ww in localWakeWords) {
                    if (ww.id > 0L) {
                        runCatching {
                            val docId = syncRepo.upsertWakeWord(userId, ww)
                            wakeWordRepository.markWakeWordSynced(ww.id, docId, userId)
                        }
                    }
                }
                val localMessages = messageRepository.getPendingSyncMessages().ifEmpty { allMessages.value }
                for (msg in localMessages) {
                    if (msg.id > 0L && msg.content.isNotBlank()) {
                        runCatching {
                            val docId = syncRepo.upsertMessage(userId, msg)
                            messageRepository.markMessageSynced(msg.id, docId, userId)
                        }
                    }
                }
                _cloudSyncStatus.value = "Synced with Cloud Firestore"

                // 2. Listen for real-time remote chat changes from Firestore -> Room (Last-Write-Wins)
                launch {
                    runCatching {
                        syncRepo.observeChats(userId).collect { remoteDocs ->
                            for (doc in remoteDocs) {
                                val remoteSession = doc.toChatSession()
                                if (remoteSession.id > 0L) {
                                    repository.upsertRemoteChat(remoteSession)
                                }
                            }
                            _cloudSyncStatus.value = "Synced • ${remoteDocs.size} chats in cloud"
                        }
                    }.onFailure {
                        _cloudSyncStatus.value = "Offline cache active (Cloud sync paused)"
                    }
                }

                // 3. Listen for real-time remote wake word changes from Firestore -> Room (Last-Write-Wins)
                launch {
                    runCatching {
                        syncRepo.observeWakeWords(userId).collect { remoteWakeDocs ->
                            for (doc in remoteWakeDocs) {
                                val remoteWake = doc.toWakeWord()
                                if (remoteWake.id > 0L && remoteWake.phrase.isNotBlank()) {
                                    wakeWordRepository.upsertRemoteWakeWord(remoteWake)
                                }
                            }
                        }
                    }
                }

                // 4. Listen for real-time remote message changes from Firestore -> Room (Last-Write-Wins)
                launch {
                    runCatching {
                        syncRepo.observeMessages(userId).collect { remoteMsgDocs ->
                            for (doc in remoteMsgDocs) {
                                val remoteMsg = doc.toChatMessage()
                                if (remoteMsg.id > 0L && remoteMsg.chatId > 0L && remoteMsg.content.isNotBlank()) {
                                    messageRepository.upsertRemoteMessage(remoteMsg)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                _cloudSyncStatus.value = "Offline cache active (Cloud sync paused)"
            }
        }
    }

    private fun syncChatToCloudIfSignedIn(chatId: Long) {
        val uid = _currentUserUid.value ?: return
        val syncRepo = firebaseSyncRepository ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val chat = repository.getChatByIdSync(chatId) ?: return@launch
            if (chat.isPersistent) {
                runCatching {
                    val docId = syncRepo.upsertChat(uid, chat)
                    repository.markChatSynced(chat.id, docId, uid)
                }
            }
            val wakeWords = wakeWordRepository.getChatWakeWordsForChatSync(chatId)
            for (ww in wakeWords) {
                runCatching {
                    val docId = syncRepo.upsertWakeWord(uid, ww)
                    wakeWordRepository.markWakeWordSynced(ww.id, docId, uid)
                }
            }
        }
    }

    private fun syncMessageToCloudIfSignedIn(message: ChatMessage) {
        val uid = _currentUserUid.value ?: return
        val syncRepo = firebaseSyncRepository ?: return
        if (message.id <= 0L || message.content.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val docId = syncRepo.upsertMessage(uid, message)
                messageRepository.markMessageSynced(message.id, docId, uid)
            }
        }
    }

    private fun syncWakeWordToCloudIfSignedIn(wakeWordId: Long) {
        val uid = _currentUserUid.value ?: return
        val syncRepo = firebaseSyncRepository ?: return
        if (wakeWordId <= 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            val wakeWord = wakeWordRepository.getAllWakeWordsSync().firstOrNull { it.id == wakeWordId } ?: return@launch
            runCatching {
                val docId = syncRepo.upsertWakeWord(uid, wakeWord)
                wakeWordRepository.markWakeWordSynced(wakeWord.id, docId, uid)
            }
        }
    }

    private val appPrefs = application.getSharedPreferences("gemini_wake_settings", android.content.Context.MODE_PRIVATE)

    private val initialVoiceMode = try {
        val saved = appPrefs.getString("voice_engine_mode", com.example.voice.VoiceEngineMode.GEMINI_ORION.name)
        com.example.voice.VoiceEngineMode.valueOf(saved ?: com.example.voice.VoiceEngineMode.GEMINI_ORION.name)
    } catch (_: Exception) {
        com.example.voice.VoiceEngineMode.GEMINI_ORION
    }
    private val _voiceEngineMode = MutableStateFlow(initialVoiceMode)
    val voiceEngineMode: StateFlow<com.example.voice.VoiceEngineMode> = _voiceEngineMode.asStateFlow()
    val isOrionVoicePlaying: StateFlow<Boolean> = streamingVoicePlayer.isPlaying

    private val initialSelectedVoice = appPrefs.getString("selected_voice", "Fenrir") ?: "Fenrir"
    private val _selectedVoice = MutableStateFlow(initialSelectedVoice)
    val selectedVoice: StateFlow<String> = _selectedVoice.asStateFlow()

    private val initialAppTheme = try {
        val saved = appPrefs.getString("app_theme", AppTheme.SYSTEM.name)
        AppTheme.valueOf(saved ?: AppTheme.SYSTEM.name)
    } catch (_: Exception) {
        AppTheme.SYSTEM
    }
    private val _appTheme = MutableStateFlow(initialAppTheme)
    val appTheme: StateFlow<AppTheme> = _appTheme.asStateFlow()

    fun setAppTheme(theme: AppTheme) {
        _appTheme.value = theme
        appPrefs.edit().putString("app_theme", theme.name).apply()
    }

    fun selectVoice(voice: String) {
        _selectedVoice.value = voice
        appPrefs.edit().putString("selected_voice", voice).apply()
    }

    fun testVoice(voice: String) {
        // StreamingVoicePlayer strictly plays Gemini model responses only; do not synthesize local status/test audio.
        streamingVoicePlayer.stop()
    }

    private val _defaultPersistenceEnabled = MutableStateFlow(appPrefs.getBoolean("default_persistence", true))
    val defaultPersistenceEnabled: StateFlow<Boolean> = _defaultPersistenceEnabled.asStateFlow()

    private val _preserveCacheAcrossSessions = MutableStateFlow(appPrefs.getBoolean("preserve_cache_across_sessions", true))
    val preserveCacheAcrossSessions: StateFlow<Boolean> = _preserveCacheAcrossSessions.asStateFlow()

    private val _heartbeatPingOnlyMode = MutableStateFlow(appPrefs.getBoolean("heartbeat_ping_only_mode", true))
    val heartbeatPingOnlyMode: StateFlow<Boolean> = _heartbeatPingOnlyMode.asStateFlow()

    // Hands-Free Voice Dictation & Custom Trigger Words
    private val _dictateTrigger = MutableStateFlow(
        appPrefs.getString("dictate_trigger", com.example.voice.AudioCommandSanitizer.DEFAULT_DICTATE_TRIGGER)
            ?: com.example.voice.AudioCommandSanitizer.DEFAULT_DICTATE_TRIGGER
    )
    val dictateTrigger: StateFlow<String> = _dictateTrigger.asStateFlow()

    private val _sendTrigger = MutableStateFlow(
        appPrefs.getString("send_trigger", com.example.voice.AudioCommandSanitizer.DEFAULT_SEND_TRIGGER)
            ?: com.example.voice.AudioCommandSanitizer.DEFAULT_SEND_TRIGGER
    )
    val sendTrigger: StateFlow<String> = _sendTrigger.asStateFlow()

    private val _heartbeatTrigger = MutableStateFlow(
        appPrefs.getString("heartbeat_trigger", com.example.voice.AudioCommandSanitizer.DEFAULT_HEARTBEAT_TRIGGER)
            ?: com.example.voice.AudioCommandSanitizer.DEFAULT_HEARTBEAT_TRIGGER
    )
    val heartbeatTrigger: StateFlow<String> = _heartbeatTrigger.asStateFlow()

    private val _isHandsFreeActive = MutableStateFlow(appPrefs.getBoolean("hands_free_active", false))
    val isHandsFreeActive: StateFlow<Boolean> = _isHandsFreeActive.asStateFlow()

    val handsFreeVoiceState: StateFlow<com.example.voice.HandsFreeVoiceState> = wakeWordManager.handsFreeState
    val wakeWordLogs: StateFlow<List<com.example.voice.WakeWordLogEntry>> = wakeWordManager.wakeWordLogs
    val isTextInputFocused: StateFlow<Boolean> = wakeWordManager.isTextInputFocused
    val hasExclusiveAudioFocus: StateFlow<Boolean> = wakeWordManager.hasAudioFocus
    val isUserVoiceTurn: StateFlow<Boolean> = wakeWordManager.isUserVoiceTurn

    val isChatTurnRecording: StateFlow<Boolean> = combine(
        wakeWordManager.isListening,
        wakeWordManager.engineState,
        wakeWordManager.handsFreeState,
        wakeWordManager.isUserVoiceTurn
    ) { listening, engineState, hfState, userVoiceTurn ->
        (listening && (engineState == WakeWordManagerState.ACTIVE_STREAMING || hfState == HandsFreeVoiceState.TRANSCRIBING)) ||
            (listening && userVoiceTurn)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    // Microphone Permission Verification & SpeechRecognizer Continuous Wake-Word Engine State
    private var permissionGrantedOverrideForTest: Boolean? = null

    private val _hasRecordAudioPermission = MutableStateFlow(
        androidx.core.content.ContextCompat.checkSelfPermission(
            application,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    )
    val hasRecordAudioPermission: StateFlow<Boolean> = _hasRecordAudioPermission.asStateFlow()

    private val _requiresAudioPermission = MutableStateFlow(false)
    val requiresAudioPermission: StateFlow<Boolean> = _requiresAudioPermission.asStateFlow()

    private var pendingWakeStartAfterPermission: Boolean = false
    private var speechRecognizer: android.speech.SpeechRecognizer? = null
    private var speechRecognizerRestartJob: Job? = null
    private var consecutiveSpeechRecognizerErrors: Int = 0
    private var lastPartialWakeMatchMs: Long = 0L

    private val _isSpeechRecognizerListening = MutableStateFlow(false)
    val isSpeechRecognizerListening: StateFlow<Boolean> = _isSpeechRecognizerListening.asStateFlow()

    private val _speechRecognizerErrorCount = MutableStateFlow(0)
    val speechRecognizerErrorCount: StateFlow<Int> = _speechRecognizerErrorCount.asStateFlow()

    private val _wakeRetriggerEventId = MutableStateFlow(0L)
    val wakeRetriggerEventId: StateFlow<Long> = _wakeRetriggerEventId.asStateFlow()

    private val _lastDispatchedVoicePrompt = MutableStateFlow<String?>(null)
    val lastDispatchedVoicePrompt: StateFlow<String?> = _lastDispatchedVoicePrompt.asStateFlow()

    internal fun setPermissionGrantedOverrideForTest(granted: Boolean?) {
        permissionGrantedOverrideForTest = granted
        if (granted != null) {
            _hasRecordAudioPermission.value = granted
        }
    }

    /**
     * Checks whether Manifest.permission.RECORD_AUDIO is currently granted.
     */
    fun checkRecordAudioPermission(): Boolean {
        val override = permissionGrantedOverrideForTest
        val granted = override ?: (
            androidx.core.content.ContextCompat.checkSelfPermission(
                getApplication(),
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
        _hasRecordAudioPermission.value = granted
        return granted
    }

    /**
     * Verifies RECORD_AUDIO permission before starting continuous wake-word listening or voice capture.
     * Emits requiresAudioPermission = true if permission must be requested from the UI.
     */
    fun ensureMicrophonePermission(startWakeListeningWhenGranted: Boolean = true): Boolean {
        return if (checkRecordAudioPermission()) {
            _requiresAudioPermission.value = false
            true
        } else {
            pendingWakeStartAfterPermission = startWakeListeningWhenGranted
            _requiresAudioPermission.value = true
            wakeWordManager.setStatusMessage("Microphone permission required for wake-word listening")
            false
        }
    }

    fun consumeAudioPermissionRequest() {
        _requiresAudioPermission.value = false
    }

    /**
     * Handles the runtime RECORD_AUDIO permission result from the Activity/Compose launcher.
     */
    fun onAudioPermissionResult(granted: Boolean) {
        if (permissionGrantedOverrideForTest != null) {
            permissionGrantedOverrideForTest = granted
        }
        _hasRecordAudioPermission.value = granted
        _requiresAudioPermission.value = false
        if (granted) {
            val shouldStart = pendingWakeStartAfterPermission || _isHandsFreeActive.value
            pendingWakeStartAfterPermission = false
            if (shouldStart) {
                startContinuousWakeWordListening()
            }
        } else {
            pendingWakeStartAfterPermission = false
            stopContinuousWakeWordListening()
            wakeWordManager.setStatusMessage("Microphone permission denied — wake-word listening disabled")
        }
    }

    /**
     * Builds the RecognizerIntent for continuous wake-word and hands-free speech recognition.
     * Uses explicit 3000ms/3000ms/5000ms timeout overrides and LANGUAGE_MODEL_FREE_FORM to prevent mid-sentence cutoff.
     */
    fun createWakeRecognizerIntent(): android.content.Intent {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        return WakeWordManager.configureRecognizerIntent(
            intent = intent,
            callingPackage = getApplication<Application>().packageName
        )
    }

    /**
     * RecognitionListener implementation for continuous wake-word listening inside MainViewModel.
     */
    val wakeRecognitionListener = object : android.speech.RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {
            consecutiveSpeechRecognizerErrors = 0
            _isSpeechRecognizerListening.value = true
            wakeWordManager.updateExternalListeningState(
                listening = true,
                status = "Listening for wake word (SpeechRecognizer)..."
            )
        }

        override fun onBeginningOfSpeech() {
            _isSpeechRecognizerListening.value = true
            wakeWordManager.setStatusMessage("Detecting speech...")
        }

        override fun onRmsChanged(rmsdB: Float) {
            val normalizedRms = rmsdB.coerceIn(0f, 10f)
            wakeWordManager.updateExternalRmsDb(normalizedRms)
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            if (buffer != null && buffer.isNotEmpty()) {
                wakeWordManager.appendPreRollPcm(buffer, buffer.size)
            }
        }

        override fun onEndOfSpeech() {
            _isSpeechRecognizerListening.value = false
        }

        override fun onError(error: Int) {
            _isSpeechRecognizerListening.value = false
            when (error) {
                android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    _hasRecordAudioPermission.value = false
                    _requiresAudioPermission.value = true
                    wakeWordManager.updateExternalListeningState(
                        listening = false,
                        status = "Microphone permission required for wake-word listening"
                    )
                }
                android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                android.speech.SpeechRecognizer.ERROR_NO_MATCH -> {
                    consecutiveSpeechRecognizerErrors = 0
                    if (isUserVoiceTurn.value) {
                        wakeWordManager.setUserVoiceTurn(false)
                        if (_isHandsFreeActive.value) {
                            wakeWordManager.transitionToPassiveListening()
                        }
                    }
                    if (_isHandsFreeActive.value && !isTextInputFocused.value) {
                        scheduleSpeechRecognizerRestart(delayMs = 180L)
                    }
                }
                else -> {
                    consecutiveSpeechRecognizerErrors++
                    _speechRecognizerErrorCount.update { it + 1 }
                    if (_isHandsFreeActive.value && !isTextInputFocused.value) {
                        if (consecutiveSpeechRecognizerErrors <= 4) {
                            val backoffMs = (250L * consecutiveSpeechRecognizerErrors).coerceAtMost(1200L)
                            scheduleSpeechRecognizerRestart(delayMs = backoffMs, allowDuringUserVoiceTurn = true)
                        } else {
                            // Hybrid Fallback: switch to hardware AudioRecord PCM wake listener
                            stopSpeechRecognizerInternal()
                            wakeWordManager.startContinuousListening(forceActiveTurn = isUserVoiceTurn.value)
                        }
                    }
                }
            }
        }

        override fun onResults(results: android.os.Bundle?) {
            _isSpeechRecognizerListening.value = false
            val matches = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
            val recognizedText = matches?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (recognizedText.isNotBlank()) {
                com.example.voice.WakeWordVoiceService.startServiceSafely(
                    context = getApplication(),
                    candidateTranscript = recognizedText
                )
            }
            val matchedWakeOrCommand = processSpeechRecognizerResults(matches, isFinal = true)
            if (!matchedWakeOrCommand && _isHandsFreeActive.value && !isTextInputFocused.value) {
                scheduleSpeechRecognizerRestart(delayMs = if (_isSleepMode.value) 350L else 150L, allowDuringUserVoiceTurn = true)
            }
        }

        override fun onPartialResults(partialResults: android.os.Bundle?) {
            val matches = partialResults?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
            processSpeechRecognizerResults(matches, isFinal = false)
        }

        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
    }

    /**
     * Evaluates partial or final SpeechRecognizer transcripts for wake-words, follow-up queries,
     * and hands-free commands (Dictate, Send, Heartbeat).
     * Returns true if a wake-word or terminal command was triggered.
     */
    fun processSpeechRecognizerResults(candidates: List<String>?, isFinal: Boolean): Boolean {
        val rawList = candidates?.filter { it.isNotBlank() }.orEmpty()
        if (rawList.isEmpty()) return false

        val primaryTranscript = rawList.first().trim()

        // 0a. Check Wake-Up Voice Command ("Gemini, wake up") to exit Sleep Mode
        if (com.example.voice.AudioCommandSanitizer.containsWakeUpCommand(primaryTranscript)) {
            if (isFinal) {
                exitSleepModeAndResumeListening(reason = "Voice command: \"$primaryTranscript\"")
                webLinkHistoryManager.recordVoiceCommand(
                    commandType = VoiceCommandType.WAKE_UP,
                    triggerPhrase = com.example.voice.AudioCommandSanitizer.DEFAULT_WAKE_UP_COMMAND,
                    recognizedTranscript = primaryTranscript,
                    chatTitle = currentChat.value?.title
                )
            }
            return isFinal
        }

        // 0b. Check Sleep Voice Command ("Gemini, sleep") to temporarily disable the wake word listener
        if (com.example.voice.AudioCommandSanitizer.containsSleepCommand(primaryTranscript)) {
            if (isFinal) {
                enterSleepMode(reason = "Voice command: \"$primaryTranscript\"")
                webLinkHistoryManager.recordVoiceCommand(
                    commandType = VoiceCommandType.SLEEP,
                    triggerPhrase = com.example.voice.AudioCommandSanitizer.DEFAULT_SLEEP_COMMAND,
                    recognizedTranscript = primaryTranscript,
                    chatTitle = currentChat.value?.title
                )
            }
            return isFinal
        }

        // If in sleep mode, ignore normal wake word and dictation triggers until woken up
        if (_isSleepMode.value) {
            return false
        }

        // 1. Check Heartbeat Trigger
        if (com.example.voice.AudioCommandSanitizer.containsHeartbeatTrigger(primaryTranscript, _heartbeatTrigger.value)) {
            if (isFinal) {
                wakeWordManager.onHandsFreeHeartbeat?.invoke()
                com.example.voice.AudioCommandSanitizer.playHeartbeatConfirmation(getApplication())
            }
            return isFinal
        }

        // 2. Check Send Trigger
        if (com.example.voice.AudioCommandSanitizer.containsSendTrigger(primaryTranscript, _sendTrigger.value)) {
            if (isFinal) {
                wakeWordManager.onHandsFreeSend?.invoke(primaryTranscript)
            }
            return isFinal
        }

        // 3. Check Dictate Trigger
        if (com.example.voice.AudioCommandSanitizer.containsDictateTrigger(primaryTranscript, _dictateTrigger.value)) {
            val extracted = com.example.voice.AudioCommandSanitizer.extractContentAfterDictate(
                primaryTranscript,
                _dictateTrigger.value
            )
            if (extracted.isNotBlank()) {
                _currentDraftText.value = extracted
                wakeWordManager.updateLastHeardText(extracted)
                if (isFinal) {
                    webLinkHistoryManager.recordVoiceCommand(
                        commandType = VoiceCommandType.DICTATE,
                        triggerPhrase = _dictateTrigger.value,
                        recognizedTranscript = extracted,
                        chatTitle = currentChat.value?.title
                    )
                    sendMessage(extracted, triggeredByWakeWord = true)
                    return true
                }
            }
            return false
        }

        // 4. Check Wake-Word Phrases across all recognition candidates
        for (candidate in rawList) {
            val wakeMatch = wakeWordManager.matchWakePhraseInTranscript(candidate)
            if (wakeMatch != null) {
                val now = System.currentTimeMillis()
                // If user is still speaking a follow-up query during partial results, wait for final result
                // so the entire follow-up question is captured in one breath.
                if (!isFinal && !wakeMatch.followUpQuery.isNullOrBlank()) {
                    wakeWordManager.updateLastHeardText(wakeMatch.followUpQuery)
                    return false
                }
                if (!isFinal && now - lastPartialWakeMatchMs < 1500L) {
                    return false
                }
                lastPartialWakeMatchMs = now
                stopSpeechRecognizerInternal()
                if (!wakeMatch.followUpQuery.isNullOrBlank()) {
                    _lastDispatchedVoicePrompt.value = wakeMatch.followUpQuery
                }
                wakeWordManager.onKeywordHit(
                    keyword = wakeMatch.matchedWakeWord,
                    initialQuery = wakeMatch.followUpQuery,
                    targetChatIdOverride = wakeMatch.matchedChat?.id
                )
                return true
            }
        }

        // Update live partial/final transcript and auto-dispatch on final SpeechRecognizer results
        val activeWakeWords = (
            allChats.value.mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } } +
                wakeWordManager.getActiveChats().mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } } +
                allWakeWords.value.filter { it.isEnabled }.map { it.phrase }
            ).distinct()
        val scrubbed = com.example.voice.AudioCommandSanitizer.stripWakeWordTrigger(
            rawText = primaryTranscript,
            customWakeWords = activeWakeWords
        )
        if (scrubbed.isNotBlank() &&
            !com.example.voice.AudioCommandSanitizer.isSystemOrCommandStatusString(
                text = scrubbed,
                dictateTrigger = _dictateTrigger.value,
                sendTrigger = _sendTrigger.value,
                heartbeatTrigger = _heartbeatTrigger.value
            )
        ) {
            wakeWordManager.updateLastHeardText(scrubbed)
            _currentDraftText.value = scrubbed
            if (isFinal) {
                _lastDispatchedVoicePrompt.value = scrubbed
                webLinkHistoryManager.recordVoiceCommand(
                    commandType = VoiceCommandType.VOICE_TURN,
                    triggerPhrase = currentChat.value?.wakeWord?.ifBlank { "Voice Input" } ?: "Voice Input",
                    recognizedTranscript = scrubbed,
                    chatTitle = currentChat.value?.title
                )
                sendMessage(scrubbed, triggeredByWakeWord = true)
                if (_isHandsFreeActive.value && !isTextInputFocused.value) {
                    scheduleSpeechRecognizerRestart(delayMs = 180L, allowDuringUserVoiceTurn = true)
                }
                return true
            }
        }
        return false
    }

    private fun scheduleSpeechRecognizerRestart(
        delayMs: Long = 180L,
        allowDuringUserVoiceTurn: Boolean = false
    ) {
        speechRecognizerRestartJob?.cancel()
        speechRecognizerRestartJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            delay(delayMs.coerceAtLeast(50L))
            if ((_isHandsFreeActive.value || allowDuringUserVoiceTurn) &&
                !isTextInputFocused.value &&
                (allowDuringUserVoiceTurn || !isUserVoiceTurn.value) &&
                checkRecordAudioPermission()
            ) {
                startSpeechRecognizerInternal()
            }
        }
    }

    /**
     * Explicitly stops and releases any active AudioRecord instances in both AudioRecorder
     * and WakeWordManager before SpeechRecognizer.startListening() is called, guaranteeing
     * that legacy PCM AudioRecord and SpeechRecognizer NEVER run concurrently.
     */
    fun releaseAllNativeAudioRecordTracksForSpeechRecognizer() {
        audioRecorder.stopAndReleaseHardware()
        wakeWordManager.stopAndReleaseAudioRecordSynchronously()
    }

    private fun startSpeechRecognizerInternal(): Boolean {
        val context = getApplication<Application>()
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context) || !checkRecordAudioPermission()) {
            return false
        }
        return try {
            wakeWordManager.isExternalSpeechRecognizerActive = true
            releaseAllNativeAudioRecordTracksForSpeechRecognizer()
            if (speechRecognizer == null) {
                speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(wakeRecognitionListener)
                }
            }
            val intent = createWakeRecognizerIntent()
            speechRecognizer?.startListening(intent)
            _isSpeechRecognizerListening.value = true
            wakeWordManager.updateExternalListeningState(
                listening = true,
                status = "Listening for wake word (SpeechRecognizer)..."
            )
            true
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "SpeechRecognizer failed to start, using PCM fallback: ${e.message}")
            wakeWordManager.isExternalSpeechRecognizerActive = false
            _isSpeechRecognizerListening.value = false
            false
        }
    }

    private fun stopSpeechRecognizerInternal() {
        speechRecognizerRestartJob?.cancel()
        speechRecognizerRestartJob = null
        wakeWordManager.isExternalSpeechRecognizerActive = false
        _isSpeechRecognizerListening.value = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
    }

    private fun destroySpeechRecognizerInternal() {
        stopSpeechRecognizerInternal()
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    /**
     * Starts continuous wake-word listening via the ViewModel's Hybrid SpeechRecognizer + PCM engine,
     * verifying Manifest.permission.RECORD_AUDIO first.
     */
    fun startContinuousWakeWordListening() {
        if (!ensureMicrophonePermission(startWakeListeningWhenGranted = true)) {
            return
        }
        if (isTextInputFocused.value || isUserVoiceTurn.value) {
            return
        }
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val context = getApplication<Application>()
            if (android.speech.SpeechRecognizer.isRecognitionAvailable(context) &&
                !wakeWordManager.isListening.value
            ) {
                val started = startSpeechRecognizerInternal()
                if (!started) {
                    wakeWordManager.startContinuousListening(forceActiveTurn = false)
                }
            } else {
                wakeWordManager.startContinuousListening(forceActiveTurn = false)
            }
        }
    }

    /**
     * Stops continuous wake-word listening across both SpeechRecognizer and WakeWordManager.
     */
    fun stopContinuousWakeWordListening() {
        stopSpeechRecognizerInternal()
        wakeWordManager.stopContinuousListening()
    }

    /**
     * Toggles continuous wake-word listening on or off, requesting RECORD_AUDIO permission if needed.
     */
    fun toggleContinuousWakeWordListening() {
        if (wakeWordManager.isListening.value || _isSpeechRecognizerListening.value) {
            stopContinuousWakeWordListening()
        } else {
            startContinuousWakeWordListening()
        }
    }

    private val _currentDraftText = MutableStateFlow("")
    val currentDraftText: StateFlow<String> = _currentDraftText.asStateFlow()

    private val _lastHeartbeatPingTime = MutableStateFlow<Long?>(null)
    val lastHeartbeatPingTime: StateFlow<Long?> = _lastHeartbeatPingTime.asStateFlow()

    val customPrompts: StateFlow<List<com.example.data.model.PromptTemplate>> = promptRepository.prompts

    private val _wakeWordSensitivity = MutableStateFlow(appPrefs.getFloat("wake_word_sensitivity", 0.78f))
    val wakeWordSensitivity: StateFlow<Float> = _wakeWordSensitivity.asStateFlow()

    private val _isSleepMode = MutableStateFlow(false)
    val isSleepMode: StateFlow<Boolean> = _isSleepMode.asStateFlow()

    private val _sleepModeReason = MutableStateFlow("")
    val sleepModeReason: StateFlow<String> = _sleepModeReason.asStateFlow()

    private val _backgroundCueOnlyMode = MutableStateFlow(appPrefs.getBoolean("background_cue_only_mode", true))
    val backgroundCueOnlyMode: StateFlow<Boolean> = _backgroundCueOnlyMode.asStateFlow()

    private val _wakeReadyVisualCue = MutableStateFlow<WakeReadyCueState?>(null)
    val wakeReadyVisualCue: StateFlow<WakeReadyCueState?> = _wakeReadyVisualCue.asStateFlow()

    val allWakeWordSamples: StateFlow<List<WakeWordSample>> = repository.getAllWakeWordSamplesFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    fun setBackgroundCueOnlyMode(enabled: Boolean) {
        _backgroundCueOnlyMode.value = enabled
        appPrefs.edit().putBoolean("background_cue_only_mode", enabled).apply()
    }

    fun dismissWakeReadyVisualCue() {
        _wakeReadyVisualCue.value = null
    }

    /**
     * Temporarily disables the wake word listener (Sleep Mode) and displays visual feedback.
     * Can be triggered via the "Gemini, sleep" voice command or a UI tap.
     */
    fun enterSleepMode(reason: String = "Say \"Gemini, wake up\" or tap Wake Up to resume") {
        _isSleepMode.value = true
        _sleepModeReason.value = reason
        wakeWordManager.enterSleepMode(reason)
        com.example.voice.WakeWordVoiceService.setSleepMode(true)
        com.example.voice.WakeWordVoiceService.startServiceSafely(
            context = getApplication(),
            action = com.example.voice.WakeWordVoiceService.ACTION_ENTER_SLEEP_MODE
        )
        wakeWordManager.stopContinuousListening()
        wakeWordManager.setStatusMessage("Sleep Mode active — tap Wake Up or say \"Gemini, wake up\"")
        if (_isHandsFreeActive.value && !isTextInputFocused.value) {
            scheduleSpeechRecognizerRestart(delayMs = 300L, allowDuringUserVoiceTurn = true)
        }
    }

    /**
     * Re-enables the wake word listener from Sleep Mode via UI tap or "Gemini, wake up" voice command.
     */
    fun exitSleepModeAndResumeListening(reason: String = "Listening resumed") {
        _isSleepMode.value = false
        _sleepModeReason.value = ""
        wakeWordManager.exitSleepMode(reason)
        com.example.voice.WakeWordVoiceService.setSleepMode(false)
        com.example.voice.WakeWordVoiceService.startServiceSafely(
            context = getApplication(),
            action = com.example.voice.WakeWordVoiceService.ACTION_WAKE_FROM_SLEEP
        )
        wakeWordManager.setStatusMessage("$reason — say a wake word")
        startContinuousWakeWordListening()
    }

    fun setWakeWordSensitivity(sensitivity: Float) {
        val clamped = sensitivity.coerceIn(0.50f, 0.98f)
        _wakeWordSensitivity.value = clamped
        appPrefs.edit().putFloat("wake_word_sensitivity", clamped).apply()
        wakeWordManager.wakeWordSensitivity = clamped
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.updateSensitivity(clamped)
        }
    }

    fun setWakeWordSensitivityLevel(level: com.example.voice.WakeSensitivityLevel) {
        setWakeWordSensitivity(level.sensitivityValue)
    }

    val localSyncServer = com.example.bridge.LocalSyncRelayServer(viewModelScope) { chromeMessage ->
        val chatId = _currentChatId.value ?: return@LocalSyncRelayServer
        // Insert message received from Chrome using safe transaction
        repository.insertMessageSafely(chromeMessage.copy(chatId = chatId))
        // Trigger Gemini reply if it's a user message
        if (chromeMessage.sender == "user") {
            sendMessage(chatId, chromeMessage.content, triggeredByWakeWord = false)
        }
    }

    val relayServerState: StateFlow<com.example.bridge.RelayServerState> = localSyncServer.serverState

    val audioRecorder = AudioRecorder(application.applicationContext)
    val snippetPlayer = SnippetAudioPlayer()
    val wakeWordStorageManager = WakeWordStorageManager(application.applicationContext)

    private val _activeRecordingSlot = MutableStateFlow<Int?>(null)
    val activeRecordingSlot: StateFlow<Int?> = _activeRecordingSlot.asStateFlow()

    private var activeRecordingChatId: Long = 0L

    private val _snippetRecordingDurationMs = MutableStateFlow(0L)
    val snippetRecordingDurationMs: StateFlow<Long> = _snippetRecordingDurationMs.asStateFlow()

    private var recordingTimerJob: kotlinx.coroutines.Job? = null

    val audioRecorderState: StateFlow<AudioRecorder.State> = audioRecorder.state
    val currentlyPlayingSnippetPath: StateFlow<String?> = snippetPlayer.currentPlayingPath
    val snippetPlaybackProgress: StateFlow<Float> = snippetPlayer.playbackProgress
    val snippetPlaybackPositionMs: StateFlow<Int> = snippetPlayer.playbackPositionMs
    val snippetPlaybackDurationMs: StateFlow<Int> = snippetPlayer.playbackDurationMs
    val isSnippetPaused: StateFlow<Boolean> = snippetPlayer.isPaused

    private val _wakeWordStorageBytes = MutableStateFlow(wakeWordStorageManager.getTotalStorageBytes())
    val wakeWordStorageBytes: StateFlow<Long> = _wakeWordStorageBytes.asStateFlow()

    private val _autoStopLimitSec = MutableStateFlow(appPrefs.getInt("auto_stop_limit_sec", 3))
    val autoStopLimitSec: StateFlow<Int> = _autoStopLimitSec.asStateFlow()

    private val _masterVolume = MutableStateFlow(appPrefs.getFloat("master_volume", 1.0f))
    val masterVolume: StateFlow<Float> = _masterVolume.asStateFlow()

    fun setAutoStopLimit(seconds: Int) {
        _autoStopLimitSec.value = seconds.coerceAtLeast(0)
        appPrefs.edit().putInt("auto_stop_limit_sec", _autoStopLimitSec.value).apply()
    }

    fun setMasterVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _masterVolume.value = clamped
        appPrefs.edit().putFloat("master_volume", clamped).apply()
        snippetPlayer.setVolume(clamped)
        streamingVoicePlayer.setVolume(clamped)
    }

    private val networkStatusTracker = NetworkStatusTracker(application)
    val networkState: StateFlow<NetworkConnectionState> = networkStatusTracker.networkState

    fun refreshNetworkStatus() {
        networkStatusTracker.refresh()
    }

    private val webLinkHistoryManager = WebLinkHistoryManager(application)
    val webLinkHistory: StateFlow<List<WebLinkHistoryEntry>> = webLinkHistoryManager.historyEntries
    val voiceCommandHistory: StateFlow<List<VoiceCommandHistoryEntry>> = webLinkHistoryManager.voiceCommandEntries

    fun submitWebSearchOrUrl(input: String): WebLinkHistoryEntry? {
        return webLinkHistoryManager.recordSearchOrUrl(input)
    }

    fun recordChatLinkOpened(url: String, label: String? = null, chatTitle: String? = null): WebLinkHistoryEntry? {
        return webLinkHistoryManager.recordChatLink(url, label, chatTitle)
    }

    fun recordIncomingExternalLink(url: String): WebLinkHistoryEntry? {
        return webLinkHistoryManager.recordIncomingExternalLink(url)
    }

    fun deleteWebHistoryEntry(id: String) {
        webLinkHistoryManager.deleteEntry(id)
    }

    fun clearAllWebHistory() {
        webLinkHistoryManager.clearAllHistory()
    }

    fun recordVoiceCommand(
        commandType: VoiceCommandType,
        triggerPhrase: String,
        recognizedTranscript: String,
        chatTitle: String? = null
    ): VoiceCommandHistoryEntry? {
        return webLinkHistoryManager.recordVoiceCommand(
            commandType = commandType,
            triggerPhrase = triggerPhrase,
            recognizedTranscript = recognizedTranscript,
            chatTitle = chatTitle
        )
    }

    fun deleteVoiceCommandEntry(id: String) {
        webLinkHistoryManager.deleteVoiceCommandEntry(id)
    }

    fun clearAllVoiceCommandHistory() {
        webLinkHistoryManager.clearAllVoiceCommandHistory()
    }

    /**
     * Purges or repairs any corrupted JSON entries in web/voice history and chat disk caches.
     */
    fun clearCorruptedHistory(): Int {
        val repairedWebAndVoice = webLinkHistoryManager.repairOrClearCorruptedHistory()
        val repairedDiskCaches = bufferManager.repairOrClearCorruptedDiskCaches()
        return repairedWebAndVoice + repairedDiskCaches
    }

    private val _notifyActiveChatReplies = MutableStateFlow(appPrefs.getBoolean("notify_active_chat_replies", true))
    val notifyActiveChatReplies: StateFlow<Boolean> = _notifyActiveChatReplies.asStateFlow()

    fun setNotifyActiveChatReplies(enabled: Boolean) {
        _notifyActiveChatReplies.value = enabled
        appPrefs.edit().putBoolean("notify_active_chat_replies", enabled).apply()
    }

    private val _permissionRationaleEnabled = MutableStateFlow(appPrefs.getBoolean("permission_rationale_enabled", true))
    val permissionRationaleEnabled: StateFlow<Boolean> = _permissionRationaleEnabled.asStateFlow()

    private val _permissionsResetCount = MutableStateFlow(appPrefs.getInt("permissions_reset_count", 0))
    val permissionsResetCount: StateFlow<Int> = _permissionsResetCount.asStateFlow()

    fun resetPermissionsState() {
        val nextCount = _permissionsResetCount.value + 1
        _permissionsResetCount.value = nextCount
        _permissionRationaleEnabled.value = true
        appPrefs.edit()
            .putBoolean("permission_rationale_enabled", true)
            .putInt("permissions_reset_count", nextCount)
            .apply()
    }

    private var isAppInForeground: Boolean = true

    fun refreshWakeWordStorageStats() {
        _wakeWordStorageBytes.value = wakeWordStorageManager.getTotalStorageBytes()
    }

    val allChats: StateFlow<List<ChatSession>> = repository.allChats
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val allWakeWords: StateFlow<List<WakeWord>> = wakeWordRepository.observeAllWakeWords()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val configuredWakeWords: StateFlow<List<WakeWord>> = wakeWordRepository.observeChatWakeWords()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _currentChatId = MutableStateFlow<Long?>(null)
    val currentChatId: StateFlow<Long?> = _currentChatId.asStateFlow()

    val currentChat: StateFlow<ChatSession?> = _currentChatId
        .flatMapLatest { id ->
            if (id != null) repository.getChatById(id) else flowOf(null)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    // Raw messages from MessageRepository
    val rawCurrentMessages: StateFlow<List<ChatMessage>> = _currentChatId
        .flatMapLatest { id ->
            if (id != null) messageRepository.getMessagesForChat(id) else flowOf(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Hot memory-buffered messages (avoids freezing on large chat threads)
    val hotMessages: StateFlow<List<ChatMessage>> = bufferManager.hotMessages
    val bufferMetrics: StateFlow<BufferMetrics> = bufferManager.bufferMetrics
    val hasMoreOlderMessages: StateFlow<Boolean> = bufferManager.hasMoreOlderMessages
    val isLoadingOlder: StateFlow<Boolean> = bufferManager.isLoadingOlder

    val allMessages: StateFlow<List<ChatMessage>> = messageRepository.getAllMessages()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _triggerBanner = MutableStateFlow<TriggerBanner?>(null)
    val triggerBanner: StateFlow<TriggerBanner?> = _triggerBanner.asStateFlow()

    private val _isAutoSpeakEnabled = MutableStateFlow(true)
    val isAutoSpeakEnabled: StateFlow<Boolean> = _isAutoSpeakEnabled.asStateFlow()

    // Active stopwatch/timer tracking state per listed chat
    private val _activeTimerChatIds = MutableStateFlow<Set<Long>>(emptySet())
    val activeTimerChatIds: StateFlow<Set<Long>> = _activeTimerChatIds.asStateFlow()

    private val _liveTrackedSeconds = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val liveTrackedSeconds: StateFlow<Map<Long, Long>> = _liveTrackedSeconds.asStateFlow()

    // Open chat stopwatch/timer: only persists as long as the chat is open (not necessarily active)
    private val _openChatTimerSeconds = MutableStateFlow(0L)
    val openChatTimerSeconds: StateFlow<Long> = _openChatTimerSeconds.asStateFlow()

    private val _isOpenChatTimerRunning = MutableStateFlow(false)
    val isOpenChatTimerRunning: StateFlow<Boolean> = _isOpenChatTimerRunning.asStateFlow()

    // Comparison UI element toggle for Usage Limits vs Cache
    private val _showUsageLimitsComparison = MutableStateFlow(true)
    val showUsageLimitsComparison: StateFlow<Boolean> = _showUsageLimitsComparison.asStateFlow()

    // Gemini Coupler & Piggyback Bridge State
    private val _lastCouplerHandoffTime = MutableStateFlow<Long?>(null)
    val lastCouplerHandoffTime: StateFlow<Long?> = _lastCouplerHandoffTime.asStateFlow()

    // Dedicated Screenshot & Image Staging Pipeline
    val stagedImageUri: StateFlow<Uri?> = GeminiCouplerBridge.stagedImageUri
    val stagedImageInfo: StateFlow<StagedImageInfo?> = GeminiCouplerBridge.stagedImageInfo
    val imagePayloadChannel = GeminiCouplerBridge.imagePayloadChannel

    private val _screenshotBanner = MutableStateFlow<StagedImageInfo?>(null)
    val screenshotBanner: StateFlow<StagedImageInfo?> = _screenshotBanner.asStateFlow()

    private val screenshotWatcher = ScreenshotWatcher(application) { uri ->
        stageImage(uri)
    }

    private val chatImportWorker = ChatImportWorker(application, database.chatDao())
    private val _importProgressState = MutableStateFlow(ImportProgressState())
    val importProgressState: StateFlow<ImportProgressState> = _importProgressState.asStateFlow()

    // Dual-Key Failover State
    val apiKeyManager = ApiKeyManager.getInstance(application)
    val activeKeyType: StateFlow<ActiveKeyType> = apiKeyManager.activeKeyType
    val primaryApiKey: StateFlow<String> = apiKeyManager.primaryKeyFlow
    val backupApiKey: StateFlow<String> = apiKeyManager.backupKeyFlow

    fun setPrimaryApiKey(key: String) {
        apiKeyManager.setPrimaryApiKey(key)
    }

    fun setBackupApiKey(key: String) {
        apiKeyManager.setBackupApiKey(key)
    }

    fun toggleActiveKey() {
        apiKeyManager.toggleActiveKey()
    }

    fun resetToPrimaryApiKey() {
        apiKeyManager.resetToPrimary()
    }

    private var timerTickerJob: Job? = null
    private var activeGenerationJob: Job? = null
    private val voicePcmOutputStream = java.io.ByteArrayOutputStream()
    private val _lastAudioPacketTimestampMs = MutableStateFlow(System.currentTimeMillis())
    val lastAudioPacketTimestampMs: StateFlow<Long> = _lastAudioPacketTimestampMs.asStateFlow()

    init {
        snippetPlayer.setVolume(_masterVolume.value)
        streamingVoicePlayer.setVolume(_masterVolume.value)
        wakeWordManager.wakeWordSensitivity = _wakeWordSensitivity.value
        screenshotWatcher.startWatching()
        // Sync active chats with wake word manager, seed Room wake-word records, and populate base tracked seconds
        viewModelScope.launch {
            wakeWordRepository.ensureSeededFromChatsAndDefaults(
                chats = emptyList(),
                initialDictate = _dictateTrigger.value,
                initialSend = _sendTrigger.value,
                initialHeartbeat = _heartbeatTrigger.value,
                initialSensitivity = _wakeWordSensitivity.value
            )
            allChats.collect { chats ->
                if (chats.isNotEmpty()) {
                    wakeWordManager.updateActiveChats(chats)
                    wakeWordRepository.ensureSeededFromChatsAndDefaults(
                        chats = chats,
                        initialDictate = _dictateTrigger.value,
                        initialSend = _sendTrigger.value,
                        initialHeartbeat = _heartbeatTrigger.value,
                        initialSensitivity = _wakeWordSensitivity.value
                    )
                }
                for (chat in chats) {
                    loadWakeWordSamples(chat.id)
                }
                _liveTrackedSeconds.update { currentMap ->
                    val mutable = currentMap.toMutableMap()
                    for (chat in chats) {
                        if (!mutable.containsKey(chat.id)) {
                            mutable[chat.id] = chat.totalTrackedSeconds
                        }
                    }
                    mutable
                }
            }
        }

        // Hydrate and reactively sync Room-persisted wake words and hands-free triggers into WakeWordManager
        viewModelScope.launch {
            wakeWordRepository.allWakeWords.collect { allConfigured ->
                wakeWordManager.updateConfiguredWakeWords(
                    allConfigured.filter { it.triggerType == WakeWord.TYPE_CHAT }
                )
                allConfigured.firstOrNull {
                    it.triggerType == WakeWord.TYPE_HANDS_FREE && it.commandRole == WakeWord.ROLE_DICTATE
                }?.let { row ->
                    if (row.phrase.isNotBlank() && _dictateTrigger.value != row.phrase) {
                        _dictateTrigger.value = row.phrase
                        wakeWordManager.dictateTrigger = row.phrase
                        appPrefs.edit().putString("dictate_trigger", row.phrase).apply()
                    }
                }
                allConfigured.firstOrNull {
                    it.triggerType == WakeWord.TYPE_HANDS_FREE && it.commandRole == WakeWord.ROLE_SEND
                }?.let { row ->
                    if (row.phrase.isNotBlank() && _sendTrigger.value != row.phrase) {
                        _sendTrigger.value = row.phrase
                        wakeWordManager.sendTrigger = row.phrase
                        appPrefs.edit().putString("send_trigger", row.phrase).apply()
                    }
                }
                allConfigured.firstOrNull {
                    it.triggerType == WakeWord.TYPE_HANDS_FREE && it.commandRole == WakeWord.ROLE_HEARTBEAT
                }?.let { row ->
                    if (row.phrase.isNotBlank() && _heartbeatTrigger.value != row.phrase) {
                        _heartbeatTrigger.value = row.phrase
                        wakeWordManager.heartbeatTrigger = row.phrase
                        appPrefs.edit().putString("heartbeat_trigger", row.phrase).apply()
                    }
                }
                allConfigured.firstOrNull {
                    it.triggerType == WakeWord.TYPE_HANDS_FREE
                }?.sensitivity?.let { savedSens ->
                    val clamped = savedSens.coerceIn(0.50f, 0.98f)
                    if (_wakeWordSensitivity.value != clamped) {
                        _wakeWordSensitivity.value = clamped
                        wakeWordManager.wakeWordSensitivity = clamped
                        appPrefs.edit().putFloat("wake_word_sensitivity", clamped).apply()
                    }
                }
            }
        }

        // Keep memory buffer synced with repository messages
        viewModelScope.launch {
            rawCurrentMessages.collect { messages ->
                _currentChatId.value?.let { id ->
                    bufferManager.updateFullMessageList(id, messages)
                }
            }
        }

        // Wire wake turn audio handoff so buffered wake audio is prepended into the active chat turn
        wakeWordManager.onWakeAudioHandoff = { wakeBytes ->
            if (!WakeWordManager.isPcmByteBufferSilent(wakeBytes, wakeBytes.size)) {
                _lastAudioPacketTimestampMs.value = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
                synchronized(voicePcmOutputStream) {
                    val existing = voicePcmOutputStream.toByteArray()
                    voicePcmOutputStream.reset()
                    val maxBytes = 16000 * 2 * 30
                    val wakeToWrite = wakeBytes.size.coerceAtMost(maxBytes)
                    voicePcmOutputStream.write(wakeBytes, 0, wakeToWrite)
                    if (existing.isNotEmpty() && !existing.contentEquals(wakeBytes)) {
                        val remaining = (maxBytes - voicePcmOutputStream.size()).coerceAtLeast(0)
                        if (remaining > 0) {
                            voicePcmOutputStream.write(existing, 0, existing.size.coerceAtMost(remaining))
                        }
                    }
                }
            }
        }

        // Synchronously reset stale draft, hung recognizers, and active voice state whenever a wake word hits
        // (even if the user is ALREADY in ChatDetailScreen or holding an unsent draft)
        wakeWordManager.onWakeForceReset = { chatId, chatTitle, keyword, sanitizedQuery ->
            stopSpeechRecognizerInternal()
            streamingVoicePlayer.stop()
            _currentDraftText.value = sanitizedQuery ?: ""
            _wakeRetriggerEventId.update { it + 1L }
            _triggerBanner.value = TriggerBanner(
                chatTitle = chatTitle,
                matchedWord = keyword,
                query = sanitizedQuery
            )
            if (chatId > 0L && _currentChatId.value != chatId) {
                selectChat(chatId)
            }
            if (!sanitizedQuery.isNullOrBlank()) {
                _lastDispatchedVoicePrompt.value = sanitizedQuery
            } else if (!isTextInputFocused.value) {
                val context = getApplication<Application>()
                if (android.speech.SpeechRecognizer.isRecognitionAvailable(context) && checkRecordAudioPermission()) {
                    wakeWordManager.isExternalSpeechRecognizerActive = true
                    releaseAllNativeAudioRecordTracksForSpeechRecognizer()
                    scheduleSpeechRecognizerRestart(delayMs = 120L, allowDuringUserVoiceTurn = true)
                }
            }
        }

        // Listen for wake word detections
        viewModelScope.launch {
            wakeWordManager.triggerEvents.collect { event ->
                if (event.capturedAudioBytes.isNotEmpty() &&
                    !WakeWordManager.isPcmByteBufferSilent(event.capturedAudioBytes, event.capturedAudioBytes.size)
                ) {
                    synchronized(voicePcmOutputStream) {
                        if (voicePcmOutputStream.size() == 0) {
                            voicePcmOutputStream.write(event.capturedAudioBytes)
                        }
                    }
                }
                val resolvedChatId = resolveOrEnsureTargetChatId(event.chatId)
                val inBackground = !isAppInForeground
                _wakeReadyVisualCue.value = WakeReadyCueState(
                    wakePhrase = event.matchedWord,
                    chatId = resolvedChatId,
                    chatTitle = event.chatTitle,
                    followUpQuery = event.initialQuery,
                    detectedInBackground = inBackground
                )
                if (inBackground || _backgroundCueOnlyMode.value) {
                    val matchedWake = allWakeWords.value.firstOrNull {
                        it.phrase.equals(event.matchedWord, ignoreCase = true)
                    } ?: WakeWord(
                        phrase = event.matchedWord,
                        triggerType = TriggerType.CHAT,
                        chatId = resolvedChatId,
                        chatTitle = event.chatTitle,
                        sensitivity = _wakeWordSensitivity.value
                    )
                    com.example.voice.WakeWordVoiceService.postWakeWordReadyCueNotification(
                        context = getApplication(),
                        result = com.example.voice.WakeWordDetectionResult(
                            wakeWord = matchedWake,
                            matchedPhrase = event.matchedWord,
                            followUpQuery = event.initialQuery,
                            rawTranscript = event.initialQuery ?: event.matchedWord
                        )
                    )
                }
                _triggerBanner.value = TriggerBanner(
                    chatTitle = event.chatTitle,
                    matchedWord = event.matchedWord,
                    query = event.initialQuery
                )
                val transcriptText = event.initialQuery?.takeIf { it.isNotBlank() }
                    ?: "Wake phrase \"${event.matchedWord}\" recognized"
                webLinkHistoryManager.recordVoiceCommand(
                    commandType = VoiceCommandType.WAKE_WORD,
                    triggerPhrase = event.matchedWord,
                    recognizedTranscript = transcriptText,
                    chatTitle = event.chatTitle
                )
                // Pre-warm memory buffer immediately and select the corresponding chat
                selectChat(resolvedChatId)

                // If user continued speaking a full text query after the wake word, submit it directly!
                val customWords = listOfNotNull(event.matchedWord.takeIf { it.isNotBlank() })
                val sanitizedQuery = com.example.voice.AudioCommandSanitizer.stripWakeWordTrigger(
                    rawText = event.initialQuery ?: "",
                    customWakeWords = customWords
                )
                if (sanitizedQuery.isNotBlank()) {
                    val handoffAudio = synchronized(voicePcmOutputStream) {
                        val bytes = voicePcmOutputStream.toByteArray()
                        voicePcmOutputStream.reset()
                        bytes
                    }.takeIf { WakeWordManager.shouldTransmitAudioPacket(it.size, it) }
                    sendMessage(
                        chatId = resolvedChatId,
                        text = sanitizedQuery,
                        audioBytes = handoffAudio,
                        triggeredByWakeWord = true
                    )
                } else {
                    val targetChat = repository.getChatByIdSync(resolvedChatId)
                    val customGreeting = targetChat?.greetingMessage?.trim().orEmpty()
                    if (customGreeting.isNotBlank() && _isAutoSpeakEnabled.value) {
                        speakMessage(customGreeting)
                    }
                }
            }
        }

        // Sync sleep mode changes from WakeWordManager and WakeWordVoiceService notification actions
        wakeWordManager.onSleepModeChanged = { sleeping, reason ->
            _isSleepMode.value = sleeping
            _sleepModeReason.value = if (sleeping) {
                reason.ifBlank { "Sleep Mode active — tap Wake Up or say \"Gemini, wake up\"" }
            } else {
                ""
            }
        }

        com.example.voice.WakeWordVoiceService.onSleepModeChangedCallback = { sleeping ->
            _isSleepMode.value = sleeping
            if (!sleeping) {
                _sleepModeReason.value = ""
                wakeWordManager.exitSleepMode("Resumed from notification")
            } else {
                if (_sleepModeReason.value.isBlank()) {
                    _sleepModeReason.value = "Sleep Mode active — tap Wake Up or say \"Gemini, wake up\""
                }
                wakeWordManager.enterSleepMode(_sleepModeReason.value)
            }
        }

        // Wire AudioRecord PCM stream (16000Hz, MONO, PCM_16BIT, VOICE_RECOGNITION with MIC fallback)
        // and Auto-Silence Cutoff into Gemini Native Audio pipeline
        wakeWordManager.onAudioPacketCaptured = { packet ->
            if (!WakeWordManager.isPcmByteBufferSilent(packet.pcmBytes, packet.bytesRead)) {
                _lastAudioPacketTimestampMs.value = WakeWordManager.resolvePresentationTimestamp(packet.presentationTimestampMs)
                synchronized(voicePcmOutputStream) {
                    if (voicePcmOutputStream.size() < 16000 * 2 * 30) { // Max 30s buffer
                        voicePcmOutputStream.write(packet.pcmBytes, 0, packet.bytesRead.coerceAtMost(packet.pcmBytes.size))
                    }
                }
            }
        }

        wakeWordManager.onAudioFrameCaptured = { pcmChunk ->
            if (wakeWordManager.onAudioPacketCaptured == null &&
                !WakeWordManager.isPcmByteBufferSilent(pcmChunk, pcmChunk.size)
            ) {
                _lastAudioPacketTimestampMs.value = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
                synchronized(voicePcmOutputStream) {
                    if (voicePcmOutputStream.size() < 16000 * 2 * 30) { // Max 30s buffer
                        voicePcmOutputStream.write(pcmChunk)
                    }
                }
            }
        }

        wakeWordManager.onSpeechStarted = {
            // Preserve already-captured non-zero PCM samples in voicePcmOutputStream
            wakeWordManager.setStatusMessage("Capturing speech...")
        }

        wakeWordManager.onSilenceCutoffDetected = {
            sendAudioTurn()
        }

        streamingVoicePlayer.onPlaybackCompleted = {
            if (_isHandsFreeActive.value && !_isGenerating.value) {
                wakeWordManager.restartWakeListeningAfterTurn()
            }
        }

        // Wire Hands-Free triggers and callbacks into WakeWordManager
        wakeWordManager.dictateTrigger = _dictateTrigger.value
        wakeWordManager.sendTrigger = _sendTrigger.value
        wakeWordManager.heartbeatTrigger = _heartbeatTrigger.value
        if (android.speech.SpeechRecognizer.isRecognitionAvailable(application)) {
            wakeWordManager.isExternalSpeechRecognizerActive = true
        }
        wakeWordManager.setHandsFreeMode(_isHandsFreeActive.value)

        wakeWordManager.onHandsFreePartialText = { partialText ->
            if (!com.example.voice.AudioCommandSanitizer.isSystemOrCommandStatusString(
                    text = partialText,
                    dictateTrigger = _dictateTrigger.value,
                    sendTrigger = _sendTrigger.value,
                    heartbeatTrigger = _heartbeatTrigger.value
                )
            ) {
                _currentDraftText.value = partialText
            }
        }

        wakeWordManager.onHandsFreeSend = { scrubbedUtterance ->
            val textToSend = if (scrubbedUtterance.isNotBlank()) scrubbedUtterance else _currentDraftText.value
            val cleaned = com.example.voice.AudioCommandSanitizer.sanitizeSpokenText(
                text = textToSend,
                sendTrigger = _sendTrigger.value,
                heartbeatTrigger = _heartbeatTrigger.value
            )
            if (cleaned.isNotBlank() &&
                !com.example.voice.AudioCommandSanitizer.isSystemOrCommandStatusString(
                    text = cleaned,
                    dictateTrigger = _dictateTrigger.value,
                    sendTrigger = _sendTrigger.value,
                    heartbeatTrigger = _heartbeatTrigger.value
                )
            ) {
                viewModelScope.launch {
                    val targetChatId = resolveOrEnsureTargetChatId(_currentChatId.value)
                    selectChat(targetChatId)
                    webLinkHistoryManager.recordVoiceCommand(
                        commandType = VoiceCommandType.VOICE_SEND,
                        triggerPhrase = _sendTrigger.value,
                        recognizedTranscript = cleaned,
                        chatTitle = currentChat.value?.title
                    )
                    _currentDraftText.value = ""
                    val bufferedAudio = synchronized(voicePcmOutputStream) {
                        val b = voicePcmOutputStream.toByteArray()
                        voicePcmOutputStream.reset()
                        b
                    }.takeIf { WakeWordManager.shouldTransmitAudioPacket(it.size, it) }
                    sendMessage(targetChatId, cleaned, audioBytes = bufferedAudio, triggeredByWakeWord = true)
                }
            }
        }

        wakeWordManager.onHandsFreeHeartbeat = {
            _lastHeartbeatPingTime.value = System.currentTimeMillis()
            webLinkHistoryManager.recordVoiceCommand(
                commandType = VoiceCommandType.HEARTBEAT,
                triggerPhrase = _heartbeatTrigger.value,
                recognizedTranscript = "Hands-free heartbeat ping command recognized",
                chatTitle = currentChat.value?.title
            )
            com.example.coupler.ChromeBridgeSocketManager.recordHeartbeatPing()
            com.example.heartbeat.HeartbeatAlarmReceiver.scheduleHeartbeatPing(getApplication(), 0L)
        }

        // Barge-In Interruption: user speaking wake or dictate words immediately halts Orion audio
        wakeWordManager.onBargeInDetected = {
            if (streamingVoicePlayer.isPlaying.value) {
                streamingVoicePlayer.stop()
            }
        }

        // Start the background sync relay server for automatic Chrome synchronization
        localSyncServer.setChatDataProvider {
            currentChat.value to rawCurrentMessages.value
        }
        localSyncServer.start(8765)

        // Observe progress and results from both direct ChatImportWorker and background FileProcessingService
        viewModelScope.launch {
            chatImportWorker.progressState.collect { state ->
                _importProgressState.value = state
            }
        }
        viewModelScope.launch {
            FileProcessingService.serviceProgressState.collect { state ->
                if (state.isImporting || state.statusMessage.isNotBlank() || state.error != null) {
                    _importProgressState.value = state
                }
            }
        }
        viewModelScope.launch {
            FileProcessingService.completedImportChatId.collect { importedChatId ->
                if (importedChatId != null && importedChatId > 0L) {
                    selectChat(importedChatId)
                    FileProcessingService.consumeCompletedImportChatId()
                }
            }
        }
        viewModelScope.launch {
            FileProcessingService.stagedAttachmentFlow.collect { payload ->
                if (payload != null) {
                    _stagedAttachment.value = payload
                    FileProcessingService.consumeStagedAttachment()
                }
            }
        }
    }

    fun selectChat(chatId: Long?) {
        val prevChatId = _currentChatId.value
        val isSwitching = prevChatId != chatId

        if (isSwitching) {
            // Cancel active generation & streaming text immediately so old session state doesn't bleed
            activeGenerationJob?.cancel()
            activeGenerationJob = null
            _isGenerating.value = false
            _streamingText.value = null
            _errorMessage.value = null
            streamingVoicePlayer.stop()

            // Detach and purge in-memory buffers for previous chat
            bufferManager.detachChat()
            _currentDraftText.value = ""

            // Lifecycle Hook: Force reset and clear ChromeBridgeSocketManager on session switch or close
            ChromeBridgeSocketManager.forceResetAndClear(getApplication(), preserveDiskCache = _preserveCacheAcrossSessions.value)
        }

        _currentChatId.value = chatId

        if (chatId != null) {
            // Chat is OPENED: Start open-chat timer. It only persists as long as the chat is open.
            // It ticks continuously while open whether user is active, speaking, or idle (not necessarily active).
            _openChatTimerSeconds.value = 0L
            _isOpenChatTimerRunning.value = true
            _activeTimerChatIds.value = setOf(chatId)
            _liveTrackedSeconds.update { it + (chatId to 0L) }
            startTickerIfNeeded()

            viewModelScope.launch {
                bufferManager.preloadAndAttachChat(chatId)
            }
        } else {
            // Chat is CLOSED: Reset and cancel open chat timer so it only persists as long as the chat is open
            _isOpenChatTimerRunning.value = false
            _openChatTimerSeconds.value = 0L
            _activeTimerChatIds.value = emptySet()
            if (prevChatId != null) {
                _liveTrackedSeconds.update { it + (prevChatId to 0L) }
                viewModelScope.launch {
                    repository.updateTrackedSeconds(prevChatId, 0L)
                }
            }
        }
    }

    /**
     * Purges active view model session state and reloads the fresh message thread for a chat.
     */
    fun purgeActiveSessionState(chatId: Long) {
        if (_currentChatId.value == chatId) {
            _streamingText.value = null
            _errorMessage.value = null
            activeGenerationJob?.cancel()
            activeGenerationJob = null
            _isGenerating.value = false
            bufferManager.detachChat()
            ChromeBridgeSocketManager.forceResetAndClear(getApplication(), preserveDiskCache = _preserveCacheAcrossSessions.value)
            viewModelScope.launch {
                bufferManager.preloadAndAttachChat(chatId)
            }
        }
    }

    fun loadOlderMessages() {
        viewModelScope.launch {
            bufferManager.loadOlderMessagesChunk()
        }
    }

    fun setBufferSizeLimit(limit: Int) {
        bufferManager.setBufferSizeLimit(limit)
    }

    fun purgeDiskCache() {
        _currentChatId.value?.let { id ->
            bufferManager.clearDiskCache(id)
        }
    }

    fun clearIncomingDiskCache() {
        _currentChatId.value?.let { id ->
            bufferManager.clearIncomingDiskCache(id)
        }
    }

    fun clearOutgoingDiskCache() {
        _currentChatId.value?.let { id ->
            bufferManager.clearOutgoingDiskCache(id)
        }
    }

    fun openCurrentChatInChrome() {
        val chat = currentChat.value ?: return
        val messages = rawCurrentMessages.value
        chromeBridge.openChatInChrome(chat, messages)
    }

    fun getChromeBridgePayload(): String {
        val chat = currentChat.value ?: return "{}"
        val messages = rawCurrentMessages.value
        return chromeBridge.createBridgePayload(chat, messages)
    }

    fun importFromChrome(payload: String, onComplete: ((Boolean) -> Unit)? = null) {
        val parsed = chromeBridge.parseBridgePayload(payload)
        if (parsed == null) {
            onComplete?.invoke(false)
            return
        }

        viewModelScope.launch {
            val chatId = _currentChatId.value ?: return@launch
            val existing = rawCurrentMessages.value
            val existingTimestamps = existing.map { it.timestamp }.toSet()

            // Merge newly added messages from Chrome
            for (newMsg in parsed.messages) {
                val resolvedTs = WakeWordManager.resolvePresentationTimestamp(newMsg.timestamp)
                if (!existingTimestamps.contains(resolvedTs)) {
                    repository.insertMessage(
                        ChatMessage(
                            chatId = chatId,
                            sender = newMsg.sender,
                            content = newMsg.content,
                            timestamp = resolvedTs,
                            triggeredByWakeWord = newMsg.triggeredByWakeWord
                        )
                    )
                }
            }
            onComplete?.invoke(true)
        }
    }

    fun toggleAutoSpeak() {
        _isAutoSpeakEnabled.value = !_isAutoSpeakEnabled.value
        if (!_isAutoSpeakEnabled.value) {
            streamingVoicePlayer.stop()
        }
    }

    private val _stagedAttachment = MutableStateFlow<AttachmentPayload?>(null)
    val stagedAttachment: StateFlow<AttachmentPayload?> = _stagedAttachment.asStateFlow()

    fun stageFile(uri: Uri, context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val contentResolver = context.contentResolver
                var fileName = "attachment"
                var mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            val name = cursor.getString(nameIndex)
                            if (!name.isNullOrBlank()) fileName = name
                        }
                    }
                }
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: byteArrayOf()
                val sizeBytes = bytes.size.toLong()
                _stagedAttachment.value = AttachmentPayload(uri, fileName, mimeType, bytes, sizeBytes)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to stage file: ${e.message}", e)
            }
        }
    }

    fun stageAttachment(payload: AttachmentPayload) {
        _stagedAttachment.value = payload
    }

    fun clearStagedAttachment() {
        _stagedAttachment.value = null
    }

    fun clearTriggerBanner() {
        _triggerBanner.value = null
    }

    val recordingAudioSource: Int
        get() = wakeWordManager.audioSource

    val isLiveDuplexMode: StateFlow<Boolean> = wakeWordManager.isLiveDuplexMode

    fun setLiveDuplexMode(enabled: Boolean) {
        wakeWordManager.setLiveDuplexMode(enabled)
        if (enabled && !_isHandsFreeActive.value) {
            toggleHandsFreeMode(true)
        }
    }

    fun calculateRecordingBufferSize(): Int {
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            16000,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf * 2, 4096)
        return bufferSize
    }

    fun onTextInputFocusChanged(focused: Boolean) {
        if (focused) {
            stopSpeechRecognizerInternal()
        }
        wakeWordManager.onTextInputFocusChanged(focused)
        if (!focused && _isHandsFreeActive.value && !isUserVoiceTurn.value && checkRecordAudioPermission()) {
            scheduleSpeechRecognizerRestart(delayMs = 200L)
        }
    }

    /**
     * Returns a snapshot of the currently buffered PCM bytes for the active voice turn.
     */
    fun getBufferedVoiceTurnBytes(): ByteArray {
        synchronized(voicePcmOutputStream) {
            return voicePcmOutputStream.toByteArray()
        }
    }

    /**
     * Resolves an existing chat ID or creates a default Gemini chat session if none exists,
     * ensuring hands-free voice turns always route into a valid active chat session.
     */
    private suspend fun resolveOrEnsureTargetChatId(preferredChatId: Long? = null): Long {
        if (preferredChatId != null && preferredChatId > 0L) {
            val existing = repository.getChatByIdSync(preferredChatId)
            if (existing != null) return existing.id
        }
        val currentId = _currentChatId.value
        if (currentId != null && currentId > 0L) {
            val existing = repository.getChatByIdSync(currentId)
            if (existing != null) return existing.id
        }
        val cachedDefault = allChats.value.firstOrNull { it.isDefaultWakeChat } ?: allChats.value.firstOrNull()
        if (cachedDefault != null && cachedDefault.id > 0L) {
            return cachedDefault.id
        }
        val defaultSession = ChatSession(
            title = "Gemini Assistant",
            wakeWord = "Gemini",
            systemInstruction = "You are Gemini, an intelligent and helpful AI assistant.",
            themeColorHex = "#38BDF8",
            iconName = "Sparkles",
            isDefaultWakeChat = true,
            isPersistent = _defaultPersistenceEnabled.value
        )
        return repository.saveChat(defaultSession)
    }

    /**
     * Starts an active voice recording turn via hardware AudioRecord
     * (VOICE_RECOGNITION with automatic fallback to MIC).
     */
    fun startVoiceRecording() {
        if (!ensureMicrophonePermission(startWakeListeningWhenGranted = false)) {
            return
        }
        destroySpeechRecognizerInternal()
        streamingVoicePlayer.stop()
        _currentDraftText.value = ""
        wakeWordManager.clearLastHeardText()
        synchronized(voicePcmOutputStream) {
            voicePcmOutputStream.reset()
        }
        wakeWordManager.setUserVoiceTurn(true)
        wakeWordManager.startContinuousListening(forceActiveTurn = true)
    }

    /**
     * Cancels the active voice recording turn, releases the chat-turn AudioRecord,
     * discards buffered PCM audio, and restarts passive wake listening if hands-free is active.
     */
    fun cancelVoiceRecording() {
        wakeWordManager.setUserVoiceTurn(false)
        wakeWordManager.completeChatTurnAndReleaseMic()
        _currentDraftText.value = ""
        wakeWordManager.clearLastHeardText()
        synchronized(voicePcmOutputStream) {
            voicePcmOutputStream.reset()
        }
        if (_isHandsFreeActive.value) {
            wakeWordManager.restartWakeListeningAfterTurn()
        }
    }

    /**
     * Manually finishes the active voice recording turn (or active SpeechRecognizer session),
     * grabs whatever partial/final transcription exists in the buffer, and immediately dispatches it to Gemini.
     * Aborts the turn with a brief notice if the captured buffer is empty or silent and no text was entered.
     */
    fun finishVoiceRecordingAndSend(manualPromptText: String = "") {
        stopSpeechRecognizerInternal()
        wakeWordManager.setUserVoiceTurn(false)
        wakeWordManager.completeChatTurnAndReleaseMic()
        val rawBytes: ByteArray
        synchronized(voicePcmOutputStream) {
            rawBytes = voicePcmOutputStream.toByteArray()
            voicePcmOutputStream.reset()
        }
        val hasValidAudio = WakeWordManager.shouldTransmitAudioPacket(rawBytes.size, rawBytes)
        val activeWakeWords = (allChats.value + wakeWordManager.getActiveChats())
            .mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } }
            .distinct()
        val bufferedDraft = _currentDraftText.value.trim()
        val bufferedHeard = wakeWordManager.lastHeardText.value.trim()
        val rawCandidateText = manualPromptText.trim()
            .ifBlank { bufferedDraft }
            .ifBlank { bufferedHeard }
        val sanitizedPrompt = com.example.voice.AudioCommandSanitizer.stripWakeWordTrigger(
            rawText = rawCandidateText,
            customWakeWords = activeWakeWords
        )
        _currentDraftText.value = ""
        wakeWordManager.clearLastHeardText()

        if (!hasValidAudio && sanitizedPrompt.isEmpty()) {
            val notice = "No speech detected — voice turn cancelled"
            wakeWordManager.setStatusMessage(notice)
            _errorMessage.value = notice
            if (_isHandsFreeActive.value) {
                wakeWordManager.restartWakeListeningAfterTurn()
            }
            return
        }

        if (sanitizedPrompt.isNotBlank()) {
            _lastDispatchedVoicePrompt.value = sanitizedPrompt
        }

        val packetTimestamp = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
        _lastAudioPacketTimestampMs.value = packetTimestamp

        viewModelScope.launch {
            val targetChatId = resolveOrEnsureTargetChatId(_currentChatId.value)
            if (_currentChatId.value != targetChatId) {
                selectChat(targetChatId)
            }
            val targetChat = repository.getChatByIdSync(targetChatId)
                ?: allChats.value.firstOrNull { it.id == targetChatId }
            val chatWakeWords = (listOfNotNull(targetChat?.wakeWord?.takeIf { it.isNotBlank() }) + activeWakeWords).distinct()
            val cleanedPrompt = com.example.voice.AudioCommandSanitizer.stripWakeWordTrigger(
                rawText = sanitizedPrompt,
                customWakeWords = chatWakeWords
            )

            var resolvedText = cleanedPrompt
            if (resolvedText.isBlank() && hasValidAudio) {
                val transcribedRaw = try {
                    geminiService.transcribeAudioSnippet(rawBytes, "audio/pcm").trim()
                } catch (_: Exception) {
                    ""
                }
                resolvedText = com.example.voice.AudioCommandSanitizer.stripWakeWordTrigger(
                    rawText = transcribedRaw,
                    customWakeWords = chatWakeWords
                )
            }
            if (resolvedText.isNotBlank()) {
                webLinkHistoryManager.recordVoiceCommand(
                    commandType = VoiceCommandType.VOICE_TURN,
                    triggerPhrase = targetChat?.wakeWord?.ifBlank { "Voice Turn" } ?: "Voice Turn",
                    recognizedTranscript = resolvedText,
                    chatTitle = targetChat?.title
                )
            }

            sendMessage(
                chatId = targetChatId,
                text = resolvedText,
                audioBytes = if (hasValidAudio) rawBytes else null,
                triggeredByWakeWord = true
            )
        }
    }

    /**
     * Dispatches the captured PCM audio buffer from WakeWordManager to Gemini for the active chat turn.
     * Aborts the turn with a brief notice if bytesRead <= 0 or if the buffer is silent/empty.
     */
    fun sendAudioTurn(chatId: Long? = _currentChatId.value) {
        wakeWordManager.setUserVoiceTurn(false)
        if (_isHandsFreeActive.value || wakeWordManager.isLiveDuplexMode.value) {
            wakeWordManager.completeTurnKeepLiveDuplexMic()
        } else {
            wakeWordManager.completeChatTurnAndReleaseMic()
        }
        val capturedBytes: ByteArray
        synchronized(voicePcmOutputStream) {
            capturedBytes = voicePcmOutputStream.toByteArray()
            voicePcmOutputStream.reset()
        }
        val hasValidAudio = WakeWordManager.shouldTransmitAudioPacket(capturedBytes.size, capturedBytes)
        val activeWakeWords = allChats.value.mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } }
        val draftText = com.example.voice.AudioCommandSanitizer.stripWakeWordTrigger(
            rawText = _currentDraftText.value,
            customWakeWords = activeWakeWords
        )
        _currentDraftText.value = ""
        wakeWordManager.clearLastHeardText()

        if (!hasValidAudio && draftText.isEmpty()) {
            val notice = "No speech detected — voice turn cancelled"
            wakeWordManager.setStatusMessage(notice)
            if (_isHandsFreeActive.value) {
                wakeWordManager.restartWakeListeningAfterTurn()
            }
            return
        }

        val packetTimestamp = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
        _lastAudioPacketTimestampMs.value = packetTimestamp

        viewModelScope.launch {
            val targetChatId = resolveOrEnsureTargetChatId(chatId ?: _currentChatId.value)
            if (_currentChatId.value != targetChatId) {
                selectChat(targetChatId)
            }
            val targetChat = repository.getChatByIdSync(targetChatId)
                ?: allChats.value.firstOrNull { it.id == targetChatId }
            val chatWakeWords = listOfNotNull(targetChat?.wakeWord?.takeIf { it.isNotBlank() }) + activeWakeWords
            var resolvedDraft = com.example.voice.AudioCommandSanitizer.stripWakeWordTrigger(
                rawText = draftText,
                customWakeWords = chatWakeWords
            )

            if (resolvedDraft.isBlank() && hasValidAudio) {
                val transcribedRaw = try {
                    geminiService.transcribeAudioSnippet(capturedBytes, "audio/pcm").trim()
                } catch (_: Exception) {
                    ""
                }
                resolvedDraft = com.example.voice.AudioCommandSanitizer.stripWakeWordTrigger(
                    rawText = transcribedRaw,
                    customWakeWords = chatWakeWords
                )
            }
            if (resolvedDraft.isNotBlank()) {
                webLinkHistoryManager.recordVoiceCommand(
                    commandType = VoiceCommandType.VOICE_TURN,
                    triggerPhrase = targetChat?.wakeWord ?: "Voice Turn",
                    recognizedTranscript = resolvedDraft,
                    chatTitle = targetChat?.title
                )
            }
            sendMessage(
                chatId = targetChatId,
                text = resolvedDraft,
                audioBytes = if (hasValidAudio) capturedBytes else null,
                triggeredByWakeWord = true
            )
        }
    }

    /**
     * Transcribes the currently captured voice buffer into text using the transcription model.
     */
    fun transcribeVoiceInput(onResult: (String) -> Unit) {
        val capturedBytes: ByteArray
        synchronized(voicePcmOutputStream) {
            capturedBytes = voicePcmOutputStream.toByteArray()
        }
        if (!WakeWordManager.shouldTransmitAudioPacket(capturedBytes.size, capturedBytes)) {
            wakeWordManager.setStatusMessage("No speech detected — voice turn cancelled")
            onResult("")
            return
        }
        _lastAudioPacketTimestampMs.value = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
        viewModelScope.launch {
            val transcription = geminiService.transcribeAudioSnippet(capturedBytes, "audio/pcm")
            if (transcription.isNotBlank()) {
                _currentDraftText.value = transcription
                webLinkHistoryManager.recordVoiceCommand(
                    commandType = VoiceCommandType.DICTATE,
                    triggerPhrase = _dictateTrigger.value,
                    recognizedTranscript = transcription,
                    chatTitle = currentChat.value?.title
                )
            }
            onResult(transcription)
        }
    }

    /**
     * Convenience overload that dispatches recognized speech or text directly to the active/default chat session.
     */
    fun sendMessage(
        spokenText: String,
        triggeredByWakeWord: Boolean = true
    ) {
        val targetChatId = _currentChatId.value
            ?: allChats.value.firstOrNull { it.isDefaultWakeChat }?.id
            ?: allChats.value.firstOrNull()?.id
            ?: wakeWordManager.getActiveChats().firstOrNull { it.isDefaultWakeChat }?.id
            ?: wakeWordManager.getActiveChats().firstOrNull()?.id
            ?: 0L
        sendMessage(
            chatId = targetChatId,
            text = spokenText,
            audioBytes = null,
            triggeredByWakeWord = triggeredByWakeWord
        )
    }

    fun sendMessage(
        chatId: Long,
        text: String,
        audioBytes: ByteArray? = null,
        triggeredByWakeWord: Boolean = false
    ) {
        // Explicitly stop SpeechRecognizer and grab any buffered partial/final transcription if text is blank
        stopSpeechRecognizerInternal()
        val activeWakeWords = (allChats.value + wakeWordManager.getActiveChats())
            .mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } }
            .distinct()
        val effectiveRawText = text.trim()
            .ifBlank { _currentDraftText.value.trim() }
            .ifBlank { wakeWordManager.lastHeardText.value.trim() }
        val trimmed = if (triggeredByWakeWord || text.isBlank()) {
            com.example.voice.AudioCommandSanitizer.stripWakeWordTrigger(
                rawText = effectiveRawText,
                customWakeWords = activeWakeWords
            )
        } else {
            effectiveRawText
        }
        val attachment = _stagedAttachment.value
        val validAudioBytes = audioBytes?.takeIf {
            WakeWordManager.shouldTransmitAudioPacket(it.size, it)
        }
        val hasAudio = validAudioBytes != null
        if (trimmed.isBlank() && attachment == null && !hasAudio) return
        if (_isGenerating.value) return
        if (trimmed.isNotBlank()) {
            _lastDispatchedVoicePrompt.value = trimmed
        }
        _currentDraftText.value = ""
        wakeWordManager.clearLastHeardText()

        val outgoingTimestamp = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
        if (hasAudio) {
            _lastAudioPacketTimestampMs.value = outgoingTimestamp
        }

        // In Live Duplex / Hands-Free mode, keep the bidirectional mic pipeline open;
        // in manual walkie-talkie mode, release the turn AudioRecord.
        if (_isHandsFreeActive.value || wakeWordManager.isLiveDuplexMode.value) {
            wakeWordManager.completeTurnKeepLiveDuplexMic()
        } else {
            wakeWordManager.completeChatTurnAndReleaseMic()
        }

        clearStagedAttachment()

        viewModelScope.launch {
            _errorMessage.value = null
            val resolvedChatId = resolveOrEnsureTargetChatId(chatId)
            if (_currentChatId.value == null || _currentChatId.value != resolvedChatId) {
                _currentChatId.value = resolvedChatId
                bufferManager.preloadAndAttachChat(resolvedChatId)
            }
            val now = WakeWordManager.resolvePresentationTimestamp(outgoingTimestamp)

            val resolvedUserText = when {
                trimmed.isNotEmpty() && !ChatMessage.isLegacyAudioPlaceholder(trimmed) -> trimmed
                validAudioBytes != null -> {
                    val transcribedRaw = try {
                        geminiService.transcribeAudioSnippet(validAudioBytes, "audio/pcm").trim()
                    } catch (_: Exception) {
                        ""
                    }
                    com.example.voice.AudioCommandSanitizer.stripWakeWordTrigger(
                        rawText = transcribedRaw,
                        customWakeWords = activeWakeWords
                    ).ifBlank { trimmed.takeUnless { ChatMessage.isLegacyAudioPlaceholder(it) }.orEmpty() }
                }
                else -> ""
            }

            if (resolvedUserText.isNotBlank()) {
                _lastDispatchedVoicePrompt.value = resolvedUserText
            }

            // Prompt text sent alongside structured inlineData parts
            val apiPrompt = when {
                resolvedUserText.isNotEmpty() -> resolvedUserText
                attachment != null -> "Please analyze this attached file."
                else -> ""
            }

            val userMsg = ChatMessage(
                chatId = resolvedChatId,
                sender = "user",
                content = resolvedUserText,
                timestamp = now,
                triggeredByWakeWord = triggeredByWakeWord,
                attachmentUri = attachment?.uri?.toString(),
                attachmentName = attachment?.fileName,
                attachmentMimeType = attachment?.mimeType,
                attachmentSizeBytes = attachment?.sizeBytes
            )

            // 1. Insert user message into Room safely and append to buffer
            val userMsgId = repository.insertMessage(userMsg)
            if (userMsgId <= 0) {
                // Parent chat session was deleted or does not exist; abort safely
                return@launch
            }
            val savedUserMsg = userMsg.copy(id = userMsgId)
            bufferManager.appendMessage(savedUserMsg)
            syncMessageToCloudIfSignedIn(savedUserMsg)
            syncChatToCloudIfSignedIn(resolvedChatId)

            // 2. Fetch current chat details for system instruction and full chronological message history
            val chat = repository.getChatByIdSync(resolvedChatId)
            val systemPrompt = chat?.systemInstruction ?: "You are Gemini, an intelligent and helpful AI assistant."
            val history = withContext(Dispatchers.IO) {
                messageRepository.getMessagesForChatSync(resolvedChatId)
                    .filter { it.id != userMsgId && !it.isDeleted }
            }

            _isGenerating.value = true
            _streamingText.value = ""

            activeGenerationJob?.cancel()
            activeGenerationJob = launch {
                val fullResponse = StringBuilder()
                try {
                    streamingVoicePlayer.stop()
                    val requestAudio = _voiceEngineMode.value == com.example.voice.VoiceEngineMode.GEMINI_ORION && _isAutoSpeakEnabled.value

                    geminiService.streamChatResponse(
                        context = getApplication(),
                        systemInstruction = systemPrompt,
                        history = history,
                        userPrompt = apiPrompt,
                        userAudioBytes = validAudioBytes,
                        attachmentBytes = attachment?.bytes,
                        attachmentMimeType = attachment?.mimeType,
                        requestAudio = requestAudio,
                        onAudioChunk = { pcm ->
                            if (_voiceEngineMode.value == com.example.voice.VoiceEngineMode.GEMINI_ORION && _isAutoSpeakEnabled.value) {
                                streamingVoicePlayer.feedPcmChunk(pcm, System.currentTimeMillis())
                            }
                        }
                    ).collect { chunk ->
                        fullResponse.append(chunk)
                        _streamingText.value = fullResponse.toString()
                    }

                    val finalAnswer = fullResponse.toString().trim()
                    if (finalAnswer.isNotEmpty() && _currentChatId.value == resolvedChatId) {
                        val aiMsg = ChatMessage(
                            chatId = resolvedChatId,
                            sender = "gemini",
                            content = finalAnswer,
                            timestamp = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
                        )
                        val aiMsgId = repository.insertMessage(aiMsg)
                        if (aiMsgId > 0) {
                            val savedAiMsg = aiMsg.copy(id = aiMsgId)
                            bufferManager.appendMessage(savedAiMsg)
                            syncMessageToCloudIfSignedIn(savedAiMsg)
                        }
                        ReplyNotificationHelper.notifyModelReplyIfBackgroundedOrScreenOff(
                            context = getApplication(),
                            notifySettingEnabled = _notifyActiveChatReplies.value,
                            isAppInForeground = isAppInForeground,
                            chatId = resolvedChatId,
                            chatTitle = chat?.title ?: "Gemini Chat",
                            replyText = finalAnswer
                        )
                    }
                } catch (e: Exception) {
                    _errorMessage.value = e.localizedMessage ?: "Failed to generate response."
                } finally {
                    _isGenerating.value = false
                    _streamingText.value = null
                    // Post-turn mic behavior:
                    // - Hands-Free ON: Re-initialize and restart wake-word listening once audio playback has finished
                    // - Hands-Free OFF (Walkie-Talkie mode): Stay stopped until manually triggered
                    if (_isHandsFreeActive.value && !streamingVoicePlayer.isPlaying.value) {
                        wakeWordManager.restartWakeListeningAfterTurn()
                    }
                }
            }
        }
    }

    fun setDefaultPersistenceEnabled(enabled: Boolean) {
        _defaultPersistenceEnabled.value = enabled
        appPrefs.edit().putBoolean("default_persistence", enabled).apply()
    }

    fun setPreserveCacheAcrossSessions(enabled: Boolean) {
        _preserveCacheAcrossSessions.value = enabled
        appPrefs.edit().putBoolean("preserve_cache_across_sessions", enabled).apply()
    }

    fun setHeartbeatPingOnlyMode(enabled: Boolean) {
        _heartbeatPingOnlyMode.value = enabled
        appPrefs.edit().putBoolean("heartbeat_ping_only_mode", enabled).apply()
    }

    fun setDictateTrigger(trigger: String) {
        val trimmed = trigger.trim().ifBlank { com.example.voice.AudioCommandSanitizer.DEFAULT_DICTATE_TRIGGER }
        _dictateTrigger.value = trimmed
        appPrefs.edit().putString("dictate_trigger", trimmed).apply()
        wakeWordManager.dictateTrigger = trimmed
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.upsertSystemTrigger(
                role = WakeWord.ROLE_DICTATE,
                phrase = trimmed,
                sensitivity = _wakeWordSensitivity.value
            )
        }
    }

    fun setSendTrigger(trigger: String) {
        val trimmed = trigger.trim().ifBlank { com.example.voice.AudioCommandSanitizer.DEFAULT_SEND_TRIGGER }
        _sendTrigger.value = trimmed
        appPrefs.edit().putString("send_trigger", trimmed).apply()
        wakeWordManager.sendTrigger = trimmed
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.upsertSystemTrigger(
                role = WakeWord.ROLE_SEND,
                phrase = trimmed,
                sensitivity = _wakeWordSensitivity.value
            )
        }
    }

    fun setHeartbeatTrigger(trigger: String) {
        val trimmed = trigger.trim().ifBlank { com.example.voice.AudioCommandSanitizer.DEFAULT_HEARTBEAT_TRIGGER }
        _heartbeatTrigger.value = trimmed
        appPrefs.edit().putString("heartbeat_trigger", trimmed).apply()
        wakeWordManager.heartbeatTrigger = trimmed
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.upsertSystemTrigger(
                role = WakeWord.ROLE_HEARTBEAT,
                phrase = trimmed,
                sensitivity = _wakeWordSensitivity.value
            )
        }
    }

    fun resetTriggersToDefaults() {
        setDictateTrigger(com.example.voice.AudioCommandSanitizer.DEFAULT_DICTATE_TRIGGER)
        setSendTrigger(com.example.voice.AudioCommandSanitizer.DEFAULT_SEND_TRIGGER)
        setHeartbeatTrigger(com.example.voice.AudioCommandSanitizer.DEFAULT_HEARTBEAT_TRIGGER)
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.resetSystemTriggersToDefaults(_wakeWordSensitivity.value)
        }
    }

    fun addWakeWord(
        phrase: String,
        triggerType: TriggerType = TriggerType.CHAT,
        metadata: String? = null,
        chatId: Long? = _currentChatId.value
    ) {
        val trimmed = phrase.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            if (triggerType == TriggerType.CHAT && chatId != null && chatId > 0L) {
                val chat = repository.getChatByIdSync(chatId)
                    ?: allChats.value.firstOrNull { it.id == chatId }
                val title = chat?.title ?: (metadata ?: "Gemini Chat")
                val id = wakeWordRepository.addCustomChatWakeWord(
                    chatId = chatId,
                    chatTitle = title,
                    phrase = trimmed,
                    sensitivity = _wakeWordSensitivity.value,
                    metadata = metadata ?: title
                )
                syncWakeWordToCloudIfSignedIn(id)
            } else {
                val id = wakeWordRepository.addWakeWord(
                    phrase = trimmed,
                    triggerType = triggerType,
                    metadata = metadata,
                    chatId = chatId,
                    sensitivity = _wakeWordSensitivity.value
                )
                syncWakeWordToCloudIfSignedIn(id)
            }
        }
    }

    fun removeWakeWord(wakeWord: WakeWord) {
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.removeWakeWord(wakeWord)
            _currentUserUid.value?.let { uid ->
                runCatching { firebaseSyncRepository?.deleteWakeWord(uid, wakeWord.id) }
            }
        }
    }

    fun removeWakeWordById(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.removeWakeWordById(id)
            _currentUserUid.value?.let { uid ->
                runCatching { firebaseSyncRepository?.deleteWakeWord(uid, id) }
            }
        }
    }

    fun removeWakeWordByPhrase(phrase: String) {
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.removeWakeWordByPhrase(phrase)
        }
    }

    fun addCustomWakeWord(
        chatId: Long,
        phrase: String,
        sensitivity: Float = _wakeWordSensitivity.value
    ) {
        val trimmed = phrase.trim()
        if (trimmed.isBlank()) return
        val clampedSensitivity = sensitivity.coerceIn(0.50f, 0.98f)
        viewModelScope.launch(Dispatchers.IO) {
            val chat = repository.getChatByIdSync(chatId)
                ?: allChats.value.firstOrNull { it.id == chatId }
            val title = chat?.title ?: "Gemini Chat"
            val id = wakeWordRepository.addCustomChatWakeWord(
                chatId = chatId,
                chatTitle = title,
                phrase = trimmed,
                sensitivity = clampedSensitivity
            )
            syncWakeWordToCloudIfSignedIn(id)
        }
    }

    fun addCustomWakeWordWithNewChat(
        phrase: String,
        newChatTitle: String,
        sensitivity: Float = _wakeWordSensitivity.value,
        onCreated: ((Long) -> Unit)? = null
    ) {
        val trimmedPhrase = phrase.trim()
        if (trimmedPhrase.isBlank()) return
        val resolvedTitle = newChatTitle.trim().ifBlank { "$trimmedPhrase Chat" }
        val clampedSensitivity = sensitivity.coerceIn(0.50f, 0.98f)
        viewModelScope.launch(Dispatchers.IO) {
            val (createdChatId, wakeWordId) = wakeWordRepository.createChatAndBindWakeWord(
                chatTitle = resolvedTitle,
                phrase = trimmedPhrase,
                sensitivity = clampedSensitivity
            )
            if (createdChatId > 0L) {
                syncChatToCloudIfSignedIn(createdChatId)
            }
            if (wakeWordId > 0L) {
                syncWakeWordToCloudIfSignedIn(wakeWordId)
            }
            if (createdChatId > 0L && onCreated != null) {
                withContext(Dispatchers.Main) {
                    onCreated.invoke(createdChatId)
                }
            }
        }
    }

    fun setIndividualWakeWordSensitivity(id: Long, sensitivity: Float) {
        val clamped = sensitivity.coerceIn(0.50f, 0.98f)
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.updateWakeWordSensitivity(id, clamped)
            wakeWordManager.updateWakeWordItemSensitivity(id, clamped)
            syncWakeWordToCloudIfSignedIn(id)
        }
    }

    fun setIndividualWakeWordSensitivityPercent(id: Long, sensitivityPercent: Int) {
        val clampedInt = sensitivityPercent.coerceIn(0, 100)
        val floatVal = WakeWord.fromSensitivityInt(clampedInt)
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.updateWakeWordSensitivity(id, clampedInt)
            wakeWordManager.updateWakeWordItemSensitivity(id, floatVal)
            syncWakeWordToCloudIfSignedIn(id)
        }
    }

    fun setIndividualWakeWordSensitivityLevel(id: Long, level: com.example.voice.WakeSensitivityLevel) {
        setIndividualWakeWordSensitivity(id, level.sensitivityValue)
    }

    fun reassignWakeWordToChat(wakeWordId: Long, targetChatId: Long) {
        if (targetChatId <= 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            val targetChat = repository.getChatByIdSync(targetChatId)
                ?: allChats.value.firstOrNull { it.id == targetChatId }
            val resolvedTitle = targetChat?.title ?: "Gemini Chat"
            wakeWordRepository.updateWakeWordTargetChatId(wakeWordId, targetChatId, resolvedTitle)
            wakeWordManager.updateWakeWordChatAssociation(wakeWordId, targetChatId, resolvedTitle)
            syncWakeWordToCloudIfSignedIn(wakeWordId)
            syncChatToCloudIfSignedIn(targetChatId)
        }
    }

    fun reassignWakeWordToNewChat(
        wakeWordId: Long,
        newChatTitle: String,
        onCreated: ((Long) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val newChatId = wakeWordRepository.reassignWakeWordToNewChat(wakeWordId, newChatTitle)
            if (newChatId > 0L) {
                wakeWordManager.retrieveWakeWordConfigurations()
                syncChatToCloudIfSignedIn(newChatId)
                syncWakeWordToCloudIfSignedIn(wakeWordId)
                if (onCreated != null) {
                    withContext(Dispatchers.Main) {
                        onCreated.invoke(newChatId)
                    }
                }
            }
        }
    }

    fun updateConfiguredWakeWordPhrase(id: Long, newPhrase: String) {
        val trimmed = newPhrase.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.updateWakeWordPhrase(id, trimmed)
            syncWakeWordToCloudIfSignedIn(id)
        }
    }

    fun toggleConfiguredWakeWord(id: Long, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.setWakeWordEnabled(id, enabled)
            syncWakeWordToCloudIfSignedIn(id)
        }
    }

    fun deleteConfiguredWakeWord(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.removeWakeWordById(id)
            _currentUserUid.value?.let { uid ->
                runCatching { firebaseSyncRepository?.deleteWakeWord(uid, id) }
            }
        }
    }

    fun toggleHandsFreeMode(enabled: Boolean? = null) {
        val next = enabled ?: !_isHandsFreeActive.value
        if (next && !checkRecordAudioPermission()) {
            pendingWakeStartAfterPermission = true
            _requiresAudioPermission.value = true
            wakeWordManager.setStatusMessage("Microphone permission required for hands-free wake-word listening")
        }
        _isHandsFreeActive.value = next
        appPrefs.edit().putBoolean("hands_free_active", next).apply()
        if (next && _currentChatId.value == null) {
            viewModelScope.launch {
                val resolvedId = resolveOrEnsureTargetChatId(null)
                if (_currentChatId.value == null) {
                    selectChat(resolvedId)
                }
            }
        }
        if (!next) {
            stopSpeechRecognizerInternal()
        }
        wakeWordManager.setHandsFreeMode(next)
    }

    fun updateCurrentDraftText(text: String) {
        _currentDraftText.value = text
    }

    fun triggerManualHeartbeatPing() {
        _lastHeartbeatPingTime.value = System.currentTimeMillis()
        com.example.coupler.ChromeBridgeSocketManager.recordHeartbeatPing()
        com.example.heartbeat.HeartbeatAlarmReceiver.scheduleHeartbeatPing(getApplication(), 0L)
        com.example.voice.AudioCommandSanitizer.playHeartbeatConfirmation(getApplication())
    }

    fun onAppBackgrounded() {
        isAppInForeground = false
        if ((_isHandsFreeActive.value || wakeWordManager.isListening.value) && !_isSleepMode.value) {
            com.example.voice.WakeWordVoiceService.startServiceSafely(
                context = getApplication(),
                action = com.example.voice.WakeWordVoiceService.ACTION_START_VOICE_SERVICE
            )
        }
        if (_heartbeatPingOnlyMode.value) {
            ChromeBridgeSocketManager.enterHeartbeatMode(getApplication())
        }
    }

    fun onAppForegrounded() {
        isAppInForeground = true
        networkStatusTracker.refresh()
        if (_heartbeatPingOnlyMode.value) {
            ChromeBridgeSocketManager.exitHeartbeatMode(getApplication())
        }
    }

    fun scheduleChatTimerAlarm(chatId: Long, delaySeconds: Long, chatTitle: String, alarmMessage: String? = null) {
        com.example.heartbeat.HeartbeatAlarmReceiver.scheduleChatTimerAlarm(
            context = getApplication(),
            chatId = chatId,
            chatTitle = chatTitle,
            delaySeconds = delaySeconds,
            alarmMessage = alarmMessage
        )
    }

    fun cancelChatTimerAlarm(chatId: Long) {
        com.example.heartbeat.HeartbeatAlarmReceiver.cancelChatTimerAlarm(getApplication(), chatId)
    }

    fun toggleChatPersistence(chatId: Long) {
        viewModelScope.launch {
            val chat = repository.getChatByIdSync(chatId) ?: return@launch
            val updated = chat.copy(isPersistent = !chat.isPersistent)
            repository.saveChat(updated)
        }
    }

    fun addCustomPrompt(title: String, promptText: String, iconName: String = "Sparkles") {
        promptRepository.addCustomPrompt(title, promptText, iconName)
    }

    fun deleteCustomPrompt(id: String) {
        promptRepository.deletePrompt(id)
    }

    suspend fun findConflictingWakeWordSession(wakeWord: String, excludeChatId: Long = -1L): ChatSession? {
        return repository.findConflictingWakeWordSession(wakeWord, excludeChatId)
    }

    suspend fun isWakeWordUnique(wakeWord: String, excludeChatId: Long = -1L): Boolean {
        return repository.isWakeWordUnique(wakeWord, excludeChatId)
    }

    fun saveChat(chat: ChatSession, onComplete: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            val conflict = repository.findConflictingWakeWordSession(
                wakeWord = chat.wakeWord,
                excludeChatId = if (chat.id > 0L) chat.id else -1L
            )
            if (conflict != null) {
                _errorMessage.value =
                    "Wake word \"${chat.wakeWord.trim()}\" is already used by \"${conflict.title}\". Each chat session must have a unique wake word."
                onComplete?.invoke(-1L)
                return@launch
            }
            val id = repository.saveChat(chat)
            if (id > 0L) {
                wakeWordRepository.syncChatWakeWordFromSession(
                    chatId = id,
                    chatTitle = chat.title,
                    wakeWord = chat.wakeWord,
                    sensitivity = _wakeWordSensitivity.value
                )
                syncChatToCloudIfSignedIn(id)
            }
            onComplete?.invoke(id)
        }
    }

    fun updateChatTheme(chatId: Long, theme: AppTheme) {
        viewModelScope.launch {
            repository.updateChatTheme(chatId, theme)
        }
    }

    fun importParsedChat(
        session: ChatSession,
        messages: List<ChatMessage>,
        onComplete: ((Long) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val newChatId = repository.saveChat(session)
            for (msg in messages) {
                repository.insertMessage(
                    msg.copy(chatId = newChatId)
                )
            }
            // Pre-warm the buffer for this chat and select it
            selectChat(newChatId)
            onComplete?.invoke(newChatId)
        }
    }

    fun recordCouplerHandoff() {
        _lastCouplerHandoffTime.value = System.currentTimeMillis()
    }

    /**
     * Piggyback load: Appends incoming Gemini response turns to the currently active chat
     * without duplicating existing messages.
     */
    fun appendPiggybackMessages(
        chatId: Long,
        incomingMessages: List<ChatMessage>,
        onComplete: ((Int) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val existing = rawCurrentMessages.value
            val existingFingerprints = existing.map { "${it.sender}:${it.content.trim()}" }.toSet()

            var insertedCount = 0
            var baseTimestamp = System.currentTimeMillis()

            for (msg in incomingMessages) {
                val fingerprint = "${msg.sender}:${msg.content.trim()}"
                if (!existingFingerprints.contains(fingerprint)) {
                    val ts = if (msg.timestamp > 0) msg.timestamp else baseTimestamp++
                    val newMsg = ChatMessage(
                        chatId = chatId,
                        sender = msg.sender,
                        content = msg.content,
                        timestamp = ts,
                        triggeredByWakeWord = false
                    )
                    repository.insertMessage(newMsg)
                    bufferManager.appendMessage(newMsg)
                    insertedCount++
                }
            }
            onComplete?.invoke(insertedCount)
        }
    }

    /**
     * Creates a new chat session from a Coupler payload or shared transcript.
     */
    fun createCoupledChat(
        title: String,
        wakeWord: String,
        messages: List<ChatMessage>,
        onComplete: ((Long) -> Unit)? = null
    ) {
        val session = ChatSession(
            title = title,
            wakeWord = wakeWord,
            systemInstruction = "You are Gemini, continuing this coupled project session.",
            themeColorHex = "#4285F4",
            isPersistent = true
        )
        importParsedChat(session, messages, onComplete)
    }

    /**
     * Paced chunked import to prevent choking on long threads (100+ turns).
     */
    fun importParsedChatChunked(
        session: ChatSession,
        messages: List<ChatMessage>,
        pacing: com.example.importer.ImportPacing = com.example.importer.ImportPacing.SAFE_SLOW,
        onProgress: (com.example.importer.ImportProgress) -> Unit,
        onComplete: (Long) -> Unit
    ) {
        viewModelScope.launch {
            val newChatId = repository.saveChat(session)
            chunkedImporter.importInChunks(messages, pacing) { chunk ->
                for (msg in chunk) {
                    repository.insertMessage(msg.copy(chatId = newChatId))
                }
            }.collect { progress ->
                onProgress(progress)
                if (progress.isComplete) {
                    selectChat(newChatId)
                    onComplete(newChatId)
                }
            }
        }
    }

    fun setDefaultWakeChat(chatId: Long) {
        viewModelScope.launch {
            repository.setDefaultWakeChat(chatId)
        }
    }

    fun deleteChat(chatId: Long) {
        viewModelScope.launch {
            if (_currentChatId.value == chatId) {
                activeGenerationJob?.cancel()
                activeGenerationJob = null
                _isGenerating.value = false
                _streamingText.value = null
                _errorMessage.value = null
                streamingVoicePlayer.stop()
                bufferManager.detachChat()
                _currentChatId.value = null
            }
            // Lifecycle Hook: Force reset and clear ChromeBridgeSocketManager when chat is deleted
            ChromeBridgeSocketManager.forceResetAndClear(getApplication(), preserveDiskCache = _preserveCacheAcrossSessions.value)
            bufferManager.clearDiskCache(chatId)
            deleteWakeWordSamples(chatId)
            wakeWordRepository.deleteWakeWordsForChat(chatId)
            repository.deleteChat(chatId)
            _currentUserUid.value?.let { uid ->
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching { firebaseSyncRepository?.deleteChat(uid, chatId) }
                }
            }
        }
    }

    fun clearChatHistory(chatId: Long) {
        viewModelScope.launch {
            repository.clearChatMessages(chatId)
            bufferManager.clearDiskCache(chatId)
            _streamingText.value = null
        }
    }

    // Wake Word Snippet Recording (MediaRecorder API) & Enrollment
    val isWakeWordEnrollmentRecording: StateFlow<Boolean> = wakeWordManager.isEnrollmentRecording

    fun startRecordingWakeSnippet(chatId: Long, slotIndex: Int): Boolean {
        if (!ensureMicrophonePermission(startWakeListeningWhenGranted = false)) {
            _activeRecordingSlot.value = null
            return false
        }
        // Stop any active snippet playback preview, SpeechRecognizer, and WakeWordManager mic first
        snippetPlayer.stop()
        destroySpeechRecognizerInternal()
        wakeWordManager.completeChatTurnAndReleaseMic()

        val outputFile = wakeWordStorageManager.getSnippetFile(chatId, slotIndex)
        val res = audioRecorder.start(outputFile)
        return if (res.isSuccess) {
            activeRecordingChatId = chatId
            _activeRecordingSlot.value = slotIndex
            _snippetRecordingDurationMs.value = 0L
            startRecordingTimer()
            true
        } else {
            _activeRecordingSlot.value = null
            if (_isHandsFreeActive.value) {
                wakeWordManager.restartWakeListeningAfterTurn()
                scheduleSpeechRecognizerRestart(delayMs = 250L)
            }
            false
        }
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            val limitSec = _autoStopLimitSec.value
            val limitMs = if (limitSec > 0) limitSec * 1000L else 0L
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                _snippetRecordingDurationMs.value = elapsed

                if (limitMs > 0L && elapsed >= limitMs) {
                    val slot = _activeRecordingSlot.value
                    if (slot != null) {
                        stopRecordingWakeSnippet(activeRecordingChatId, slot)
                    }
                    break
                }
                kotlinx.coroutines.delay(100)
            }
        }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        _snippetRecordingDurationMs.value = 0L
    }

    fun stopRecordingWakeSnippet(chatId: Long, slotIndex: Int) {
        _activeRecordingSlot.value = null
        stopRecordingTimer()
        if (audioRecorder.lastRecordedPcmBytes.isEmpty()) {
            val fallbackBufferedPcm = wakeWordManager.getCapturedSnippetPcm()
            if (fallbackBufferedPcm.isNotEmpty()) {
                audioRecorder.appendPcmBytes(fallbackBufferedPcm, fallbackBufferedPcm.size)
            }
        }
        val res = audioRecorder.stop()
        val recordedPcm = audioRecorder.lastRecordedPcmBytes
        if (recordedPcm.isNotEmpty()) {
            wakeWordManager.recordSnippetPcmBytes(recordedPcm, recordedPcm.size)
        }
        val file = res.getOrNull()
        if (file != null && file.exists() && file.length() > 0) {
            viewModelScope.launch(Dispatchers.IO) {
                val meta = wakeWordStorageManager.extractMetadata(file)
                val chatWakeWords = wakeWordRepository.getChatWakeWordsForChatSync(chatId)
                val primaryWake = chatWakeWords.firstOrNull()
                val chatSession = repository.getChatByIdSync(chatId)
                val resolvedPhrase = primaryWake?.phrase ?: chatSession?.wakeWord ?: "Hey Gemini"
                val sample = com.example.data.model.WakeWordSample(
                    chatId = chatId,
                    wakeWordId = primaryWake?.id,
                    sampleUuid = java.util.UUID.randomUUID().toString(),
                    wakePhrase = resolvedPhrase,
                    samplePath = file.absolutePath,
                    recordedAt = System.currentTimeMillis(),
                    durationMs = meta.durationMs,
                    sizeBytes = meta.sizeBytes,
                    mimeType = meta.mimeType
                )
                repository.insertWakeWordSample(sample)
                loadWakeWordSamples(chatId)
                _wakeWordStorageBytes.value = wakeWordStorageManager.getTotalStorageBytes()
            }
        }
        if (_isHandsFreeActive.value && !_isSleepMode.value) {
            wakeWordManager.restartWakeListeningAfterTurn()
            scheduleSpeechRecognizerRestart(delayMs = 250L)
        }
    }

    /**
     * Saves a custom wake word associated with [chatId] and links all recorded audio samples
     * for [chatId] with the wake word's unique database ID for the keyword detection loop.
     */
    fun saveCustomWakeWordWithSamples(
        phrase: String,
        chatId: Long,
        sensitivity: Float = _wakeWordSensitivity.value,
        onComplete: ((Long) -> Unit)? = null
    ) {
        val cleanPhrase = phrase.trim()
        if (cleanPhrase.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val targetChatId = resolveOrEnsureTargetChatId(chatId)
            val chat = repository.getChatByIdSync(targetChatId)
            val chatTitle = chat?.title ?: "Gemini Chat"
            val wakeWordId = wakeWordRepository.addCustomChatWakeWord(
                chatId = targetChatId,
                chatTitle = chatTitle,
                phrase = cleanPhrase,
                sensitivity = sensitivity.coerceIn(0.50f, 0.98f),
                metadata = chatTitle
            )
            if (wakeWordId > 0L) {
                repository.associateSamplesWithWakeWord(
                    chatId = targetChatId,
                    wakeWordId = wakeWordId,
                    wakePhrase = cleanPhrase
                )
                loadWakeWordSamples(targetChatId)
                syncWakeWordToCloudIfSignedIn(wakeWordId)
                syncChatToCloudIfSignedIn(targetChatId)
            }
            withContext(Dispatchers.Main) {
                onComplete?.invoke(wakeWordId)
            }
        }
    }

    fun cancelRecordingWakeSnippet() {
        audioRecorder.cancel()
        _activeRecordingSlot.value = null
        stopRecordingTimer()
        if (_isHandsFreeActive.value) {
            wakeWordManager.restartWakeListeningAfterTurn()
            scheduleSpeechRecognizerRestart(delayMs = 250L)
        }
    }

    fun playSnippetPreview(filePath: String) {
        val file = java.io.File(filePath)
        if (file.exists() && file.length() > 0L) {
            snippetPlayer.play(file)
        } else {
            val fallbackWav = wakeWordManager.getCapturedSnippetWav()
            if (fallbackWav.isNotEmpty()) {
                snippetPlayer.playPcmOrWavBytes(fallbackWav, WakeWordManager.SAMPLE_RATE_HZ, filePath)
            }
        }
    }

    fun playCapturedSnippetTestBuffer() {
        val wavBytes = wakeWordManager.getCapturedSnippetWav()
        if (wavBytes.isNotEmpty()) {
            snippetPlayer.playPcmOrWavBytes(wavBytes, WakeWordManager.SAMPLE_RATE_HZ, "captured_snippet_buffer")
        }
    }

    fun pauseSnippetPreview() {
        snippetPlayer.pause()
    }

    fun resumeSnippetPreview() {
        snippetPlayer.resume()
    }

    fun stopSnippetPreview() {
        snippetPlayer.stop()
    }

    fun playAllWakeSnippets(chatId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val samples = repository.getSamplesForChat(chatId).sortedBy { it.sampleIndex }
            val files = samples.map { java.io.File(it.samplePath) }
            snippetPlayer.playSequence(files)
        }
    }

    fun deleteWakeWordSampleById(chatId: Long, sampleId: Long, filePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (snippetPlayer.currentPlayingPath.value == filePath) {
                snippetPlayer.stop()
            }
            wakeWordStorageManager.deleteFile(filePath)
            repository.deleteSampleById(sampleId)
            loadWakeWordSamples(chatId)
            _wakeWordStorageBytes.value = wakeWordStorageManager.getTotalStorageBytes()
        }
    }

    fun pruneOrphanedWakeSnippets() {
        viewModelScope.launch(Dispatchers.IO) {
            val chats = allChats.value
            val activePaths = mutableSetOf<String>()
            for (chat in chats) {
                val samples = repository.getSamplesForChat(chat.id)
                for (s in samples) {
                    activePaths.add(s.samplePath)
                }
            }
            wakeWordStorageManager.pruneOrphanedFiles(activePaths)
            _wakeWordStorageBytes.value = wakeWordStorageManager.getTotalStorageBytes()
        }
    }

    fun startWakeWordEnrollment(onSampleCaptured: (ShortArray) -> Unit) {
        wakeWordManager.startEnrollmentCapture(onSampleCaptured)
    }

    fun stopWakeWordEnrollment() {
        wakeWordManager.stopEnrollmentCapture()
    }

    fun saveWakeWordSample(chatId: Long, sampleIndex: Int, pcm: ShortArray) {
        wakeWordManager.recordSnippetShorts(pcm, pcm.size)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = "wake_word_${chatId}_${sampleIndex}.pcm"
                val file = java.io.File(getApplication<Application>().filesDir, fileName)
                file.outputStream().use { out ->
                    val bytes = ByteArray(pcm.size * 2)
                    for (i in pcm.indices) {
                        val s = pcm[i].toInt()
                        bytes[i * 2] = (s and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    out.write(bytes)
                }

                val sample = com.example.data.model.WakeWordSample(
                    chatId = chatId,
                    samplePath = file.absolutePath,
                    recordedAt = System.currentTimeMillis(),
                    durationMs = (pcm.size / 16).toLong().coerceAtLeast(1L),
                    sizeBytes = (pcm.size * 2).toLong(),
                    mimeType = "audio/pcm"
                )
                repository.insertWakeWordSample(sample)

                // Refresh WakeWordManager cache
                loadWakeWordSamples(chatId)
                _wakeWordStorageBytes.value = wakeWordStorageManager.getTotalStorageBytes()
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to save wake word sample", e)
            }
        }
    }

    fun deleteWakeWordSamples(chatId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            snippetPlayer.stop()
            wakeWordStorageManager.deleteAllSnippetsForChat(chatId)
            repository.deleteSamplesForChat(chatId)
            wakeWordManager.updateEnrollmentSamples(chatId, emptyList())
            _wakeWordStorageBytes.value = wakeWordStorageManager.getTotalStorageBytes()
        }
    }

    fun loadWakeWordSamples(chatId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val samples = repository.getSamplesForChat(chatId)
            val pcmList = mutableListOf<ShortArray>()
            for (s in samples) {
                try {
                    val file = java.io.File(s.samplePath)
                    val lower = s.samplePath.lowercase(java.util.Locale.US)
                    if (file.exists() && (lower.endsWith(".pcm") || lower.endsWith(".wav"))) {
                        val rawPcmBytes = WakeWordManager.extractPcmFromWavOrRaw(file.readBytes())
                        if (rawPcmBytes.size >= 2) {
                            wakeWordManager.recordSnippetPcmBytes(rawPcmBytes, rawPcmBytes.size)
                            val pcm = ShortArray(rawPcmBytes.size / 2)
                            for (i in pcm.indices) {
                                pcm[i] = ((rawPcmBytes[i * 2].toInt() and 0xFF) or ((rawPcmBytes[i * 2 + 1].toInt() and 0xFF) shl 8)).toShort()
                            }
                            pcmList.add(pcm)
                        }
                    }
                } catch (_: Exception) {}
            }
            wakeWordManager.updateEnrollmentSamples(chatId, pcmList)
        }
    }

    fun getSamplesForChatFlow(chatId: Long) = repository.getSamplesForChatFlow(chatId)

    fun deleteMessage(messageId: Long) {
        viewModelScope.launch {
            repository.deleteMessage(messageId)
            _currentUserUid.value?.let { uid ->
                withContext(Dispatchers.IO) {
                    runCatching { firebaseSyncRepository?.deleteMessage(uid, messageId) }
                }
            }
        }
    }

    fun speakMessage(text: String) {
        if (com.example.voice.AudioCommandSanitizer.isSystemOrCommandStatusString(
                text = text,
                dictateTrigger = _dictateTrigger.value,
                sendTrigger = _sendTrigger.value,
                heartbeatTrigger = _heartbeatTrigger.value
            )
        ) {
            return
        }
        // StreamingVoicePlayer strictly plays incoming model responses from Gemini only.
    }

    fun stopSpeaking() {
        streamingVoicePlayer.stop()
    }

    fun setVoiceEngineMode(mode: com.example.voice.VoiceEngineMode) {
        _voiceEngineMode.value = mode
        appPrefs.edit().putString("voice_engine_mode", mode.name).apply()
        if (mode == com.example.voice.VoiceEngineMode.MUTED) {
            streamingVoicePlayer.stop()
        }
    }

    fun playOrionTestSample() {
        // StreamingVoicePlayer strictly plays incoming model responses from Gemini only.
        streamingVoicePlayer.stop()
    }

    private fun startTickerIfNeeded() {
        if (timerTickerJob?.isActive == true) return
        timerTickerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                val openChatId = _currentChatId.value
                // When chat is open, track elapsed time continuously as long as chat is open (not necessarily active)
                if (openChatId != null && _isOpenChatTimerRunning.value) {
                    _openChatTimerSeconds.update { it + 1L }
                    val currentSecs = _openChatTimerSeconds.value
                    _liveTrackedSeconds.update { it + (openChatId to currentSecs) }
                } else {
                    val activeIds = _activeTimerChatIds.value
                    if (activeIds.isNotEmpty()) {
                        _liveTrackedSeconds.update { currentMap ->
                            val updated = currentMap.toMutableMap()
                            for (id in activeIds) {
                                val prev = updated[id] ?: 0L
                                updated[id] = prev + 1L
                            }
                            updated
                        }
                    }
                }
            }
        }
    }

    /**
     * Toggles stopwatch timer for the currently open chat ON or OFF.
     */
    fun toggleOpenChatTimer() {
        _isOpenChatTimerRunning.value = !_isOpenChatTimerRunning.value
        if (_isOpenChatTimerRunning.value) {
            _currentChatId.value?.let { id ->
                _activeTimerChatIds.update { it + id }
            }
            startTickerIfNeeded()
        }
    }

    /**
     * Resets open chat stopwatch timer back to 0 for the open session.
     */
    fun resetOpenChatTimer() {
        _openChatTimerSeconds.value = 0L
        _currentChatId.value?.let { id ->
            _liveTrackedSeconds.update { it + (id to 0L) }
        }
    }

    /**
     * Toggles the Usage Limits vs Cache Bars comparison UI element.
     */
    fun toggleUsageLimitsComparison(enabled: Boolean? = null) {
        _showUsageLimitsComparison.value = enabled ?: !_showUsageLimitsComparison.value
    }

    /**
     * Toggles stopwatch timer for the specified chat ON or OFF.
     */
    fun toggleChatTimer(chatId: Long) {
        val currentActive = _activeTimerChatIds.value
        if (currentActive.contains(chatId)) {
            // Turn OFF (pause)
            _activeTimerChatIds.value = currentActive - chatId
            val currentSecs = _liveTrackedSeconds.value[chatId] ?: 0L
            viewModelScope.launch {
                repository.updateTrackedSeconds(chatId, currentSecs)
            }
        } else {
            // Turn ON (start/resume)
            if (!_liveTrackedSeconds.value.containsKey(chatId)) {
                val chat = allChats.value.firstOrNull { it.id == chatId }
                val initial = chat?.totalTrackedSeconds ?: 0L
                _liveTrackedSeconds.update { it + (chatId to initial) }
            }
            _activeTimerChatIds.value = currentActive + chatId
            startTickerIfNeeded()
        }
    }

    /**
     * Resets the accumulated stopwatch time for the specified chat to 0 seconds.
     */
    fun resetChatTimer(chatId: Long) {
        _liveTrackedSeconds.update { it + (chatId to 0L) }
        viewModelScope.launch {
            repository.updateTrackedSeconds(chatId, 0L)
        }
    }

    fun dismissScreenshotBanner() {
        _screenshotBanner.value = null
    }

    fun stageImage(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = GeminiCouplerBridge.stageImage(getApplication(), uri)
            if (result != null) {
                _screenshotBanner.value = GeminiCouplerBridge.stagedImageInfo.value
            }
        }
    }

    fun stageImageBytes(bytes: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = GeminiCouplerBridge.stageImageBytes(getApplication(), bytes)
            if (result != null) {
                _screenshotBanner.value = GeminiCouplerBridge.stagedImageInfo.value
            }
        }
    }

    fun clearStagedImage() {
        GeminiCouplerBridge.clearStagedImage(getApplication())
        _screenshotBanner.value = null
    }

    fun checkForRecentScreenshot() {
        screenshotWatcher.checkForRecentScreenshot()
    }

    fun dispatchScreenshotToCoupler(chatId: Long, promptText: String) {
        val chat = allChats.value.firstOrNull { it.id == chatId } ?: return
        val messages = rawCurrentMessages.value
        val imageUri = stagedImageUri.value

        GeminiCouplerBridge.launchGeminiAppHandoff(
            context = getApplication(),
            chat = chat,
            messages = messages,
            activeTaskPrompt = promptText.ifBlank { "Analyze this attached screenshot and provide recommendations." },
            imageUri = imageUri
        )

        if (promptText.isNotBlank()) {
            viewModelScope.launch {
                val userMsg = ChatMessage(
                    chatId = chatId,
                    sender = "user",
                    content = "📸 [Attached Screenshot]\n$promptText",
                    timestamp = System.currentTimeMillis()
                )
                repository.insertMessage(userMsg)
            }
        }
    }

    fun importChatArchive(uri: Uri, targetChatId: Long? = null) {
        try {
            FileProcessingService.startServiceForUri(
                context = getApplication(),
                uri = uri,
                targetChatId = targetChatId,
                action = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
            )
        } catch (_: Exception) {
            viewModelScope.launch(Dispatchers.IO) {
                val createdChatId = chatImportWorker.importChatArchive(uri, targetChatId)
                if (createdChatId != null && createdChatId > 0L) {
                    withContext(Dispatchers.Main) {
                        selectChat(createdChatId)
                    }
                }
            }
        }
    }

    fun cancelChatImport() {
        chatImportWorker.cancelImport()
        try {
            val app = getApplication<Application>()
            app.stopService(android.content.Intent(app, FileProcessingService::class.java))
        } catch (_: Exception) {}
    }

    fun cleanShutdown(activity: android.app.Activity?) {
        try {
            timerTickerJob?.cancel()
            destroySpeechRecognizerInternal()
            wakeWordManager.stopContinuousListening()
            wakeWordManager.destroy()
            streamingVoicePlayer.stop()
            streamingVoicePlayer.release()
            localSyncServer.stop()
            screenshotWatcher.stopWatching()
            networkStatusTracker.stopTracking()
            activeGenerationJob?.cancel()
        } catch (_: Exception) {}
        activity?.finishAffinity()
    }

    fun exportCurrentChatAsPlainText(): String {
        val chat = currentChat.value ?: return ""
        val messages = rawCurrentMessages.value
        val sb = java.lang.StringBuilder()
        sb.appendLine("=========================================")
        sb.appendLine("Chat: ${chat.title}")
        sb.appendLine("Wake Word: \"${chat.wakeWord}\"")
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        sb.appendLine("Exported: ${dateFormat.format(java.util.Date())}")
        sb.appendLine("=========================================\n")
        for (msg in messages) {
            val role = if (msg.sender == "user") "User" else "Gemini"
            val time = dateFormat.format(java.util.Date(msg.timestamp))
            sb.appendLine("[$time] $role:")
            sb.appendLine(msg.content)
            sb.appendLine()
        }
        return sb.toString()
    }

    override fun onCleared() {
        super.onCleared()
        timerTickerJob?.cancel()
        destroySpeechRecognizerInternal()
        localSyncServer.stop()
        wakeWordManager.destroy()
        audioRecorder.release()
        snippetPlayer.release()
        streamingVoicePlayer.release()
        screenshotWatcher.stopWatching()
        networkStatusTracker.stopTracking()
        activeGenerationJob?.cancel()
    }
}
