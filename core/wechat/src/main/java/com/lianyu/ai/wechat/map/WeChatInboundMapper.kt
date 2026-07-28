package com.lianyu.ai.wechat.map

import com.lianyu.ai.domain.wechat.WeChatCdnMediaRef
import com.lianyu.ai.domain.wechat.WeChatContentKind
import com.lianyu.ai.domain.wechat.WeChatContentPart
import com.lianyu.ai.domain.wechat.WeChatInboundMessage
import com.lianyu.ai.domain.wechat.WeChatMediaRef
import com.lianyu.ai.domain.wechat.WeChatMessageDirection
import com.lianyu.ai.wechat.wire.WireCdnMedia
import com.lianyu.ai.wechat.wire.WireMessageItem
import com.lianyu.ai.wechat.wire.WireWeChatMessage
import java.security.MessageDigest

/**
 * 线模型 → domain [WeChatInboundMessage]。
 * 无 SDK 依赖；feature 旧 M0 可先转 Wire 再映射。
 */
object WeChatInboundMapper {

    fun toDomain(wire: WireWeChatMessage): WeChatInboundMessage? {
        val fromUserId = wire.fromUserId?.takeIf { it.isNotBlank() } ?: return null
        val parts = wire.itemList.orEmpty().mapNotNull { it.toPart() }
        val messageId = wire.messageId ?: wire.seq
        return WeChatInboundMessage(
            dedupeKey = buildDedupeKey(
                messageId = messageId,
                fromUserId = fromUserId,
                createTimeMs = wire.createTimeMs,
                parts = parts,
            ),
            messageId = messageId,
            seq = wire.seq,
            fromUserId = fromUserId,
            toUserId = wire.toUserId,
            createTimeMs = wire.createTimeMs,
            sessionId = wire.sessionId,
            direction = WeChatMessageDirection.INBOUND,
            protocolMessageType = wire.messageType,
            contextToken = wire.contextToken,
            parts = parts,
        )
    }

    fun buildDedupeKey(
        messageId: Long?,
        fromUserId: String,
        createTimeMs: Long?,
        parts: List<WeChatContentPart>,
    ): String {
        if (messageId != null && messageId != 0L) {
            return "mid:$messageId"
        }
        val payload = buildString {
            append(fromUserId)
            append('|')
            append(createTimeMs ?: 0L)
            append('|')
            parts.forEach { part ->
                append(part.kind.wireType)
                append(':')
                append(part.text.orEmpty())
                append(':')
                append(part.media?.cdn?.encryptQueryParam.orEmpty())
                append(';')
            }
        }
        return "h:${sha256Hex(payload).take(32)}"
    }

    private fun WireMessageItem.toPart(): WeChatContentPart? {
        val kind = WeChatContentKind.fromWireType(type)
        return when (kind) {
            WeChatContentKind.TEXT -> {
                val text = textItem?.text.orEmpty()
                if (text.isBlank()) null
                else WeChatContentPart(kind = kind, text = text)
            }
            WeChatContentKind.IMAGE -> WeChatContentPart(
                kind = kind,
                media = WeChatMediaRef(
                    kind = kind,
                    cdn = imageItem?.cdnImg?.toRef(),
                ),
            )
            WeChatContentKind.VOICE -> WeChatContentPart(
                kind = kind,
                media = WeChatMediaRef(
                    kind = kind,
                    cdn = voiceItem?.cdnVoice?.toRef(),
                ),
            )
            WeChatContentKind.FILE -> WeChatContentPart(
                kind = kind,
                media = WeChatMediaRef(
                    kind = kind,
                    fileName = fileItem?.fileName,
                    cdn = fileItem?.cdnFile?.toRef(),
                ),
            )
            WeChatContentKind.VIDEO -> WeChatContentPart(
                kind = kind,
                media = WeChatMediaRef(
                    kind = kind,
                    cdn = videoItem?.cdnVideo?.toRef(),
                    thumbCdn = videoItem?.cdnThumb?.toRef(),
                ),
            )
            WeChatContentKind.UNKNOWN -> null
        }
    }

    private fun WireCdnMedia.toRef(): WeChatCdnMediaRef =
        WeChatCdnMediaRef(
            encryptQueryParam = encryptQueryParam,
            aesKey = aesKey,
        )

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}
