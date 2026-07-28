package com.lianyu.ai.domain.wechat

import kotlinx.coroutines.flow.Flow

/**
 * 微信通道只读状态（UI / 保活查询）。
 */
interface WeChatChannelGateway {
    suspend fun isLinked(): Boolean
    fun connectionState(): Flow<WeChatConnectionSnapshot>
}

/**
 * App → 微信出站端口。
 * feature/chat、notification 等只依赖此接口，不依赖 feature:wechat。
 */
interface WeChatOutboundPort {
    /**
     * @return outbox 根 id（同一请求多分段共享前缀）
     */
    suspend fun enqueue(request: WeChatOutboundRequest): String
}

/**
 * 微信入站 → AI 回复。
 * 由 app 绑定到现有 AI 能力（ServiceRegistry），禁止 feature:wechat 依赖 feature:chat。
 */
interface WeChatDialoguePort {
    suspend fun generateReply(request: WeChatDialogueRequest): WeChatDialogueResult
}

/**
 * 用户映射端口（设置页 / Inbox 共用）。
 */
interface WeChatIdentityMapPort {
    suspend fun resolveCompanionId(wechatUserId: String): Long?
    suspend fun getOrCreateMapping(wechatUserId: String): Long?
    suspend fun listMappings(): List<WeChatUserMapping>
    suspend fun bind(wechatUserId: String, companionId: Long)
    suspend fun unbind(wechatUserId: String)
}
