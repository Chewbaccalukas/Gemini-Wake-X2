package com.example.bridge

import android.util.Log
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "LocalSyncRelayServer"

data class RelayServerState(
    val isRunning: Boolean = false,
    val port: Int = 8765,
    val lastSyncTimestamp: Long = 0L,
    val connectedClientsCount: Int = 0,
    val statusText: String = "Stopped"
)

/**
 * Embedded local loopback HTTP relay server for automatic real-time sync between
 * Google Chrome and the Android application.
 */
class LocalSyncRelayServer(
    private val scope: CoroutineScope,
    private val onMessageReceivedFromChrome: suspend (ChatMessage) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    private val _serverState = MutableStateFlow(RelayServerState())
    val serverState: StateFlow<RelayServerState> = _serverState.asStateFlow()

    private var activeChatProvider: (() -> Pair<ChatSession?, List<ChatMessage>>)? = null

    fun setChatDataProvider(provider: () -> Pair<ChatSession?, List<ChatMessage>>) {
        this.activeChatProvider = provider
    }

    fun start(port: Int = 8765) {
        if (_serverState.value.isRunning) return

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                _serverState.value = RelayServerState(
                    isRunning = true,
                    port = port,
                    statusText = "Relay Active (Port $port)"
                )
                Log.d(TAG, "LocalSyncRelayServer started on port $port")

                while (isActive && serverSocket != null && !serverSocket!!.isClosed) {
                    try {
                        val client = serverSocket!!.accept()
                        handleClient(client)
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.w(TAG, "Error accepting client connection", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start LocalSyncRelayServer on port $port", e)
                _serverState.value = RelayServerState(
                    isRunning = false,
                    port = port,
                    statusText = "Server Error: ${e.localizedMessage}"
                )
            }
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
            serverSocket = null
            serverJob?.cancel()
            serverJob = null
            _serverState.value = _serverState.value.copy(
                isRunning = false,
                statusText = "Stopped"
            )
            Log.d(TAG, "LocalSyncRelayServer stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    private fun handleClient(socket: Socket) {
        scope.launch(Dispatchers.IO) {
            try {
                socket.use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    val line = reader.readLine() ?: return@use
                    val parts = line.split(" ")
                    val method = if (parts.isNotEmpty()) parts[0] else "GET"
                    val path = if (parts.size > 1) parts[1] else "/"

                    // Read HTTP headers
                    var contentLength = 0
                    var headerLine = reader.readLine()
                    while (!headerLine.isNullOrEmpty()) {
                        if (headerLine.startsWith("Content-Length:", ignoreCase = true)) {
                            contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                        }
                        headerLine = reader.readLine()
                    }

                    when {
                        method == "OPTIONS" -> {
                            sendResponse(client.getOutputStream(), 200, "OK", "application/json", "{}")
                        }
                        method == "GET" && path.startsWith("/sync") -> {
                            val data = activeChatProvider?.invoke()
                            val chat = data?.first
                            val messages = data?.second ?: emptyList()

                            val root = JSONObject().apply {
                                put("chatId", chat?.id ?: 0L)
                                put("chatTitle", chat?.title ?: "Gemini Chat")
                                put("wakeWord", chat?.wakeWord ?: "Hey Gemini")
                                put("timestamp", System.currentTimeMillis())

                                val msgArray = JSONArray()
                                messages.forEach { msg ->
                                    val obj = JSONObject().apply {
                                        put("id", msg.id)
                                        put("sender", msg.sender)
                                        put("content", msg.content)
                                        put("timestamp", msg.timestamp)
                                        put("triggeredByWakeWord", msg.triggeredByWakeWord)
                                    }
                                    msgArray.put(obj)
                                }
                                put("messages", msgArray)
                            }

                            _serverState.value = _serverState.value.copy(
                                lastSyncTimestamp = System.currentTimeMillis(),
                                statusText = "Synced with Chrome (${messages.size} msgs)"
                            )

                            sendResponse(client.getOutputStream(), 200, "OK", "application/json", root.toString())
                        }
                        method == "POST" && path.startsWith("/sync") -> {
                            // Read body
                            val body = CharArray(contentLength)
                            var read = 0
                            while (read < contentLength) {
                                val r = reader.read(body, read, contentLength - read)
                                if (r == -1) break
                                read += r
                            }
                            val bodyString = String(body)

                            try {
                                val json = JSONObject(bodyString)
                                val sender = json.optString("sender", "user")
                                val content = json.optString("content", "")
                                val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                                val chatId = json.optLong("chatId", 0L)

                                if (content.isNotBlank()) {
                                    val newMsg = ChatMessage(
                                        chatId = chatId,
                                        sender = sender,
                                        content = content,
                                        timestamp = timestamp,
                                        triggeredByWakeWord = false
                                    )
                                    onMessageReceivedFromChrome(newMsg)
                                    _serverState.value = _serverState.value.copy(
                                        lastSyncTimestamp = System.currentTimeMillis(),
                                        statusText = "Received message from Chrome"
                                    )
                                }
                                sendResponse(client.getOutputStream(), 200, "OK", "application/json", "{\"status\":\"ok\"}")
                            } catch (e: Exception) {
                                sendResponse(client.getOutputStream(), 400, "Bad Request", "application/json", "{\"error\":\"invalid_json\"}")
                            }
                        }
                        else -> {
                            sendResponse(client.getOutputStream(), 200, "OK", "text/plain", "Gemini Wake Sync Server Running")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error handling client request", e)
            }
        }
    }

    private fun sendResponse(
        out: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: String
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Connection: close\r\n\r\n"

        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
