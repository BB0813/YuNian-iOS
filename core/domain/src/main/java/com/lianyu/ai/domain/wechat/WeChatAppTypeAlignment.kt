package com.lianyu.ai.domain.wechat

/**
 * App 存储类型 ↔ 微信通道类型 双向对齐。
 *
 * 规则：
 * - REASONING 永不映射为可同步微信类型
 * - App AUDIO 与微信 VOICE 互通（语音）
 * - AI 对话当前仅消费 TEXT / IMAGE（与 [com.lianyu.ai.domain.AiMessageType] 一致）
 */
object WeChatAppTypeAlignment {

    fun toAppContentType(kind: WeChatContentKind): AppContentType? = when (kind) {
        WeChatContentKind.TEXT -> AppContentType.TEXT
        WeChatContentKind.IMAGE -> AppContentType.IMAGE
        WeChatContentKind.VOICE -> AppContentType.VOICE
        WeChatContentKind.FILE -> AppContentType.FILE
        WeChatContentKind.VIDEO -> AppContentType.VIDEO
        WeChatContentKind.UNKNOWN -> null
    }

    fun toWeChatContentKind(app: AppContentType): WeChatContentKind? = when (app) {
        AppContentType.TEXT -> WeChatContentKind.TEXT
        AppContentType.IMAGE -> WeChatContentKind.IMAGE
        AppContentType.VOICE, AppContentType.AUDIO -> WeChatContentKind.VOICE
        AppContentType.FILE -> WeChatContentKind.FILE
        AppContentType.VIDEO -> WeChatContentKind.VIDEO
        AppContentType.REASONING -> null
    }

    fun toWeChatContentKindFromAppName(appTypeName: String?): WeChatContentKind? {
        val app = AppContentType.fromWireName(appTypeName) ?: return null
        return toWeChatContentKind(app)
    }

    /** 是否允许同步到微信（出站） */
    fun isSyncableToWeChat(app: AppContentType): Boolean =
        toWeChatContentKind(app) != null

    fun isSyncableToWeChat(appTypeName: String?): Boolean {
        val app = AppContentType.fromWireName(appTypeName) ?: return false
        return isSyncableToWeChat(app)
    }

    /** 是否可作为 AI 对话输入（与 AiMessageType 对齐） */
    fun isSupportedByAiDialogue(kind: WeChatContentKind): Boolean = when (kind) {
        WeChatContentKind.TEXT, WeChatContentKind.IMAGE -> true
        else -> false
    }

    /**
     * 映射到 AI 层类型名：仅 text / image；其余返回 null。
     */
    fun toAiMessageTypeName(kind: WeChatContentKind): String? = when (kind) {
        WeChatContentKind.TEXT -> "text"
        WeChatContentKind.IMAGE -> "image"
        else -> null
    }

    fun toAiMessageTypeName(app: AppContentType): String? {
        val kind = toWeChatContentKind(app) ?: return null
        return toAiMessageTypeName(kind)
    }
}
