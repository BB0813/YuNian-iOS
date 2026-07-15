package com.lianyu.ai.database.repository

import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.GroupMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MessageWriteCoordinator(
    private val chatRepository: ChatRepository,
    private val groupMessageRepository: GroupMessageRepository,
    scope: CoroutineScope,
    private val batchWindowMs: Long = DEFAULT_BATCH_WINDOW_MS
) {
    private val chatQueue = Channel<PendingChatWrite>(QUEUE_CAPACITY)
    private val groupQueue = Channel<PendingGroupWrite>(QUEUE_CAPACITY)

    init {
        scope.launch { consumeChatWrites() }
        scope.launch { consumeGroupWrites() }
    }

    suspend fun enqueueChat(message: ChatMessage): Long {
        val result = CompletableDeferred<Long>()
        chatQueue.send(PendingChatWrite(message, result, flushImmediately = true))
        return result.await()
    }

    suspend fun submitChat(message: ChatMessage): Deferred<Long> {
        val result = CompletableDeferred<Long>()
        chatQueue.send(PendingChatWrite(message, result, flushImmediately = false))
        return result
    }

    suspend fun enqueueChats(messages: List<ChatMessage>): List<Long> =
        messages.map { submitChat(it) }.awaitAll()

    suspend fun enqueueGroup(message: GroupMessage): Long {
        val result = CompletableDeferred<Long>()
        groupQueue.send(PendingGroupWrite(message, result, flushImmediately = true))
        return result.await()
    }

    suspend fun submitGroup(message: GroupMessage): Deferred<Long> {
        val result = CompletableDeferred<Long>()
        groupQueue.send(PendingGroupWrite(message, result, flushImmediately = false))
        return result
    }

    suspend fun enqueueGroups(messages: List<GroupMessage>): List<Long> =
        messages.map { submitGroup(it) }.awaitAll()

    fun close() {
        chatQueue.close()
        groupQueue.close()
    }

    private suspend fun consumeChatWrites() {
        consumeBatches(chatQueue) { batch ->
            chatRepository.batchInsertMessages(batch.map { it.message })
        }
    }

    private suspend fun consumeGroupWrites() {
        consumeBatches(groupQueue) { batch ->
            groupMessageRepository.batchInsertMessages(batch.map { it.message })
        }
    }

    private suspend fun <T : PendingWrite> consumeBatches(
        queue: Channel<T>,
        persist: suspend (List<T>) -> List<Long>
    ) {
        for (first in queue) {
            val batch = mutableListOf(first)
            if (first.flushImmediately) {
                persistBatch(batch, persist)
                continue
            }
            val deadline = System.currentTimeMillis() + batchWindowMs
            while (batch.size < BATCH_SIZE) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                val next = withTimeoutOrNull(remaining) { queue.receiveCatching().getOrNull() } ?: break
                batch += next
                if (next.flushImmediately) break
            }
            persistBatch(batch, persist)
        }
    }

    private suspend fun <T : PendingWrite> persistBatch(
        batch: List<T>,
        persist: suspend (List<T>) -> List<Long>
    ) {
        runCatching { persist(batch) }
            .onSuccess { ids ->
                check(ids.size == batch.size) { "Persisted ID count does not match message count" }
                batch.zip(ids).forEach { (pending, id) -> pending.result.complete(id) }
            }
            .onFailure { error -> batch.forEach { it.result.completeExceptionally(error) } }
    }

    private interface PendingWrite {
        val result: CompletableDeferred<Long>
        val flushImmediately: Boolean
    }

    private data class PendingChatWrite(
        val message: ChatMessage,
        override val result: CompletableDeferred<Long>,
        override val flushImmediately: Boolean
    ) : PendingWrite

    private data class PendingGroupWrite(
        val message: GroupMessage,
        override val result: CompletableDeferred<Long>,
        override val flushImmediately: Boolean
    ) : PendingWrite

    private companion object {
        const val BATCH_SIZE = 5
        const val DEFAULT_BATCH_WINDOW_MS = 200L
        const val QUEUE_CAPACITY = 100
    }
}
