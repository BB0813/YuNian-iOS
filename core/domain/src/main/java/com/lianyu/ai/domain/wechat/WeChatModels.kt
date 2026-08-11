package com.lianyu.ai.domain.wechat

/**
 * 微信通道领域模型（零 Android / SDK 依赖）。
 *
 * 与 feature 旧 M0–M7、ilink SDK 的字段对齐在 :core:wechat 映射层完成。
 */

/** CDN 加密媒体引用（下载/上传前） */
data class WeChatCdnMediaRef(
    val encryptQueryParam: String? = null,
    val aesKey: String? = null,
)

/** 本地或远程媒体句柄（下载完成后用 path） */
data class WeChatMediaRef(
    val kind: WeChatContentKind,
    val localPath: String? = null,
    val fileName: String? = null,
    val description: String? = null,
    val cdn: WeChatCdnMediaRef? = null,
    val thumbCdn: WeChatCdnMediaRef? = null,
    val byteSize: Long? = null,
)

/** 单条内容部件（一条微信消息可含多 item） */
data class WeChatContentPart(
    val kind: WeChatContentKind,
    val text: String? = null,
    val media: WeChatMediaRef? = null,
)

/**
 * 规范化入站消息（Inbox 真相源形态）。
 *
 * [dedupeKey] 用于去重；优先 messageId，否则合成键。
 */
data class WeChatInboundMessage(
    val dedupeKey: String,
    val messageId: Long? = null,
    val seq: Long? = null,
    val fromUserId: String,
    val toUserId: String? = null,
    val createTimeMs: Long? = null,
    val sessionId: String? = null,
    val direction: WeChatMessageDirection = WeChatMessageDirection.INBOUND,
    /** 协议层 message_type：用户/bot 等，可空 */
    val protocolMessageType: Int? = null,
    val contextToken: String? = null,
    val parts: List<WeChatContentPart> = emptyList(),
) {
    val primaryKind: WeChatContentKind
        get() = parts.firstOrNull()?.kind ?: WeChatContentKind.UNKNOWN

    val primaryText: String?
        get() = parts.firstNotNullOfOrNull { it.text?.takeIf { t -> t.isNotBlank() } }

    val hasImage: Boolean
        get() = parts.any { it.kind == WeChatContentKind.IMAGE }

    val isAiDialogueCandidate: Boolean
        get() = WeChatAppTypeAlignment.isSupportedByAiDialogue(primaryKind) ||
            (primaryText != null) ||
            hasImage
}

/**
 * 出站请求（App → 微信），进入 Outbox 前。
 *
 * [text] 为完整正文；气泡架构下整条 = 一条出站（不再客户端分句）。
 */
data class WeChatOutboundRequest(
    val companionId: Long,
    val wechatUserId: String? = null,
    val sourceMessageId: Long? = null,
    val text: String? = null,
    val media: WeChatMediaRef? = null,
    val contextToken: String? = null,
    val priority: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis(),
)

/** 出站分段（气泡架构：整条一条；微信侧逐段 send） */
data class WeChatOutboundSegment(
    val outboxId: String,
    val wechatUserId: String,
    val kind: WeChatContentKind,
    val text: String? = null,
    val media: WeChatMediaRef? = null,
    val segmentIndex: Int,
    val segmentCount: Int,
    val contextToken: String? = null,
    val status: WeChatDeliveryStatus = WeChatDeliveryStatus.PENDING,
    val retryCount: Int = 0,
    val nextAttemptAtMs: Long = 0L,
    val lastError: String? = null,
)

/** 登录账号摘要（不含明文落日志） */
data class WeChatAccount(
    val accountId: String,
    val ilinkBotId: String,
    val ilinkUserId: String,
    val baseUrl: String,
    /** 是否已持有 botToken；token 本体不进 domain 日志 */
    val hasBotToken: Boolean,
)

/** 微信用户 ↔ 伴侣映射 */
data class WeChatUserMapping(
    val wechatUserId: String,
    val companionId: Long,
    val accountId: String? = null,
    val updatedAtMs: Long = 0L,
)

/** 对话端口入参 */
data class WeChatDialogueRequest(
    val companionId: Long,
    val wechatUserId: String,
    val inbound: WeChatInboundMessage,
)

/**
 * 对话端口出参。
 *
 * [replyText] 为完整回复；调用方用 SIMPLE 分段后再入 Outbox。
 * [stickerLabels] 为表情描述标签（非文件路径），由 ContentPipeline 解析发送。
 * [assistantMessageId] 为最后一个落库的助手消息 id（>0 时可用于 Outbox sourceMessageId）。
 * [assistantMessageIds] 为按分段顺序落库的全部助手消息 id，供内容回写使用。
 */
data class WeChatDialogueResult(
    val replyText: String,
    val stickerLabels: List<String> = emptyList(),
    val blocked: Boolean = false,
    val assistantMessageId: Long? = null,
    val assistantMessageIds: List<Long> = emptyList(),
)

/** 连接状态快照 */
data class WeChatConnectionSnapshot(
    val state: WeChatConnectionState,
    val accountId: String? = null,
    val lastError: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis(),
)

/**
 * S5：通道可观测性快照（进程内 Runtime + Outbox 聚合）。
 *
 * 不含 token / 明文消息内容；[lastError] 仅短错误摘要。
 */
data class WeChatChannelHealthSnapshot(
    val primaryPollerActive: Boolean = false,
    val consecutiveFailures: Int = 0,
    val lastPollAtMs: Long = 0L,
    val lastErrorAtMs: Long = 0L,
    val lastError: String? = null,
    val openOutboxCount: Int = 0,
    val pendingOutboxCount: Int = 0,
    val failedOutboxCount: Int = 0,
    val sendingOutboxCount: Int = 0,
    val recentFailures: List<WeChatOutboxFailure> = emptyList(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    /** 看门狗停摆次数：进程被 ROM 冻结 / FGS 被杀后长时间无巡检则 +1（熄屏掉线取证） */
    val watchdogStallCount: Int = 0,
    /** 最近一次看门狗停摆时长（毫秒） */
    val lastWatchdogStallMs: Long = 0L,
)

/** Outbox 近期失败摘要（设置页可观测） */
data class WeChatOutboxFailure(
    val id: String,
    val wechatUserId: String,
    val kind: String,
    val retryCount: Int,
    val lastError: String?,
    val updatedAtMs: Long,
)
