package com.example.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs

private const val TAG = "AudioRecorder"

/**
 * Lifecycle-safe audio recorder that captures 16 kHz 16-bit mono PCM audio from
 * MediaRecorder.AudioSource.MIC (with fallback to UNPROCESSED / VOICE_RECOGNITION)
 * into a local PCM test buffer and writes a standard RIFF/WAVE (.wav) or .m4a file
 * for wake word training and snippet playback.
 */
class AudioRecorder(private val context: Context) {

    sealed class State {
        object Idle : State()
        data class Recording(val outputFile: File, val startedAt: Long) : State()
        data class Error(val message: String, val cause: Throwable? = null) : State()
    }

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null

    private val pcmBufferLock = Any()
    private val pcmByteStream = ByteArrayOutputStream()

    @Volatile
    private var isCapturingPcm = false

    @Volatile
    private var lastMaxAmplitude = 0

    @Volatile
    var lastRecordedPcmBytes: ByteArray = ByteArray(0)
        private set

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val isRecording: Boolean
        get() = _state.value is State.Recording

    /**
     * Appends incoming 16-bit Little-Endian PCM bytes directly into the active snippet buffer.
     */
    fun appendPcmBytes(pcmBytes: ByteArray, bytesRead: Int = pcmBytes.size) {
        if (bytesRead <= 0 || pcmBytes.isEmpty()) return
        val limit = bytesRead.coerceAtMost(pcmBytes.size)
        synchronized(pcmBufferLock) {
            pcmByteStream.write(pcmBytes, 0, limit)
            lastRecordedPcmBytes = pcmByteStream.toByteArray()
        }
        var maxSample = 0
        var i = 0
        while (i + 1 < limit) {
            val low = pcmBytes[i].toInt() and 0xFF
            val high = pcmBytes[i + 1].toInt()
            val sample = abs(((high shl 8) or low).toShort().toInt())
            if (sample > maxSample) maxSample = sample
            i += 2
        }
        if (maxSample > lastMaxAmplitude) {
            lastMaxAmplitude = maxSample
        }
    }

    /**
     * Starts recording a voice snippet to the designated [outputFile] using 16 kHz 16-bit mono
     * PCM from MediaRecorder.AudioSource.MIC (or MediaRecorder fallback).
     */
    @Synchronized
    fun start(outputFile: File): Result<Unit> {
        return try {
            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                val err = SecurityException("RECORD_AUDIO permission not granted")
                _state.value = State.Error("Microphone permission required", err)
                return Result.failure(err)
            }

            if (isRecording) {
                cancel()
            }

            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) {
                outputFile.delete()
            }

            synchronized(pcmBufferLock) {
                pcmByteStream.reset()
            }
            lastMaxAmplitude = 0
            currentOutputFile = outputFile

            val minBuf = AudioRecord.getMinBufferSize(
                WakeWordManager.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = WakeWordManager.calculateBufferSize(minBuf)
            val initializedRecord = createMicAudioRecord(bufferSize)

            if (initializedRecord != null && initializedRecord.state == AudioRecord.STATE_INITIALIZED) {
                val startedSuccessfully = try {
                    if (initializedRecord.state == AudioRecord.STATE_INITIALIZED) {
                        initializedRecord.startRecording()
                    }
                    initializedRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "IllegalStateException starting AudioRecord in AudioRecorder", e)
                    false
                } catch (t: Throwable) {
                    Log.e(TAG, "Native failure starting AudioRecord in AudioRecorder", t)
                    false
                }

                if (startedSuccessfully) {
                    audioRecord = initializedRecord
                    WakeWordVoiceService.registerOwnAudioSessionId(initializedRecord.audioSessionId)
                    WakeWordVoiceService.isAppRecordingActive = true
                    isCapturingPcm = true
                    _state.value = State.Recording(outputFile, System.currentTimeMillis())

                    captureThread = thread(start = true, name = "Snippet-PCM-Recorder") {
                        val shortBuf = ShortArray(bufferSize / 2)
                        while (isCapturingPcm) {
                            if (!hasRecordAudioPermission()) {
                                Log.w(TAG, "RECORD_AUDIO permission revoked during snippet capture — stopping")
                                break
                            }
                            val rec = audioRecord ?: break
                            if (rec.state != AudioRecord.STATE_INITIALIZED ||
                                rec.recordingState != AudioRecord.RECORDSTATE_RECORDING
                            ) {
                                break
                            }
                            val readCount = try {
                                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                                    rec.read(shortBuf, 0, shortBuf.size)
                                } else {
                                    AudioRecord.ERROR_INVALID_OPERATION
                                }
                            } catch (e: IllegalStateException) {
                                Log.w(TAG, "IllegalStateException reading AudioRecord: ${e.message}")
                                break
                            } catch (_: Throwable) {
                                break
                            }
                            if (readCount < 0) {
                                // Abort on native read error (-20 / NO_INIT / ERROR_BAD_VALUE / ERROR_DEAD_OBJECT)
                                Log.w(TAG, "AudioRecorder read returned error code $readCount — aborting loop")
                                break
                            }
                            if (readCount > 0) {
                                val chunk = ByteArray(readCount * 2)
                                var peak = 0
                                for (i in 0 until readCount) {
                                    val s = shortBuf[i].toInt()
                                    val absVal = abs(s)
                                    if (absVal > peak) peak = absVal
                                    chunk[i * 2] = (s and 0xFF).toByte()
                                    chunk[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                                }
                                lastMaxAmplitude = peak
                                synchronized(pcmBufferLock) {
                                    pcmByteStream.write(chunk)
                                    lastRecordedPcmBytes = pcmByteStream.toByteArray()
                                }
                            }
                        }
                    }
                    Log.d(TAG, "AudioRecorder started 16kHz PCM capture (MIC): ${outputFile.name}")
                    return Result.success(Unit)
                } else {
                    try {
                        initializedRecord.release()
                    } catch (_: Throwable) {}
                }
            }

            // Fallback to MediaRecorder if AudioRecord was unavailable
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(16_000)
                setAudioChannels(1)
                setOutputFile(outputFile.absolutePath)
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder error: what=$what, extra=$extra")
                    _state.value = State.Error("MediaRecorder runtime error ($what, $extra)")
                }
                prepare()
                start()
            }

            this.mediaRecorder = recorder
            _state.value = State.Recording(outputFile, System.currentTimeMillis())
            Log.d(TAG, "AudioRecorder started MediaRecorder fallback: ${outputFile.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecorder", e)
            releaseRecorder()
            _state.value = State.Error("Failed to start recording: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun createMicAudioRecord(bufferSize: Int): AudioRecord? {
        if (!hasRecordAudioPermission() || bufferSize <= 0) return null

        try {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                WakeWordManager.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                return rec
            }
            try {
                rec.release()
            } catch (_: Throwable) {}
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "IllegalArgumentException creating AudioRecord: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "IllegalStateException creating AudioRecord: ${e.message}")
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "UnsupportedOperationException creating AudioRecord: ${e.message}")
        } catch (t: Throwable) {
            Log.w(TAG, "Native error creating AudioRecord: ${t.message}")
        }
        return null
    }

    /**
     * Stops the active recording session, writes captured PCM/WAV bytes to [currentOutputFile],
     * and returns the recorded [File].
     */
    @Synchronized
    fun stop(): Result<File> {
        val activeFile = currentOutputFile
        return try {
            isCapturingPcm = false
            captureThread?.let { t ->
                if (Thread.currentThread() != t) {
                    t.interrupt()
                    try {
                        t.join(150)
                    } catch (_: InterruptedException) {}
                }
            }
            captureThread = null

            val capturedPcm = synchronized(pcmBufferLock) {
                pcmByteStream.toByteArray()
            }
            if (capturedPcm.isNotEmpty()) {
                lastRecordedPcmBytes = capturedPcm
                if (activeFile != null) {
                    val bytesToWrite = if (activeFile.name.endsWith(".pcm", ignoreCase = true)) {
                        capturedPcm
                    } else {
                        WakeWordManager.buildWavFromPcmBytes(capturedPcm, WakeWordManager.SAMPLE_RATE_HZ)
                    }
                    activeFile.outputStream().use { it.write(bytesToWrite) }
                }
            }

            val recorder = mediaRecorder
            if (recorder != null) {
                try {
                    recorder.stop()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "MediaRecorder stop failed (possibly too short): ${e.message}")
                    if (capturedPcm.isEmpty()) {
                        activeFile?.delete()
                        releaseRecorder()
                        _state.value = State.Error("Recording was too short")
                        return Result.failure(e)
                    }
                }
            }
            releaseRecorder()
            _state.value = State.Idle

            if (activeFile != null && activeFile.exists() && activeFile.length() > 0) {
                Log.d(TAG, "AudioRecorder stopped successfully: ${activeFile.name} (${activeFile.length()} bytes)")
                Result.success(activeFile)
            } else {
                val err = IllegalStateException("Recorded audio snippet was empty or missing")
                _state.value = State.Error(err.message ?: "Empty recording")
                Result.failure(err)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecorder", e)
            releaseRecorder()
            _state.value = State.Error("Error stopping recording: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Cancels any active recording and deletes the partial audio file.
     */
    @Synchronized
    fun cancel() {
        isCapturingPcm = false
        captureThread?.interrupt()
        captureThread = null
        try {
            mediaRecorder?.let {
                try {
                    it.stop()
                } catch (_: Exception) {}
            }
        } finally {
            releaseRecorder()
            currentOutputFile?.delete()
            currentOutputFile = null
            _state.value = State.Idle
        }
    }

    /**
     * Returns the maximum absolute amplitude recorded since the last call, or 0.
     * Useful for animating amplitude rings and live recording VU meters.
     */
    fun getMaxAmplitude(): Int {
        return try {
            if (isRecording) {
                val pcmPeak = lastMaxAmplitude
                lastMaxAmplitude = 0
                if (pcmPeak > 0) pcmPeak else (mediaRecorder?.maxAmplitude ?: 0)
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Synchronously stops and releases any active AudioRecord or MediaRecorder instance
     * so SpeechRecognizer has uncontested access to the hardware microphone track.
     */
    @Synchronized
    fun stopAndReleaseHardware() {
        isCapturingPcm = false
        val threadToJoin = captureThread
        captureThread = null
        if (threadToJoin != null && Thread.currentThread() != threadToJoin) {
            threadToJoin.interrupt()
            try {
                threadToJoin.join(150)
            } catch (_: InterruptedException) {}
        }
        releaseRecorder()
        if (_state.value is State.Recording) {
            _state.value = State.Idle
        }
    }

    /**
     * Releases hardware recorder resources.
     */
    @Synchronized
    fun release() {
        cancel()
    }

    private fun releaseRecorder() {
        isCapturingPcm = false
        val rec = audioRecord
        audioRecord = null
        WakeWordVoiceService.isAppRecordingActive = false
        if (rec != null) {
            try {
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    rec.stop()
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "IllegalStateException stopping AudioRecord in AudioRecorder: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioRecord in AudioRecorder: ${e.message}")
            }
            try {
                rec.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioRecord in AudioRecorder", e)
            }
        }
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaRecorder", e)
        } finally {
            mediaRecorder = null
            currentOutputFile = null
        }
    }
}
