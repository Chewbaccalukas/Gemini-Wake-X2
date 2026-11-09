package com.example.ai

import android.util.Base64
import android.util.Log
import com.example.data.model.ChatMessage
import com.example.voice.WakeWordManager
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "GeminiApiService"

data class GeminiStreamChunk(
    val text: String? = null,
    val pcmAudioBytes: ByteArray? = null,
    val isAudio: Boolean = false,
    val presentationTimestampMs: Long = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GeminiStreamChunk
        if (text != other.text) return false
        if (pcmAudioBytes != null) {
            if (other.pcmAudioBytes == null) return false
            if (!pcmAudioBytes.contentEquals(other.pcmAudioBytes)) return false
        } else if (other.pcmAudioBytes != null) return false
        if (isAudio != other.isAudio) return false
        return presentationTimestampMs == other.presentationTimestampMs
    }

    override fun hashCode(): Int {
        var result = text?.hashCode() ?: 0
        result = 31 * result + (pcmAudioBytes?.contentHashCode() ?: 0)
        result = 31 * result + isAudio.hashCode()
        result = 31 * result + presentationTimestampMs.hashCode()
        return result
    }
}

/**
 * GeminiApiService handles communication with Gemini 3.8 / 2.5 models
 * supporting audio modalities and Orion voice speech_config.
 */
class GeminiApiService {

    companion object {
        const val ORION_VOICE_NAME = "Fenrir"
        const val AUDIO_MODALITY = "AUDIO"
        const val TEXT_MODALITY = "TEXT"

        /**
         * Generates the standard generationConfig for Gemini Neural Voice (Orion).
         */
        fun buildOrionVoiceGenerationConfig(includeText: Boolean = true): JSONObject {
            val modalities = JSONArray().apply {
                put(AUDIO_MODALITY)
                if (includeText) {
                    put(TEXT_MODALITY)
                }
            }

            val speechConfig = JSONObject().apply {
                put("voice_config", JSONObject().apply {
                    put("prebuilt_voice_config", JSONObject().apply {
                        put("voice_name", ORION_VOICE_NAME)
                    })
                })
            }

            return JSONObject().apply {
                put("response_modalities", modalities)
                put("speech_config", speechConfig)
            }
        }

        /**
         * Sanitizes outgoing user prompt payload to remove unspaced duplicate concatenation artifacts.
         */
        fun sanitizePayload(text: String): String {
            val trimmed = text.trim()
            val half = trimmed.length / 2
            return if (trimmed.length >= 8 && trimmed.length % 2 == 0 && trimmed.take(half) == trimmed.substring(half)) {
                Log.w("PayloadSanitizer", "De-duplicated unspaced payload: $trimmed")
                trimmed.take(half)
            } else {
                trimmed
            }
        }
    }

    /**
     * Streams responses requesting Orion neural voice and text chunks.
     * Catches HTTP 429, RESOURCE_EXHAUSTED, and spend cap errors, failovers to backup key, and retries turn.
     */
    fun streamChatWithOrionVoice(
        context: android.content.Context? = null,
        systemInstruction: String,
        history: List<ChatMessage>,
        userPrompt: String,
        userAudioBytes: ByteArray? = null,
        attachmentBytes: ByteArray? = null,
        attachmentMimeType: String? = null,
        requestAudio: Boolean = true
    ): Flow<GeminiStreamChunk> = flow {
        val apiKeyManager = context?.let { ApiKeyManager.getInstance(it) }
        val sanitizedUserPrompt = sanitizePayload(userPrompt)
        var retriedAfterFailover = false

        while (true) {
            var voiceStreamSuccess = false
            var caughtError: Exception? = null

            val hasAudio = userAudioBytes != null &&
                WakeWordManager.shouldTransmitAudioPacket(userAudioBytes.size, userAudioBytes)
            val hasAttachment = attachmentBytes != null && attachmentBytes.isNotEmpty() && !attachmentMimeType.isNullOrBlank()

            val userContent = content("user") {
                if (hasAttachment) {
                    inlineData(attachmentBytes!!, attachmentMimeType!!)
                }
                if (hasAudio) {
                    inlineData(userAudioBytes!!, "audio/pcm;rate=16000")
                }
                if (sanitizedUserPrompt.isNotEmpty()) {
                    text(sanitizedUserPrompt)
                }
            }

            if (requestAudio) {
                try {
                    val orionInstruction = "$systemInstruction (Voice model: Orion neural speech enabled)"
                    val model = Firebase.ai.generativeModel(
                        modelName = "gemini-3.8-flash",
                        tools = listOf(Tool.googleSearch()),
                        systemInstruction = content { text(orionInstruction) }
                    )

                    val chronologicalHistory = history
                        .filter { !it.isDeleted && it.content.isNotBlank() }
                        .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
                    val historyContent = chronologicalHistory.map { msg ->
                        val role = if (msg.sender == "user") "user" else "model"
                        content(role) { text(msg.content) }
                    }

                    val chatSession = model.startChat(history = historyContent)

                    withTimeout(60_000L) {
                        val stream = if (hasAudio || hasAttachment) {
                            chatSession.sendMessageStream(userContent)
                        } else {
                            chatSession.sendMessageStream(sanitizedUserPrompt)
                        }
                        stream.collect { chunk ->
                            val chunkTimestamp = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
                            // Extract any inline PCM audio parts from candidates for 24000 Hz AudioTrack streaming
                            chunk.candidates.forEach { candidate ->
                                candidate.content.parts.forEach { part ->
                                    if (part is InlineDataPart &&
                                        part.mimeType.lowercase().startsWith("audio") &&
                                        WakeWordManager.shouldTransmitAudioPacket(part.inlineData.size, part.inlineData)
                                    ) {
                                        voiceStreamSuccess = true
                                        emit(
                                            GeminiStreamChunk(
                                                pcmAudioBytes = part.inlineData,
                                                isAudio = true,
                                                presentationTimestampMs = chunkTimestamp
                                            )
                                        )
                                    }
                                }
                            }
                            val text = chunk.text.orEmpty()
                            if (text.isNotEmpty()) {
                                voiceStreamSuccess = true
                                emit(
                                    GeminiStreamChunk(
                                        text = text,
                                        isAudio = false,
                                        presentationTimestampMs = chunkTimestamp
                                    )
                                )
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    caughtError = e
                    Log.w(TAG, "Audio stream attempt failed: ${e.message}")
                }
            }

            // If audio wasn't requested or threw an exception, fall back gracefully to text
            if (!voiceStreamSuccess) {
                try {
                    val fallbackModel = Firebase.ai.generativeModel(
                        modelName = "gemini-3.8-flash",
                        tools = listOf(Tool.googleSearch()),
                        systemInstruction = content { text(systemInstruction) }
                    )
                    val chronologicalHistory = history
                        .filter { !it.isDeleted && it.content.isNotBlank() }
                        .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
                    val historyContent = chronologicalHistory.map { msg ->
                        val role = if (msg.sender == "user") "user" else "model"
                        content(role) { text(msg.content) }
                    }
                    val chat = fallbackModel.startChat(history = historyContent)
                    withTimeout(60_000L) {
                        val stream = if (hasAudio || hasAttachment) {
                            chat.sendMessageStream(userContent)
                        } else {
                            chat.sendMessageStream(sanitizedUserPrompt)
                        }
                        stream.collect { chunk ->
                            val text = chunk.text.orEmpty()
                            if (text.isNotEmpty()) {
                                emit(GeminiStreamChunk(text = text, isAudio = false))
                            }
                        }
                    }
                    caughtError = null // Successfully completed fallback text stream
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    caughtError = e
                }
            }

            if (caughtError != null) {
                if (!retriedAfterFailover && apiKeyManager != null && apiKeyManager.isQuotaOr429Error(caughtError)) {
                    val failoverActivated = apiKeyManager.handleQuotaExhaustionAndFailover()
                    if (failoverActivated) {
                        retriedAfterFailover = true
                        Log.i(TAG, "HTTP 429 / RESOURCE_EXHAUSTED encountered. Retrying execution with Backup Key...")
                        continue // Retry loop with new active backup key!
                    }
                }
                Log.e(TAG, "Streaming failed: ${caughtError.message}", caughtError)
                throw caughtError
            }

            break // Request succeeded
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Transcribes audio bytes using gemini-3.5-transcribe model.
     */
    suspend fun transcribeAudio(audioBytes: ByteArray, mimeType: String = "audio/wav"): String = withContext(Dispatchers.IO) {
        try {
            val model = Firebase.ai.generativeModel(modelName = "gemini-3.5-transcribe")
            val response = withTimeout(30_000L) {
                model.generateContent(
                    content {
                        inlineData(audioBytes, mimeType)
                        text("Transcribe this audio accurately.")
                    }
                )
            }
            response.text ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed: ${e.message}")
            ""
        }
    }
}
