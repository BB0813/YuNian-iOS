package com.lianyu.ai.wechat.wire

/**
 * 与 feature/wechat 旧 M0–M7 / ilink item 字段对齐的中性线模型。
 * 不依赖 SDK 与 feature 包，便于 S0 单测与后续 Transport 替换。
 */
data class WireCdnMedia(
    val encryptQueryParam: String? = null,
    val aesKey: String? = null,
)

data class WireTextItem(val text: String? = null)

data class WireImageItem(val cdnImg: WireCdnMedia? = null)

data class WireVoiceItem(val cdnVoice: WireCdnMedia? = null)

data class WireFileItem(
    val cdnFile: WireCdnMedia? = null,
    val fileName: String? = null,
)

data class WireVideoItem(
    val cdnVideo: WireCdnMedia? = null,
    val cdnThumb: WireCdnMedia? = null,
)

data class WireMessageItem(
    val type: Int,
    val textItem: WireTextItem? = null,
    val imageItem: WireImageItem? = null,
    val voiceItem: WireVoiceItem? = null,
    val fileItem: WireFileItem? = null,
    val videoItem: WireVideoItem? = null,
)

data class WireWeChatMessage(
    val seq: Long? = null,
    val messageId: Long? = null,
    val fromUserId: String? = null,
    val toUserId: String? = null,
    val createTimeMs: Long? = null,
    val sessionId: String? = null,
    val messageType: Int? = null,
    val messageState: Int? = null,
    val itemList: List<WireMessageItem>? = null,
    val contextToken: String? = null,
)
