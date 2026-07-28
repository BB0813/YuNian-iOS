package com.lianyu.ai.wechat

import android.content.Context
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.domain.wechat.WeChatFailureReason
import com.lianyu.ai.domain.wechat.WeChatOutboundPort
import com.lianyu.ai.domain.wechat.WeChatOutboundRequest
import com.lianyu.ai.feature.wechat.data.WeChatStickerMaterializer
import com.lianyu.ai.feature.wechat.service.WeChatServiceLocator
import com.lianyu.ai.wechat.map.WeChatContentCleaner

/**
 * S6/S7：App → 微信出站端口（替代 Broadcast + goAsync Receiver）。
 *
 * - 解析 companion 映射的微信用户
 * - 文本清洗后入 Outbox（SIMPLE 分段）
 * - 表情物化本地路径后入 Outbox（可重试）
 * - 入队后尝试 drain，剩余由 FGS 继续
 */
class WeChatOutboundPortImpl(
    private val appContext: Context,
) : WeChatOutboundPort {

    override suspend fun enqueue(request: WeChatOutboundRequest): String {
        val companionId = request.companionId
        if (companionId <= 0L) return SKIPPED

        val tokenStore = WeChatServiceLocator.tokenStore(appContext)
        if (!tokenStore.isLoggedIn()) {
            SecureLog.d(TAG, "skip enqueue: not logged in companionId=$companionId")
            return SKIPPED
        }
        if (!tokenStore.getForwardEnabled()) {
            SecureLog.d(TAG, "skip enqueue: forward disabled companionId=$companionId")
            return SKIPPED
        }

        val content = resolveContent(request)
        if (content.isNullOrBlank()) {
            SecureLog.d(TAG, "skip enqueue: empty content companionId=$companionId")
            return SKIPPED
        }

        val wechatUserIds = resolveUserIds(request, tokenStore)
        if (wechatUserIds.isEmpty()) {
            SecureLog.d(
                TAG,
                "skip enqueue reason=${WeChatFailureReason.MAPPING_MISSING.wireName} companionId=$companionId",
            )
            return SKIPPED
        }

        val repository = WeChatServiceLocator.messageRepository(appContext)
        val account = tokenStore.getAccount()
        val isSticker = WeChatContentCleaner.isStickerContent(content)
        var lastRootId = SKIPPED
        var enqueuedAny = false

        for (wechatUserId in wechatUserIds) {
            val contextToken = request.contextToken
                ?: account?.let { tokenStore.getContextToken(it.accountId, wechatUserId) }
            if (isSticker) {
                val rootId = enqueueSticker(
                    repository = repository,
                    companionId = companionId,
                    wechatUserId = wechatUserId,
                    content = content,
                    contextToken = contextToken,
                    sourceMessageId = request.sourceMessageId,
                )
                if (rootId != null) {
                    lastRootId = rootId
                    enqueuedAny = true
                }
            } else {
                val cleaned = WeChatContentCleaner.clean(content)
                if (cleaned.isBlank()) {
                    SecureLog.d(TAG, "skip blank after clean user=${mask(wechatUserId)}")
                    continue
                }
                lastRootId = repository.enqueueTextOutbound(
                    companionId = companionId,
                    wechatUserId = wechatUserId,
                    text = cleaned,
                    contextToken = contextToken,
                    sourceMessageId = request.sourceMessageId,
                )
                enqueuedAny = true
                SecureLog.d(
                    TAG,
                    "enqueued rootId=$lastRootId user=${mask(wechatUserId)} companionId=$companionId",
                )
            }
        }

        if (enqueuedAny) {
            val sent = runCatching { repository.drainOutbox() }.getOrDefault(0)
            SecureLog.d(TAG, "drain after enqueue sent=$sent")
        }
        return lastRootId
    }

    private suspend fun resolveContent(request: WeChatOutboundRequest): String? {
        if (!request.text.isNullOrBlank()) return request.text
        val messageId = request.sourceMessageId ?: return null
        if (messageId <= 0L) return null
        val msg = AppDatabase.getDatabase(appContext).messageDao().getMessageById(messageId)
        return msg?.body?.content
    }

    private suspend fun resolveUserIds(
        request: WeChatOutboundRequest,
        tokenStore: com.lianyu.ai.feature.wechat.data.WeChatTokenStore,
    ): List<String> {
        val explicit = request.wechatUserId?.trim().orEmpty()
        if (explicit.isNotEmpty()) return listOf(explicit)
        return tokenStore.getWechatUserIdsForCompanionId(request.companionId)
    }

    private suspend fun enqueueSticker(
        repository: com.lianyu.ai.feature.wechat.data.WeChatMessageRepository,
        companionId: Long,
        wechatUserId: String,
        content: String,
        contextToken: String?,
        sourceMessageId: Long?,
    ): String? {
        val stickerName = WeChatContentCleaner.extractStickerName(content) ?: run {
            SecureLog.w(TAG, "invalid sticker format")
            return null
        }
        val material = WeChatStickerMaterializer.materializeByName(appContext, stickerName) ?: return null
        val rootId = repository.enqueueImageOutbound(
            companionId = companionId,
            wechatUserId = wechatUserId,
            localPath = material.localPath,
            fileName = material.fileName,
            description = material.description,
            contextToken = contextToken,
            sourceMessageId = sourceMessageId,
        )
        SecureLog.d(
            TAG,
            "sticker enqueued rootId=$rootId user=${mask(wechatUserId)} name=$stickerName",
        )
        return rootId
    }

    companion object {
        private const val TAG = "WeChatOutboundPort"
        private const val SKIPPED = ""

        private fun mask(userId: String): String {
            if (userId.length <= 6) return "***"
            return userId.take(3) + "***" + userId.takeLast(2)
        }
    }
}

