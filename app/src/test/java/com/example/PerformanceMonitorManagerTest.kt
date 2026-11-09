package com.example

import com.example.util.PerformanceMonitorManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PerformanceMonitorManagerTest {

    private var fakeClockMs = 1_000L

    @Before
    fun setUp() {
        fakeClockMs = 1_000L
        PerformanceMonitorManager.clockMsProvider = { fakeClockMs }
        PerformanceMonitorManager.resetSessionTelemetry(preserveStartup = false)
    }

    @After
    fun tearDown() {
        PerformanceMonitorManager.resetSessionTelemetry(preserveStartup = false)
    }

    @Test
    fun `startup trace records onCreate to first Compose frame duration once`() {
        PerformanceMonitorManager.startAppStartupTrace("cold_start")
        fakeClockMs += 185L

        val measured = PerformanceMonitorManager.stopAppStartupTrace()
        assertEquals(185L, measured)

        val snapshot = PerformanceMonitorManager.telemetry.value
        assertTrue(snapshot.isStartupTraceCompleted)
        assertEquals(185L, snapshot.startupDurationMs)

        // Calling stopAppStartupTrace a second time preserves the initial first-frame duration
        fakeClockMs += 500L
        assertEquals(185L, PerformanceMonitorManager.stopAppStartupTrace())
    }

    @Test
    fun `gemini stream trace captures TTFT, total duration, audio and text chunks, and rolling average`() {
        val session1 = PerformanceMonitorManager.startGeminiStreamTrace(
            modelName = "gemini-3.8-flash",
            requestAudio = true,
            hasAttachment = false
        )
        assertTrue(PerformanceMonitorManager.telemetry.value.isRequestInFlight)

        // First token arrives at +120ms (audio chunk)
        fakeClockMs += 120L
        session1.recordChunk(isAudio = true)

        // Second audio chunk + two text chunks
        fakeClockMs += 80L
        session1.recordChunk(isAudio = true)
        session1.recordChunk(isAudio = false)
        session1.recordChunk(isAudio = false)

        // Stream completes at +350ms total
        fakeClockMs += 150L
        session1.finish("success")

        val snap1 = PerformanceMonitorManager.telemetry.value
        assertFalse(snap1.isRequestInFlight)
        assertEquals(120L, snap1.lastTtftMs)
        assertEquals(120L, snap1.averageTtftMs)
        assertEquals(350L, snap1.lastTotalDurationMs)
        assertEquals(2, snap1.lastAudioChunkCount)
        assertEquals(2, snap1.lastTextChunkCount)
        assertEquals(1, snap1.totalTrackedRequests)
        assertEquals("success", snap1.lastRequestStatus)

        // Second stream with TTFT = 200ms -> rolling average should be (120 + 200) / 2 = 160ms
        val session2 = PerformanceMonitorManager.startGeminiStreamTrace(
            modelName = "gemini-3.8-flash",
            requestAudio = false,
            hasAttachment = true
        )
        fakeClockMs += 200L
        session2.recordChunk(isAudio = false)
        fakeClockMs += 100L
        session2.finish("fallback_text")

        val snap2 = PerformanceMonitorManager.telemetry.value
        assertEquals(200L, snap2.lastTtftMs)
        assertEquals(160L, snap2.averageTtftMs)
        assertEquals(300L, snap2.lastTotalDurationMs)
        assertEquals(0, snap2.lastAudioChunkCount)
        assertEquals(1, snap2.lastTextChunkCount)
        assertEquals(2, snap2.totalTrackedRequests)
        assertEquals("fallback_text", snap2.lastRequestStatus)
    }

    @Test
    fun `audio transcription trace and resetSessionTelemetry preserve startup timing`() = runTest {
        PerformanceMonitorManager.startAppStartupTrace("cold_start")
        fakeClockMs += 140L
        PerformanceMonitorManager.stopAppStartupTrace()

        val transcript = PerformanceMonitorManager.traceAudioTranscription(
            modelName = "gemini-3.8-flash",
            mimeType = "audio/pcm",
            audioBytesCount = 32000
        ) {
            fakeClockMs += 240L
            "Hey Gemini open research"
        }

        assertEquals("Hey Gemini open research", transcript)
        assertEquals(240L, PerformanceMonitorManager.telemetry.value.lastTranscribeDurationMs)

        // Reset session telemetry while preserving startup duration
        PerformanceMonitorManager.resetSessionTelemetry(preserveStartup = true)
        val afterReset = PerformanceMonitorManager.telemetry.value
        assertEquals(140L, afterReset.startupDurationMs)
        assertTrue(afterReset.isStartupTraceCompleted)
        assertNull(afterReset.lastTtftMs)
        assertNull(afterReset.lastTotalDurationMs)
        assertNull(afterReset.lastTranscribeDurationMs)
        assertEquals(0, afterReset.totalTrackedRequests)
    }
}
