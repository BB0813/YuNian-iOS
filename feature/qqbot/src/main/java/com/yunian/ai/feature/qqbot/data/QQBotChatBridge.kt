package com.yunian.ai.feature.qqbot.data

import android.content.Context
import com.yunian.ai.common.AppSettingsStore
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.database.repository.ChatRepository
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.database.repository.MessageWriteCoordinator
import com.yunian.ai.database.repository.filterDecrypted
import com.yunian.ai.domain.AiChatMessage
import com.yunian.ai.domain.AiCompanionInfo
import com.yunian.ai.domain.AiMessageType
import com.yunian.ai.domain.AiResponse
import com.yunian.ai.domain.AiServiceProvider
import com.yunian.ai.domain.MemoryProvider
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.imagegen.ImageGenProtocol
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
    private val chatRepository = ServiceRegistry.getOrThrow(ChatRepository::class.java)
    private val messageWriter = ServiceRegistry.getOrThrow(MessageWriteCoordinator::class.java)
    private val companionRepository = CompanionRepository(database.companionDao())
    private val memoryProvider: MemoryProvider by lazy {
        ServiceRegistry.getOrThrow(MemoryProvider::class.java)
    }
    private val mappingManager = QQBotUserMappingManager(tokenStore, companionRepository)
    private val appSettingsStore = AppSettingsStore(context.applicationContext)
    private val aiServiceProvider: AiServiceProvider by lazy {
        ServiceRegistry.get(AiServiceProvider::class.java)
            ?: throw IllegalStateException("AiServiceProvider not registered in ServiceRegistry")
    }
    private val bridgeJob = SupervisorJob()
    private val bridgeScope = CoroutineScope(bridgeJob + Dispatchers.IO)

    private var eventCollectionJob: kotlinx.coroutines.Job? = null
    private val activeReplyJobs = Any()
    private val pendingLock = Any()
    private val pendingByKey = ConcurrentHashMap<String, MutableList<QQInboundEvent>>()
    private val missingCompanionHintAtMs = ConcurrentHashMap<String, Long>()

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

            if (com.yunian.ai.common.BanManager.isBanned(context)) {
                android.util.Log.w("QQBotBridge", "Banned, skip")
                return@withContext
            }

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

            val filterResult = com.yunian.ai.common.ContentFilter.checkInput(text)
            if (filterResult.isViolating) {
                android.util.Log.w("QQBotBridge", "Input blocked: ${filterResult.reason}")
                com.yunian.ai.common.BanManager.recordViolation(context, filterResult.level)
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
            messageWriter.enqueueChat(userMessage)
            companionRepository.updateTimestamp(companionId)

            val history = chatRepository.getRecentMessagesSync(companionId, limit = 30).filterDecrypted()
            android.util.Log.d("QQBotBridge", "Calling AI with ${history.size} history messages")

            // 生图协议与 App 内聊天完全一致：总开关关闭时 rules 为空串（零行为变化）。
            val aiCompanionInfo = companion.toAiCompanionInfo().withImageGenRules()
            val response = try {
                aiServiceProvider.sendMessage(aiCompanionInfo, history.toAiChatMessages(), 0)
            } catch (e: Exception) {
                android.util.Log.e("QQBotBridge", "sendMessage failed", e)
                null
            }

            if (response == null || response.content.isBlank()) {
                val fallback = "抱歉，我暂时无法处理这条消息。"
                sendReply(event, fallback)
                persistBlockedMessage(companionId, fallback)
                return@withContext
            }

            val outputSafety = com.yunian.ai.common.ContentFilter.checkOutputSafety(response.content)
            val rawSafeText = if (!outputSafety.isSafe) {
                android.util.Log.w("QQBotBridge", "AI output blocked: ${outputSafety.reason}")
                "抱歉，我无法回应这个话题。"
            } else {
                response.content
            }
            // 画面描述绝不能出现在 QQ 消息或聊天记录里：统一走 ImageGenProtocol 清洗。
            val safeText = ImageGenProtocol.sanitizeForDisplay(rawSafeText).let { stripped ->
                if (stripped.isNotBlank()) {
                    stripped
                } else if (ImageGenProtocol.isPromptOnly(rawSafeText)) {
                    IMAGE_GEN_ONLY_REPLY_TEXT
                } else {
                    rawSafeText
                }
            }

            var lastSendTime = 0L
            val minGapMs = 500L
            val sentences = splitIntoSentences(safeText)
            for (sentence in sentences) {
                if (sentence.isBlank()) continue
                val elapsed = System.currentTimeMillis() - lastSendTime
                if (elapsed < minGapMs && lastSendTime > 0) {
                    delay(minGapMs - elapsed)
                }
                if (tokenStore.getForwardEnabled()) {
                    sendReply(event, sentence)
                }
                lastSendTime = System.currentTimeMillis()
            }

            val aiMessage = ChatMessage(
                companionId = companionId,
                content = safeText,
                isFromUser = false,
                timestamp = System.currentTimeMillis()
            )
            messageWriter.enqueueChat(aiMessage)

            // 生图：判定逻辑与 App 内完全一致（复用 ImageGenService），失败绝不影响聊天主流程
            runCatching { generateAndSendImages(event, companionId, text, safeText) }
                .onFailure { e ->
                    android.util.Log.e("QQBotBridge", "image gen failed: ${e.message}", e)
                }

            companionRepository.increaseIntimacy(companionId, 2)
            bridgeScope.launch {
                runCatching {
                    memoryProvider.extractAndSaveFromConversation(
                        userInput = text,
                        aiResponse = safeText,
                        companionId = companionId,
                    )
                }
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

    /**
     * 把生图协议并入系统提示词（复用 App 内聊天的同一份文案）。
     * 总开关关闭时 [ImageGenProtocol.systemRules] 返回空串 → 零行为变化。
     */
    private suspend fun AiCompanionInfo.withImageGenRules(): AiCompanionInfo {
        val rules = runCatching {
            ImageGenProtocol.systemRules(
                enabled = appSettingsStore.getImageGenEnabled(),
                hasKeywordTrigger = appSettingsStore.getImageGenKeywords().isNotEmpty(),
            )
        }.getOrDefault("")
        if (rules.isBlank()) return this
        return copy(
            systemPrompt = listOfNotNull(
                systemPrompt?.trim()?.takeIf { it.isNotEmpty() },
                rules,
            ).joinToString("\n\n")
        )
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
        sendReply(event, "还没有可用的 AI 伴侣，请先在恋语里创建一个伴侣，再回来和我聊天。")
    }

    private suspend fun sendReply(event: QQInboundEvent, text: String) {
        val result = qqBotRepository.sendTextMessage(event, text)
        result.onFailure { e ->
            android.util.Log.e("QQBotBridge", "Failed to send QQ reply: ${e.message}", e)
        }
    }

    private suspend fun persistBlockedMessage(companionId: Long, text: String) {
        messageWriter.enqueueChat(
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

    private fun splitIntoSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val delimiters = charArrayOf('。', '！', '？', '!', '?', '\n')
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val idx = text.indexOfAny(delimiters, startIndex = start)
            if (idx < 0) {
                val remaining = text.substring(start).trim()
                if (remaining.isNotEmpty()) result.add(remaining)
                break
            }
            val end = idx + 1
            val sentence = text.substring(start, end).trim()
            if (sentence.isNotEmpty()) result.add(sentence)
            start = end
        }
        return result
    }

    fun close() {
        bridgeJob.cancel()
    }

    private fun com.yunian.ai.database.model.CompanionEntity.toAiCompanionInfo() = AiCompanionInfo(
        id = id, name = name, personality = personality,
        age = age, backstory = backstory, speakingStyle = speakingStyle,
        systemPrompt = systemPrompt
    )

    private fun com.yunian.ai.database.model.ChatMessage.toAiChatMessage() = AiChatMessage(
        isFromUser = isFromUser, content = content, timestamp = timestamp,
        type = when (type) {
            MessageType.IMAGE -> AiMessageType.IMAGE
            else -> AiMessageType.TEXT
        },
        companionId = companionId
    )

    private fun List<com.yunian.ai.database.model.ChatMessage>.toAiChatMessages() = map { it.toAiChatMessage() }

    private companion object {
        /** 模型整条回复只有画面描述时的占位文案（不含方括号，避免被当成表情包标签） */
        private const val IMAGE_GEN_ONLY_REPLY_TEXT = "（正在为你配图…）"
    }
}
