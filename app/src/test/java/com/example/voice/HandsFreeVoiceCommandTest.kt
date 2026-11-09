package com.example.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HandsFreeVoiceCommandTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun testDictateTriggerDetectionAndExtraction() {
        val test1 = "Dictate what is quantum computing?"
        assertTrue(AudioCommandSanitizer.containsDictateTrigger(test1, "Dictate"))
        val extracted1 = AudioCommandSanitizer.extractContentAfterDictate(test1, "Dictate")
        assertEquals("what is quantum computing?", extracted1)

        val test2 = "Take note organize task list for tomorrow"
        assertTrue(AudioCommandSanitizer.containsDictateTrigger(test2, "Dictate"))
        val extracted2 = AudioCommandSanitizer.extractContentAfterDictate(test2, "Dictate")
        assertEquals("organize task list for tomorrow", extracted2)

        val test3 = "Start dictation summarize meeting notes"
        assertTrue(AudioCommandSanitizer.containsDictateTrigger(test3, "Dictate"))
        val extracted3 = AudioCommandSanitizer.extractContentAfterDictate(test3, "Dictate")
        assertEquals("summarize meeting notes", extracted3)

        // Custom trigger
        val testCustom = "Record query what is the speed of sound?"
        assertTrue(AudioCommandSanitizer.containsDictateTrigger(testCustom, "Record query"))
        val extractedCustom = AudioCommandSanitizer.extractContentAfterDictate(testCustom, "Record query")
        assertEquals("what is the speed of sound?", extractedCustom)
    }

    @Test
    fun testSendTriggerDetection() {
        assertTrue(AudioCommandSanitizer.containsSendTrigger("Please compile this code send", "Send"))
        assertTrue(AudioCommandSanitizer.containsSendTrigger("How does photosynthesis work? Over", "Send"))
        assertTrue(AudioCommandSanitizer.containsSendTrigger("Send message", "Send"))
        assertTrue(AudioCommandSanitizer.containsSendTrigger("Dispatch this query", "Send"))
        assertTrue(AudioCommandSanitizer.containsSendTrigger("Transmit now", "Transmit"))

        assertFalse(AudioCommandSanitizer.containsSendTrigger("This is a regular question without trigger", "Send"))
    }

    @Test
    fun testHeartbeatTriggerDetection() {
        assertTrue(AudioCommandSanitizer.containsHeartbeatTrigger("heartbeat", "Heartbeat"))
        assertTrue(AudioCommandSanitizer.containsHeartbeatTrigger("Pulse", "Heartbeat"))
        assertTrue(AudioCommandSanitizer.containsHeartbeatTrigger("Ping", "Heartbeat"))
        assertTrue(AudioCommandSanitizer.containsHeartbeatTrigger("Heartbeat ping", "Heartbeat"))
        assertTrue(AudioCommandSanitizer.containsHeartbeatTrigger("Keep alive check", "Keep alive"))

        assertFalse(AudioCommandSanitizer.containsHeartbeatTrigger("Hello Gemini how are you?", "Heartbeat"))
    }

    @Test
    fun testAudioCommandSanitization_RemovesTrailingAndLeadingCommands() {
        // Trailing send trigger should be scrubbed
        val prompt1 = "Explain the difference between TCP and UDP send"
        val scrubbed1 = AudioCommandSanitizer.sanitizeSpokenText(prompt1, sendTrigger = "Send", heartbeatTrigger = "Heartbeat")
        assertEquals("Explain the difference between TCP and UDP", scrubbed1)

        // Trailing over trigger should be scrubbed
        val prompt2 = "Review this Kotlin function over"
        val scrubbed2 = AudioCommandSanitizer.sanitizeSpokenText(prompt2, sendTrigger = "Send", heartbeatTrigger = "Heartbeat")
        assertEquals("Review this Kotlin function", scrubbed2)

        // Trailing heartbeat trigger should be scrubbed
        val prompt3 = "Checking system state heartbeat"
        val scrubbed3 = AudioCommandSanitizer.sanitizeSpokenText(prompt3, sendTrigger = "Send", heartbeatTrigger = "Heartbeat")
        assertEquals("Checking system state", scrubbed3)

        // Trailing custom trigger
        val promptCustom = "Generate a unit test for Room database dispatch"
        val scrubbedCustom = AudioCommandSanitizer.sanitizeSpokenText(promptCustom, sendTrigger = "dispatch", heartbeatTrigger = "Heartbeat")
        assertEquals("Generate a unit test for Room database", scrubbedCustom)

        // Internal words shouldn't be damaged unless they are isolated command triggers
        val promptClean = "Send me a recipe for chocolate cake"
        // Leading trigger "Send" alone as command scrubbed, remaining prompt preserved
        val scrubbedClean = AudioCommandSanitizer.sanitizeSpokenText(promptClean, sendTrigger = "Send", heartbeatTrigger = "Heartbeat")
        assertEquals("me a recipe for chocolate cake", scrubbedClean)
    }

    @Test
    fun testPlayHeartbeatConfirmationDoesNotCrash() {
        // Runs without throwing exception under Robolectric JVM environment
        AudioCommandSanitizer.playHeartbeatConfirmation(context)
    }

    @Test
    fun testHandsFreeVoiceStateValues() {
        assertEquals("Idle", HandsFreeVoiceState.IDLE.displayName)
        assertEquals("Listening", HandsFreeVoiceState.LISTENING.displayName)
        assertEquals("Transcribing", HandsFreeVoiceState.TRANSCRIBING.displayName)
        assertEquals("Heartbeat Ping", HandsFreeVoiceState.HEARTBEAT_PING.displayName)
    }

    @Test
    fun testWakeWordManagerSilenceDetection_3dbThresholdAnd1200msCutoff() {
        val manager = WakeWordManager(context)
        manager.transitionToActiveStreaming()
        var speechStartedCount = 0
        var silenceCutoffCount = 0

        manager.onSpeechStarted = { speechStartedCount++ }
        manager.onSilenceCutoffDetected = { silenceCutoffCount++ }

        assertEquals(0.0f, WakeWordManager.SPEECH_DB_THRESHOLD, 0.001f)
        assertEquals(1200L, WakeWordManager.SILENCE_CUTOFF_MS)

        // Pure silence (0.0f dB <= 0.0f) should NOT start speech
        assertFalse(manager.processRmsFrame(0.0f, nowMs = 1000L))
        assertEquals(0, speechStartedCount)
        assertEquals(0, silenceCutoffCount)

        // Above-threshold speech (> 0 dB, e.g. 0.5f or 3.5f dB) starts speech without dropping low-volume human speech
        assertFalse(manager.processRmsFrame(0.5f, nowMs = 1100L))
        assertEquals(1, speechStartedCount)
        assertEquals(0, silenceCutoffCount)

        // Drop to silence (0.0f dB) at t=2000ms
        assertFalse(manager.processRmsFrame(0.0f, nowMs = 2000L))
        // Still silent at t=2800ms (800ms elapsed < 1200ms) -> should NOT trigger yet
        assertFalse(manager.processRmsFrame(0.0f, nowMs = 2800L))
        assertEquals(0, silenceCutoffCount)

        // Continuous silence reaches 1200ms at t=3200ms -> triggers onSilenceCutoffDetected & transitions to passive listening
        assertTrue(manager.processRmsFrame(0.0f, nowMs = 3200L))
        assertEquals(1, silenceCutoffCount)
        assertEquals(HandsFreeVoiceState.LISTENING, manager.handsFreeState.value)

        manager.destroy()
    }

    @Test
    fun testWakeWordManagerPassiveListening_silenceCutoffDecoupled() {
        val manager = WakeWordManager(context)
        manager.transitionToPassiveListening()
        var silenceCutoffCount = 0
        manager.onSilenceCutoffDetected = { silenceCutoffCount++ }

        // In PASSIVE_LISTENING / Standby, low RMS and prolonged silence should NEVER trigger silence cutoff
        assertFalse(manager.processRmsFrame(0.1f, nowMs = 1000L))
        assertFalse(manager.processRmsFrame(0.2f, nowMs = 3000L))
        assertFalse(manager.processRmsFrame(0.1f, nowMs = 6000L))
        assertEquals(0, silenceCutoffCount)
        assertEquals(HandsFreeVoiceState.LISTENING, manager.handsFreeState.value)

        // Keyword hit transitions to active streaming
        manager.onKeywordHit("jimmy")
        assertEquals(HandsFreeVoiceState.TRANSCRIBING, manager.handsFreeState.value)

        manager.destroy()
    }

    @Test
    fun testMicrophoneMutexAndWakePreRollAudioHandoffToChatSession() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        val manager = viewModel.wakeWordManager

        // Simulate passive wake-word listening with non-silent wake speech frames in pre-roll buffer
        manager.setHandsFreeMode(true)
        assertTrue(manager.activeAudioRecordCount <= 1)

        val wakePcmSample = ByteArray(640) { idx -> if (idx % 2 == 0) 0x24 else 0x08 }
        manager.appendPreRollPcm(wakePcmSample, wakePcmSample.size)

        val handoffBefore = manager.wakeHandoffCount
        manager.onKeywordHit("Gemini", initialQuery = null)

        // Verify wake AudioRecord was released before chat-turn stream initialized (single-instance mutex)
        assertEquals(handoffBefore + 1, manager.wakeHandoffCount)
        assertTrue(manager.activeAudioRecordCount <= 1)
        assertEquals(WakeWordManagerState.ACTIVE_STREAMING, manager.engineState.value)
        assertEquals(HandsFreeVoiceState.TRANSCRIBING, manager.handsFreeState.value)

        // Verify captured pre-roll wake audio was forwarded directly into MainViewModel's active chat turn buffer
        val bufferedTurnBytes = viewModel.getBufferedVoiceTurnBytes()
        assertTrue(bufferedTurnBytes.isNotEmpty())
        assertEquals(wakePcmSample.size, bufferedTurnBytes.size)

        // Cancelling or completing the voice turn releases the turn AudioRecord and re-arms passive wake listening
        viewModel.cancelVoiceRecording()
        assertTrue(manager.activeAudioRecordCount <= 1)
        assertEquals(WakeWordManagerState.PASSIVE_LISTENING, manager.engineState.value)
        assertEquals(HandsFreeVoiceState.LISTENING, manager.handsFreeState.value)

        manager.destroy()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun testAudioFocusGainTransientExclusiveAndGboardTextFocusCompatibility() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        val manager = viewModel.wakeWordManager

        // Verify AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE configuration
        assertEquals(
            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            manager.audioFocusGainMode
        )
        assertEquals(
            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            WakeWordVoiceService.AUDIO_FOCUS_GAIN_MODE
        )

        manager.setHandsFreeMode(true)

        // When an on-screen text input gains focus (e.g. user taps chat box or Gboard opens),
        // background AudioRecord must immediately pause and release
        viewModel.onTextInputFocusChanged(true)
        assertTrue(viewModel.isTextInputFocused.value)
        assertFalse(manager.isListening.value)
        assertEquals(0, manager.activeAudioRecordCount)

        // When text focus clears, hands-free mode automatically resumes passive wake listening
        viewModel.onTextInputFocusChanged(false)
        assertFalse(viewModel.isTextInputFocused.value)
        assertEquals(WakeWordManagerState.PASSIVE_LISTENING, manager.engineState.value)

        // When external package (such as Gboard voice typing) requests microphone capture or audio focus
        manager.handleExternalMicContention(true)
        assertTrue(manager.isExternalMicActive.value)
        assertFalse(manager.isListening.value)
        assertEquals(0, manager.activeAudioRecordCount)

        manager.handleExternalMicContention(false)
        assertFalse(manager.isExternalMicActive.value)

        // AudioFocus loss and gain cycle
        manager.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(manager.hasAudioFocus.value)
        assertFalse(manager.isListening.value)
        assertEquals(0, manager.activeAudioRecordCount)

        manager.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(manager.hasAudioFocus.value)

        manager.destroy()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun testSystemAndCommandStatusStringsBlockedFromAudioPlayback() {
        assertTrue(AudioCommandSanitizer.isSystemOrCommandStatusString("Listening"))
        assertTrue(AudioCommandSanitizer.isSystemOrCommandStatusString("Processing"))
        assertTrue(AudioCommandSanitizer.isSystemOrCommandStatusString("Heartbeat"))
        assertTrue(AudioCommandSanitizer.isSystemOrCommandStatusString("Transcribing"))
        assertTrue(AudioCommandSanitizer.isSystemOrCommandStatusString("Idle"))
        assertFalse(AudioCommandSanitizer.isSystemOrCommandStatusString("Here is the analysis of your Kotlin code."))

        val player = StreamingVoicePlayer()
        player.feedBase64Chunk("Listening")
        assertFalse(player.isPlaying.value)
        player.feedBase64Chunk("Processing")
        assertFalse(player.isPlaying.value)
        player.feedBase64Chunk("Heartbeat")
        assertFalse(player.isPlaying.value)
        player.release()
    }

    @Test
    fun testSelfInitiatedAudioRecordingCallbackIgnored_NoMicCyclingLoop() {
        val manager = WakeWordManager(context)
        val myUid = android.os.Process.myUid()
        val ownSessionId = 7742
        WakeWordVoiceService.registerOwnAudioSessionId(ownSessionId)

        // 1. Config matching our own app UID (Process.myUid()) MUST be recognized as own capture and ignored
        assertTrue(
            WakeWordVoiceService.isOwnRecordingConfig(
                clientUid = myUid,
                clientAudioSessionId = 9999,
                currentAudioSessionId = ownSessionId
            )
        )

        // 2. Config matching our AudioRecord.audioSessionId MUST be recognized as own capture and ignored
        assertTrue(
            WakeWordVoiceService.isOwnRecordingConfig(
                clientUid = null,
                clientAudioSessionId = ownSessionId,
                currentAudioSessionId = ownSessionId
            )
        )

        // 3. Config from a different UID and different session ID is recognized as external
        assertFalse(
            WakeWordVoiceService.isOwnRecordingConfig(
                clientUid = myUid + 1000,
                clientAudioSessionId = 8888,
                currentAudioSessionId = ownSessionId
            )
        )

        // 4. Empty or self-initiated callback invocation does not flip isExternalMicActive or cycle the mic
        manager.handleRecordingConfigsChanged(emptyList())
        assertFalse(manager.isExternalMicActive.value)

        manager.destroy()
    }

    @Test
    fun testIsTransitioningAudioStateGuardAndIsUserVoiceTurnSingleActiveState() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        val manager = viewModel.wakeWordManager

        manager.setHandsFreeMode(true)
        assertFalse(viewModel.isUserVoiceTurn.value)

        // 1. Manual mic button tap sets isUserVoiceTurn = true
        viewModel.startVoiceRecording()
        assertTrue(viewModel.isUserVoiceTurn.value)
        assertTrue(manager.isUserVoiceTurn.value)
        assertEquals(WakeWordManagerState.ACTIVE_STREAMING, manager.engineState.value)

        // While isUserVoiceTurn == true, passive wake listener transitions/restarts are suppressed
        manager.transitionToPassiveListening()
        assertEquals(WakeWordManagerState.ACTIVE_STREAMING, manager.engineState.value)
        manager.restartWakeListeningAfterTurn()
        assertEquals(WakeWordManagerState.ACTIVE_STREAMING, manager.engineState.value)

        // Cancelling the voice turn resets isUserVoiceTurn = false and resumes passive wake mode
        viewModel.cancelVoiceRecording()
        assertFalse(viewModel.isUserVoiceTurn.value)
        assertFalse(manager.isUserVoiceTurn.value)
        assertEquals(WakeWordManagerState.PASSIVE_LISTENING, manager.engineState.value)

        // 2. Hands-free keyword hit also sets isUserVoiceTurn = true
        manager.onKeywordHit("Gemini", initialQuery = null)
        assertTrue(viewModel.isUserVoiceTurn.value)
        assertEquals(WakeWordManagerState.ACTIVE_STREAMING, manager.engineState.value)

        // Completing the turn resets isUserVoiceTurn = false
        manager.completeChatTurnAndReleaseMic()
        assertFalse(viewModel.isUserVoiceTurn.value)

        // 3. Verify isTransitioningAudioState re-entrancy guard blocks re-entrant contention/focus triggers
        manager.setTransitioningAudioStateForTest(true)
        assertTrue(manager.isTransitioningAudioState)
        manager.handleExternalMicContention(true)
        assertFalse(manager.isExternalMicActive.value)
        manager.setTransitioningAudioStateForTest(false)
        assertFalse(manager.isTransitioningAudioState)

        manager.destroy()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun testMainActivityManifestWindowSoftInputModeAdjustResize() {
        val pm = context.packageManager
        val componentName = android.content.ComponentName(context, com.example.MainActivity::class.java)
        val activityInfo = pm.getActivityInfo(componentName, 0)
        @Suppress("DEPRECATION")
        val adjustResizeMask = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        assertTrue(
            "MainActivity must specify android:windowSoftInputMode=\"adjustResize\"",
            (activityInfo.softInputMode and adjustResizeMask) == adjustResizeMask
        )
    }

    @Test
    fun testAudioSourceNormalizationAndZeroAmplitudeCycleToMic() {
        val manager = WakeWordManager(context)

        // 1. Default AudioSource is MediaRecorder.AudioSource.MIC (with UNPROCESSED and VOICE_RECOGNITION fallbacks)
        assertEquals(android.media.MediaRecorder.AudioSource.MIC, WakeWordManager.AUDIO_SOURCE)
        assertEquals(android.media.MediaRecorder.AudioSource.UNPROCESSED, WakeWordManager.UNPROCESSED_AUDIO_SOURCE)
        assertEquals(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeWordManager.FALLBACK_AUDIO_SOURCE)
        assertEquals(android.media.MediaRecorder.AudioSource.MIC, manager.audioSource)

        // 2. Simulate Samsung One UI zeroed buffer on VOICE_RECOGNITION: inspectAndLogAmplitude must warn & cycle to MIC
        manager.setAudioSourceForTest(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION)
        assertEquals(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, manager.audioSource)

        val zeroShorts = ShortArray(320) { 0 }
        val warningsBefore = manager.zeroAmplitudeWarningCount
        val zeroRms = manager.inspectAndLogAmplitude(zeroShorts, zeroShorts.size)
        assertEquals(0f, zeroRms, 0.0001f)
        assertEquals(warningsBefore + 1, manager.zeroAmplitudeWarningCount)
        assertEquals(android.media.MediaRecorder.AudioSource.MIC, manager.audioSource)

        // 3. Non-zero PCM buffer produces > 0 RMS and stays on MIC
        val speechShorts = ShortArray(320) { idx -> if (idx % 2 == 0) 900 else -900 }
        val activeRms = manager.inspectAndLogAmplitude(speechShorts, speechShorts.size)
        assertTrue(activeRms > 0f)
        assertEquals(android.media.MediaRecorder.AudioSource.MIC, manager.audioSource)

        manager.destroy()
    }

    @Test
    fun testWakeSnippetAndTransitionRingBufferRecordingAndPlayback() {
        val manager = WakeWordManager(context)
        manager.clearCapturedSnippetPcm()
        assertTrue(manager.getCapturedSnippetPcm().isEmpty())

        // 1. Incoming PCM bytes in pre-roll or snippet capture populate the local test buffer
        val samplePcm = ByteArray(1600) { idx -> if (idx % 2 == 0) 0x34 else 0x12 }
        manager.appendPreRollPcm(samplePcm, samplePcm.size)

        val capturedPcm = manager.getCapturedSnippetPcm()
        assertEquals(samplePcm.size, capturedPcm.size)
        assertTrue(samplePcm.contentEquals(capturedPcm))

        val capturedWav = manager.getCapturedSnippetWav()
        assertEquals(44 + samplePcm.size, capturedWav.size)
        assertEquals('R'.code.toByte(), capturedWav[0])
        assertEquals('I'.code.toByte(), capturedWav[1])
        assertEquals('F'.code.toByte(), capturedWav[2])
        assertEquals('F'.code.toByte(), capturedWav[3])

        // 2. SnippetAudioPlayer plays both WAV and raw PCM buffers via AudioTrack and extracts exact PCM payload
        val snippetPlayer = SnippetAudioPlayer()
        val tempWavFile = java.io.File(context.cacheDir, "wake_test_1_0.wav")
        tempWavFile.writeBytes(capturedWav)

        snippetPlayer.play(tempWavFile)
        assertEquals(samplePcm.size, snippetPlayer.lastPlayedPcmBytes.size)
        assertTrue(samplePcm.contentEquals(snippetPlayer.lastPlayedPcmBytes))
        assertTrue(snippetPlayer.playbackDurationMs.value > 0)

        snippetPlayer.stop()
        tempWavFile.delete()
        snippetPlayer.release()
        manager.destroy()
    }

    @Test
    fun testTrueLiveDuplexStreamingModeContinuesWithoutWalkieTalkieDrop() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        val manager = viewModel.wakeWordManager

        // Enabling Hands-Free enables persistent Live Duplex mode
        viewModel.toggleHandsFreeMode(true)
        assertTrue(viewModel.isHandsFreeActive.value)
        assertTrue(viewModel.isLiveDuplexMode.value)
        assertTrue(manager.isLiveDuplexMode.value)

        // Simulate user voice turn followed by turn completion in Live Duplex mode
        manager.onKeywordHit("Gemini", initialQuery = null)
        assertTrue(manager.isUserVoiceTurn.value)
        assertEquals(WakeWordManagerState.ACTIVE_STREAMING, manager.engineState.value)

        // Completing the turn in Live Duplex mode keeps the session in continuous listening state (never drops to IDLE)
        manager.completeTurnKeepLiveDuplexMic()
        assertFalse(manager.isUserVoiceTurn.value)
        assertEquals(WakeWordManagerState.PASSIVE_LISTENING, manager.engineState.value)
        assertEquals(HandsFreeVoiceState.LISTENING, manager.handsFreeState.value)

        manager.destroy()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun testWakeWordTriggerIsolation_NeverInjectsOpenIntoPromptOrLastHeardText() {
        // 1. Standalone wake words ("open", "gemini", custom wake word) scrub to empty string
        assertEquals("", AudioCommandSanitizer.stripWakeWordTrigger("open"))
        assertEquals("", AudioCommandSanitizer.stripWakeWordTrigger("Open!"))
        assertEquals("", AudioCommandSanitizer.stripWakeWordTrigger("Hey Gemini"))
        assertEquals("", AudioCommandSanitizer.stripWakeWordTrigger("Code Mode", listOf("Code Mode")))
        assertTrue(AudioCommandSanitizer.isPureWakeWordTrigger("open"))
        assertTrue(AudioCommandSanitizer.isSystemOrCommandStatusString("open"))

        // 2. Wake word followed by spoken query strips only the wake word prefix
        assertEquals(
            "what is the weather today",
            AudioCommandSanitizer.stripWakeWordTrigger("open what is the weather today")
        )
        assertEquals(
            "explain recursion",
            AudioCommandSanitizer.stripWakeWordTrigger("Code Mode explain recursion", listOf("Code Mode"))
        )

        // 3. WakeWordManager.onKeywordHit("open") never populates lastHeardText with "open"
        val manager = WakeWordManager(context)
        manager.onKeywordHit("open", initialQuery = "open")
        assertEquals("", manager.lastHeardText.value)

        manager.onKeywordHit("open", initialQuery = "open tell me a joke")
        assertEquals("tell me a joke", manager.lastHeardText.value)

        manager.clearLastHeardText()
        assertEquals("", manager.lastHeardText.value)
        manager.destroy()
    }

    @Test
    fun testCorruptedHistoryRecovery_HandlesMalformedJsonWithoutCrashing() {
        val prefs = context.getSharedPreferences("web_link_history_prefs", Context.MODE_PRIVATE)
        // Inject corrupted JSON and duplicate/malformed entries into SharedPreferences
        prefs.edit()
            .putString("web_link_history_entries_v1", "{corrupted_json_not_an_array")
            .putString("voice_command_history_entries_v1", "[{\"id\":\"\",\"recognizedTranscript\":\"\"}, {\"corrupt\":true}]")
            .commit()

        // Initializing WebLinkHistoryManager must not crash
        val historyManager = com.example.util.WebLinkHistoryManager(context)
        assertTrue(historyManager.historyEntries.value.isEmpty())

        // Repairing corrupted history purges invalid keys and keeps app functional
        val repairedCount = historyManager.repairOrClearCorruptedHistory()
        assertTrue(repairedCount >= 0)

        val validEntry = historyManager.recordVoiceCommand(
            commandType = com.example.util.VoiceCommandType.VOICE_TURN,
            triggerPhrase = "Gemini",
            recognizedTranscript = "What is Kotlin Multiplatform?",
            chatTitle = "Android Dev"
        )
        assertTrue(validEntry != null)
        assertEquals(1, historyManager.voiceCommandEntries.value.size)
    }

    @Test
    fun testCustomAudioWakeWordAcousticTemplateMatchingAndSensitivity() {
        val manager = WakeWordManager(context)
        val chat = com.example.data.model.ChatSession(
            id = 42L,
            title = "Voice Lab",
            wakeWord = "Phoenix",
            systemInstruction = "Helpful assistant",
            themeColorHex = "#38BDF8",
            iconName = "Sparkles",
            isDefaultWakeChat = true
        )
        manager.updateActiveChats(listOf(chat))

        // Create 3 enrolled 16 kHz PCM samples with a distinctive 2-syllable energy envelope
        val totalSamples = 16000 // 1.0 second at 16 kHz
        val templatePcm = ShortArray(totalSamples) { i ->
            val inSyllable1 = i in 1600..5600
            val inSyllable2 = i in 8000..13600
            val amp = when {
                inSyllable1 -> 6000
                inSyllable2 -> 8500
                else -> 150
            }
            if (i % 2 == 0) amp.toShort() else (-amp).toShort()
        }

        manager.updateEnrollmentSamples(42L, listOf(templatePcm, templatePcm.clone(), templatePcm.clone()))
        manager.wakeWordSensitivity = 0.72f

        val liveRms = manager.computeRmsProfile(templatePcm)
        val liveZcr = manager.computeZcrProfile(templatePcm)

        val match = manager.matchAcousticWakeTemplates(liveRms, liveZcr)
        assertTrue("Expected acoustic template match for enrolled 16 kHz PCM phrase", match != null)
        assertEquals(42L, match!!.first.id)
        assertTrue(match.second >= 0.72f)

        manager.destroy()
    }

    @Test
    fun testViewModelSpeechRecognizerPermissionRequestAndGrantFlow() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)

        // Simulate missing RECORD_AUDIO permission
        viewModel.setPermissionGrantedOverrideForTest(false)
        assertFalse(viewModel.checkRecordAudioPermission())
        assertFalse(viewModel.hasRecordAudioPermission.value)

        // Attempting to start continuous wake-word listening without permission emits requiresAudioPermission = true
        viewModel.startContinuousWakeWordListening()
        assertTrue(viewModel.requiresAudioPermission.value)
        assertFalse(viewModel.isSpeechRecognizerListening.value)

        // UI consumes the permission request event when launching RequestPermission()
        viewModel.consumeAudioPermissionRequest()
        assertFalse(viewModel.requiresAudioPermission.value)

        // Simulate SpeechRecognizer reporting ERROR_INSUFFICIENT_PERMISSIONS
        viewModel.wakeRecognitionListener.onError(android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertTrue(viewModel.requiresAudioPermission.value)
        assertFalse(viewModel.hasRecordAudioPermission.value)

        // Simulate user granting RECORD_AUDIO permission
        viewModel.setPermissionGrantedOverrideForTest(true)
        viewModel.onAudioPermissionResult(true)
        assertTrue(viewModel.hasRecordAudioPermission.value)
        assertFalse(viewModel.requiresAudioPermission.value)

        viewModel.cleanShutdown(null)
    }

    @Test
    fun testViewModelSpeechRecognizerWakeWordAndFollowUpQueryMatching() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)

        val customChat = com.example.data.model.ChatSession(
            id = 99L,
            title = "Quantum Research",
            wakeWord = "Hey Atlas",
            systemInstruction = "Research assistant",
            themeColorHex = "#38BDF8",
            iconName = "Sparkles",
            isDefaultWakeChat = true
        )
        viewModel.wakeWordManager.updateActiveChats(listOf(customChat))

        // Verify direct wake phrase + follow-up query matching in WakeWordManager
        val matchWithFollowUp = viewModel.wakeWordManager.matchWakePhraseInTranscript(
            "Hey Atlas explain quantum entanglement in simple terms"
        )
        assertTrue(matchWithFollowUp != null)
        assertEquals(99L, matchWithFollowUp!!.matchedChat?.id)
        assertEquals("Hey Atlas", matchWithFollowUp.matchedWakeWord)
        assertEquals("explain quantum entanglement in simple terms", matchWithFollowUp.followUpQuery)

        // Verify standalone wake phrase matching (no follow-up query yet -> followUpQuery is null)
        val standaloneMatch = viewModel.wakeWordManager.matchWakePhraseInTranscript("Hey Atlas")
        assertTrue(standaloneMatch != null)
        assertEquals(99L, standaloneMatch!!.matchedChat?.id)
        assertEquals(null, standaloneMatch.followUpQuery)

        // Verify SpeechRecognizer RecognitionListener RMS & timeout handling in MainViewModel
        viewModel.wakeRecognitionListener.onReadyForSpeech(android.os.Bundle())
        viewModel.wakeRecognitionListener.onRmsChanged(6.5f)
        assertEquals(6.5f, viewModel.wakeWordManager.audioRmsDb.value, 0.01f)

        // 1. Standalone wake phrase triggers user voice turn and does NOT inject wake word into lastHeardText
        val standaloneBundle = android.os.Bundle().apply {
            putStringArrayList(
                android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("Hey Atlas")
            )
        }
        viewModel.wakeRecognitionListener.onResults(standaloneBundle)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("", viewModel.wakeWordManager.lastHeardText.value)
        assertTrue(viewModel.isUserVoiceTurn.value)

        // 2. Wake phrase + follow-up query extracts only the scrubbed follow-up query into lastHeardText
        // and transitions into active user voice turn
        viewModel.wakeWordManager.completeChatTurnAndReleaseMic()
        assertFalse(viewModel.isUserVoiceTurn.value)
        viewModel.wakeWordManager.updateActiveChats(listOf(customChat))
        val followUpBundle = android.os.Bundle().apply {
            putStringArrayList(
                android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("Hey Atlas what is superfluidity")
            )
        }
        viewModel.wakeRecognitionListener.onResults(followUpBundle)
        assertEquals("what is superfluidity", viewModel.wakeWordManager.lastHeardText.value)
        assertTrue(viewModel.isUserVoiceTurn.value)

        // Simulate benign silence timeout (ERROR_SPEECH_TIMEOUT / ERROR_NO_MATCH) without crashing
        viewModel.wakeRecognitionListener.onError(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        viewModel.wakeRecognitionListener.onError(android.speech.SpeechRecognizer.ERROR_NO_MATCH)

        viewModel.cleanShutdown(null)
    }

    @Test
    fun testSpeechRecognizerIntentSilenceTimeoutOverrides() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        val intent = viewModel.createWakeRecognizerIntent()

        assertEquals(
            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            intent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL)
        )
        assertEquals(
            3000L,
            intent.getLongExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 0L)
        )
        assertEquals(
            3000L,
            intent.getLongExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 0L)
        )
        assertEquals(
            5000L,
            intent.getLongExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 0L)
        )

        viewModel.cleanShutdown(null)
    }

    @Test
    fun testWakeWordReTriggerForcesStateResetWhenAlreadyInChatOrHoldingStaleDraft() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)

        val customChat = com.example.data.model.ChatSession(
            id = 99L,
            title = "Quantum Research",
            wakeWord = "Hey Atlas",
            systemInstruction = "Research assistant",
            themeColorHex = "#38BDF8",
            iconName = "Sparkles",
            isDefaultWakeChat = true
        )
        viewModel.wakeWordManager.updateActiveChats(listOf(customChat))
        viewModel.selectChat(99L)

        // Simulate user already in ChatDetailScreen with an unsent partial draft in the buffer
        val partialBundle = android.os.Bundle().apply {
            putStringArrayList(
                android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("unfinished stale draft")
            )
        }
        viewModel.wakeRecognitionListener.onPartialResults(partialBundle)
        assertEquals("unfinished stale draft", viewModel.currentDraftText.value)
        val retriggerIdBefore = viewModel.wakeRetriggerEventId.value

        // Re-trigger wake word while already in ChatDetailScreen: must force state reset and clear stale draft
        viewModel.wakeWordManager.onKeywordHit("Hey Atlas", initialQuery = null)
        assertEquals("", viewModel.currentDraftText.value)
        assertEquals("", viewModel.wakeWordManager.lastHeardText.value)
        assertEquals(retriggerIdBefore + 1L, viewModel.wakeRetriggerEventId.value)
        assertTrue(viewModel.isUserVoiceTurn.value)
        assertEquals(HandsFreeVoiceState.TRANSCRIBING, viewModel.wakeWordManager.handsFreeState.value)

        viewModel.cleanShutdown(null)
    }

    @Test
    fun testSpeechRecognizerAutoDispatchOnResultsAndManualSendBufferFlush() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)

        val customChat = com.example.data.model.ChatSession(
            id = 99L,
            title = "Quantum Research",
            wakeWord = "Hey Atlas",
            systemInstruction = "Research assistant",
            themeColorHex = "#38BDF8",
            iconName = "Sparkles",
            isDefaultWakeChat = true
        )
        viewModel.wakeWordManager.updateActiveChats(listOf(customChat))
        viewModel.selectChat(99L)

        // 1. SpeechRecognizer onResults() automatically dispatches finalized spoken text to Gemini
        val finalBundle = android.os.Bundle().apply {
            putStringArrayList(
                android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("Explain quantum tunneling")
            )
        }
        viewModel.wakeRecognitionListener.onResults(finalBundle)
        assertEquals("Explain quantum tunneling", viewModel.lastDispatchedVoicePrompt.value)
        assertEquals("", viewModel.currentDraftText.value)
        assertEquals("", viewModel.wakeWordManager.lastHeardText.value)

        // Reset generation state so next manual send can proceed immediately in unit test
        viewModel.selectChat(null)
        viewModel.selectChat(99L)

        // 2. Manual Send button grabs partial transcription from buffer, stops SpeechRecognizer, and dispatches immediately
        val partialBundle = android.os.Bundle().apply {
            putStringArrayList(
                android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("Summarize the Schrödinger equation")
            )
        }
        viewModel.wakeRecognitionListener.onPartialResults(partialBundle)
        assertEquals("Summarize the Schrödinger equation", viewModel.currentDraftText.value)

        // Tapping manual Send with empty inputText argument still grabs the buffered partial transcription
        viewModel.sendMessage(chatId = 99L, text = "")
        assertFalse(viewModel.isSpeechRecognizerListening.value)
        assertEquals("Summarize the Schrödinger equation", viewModel.lastDispatchedVoicePrompt.value)
        assertEquals("", viewModel.currentDraftText.value)
        assertEquals("", viewModel.wakeWordManager.lastHeardText.value)

        viewModel.cleanShutdown(null)
    }

    @Test
    fun testAudioRecordError20MutualExclusionAndExponentialBackoff() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        val manager = viewModel.wakeWordManager

        // 1. Verify exponential backoff progression (500ms -> 1000ms -> 2000ms -> 4000ms cap)
        assertEquals(3, WakeWordManager.MAX_AUDIO_RECORD_INIT_RETRIES)
        assertEquals(500L, WakeWordManager.calculateExponentialBackoffMs(0))
        assertEquals(1000L, WakeWordManager.calculateExponentialBackoffMs(1))
        assertEquals(2000L, WakeWordManager.calculateExponentialBackoffMs(2))
        assertEquals(4000L, WakeWordManager.calculateExponentialBackoffMs(3))
        assertEquals(4000L, WakeWordManager.calculateExponentialBackoffMs(10))

        // 2. Verify mutual exclusion: when SpeechRecognizer is active, AudioRecord is released and never created concurrently
        manager.isExternalSpeechRecognizerActive = false
        manager.startContinuousListening(forceActiveTurn = false)
        assertTrue(manager.activeAudioRecordCount <= 1)

        manager.isExternalSpeechRecognizerActive = true
        viewModel.releaseAllNativeAudioRecordTracksForSpeechRecognizer()
        assertEquals(0, manager.activeAudioRecordCount)

        // Attempting to start continuous PCM listening while SpeechRecognizer is active must NOT allocate an AudioRecord
        manager.startContinuousListening(forceActiveTurn = true)
        assertEquals(0, manager.activeAudioRecordCount)

        // 3. Verify permission guard: revoking RECORD_AUDIO prevents AudioRecord initialization
        manager.isExternalSpeechRecognizerActive = false
        org.robolectric.Shadows.shadowOf(app)
            .denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        assertFalse(manager.hasRecordAudioPermission())
        manager.startContinuousListening(forceActiveTurn = false)
        assertEquals(0, manager.activeAudioRecordCount)
        assertEquals(
            "Microphone permission required for wake-word listening",
            manager.statusMessage.value
        )

        // Restore permission for cleanup
        org.robolectric.Shadows.shadowOf(app)
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        viewModel.cleanShutdown(null)
    }
}
