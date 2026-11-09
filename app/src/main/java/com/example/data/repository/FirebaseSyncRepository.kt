package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.firebase.FirestoreChatDoc
import com.example.data.firebase.FirestoreMessageDoc
import com.example.data.firebase.FirestoreWakeWordDoc
import com.example.data.firebase.OperationType
import com.example.data.firebase.handleFirestoreError
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.WakeWord
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import com.google.firebase.firestore.toObject
import com.google.firebase.firestore.toObjects
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Cloud Firestore repository syncing user-owned chats, messages, and wake words
 * under `/users/{userId}/chats`, `/users/{userId}/messages`, and `/users/{userId}/wake_words`.
 *
 * Always resolves the named Firestore database ID from `R.string.firestore_database_id` when
 * constructed from an Android [Context], and accepts an injected [FirebaseFirestore] instance
 * for unit and emulator rule tests.
 */
class FirebaseSyncRepository(
    private val firestore: FirebaseFirestore
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun chatsPath(userId: String) = "users/$userId/chats"
    private fun messagesPath(userId: String) = "users/$userId/messages"
    private fun wakeWordsPath(userId: String) = "users/$userId/wake_words"

    // =========================================================================
    // Chats (/users/{userId}/chats/{chatId})
    // =========================================================================

    fun observeChats(userId: String): Flow<List<FirestoreChatDoc>> {
        val path = chatsPath(userId)
        return firestore.collection(path)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot -> snapshot.toObjects<FirestoreChatDoc>() }
            .catch { e ->
                if (e is Exception && e !is CancellationException) {
                    throw handleFirestoreError(e, OperationType.LIST, path)
                } else {
                    throw e
                }
            }
    }

    suspend fun getChat(userId: String, docId: String): FirestoreChatDoc? {
        val path = "${chatsPath(userId)}/$docId"
        return try {
            val snap = firestore.document(path).get().await()
            if (snap.exists()) snap.toObject<FirestoreChatDoc>() else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.GET, path)
        }
    }

    suspend fun upsertChat(userId: String, chat: ChatSession): String {
        val doc = FirestoreChatDoc.fromChatSession(userId, chat)
        return saveChatDoc(userId, doc)
    }

    suspend fun saveChatDoc(userId: String, doc: FirestoreChatDoc): String {
        val docId = doc.id.ifBlank { FirestoreChatDoc.docIdForChat(doc.localChatId) }
        val path = "${chatsPath(userId)}/$docId"
        return try {
            val docRef = firestore.document(path)
            val existing = docRef.get().await()
            if (existing.exists()) {
                val updates = mapOf(
                    "title" to doc.title,
                    "wakeWord" to doc.wakeWord,
                    "systemInstruction" to doc.systemInstruction,
                    "themeColorHex" to doc.themeColorHex,
                    "iconName" to doc.iconName,
                    "isDefaultWakeChat" to doc.isDefaultWakeChat,
                    "isPersistent" to doc.isPersistent,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            } else {
                docRef.set(doc.copy(id = docId, userId = userId)).await()
            }
            docId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.WRITE, path)
        }
    }

    suspend fun createChatDoc(userId: String, doc: FirestoreChatDoc): String {
        val docId = doc.id.ifBlank { FirestoreChatDoc.docIdForChat(doc.localChatId) }
        val path = "${chatsPath(userId)}/$docId"
        return try {
            firestore.document(path).set(doc.copy(id = docId, userId = userId)).await()
            docId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.CREATE, path)
        }
    }

    suspend fun updateChatTitleAndWakeWord(
        userId: String,
        docId: String,
        title: String,
        wakeWord: String
    ) {
        val path = "${chatsPath(userId)}/$docId"
        try {
            firestore.document(path).update(
                mapOf(
                    "title" to title.take(199),
                    "wakeWord" to wakeWord.take(119),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.UPDATE, path)
        }
    }

    suspend fun deleteChat(userId: String, localChatId: Long) {
        deleteChatByDocId(userId, FirestoreChatDoc.docIdForChat(localChatId))
    }

    suspend fun deleteChatByDocId(userId: String, docId: String) {
        val path = "${chatsPath(userId)}/$docId"
        try {
            firestore.document(path).delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.DELETE, path)
        }
    }

    // =========================================================================
    // Messages (/users/{userId}/messages/{messageId})
    // =========================================================================

    fun observeMessages(userId: String, limit: Long = 100L): Flow<List<FirestoreMessageDoc>> {
        val path = messagesPath(userId)
        return firestore.collection(path)
            .orderBy("messageTimestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .snapshots()
            .map { snapshot -> snapshot.toObjects<FirestoreMessageDoc>() }
            .catch { e ->
                if (e is Exception && e !is CancellationException) {
                    throw handleFirestoreError(e, OperationType.LIST, path)
                } else {
                    throw e
                }
            }
    }

    suspend fun getMessage(userId: String, docId: String): FirestoreMessageDoc? {
        val path = "${messagesPath(userId)}/$docId"
        return try {
            val snap = firestore.document(path).get().await()
            if (snap.exists()) snap.toObject<FirestoreMessageDoc>() else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.GET, path)
        }
    }

    suspend fun upsertMessage(userId: String, message: ChatMessage): String {
        val doc = FirestoreMessageDoc.fromChatMessage(userId, message)
        return createMessageDoc(userId, doc)
    }

    suspend fun createMessageDoc(userId: String, doc: FirestoreMessageDoc): String {
        val docId = doc.id.ifBlank { FirestoreMessageDoc.docIdForMessage(doc.localMessageId) }
        val path = "${messagesPath(userId)}/$docId"
        return try {
            firestore.document(path).set(doc.copy(id = docId, userId = userId)).await()
            docId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.CREATE, path)
        }
    }

    suspend fun updateMessageContent(userId: String, docId: String, content: String) {
        val path = "${messagesPath(userId)}/$docId"
        try {
            firestore.document(path).update(
                mapOf(
                    "content" to content.take(19999),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.UPDATE, path)
        }
    }

    suspend fun deleteMessage(userId: String, localMessageId: Long) {
        deleteMessageByDocId(userId, FirestoreMessageDoc.docIdForMessage(localMessageId))
    }

    suspend fun deleteMessageByDocId(userId: String, docId: String) {
        val path = "${messagesPath(userId)}/$docId"
        try {
            firestore.document(path).delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.DELETE, path)
        }
    }

    // =========================================================================
    // Wake Words (/users/{userId}/wake_words/{wakeWordId})
    // =========================================================================

    fun observeWakeWords(userId: String): Flow<List<FirestoreWakeWordDoc>> {
        val path = wakeWordsPath(userId)
        return firestore.collection(path)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .snapshots()
            .map { snapshot -> snapshot.toObjects<FirestoreWakeWordDoc>() }
            .catch { e ->
                if (e is Exception && e !is CancellationException) {
                    throw handleFirestoreError(e, OperationType.LIST, path)
                } else {
                    throw e
                }
            }
    }

    suspend fun getWakeWord(userId: String, docId: String): FirestoreWakeWordDoc? {
        val path = "${wakeWordsPath(userId)}/$docId"
        return try {
            val snap = firestore.document(path).get().await()
            if (snap.exists()) snap.toObject<FirestoreWakeWordDoc>() else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.GET, path)
        }
    }

    suspend fun upsertWakeWord(userId: String, wakeWord: WakeWord): String {
        val doc = FirestoreWakeWordDoc.fromWakeWord(userId, wakeWord)
        return saveWakeWordDoc(userId, doc)
    }

    suspend fun saveWakeWordDoc(userId: String, doc: FirestoreWakeWordDoc): String {
        val docId = doc.id.ifBlank { FirestoreWakeWordDoc.docIdForWakeWord(doc.localWakeWordId) }
        val path = "${wakeWordsPath(userId)}/$docId"
        return try {
            val docRef = firestore.document(path)
            val existing = docRef.get().await()
            if (existing.exists()) {
                val updates = mapOf(
                    "phrase" to doc.phrase,
                    "triggerType" to doc.triggerType,
                    "chatId" to doc.chatId,
                    "chatTitle" to doc.chatTitle,
                    "isEnabled" to doc.isEnabled,
                    "sensitivity" to doc.sensitivity,
                    "metadata" to doc.metadata,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            } else {
                docRef.set(doc.copy(id = docId, userId = userId)).await()
            }
            docId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.WRITE, path)
        }
    }

    suspend fun createWakeWordDoc(userId: String, doc: FirestoreWakeWordDoc): String {
        val docId = doc.id.ifBlank { FirestoreWakeWordDoc.docIdForWakeWord(doc.localWakeWordId) }
        val path = "${wakeWordsPath(userId)}/$docId"
        return try {
            firestore.document(path).set(doc.copy(id = docId, userId = userId)).await()
            docId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.CREATE, path)
        }
    }

    suspend fun updateWakeWordSensitivityAndState(
        userId: String,
        docId: String,
        phrase: String,
        isEnabled: Boolean,
        sensitivity: Double
    ) {
        val path = "${wakeWordsPath(userId)}/$docId"
        try {
            firestore.document(path).update(
                mapOf(
                    "phrase" to phrase.take(119),
                    "isEnabled" to isEnabled,
                    "sensitivity" to sensitivity.coerceIn(0.50, 0.98),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.UPDATE, path)
        }
    }

    suspend fun deleteWakeWord(userId: String, localWakeWordId: Long) {
        deleteWakeWordByDocId(userId, FirestoreWakeWordDoc.docIdForWakeWord(localWakeWordId))
    }

    suspend fun deleteWakeWordByDocId(userId: String, docId: String) {
        val path = "${wakeWordsPath(userId)}/$docId"
        try {
            firestore.document(path).delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.DELETE, path)
        }
    }
}
