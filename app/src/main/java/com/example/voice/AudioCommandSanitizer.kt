package com.example.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

enum class HandsFreeVoiceState(val displayName: String) {
    IDLE("Idle"),
    LISTENING("Listening"),
    TRANSCRIBING("Transcribing"),
    HEARTBEAT_PING("Heartbeat Ping"),
    SLEEPING("Sleep Mode")
}

/**
 * Predefined wake word sensitivity levels (Low, Medium, High) alongside custom slider tuning.
 */
enum class WakeSensitivityLevel(
    val label: String,
    val sensitivityValue: Float,
    val description: String
) {
    LOW(
        label = "Low",
        sensitivityValue = 0.62f,
        description = "Easy trigger — responds more readily in noisy rooms or soft speech"
    ),
    MEDIUM(
        label = "Medium",
        sensitivityValue = 0.78f,
        description = "Balanced accuracy and responsiveness"
    ),
    HIGH(
        label = "High",
        sensitivityValue = 0.90f,
        description = "Strict match — requires clear pronunciation to avoid false triggers"
    );

    companion object {
        fun fromSensitivity(value: Float): WakeSensitivityLevel {
            val clamped = value.coerceIn(0.50f, 0.98f)
            return when {
                clamped < 0.70f -> LOW
                clamped >= 0.85f -> HIGH
                else -> MEDIUM
            }
        }
    }
}

/**
 * AudioCommandSanitizer detects custom and default trigger phrases (Dictate, Send, Heartbeat, Sleep, Wake Up)
 * and scrubs internal action commands from speech buffers so command words never bleed into
 * Room database entries or Gemini AI prompts.
 */
object AudioCommandSanitizer {
    const val DEFAULT_DICTATE_TRIGGER = "Dictate"
    const val DEFAULT_SEND_TRIGGER = "Send"
    const val DEFAULT_HEARTBEAT_TRIGGER = "Heartbeat"
    const val DEFAULT_SLEEP_COMMAND = "Gemini, sleep"
    const val DEFAULT_WAKE_UP_COMMAND = "Gemini, wake up"

    val DEFAULT_DICTATE_SYNONYMS = listOf("dictate", "take note", "start dictation")
    val DEFAULT_SEND_SYNONYMS = listOf("send", "over", "send message", "dispatch")
    val DEFAULT_HEARTBEAT_SYNONYMS = listOf("heartbeat", "pulse", "ping", "heartbeat ping")
    val DEFAULT_SLEEP_SYNONYMS = listOf(
        "gemini, sleep",
        "gemini sleep",
        "hey gemini sleep",
        "hey gemini, sleep",
        "ok gemini sleep",
        "go to sleep",
        "sleep mode",
        "gemini pause listening"
    )
    val DEFAULT_WAKE_UP_SYNONYMS = listOf(
        "gemini, wake up",
        "gemini wake up",
        "gemini wake",
        "hey gemini wake up",
        "hey gemini, wake up",
        "hey gemini wake",
        "wake up gemini",
        "wake up",
        "resume listening",
        "gemini resume"
    )
    val DEFAULT_WAKE_WORDS = listOf("open", "gemini", "hey gemini", "ok gemini", "jimmy")

    /**
     * Checks if spoken text is a sleep command (e.g., "Gemini, sleep") to temporarily disable the wake word listener.
     */
    fun containsSleepCommand(text: String, customSleepCommand: String = DEFAULT_SLEEP_COMMAND): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isBlank()) return false
        val targets = (listOf(customSleepCommand.lowercase().trim()) + DEFAULT_SLEEP_SYNONYMS)
            .filter { it.isNotBlank() }
            .distinct()
        return targets.any { pattern ->
            val normalizedPattern = pattern.replace(",", "\\s*,?\\s*")
            Regex("(^|\\b)$normalizedPattern(\\b|$|[.!?])", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        }
    }

    /**
     * Checks if spoken text is a wake-up command (e.g., "Gemini, wake up") to re-enable the wake word listener from sleep mode.
     */
    fun containsWakeUpCommand(text: String, customWakeUpCommand: String = DEFAULT_WAKE_UP_COMMAND): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isBlank()) return false
        val targets = (listOf(customWakeUpCommand.lowercase().trim()) + DEFAULT_WAKE_UP_SYNONYMS)
            .filter { it.isNotBlank() }
            .distinct()
        return targets.any { pattern ->
            val normalizedPattern = pattern.replace(",", "\\s*,?\\s*")
            Regex("(^|\\b)$normalizedPattern(\\b|$|[.!?])", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        }
    }

    /**
     * Computes normalized character-level similarity [0.0..1.0] between two strings for sensitivity-scaled wake phrase matching.
     */
    fun computePhraseSimilarity(a: String, b: String): Float {
        val s1 = a.trim().lowercase().replace(Regex("[^a-z0-9\\s]"), "").replace(Regex("\\s+"), " ")
        val s2 = b.trim().lowercase().replace(Regex("[^a-z0-9\\s]"), "").replace(Regex("\\s+"), " ")
        if (s1 == s2) return 1.0f
        if (s1.isEmpty() || s2.isEmpty()) return 0.0f
        val maxLen = maxOf(s1.length, s2.length)
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        val dist = dp[s2.length]
        return (1.0f - dist.toFloat() / maxLen.toFloat()).coerceIn(0.0f, 1.0f)
    }

    /**
     * Strips any leading or standalone wake word trigger (e.g. "open", custom chat wakeWord,
     * "hey gemini") from spoken or buffered text so the wake word itself is never injected
     * into the user prompt or Gemini chat message.
     */
    fun stripWakeWordTrigger(
        rawText: String,
        customWakeWords: Collection<String> = emptyList()
    ): String {
        var cleaned = rawText.trim()
        if (cleaned.isBlank()) return ""

        val wakeTargets = (customWakeWords.map { it.trim() } + DEFAULT_WAKE_WORDS)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            // Sort longest first so multi-word wake phrases ("hey gemini") match before single words ("gemini")
            .sortedByDescending { it.length }

        var changed = true
        while (changed && cleaned.isNotBlank()) {
            changed = false
            for (wake in wakeTargets) {
                val standaloneRegex = Regex("(?i)^[,\\s.!?-]*${Regex.escape(wake)}[,\\s.!?-]*$")
                if (standaloneRegex.matches(cleaned)) {
                    return ""
                }
                val leadingRegex = Regex("(?i)^${Regex.escape(wake)}\\b[,:\\s.!?-]*")
                if (leadingRegex.containsMatchIn(cleaned)) {
                    cleaned = cleaned.replaceFirst(leadingRegex, "").trim()
                    changed = true
                }
            }
        }
        return cleaned
    }

    /**
     * Returns true if the text is empty or consists solely of a wake word trigger phrase (such as "open").
     */
    fun isPureWakeWordTrigger(
        rawText: String?,
        customWakeWords: Collection<String> = emptyList()
    ): Boolean {
        if (rawText.isNullOrBlank()) return true
        return stripWakeWordTrigger(rawText, customWakeWords).isBlank()
    }

    /**
     * Checks if spoken text contains a heartbeat trigger phrase.
     */
    fun containsHeartbeatTrigger(text: String, customTrigger: String = DEFAULT_HEARTBEAT_TRIGGER): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isBlank()) return false
        val targets = (listOf(customTrigger.lowercase().trim()) + DEFAULT_HEARTBEAT_SYNONYMS).filter { it.isNotBlank() }.distinct()
        return targets.any { pattern ->
            Regex("(^|\\b)${Regex.escape(pattern)}(\\b|$|[.!?])", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        }
    }

    /**
     * Checks if spoken text ends with or contains a send trigger phrase.
     */
    fun containsSendTrigger(text: String, customTrigger: String = DEFAULT_SEND_TRIGGER): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isBlank()) return false
        val targets = (listOf(customTrigger.lowercase().trim()) + DEFAULT_SEND_SYNONYMS).filter { it.isNotBlank() }.distinct()
        return targets.any { pattern ->
            Regex("(^|\\b)${Regex.escape(pattern)}(\\b|$|[.!?])", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        }
    }

    /**
     * Checks if spoken text contains a dictate trigger phrase.
     */
    fun containsDictateTrigger(text: String, customTrigger: String = DEFAULT_DICTATE_TRIGGER): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isBlank()) return false
        val targets = (listOf(customTrigger.lowercase().trim()) + DEFAULT_DICTATE_SYNONYMS).filter { it.isNotBlank() }.distinct()
        return targets.any { pattern ->
            Regex("(^|\\b)${Regex.escape(pattern)}(\\b|$|[.!?])", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        }
    }

    /**
     * Extracts dictation content after the dictate trigger phrase.
     * E.g. "Dictate what is the capital of France" -> "what is the capital of France".
     */
    fun extractContentAfterDictate(text: String, customTrigger: String = DEFAULT_DICTATE_TRIGGER): String {
        val targets = (listOf(customTrigger.lowercase().trim()) + DEFAULT_DICTATE_SYNONYMS).filter { it.isNotBlank() }.distinct()
        val lower = text.lowercase()
        for (pattern in targets) {
            val match = Regex("(^|\\b)${Regex.escape(pattern)}(\\b|:|,|\\s*)", RegexOption.IGNORE_CASE).find(lower)
            if (match != null) {
                val candidate = text.substring(match.range.last + 1).trim()
                if (candidate.isNotBlank()) return candidate
            }
        }
        return text.trim()
    }

    /**
     * Scrubs recognized action phrases ("Send", "Over", "Heartbeat", "Pulse", custom triggers)
     * and wake-word prefixes from the transcribed buffer prior to dispatching to Room or Gemini API.
     */
    fun sanitizeSpokenText(
        text: String,
        sendTrigger: String = DEFAULT_SEND_TRIGGER,
        heartbeatTrigger: String = DEFAULT_HEARTBEAT_TRIGGER,
        wakeWords: Collection<String> = emptyList()
    ): String {
        var result = stripWakeWordTrigger(text.trim(), wakeWords)
        if (result.isBlank()) return ""

        val allActionWords = (
            listOf(sendTrigger.lowercase().trim(), heartbeatTrigger.lowercase().trim()) +
            DEFAULT_SEND_SYNONYMS +
            DEFAULT_HEARTBEAT_SYNONYMS
        ).filter { it.isNotBlank() }.distinct()

        for (action in allActionWords) {
            // 1. Scrub trailing trigger command (e.g. "What is the speed of light? Send", "Code refactor over.")
            val trailingRegex = Regex("(?i)[,\\s-]*\\b${Regex.escape(action)}\\b[.!?\\s]*$")
            result = result.replace(trailingRegex, "").trim()

            // 2. Scrub leading trigger command if spoken as a bare prefix
            val leadingRegex = Regex("(?i)^${Regex.escape(action)}\\b[,\\s-]*")
            result = result.replace(leadingRegex, "").trim()
        }

        result = stripWakeWordTrigger(result, wakeWords)

        // Clean up any double spaces or orphan punctuation
        result = result.replace(Regex("\\s+"), " ")
        result = result.trim().removeSuffix(",").removeSuffix("-").trim()
        return result
    }

    private val INTERNAL_STATUS_OR_COMMAND_STRINGS = setOf(
        "idle",
        "listening",
        "listening...",
        "processing",
        "processing...",
        "transcribing",
        "transcribing voice",
        "heartbeat",
        "heartbeat ping",
        "hands-free standby",
        "listening paused",
        "tap to start listening",
        "listening via hardware mic (audiorecord)...",
        "dictate",
        "send",
        "over",
        "dispatch",
        "pulse",
        "ping",
        "open",
        "ping received, orion voice online"
    )

    /**
     * Returns true if the candidate string is an internal system status, debug prompt,
     * wake word trigger, or command trigger that must NEVER be passed to StreamingVoicePlayer
     * or injected as a user chat message.
     */
    fun isSystemOrCommandStatusString(
        text: String?,
        dictateTrigger: String = DEFAULT_DICTATE_TRIGGER,
        sendTrigger: String = DEFAULT_SEND_TRIGGER,
        heartbeatTrigger: String = DEFAULT_HEARTBEAT_TRIGGER,
        wakeWords: Collection<String> = emptyList()
    ): Boolean {
        val normalized = text?.trim()?.lowercase()?.removeSuffix(".")?.removeSuffix("!") ?: return true
        if (normalized.isEmpty()) return true
        if (INTERNAL_STATUS_OR_COMMAND_STRINGS.contains(normalized)) return true
        val dynamicTriggers = (
            listOf(
                dictateTrigger.trim().lowercase(),
                sendTrigger.trim().lowercase(),
                heartbeatTrigger.trim().lowercase()
            ) + DEFAULT_WAKE_WORDS + wakeWords.map { it.trim().lowercase() }
        ).filter { it.isNotBlank() }.toSet()
        return dynamicTriggers.contains(normalized)
    }

    /**
     * Triggers silent haptic feedback pulse upon heartbeat trigger interception without synthesizing status audio.
     */
    fun playHeartbeatConfirmation(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(150)
            }
        } catch (e: Exception) {
            Log.w("AudioCommandSanitizer", "Could not trigger haptic: ${e.message}")
        }
    }
}
