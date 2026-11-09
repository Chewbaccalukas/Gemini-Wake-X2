package com.example.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.data.db.AppDatabase
import com.example.data.model.TriggerType
import com.example.data.model.WakeWord
import com.example.data.repository.WakeWordRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.sqrt

private const val TAG = "WakeWordVoiceService"

/**
 * Result emitted when the foreground service keyword detection loop matches an active [WakeWord]
 * from [WakeWordRepository].
 */
data class WakeWordDetectionResult(
    val wakeWord: WakeWord,
    val matchedPhrase: String,
    val followUpQuery: String?,
    val rawTranscript: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Foreground service declared with android:foregroundServiceType="microphone"
 * so Android does not mute background or live voice microphone capture.
 *
 * Initializes microphone capture and runs a keyword detection loop integrated with
 * [WakeWordRepository] to check spoken input against active user-defined wake words in the Room database.
 */
class WakeWordVoiceService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): WakeWordVoiceService = this@WakeWordVoiceService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var repositoryObserverJob: Job? = null
    private var keywordLoopJob: Job? = null
    private var serviceAudioRecord: AudioRecord? = null

    private var customRepository: WakeWordRepository? = null
    private val transcriptQueue = ConcurrentLinkedQueue<String>()

    private val _serviceActiveWakeWords = MutableStateFlow<List<WakeWord>>(emptyList())
    val serviceActiveWakeWords: StateFlow<List<WakeWord>> = _serviceActiveWakeWords.asStateFlow()

    private val _isKeywordLoopRunning = MutableStateFlow(false)
    val isKeywordLoopRunning: StateFlow<Boolean> = _isKeywordLoopRunning.asStateFlow()

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (isTransitioningAudioState) {
            Log.d(TAG, "Ignoring AudioFocus change ($focusChange) during audio state transition")
            return@OnAudioFocusChangeListener
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "AudioFocus lost ($focusChange) — pausing background AudioRecord")
                _hasExclusiveAudioFocus.value = false
                onAudioFocusLostCallback?.invoke(focusChange)
            }
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> {
                Log.d(TAG, "AudioFocus gained ($focusChange) — restoring background AudioRecord if eligible")
                _hasExclusiveAudioFocus.value = true
                onAudioFocusGainedCallback?.invoke()
            }
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            super.onRecordingConfigChanged(configs)
            if (isTransitioningAudioState) {
                Log.d(TAG, "Ignoring onRecordingConfigChanged while isTransitioningAudioState=true")
                return
            }

            // Filter out any recording configuration initiated by our own app (Process.myUid() or own audioSessionId)
            val externalConfigs = configs.filterNot { config ->
                isOwnRecordingConfig(config, activeAppAudioSessionId)
            }

            if (configs.isNotEmpty() && externalConfigs.isEmpty()) {
                Log.d(TAG, "Ignoring self-initiated AudioRecordingCallback in service (session=$activeAppAudioSessionId)")
                return
            }

            // Only flag external recording if a config is verified to belong to a different package UID
            val externalActive = externalConfigs.any { config ->
                val clientUid = extractClientUid(config)
                clientUid != null && clientUid != Process.myUid()
            }

            val previous = _isExternalRecordingActive.value
            if (externalActive == previous) return
            _isExternalRecordingActive.value = externalActive
            if (externalActive) {
                Log.d(TAG, "External microphone capture detected (non-app UID) — yielding AudioRecord")
                onExternalMicContentionChanged?.invoke(true)
            } else {
                Log.d(TAG, "External microphone capture ended — restoring wake listening")
                onExternalMicContentionChanged?.invoke(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            audioManager?.registerAudioRecordingCallback(recordingCallback, null)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register AudioRecordingCallback: ${e.message}")
        }
        startObservingWakeWordRepository()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_VOICE_SERVICE -> {
                stopKeywordDetectionLoop()
                abandonExclusiveAudioFocus()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            ACTION_ENTER_SLEEP_MODE -> {
                setSleepMode(true)
                abandonExclusiveAudioFocus()
                promoteToMicrophoneForeground()
                onSleepModeChangedCallback?.invoke(true)
                return START_STICKY
            }
            ACTION_WAKE_FROM_SLEEP -> {
                setSleepMode(false)
                if (!_hasExclusiveAudioFocus.value && !isAppRecordingActive) {
                    requestExclusiveAudioFocus()
                }
                promoteToMicrophoneForeground()
                if (!_isKeywordLoopRunning.value) {
                    startKeywordDetectionLoop()
                }
                onSleepModeChangedCallback?.invoke(false)
                return START_STICKY
            }
            ACTION_PAUSE_FOR_INPUT_FOCUS -> {
                _isInputFocusPaused.value = true
                abandonExclusiveAudioFocus()
                onInputFocusPauseRequested?.invoke(true)
                return START_STICKY
            }
            ACTION_RESUME_AFTER_INPUT_FOCUS -> {
                _isInputFocusPaused.value = false
                if (!_hasExclusiveAudioFocus.value) {
                    requestExclusiveAudioFocus()
                }
                onInputFocusPauseRequested?.invoke(false)
                if (!_isKeywordLoopRunning.value) {
                    startKeywordDetectionLoop()
                }
                return START_STICKY
            }
            else -> {
                promoteToMicrophoneForeground()
                // Do not re-request audio focus with a competing listener if WakeWordManager already holds it
                if (!_hasExclusiveAudioFocus.value && !isAppRecordingActive && !_isSleepModeGlobal.value) {
                    requestExclusiveAudioFocus()
                }
                val candidateTranscript = intent?.getStringExtra(EXTRA_CANDIDATE_TRANSCRIPT)
                if (!candidateTranscript.isNullOrBlank()) {
                    submitTranscriptForKeywordCheck(candidateTranscript)
                }
                startKeywordDetectionLoop()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopKeywordDetectionLoop()
        repositoryObserverJob?.cancel()
        serviceScope.cancel()
        try {
            audioManager?.unregisterAudioRecordingCallback(recordingCallback)
        } catch (_: Exception) {}
        abandonExclusiveAudioFocus()
        super.onDestroy()
    }

    /**
     * Resolves the active [WakeWordRepository] (using injected test repository if set, or Room singleton).
     */
    fun resolveWakeWordRepository(): WakeWordRepository {
        customRepository?.let { return it }
        injectedRepositoryForTesting?.let { return it }
        val db = AppDatabase.getDatabase(applicationContext, serviceScope)
        return WakeWordRepository(db.wakeWordDao(), db.chatDao()).also {
            customRepository = it
        }
    }

    /**
     * Injects a custom [WakeWordRepository] into this service instance and refreshes the active wake word observer.
     */
    fun setWakeWordRepository(repository: WakeWordRepository) {
        customRepository = repository
        startObservingWakeWordRepository()
    }

    private fun startObservingWakeWordRepository() {
        repositoryObserverJob?.cancel()
        repositoryObserverJob = serviceScope.launch {
            launch {
                try {
                    val repo = resolveWakeWordRepository()
                    repo.observeAllWakeWords().collect { allWords ->
                        val enabled = allWords.filter { it.isEnabled && it.phrase.isNotBlank() }
                        _serviceActiveWakeWords.value = enabled
                        _activeDatabaseWakeWords.value = enabled
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error observing WakeWordRepository in service: ${e.message}")
                }
            }
            launch {
                try {
                    val db = AppDatabase.getDatabase(applicationContext, serviceScope)
                    db.chatDao().getAllWakeWordSamplesFlow().collect { samples ->
                        _activeWakeWordSamples.value = samples
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error observing WakeWordSamples in service: ${e.message}")
                }
            }
        }
    }

    /**
     * Initializes the microphone (when not already held by the in-process speech/wake engine)
     * and runs the continuous keyword detection loop checking against [WakeWordRepository].
     */
    fun startKeywordDetectionLoop() {
        if (keywordLoopJob?.isActive == true) return
        _isKeywordLoopRunning.value = true
        _isKeywordLoopActiveGlobal.value = true

        keywordLoopJob = serviceScope.launch {
            try {
                val repo = resolveWakeWordRepository()
                if (_serviceActiveWakeWords.value.isEmpty()) {
                    val initial = repo.getAllWakeWordsSync().filter { it.isEnabled && it.phrase.isNotBlank() }
                    _serviceActiveWakeWords.value = initial
                    _activeDatabaseWakeWords.value = initial
                }

                // Initialize standalone microphone AudioRecord if permission is granted and no external recognizer holds mic
                val hasRecordAudioPerm = ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                var pcmBuffer: ShortArray? = null
                if (hasRecordAudioPerm && !isAppRecordingActive && !_isSleepModeGlobal.value) {
                    val record = initializeMicrophoneRecord()
                    if (record != null) {
                        serviceAudioRecord = record
                        pcmBuffer = ShortArray(1024)
                    }
                }

                while (isActive && _isKeywordLoopRunning.value) {
                    // Always drain candidate transcripts first so "Gemini, wake up" can wake from sleep mode
                    var candidate = transcriptQueue.poll()
                    while (candidate != null) {
                        checkTranscriptAgainstDatabase(candidate)
                        candidate = transcriptQueue.poll()
                    }

                    if (_isInputFocusPaused.value || _isExternalRecordingActive.value || _isSleepModeGlobal.value) {
                        if (_isSleepModeGlobal.value && serviceAudioRecord != null) {
                            releaseServiceMicrophoneRecord()
                        }
                        delay(120L)
                        continue
                    }

                    // 1. If a standalone AudioRecord is active and the main app engine isn't holding the mic, read PCM audio
                    val activeRecord = serviceAudioRecord
                    if (activeRecord != null && pcmBuffer != null && !isAppRecordingActive) {
                        try {
                            if (activeRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                                val read = activeRecord.read(pcmBuffer, 0, pcmBuffer.size)
                                if (read > 0) {
                                    var sumSq = 0.0
                                    for (i in 0 until read) {
                                        val s = pcmBuffer[i].toDouble()
                                        sumSq += s * s
                                    }
                                    val rms = sqrt(sumSq / read)
                                    _serviceMicRms.value = rms.toFloat()
                                }
                            }
                        } catch (_: Throwable) {}
                    } else if (isAppRecordingActive && serviceAudioRecord != null) {
                        // Yield standalone service AudioRecord immediately when SpeechRecognizer or WakeWordManager owns the mic
                        releaseServiceMicrophoneRecord()
                    }

                    delay(60L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Keyword detection loop terminated: ${e.message}")
            } finally {
                releaseServiceMicrophoneRecord()
                _isKeywordLoopRunning.value = false
                _isKeywordLoopActiveGlobal.value = false
            }
        }
    }

    /**
     * Stops the keyword detection loop and releases any standalone microphone handle held by the service.
     */
    fun stopKeywordDetectionLoop() {
        _isKeywordLoopRunning.value = false
        _isKeywordLoopActiveGlobal.value = false
        keywordLoopJob?.cancel()
        keywordLoopJob = null
        releaseServiceMicrophoneRecord()
    }

    /**
     * Queues a recognized speech transcript to be evaluated by the keyword detection loop.
     */
    fun submitTranscriptForKeywordCheck(transcript: String) {
        val trimmed = transcript.trim()
        if (trimmed.isNotBlank()) {
            transcriptQueue.offer(trimmed)
        }
    }

    /**
     * Checks [transcript] directly against sleep/wake voice commands and the active wake words in [WakeWordRepository]
     * and emits a [WakeWordDetectionResult] if a match is found.
     */
    suspend fun checkTranscriptAgainstDatabase(transcript: String): WakeWordDetectionResult? {
        val cleanTranscript = transcript.trim()
        if (cleanTranscript.isBlank()) return null

        // Check for "Gemini, wake up" command to exit sleep mode
        if (AudioCommandSanitizer.containsWakeUpCommand(cleanTranscript)) {
            if (_isSleepModeGlobal.value) {
                setSleepMode(false)
                promoteToMicrophoneForeground()
                onSleepModeChangedCallback?.invoke(false)
            }
            return null
        }

        // Check for "Gemini, sleep" command to enter sleep mode
        if (AudioCommandSanitizer.containsSleepCommand(cleanTranscript)) {
            setSleepMode(true)
            promoteToMicrophoneForeground()
            onSleepModeChangedCallback?.invoke(true)
            return null
        }

        // If in sleep mode, ignore normal wake word triggers until woken up
        if (_isSleepModeGlobal.value) return null

        val currentActive = _serviceActiveWakeWords.value.ifEmpty {
            val loaded = resolveWakeWordRepository().getAllWakeWordsSync()
                .filter { it.isEnabled && it.phrase.isNotBlank() }
            _serviceActiveWakeWords.value = loaded
            _activeDatabaseWakeWords.value = loaded
            loaded
        }

        val match = matchWakeWordFromList(cleanTranscript, currentActive)
        if (match != null) {
            _lastDetectedWakeWord.value = match
            _detectedWakeWordsFlow.tryEmit(match)
            postWakeWordReadyCueNotification(applicationContext, match)
            onWakeWordDetectedCallback?.invoke(match)
        }
        return match
    }

    private fun initializeMicrophoneRecord(): AudioRecord? {
        return try {
            val sampleRate = 16000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = if (minBuf > 0) (minBuf * 2).coerceAtLeast(3200) else 6400
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                registerOwnAudioSessionId(record.audioSessionId)
                record.startRecording()
                _isMicrophoneInitialized.value = true
                record
            } else {
                record.release()
                null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException initializing AudioRecord in service: ${e.message}")
            null
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to initialize AudioRecord in service: ${e.message}")
            null
        }
    }

    private fun releaseServiceMicrophoneRecord() {
        val record = serviceAudioRecord ?: return
        serviceAudioRecord = null
        _isMicrophoneInitialized.value = false
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (_: Throwable) {}
        try {
            record.release()
        } catch (_: Throwable) {}
    }

    fun requestExclusiveAudioFocus(): Boolean {
        val manager = audioManager ?: (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
            ?: return false
        audioManager = manager
        return try {
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
                audioFocusRequest = req
                manager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
            val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            _hasExclusiveAudioFocus.value = granted
            granted
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE: ${e.message}")
            false
        }
    }

    fun abandonExclusiveAudioFocus() {
        val manager = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(focusChangeListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning audio focus: ${e.message}")
        } finally {
            _hasExclusiveAudioFocus.value = false
        }
    }

    private fun promoteToMicrophoneForeground() {
        try {
            ensureNotificationChannel()
            val notification = buildNotification()
            val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            androidx.core.app.ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                fgType
            )
        } catch (e: Throwable) {
            try {
                val notification = buildNotification()
                startForeground(NOTIFICATION_ID, notification)
            } catch (inner: Throwable) {
                Log.w(TAG, "Unable to promote WakeWordVoiceService to foreground: ${inner.message}")
            }
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Live Voice & Wake Word Microphone",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps microphone capture active during wake-word and live voice sessions"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
            val cueExisting = manager.getNotificationChannel(CHANNEL_WAKE_CUE_ID)
            if (cueExisting == null) {
                val cueChannel = NotificationChannel(
                    CHANNEL_WAKE_CUE_ID,
                    "Wake Word Ready Cue",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Non-intrusive notification when a wake word is detected in the background"
                    setShowBadge(true)
                }
                manager.createNotificationChannel(cueChannel)
            }
        }
    }

    private fun buildNotification(): Notification {
        val isSleeping = _isSleepModeGlobal.value
        val title = if (isSleeping) {
            "Gemini Wake • Sleep Mode"
        } else {
            "Gemini Wake Active"
        }
        val subtitle = when {
            isSleeping -> "Wake word listener paused — tap Wake Up or say \"Gemini, wake up\""
            _isInputFocusPaused.value || _isExternalRecordingActive.value ->
                "Microphone yielded to keyboard / external input"
            else ->
                "Listening for wake words (${_serviceActiveWakeWords.value.size} active)"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (isSleeping) {
            val wakeIntent = Intent(this, WakeWordVoiceService::class.java).apply {
                action = ACTION_WAKE_FROM_SLEEP
            }
            val wakePendingIntent = android.app.PendingIntent.getService(
                this,
                4105,
                wakeIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_btn_speak_now,
                "Wake Up",
                wakePendingIntent
            )
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "gemini_wake_mic_channel"
        const val CHANNEL_WAKE_CUE_ID = "gemini_wake_ready_cue_channel"
        const val NOTIFICATION_ID = 4102
        const val NOTIFICATION_ID_WAKE_READY = 4103
        const val ACTION_START_VOICE_SERVICE = "com.example.voice.action.START_VOICE_SERVICE"
        const val ACTION_STOP_VOICE_SERVICE = "com.example.voice.action.STOP_VOICE_SERVICE"
        const val ACTION_ENTER_SLEEP_MODE = "com.example.voice.action.ENTER_SLEEP_MODE"
        const val ACTION_WAKE_FROM_SLEEP = "com.example.voice.action.WAKE_FROM_SLEEP"
        const val ACTION_PAUSE_FOR_INPUT_FOCUS = "com.example.voice.action.PAUSE_FOR_INPUT_FOCUS"
        const val ACTION_RESUME_AFTER_INPUT_FOCUS = "com.example.voice.action.RESUME_AFTER_INPUT_FOCUS"
        const val EXTRA_CANDIDATE_TRANSCRIPT = "com.example.voice.extra.CANDIDATE_TRANSCRIPT"
        const val EXTRA_WAKE_TARGET_CHAT_ID = "com.example.voice.extra.WAKE_TARGET_CHAT_ID"
        const val EXTRA_WAKE_MATCHED_PHRASE = "com.example.voice.extra.WAKE_MATCHED_PHRASE"

        @Volatile
        var injectedRepositoryForTesting: WakeWordRepository? = null

        private val _activeDatabaseWakeWords = MutableStateFlow<List<WakeWord>>(emptyList())
        val activeDatabaseWakeWords: StateFlow<List<WakeWord>> = _activeDatabaseWakeWords.asStateFlow()

        private val _activeWakeWordSamples = MutableStateFlow<List<com.example.data.model.WakeWordSample>>(emptyList())
        val activeWakeWordSamples: StateFlow<List<com.example.data.model.WakeWordSample>> = _activeWakeWordSamples.asStateFlow()

        private val _isSleepModeGlobal = MutableStateFlow(false)
        val isSleepModeGlobal: StateFlow<Boolean> = _isSleepModeGlobal.asStateFlow()

        fun setSleepMode(sleeping: Boolean) {
            _isSleepModeGlobal.value = sleeping
        }

        private val _isKeywordLoopActiveGlobal = MutableStateFlow(false)
        val isKeywordLoopActiveGlobal: StateFlow<Boolean> = _isKeywordLoopActiveGlobal.asStateFlow()

        private val _isMicrophoneInitialized = MutableStateFlow(false)
        val isMicrophoneInitialized: StateFlow<Boolean> = _isMicrophoneInitialized.asStateFlow()

        private val _serviceMicRms = MutableStateFlow(0f)
        val serviceMicRms: StateFlow<Float> = _serviceMicRms.asStateFlow()

        private val _lastDetectedWakeWord = MutableStateFlow<WakeWordDetectionResult?>(null)
        val lastDetectedWakeWord: StateFlow<WakeWordDetectionResult?> = _lastDetectedWakeWord.asStateFlow()

        private val _detectedWakeWordsFlow = MutableSharedFlow<WakeWordDetectionResult>(extraBufferCapacity = 16)
        val detectedWakeWordsFlow: SharedFlow<WakeWordDetectionResult> = _detectedWakeWordsFlow.asSharedFlow()

        var onWakeWordDetectedCallback: ((WakeWordDetectionResult) -> Unit)? = null
        var onSleepModeChangedCallback: ((Boolean) -> Unit)? = null

        /**
         * Posts a non-intrusive notification cue confirming the wake word was detected and the app
         * is ready to receive commands, without forcibly opening the full app UI over other apps.
         * Tapping the notification opens directly to the associated chat conversation.
         */
        fun postWakeWordReadyCueNotification(
            context: Context,
            result: WakeWordDetectionResult
        ) {
            try {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (manager.getNotificationChannel(CHANNEL_WAKE_CUE_ID) == null) {
                        val cueChannel = NotificationChannel(
                            CHANNEL_WAKE_CUE_ID,
                            "Wake Word Ready Cue",
                            NotificationManager.IMPORTANCE_DEFAULT
                        ).apply {
                            description = "Non-intrusive notification when a wake word is detected in the background"
                            setShowBadge(true)
                        }
                        manager.createNotificationChannel(cueChannel)
                    }
                }
                val chatId = result.wakeWord.chatId ?: -1L
                val chatTitle = result.wakeWord.chatTitle.ifBlank { "Gemini Chat" }
                val openIntent = Intent(context, com.example.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    if (chatId > 0L) {
                        putExtra(EXTRA_WAKE_TARGET_CHAT_ID, chatId)
                    }
                    putExtra(EXTRA_WAKE_MATCHED_PHRASE, result.matchedPhrase)
                }
                val pendingIntent = android.app.PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID_WAKE_READY,
                    openIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                val notification = NotificationCompat.Builder(context, CHANNEL_WAKE_CUE_ID)
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("Ready for Commands • \"${result.matchedPhrase}\"")
                    .setContentText("Matched $chatTitle — speak your command or tap to open chat")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
                    .setSilent(true)
                    .setContentIntent(pendingIntent)
                    .build()
                manager.notify(NOTIFICATION_ID_WAKE_READY, notification)
            } catch (e: Throwable) {
                Log.w(TAG, "Unable to post wake word ready cue notification: ${e.message}")
            }
        }

        /**
         * Matches a spoken [transcript] against a list of active [WakeWord] entities from [WakeWordRepository].
         * Prioritizes longer wake phrases first so specific phrases match before shorter prefixes,
         * and uses each wake word's sensitivity (or [sensitivityOverride]) to allow fuzzy prefix matching.
         */
        fun matchWakeWordFromList(
            transcript: String,
            activeWakeWords: List<WakeWord>,
            sensitivityOverride: Float? = null
        ): WakeWordDetectionResult? {
            val cleanTranscript = transcript.trim()
            if (cleanTranscript.isBlank() || activeWakeWords.isEmpty()) return null

            val sorted = activeWakeWords
                .filter { it.isEnabled && it.phrase.isNotBlank() }
                .sortedByDescending { it.phrase.trim().length }

            // 1. Exact case-insensitive substring match
            for (wakeWord in sorted) {
                val phrase = wakeWord.phrase.trim()
                val idx = cleanTranscript.indexOf(phrase, ignoreCase = true)
                if (idx >= 0) {
                    val afterIndex = idx + phrase.length
                    val followUp = if (afterIndex < cleanTranscript.length) {
                        cleanTranscript.substring(afterIndex)
                            .trimStart(' ', ',', '.', ':', ';', '-', '!', '?')
                            .trim()
                            .takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                    return WakeWordDetectionResult(
                        wakeWord = wakeWord,
                        matchedPhrase = phrase,
                        followUpQuery = followUp,
                        rawTranscript = cleanTranscript
                    )
                }
            }

            // 2. Sensitivity-scaled fuzzy prefix match on leading words
            val transcriptWords = cleanTranscript.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (transcriptWords.isNotEmpty()) {
                for (wakeWord in sorted) {
                    val phrase = wakeWord.phrase.trim()
                    val phraseWords = phrase.split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (transcriptWords.size >= phraseWords.size) {
                        val candidatePrefix = transcriptWords.take(phraseWords.size).joinToString(" ")
                        val effectiveSensitivity = (sensitivityOverride ?: wakeWord.sensitivity).coerceIn(0.50f, 0.98f)
                        val similarity = AudioCommandSanitizer.computePhraseSimilarity(candidatePrefix, phrase)
                        if (similarity >= effectiveSensitivity) {
                            val followUp = transcriptWords.drop(phraseWords.size).joinToString(" ")
                                .trimStart(' ', ',', '.', ':', ';', '-', '!', '?')
                                .trim()
                                .takeIf { it.isNotBlank() }
                            return WakeWordDetectionResult(
                                wakeWord = wakeWord,
                                matchedPhrase = phrase,
                                followUpQuery = followUp,
                                rawTranscript = cleanTranscript
                            )
                        }
                    }
                }
            }
            return null
        }

        fun startServiceSafely(
            context: Context,
            action: String = ACTION_START_VOICE_SERVICE,
            candidateTranscript: String? = null
        ) {
            val appCtx = context.applicationContext ?: context
            val intent = Intent(appCtx, WakeWordVoiceService::class.java).apply {
                this.action = action
                if (!candidateTranscript.isNullOrBlank()) {
                    putExtra(EXTRA_CANDIDATE_TRANSCRIPT, candidateTranscript)
                }
            }
            try {
                if (action == ACTION_START_VOICE_SERVICE) {
                    ContextCompat.startForegroundService(appCtx, intent)
                } else {
                    appCtx.startService(intent)
                }
            } catch (e: Throwable) {
                try {
                    appCtx.startService(intent)
                } catch (inner: Throwable) {
                    Log.w(TAG, "Unable to start WakeWordVoiceService ($action): ${inner.message}")
                }
            }
        }

        const val AUDIO_FOCUS_GAIN_MODE = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE

        private val ownAudioSessionIds: MutableSet<Int> = ConcurrentHashMap.newKeySet()

        @Volatile
        var activeAppAudioSessionId: Int = -1
            set(value) {
                field = value
                if (value > 0) {
                    ownAudioSessionIds.add(value)
                }
            }

        @Volatile
        var isAppRecordingActive: Boolean = false

        @Volatile
        var isTransitioningAudioState: Boolean = false

        private val _hasExclusiveAudioFocus = MutableStateFlow(false)
        val hasExclusiveAudioFocus: StateFlow<Boolean> = _hasExclusiveAudioFocus.asStateFlow()

        private val _isExternalRecordingActive = MutableStateFlow(false)
        val isExternalRecordingActive: StateFlow<Boolean> = _isExternalRecordingActive.asStateFlow()

        private val _isInputFocusPaused = MutableStateFlow(false)
        val isInputFocusPaused: StateFlow<Boolean> = _isInputFocusPaused.asStateFlow()

        var onAudioFocusLostCallback: ((Int) -> Unit)? = null
        var onAudioFocusGainedCallback: (() -> Unit)? = null
        var onExternalMicContentionChanged: ((Boolean) -> Unit)? = null
        var onInputFocusPauseRequested: ((Boolean) -> Unit)? = null

        fun registerOwnAudioSessionId(sessionId: Int) {
            if (sessionId > 0) {
                ownAudioSessionIds.add(sessionId)
                activeAppAudioSessionId = sessionId
            }
        }

        fun setExclusiveAudioFocusState(granted: Boolean) {
            _hasExclusiveAudioFocus.value = granted
        }

        /**
         * Safely extracts the client UID from an AudioRecordingConfiguration if accessible
         * on the current Android runtime.
         */
        fun extractClientUid(config: AudioRecordingConfiguration): Int? {
            return try {
                val method = config.javaClass.getMethod("getClientUid")
                (method.invoke(config) as? Int)?.takeIf { it >= 0 }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Returns true if the recording configuration belongs to our own app process
         * (Process.myUid()) or matches our AudioRecord's audioSessionId.
         */
        fun isOwnRecordingConfig(
            config: AudioRecordingConfiguration,
            currentAudioSessionId: Int = activeAppAudioSessionId
        ): Boolean {
            val clientUid = extractClientUid(config)
            val sessionId = config.clientAudioSessionId
            if (isOwnRecordingConfig(clientUid, sessionId, currentAudioSessionId)) {
                return true
            }
            if (clientUid == null && (isAppRecordingActive || isTransitioningAudioState || activeAppAudioSessionId > 0)) {
                return true
            }
            return false
        }

        /**
         * Verifies whether a recording client UID or audioSessionId belongs to our own app.
         */
        fun isOwnRecordingConfig(
            clientUid: Int?,
            clientAudioSessionId: Int,
            currentAudioSessionId: Int = activeAppAudioSessionId
        ): Boolean {
            if (clientUid != null && clientUid == Process.myUid()) {
                return true
            }
            if (clientAudioSessionId > 0 && (
                    clientAudioSessionId == currentAudioSessionId ||
                        clientAudioSessionId == activeAppAudioSessionId ||
                        ownAudioSessionIds.contains(clientAudioSessionId)
                    )
            ) {
                return true
            }
            return false
        }
    }
}
