package com.lianyu.ai.feature.chat.ui.viewmodel

import com.lianyu.ai.network.ChunkedResponseHandler

/**
 * State machine for streaming chat response handling.
 *
 * Extracts the 100-line when(result) control logic from ChatViewModel
 * into a testable, single-responsibility class.
 *
 * Usage:
 *   val handler = ChatStreamHandler(companionId, showReasoning)
 *   flow.collect { chunk ->
 *       for (action in handler.consume(chunk)) {
 *           viewModel.dispatch(action)
 *       }
 *   }
 *   for (action in handler.finish()) { viewModel.dispatch(action) }
 */
class ChatStreamHandler(
    companionId: Long,
    showReasoning: Boolean,
    val accumulatedText: AccumulatedText = AccumulatedText(),
    val reasoningText: AccumulatedText = AccumulatedText()
) {
    var hasReceivedContent = false
        private set

    private var hasCreatedMessage = false
    private var aiMessageId: Long = 0
    private var lastDbUpdateTime = System.currentTimeMillis()
    private val dbUpdateIntervalMs = 150L

    private val companionId = companionId
    private val showReasoning = showReasoning

    /** The user's original message content — set before streaming starts */
    var userContent: String = ""

    /** Accumulated text container (mutable, shareable with ViewModel) */
    class AccumulatedText(var value: String = "")

    /** Actions emitted by the state machine — ViewModel handles these */
    sealed class Action {
        data class AppendTypingText(val text: String) : Action()
        data class CreateMessage(val tempContent: String, val isFromUser: Boolean = false) : Action()
        data class UpdateMessage(val messageId: Long, val content: String) : Action()
        data class UpdateReasoning(val show: Boolean, val text: String) : Action()
        data class Toast(val message: String) : Action()
        data class FinalizeMessage(
            val messageId: Long,
            val text: String,
            val needsStickerProcessing: Boolean,
            val userContentForMemory: String?
        ) : Action()
        data object StreamCompleted : Action()
    }

    /**
     * Consume one chunk from the stream. Returns list of actions to dispatch.
     * Returns empty list to stop the stream.
     */
    fun consume(chunk: ChunkedResponseHandler.ChunkResult): List<Action> {
        return when (chunk) {
            is ChunkedResponseHandler.ChunkResult.Text -> handleText(chunk)
            is ChunkedResponseHandler.ChunkResult.Reasoning -> handleReasoning(chunk)
            is ChunkedResponseHandler.ChunkResult.Error -> handleError(chunk)
            is ChunkedResponseHandler.ChunkResult.Done -> handleDone()
        }
    }

    fun finish(): List<Action> = handleDone()

    fun getMessageId(): Long = aiMessageId

    fun setMessageId(id: Long) {
        aiMessageId = id
    }

    // ── State machine transitions ──

    private fun handleText(chunk: ChunkedResponseHandler.ChunkResult.Text): List<Action> {
        val actions = mutableListOf<Action>()
        accumulatedText.value += chunk.content
        hasReceivedContent = true
        actions += Action.AppendTypingText(chunk.content)

        if (!hasCreatedMessage) {
            actions += Action.CreateMessage(accumulatedText.value)
            hasCreatedMessage = true
        }

        val now = System.currentTimeMillis()
        if (now - lastDbUpdateTime >= dbUpdateIntervalMs && aiMessageId > 0) {
            actions += Action.UpdateMessage(aiMessageId, accumulatedText.value)
            lastDbUpdateTime = now
        }
        return actions
    }

    private fun handleReasoning(chunk: ChunkedResponseHandler.ChunkResult.Reasoning): List<Action> {
        if (showReasoning) {
            reasoningText.value += chunk.content
            return listOf(Action.UpdateReasoning(true, reasoningText.value))
        }
        return emptyList()
    }

    private fun handleError(chunk: ChunkedResponseHandler.ChunkResult.Error): List<Action> {
        val actions = mutableListOf<Action>()
        actions += Action.UpdateReasoning(false, "")

        val message = chunk.message
        if (message.startsWith("[TOAST]")) {
            actions += Action.Toast(message.removePrefix("[TOAST]"))
            if (hasCreatedMessage && aiMessageId > 0) {
                actions += Action.UpdateMessage(aiMessageId, accumulatedText.value.ifBlank { "..." })
            }
        } else {
            if (!hasCreatedMessage) {
                actions += Action.CreateMessage(message)
                hasCreatedMessage = true
            } else if (aiMessageId > 0) {
                val finalText = accumulatedText.value.ifNotEmpty() ?: message
                actions += Action.UpdateMessage(aiMessageId, finalText)
            }
        }
        return actions
    }

    private fun handleDone(): List<Action> {
        val actions = mutableListOf<Action>()
        actions += Action.UpdateReasoning(false, "")

        val finalText = accumulatedText.value.ifBlank { "API返回空内容，请检查模型名或API配置" }
        if (!hasCreatedMessage) {
            actions += Action.CreateMessage(finalText)
            hasCreatedMessage = true
        } else if (aiMessageId > 0) {
            actions += Action.UpdateMessage(aiMessageId, finalText)
        }

        if (aiMessageId > 0) {
            actions += Action.FinalizeMessage(
                messageId = aiMessageId,
                text = accumulatedText.value,
                needsStickerProcessing = accumulatedText.value.isNotBlank(),
                userContentForMemory = userContent.takeIf { it.isNotBlank() }
            )
        }

        actions += Action.StreamCompleted
        return actions
    }

    private fun String.ifNotEmpty(): String? = if (isNotEmpty()) this else null
}
