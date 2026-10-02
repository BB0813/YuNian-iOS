package com.yunian.ai.feature.qqbot.data

import android.content.Context
import com.yunian.ai.common.concurrent.AppDispatchers
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.feature.qqbot.QQBotDebugLog
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.domain.ChannelKeys
import com.yunian.ai.domain.DialogueCoordinator
import com.yunian.ai.domain.DialogueRequest
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.imagegen.ImageGenService
import com.yunian.ai.feature.qqbot.data.model.QQInboundEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val MERGE_WINDOW_MS = 2000L
private const val MISSING_COMPANION_HINT_COOLDOWN_MS = 10 * 60 * 1000L

class QQBotChatBridge(
    private val context: Context,
    private val qqBotRepository: QQBotMessageRepository,
    private val tokenStore: QQBotTokenStore
) {
    private val database = AppDatabase.getDatabase(context)
    private val companionRepository = CompanionRepository(database.companionDao())
    private val mappingManager = QQBotUserMappingManager(tokenStore, companionRepository)

    /** 统一 AI 对话中间层（core:agent 实现）：AI 回合 / 安全 / 落库 / 记忆全部内聚。 */
    private val dialogueCoordinator: DialogueCoordinator by lazy {
        ServiceRegistry.get(DialogueCoordinator::class.java)
            ?: throw IllegalStateException("DialogueCoordinator not registered in ServiceRegistry")
    }

    private val bridgeJob = SupervisorJob()
    private val bridgeScope = CoroutineScope(bridgeJob + AppDispatchers.io)

    private var eventCollectionJob: kotlinx.coroutines.Job? = null
    private val activeReplyJobs = Any()
    private val pendingLock = Any()
    private val pendingByKey = ConcurrentHashMap<String, MutableList<QQInboundEvent>>()
    private val missingCompanionHintAtMs = ConcurrentHashMap<String, Long>()

    fun start() {
        if (eventCollectionJob?.isActive == true) {
            android.util.Log.d("QQBotBridge", "Already started")
            QQBotDebugLog.log("[Bridge] start ignored: already running")
            return
        }
        android.util.Log.i("QQBotBridge", "Starting event collection")
        // 生命周期留痕：没有这行就无法区分「桥接没启动」与「启动了但没收到事件」。
        QQBotDebugLog.log("[Bridge] start: collecting inbound events")
        eventCollectionJob = bridgeScope.launch {
            qqBotRepository.incomingEvents.collect { event ->
                // 宿主 openid 补齐（**不依赖 autoReply**）：改动前就已绑定的用户，
                // 绑定流程那次 `user_openid` 已经丢了，只能从入站 C2C 消息里补回来。
                // 这是主动发送「默认发给用户本人」能成立的前提。
                // 幂等且不阻塞：值未变化时直接返回（见 rememberHostUserOpenId）。
                when (event) {
                    is QQInboundEvent.C2CMessage -> {
                        runCatching { qqBotRepository.rememberHostUserOpenId(event.userOpenid) }
                            .onFailure {
                                QQBotDebugLog.log("[Route] capture host target failed: " + it.message)
                            }
                    }
                    is QQInboundEvent.GroupAtMessage -> {
                        // Gateway 已验证的入站事件是 group_openid 的可信来源。捕获动作不依赖
                        // autoReply：即使关闭自动回复，App 内的主动工具仍能用 target=group
                        // 找到最近群聊；模型不再需要知道平台内部的不透明路由 ID。
                        runCatching { qqBotRepository.rememberRecentGroupOpenId(event.groupOpenid) }
                            .onFailure {
                                QQBotDebugLog.log("[Route] capture recent group failed: " + it.message)
                            }
                    }
                    else -> Unit
                }
                val autoReply = tokenStore.getAutoReply()
                val text = qqBotRepository.extractText(event)
                android.util.Log.d("QQBotBridge", "Event received, autoReply=$autoReply, text=$text")
                // 每个入站事件都留痕：这是判断「群消息到底有没有进来」的唯一可靠依据。
                QQBotDebugLog.log(
                    "[Bridge] event received type=" + event.javaClass.simpleName +
                        " autoReply=" + autoReply + " text_len=" + text.length +
                        " key=" + qqBotRepository.getReplyKey(event)
                )
                if (!autoReply) {
                    QQBotDebugLog.log("[Bridge] auto-reply disabled, event dropped")
                    return@collect
                }
                val key = qqBotRepository.getReplyKey(event)

                synchronized(pendingLock) {
                    pendingByKey.getOrPut(key) { mutableListOf() }.add(event)
                }

                synchronized(activeReplyJobs) {
                    val existingJob = qqBotRepository.getActiveReplyJob(key)
                    if (existingJob?.isActive == true) {
                        android.util.Log.d("QQBotBridge", "Merge window open for $key, queued")
                        return@synchronized
                    }
                    val newJob = bridgeScope.launch { drainPending(key) }
                    qqBotRepository.setActiveReplyJob(key, newJob)
                }
            }
        }
    }

    fun stop() {
        eventCollectionJob?.cancel()
        eventCollectionJob = null
        synchronized(activeReplyJobs) {
            qqBotRepository.activeReplyJobKeys().forEach { key ->
                qqBotRepository.getActiveReplyJob(key)?.cancel()
                qqBotRepository.removeActiveReplyJob(key)
            }
        }
        synchronized(pendingLock) { pendingByKey.clear() }
    }

    private suspend fun drainPending(key: String) {
        try {
            while (true) {
                delay(MERGE_WINDOW_MS)
                val batch: List<QQInboundEvent> = synchronized(pendingLock) {
                    val list = pendingByKey[key] ?: mutableListOf()
                    val snapshot = list.toList()
                    list.clear()
                    snapshot
                }
                if (batch.isEmpty()) break
                if (batch.size == 1) {
                    handleIncomingEventStreaming(batch.last())
                } else {
                    val mergedText = batch.joinToString("\n") { qqBotRepository.extractText(it) }.trim()
                    if (mergedText.isNotBlank()) {
                        android.util.Log.d("QQBotBridge", "Merged ${batch.size} events for $key")
                        runReply(batch.last(), mergedText)
                    }
                }
            }
        } finally {
            synchronized(activeReplyJobs) {
                qqBotRepository.removeActiveReplyJob(key)
            }
            val hasMore = synchronized(pendingLock) { pendingByKey[key].orEmpty().isNotEmpty() }
            if (hasMore) {
                synchronized(activeReplyJobs) {
                    if (qqBotRepository.getActiveReplyJob(key)?.isActive != true) {
                        qqBotRepository.setActiveReplyJob(key, bridgeScope.launch { drainPending(key) })
                    }
                }
            }
        }
    }

    suspend fun handleIncomingEventStreaming(event: QQInboundEvent) = withContext(Dispatchers.IO) {
        val text = qqBotRepository.extractText(event)
        if (text.isNotBlank()) {
            runReply(event, text)
        } else {
            // 群消息会剥离 @ 前缀（见 QQBotMessageRepository.extractText），
            // 剥离后为空则整条事件被跳过。这是静默分支，必须留痕。
            QQBotDebugLog.log(
                "[Bridge] text blank after extract, event skipped key=" +
                    qqBotRepository.getReplyKey(event)
            )
        }
    }

    private suspend fun runReply(event: QQInboundEvent, text: String) = withContext(Dispatchers.IO) {
        try {
            val qqUserId = when (event) {
                is QQInboundEvent.C2CMessage -> event.userOpenid
                is QQInboundEvent.GroupAtMessage -> "${event.groupOpenid}:${event.memberOpenid}"
                is QQInboundEvent.GuildMessage -> "${event.channelId}:${event.authorId}"
                is QQInboundEvent.DirectMessage -> "${event.guildId}:${event.authorId}"
            }
            android.util.Log.d("QQBotBridge", "Handling streaming event, qqUserId=$qqUserId")
            if (qqUserId.isBlank()) return@withContext

            android.util.Log.d("QQBotBridge", "Extracted text: $text")

            val companionId = mappingManager.getOrCreateMapping(qqUserId) ?: run {
                android.util.Log.w("QQBotBridge", "No companion mapping for $qqUserId")
                notifyMissingCompanion(event)
                return@withContext
            }
            android.util.Log.d("QQBotBridge", "Mapped to companionId=$companionId")
            val companion = companionRepository.getCompanionById(companionId) ?: run {
                android.util.Log.w("QQBotBridge", "Companion not found: $companionId")
                return@withContext
            }
            android.util.Log.d("QQBotBridge", "Mapped companion=${companion.name}")

            // 纯净桥接：封禁判定 / 输入安全检查 / AI 回合 / 输出安全检查 / 生图清洗 /
            // 落库 / 记忆提取全部收敛在中间层（DialogueCoordinator，core:agent 实现）。
            val result = dialogueCoordinator.generateReply(
                DialogueRequest(
                    companionId = companionId,
                    text = text,
                    imagePath = null,
                    // 通道身份（**不参与授权判定**，已核实）：工具授权只按 (伴侣 × 工具) 折叠，
                    // 唯一折叠点是 AgentFacade.toolDefinitionsFor —— 它调
                    // CapabilityGrantStore.decisionsFor(companionId)，签名里根本没有通道参数。
                    // 同一条授权在 App 内单聊 / 群聊 / QQ / 微信上得到完全相同的结果，
                    // 通道之间的差异只剩「有没有确认界面」。
                    // 这里显式声明 QQBOT 是为了通道身份 / 观测（P3 通道插件化）：
                    // 拼错它不会改变任何工具的放行结果。
                    channelKey = ChannelKeys.QQBOT,
                )
            )
            android.util.Log.d(
                "QQBotBridge",
                "Dialogue done blocked=${result.blocked} reply_len=${result.replyText.length}",
            )
            QQBotDebugLog.log(
                "[Bridge] dialogue done blocked=" + result.blocked +
                    " reply_len=" + result.replyText.length +
                    " turn=" + (result.turn != null)
            )

            // 消费判定（P3-3c，**防双发**）：turn != null → **只**消费 events；
            // turn == null → 回退 replyText（与改动前逐字一致）。
            // 两者在 QQBotOutboundProjection.resolveSendText 的 ?: 上严格互斥，
            // 因此 replyText 在 turn 非空时不可能被发送（下面所有出站都只用 safeText）。
            val safeText = QQBotOutboundProjection.resolveSendText(result)
            android.util.Log.d(
                "QQBotBridge",
                "Outbound projection: fromTurn=${result.turn != null} send_len=${safeText.length}",
            )
            if (safeText.isBlank()) {
                // 中间层未产出可发送内容（被拦截或上游错误）：不回灌任何消息，
                // 拦截文案已由中间层落库并随首次 user 消息进入会话。
                return@withContext
            }

            // 以下「怎么发、什么时候发」逐字未变：分句 + 500ms 节流 + sendTextMessage。
            // 分句本身抽到 [QQBotSentenceSplitter]（纯函数，可被纯 JVM 单测驱动）；
            // 本次只修「连续句末标点会切出纯标点片段」这一个 bug，节流与发送策略一行未动。
            var lastSendTime = 0L
            var forwardSkipLogged = false
            val minGapMs = 500L
            val sentences = QQBotSentenceSplitter.split(safeText)
            for (sentence in sentences) {
                if (sentence.isBlank()) continue
                val elapsed = System.currentTimeMillis() - lastSendTime
                if (elapsed < minGapMs && lastSendTime > 0) {
                    delay(minGapMs - elapsed)
                }
                if (tokenStore.getForwardEnabled()) {
                    sendReply(event, sentence)
                } else if (!forwardSkipLogged) {
                    // 转发关闭 → 整段出站被丢弃。发送判定逐字未动，只补可观测性；
                    // 只记一次，避免逐句刷屏。
                    forwardSkipLogged = true
                    QQBotDebugLog.log(
                        "[Bridge] forward disabled, outbound skipped key=" +
                            qqBotRepository.getReplyKey(event)
                    )
                }
                lastSendTime = System.currentTimeMillis()
            }

            // 生图：判定逻辑与 App 内完全一致（复用 ImageGenService），失败绝不影响聊天主流程
            runCatching { generateAndSendImages(event, companionId, text, safeText) }
                .onFailure { e ->
                    android.util.Log.e("QQBotBridge", "image gen failed: ${e.message}", e)
                }

        } catch (e: Exception) {
            android.util.Log.e("QQBotBridge", "Error handling incoming event", e)
        }
    }

    /**
     * 桥接链路的生图：判定逻辑与 App 内完全一致（复用 [ImageGenService]），
     * 落库由服务完成，这里只负责把同一张图发到 QQ。
     *
     * QQ 富媒体依赖「上传 file_info → msg_type=7 发送」两步，频道场景与任何失败都会
     * 明确降级为一条文字说明并落 I 级日志，绝不静默失败。
     */
    private suspend fun generateAndSendImages(
        event: QQInboundEvent,
        companionId: Long,
        userText: String,
        aiText: String,
    ) {
        if (aiText.isBlank()) return
        val service = ServiceRegistry.get(ImageGenService::class.java) ?: run {
            android.util.Log.i("QQBotBridge", "image gen skipped: ImageGenService not registered")
            return
        }
        val images = service.generateForReply(
            companionId = companionId,
            userText = userText,
            aiText = aiText,
        )
        if (images.isEmpty()) {
            android.util.Log.i("QQBotBridge", "image gen not triggered companionId=$companionId")
            return
        }
        android.util.Log.i("QQBotBridge", "image gen done companionId=$companionId count=${images.size}")
        if (!tokenStore.getForwardEnabled()) {
            android.util.Log.i("QQBotBridge", "image not sent: forward disabled")
            return
        }
        images.forEach { image ->
            if (image.filePath.isBlank()) return@forEach
            val result = qqBotRepository.sendImageMessage(event, image.filePath)
            result.onFailure { e ->
                // 明确降级：告诉用户图片没发出去，并把原因写进 I 级日志
                android.util.Log.i(
                    "QQBotBridge",
                    "QQ image send failed, fallback to text: ${e.message}",
                )
                runCatching { sendReply(event, "（配图已生成，但发送失败了：${e.message ?: "未知原因"}）") }
            }.onSuccess {
                android.util.Log.i("QQBotBridge", "QQ image sent path=${image.filePath.take(60)}")
            }
        }
    }

    private suspend fun notifyMissingCompanion(event: QQInboundEvent) {
        val key = qqBotRepository.getReplyKey(event)
        val now = System.currentTimeMillis()
        val last = missingCompanionHintAtMs.putIfAbsent(key, now) ?: 0L
        if (last > 0L && now - last < MISSING_COMPANION_HINT_COOLDOWN_MS) {
            missingCompanionHintAtMs[key] = last
            return
        }
        missingCompanionHintAtMs[key] = now
        sendReply(event, "还没有可用的 AI 伴侣，请先在予念里创建一个伴侣，再回来和我聊天。")
    }

    private suspend fun sendReply(event: QQInboundEvent, text: String) {
        QQBotDebugLog.log(
            "[Bridge] send reply type=" + event.javaClass.simpleName + " text_len=" + text.length
        )
        val result = qqBotRepository.sendTextMessage(event, text)
        result.onSuccess {
            QQBotDebugLog.log("[Bridge] send reply success type=" + event.javaClass.simpleName)
        }.onFailure { e ->
            android.util.Log.e("QQBotBridge", "Failed to send QQ reply: ${e.message}", e)
            QQBotDebugLog.log(
                "[Bridge] send reply failed type=" + event.javaClass.simpleName +
                    " reason=" + (e.message ?: e.javaClass.simpleName)
            )
        }
    }

    private fun cleanReplyText(text: String): String {
        return text.trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^[\\[\\]\\s，。！？、]+"), "")
            .replace(Regex("[\\[\\]\\s，。！？、]+$"), "")
            .trim()
    }

    fun close() {
        bridgeJob.cancel()
    }
}
