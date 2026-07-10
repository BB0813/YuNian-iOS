package com.lianyu.ai.feature.chat.ui.viewmodel

import com.lianyu.ai.database.model.ChatMessage

data class QuoteReply(
    val messageId: Long,
    val authorName: String,
    val content: String
) {
    val previewText: String = content.replace(Regex("\\s+"), " ").trim().take(MAX_QUOTE_PREVIEW_LENGTH)
}

data class QuotedTextContent(
    val quote: QuoteReply?,
    val body: String
)

private const val QUOTE_PREFIX = "[引用回复]"
private const val QUOTE_SEPARATOR = "\n---\n"
private const val MAX_QUOTE_PREVIEW_LENGTH = 80

fun ChatMessage.toQuoteReply(companionName: String?, userName: String): QuoteReply {
    val authorName = if (isFromUser) userName else companionName.orEmpty().ifBlank { "对方" }
    return QuoteReply(
        messageId = id,
        authorName = authorName,
        content = quoteDisplayContent()
    )
}

fun encodeQuotedMessage(quote: QuoteReply, body: String): String {
    return buildString {
        append(QUOTE_PREFIX)
        append(quote.messageId)
        append("|")
        append(quote.authorName.sanitizeQuoteLine())
        append(": ")
        append(quote.previewText.sanitizeQuoteLine())
        append(QUOTE_SEPARATOR)
        append(body.trim())
    }
}

fun parseQuotedTextContent(content: String): QuotedTextContent {
    if (!content.startsWith(QUOTE_PREFIX)) return QuotedTextContent(quote = null, body = content)
    val separatorIndex = content.indexOf(QUOTE_SEPARATOR)
    if (separatorIndex <= QUOTE_PREFIX.length) return QuotedTextContent(quote = null, body = content)

    val quoteLine = content.substring(QUOTE_PREFIX.length, separatorIndex).trim()
    val body = content.substring(separatorIndex + QUOTE_SEPARATOR.length).trim()
    val idParts = quoteLine.split("|", limit = 2)
    val messageId = if (idParts.size == 2) idParts[0].toLongOrNull() ?: 0 else 0
    val quoteText = if (idParts.size == 2) idParts[1] else quoteLine
    val parts = quoteText.split(": ", limit = 2)
    val authorName = parts.getOrNull(0).orEmpty().ifBlank { "引用" }
    val quoteContent = parts.getOrNull(1).orEmpty()

    return QuotedTextContent(
        quote = QuoteReply(
            messageId = messageId,
            authorName = authorName,
            content = quoteContent
        ),
        body = body
    )
}

private fun ChatMessage.quoteDisplayContent(): String {
    return when {
        content.startsWith("[语音]") -> "[语音]"
        content.startsWith("[视频]") -> "[视频]"
        content.startsWith("[文件]") -> "[文件]"
        content.startsWith("[") && content.endsWith("]") -> "[表情] ${content.removeSurrounding("[", "]")}"
        else -> parseQuotedTextContent(content).body.ifBlank { content }
    }
}

private fun String.sanitizeQuoteLine(): String = replace("\n", " ").replace("\r", " ").trim()