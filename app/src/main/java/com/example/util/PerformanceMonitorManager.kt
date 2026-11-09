package com.example.util

import android.os.SystemClock
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

data class PerformanceTelemetrySnapshot(
    val startupDurationMs: Long? = null,
    val isStartupTraceCompleted: Boolean = false,
    val isRequestInFlight: Boolean = false,
    val lastTtftMs: Long? = null,
    val averageTtftMs: Long? = null,
    val lastTotalDurationMs: Long? = null,
    val lastAudioChunkCount: Int = 0,
    val lastTextChunkCount: Int = 0,
    val lastTranscribeDurationMs: Long? = null,
    val totalTrackedRequests: Int = 0,
    val lastModelName: String = "gemini-3.8-flash",
    val lastRequestStatus: String = "idle"
)

/**
 * Coordinates Firebase Performance Monitoring custom code traces alongside
 * an in-app reactive telemetry StateFlow for startup time and Gemini API latency.
 */
object PerformanceMonitorManager {
    private const val TAG = "PerfMonitor"

    const val TRACE_APP_STARTUP = "app_startup_first_frame"
    const val TRACE_GEMINI_STREAM = "gemini_chat_stream"
    const val TRACE_GEMINI_TRANSCRIBE = "gemini_audio_transcribe"

    const val METRIC_STARTUP_MS = "startup_first_frame_ms"
    const val METRIC_TTFT_MS = "ttft_ms"
    const val METRIC_TOTAL_DURATION_MS = "total_duration_ms"
    const val METRIC_AUDIO_CHUNKS = "audio_chunks"
    const val METRIC_TEXT_CHUNKS = "text_chunks"
    const val METRIC_TRANSCRIBE_MS = "transcribe_duration_ms"
    const val METRIC_AUDIO_BYTES = "audio_bytes"

    private val _telemetry = MutableStateFlow(PerformanceTelemetrySnapshot())
    val telemetry: StateFlow<PerformanceTelemetrySnapshot> = _telemetry.asStateFlow()

    @Volatile
    private var startupStartRealtimeMs: Long = 0L

    @Volatile
    private var startupTrace: Trace? = null

    private val startupCompleted = AtomicBoolean(false)

    private val ttftSamplesMs = mutableListOf<Long>()

    /**
     * Clock provider that uses SystemClock.elapsedRealtime() when available, falling back
     * to System.currentTimeMillis() in plain JVM tests.
     */
    internal var clockMsProvider: () -> Long = {
        try {
            val elapsed = SystemClock.elapsedRealtime()
            if (elapsed > 0L) elapsed else System.currentTimeMillis()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }

    private fun logDebug(message: String) {
        try {
            Log.d(TAG, message)
        } catch (_: Throwable) {}
    }

    private fun logWarn(message: String) {
        try {
            Log.w(TAG, message)
        } catch (_: Throwable) {}
    }

    private fun createFirebaseTraceOrNull(traceName: String): Trace? {
        return try {
            if (FirebaseApp.getApps(FirebaseApp.getInstance().applicationContext).isNotEmpty()) {
                FirebasePerformance.getInstance().newTrace(traceName)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Starts a custom trace at the beginning of MainActivity.onCreate() to measure
     * time until the first Jetpack Compose frame is rendered.
     */
    @Synchronized
    fun startAppStartupTrace(launchMode: String = "cold_or_warm") {
        if (startupCompleted.get() || startupStartRealtimeMs > 0L) return
        startupStartRealtimeMs = clockMsProvider()
        startupTrace = createFirebaseTraceOrNull(TRACE_APP_STARTUP)?.apply {
            try {
                putAttribute("launch_mode", launchMode)
                start()
            } catch (e: Throwable) {
                logWarn("Unable to start Firebase startup trace: ${e.message}")
            }
        }
    }

    /**
     * Stops the custom startup trace once the first Compose frame has been drawn.
     */
    @Synchronized
    fun stopAppStartupTrace(): Long? {
        if (startupCompleted.getAndSet(true)) {
            return _telemetry.value.startupDurationMs
        }
        val now = clockMsProvider()
        val start = if (startupStartRealtimeMs > 0L) startupStartRealtimeMs else now
        val durationMs = (now - start).coerceAtLeast(1L)

        startupTrace?.let { trace ->
            try {
                trace.putMetric(METRIC_STARTUP_MS, durationMs)
                trace.stop()
            } catch (e: Throwable) {
                logWarn("Unable to stop Firebase startup trace: ${e.message}")
            }
        }
        startupTrace = null

        _telemetry.update { current ->
            current.copy(
                startupDurationMs = durationMs,
                isStartupTraceCompleted = true
            )
        }
        logDebug("App startup (onCreate -> first Compose frame) completed in ${durationMs}ms")
        return durationMs
    }

    /**
     * Begins a custom trace session for a streaming Gemini chat/voice request.
     */
    fun startGeminiStreamTrace(
        modelName: String = "gemini-3.8-flash",
        requestAudio: Boolean = true,
        hasAttachment: Boolean = false
    ): GeminiStreamTraceSession {
        val startMs = clockMsProvider()
        val trace = createFirebaseTraceOrNull(TRACE_GEMINI_STREAM)?.apply {
            try {
                putAttribute("model", modelName)
                putAttribute("request_audio", requestAudio.toString())
                putAttribute("has_attachment", hasAttachment.toString())
                start()
            } catch (e: Throwable) {
                logWarn("Unable to start Gemini stream trace: ${e.message}")
            }
        }
        _telemetry.update {
            it.copy(
                isRequestInFlight = true,
                lastModelName = modelName,
                lastRequestStatus = "streaming"
            )
        }
        return GeminiStreamTraceSession(
            modelName = modelName,
            startMs = startMs,
            trace = trace
        )
    }

    /**
     * Records a completed Gemini stream trace session into Firebase Performance and the live UI flow.
     */
    @Synchronized
    internal fun recordCompletedStreamSession(
        modelName: String,
        ttftMs: Long?,
        totalDurationMs: Long,
        audioChunks: Int,
        textChunks: Int,
        status: String,
        trace: Trace?
    ) {
        val resolvedTtft = ttftMs ?: totalDurationMs
        ttftSamplesMs.add(resolvedTtft)
        if (ttftSamplesMs.size > 50) {
            ttftSamplesMs.removeAt(0)
        }
        val avgTtft = ttftSamplesMs.average().toLong()

        trace?.let { activeTrace ->
            try {
                activeTrace.putAttribute("status", status.take(40))
                activeTrace.putMetric(METRIC_TTFT_MS, resolvedTtft)
                activeTrace.putMetric(METRIC_TOTAL_DURATION_MS, totalDurationMs)
                activeTrace.putMetric(METRIC_AUDIO_CHUNKS, audioChunks.toLong())
                activeTrace.putMetric(METRIC_TEXT_CHUNKS, textChunks.toLong())
                activeTrace.stop()
            } catch (e: Throwable) {
                logWarn("Unable to stop Gemini stream trace: ${e.message}")
            }
        }

        _telemetry.update { current ->
            current.copy(
                isRequestInFlight = false,
                lastTtftMs = resolvedTtft,
                averageTtftMs = avgTtft,
                lastTotalDurationMs = totalDurationMs,
                lastAudioChunkCount = audioChunks,
                lastTextChunkCount = textChunks,
                totalTrackedRequests = current.totalTrackedRequests + 1,
                lastModelName = modelName,
                lastRequestStatus = status
            )
        }
        logDebug(
            "Gemini stream trace [$modelName]: TTFT=${resolvedTtft}ms, Total=${totalDurationMs}ms, " +
                "audioChunks=$audioChunks, textChunks=$textChunks, status=$status"
        )
    }

    /**
     * Wraps a Gemini audio transcription call in a custom Firebase Performance trace.
     */
    suspend fun traceAudioTranscription(
        modelName: String = "gemini-3.8-flash",
        mimeType: String,
        audioBytesCount: Int,
        block: suspend () -> String
    ): String {
        val startMs = clockMsProvider()
        val trace = createFirebaseTraceOrNull(TRACE_GEMINI_TRANSCRIBE)?.apply {
            try {
                putAttribute("model", modelName)
                putAttribute("mime_type", mimeType.take(40))
                putMetric(METRIC_AUDIO_BYTES, audioBytesCount.toLong())
                start()
            } catch (e: Throwable) {
                logWarn("Unable to start transcription trace: ${e.message}")
            }
        }
        var status = "success"
        return try {
            val transcript = block()
            if (transcript.isBlank()) {
                status = "empty"
            }
            transcript
        } catch (e: Throwable) {
            status = "error"
            throw e
        } finally {
            val durationMs = (clockMsProvider() - startMs).coerceAtLeast(1L)
            trace?.let { activeTrace ->
                try {
                    activeTrace.putAttribute("status", status)
                    activeTrace.putMetric(METRIC_TRANSCRIBE_MS, durationMs)
                    activeTrace.stop()
                } catch (e: Throwable) {
                    logWarn("Unable to stop transcription trace: ${e.message}")
                }
            }
            _telemetry.update { current ->
                current.copy(
                    lastTranscribeDurationMs = durationMs,
                    lastModelName = modelName
                )
            }
        }
    }

    /**
     * Resets session Gemini API latency counters while optionally preserving the recorded startup time.
     */
    @Synchronized
    fun resetSessionTelemetry(preserveStartup: Boolean = true) {
        ttftSamplesMs.clear()
        if (!preserveStartup) {
            startupCompleted.set(false)
            startupStartRealtimeMs = 0L
            startupTrace = null
        }
        _telemetry.update { current ->
            PerformanceTelemetrySnapshot(
                startupDurationMs = if (preserveStartup) current.startupDurationMs else null,
                isStartupTraceCompleted = if (preserveStartup) current.isStartupTraceCompleted else false
            )
        }
    }

    class GeminiStreamTraceSession internal constructor(
        private val modelName: String,
        private val startMs: Long,
        private val trace: Trace?
    ) {
        private var firstTokenRecorded = false
        private var ttftMs: Long? = null
        private var audioChunkCount = 0
        private var textChunkCount = 0
        private val finished = AtomicBoolean(false)

        @Synchronized
        fun recordChunk(isAudio: Boolean) {
            if (!firstTokenRecorded) {
                firstTokenRecorded = true
                ttftMs = (clockMsProvider() - startMs).coerceAtLeast(1L)
            }
            if (isAudio) {
                audioChunkCount++
            } else {
                textChunkCount++
            }
        }

        @Synchronized
        fun finish(status: String = "success") {
            if (finished.getAndSet(true)) return
            val totalDurationMs = (clockMsProvider() - startMs).coerceAtLeast(1L)
            recordCompletedStreamSession(
                modelName = modelName,
                ttftMs = ttftMs,
                totalDurationMs = totalDurationMs,
                audioChunks = audioChunkCount,
                textChunks = textChunkCount,
                status = status,
                trace = trace
            )
        }
    }
}
