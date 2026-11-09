package com.example.cache

import android.content.Context
import android.util.Log
import com.example.data.dao.ChatDao
import com.example.data.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "ChatMemoryBufferManager"

data class BufferMetrics(
    val hotMemoryCount: Int = 0,
    val totalDiskCount: Int = 0,
    val diskCacheSizeBytes: Long = 0L,
    val incomingDiskCount: Int = 0,
    val outgoingDiskCount: Int = 0,
    val incomingDiskSizeBytes: Long = 0L,
    val outgoingDiskSizeBytes: Long = 0L,
    val hotBufferSizeLimit: Int = 30,
    val isPreloaded: Boolean = false,
    val preloadStatus: String = "Ready"
)

/**
 * High-performance, anti-freeze two-tier caching engine:
 * 1. Hot In-Memory Buffer: Keeps a configurable window of recent messages in RAM for smooth 60fps rendering.
 * 2. Disk-Side Cache: SQLite + persistent disk cache files that page older messages smoothly on demand
 *    without freezing the main Compose thread.
 */
class ChatMemoryBufferManager(
    private val context: Context,
    private val chatDao: ChatDao
) {
    private val cacheDir = File(context.cacheDir, "chat_disk_buffers").apply {
        if (!exists()) mkdirs()
    }

    private val _bufferMetrics = MutableStateFlow(BufferMetrics())
    val bufferMetrics: StateFlow<BufferMetrics> = _bufferMetrics.asStateFlow()

    private val _hotMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val hotMessages: StateFlow<List<ChatMessage>> = _hotMessages.asStateFlow()

    private val _isLoadingOlder = MutableStateFlow(false)
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder.asStateFlow()

    private val _hasMoreOlderMessages = MutableStateFlow(false)
    val hasMoreOlderMessages: StateFlow<Boolean> = _hasMoreOlderMessages.asStateFlow()

    private var currentChatId: Long? = null
    private var hotBufferSizeLimit = 30
    private var allCachedMessagesForCurrentChat: MutableList<ChatMessage> = mutableListOf()

    fun setBufferSizeLimit(limit: Int) {
        hotBufferSizeLimit = limit.coerceIn(10, 100)
        refreshBuffer()
    }

    /**
     * Preloads and warms the chat thread buffer on background thread.
     * Loads the saved message history for [chatId] from Room in chronological order (`timestamp ASC, id ASC`),
     * falling back to the local disk cache file if needed.
     */
    suspend fun preloadAndAttachChat(chatId: Long) = withContext(Dispatchers.IO) {
        currentChatId = chatId
        _bufferMetrics.value = _bufferMetrics.value.copy(
            preloadStatus = "Warming disk cache...",
            isPreloaded = false
        )

        try {
            val dbMessages = try {
                chatDao.getMessagesForChatSync(chatId)
            } catch (_: Throwable) {
                emptyList()
            }
            val snapshot = if (dbMessages.isNotEmpty()) {
                dbMessages
            } else {
                readDiskCacheFile(chatId) ?: emptyList()
            }.filter { it.chatId == chatId && !it.isDeleted }
                .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })

            allCachedMessagesForCurrentChat.clear()
            allCachedMessagesForCurrentChat.addAll(snapshot)
            if (snapshot.isNotEmpty()) {
                writeDiskCacheFileAsync(chatId, snapshot)
            }

            refreshBuffer()
            _bufferMetrics.value = _bufferMetrics.value.copy(
                isPreloaded = true,
                preloadStatus = "Preloaded & Warm"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Preload failed for chat $chatId", e)
            _bufferMetrics.value = _bufferMetrics.value.copy(
                preloadStatus = "Ready (Fallback mode)"
            )
        }
    }

    /**
     * Detaches the active chat session from RAM, immediately purging in-memory message
     * buffers and resetting pagination metrics to prevent cross-session memory bleeding.
     */
    fun detachChat() {
        currentChatId = null
        allCachedMessagesForCurrentChat.clear()
        _hotMessages.value = emptyList()
        _hasMoreOlderMessages.value = false
        _isLoadingOlder.value = false
        _bufferMetrics.value = BufferMetrics(
            hotMemoryCount = 0,
            totalDiskCount = 0,
            diskCacheSizeBytes = 0L,
            incomingDiskCount = 0,
            outgoingDiskCount = 0,
            incomingDiskSizeBytes = 0L,
            outgoingDiskSizeBytes = 0L,
            hotBufferSizeLimit = hotBufferSizeLimit,
            isPreloaded = false,
            preloadStatus = "Detached"
        )
    }

    /**
     * Updates the full cache and slides the active hot buffer window, strictly
     * isolating updates to the currently attached chatId and ordering messages chronologically.
     */
    fun updateFullMessageList(chatId: Long, messages: List<ChatMessage>) {
        if (currentChatId != null && currentChatId != chatId) {
            // Stale repository emission for a previous chat; drop to prevent bleed
            return
        }
        currentChatId = chatId
        val chronological = messages
            .filter { it.chatId == chatId && !it.isDeleted }
            .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
        allCachedMessagesForCurrentChat = chronological.toMutableList()

        // Asynchronously persist disk backup for instantaneous next-session preload
        writeDiskCacheFileAsync(chatId, chronological)

        refreshBuffer()
    }

    /**
     * Appends a newly sent message to hot memory and disk buffer if belonging to current chat,
     * preserving strict chronological order and avoiding duplicate message IDs.
     */
    fun appendMessage(message: ChatMessage) {
        if (currentChatId != null && currentChatId != message.chatId) {
            return
        }
        if (message.id > 0L) {
            allCachedMessagesForCurrentChat.removeAll { it.id == message.id }
        }
        allCachedMessagesForCurrentChat.add(message)
        allCachedMessagesForCurrentChat.sortWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
        refreshBuffer()
        currentChatId?.let { writeDiskCacheFileAsync(it, allCachedMessagesForCurrentChat) }
    }

    /**
     * Paged backward retrieval: loads previous historical chunk into the buffer.
     */
    suspend fun loadOlderMessagesChunk() = withContext(Dispatchers.IO) {
        if (_isLoadingOlder.value || !_hasMoreOlderMessages.value) return@withContext

        _isLoadingOlder.value = true
        try {
            val currentlyLoaded = _hotMessages.value.size
            val targetCount = (currentlyLoaded + hotBufferSizeLimit).coerceAtMost(allCachedMessagesForCurrentChat.size)

            val expandedList = allCachedMessagesForCurrentChat.takeLast(targetCount)

            _hotMessages.value = expandedList
            _hasMoreOlderMessages.value = targetCount < allCachedMessagesForCurrentChat.size
            _bufferMetrics.value = _bufferMetrics.value.copy(
                hotMemoryCount = expandedList.size,
                totalDiskCount = allCachedMessagesForCurrentChat.size,
                preloadStatus = "Paged (${expandedList.size}/${allCachedMessagesForCurrentChat.size} in RAM)"
            )
        } finally {
            _isLoadingOlder.value = false
        }
    }

    private fun refreshBuffer() {
        val total = allCachedMessagesForCurrentChat.size
        val hotList = if (total <= hotBufferSizeLimit) {
            allCachedMessagesForCurrentChat.toList()
        } else {
            allCachedMessagesForCurrentChat.takeLast(hotBufferSizeLimit)
        }

        _hotMessages.value = hotList
        _hasMoreOlderMessages.value = total > hotList.size

        val incomingFile = currentChatId?.let { getIncomingCacheFile(it) }
        val outgoingFile = currentChatId?.let { getOutgoingCacheFile(it) }
        val incomingSize = if (incomingFile != null && incomingFile.exists()) incomingFile.length() else 0L
        val outgoingSize = if (outgoingFile != null && outgoingFile.exists()) outgoingFile.length() else 0L
        val totalFileSize = incomingSize + outgoingSize

        val incomingCount = allCachedMessagesForCurrentChat.count { it.sender != "user" }
        val outgoingCount = allCachedMessagesForCurrentChat.count { it.sender == "user" }

        _bufferMetrics.value = _bufferMetrics.value.copy(
            hotMemoryCount = hotList.size,
            totalDiskCount = total,
            diskCacheSizeBytes = totalFileSize,
            incomingDiskCount = incomingCount,
            outgoingDiskCount = outgoingCount,
            incomingDiskSizeBytes = incomingSize,
            outgoingDiskSizeBytes = outgoingSize,
            hotBufferSizeLimit = hotBufferSizeLimit,
            isPreloaded = true,
            preloadStatus = if (total > hotList.size) "Buffered ($hotBufferSizeLimit in RAM, ${total - hotList.size} on Disk)" else "Optimal (All in RAM)"
        )
    }

    private fun getIncomingCacheFile(chatId: Long): File {
        return File(cacheDir, "chat_cache_incoming_$chatId.json")
    }

    private fun getOutgoingCacheFile(chatId: Long): File {
        return File(cacheDir, "chat_cache_outgoing_$chatId.json")
    }

    private fun writeDiskCacheFileAsync(chatId: Long, messages: List<ChatMessage>) {
        kotlinx.coroutines.GlobalScope.let {
            try {
                val incomingMessages = messages.filter { it.sender != "user" }
                val outgoingMessages = messages.filter { it.sender == "user" }

                writeMessagesToFile(getIncomingCacheFile(chatId), incomingMessages)
                writeMessagesToFile(getOutgoingCacheFile(chatId), outgoingMessages)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write disk cache files", e)
            }
        }
    }

    private fun writeMessagesToFile(file: File, messages: List<ChatMessage>) {
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val jsonArray = JSONArray()
            val fallbackNow = System.currentTimeMillis().coerceAtLeast(1L)
            messages.forEachIndexed { index, msg ->
                try {
                    val validTimestamp = if (msg.timestamp > 0L) msg.timestamp else fallbackNow + index
                    val obj = JSONObject().apply {
                        put("id", if (msg.id > 0L) msg.id else (fallbackNow + index))
                        put("chatId", msg.chatId)
                        put("sender", msg.sender.ifBlank { "gemini" })
                        put("content", msg.content)
                        put("timestamp", validTimestamp)
                        put("triggeredByWakeWord", msg.triggeredByWakeWord)
                    }
                    jsonArray.put(obj)
                } catch (_: Throwable) {}
            }
            file.writeText(jsonArray.toString())
        } catch (e: Throwable) {
            Log.w(TAG, "Failed writing cache file ${file.name}", e)
        }
    }

    private fun readDiskCacheFile(chatId: Long): List<ChatMessage>? {
        return try {
            val incomingFile = getIncomingCacheFile(chatId)
            val outgoingFile = getOutgoingCacheFile(chatId)
            val legacyFile = File(cacheDir, "chat_cache_$chatId.json")

            val list = mutableListOf<ChatMessage>()
            if (incomingFile.exists()) {
                readMessagesFromFile(incomingFile)?.let { list.addAll(it) }
            }
            if (outgoingFile.exists()) {
                readMessagesFromFile(outgoingFile)?.let { list.addAll(it) }
            }

            // Backward compatibility with legacy combined cache file
            if (list.isEmpty() && legacyFile.exists()) {
                readMessagesFromFile(legacyFile)?.let { list.addAll(it) }
            }

            if (list.isNotEmpty()) list.sortedBy { it.timestamp } else null
        } catch (e: Throwable) {
            Log.w(TAG, "Failed reading disk cache for chat $chatId", e)
            null
        }
    }

    private fun readMessagesFromFile(file: File): List<ChatMessage>? {
        if (!file.exists()) return null
        return try {
            val jsonString = file.readText()
            if (jsonString.isBlank()) return emptyList()
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<ChatMessage>()
            val fallbackNow = System.currentTimeMillis().coerceAtLeast(1L)
            for (i in 0 until jsonArray.length()) {
                try {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val rawTimestamp = obj.optLong("timestamp", 0L)
                    val resolvedTimestamp = if (rawTimestamp > 0L) rawTimestamp else (fallbackNow + i)
                    val rawId = obj.optLong("id", 0L)
                    val resolvedId = if (rawId > 0L) rawId else (resolvedTimestamp + i)
                    val sender = obj.optString("sender", "gemini").ifBlank { "gemini" }
                    val content = obj.optString("content", "")
                    list.add(
                        ChatMessage(
                            id = resolvedId,
                            chatId = obj.optLong("chatId", 0L),
                            sender = sender,
                            content = content,
                            timestamp = resolvedTimestamp,
                            triggeredByWakeWord = obj.optBoolean("triggeredByWakeWord", false)
                        )
                    )
                } catch (_: Throwable) {
                    // Skip corrupted individual message object
                }
            }
            list
        } catch (e: Throwable) {
            Log.w(TAG, "Corrupted cache file ${file.name}, deleting to recover", e)
            try {
                file.delete()
            } catch (_: Throwable) {}
            null
        }
    }

    /**
     * Scans and removes any corrupted disk cache JSON files across all chats.
     */
    fun repairOrClearCorruptedDiskCaches(): Int {
        var removedCount = 0
        try {
            val files = cacheDir.listFiles() ?: return 0
            for (f in files) {
                if (!f.isFile || !f.name.endsWith(".json")) continue
                try {
                    val text = f.readText()
                    if (text.isNotBlank()) {
                        JSONArray(text)
                    }
                } catch (_: Throwable) {
                    try {
                        if (f.delete()) removedCount++
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        return removedCount
    }

    /**
     * Clears incoming (AI / Gemini) messages disk cache buffer.
     */
    fun clearIncomingDiskCache(chatId: Long) {
        try {
            val file = getIncomingCacheFile(chatId)
            if (file.exists()) file.delete()
            allCachedMessagesForCurrentChat.removeAll { it.sender != "user" }
            refreshBuffer()
        } catch (_: Throwable) {}
    }

    /**
     * Clears outgoing (User queries) messages disk cache buffer.
     */
    fun clearOutgoingDiskCache(chatId: Long) {
        try {
            val file = getOutgoingCacheFile(chatId)
            if (file.exists()) file.delete()
            allCachedMessagesForCurrentChat.removeAll { it.sender == "user" }
            refreshBuffer()
        } catch (_: Throwable) {}
    }

    /**
     * Clears all disk buffers (both incoming and outgoing).
     */
    fun clearDiskCache(chatId: Long) {
        try {
            val inc = getIncomingCacheFile(chatId)
            if (inc.exists()) inc.delete()
            val out = getOutgoingCacheFile(chatId)
            if (out.exists()) out.delete()
            val legacy = File(cacheDir, "chat_cache_$chatId.json")
            if (legacy.exists()) legacy.delete()
        } catch (_: Throwable) {}

        allCachedMessagesForCurrentChat.clear()
        _hotMessages.value = emptyList()
        _bufferMetrics.value = BufferMetrics(
            hotMemoryCount = 0,
            totalDiskCount = 0,
            diskCacheSizeBytes = 0L,
            incomingDiskCount = 0,
            outgoingDiskCount = 0,
            incomingDiskSizeBytes = 0L,
            outgoingDiskSizeBytes = 0L,
            hotBufferSizeLimit = hotBufferSizeLimit,
            preloadStatus = "All Disk Caches Purged"
        )
    }
}
