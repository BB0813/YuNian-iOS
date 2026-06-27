package com.lianyu.ai.feature.qqbot.data

import android.content.Context
import com.lianyu.ai.common.DeviceIdProvider
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.MessageType
import com.lianyu.ai.database.repository.ChatRepository
import com.lianyu.ai.database.repository.CompanionRepository
import com.lianyu.ai.database.repository.MemoryRepository
import com.lianyu.ai.database.repository.filterDecrypted
import com.lianyu.ai.domain.AiChatMessage
import com.lianyu.ai.domain.AiCompanionInfo
import com.lianyu.ai.domain.AiMessageType
import com.lianyu.ai.domain.AiResponse
import com.lianyu.ai.domain.AiServiceProvider
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.feature.qqbot.data.model.QQInboundEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QQBotChatBridge(
    private val context: Context,
    private val qqBotRepository: QQBotMessageRepository,
    private val tokenStore: QQBotTokenStore
) {
    private val deviceId = DeviceIdProvider.getDeviceId(context)
    private val chatRepository = ServiceRegistry.getOrThrow(ChatRepository::class.java)
    private val companionRepository = ServiceRegistry.getOrThrow(CompanionRepository::class.java)
    private val memoryRepository = ServiceRegistry.getOrThrow(MemoryRepository::class.java)
    private val mappingManager = QQBotUserMappingManager(tokenStore, companionRepository)
    private val aiServiceProvider: AiServiceProvider by lazy {
        ServiceRegistry.getOrThrow(AiServiceProvider::class.java)
    }
    private val bridgeJob = SupervisorJob()
    private val bridgeScope = CoroutineScope(bridgeJob + Dispatchers.IO)

    private var eventCollectionJob: kotlinx.coroutines.Job? = null

    /**
     * 在 Service 中启动持续的事件监听与自动回复。
     * 与 UI 生命周期解耦，保证切到后台后仍能自动回复。
     */
    fun start() {
        if (eventCollectionJob?.isActive == true) {
            android.util.Log.d("QQBotBridge", "Already started")
            return
        }
        android.util.Log.i("QQBotBridge", "Starting event collection")
        eventCollectionJob = bridgeScope.launch {
            qqBotRepository.incomingEvents.collect { event ->
                val autoReply = tokenStore.getAutoReply()
                val text = qqBotRepository.extractText(event)
                android.util.Log.d("QQBotBridge", "Event received, autoReply=$autoReply, text=$text")
                if (!autoReply) return@collect
                val key = qqBotRepository.getReplyKey(event)
                // 连发合并：如果同 key 已有活跃回复 job，取消旧 job，
                // 短暂延迟聚合后续消息（2 秒窗口），用最新事件发起回复。
                val existingJob = qqBotRepository.getActiveReplyJob(key)
                if (existingJob?.isActive == true) {
                    android.util.Log.d("QQBotBridge", "Cancelling active job for $key to merge new message")
                    existingJob.cancel()
                    qqBotRepository.removeActiveReplyJob(key)
                }
                // 等待 2 秒聚合窗口，收到连发消息时会取消当前 job 重新合并
                val currentEventText = text
                val job = bridgeScope.launch {
                    try {
                        delay(2000L) // 连发聚合窗口
                        handleIncomingEventStreaming(event)
                    } finally {
                        qqBotRepository.removeActiveReplyJob(key)
                    }
                }
                qqBotRepository.setActiveReplyJob(key, job)
            }
        }
    }

    fun stop() {
        eventCollectionJob?.cancel()
        eventCollectionJob = null
    }

    /**
     * 非流式处理接入事件：使用 [AiServiceProvider.sendMessage] 全文发送，全文接收。
     * 与 App 内聊天一致：不分块、不 streaming、不 SSE 渐进渲染。
     * AI 回复作为单条消息保存并发送到 QQ。
     */
    suspend fun handleIncomingEventStreaming(event: QQInboundEvent) = withContext(Dispatchers.IO) {
        try {
            val qqUserId = when (event) {
                is QQInboundEvent.C2CMessage -> event.userOpenid
                is QQInboundEvent.GroupAtMessage -> "${event.groupOpenid}:${event.memberOpenid}"
                is QQInboundEvent.GuildMessage -> "${event.channelId}:${event.authorId}"
                is QQInboundEvent.DirectMessage -> "${event.guildId}:${event.authorId}"
            }
            android.util.Log.d("QQBotBridge", "Handling event, qqUserId=$qqUserId")
            if (qqUserId.isBlank()) return@withContext

            val text = qqBotRepository.extractText(event)
            android.util.Log.d("QQBotBridge", "Extracted text: $text")
            if (text.isBlank()) return@withContext

            if (com.lianyu.ai.common.BanManager.isBanned(context)) {
                android.util.Log.w("QQBotBridge", "Banned, skip")
                return@withContext
            }

            val companionId = mappingManager.getOrCreateMapping(qqUserId) ?: run {
                android.util.Log.w("QQBotBridge", "No companion mapping for $qqUserId")
                return@withContext
            }
            android.util.Log.d("QQBotBridge", "Mapped to companionId=$companionId")
            val companion = companionRepository.getCompanionById(companionId) ?: run {
                android.util.Log.w("QQBotBridge", "Companion not found: $companionId")
                return@withContext
            }

            val filterResult = com.lianyu.ai.common.ContentFilter.checkInput(text)
            if (filterResult.isViolating) {
                android.util.Log.w("QQBotBridge", "Input blocked: ${filterResult.reason}")
                com.lianyu.ai.common.BanManager.recordViolation(context, filterResult.level)
                val blockedResponse = "抱歉，我无法处理这个话题。"
                sendReply(event, blockedResponse)
                persistBlockedMessage(companionId, blockedResponse)
                return@withContext
            }

            val userMessage = ChatMessage(
                companionId = companionId,
                content = text,
                isFromUser = true,
                timestamp = System.currentTimeMillis()
            )
            chatRepository.sendMessage(userMessage)
            companionRepository.updateTimestamp(companionId)

            val history = chatRepository.getRecentMessagesSync(companionId, limit = 30).filterDecrypted()
            android.util.Log.d("QQBotBridge", "Calling non-streaming AI with ${history.size} history messages")

            // ── 非流式：全文发送，全文接收 ──
            val aiResponse = try {
                aiServiceProvider.sendMessage(companion.toAiCompanionInfo(), history.toAiChatMessages(), 0)
            } catch (e: Exception) {
                android.util.Log.e("QQBotBridge", "sendMessage failed", e)
                AiResponse(content = "抱歉，我暂时无法处理这条消息。")
            }
            val responseText = com.lianyu.ai.common.AiResponseCleaner.stripResponsePrefix(aiResponse.content)

            if (responseText.isBlank()) {
                val fallback = "抱歉，我暂时无法处理这条消息。"
                sendReply(event, fallback)
                persistBlockedMessage(companionId, fallback)
                return@withContext
            }

            // 输出安全检查
            val outputSafety = com.lianyu.ai.common.ContentFilter.checkOutputSafety(responseText)
            val safeText = if (!outputSafety.isSafe) {
                android.util.Log.w("QQBotBridge", "Output blocked: ${outputSafety.reason}")
                com.lianyu.ai.common.BanManager.recordViolation(context, outputSafety.level)
                val blockedResponse = "抱歉，我无法回应这个话题。"
                sendReply(event, blockedResponse)
                persistBlockedMessage(companionId, blockedResponse)
                return@withContext
            } else {
                responseText
            }

            // 转发到 QQ
            if (tokenStore.getForwardEnabled()) {
                sendReply(event, safeText)
            }

            // 入库完整 AI 回复
            val aiMessage = ChatMessage(
                companionId = companionId,
                content = safeText,
                isFromUser = false,
                timestamp = System.currentTimeMillis()
            )
            chatRepository.sendMessage(aiMessage)

            // 增加亲密度 + 异步提取记忆
            companionRepository.increaseIntimacy(companionId, 2)
            bridgeScope.launch {
                runCatching {
                    memoryRepository.extractAndSaveMemories(companionId, text, responseText)
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("QQBotBridge", "Error handling incoming event", e)
        }
    }

    private suspend fun sendReply(event: QQInboundEvent, text: String) {
        val result = qqBotRepository.sendTextMessage(event, text)
        result.onFailure { e ->
            android.util.Log.e("QQBotBridge", "Failed to send QQ reply: ${e.message}", e)
        }
    }

    private suspend fun persistBlockedMessage(companionId: Long, text: String) {
        chatRepository.sendMessage(
            ChatMessage(
                companionId = companionId,
                content = text,
                isFromUser = false,
                timestamp = System.currentTimeMillis()
            )
        )
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

    private fun com.lianyu.ai.database.model.CompanionEntity.toAiCompanionInfo() = AiCompanionInfo(
        id = id, name = name, personality = personality,
        age = age, backstory = backstory, speakingStyle = speakingStyle,
        systemPrompt = systemPrompt
    )

    private fun com.lianyu.ai.database.model.ChatMessage.toAiChatMessage() = AiChatMessage(
        isFromUser = isFromUser, content = content, timestamp = timestamp,
        type = when (type) {
            MessageType.IMAGE -> AiMessageType.IMAGE
            else -> AiMessageType.TEXT
        },
        companionId = companionId
    )

    private fun List<com.lianyu.ai.database.model.ChatMessage>.toAiChatMessages() = map { it.toAiChatMessage() }
}
