package com.lianyu.ai.wechat.ilink

import android.util.Log
import com.github.wechat.ilink.sdk.ILinkClient
import com.github.wechat.ilink.sdk.core.config.ILinkConfig
import com.github.wechat.ilink.sdk.core.context.ContextKey
import com.github.wechat.ilink.sdk.core.context.ContextPoolManager
import com.github.wechat.ilink.sdk.core.context.ConversationContext
import com.github.wechat.ilink.sdk.core.context.ResumeContext
import com.github.wechat.ilink.sdk.core.login.LoginContext
import com.github.wechat.ilink.sdk.core.login.LoginStatus
import com.github.wechat.ilink.sdk.core.http.BusinessApiClient
import com.github.wechat.ilink.sdk.core.model.ApiResponse
import com.github.wechat.ilink.sdk.core.model.BaseInfo
import com.github.wechat.ilink.sdk.core.model.MessageItem
import com.github.wechat.ilink.sdk.core.model.SendMessageRequest
import com.github.wechat.ilink.sdk.core.utils.RandomUtils
import com.github.wechat.ilink.sdk.service.MessageService
import com.lianyu.ai.wechat.wire.WireWeChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * ilink SDK 会话管理（S4：会话稳定）。
 *
 * 规则：
 * 1. contextToken 变更 → 热更新内存 ConversationContext，禁止无脑 full rebuild
 * 2. full rebuild 仅：登录/登出、client 缺失、显式 force、鉴权类错误后
 * 3. 会话锁只保护 client 指针交换；长轮询不阻塞出站（与 SDK 内部串行配合）
 */
class IlinkClientManager(
    private val sessionStore: IlinkSessionStore,
) {
    private val mutex = Mutex()
    private var client: ILinkClient? = null

    /** 进行中的 SDK 调用数；>0 时推迟 force rebuild。 */
    private val inFlightOps = AtomicInteger(0)

    @Volatile
    private var forceRebuildPending = false

    suspend fun startLogin(): IlinkQrCode = withContext(Dispatchers.IO) {
        val sdkClient = mutex.withLock {
            client?.close()
            forceRebuildPending = false
            ILinkClient.builder()
                .config(defaultConfig())
                .build()
                .also { client = it }
        }

        val qrContent = sdkClient.executeLogin().orEmpty()
        IlinkQrCode(
            statusToken = sdkClient.qrcode ?: qrContent,
            displayContent = qrContent.ifBlank { sdkClient.qrcode.orEmpty() },
        )
    }

    suspend fun pollLoginStatus(): IlinkAccount = withContext(Dispatchers.IO) {
        val sdkClient = mutex.withLock { client }
            ?: throw IllegalStateException("请先获取微信登录二维码")

        val future = sdkClient.loginFuture
            ?: throw IllegalStateException("微信登录尚未开始")

        if (!future.isDone) {
            val status = sdkClient.loginStatus.status
            val message = when (status) {
                LoginStatus.Status.SCANNED -> "等待手机确认"
                LoginStatus.Status.WAITING -> "等待扫码"
                else -> "等待登录"
            }
            throw IllegalStateException(message)
        }

        val context = runCatching { future.get() }.getOrElse { error ->
            val status = sdkClient.loginStatus
            throw IllegalStateException(status.errorMessage ?: error.message ?: "微信登录失败", error)
        }

        val account = context.toAccount()
        sessionStore.saveSessionAccount(account)
        persistResumeContext(sdkClient.exportResumeContext(), account)
        account
    }

    suspend fun getUpdates(): List<WireWeChatMessage> = withClientOp { sdkClient ->
        val messages = sdkClient.getUpdates()
        messages.map(IlinkMessageMapper::toWireMessage)
    }

    suspend fun commitUpdates(): Unit = withClientOp { sdkClient ->
        sessionStore.getSessionAccount()?.let { account ->
            persistResumeContext(sdkClient.exportResumeContext(), account)
        }
    }

    suspend fun resetToCommittedUpdates(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (inFlightOps.get() > 0) {
                forceRebuildPending = true
            } else {
                client?.close()
                client = null
                forceRebuildPending = false
            }
        }
    }

    suspend fun sendText(toUserId: String, text: String, contextToken: String? = null): Unit =
        withClientOp { sdkClient ->
            if (!contextToken.isNullOrBlank()) {
                val account = sessionStore.getSessionAccount()
                    ?: throw IllegalStateException("未登录微信")
                sessionStore.saveContextToken(account.accountId, toUserId, contextToken)
                hotUpdateContextTokenLocked(sdkClient, toUserId, contextToken)
            }

            sdkClient.sendText(toUserId, text)
            sessionStore.getSessionAccount()?.let { account ->
                persistResumeContext(sdkClient.exportResumeContext(), account, includeUpdatesCursor = false)
            }
            Unit
        }

    suspend fun sendTextSegments(
        toUserId: String,
        segments: List<String>,
        contextToken: String? = null,
    ): Unit = withClientOp { sdkClient ->
        val texts = segments.filter { it.isNotBlank() }
        require(texts.isNotEmpty()) { "text segments empty" }
        if (!contextToken.isNullOrBlank()) {
            val account = sessionStore.getSessionAccount()
                ?: throw IllegalStateException("未登录微信")
            sessionStore.saveContextToken(account.accountId, toUserId, contextToken)
            hotUpdateContextTokenLocked(sdkClient, toUserId, contextToken)
        }

        sendTextItems(sdkClient, toUserId, texts)
        sessionStore.getSessionAccount()?.let { account ->
            persistResumeContext(sdkClient.exportResumeContext(), account, includeUpdatesCursor = false)
        }
    }

    suspend fun sendImage(
        toUserId: String,
        imageBytes: ByteArray,
        fileName: String,
        description: String? = null,
        contextToken: String? = null,
    ): Unit = withClientOp { sdkClient ->
        if (!contextToken.isNullOrBlank()) {
            val account = sessionStore.getSessionAccount()
                ?: throw IllegalStateException("未登录微信")
            sessionStore.saveContextToken(account.accountId, toUserId, contextToken)
            hotUpdateContextTokenLocked(sdkClient, toUserId, contextToken)
        }

        sdkClient.sendImage(toUserId, imageBytes, fileName, description.orEmpty())
        sessionStore.getSessionAccount()?.let { account ->
            persistResumeContext(sdkClient.exportResumeContext(), account, includeUpdatesCursor = false)
        }
        Unit
    }

    suspend fun downloadMedia(cdnInfo: IlinkCdnMedia): ByteArray? =
        withClientOp { sdkClient ->
            try {
                val encryptedQueryParam = cdnInfo.encryptQueryParam
                val aesKey = cdnInfo.aesKey

                if (encryptedQueryParam.isNullOrBlank() || aesKey.isNullOrBlank()) {
                    Log.w(TAG, "Missing CDN encryption info")
                    return@withClientOp null
                }

                Log.d(TAG, "Downloading media via SDK...")

                val sdkCdnMedia = com.github.wechat.ilink.sdk.core.model.CDNMedia().apply {
                    encrypt_query_param = encryptedQueryParam
                    aes_key = aesKey
                }

                val mediaBytes = sdkClient.downloadMedia(sdkCdnMedia)

                if (mediaBytes != null && mediaBytes.isNotEmpty()) {
                    Log.d(TAG, "Successfully downloaded ${mediaBytes.size} bytes")
                    mediaBytes
                } else {
                    Log.w(TAG, "SDK returned empty/null media")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download media", e)
                null
            }
        }

    /**
     * S4：contextToken 热更新（写 TokenStore + 内存池），不重建 client。
     */
    suspend fun notifyContextTokenUpdated(userId: String, contextToken: String? = null) {
        if (userId.isBlank()) return
        val token = contextToken?.takeIf { it.isNotBlank() }
        if (token != null) {
            sessionStore.getSessionAccount()?.let { account ->
                sessionStore.saveContextToken(account.accountId, userId, token)
            }
        }
        val liveToken = token
            ?: sessionStore.getSessionAccount()?.let {
                sessionStore.getContextToken(it.accountId, userId)
            }
            ?: return

        mutex.withLock {
            val existing = client
            if (existing != null && existing.isLoggedIn) {
                val ok = hotUpdateContextTokenLocked(existing, userId, liveToken)
                if (ok) {
                    Log.i(TAG, "Hot-updated contextToken for $userId (no rebuild)")
                } else {
                    Log.w(TAG, "Hot-update failed for $userId; defer rebuild only if needed later")
                }
            } else {
                Log.d(TAG, "No live client; contextToken persisted for $userId")
            }
        }
    }

    /**
     * 显式请求 full rebuild（鉴权失败 / 会话损坏）。
     * 有 in-flight 操作时推迟到空闲。
     */
    suspend fun requestForceRebuild(reason: String) {
        Log.w(TAG, "Force rebuild requested: $reason")
        mutex.withLock {
            if (inFlightOps.get() > 0) {
                forceRebuildPending = true
                Log.i(TAG, "Rebuild deferred (inFlight=${inFlightOps.get()})")
            } else {
                rebuildClientLocked("force:$reason")
            }
        }
    }

    suspend fun cancelLogin(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            client?.cancelLogin()
        }
        Unit
    }

    suspend fun closeAndClear(): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            client?.close()
            client = null
            forceRebuildPending = false
            inFlightOps.set(0)
        }
        sessionStore.clearSessionAccount()
        Unit
    }

    private suspend fun <T> withClientOp(block: suspend (ILinkClient) -> T): T =
        withContext(Dispatchers.IO) {
            val sdkClient = mutex.withLock {
                ensureLoggedInClientLocked().also {
                    inFlightOps.incrementAndGet()
                }
            }
            try {
                block(sdkClient)
            } catch (e: Exception) {
                if (isAuthFailure(e)) {
                    Log.w(TAG, "Auth/session failure, scheduling rebuild: ${e.message}")
                    mutex.withLock {
                        forceRebuildPending = true
                    }
                }
                throw e
            } finally {
                mutex.withLock {
                    val left = inFlightOps.decrementAndGet().coerceAtLeast(0)
                    if (left == 0 && forceRebuildPending) {
                        rebuildClientLocked("deferred-after-inflight")
                    }
                }
            }
        }

    private suspend fun ensureLoggedInClientLocked(): ILinkClient {
        val existing = client
        if (existing != null && existing.isLoggedIn && !forceRebuildPending) {
            return existing
        }
        return rebuildClientLocked(
            if (forceRebuildPending) "pending-force" else "missing-or-logged-out",
        )
    }

    private suspend fun rebuildClientLocked(reason: String): ILinkClient {
        Log.i(TAG, "Rebuilding ILinkClient ($reason)")
        client?.close()
        forceRebuildPending = false
        return buildClientFromStore().also { client = it }
    }

    /**
     * 热更新 SDK 内存中的 ConversationContext.latestContextToken。
     * ContextPoolManager 对 ILinkClient 是私有字段，经反射访问（适配层边界）。
     */
    private fun hotUpdateContextTokenLocked(
        sdkClient: ILinkClient,
        userId: String,
        token: String,
    ): Boolean {
        return runCatching {
            val login = sdkClient.loginContext ?: return false
            val botId = login.botId?.takeIf { it.isNotBlank() } ?: return false
            val pool = resolveContextPool(sdkClient) ?: return false
            val ctx = pool.getOrCreate(botId, userId)
            ctx.setLatestContextToken(token)
            true
        }.onFailure { e ->
            Log.e(TAG, "hotUpdateContextToken failed for $userId", e)
        }.getOrDefault(false)
    }

    private fun resolveContextPool(sdkClient: ILinkClient): ContextPoolManager? {
        return runCatching {
            val field = ILinkClient::class.java.getDeclaredField("contextPoolManager")
            field.isAccessible = true
            field.get(sdkClient) as? ContextPoolManager
        }.getOrNull()
    }

    private fun sendTextItems(sdkClient: ILinkClient, toUserId: String, texts: List<String>) {
        val login = sdkClient.loginContext ?: throw IllegalStateException("未登录微信")
        val botId = login.botId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("微信 botId 缺失")
        val context = resolveContextPool(sdkClient)?.get(botId, toUserId)
            ?: throw IllegalStateException("微信会话上下文缺失")
        val contextToken = context.latestContextToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("微信 contextToken 缺失")
        val serviceField = ILinkClient::class.java.getDeclaredField("messageService").apply {
            isAccessible = true
        }
        val messageService = serviceField.get(sdkClient) as MessageService
        val apiClientField = MessageService::class.java.getDeclaredField("apiClient").apply {
            isAccessible = true
        }
        val apiClient = apiClientField.get(messageService) as BusinessApiClient
        for (text in texts) {
            val msg = SendMessageRequest.Msg(
                toUserId,
                RandomUtils.clientId("ilink-sdk"),
                contextToken,
                listOf(MessageItem.text(text)),
            )
            apiClient.post(
                login,
                "/ilink/bot/sendmessage",
                SendMessageRequest(msg, BaseInfo(sdkClient.config.channelVersion)),
                ApiResponse::class.java,
            )
        }
    }

    private suspend fun buildClientFromStore(): ILinkClient {
        val resumeContext = buildResumeContext()
            ?: throw IllegalStateException("未登录微信")

        return ILinkClient.builder()
            .config(defaultConfig())
            .resumeContext(resumeContext)
            .build()
    }

    private suspend fun buildResumeContext(): ResumeContext? {
        val account = sessionStore.getSessionAccount() ?: return null
        val loginContext = LoginContext(
            account.botToken,
            account.ilinkUserId,
            account.ilinkBotId,
            account.baseUrl.ifBlank { DEFAULT_BASE_URL },
        )

        val contexts = sessionStore.getContextTokens(account.accountId)
            .mapValues { (userId, token) ->
                ConversationContext(ContextKey(account.ilinkBotId, userId)).apply {
                    latestContextToken = token
                }
            }

        return ResumeContext.builder(loginContext)
            .updatesCursor(sessionStore.getCursor())
            .conversationContexts(contexts)
            .build()
    }

    private suspend fun persistResumeContext(
        resumeContext: ResumeContext?,
        account: IlinkAccount,
        includeUpdatesCursor: Boolean = true,
    ) {
        if (resumeContext == null) return

        if (includeUpdatesCursor) {
            resumeContext.updatesCursor?.let { cursor ->
                sessionStore.saveCursor(cursor)
            }
        }

        resumeContext.conversationContexts.forEach { context ->
            val userId = context.key?.userId
            val token = context.latestContextToken
            if (!userId.isNullOrBlank() && !token.isNullOrBlank()) {
                sessionStore.saveContextToken(account.accountId, userId, token)
            }
        }
    }

    private fun LoginContext.toAccount(): IlinkAccount {
        return IlinkAccount(
            botToken = botToken.orEmpty(),
            ilinkBotId = botId.orEmpty(),
            ilinkUserId = userId.orEmpty(),
            baseUrl = baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
        )
    }

    private fun defaultConfig(): ILinkConfig {
        // S4：恢复 SDK 默认心跳，降低会话静默掉线概率
        return ILinkConfig.builder()
            .connectTimeoutMs(10_000)
            .readTimeoutMs(35_000)
            .writeTimeoutMs(10_000)
            .loginTimeoutMs(LOGIN_TIMEOUT_MS)
            .heartbeatEnabled(true)
            .heartbeatIntervalMs(30_000)
            .channelVersion("1.0.3")
            .build()
    }

    private fun isAuthFailure(error: Throwable): Boolean {
        val msg = error.message.orEmpty().lowercase()
        return msg.contains("unauthorized") ||
            msg.contains("token invalid") ||
            msg.contains("not logged") ||
            (msg.contains("login") && msg.contains("expire")) ||
            msg.contains("session expired") ||
            msg.contains("session invalid")
    }

    private companion object {
        const val TAG = "IlinkClientManager"
        const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"
        const val LOGIN_TIMEOUT_MS = 5 * 60 * 1000L
    }
}

