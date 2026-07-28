package com.lianyu.ai.wechat.map

import com.lianyu.ai.common.text.MessageSegmenter
import com.lianyu.ai.domain.wechat.WeChatContentKind
import com.lianyu.ai.domain.wechat.WeChatOutboundRequest
import com.lianyu.ai.domain.wechat.WeChatOutboundSegment
import com.lianyu.ai.domain.wechat.WeChatDeliveryStatus
import java.util.UUID

/**
 * 出站分段契约（S0）。
 *
 * 直接委托 App 使用的 [MessageSegmenter] SIMPLE 模式，确保两个通道规则一致。
 */
object WeChatOutboundSegmenter {

    /**
     * 与 App 的 [MessageSegmenter.SplitMode.SIMPLE] 一致：
     * 空串 trim 后仍返回单元素 `listOf("")`。
     */
    fun splitTextSimple(text: String): List<String> =
        MessageSegmenter.split(text, MessageSegmenter.SplitMode.SIMPLE)

    /**
     * 将出站请求展开为 Outbox 分段。
     * @param wechatUserId 已解析的微信用户 id（IdentityMap 之后）
     */
    fun expand(
        request: WeChatOutboundRequest,
        wechatUserId: String,
        rootId: String = UUID.randomUUID().toString(),
    ): List<WeChatOutboundSegment> {
        require(wechatUserId.isNotBlank()) { "wechatUserId blank" }
        val media = request.media
        if (media != null && media.kind != WeChatContentKind.TEXT) {
            return listOf(
                WeChatOutboundSegment(
                    outboxId = "$rootId#0",
                    wechatUserId = wechatUserId,
                    kind = media.kind,
                    text = request.text,
                    media = media,
                    segmentIndex = 0,
                    segmentCount = 1,
                    contextToken = request.contextToken,
                    status = WeChatDeliveryStatus.PENDING,
                ),
            )
        }
        val raw = request.text.orEmpty()
        if (raw.trim().isEmpty()) return emptyList()
        val segments = splitTextSimple(raw)
        return segments.mapIndexed { index, part ->
            WeChatOutboundSegment(
                outboxId = "$rootId#$index",
                wechatUserId = wechatUserId,
                kind = WeChatContentKind.TEXT,
                text = part,
                media = null,
                segmentIndex = index,
                segmentCount = segments.size,
                contextToken = request.contextToken,
                status = WeChatDeliveryStatus.PENDING,
            )
        }
    }
}
