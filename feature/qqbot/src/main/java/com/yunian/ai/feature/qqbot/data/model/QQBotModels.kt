package com.yunian.ai.feature.qqbot.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class QQBotAccount(
    val appId: String,
    val clientSecret: String,
    val customName: String? = null
)

@Serializable
data class AccessTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long
)

@Serializable
data class GatewayResponse(
    val url: String
)

@Serializable
data class QQGatewayPayload(
    val op: Int,
    val d: kotlinx.serialization.json.JsonElement? = null,
    val s: Int? = null,
    val t: String? = null
)

@Serializable
data class QQHelloData(
    @SerialName("heartbeat_interval") val heartbeatInterval: Long
)

@Serializable
data class QQReadyData(
    val version: Int? = null,
    @SerialName("session_id") val sessionId: String,
    val user: QQUser? = null,
    val shard: List<Int>? = null
)

@Serializable
data class QQUser(
    val id: String? = null,
    val username: String? = null,
    val avatar: String? = null,
    @SerialName("bot_status") val botStatus: Int? = null
)

@Serializable
data class QQMessageAuthor(
    @SerialName("user_openid") val userOpenid: String? = null,
    @SerialName("member_openid") val memberOpenid: String? = null,
    val id: String? = null,
    val username: String? = null
)

@Serializable
data class QQMessageAttachment(
    @SerialName("content_type") val contentType: String? = null,
    val filename: String? = null,
    val height: Int? = null,
    val width: Int? = null,
    val url: String? = null,
    @SerialName("size") val sizeBytes: Long? = null
)

@Serializable
data class QQMessageEvent(
    val id: String,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("guild_id") val guildId: String? = null,
    @SerialName("group_openid") val groupOpenid: String? = null,
    val author: QQMessageAuthor? = null,
    val content: String? = null,
    val timestamp: String? = null,
    @SerialName("message_type") val messageType: Int? = null,
    val attachments: List<QQMessageAttachment>? = null,
    /** 消息中 @ 的用户列表。全量群消息模式下用它判断是否 @ 了机器人本身。 */
    val mentions: List<QQUser>? = null,
    val member: kotlinx.serialization.json.JsonElement? = null
)

@Serializable
data class SendTextRequest(
    val content: String? = null,
    val markdown: QQMarkdown? = null,
    @SerialName("msg_type") val msgType: Int = 0,
    @SerialName("msg_id") val msgId: String? = null,
    /** 仅被动回复使用；主动消息必须缺席，不能发送 0 或任意全局序号。 */
    @SerialName("msg_seq") val msgSeq: Int? = null,
    @SerialName("message_reference") val messageReference: QQMessageReference? = null
)

@Serializable
data class QQMarkdown(
    val content: String
)

@Serializable
data class QQMessageReference(
    @SerialName("message_id") val messageId: String
)

/**
 * 主动发送的目标类别（**本模块私有的协议细节**）。
 *
 * 刻意不复用任何 domain 类型：domain 契约（[com.yunian.ai.domain.channel.ChannelOutboundRequest]）
 * 的 `target` 是一个**不透明字符串**，由本模块按自己的协议解释成 USER / GROUP，
 * 契约层不解释它的内容。
 */
enum class QQProactiveTargetKind {
    /** 单个用户：目标标识是 `user_openid`，走 `POST /v2/users/{openid}/messages`。 */
    USER,
    /** 群聊：目标标识是 `group_openid`，走 `POST /v2/groups/{group_openid}/messages`。 */
    GROUP,
}

/**
 * 主动发送目标（**省略 `msg_id`** 的那条通路）。
 *
 * [id] 非空是硬要求：主动发送没有被动锚点可兜底，空目标必然发错人或直接 404，
 * 因此调用方必须先确认目标存在，再由仓库层做二次校验。
 */
data class QQProactiveTarget(
    val kind: QQProactiveTargetKind,
    val id: String,
)


/**
 * 主动发送结果。
 *
 * [deliveryReceipt] 恒为 `false`——QQ 出站只校验 HTTP 2xx，**没有任何 ack**：
 * 它让上层能区分「已交给通道发出」与「对方已收到」，禁止把 [messageRef] 当成送达凭证。
 */
data class QQProactiveSendResult(
    val messageRef: String? = null,
    val deliveryReceipt: Boolean = false,
)

@Serializable
data class SendMessageResponse(
    val id: String? = null,
    @SerialName("channel_id") val channelId: String? = null,
    @SerialName("guild_id") val guildId: String? = null,
    @SerialName("group_openid") val groupOpenid: String? = null,
    val content: String? = null,
    val timestamp: String? = null
)

@Serializable
data class SendMediaRequest(
    @SerialName("msg_type") val msgType: Int = 7,
    @SerialName("msg_id") val msgId: String? = null,
    @SerialName("msg_seq") val msgSeq: Int = 0,
    val content: String? = null,
    val media: QQMediaInfo? = null
)

@Serializable
data class QQMediaInfo(
    @SerialName("file_info") val fileInfo: String
)

@Serializable
data class UploadFileRequest(
    @SerialName("file_type") val fileType: Int,
    val url: String? = null,
    @SerialName("file_data") val fileData: String? = null,
    @SerialName("srv_send_msg") val srvSendMsg: Boolean = false,
    @SerialName("file_name") val fileName: String? = null
)

@Serializable
data class UploadFileResponse(
    @SerialName("file_info") val fileInfo: String? = null
)

enum class QQMessageType(val value: Int) {
    TEXT(0),
    MARKDOWN(2),
    INPUT_NOTIFY(6),
    MEDIA(7)
}

enum class QQMediaFileType(val value: Int) {
    IMAGE(1),
    VIDEO(2),
    VOICE(3),
    FILE(4)
}

sealed class QQInboundEvent {
    abstract val raw: QQMessageEvent

    data class C2CMessage(
        val userOpenid: String,
        override val raw: QQMessageEvent
    ) : QQInboundEvent()

    data class GroupAtMessage(
        val groupOpenid: String,
        val memberOpenid: String,
        override val raw: QQMessageEvent
    ) : QQInboundEvent()

    data class GuildMessage(
        val channelId: String,
        val guildId: String?,
        val authorId: String,
        override val raw: QQMessageEvent
    ) : QQInboundEvent()

    data class DirectMessage(
        val guildId: String,
        val authorId: String,
        override val raw: QQMessageEvent
    ) : QQInboundEvent()
}
