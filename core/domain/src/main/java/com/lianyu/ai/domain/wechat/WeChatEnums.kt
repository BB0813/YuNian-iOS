package com.lianyu.ai.domain.wechat

/**
 * 微信通道内容类型（协议侧语义）。
 *
 * 与 App 存储 [AppContentType] 的对齐见 [WeChatAppTypeAlignment]。
 * ilink item type 数值：TEXT=1 IMAGE=2 VOICE=3 FILE=4 VIDEO=5。
 */
enum class WeChatContentKind(val wireType: Int) {
    TEXT(1),
    IMAGE(2),
    VOICE(3),
    FILE(4),
    VIDEO(5),
    UNKNOWN(-1);

    companion object {
        fun fromWireType(type: Int?): WeChatContentKind =
            entries.firstOrNull { it.wireType == type } ?: UNKNOWN
    }
}

/**
 * App 侧消息存储类型名（与 database MessageType 的 serialName 对齐，domain 不依赖 database）。
 */
enum class AppContentType(val wireName: String) {
    TEXT("text"),
    IMAGE("image"),
    AUDIO("audio"),
    VIDEO("video"),
    VOICE("voice"),
    FILE("file"),
    /** 思考过程：永不进入微信通道 */
    REASONING("reasoning");

    companion object {
        fun fromWireName(name: String?): AppContentType? =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }
    }
}

/** 消息方向（相对恋语 bot） */
enum class WeChatMessageDirection {
    /** 微信用户 → bot */
    INBOUND,
    /** bot → 微信用户 */
    OUTBOUND,
}

/** 出站投递状态（Outbox） */
enum class WeChatDeliveryStatus {
    PENDING,
    SENDING,
    SENT,
    FAILED,
    CANCELLED,
}

/** 通道连接状态 */
enum class WeChatConnectionState {
    DISCONNECTED,
    LOGGING_IN,
    CONNECTED,
    DEGRADED,
    ERROR,
}

/** 入站处理决策（策略层输出，非 UI） */
enum class WeChatInboundAction {
    IGNORE,
    NOTIFY_ONLY,
    ENQUEUE_DIALOGUE,
}

/**
 * S5：通道失败原因码（日志 / 可观测 UI 共用，避免散落字符串）。
 *
 * wireName 用于 SecureLog 与 Outbox lastError 前缀。
 */
enum class WeChatFailureReason(val wireName: String) {
    POLL_TIMEOUT("poll_timeout"),
    POLL_CONNECTION("poll_connection"),
    POLL_AUTH("poll_auth"),
    POLL_UNKNOWN("poll_unknown"),
    OUTBOX_SEND("outbox_send"),
    OUTBOX_DEAD("outbox_dead"),
    MAPPING_MISSING("mapping_missing"),
    MAPPING_INVALID("mapping_invalid"),
    DIALOGUE_FAILED("dialogue_failed"),
    TRANSPORT_REBUILD("transport_rebuild");

    companion object {
        fun fromPollMessage(message: String?): WeChatFailureReason {
            val msg = message.orEmpty()
            return when {
                msg.contains("timeout", ignoreCase = true) -> POLL_TIMEOUT
                msg.contains("connection", ignoreCase = true) ||
                    msg.contains("unable to resolve", ignoreCase = true) ||
                    msg.contains("failed to connect", ignoreCase = true) -> POLL_CONNECTION
                msg.contains("401") ||
                    msg.contains("403") ||
                    msg.contains("unauthorized", ignoreCase = true) ||
                    msg.contains("token", ignoreCase = true) && msg.contains("invalid", ignoreCase = true) -> POLL_AUTH
                else -> POLL_UNKNOWN
            }
        }
    }
}
