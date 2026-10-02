package com.yunian.ai.feature.qqbot.data

import android.content.Context
import com.yunian.ai.common.concurrent.AppDispatchers
import com.yunian.ai.feature.qqbot.QQBotDebugLog
import com.yunian.ai.feature.qqbot.data.model.QQBotAccount
import com.yunian.ai.feature.qqbot.data.model.QQGatewayPayload
import com.yunian.ai.feature.qqbot.data.model.QQInboundEvent
import com.yunian.ai.feature.qqbot.data.model.QQMediaFileType
import com.yunian.ai.feature.qqbot.data.model.QQMediaInfo
import com.yunian.ai.feature.qqbot.data.model.QQMessageEvent
import com.yunian.ai.feature.qqbot.data.model.QQMessageType
import com.yunian.ai.feature.qqbot.data.model.QQProactiveSendResult
import com.yunian.ai.feature.qqbot.data.model.QQProactiveTarget
import com.yunian.ai.feature.qqbot.data.model.QQProactiveTargetKind
import com.yunian.ai.feature.qqbot.data.model.QQReadyData
import com.yunian.ai.feature.qqbot.data.model.SendMediaRequest
import com.yunian.ai.feature.qqbot.data.model.SendMessageResponse
import com.yunian.ai.feature.qqbot.data.model.SendTextRequest
import com.yunian.ai.feature.qqbot.data.model.UploadFileRequest
import com.yunian.ai.feature.qqbot.data.network.QQBotApiClient
import com.yunian.ai.feature.qqbot.data.network.ConnectionState
import com.yunian.ai.feature.qqbot.data.network.QQBotWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 主动发送目标标识的持久化接缝（**实现 = [QQBotTokenStore]，不新增 Room 表 / 字段 / 索引**）。
 *
 * 单独抽出接口的唯一理由是**可测性**：QQBotTokenStore 依赖 Android DataStore，
 * 而 feature:qqbot 的测试源集没有 Robolectric，纯 JVM 单测无法构造它。
 * 抽出窄接口后，仓库的主动发送路径可以在纯 JVM 里用内存替身驱动；
 * 生产装配仍然注入同一个 [QQBotTokenStore] 实例（见 [QQBotServiceLocator]）。
 */
interface QQProactiveTargetStore {
    /** 宿主（用户本人）的 user_openid；未捕获过则为 null（**必须如实返回 null，不得编造**）。 */
    suspend fun getHostUserOpenId(): String?

    /** 记录宿主（用户本人）的 user_openid（空白值忽略）。 */
    suspend fun setHostUserOpenId(openId: String?)

    /** 最近一次入站群消息携带的 group_openid；未捕获过则为 null。 */
    suspend fun getRecentGroupOpenId(): String?

    /** 记录最近一次入站群消息的 group_openid（空白值忽略）。 */
    suspend fun setRecentGroupOpenId(openId: String?)

}

/**
 * QQ Bot 消息仓库（既有收发链路的**唯一持有者**）。
 *
 * 本类同时承担两条出站通路，二者的差异只在 `msg_id` 一个字段上：
 * - **被动回复** [sendTextMessage]：`msgId = event.raw.id`（现状逐字不变）；
 * - **主动发送** [sendProactiveText]：**省略 `msg_id`**（官方文档：`msg_id` 为「否（非必填）」，
 *   省略即主动消息，受主动消息频控约束）。
 *
 * 连接 / 心跳 / 重连 / 入站时序完全不受影响：本类没有新增任何定时器或后台循环。
 *
 * ## 出站**刻意不做**串行化（原先的 `sendMutex` 是死字段，已删除）
 *
 * 三条出站通路（[sendTextMessage] / [sendProactiveText] / [sendImageMessage]）
 * **不共享任何需要互斥的本地状态**，所以这里不放锁：
 *
 * - `msg_seq` 的唯一性由 `msgSeqCounter`（`AtomicInteger`）保证，**与调用顺序无关**
 *   —— 同一 `msg_id` 内唯一递增即满足要求，进程级全局自增天然满足。
 *   ⚠️ **不得**改成「每个 `msg_id` 重置」：那反而会破坏唯一性。
 * - 令牌刷新与 REST API 实例缓存**已经**由 [QQBotApiClient] 自己的 `tokenMutex`
 *   串行化（含双重检查）—— 那才是正确的那一层，本类重复加锁没有增益。
 * - 同一会话内的发送顺序由**上一层**保证：[QQBotChatBridge] 每个 replyKey
 *   同时只有一个 `activeReplyJob`，且一次回复内的分句循环是 `await` 逐条发送的。
 *
 * 反过来，在这里加锁**有害**：它会把互不相关的会话串起来 —— A 用户的回复会因
 * 500ms 节流占住锁，B 用户只能排队。QQ 侧对被动回复的约束是**次数**约束
 * （同一 `msg_id` 能被回复的条数有限），不是并发约束：加锁既不能提高上限，
 * 也不能让超限的回复被接受。
 *
 * @param proactiveTargetStore 主动发送目标标识的持久化（生产 = 同一个 [QQBotTokenStore]）。
 */
class QQBotMessageRepository(
    context: Context,
    private val tokenStore: QQBotTokenStore,
    private val apiClient: QQBotApiClient,
    private val proactiveTargetStore: QQProactiveTargetStore = tokenStore,
) {
    private val appContext = context.applicationContext
    private val processScope = CoroutineScope(SupervisorJob() + AppDispatchers.io)
    private val json = Json { ignoreUnknownKeys = true }

    private val _incomingEvents = MutableSharedFlow<QQInboundEvent>(
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingEvents: Flow<QQInboundEvent> = _incomingEvents.asSharedFlow()

    val accountFlow = tokenStore.accountFlow
    val isLoggedInFlow = accountFlow.map { it != null }
    suspend fun isLoggedIn(): Boolean = tokenStore.isLoggedIn()

    /**
     * 是否已有账号（**同步**，不碰 DataStore）：供非 suspend 的可用性判定使用
     * （[com.yunian.ai.domain.AiTool.isAvailable]）。
     */
    fun hasAccount(): Boolean = tokenStore.hasAccount()

    /**
     * 宿主（用户本人）的 user_openid——主动发送的默认目标。
     *
     * 未捕获过时返回 null（**如实返回 null，不编造**）：调用方据此明确失败，
     * 而不是发到一个猜测出来的目标上。
     */
    suspend fun hostUserOpenId(): String? = proactiveTargetStore.getHostUserOpenId()

    /**
     * 记录宿主（用户本人）的 user_openid。
     *
     * 两个来源都会调用它（见 [QQBotTokenStore] 的 HOST_USER_OPENID_KEY KDoc）：
     * 绑定流程返回的 `user_openid`、入站 C2C 消息的 `author.user_openid`。
     * 写入是幂等的：值未变化时直接返回，避免每次入站消息都触发一次 DataStore 写。
     */
    suspend fun rememberHostUserOpenId(openId: String?) {
        val trimmed = openId?.trim()
        if (trimmed.isNullOrEmpty()) return
        if (proactiveTargetStore.getHostUserOpenId() == trimmed) return
        proactiveTargetStore.setHostUserOpenId(trimmed)
    }

    /** 最近一次入站群聊的 group_openid，供稳定别名 `target=group` 使用。 */
    suspend fun recentGroupOpenId(): String? = proactiveTargetStore.getRecentGroupOpenId()

    /**
     * 记录最近一次入站群聊目标。只保存路由 ID，不保存/复用 msg_id：
     * `target=group` 必须保持真正主动发送语义。
     */
    suspend fun rememberRecentGroupOpenId(openId: String?) {
        val trimmed = openId?.trim()
        if (trimmed.isNullOrEmpty()) return
        if (proactiveTargetStore.getRecentGroupOpenId() == trimmed) return
        proactiveTargetStore.setRecentGroupOpenId(trimmed)
        QQBotDebugLog.log("[Route] captured recent group target")
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var webSocketClient: QQBotWebSocketClient? = null
    private val connectLock = Any()
    private val msgSeqCounter = AtomicInteger(1)
    private val activeReplyJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** 机器人自身 OpenID，来自 READY；用于全量群消息模式的 @ 判定。 */
    @Volatile
    private var botOpenId: String? = null

    private val seenMessageIds = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean {
            return size > 1000
        }
    }

    suspend fun saveAccount(appId: String, clientSecret: String, customName: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (appId.isBlank() || clientSecret.isBlank()) {
                throw IllegalArgumentException("AppID 和 ClientSecret 不能为空")
            }
            tokenStore.saveAccount(QQBotAccount(appId, clientSecret, customName))

            apiClient.createAuthenticatedRestApi()
            Unit
        }
    }

    suspend fun logout() {
        disconnect()
        activeReplyJobs.values.forEach { it.cancel() }
        activeReplyJobs.clear()
        tokenStore.clearAccount()
        apiClient.clearApiCache()
        synchronized(seenMessageIds) { seenMessageIds.clear() }
    }

    fun destroy() {
        disconnect()
        processScope.cancel()
    }

    suspend fun connect() {
        val client = synchronized(connectLock) {
            if (webSocketClient != null) return
            QQBotWebSocketClient(tokenStore, apiClient, onEvent = { payload ->
                handleGatewayPayload(payload)
            }, onConnectionStateChange = { state ->
                _connectionState.value = state
            }).also { webSocketClient = it }
        }
        client.connect()
    }

    /**
     * 连接是否卡死在中间态。无客户端（未连接）时返回 false——那属于「未启动」而非「卡死」。
     */
    fun isConnectionStuck(): Boolean = synchronized(connectLock) {
        webSocketClient?.isConnectionStuck() ?: false
    }

    fun disconnect() {
        synchronized(connectLock) {
            webSocketClient?.disconnect()
            webSocketClient?.destroy()
            webSocketClient = null
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    suspend fun sendTextMessage(event: QQInboundEvent, text: String): Result<SendMessageResponse?> = withContext(Dispatchers.IO) {
        runCatching {
            val api = apiClient.createAuthenticatedRestApi()
            val request = SendTextRequest(
                content = text,
                msgType = QQMessageType.TEXT.value,
                msgId = event.raw.id,
                msgSeq = msgSeqCounter.getAndIncrement()
            )
            when (event) {
                is QQInboundEvent.C2CMessage -> {
                    val response = api.sendC2CMessage(event.userOpenid, request)
                    if (!response.isSuccessful) throw IllegalStateException("发送失败: ${response.code()}")
                    response.body()
                }
                is QQInboundEvent.GroupAtMessage -> {
                    val response = api.sendGroupMessage(event.groupOpenid, request)
                    if (!response.isSuccessful) throw IllegalStateException("发送失败: ${response.code()}")
                    response.body()
                }
                is QQInboundEvent.GuildMessage -> {
                    val response = api.sendChannelMessage(event.channelId, request)
                    if (!response.isSuccessful) throw IllegalStateException("发送失败: ${response.code()}")
                    response.body()
                }
                is QQInboundEvent.DirectMessage -> {
                    val response = api.sendDirectMessage(event.guildId, request)
                    if (!response.isSuccessful) throw IllegalStateException("发送失败: ${response.code()}")
                    response.body()
                }
            }
        }
    }

    /**
     * **主动发送**文本到指定目标（省略 `msg_id`，不锚定任何入站消息）。
     *
     * 与 [sendTextMessage] 的唯一差异就是构造请求时**不传 `msgId`**：
     * `SendTextRequest.msgId` 默认 `null`，而仓库的 Json 配置未开启 `explicitNulls`
     * （kotlinx.serialization 默认省略 null），因此请求体里**根本不会出现 `msg_id` 字段**。
     *
     * 目标为空 / 目标类型与标识不匹配 → **明确失败**，绝不退化成「发给谁算谁」。
     * 返回 [QQProactiveSendResult.deliveryReceipt] 恒为 `false`：QQ 没有 ack，
     * 上层不得把它当送达凭证。
     *
     * @param target 目标（用户 openid 或 group_openid）。
     * @param text 文本内容（空白内容明确失败）。
     */
    suspend fun sendProactiveText(
        target: QQProactiveTarget,
        text: String,
    ): Result<QQProactiveSendResult> = withContext(Dispatchers.IO) {
        runCatching {
            val request = QQBotProactiveRequests.buildTextRequest(target = target, text = text)
            val api = apiClient.createAuthenticatedRestApi()
            val response = when (target.kind) {
                QQProactiveTargetKind.USER -> api.sendC2CMessage(target.id, request)
                QQProactiveTargetKind.GROUP -> api.sendGroupMessage(target.id, request)
            }
            if (!response.isSuccessful) {
                // errorBody 最多保留 500 字符到 debug-only 私有文件；不包含 token / 请求正文。
                val detail = runCatching { response.errorBody()?.string().orEmpty() }
                    .getOrDefault("")
                    .take(500)
                QQBotDebugLog.log(
                    "[Route] proactive HTTP failed code=" + response.code() +
                        if (detail.isBlank()) "" else " body=" + detail,
                )
                throw IllegalStateException(
                    QQBotProactiveErrorMapper.message(response.code(), detail),
                )
            }
            QQProactiveSendResult(
                messageRef = response.body()?.id,
                deliveryReceipt = false,
            )
        }
    }

    /**
     * 发送富媒体图片（QQ 官方机器人 API：先上传 file_data 拿 file_info，再用 msg_type=7 发送）。
     *
     * 频道场景（GuildMessage / DirectMessage）没有富媒体上传接口，直接返回失败，
     * 由调用方降级为文字说明——绝不允许静默失败。
     */
    suspend fun sendImageMessage(event: QQInboundEvent, filePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val api = apiClient.createAuthenticatedRestApi()
                val file = java.io.File(filePath)
                if (!file.exists() || !file.isFile) {
                    throw IllegalStateException("图片文件不存在: $filePath")
                }
                val base64 = android.util.Base64.encodeToString(
                    file.readBytes(),
                    android.util.Base64.NO_WRAP,
                )
                val upload = UploadFileRequest(
                    fileType = QQMediaFileType.IMAGE.value,
                    fileData = base64,
                    srvSendMsg = false,
                    fileName = file.name,
                )
                val fileInfo = when (event) {
                    is QQInboundEvent.C2CMessage -> {
                        val response = api.uploadC2CFile(event.userOpenid, upload)
                        if (!response.isSuccessful) {
                            throw IllegalStateException("图片上传失败: ${response.code()}")
                        }
                        response.body()?.fileInfo
                            ?: throw IllegalStateException("图片上传返回缺少 file_info")
                    }
                    is QQInboundEvent.GroupAtMessage -> {
                        val response = api.uploadGroupFile(event.groupOpenid, upload)
                        if (!response.isSuccessful) {
                            throw IllegalStateException("图片上传失败: ${response.code()}")
                        }
                        response.body()?.fileInfo
                            ?: throw IllegalStateException("图片上传返回缺少 file_info")
                    }
                    is QQInboundEvent.GuildMessage ->
                        throw IllegalStateException("频道消息暂不支持发送图片")
                    is QQInboundEvent.DirectMessage ->
                        throw IllegalStateException("频道私信暂不支持发送图片")
                }
                val request = SendMediaRequest(
                    msgType = QQMessageType.MEDIA.value,
                    msgId = event.raw.id,
                    msgSeq = msgSeqCounter.getAndIncrement(),
                    media = QQMediaInfo(fileInfo),
                )
                val response = when (event) {
                    is QQInboundEvent.C2CMessage -> api.sendC2CMediaMessage(event.userOpenid, request)
                    is QQInboundEvent.GroupAtMessage -> api.sendGroupMediaMessage(event.groupOpenid, request)
                    is QQInboundEvent.GuildMessage ->
                        throw IllegalStateException("频道消息暂不支持发送图片")
                    is QQInboundEvent.DirectMessage ->
                        throw IllegalStateException("频道私信暂不支持发送图片")
                }
                if (!response.isSuccessful) {
                    throw IllegalStateException("图片发送失败: ${response.code()}")
                }
                Unit
            }
        }

    private suspend fun handleGatewayPayload(payload: QQGatewayPayload) {
        if (payload.op != 0) return
        val eventType = payload.t ?: return

        // 每个 dispatch 都留痕：这是判断「群事件到底有没有被平台推送」的唯一依据。
        // 本应用的 logcat 在部分机型被系统抑制，文件日志才是可靠出口。
        QQBotDebugLog.log("[Repo] dispatch type=" + eventType)

        // READY 携带机器人自身 OpenID：全量群消息模式靠它判断是否被 @。
        if (eventType == QQInboundEventMapper.READY) {
            captureBotOpenId(payload)
            return
        }
        if (!QQInboundEventMapper.supports(eventType)) return

        val event = payload.d?.let { json.decodeFromJsonElement<QQMessageEvent>(it) } ?: return

        // 先映射再判重：全量模式下未被 @ 的消息不该占用去重表，
        // 否则平台重复推送的同一条消息可能被误判，丢掉真正该回的那条。
        //
        // 丢弃必须留痕：此前只有全量群消息分支打日志，群 @ 分支一行都没有，
        // 群聊一旦因字段缺失被丢弃就是完全静默的（实测该应用 logcat 在部分机型
        // 还会被系统抑制），因此这里同时写 logcat 与 QQ 通道的文件日志。
        // 原因由 QQInboundEventMapper 唯一产出，不在此处复刻判定。
        val inbound = when (val outcome = QQInboundEventMapper.outcome(eventType, event, resolvedBotOpenId())) {
            is QQInboundEventMapper.MapOutcome.Mapped -> outcome.event
            is QQInboundEventMapper.MapOutcome.Dropped -> {
                QQBotDebugLog.log(
                    "[Repo] inbound DROPPED type=" + eventType + " id=" + event.id +
                        " reason=" + outcome.reason
                )
                android.util.Log.d(TAG, "inbound dropped type=" + eventType + " reason=" + outcome.reason)
                return
            }
        }

        synchronized(seenMessageIds) {
            val now = System.currentTimeMillis()

            seenMessageIds.entries.removeIf { now - it.value > 5 * 60 * 1000L }
            if (seenMessageIds.put(event.id, now) != null) return
        }

        _incomingEvents.tryEmit(inbound)
    }

    /**
     * 从 READY 事件捕获机器人自身 OpenID 并落盘。
     *
     * 断线重连走 RESUME 时不会再收到 READY，因此必须持久化，否则重连后
     * 全量群消息将因无法判定是否被 @ 而被全部丢弃。
     */
    private suspend fun captureBotOpenId(payload: QQGatewayPayload) {
        val element = payload.d ?: return
        runCatching {
            val ready = json.decodeFromJsonElement<QQReadyData>(element)
            val openId = ready.user?.id
            if (!openId.isNullOrBlank() && openId != botOpenId) {
                botOpenId = openId
                tokenStore.setBotOpenId(openId)
                android.util.Log.i(TAG, "captured bot openid")
                QQBotDebugLog.log("[Repo] captured bot openid=" + openId)
            }
        }.onFailure {
            android.util.Log.w(TAG, "failed to parse READY payload", it)
        }
    }

    private suspend fun resolvedBotOpenId(): String? {
        botOpenId?.let { return it }
        val saved = tokenStore.getBotOpenId()
        if (!saved.isNullOrBlank()) botOpenId = saved
        return botOpenId
    }

    fun extractText(event: QQInboundEvent): String {
        var text = event.raw.content.orEmpty()

        if (event is QQInboundEvent.GroupAtMessage) {
            text = text.replace(Regex("^<@!\\d+>\\s*"), "")
            text = text.replace(Regex("^@\\S+\\s*"), "")
        }
        return text.trim()
    }

    suspend fun extractImageUrl(event: QQInboundEvent): String? {
        return event.raw.attachments?.firstOrNull { it.contentType?.startsWith("image/") == true }?.url
    }

    fun getReplyKey(event: QQInboundEvent): String {
        return when (event) {
            is QQInboundEvent.C2CMessage -> "c2c:${event.userOpenid}"
            is QQInboundEvent.GroupAtMessage -> "group:${event.groupOpenid}:${event.memberOpenid}"
            is QQInboundEvent.GuildMessage -> "guild:${event.channelId}:${event.authorId}"
            is QQInboundEvent.DirectMessage -> "dm:${event.guildId}:${event.authorId}"
        }
    }

    fun getActiveReplyJob(key: String): kotlinx.coroutines.Job? = activeReplyJobs[key]

    fun activeReplyJobKeys(): List<String> = activeReplyJobs.keys.toList()

    fun setActiveReplyJob(key: String, job: kotlinx.coroutines.Job) {
        activeReplyJobs[key] = job
    }

    fun removeActiveReplyJob(key: String) {
        activeReplyJobs.remove(key)
    }

    companion object {
        private const val TAG = "QQBotMsgRepo"
    }
}
