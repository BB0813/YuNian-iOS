package com.lianyu.ai.network.transformers

import com.lianyu.ai.domain.TriggeredEntry
import com.lianyu.ai.network.Message
import com.lianyu.ai.network.transformers.TransformerContext

/**
 * Prompt injection transformer for worldbook/lorebook.
 */
class PromptInjectionTransformer : MessageTransformer {
    override val id = "prompt_injection"
    override val isInput = true
    override val priority = 100

    companion object {
        const val MAX_INJECTION_CHARS = 8000
        const val MAX_TOTAL_INJECTION_CHARS = 20000
    }

    override suspend fun transform(
        context: TransformerContext,
        messages: List<Message>
    ): List<Message> {
        val lorebookProvider = context.lorebookProvider ?: return messages
        if (context.sessionId <= 0) return messages

        val recentMessages = messages
            .takeLast(50)
            .map { uiMsg ->
                com.lianyu.ai.domain.ContextMessage(
                    role = when (uiMsg.role) {
                        "user" -> "user"
                        "assistant" -> "assistant"
                        "system" -> "system"
                        "tool" -> "assistant"
                        else -> "user"
                    },
                    content = uiMsg.content ?: "",
                    timestamp = uiMsg.timestamp ?: System.currentTimeMillis()
                )
            }
            .reversed()

        val triggeredEntries = lorebookProvider.getTriggeredEntries(context.sessionId, recentMessages)
        if (triggeredEntries.isEmpty()) return messages

        val byPosition = triggeredEntries.groupBy { it.entry.injectionPosition }

        var result = messages.toMutableList()
        var totalInjectedChars = 0

        // BEFORE_SYSTEM_PROMPT
        val beforeSystem = byPosition[com.lianyu.ai.domain.InjectionPosition.BEFORE_SYSTEM_PROMPT] ?: emptyList()
        if (beforeSystem.isNotEmpty()) {
            val injectionContent = buildInjectionContent(beforeSystem, context)
            if (injectionContent.isNotBlank() && totalInjectedChars + injectionContent.length <= MAX_TOTAL_INJECTION_CHARS) {
                val insertIndex = findFirstSystemIndex(result)
                result.add(insertIndex, Message("system", injectionContent))
                totalInjectedChars += injectionContent.length
            }
        }

        // AFTER_SYSTEM_PROMPT
        val afterSystem = byPosition[com.lianyu.ai.domain.InjectionPosition.AFTER_SYSTEM_PROMPT] ?: emptyList()
        if (afterSystem.isNotEmpty()) {
            val injectionContent = buildInjectionContent(afterSystem, context)
            if (injectionContent.isNotBlank() && totalInjectedChars + injectionContent.length <= MAX_TOTAL_INJECTION_CHARS) {
                val insertIndex = findLastSystemIndex(result) + 1
                result.add(insertIndex, Message("system", injectionContent))
                totalInjectedChars += injectionContent.length
            }
        }

        // TOP_OF_CHAT
        val topOfChat = byPosition[com.lianyu.ai.domain.InjectionPosition.TOP_OF_CHAT] ?: emptyList()
        if (topOfChat.isNotEmpty()) {
            val injectionContent = buildInjectionContent(topOfChat, context)
            if (injectionContent.isNotBlank() && totalInjectedChars + injectionContent.length <= MAX_TOTAL_INJECTION_CHARS) {
                val insertIndex = findFirstNonSystemIndex(result)
                result.add(insertIndex, Message("system", injectionContent))
                totalInjectedChars += injectionContent.length
            }
        }

        // BOTTOM_OF_CHAT
        val bottomOfChat = byPosition[com.lianyu.ai.domain.InjectionPosition.BOTTOM_OF_CHAT] ?: emptyList()
        if (bottomOfChat.isNotEmpty()) {
            val injectionContent = buildInjectionContent(bottomOfChat, context)
            if (injectionContent.isNotBlank() && totalInjectedChars + injectionContent.length <= MAX_TOTAL_INJECTION_CHARS) {
                val insertIndex = findSafeInsertIndex(result, result.size - 1)
                result.add(insertIndex, Message("system", injectionContent))
                totalInjectedChars += injectionContent.length
            }
        }

        // AT_DEPTH
        val atDepth = byPosition[com.lianyu.ai.domain.InjectionPosition.AT_DEPTH] ?: emptyList()
        if (atDepth.isNotEmpty()) {
            val injectionContent = buildInjectionContent(atDepth, context)
            if (injectionContent.isNotBlank() && totalInjectedChars + injectionContent.length <= MAX_TOTAL_INJECTION_CHARS) {
                val targetDepth = atDepth.map { it.entry.injectDepth ?: 3 }.minOrNull() ?: 3
                val insertIndex = findSafeInsertIndex(result, result.size - targetDepth)
                result.add(insertIndex, Message("system", injectionContent))
                totalInjectedChars += injectionContent.length
            }
        }

        return result
    }

    private fun buildInjectionContent(
        entries: List<TriggeredEntry>,
        context: TransformerContext
    ): String {
        val builder = StringBuilder()
        var totalChars = 0

        for (triggered in entries) {
            val entry = triggered.entry
            val header = when {
                triggered.matchedKeyword == "[CONSTANT]" -> "[Lorebook: ${entry.lorebookId} - Constant Active]"
                else -> "[Lorebook: ${entry.lorebookId} - Triggered by: \"${triggered.matchedKeyword}\"]"
            }
            val content = entry.content.trim()
            val block = "$header\n$content\n---"

            if (totalChars + block.length > MAX_INJECTION_CHARS) break
            builder.append(block).append("\n")
            totalChars += block.length
        }

        if (builder.isEmpty()) return ""
        return "[Worldbook Injections]\n${builder.toString()}\n[End Worldbook Injections]"
    }

    private fun findFirstSystemIndex(messages: List<Message>): Int {
        return messages.indexOfFirst { msg: Message -> msg.role == "system" }.let { if (it >= 0) it else 0 }
    }

    private fun findLastSystemIndex(messages: List<Message>): Int {
        return messages.indexOfLast { msg: Message -> msg.role == "system" }.let { if (it >= 0) it else 0 }
    }

    private fun findFirstNonSystemIndex(messages: List<Message>): Int {
        return messages.indexOfFirst { msg: Message -> msg.role != "system" }.let { if (it >= 0) it else messages.size }
    }

    private fun findSafeInsertIndex(messages: List<Message>, targetIndex: Int): Int {
        var idx = targetIndex.coerceIn(0, messages.size - 1)

        while (idx > 0) {
            val prev = messages[idx - 1]
            val curr = messages[idx]

            // Case 1: prev is user, curr is assistant with tool_calls -> don't insert in middle
            if (prev.role == "user" && curr.role == "assistant" && curr.tool_calls?.isNotEmpty() == true) {
                idx--
                continue
            }

            // Case 2: prev is assistant(tool_calls), curr is tool -> don't insert in middle
            if (prev.role == "assistant" && prev.tool_calls?.isNotEmpty() == true && curr.role == "tool") {
                idx--
                continue
            }

            // Case 3: prev is tool, curr is assistant -> don't insert in middle
            if (prev.role == "tool" && curr.role == "assistant") {
                idx--
                continue
            }

            // Case 4: two consecutive assistants (streaming segments) -> don't insert in middle
            if (prev.role == "assistant" && curr.role == "assistant") {
                idx--
                continue
            }

            break
        }

        return idx
    }
}