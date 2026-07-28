package com.lianyu.ai.feature.wechat.data

import android.content.Context
import android.util.Log
import com.lianyu.ai.common.AppForegroundTracker
import com.lianyu.ai.common.TimeoutBudgets
import com.lianyu.ai.domain.wechat.WeChatContentKind
import com.lianyu.ai.domain.wechat.WeChatMediaRef
import com.lianyu.ai.domain.wechat.WeChatOutboundRequest
import com.lianyu.ai.feature.wechat.data.model.M0
import com.lianyu.ai.feature.wechat.data.model.M1Type
import com.lianyu.ai.feature.wechat.WeChatDebugLog
import com.lianyu.ai.feature.wechat.service.WeChatAiReplyWorker
import com.lianyu.ai.feature.wechat.service.WeChatNotificationHelper
import com.lianyu.ai.feature.wechat.service.WeChatServiceLocator
import com.lianyu.ai.wechat.outbox.WeChatOutboxCoordinator
import com.lianyu.ai.wechat.ilink.IlinkClientManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WeChatMessageRepository(
    context: Context,
    private val sdkClientManager: IlinkClientManager,
    private val tokenStore: WeChatTokenStore
) {
    private val appContext = context.applicationContext
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _incomingMessages = MutableSharedFlow<M0>(
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingMessages: Flow<M0> = _incomingMessages.asSharedFlow()

    val accountFlow = tokenStore.accountFlow
    val isLoggedInFlow = tokenStore.accountFlow.map { it != null }
    suspend fun isLoggedIn(): Boolean = tokenStore.isLoggedIn()

    suspend fun getQrCode(): Result<WeChatQrCode> = withContext(Dispatchers.IO) {
        runCatching {
            sdkClientManager.startLogin().let { qrCode ->
                WeChatQrCode(qrCode.statusToken, qrCode.displayContent)
            }
        }
    }

    suspend fun pollQrCodeStatus(qrCode: String): Result<A0> = withContext(Dispatchers.IO) {
        runCatching {
            sdkClientManager.pollLoginStatus().let { account ->
                A0(
                    botToken = account.botToken,
                    ilinkBotId = account.ilinkBotId,
                    ilinkUserId = account.ilinkUserId,
                    baseUrl = account.baseUrl,
                    accountId = account.accountId,
                )
            }
        }
    }

    suspend fun logout() {
        sdkClientManager.closeAndClear()
        WeChatServiceLocator.inboxCoordinator(appContext).cancelAll()
        com.lianyu.ai.feature.wechat.service.WeChatChannelRuntime.reset()
        processScope.cancel()
    }

    /**
     * 销毁 Repository，取消所有协程和订阅。
     * 应在 WeChat 功能完全退出时调用。
     */
    fun destroy() {
        processScope.cancel()
    }

    suspend fun pollMessages(timeoutMs: Long = TimeoutBudgets.WECHAT_POLL_TIMEOUT_MS): Result<WeChatPollResult> = withContext(Dispatchers.IO) {
        runCatching {
            val account = tokenStore.getAccount()
                ?: throw IllegalStateException("未登录微信")

            val rawMessages = sdkClientManager.getUpdates()
            val messages = rawMessages.map { WeChatSdkMessageMapper.toAppMessage(it) }
            if (messages.isNotEmpty()) {
                WeChatDebugLog.log("[Repo] getUpdates returned ${messages.size} messages")
            }

            try {
                if (messages.isNotEmpty()) {
                    messages.forEach { msg -> handleIncomingMessageFast(account, msg) }
                }
                sdkClientManager.commitUpdates()
            } catch (error: Exception) {
                sdkClientManager.resetToCommittedUpdates()
                throw error
            }

            // S1：轮询间隙顺带 drain 出站队列
            runCatching { drainOutbox() }

            WeChatPollResult(messages)
        }
    }

    /**
     * 直接发送（兼容 UI 手动发消息）。
     * 主动同步 / Bridge 自动回复应优先 [enqueueTextOutbound]。
     */
    suspend fun sendTextMessage(
        toUserId: String,
        text: String,
        contextToken: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            tokenStore.getAccount()
                ?: throw IllegalStateException("未登录微信")
            sdkClientManager.sendText(toUserId, text, contextToken)
        }.mapFailure(::toUserFacingException)
    }

    suspend fun sendImageMessage(
        toUserId: String,
        imageBytes: ByteArray,
        fileName: String,
        description: String? = null,
        contextToken: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            tokenStore.getAccount()
                ?: throw IllegalStateException("未登录微信")
            sdkClientManager.sendImage(toUserId, imageBytes, fileName, description, contextToken)
        }.mapFailure(::toUserFacingException)
    }

    /**
     * S1：文本入 Outbox（SIMPLE 分段），返回 rootId。
     */
    suspend fun enqueueTextOutbound(
        companionId: Long,
        wechatUserId: String,
        text: String,
        contextToken: String? = null,
        sourceMessageId: Long? = null,
    ): String {
        val outbox = WeChatServiceLocator.outboxCoordinator(appContext)
        return outbox.enqueue(
            WeChatOutboundRequest(
                companionId = companionId,
                wechatUserId = wechatUserId,
                sourceMessageId = sourceMessageId,
                text = text,
                contextToken = contextToken,
            ),
            wechatUserId = wechatUserId,
        )
    }

    /**
     * S1：本地图片路径入 Outbox（单段）。
     */
    suspend fun enqueueImageOutbound(
        companionId: Long,
        wechatUserId: String,
        localPath: String,
        fileName: String? = null,
        description: String? = null,
        contextToken: String? = null,
        sourceMessageId: Long? = null,
    ): String {
        val outbox = WeChatServiceLocator.outboxCoordinator(appContext)
        return outbox.enqueue(
            WeChatOutboundRequest(
                companionId = companionId,
                wechatUserId = wechatUserId,
                sourceMessageId = sourceMessageId,
                media = WeChatMediaRef(
                    kind = WeChatContentKind.IMAGE,
                    localPath = localPath,
                    fileName = fileName,
                    description = description,
                ),
                contextToken = contextToken,
            ),
            wechatUserId = wechatUserId,
        )
    }

    /**
     * S7：表情物化后入 Outbox（可重试）；失败返回 null。
     */
    suspend fun enqueueStickerOutbound(
        companionId: Long,
        wechatUserId: String,
        sticker: com.lianyu.ai.common.StickerInfo,
        contextToken: String? = null,
        sourceMessageId: Long? = null,
    ): String? {
        val material = WeChatStickerMaterializer.materialize(appContext, sticker) ?: return null
        return enqueueImageOutbound(
            companionId = companionId,
            wechatUserId = wechatUserId,
            localPath = material.localPath,
            fileName = material.fileName,
            description = material.description,
            contextToken = contextToken,
            sourceMessageId = sourceMessageId,
        )
    }

    suspend fun drainOutbox(limit: Int = WeChatOutboxCoordinator.DEFAULT_DRAIN_LIMIT): Int {
        return WeChatServiceLocator.outboxCoordinator(appContext).drain(limit)
    }

    suspend fun getContextToken(userId: String): String? {
        val account = tokenStore.getAccount() ?: return null
        return tokenStore.getContextToken(account.accountId, userId)
    }

    fun extractText(message: M0): String {
        return extractInboundText(message).orEmpty()
    }

    private suspend fun handleIncomingMessageFast(account: A0, message: M0) {
        // S4：contextToken 热更新（写 store + SDK 内存池），禁止 stale full rebuild
        message.fromUserId?.let { userId ->
            message.contextToken?.let { token ->
                val existingToken = tokenStore.getContextToken(account.accountId, userId)
                if (existingToken != token) {
                    sdkClientManager.notifyContextTokenUpdated(userId, token)
                }
            }
        }

        // UI 旁路：可丢；处理主路径走 Inbox 去重 + 串行
        _incomingMessages.tryEmit(message)

        val text = extractText(message)
        val isImageMessage = isImageMessage(message)

        Log.d(TAG, "Received message: from=${message.fromUserId}, type=${message.messageType}, hasText=${text.isNotBlank()}, isImage=$isImageMessage")

        if (text.isBlank() && !isImageMessage) {
            Log.d(TAG, "Skipping message: no text content and not an image")
            WeChatDebugLog.log("[Repo] Skipping empty message from=${message.fromUserId}")
            return
        }

        val inbound = M0WireAdapter.toInbound(message)
        if (inbound == null) {
            Log.w(TAG, "Failed to map M0 to inbound domain message")
            return
        }

        val inbox = WeChatServiceLocator.inboxCoordinator(appContext)
        // 默认不 await handler：AI 在 per-user 串行队列异步跑，poll 可继续 getUpdates
        val accepted = inbox.acceptIfNew(inbound) { acceptedInbound ->
            processAcceptedInbound(message, acceptedInbound.primaryText.orEmpty(), isImageMessage)
        }
        if (!accepted) {
            Log.d(TAG, "Duplicate inbound dropped: ${inbound.dedupeKey}")
            WeChatDebugLog.log("[Repo] Duplicate dropped key=${inbound.dedupeKey}")
        } else {
            WeChatDebugLog.log("[Repo] Accepted inbound from=${message.fromUserId} text_len=${text.length}")
        }
    }

    private suspend fun processAcceptedInbound(
        message: M0,
        text: String,
        isImageMessage: Boolean,
    ) {
        val notifyEnabled = tokenStore.getNotifyEnabled()
        val autoReplyEnabled = tokenStore.getAutoReply()
        val fromUserId = message.fromUserId ?: return

        if (isImageMessage) {
            if (notifyEnabled) {
                WeChatNotificationHelper.showIncomingMessageNotification(appContext, message)
            }
            if (!autoReplyEnabled || fromUserId.isBlank()) {
                Log.d(TAG, "Auto-reply disabled or userId blank for image from $fromUserId")
                return
            }
            Log.d(TAG, "Starting AI vision reply for image from $fromUserId (serial queue)")
            try {
                val bridge = WeChatServiceLocator.chatBridge(appContext)
                bridge.handleImageMessage(fromUserId, message)
            } catch (e: Exception) {
                Log.e(TAG, "AI vision reply failed for image", e)
                // 图片上下文无法可靠塞进 Worker；用占位文本兜底，避免整条链路静默丢失
                WeChatAiReplyWorker.enqueue(appContext, fromUserId, "[图片]")
            }
            return
        }

        val decision = WeChatIncomingMessagePolicy.evaluate(
            isAppInForeground = AppForegroundTracker.isInForeground,
            notifyEnabled = notifyEnabled,
            autoReplyEnabled = autoReplyEnabled,
            messageText = text,
        )

        if (decision.shouldNotify) {
            WeChatNotificationHelper.showIncomingMessageNotification(appContext, message)
        }

        if (!decision.shouldAutoReply || fromUserId.isBlank()) {
            WeChatDebugLog.log("[Repo] Auto-reply skipped: enabled=$autoReplyEnabled from=$fromUserId")
            return
        }

        // S2：同用户串行排队，不再 skip 并发入站
        Log.d(TAG, "Starting AI reply for $fromUserId (serial queue)")
        WeChatDebugLog.log("[Repo] Starting AI reply for $fromUserId text_len=${text.length}")
        try {
            val bridge = WeChatServiceLocator.chatBridge(appContext)
            val reply = bridge.handleTextMessage(fromUserId, text)
            WeChatDebugLog.log("[Repo] AI reply done for $fromUserId reply_len=${reply?.length ?: 0}")
        } catch (e: Exception) {
            Log.e(TAG, "Direct AI reply failed, falling back to Worker", e)
            WeChatDebugLog.log("[Repo] AI reply FAILED for $fromUserId: ${e.message}")
            WeChatAiReplyWorker.enqueue(appContext, fromUserId, text)
        }
    }

    private fun isImageMessage(message: M0): Boolean {
        return message.messageType == M1Type.IMAGE.value ||
            message.itemList?.any { it.type == M1Type.IMAGE.value && it.imageItem != null } == true
    }

    private fun toUserFacingException(error: Throwable): Throwable {
        val message = error.message.orEmpty()
        return if (message.contains("missing latest context token", ignoreCase = true)) {
            IllegalStateException("缺少 context_token，请先让对方发送消息", error)
        } else {
            error
        }
    }

    companion object {
        private const val TAG = "WeChatMsgRepo"
    }
}

data class WeChatPollResult(
    val messages: List<M0>
)

data class WeChatQrCode(
    val statusToken: String,
    val displayContent: String
)

private inline fun <T> Result<T>.mapFailure(transform: (Throwable) -> Throwable): Result<T> {
    return fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(transform(it)) }
    )
}
