package com.lianyu.ai.feature.chat.ui.viewmodel

import android.app.Application
import com.lianyu.ai.common.ChatConstants
import com.lianyu.ai.common.ContentFilter
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.StickerInfo
import com.lianyu.ai.common.StickerManager
import com.lianyu.ai.common.TimeoutBudgets
import com.lianyu.ai.common.safety.SafetyScore
import com.lianyu.ai.common.safety.ScoreSource
import com.lianyu.ai.common.text.MessageSegmenter
import com.lianyu.ai.domain.wechat.WeChatProactiveSync
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.repository.ChatRepository
import com.lianyu.ai.database.repository.MessageWriteCoordinator
import com.lianyu.ai.domain.AiServiceProvider
import com.lianyu.ai.domain.MemoryProvider
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.domain.timeline.ConversationRef
import com.lianyu.ai.domain.timeline.TimelineStore
import com.lianyu.ai.feature.chat.data.ChatContextResolver
import com.lianyu.ai.feature.chat.data.ChatDetailSettingsStore
import com.lianyu.ai.feature.chat.timeline.EventCommitRules
import com.lianyu.ai.feature.chat.timeline.PendingTurn
import com.lianyu.ai.feature.chat.timeline.StreamingReasoningMessagePipeline
import com.lianyu.ai.feature.chat.timeline.TurnCommitCoordinator
import com.lianyu.ai.feature.chat.voice.ChatTtsController
import com.lianyu.ai.common.AppSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * AI 回复落地处理器（从 ChatViewModel 抽取，方案B：独立类 + 委托存根）。
 *
 * 职责链：
 * 1. PendingTurn 思考过程投影 + 终态落库（TimelineStore）
 * 2. 表情包标签处理
 * 3. L1+L2 关键词/向量安全检查 → 贝叶斯模型输出校验（fail-closed）
 * 4. 分段发送（模拟真人连续发消息，附 turn 元数据）
 * 5. 微信广播
 * 6. 记忆提取
 * 7. 连续追问（概率触发）
 * 8. 语音条入库（VOICE_BAR：合成后写入同条消息 linkString，架构去重）
 *
 * @param companionId 当前伴侣 ID
 * @param chatRepository 消息持久化
 * @param memoryProvider 统一记忆读写入口
 * @param stickerManager 表情包管理
 * @param chatDetailSettingsStore 聊天设置（表情包概率、追问开关等）
 * @param appSettingsStore 应用设置（reasoning 展示开关）
 * @param contextResolver 上下文解析（追问用）
 * @param aiService AI 服务（追问调用 generateFollowUpQuestion）
 * @param applicationApiScope 应用级作用域（追问异步发起）
 * @param turnState 单轮状态（表情包互斥、stale sticker）
 * @param chatTtsController TTS 控制器（语音条合成）
 * @param application Application（用于微信广播）
 * @param questionRegex 问句正则（追问触发判断）
 * @param turnCommit 回合提交协调器；null 时从 ServiceRegistry 懒取
 */
class AiResponseFinalizer(
    private val companionId: Long,
    private val chatRepository: ChatRepository,
    private val messageWriter: MessageWriteCoordinator,
    private val memoryProvider: MemoryProvider,
    private val stickerManager: StickerManager,
    private val chatDetailSettingsStore: ChatDetailSettingsStore,
    private val appSettingsStore: AppSettingsStore,
    private val contextResolver: ChatContextResolver,
    private val aiService: AiServiceProvider,
    private val applicationApiScope: CoroutineScope,
    private val turnState: ChatTurnState,
    private val chatTtsController: ChatTtsController,
    private val application: Application,
    private val questionRegex: Regex,
    private val turnCommit: TurnCommitCoordinator? = null,
) {
    private val commitCoordinator: TurnCommitCoordinator by lazy {
        turnCommit ?: TurnCommitCoordinator(
            timelineStore = ServiceRegistry.getOrThrow(TimelineStore::class.java),
            messageWriter = messageWriter,
        )
    }
    /**
     * 消息落地结果：UI 可见消息与后处理所需上下文分离。
     * loading/typing 只覆盖「网络等待 + 分段投递」，不覆盖记忆/追问/TTS。
     */
    data class DeliveredResponse(
        val messageId: Long,
        val aiContent: String,
        val segments: List<String>,
        val userContentForMemory: String?,
        val allowFollowUpMessage: Boolean
    )

    /**
     * 兼容入口：先落地消息，再做后处理。
     * 新调用方应拆成 [deliverResponse] + [afterDeliver]，以便在消息可见后立刻结束 typing。
     */
    suspend fun finalizeResponse(
        aiContent: String,
        reasoning: String?,
        userContentForMemory: String? = null,
        logMessage: String = "AI response received"
    ): Long {
        val delivered = deliverResponse(
            aiContent = aiContent,
            reasoning = reasoning,
            userContentForMemory = userContentForMemory,
            logMessage = logMessage
        )
        afterDeliver(delivered)
        return delivered.messageId
    }

    /**
     * 仅负责：PendingTurn 思考投影/落库、表情包、分段入库、微信广播。
     * 返回后消息已对用户可见，调用方应立即结束 loading/typing。
     *
     * @param pendingTurn 本轮内存态；null 时自动创建（兼容旧调用方）
     * @param reasoningStartedAtMs 思考开始时间；用于 durationMs
     */
    suspend fun deliverResponse(
        aiContent: String,
        reasoning: String?,
        userContentForMemory: String? = null,
        logMessage: String = "AI response received",
        pendingTurn: PendingTurn? = null,
        reasoningStartedAtMs: Long? = null,
    ): DeliveredResponse {
        val showReasoning = appSettingsStore.getShowReasoning()
        val turn = pendingTurn ?: PendingTurn.start(
            conversation = ConversationRef(conversationId = companionId, conversationType = "chat"),
            startedAtMs = reasoningStartedAtMs ?: System.currentTimeMillis(),
        )
        // Slice 5：流适配器可能已 completeReasoning；未完成时再写入缓冲
        if (EventCommitRules.shouldPersistReasoning(reasoning) && !turn.isReasoningComplete) {
            turn.replaceReasoningText(reasoning!!.trim())
        }

        // 非流式/兼容入口：终态前先把思考投影到消息链路（L1），与 SSE 路径一致
        if (EventCommitRules.shouldProjectReasoningLive(showReasoning, reasoning)) {
            StreamingReasoningMessagePipeline.upsertStreaming(
                companionId = companionId,
                turnId = turn.turnId,
                text = reasoning!!.trim(),
                timestamp = reasoningStartedAtMs ?: turn.startedAtMs,
                eventIndex = turn.streamingReasoningEvent()?.eventIndex,
                anchorMessageId = turn.anchorMessageId,
            )
        } else if (!showReasoning) {
            StreamingReasoningMessagePipeline.removeStreaming(companionId, turn.turnId)
        }

        // 终态 REASONING 先落库（eventIndex 先于正文；complete 幂等，commit 仅一次）
        if (EventCommitRules.shouldPersistReasoning(reasoning) || turn.isReasoningComplete) {
            val completedAt = System.currentTimeMillis()
            val duration = EventCommitRules.durationMs(
                startedAtMs = reasoningStartedAtMs ?: turn.startedAtMs,
                completedAtMs = completedAt,
            )
            turn.completeReasoning(
                finalText = reasoning?.trim()?.takeIf { it.isNotEmpty() },
                durationMs = duration,
                timestamp = completedAt,
            )
            val reasoningEvent = turn.takeReasoningEventForCommit()
            if (reasoningEvent != null) {
                runCatching {
                    commitCoordinator.commitReasoning(turn.conversation, reasoningEvent)
                }.onFailure {
                    SecureLog.e("ChatViewModel", "REASONING commit failed: ${it.message}")
                }
            }
            // 真实 REASONING 行已写入 MessageCache；移除同 turn 流式临时行，避免双渲染
            StreamingReasoningMessagePipeline.removeStreaming(companionId, turn.turnId)
        }

        val settings = chatDetailSettingsStore.getSettings(companionId)
        // 双端兜底：用本轮用户原文再裁一次闲聊护理包（防历史 lastUser 漂移/改写漏检）
        val deliverySafeText = if (!userContentForMemory.isNullOrBlank()) {
            com.lianyu.ai.network.ResponsePostProcessor.trimIdleEmotionOverDelivery(
                aiContent,
                userContentForMemory,
            )
        } else {
            aiContent
        }
        val processedText = TextProcessor.processStickerTagsForSplit(
            deliverySafeText,
            stickerManager,
            settings.stickerProbability,
        ) { sendStickerMessage(it) }

        // 分段发送：将AI回复拆分为多条短消息，模拟真人连续发送
        val segments = splitIntoSegments(processedText)
        val hasPendingSticker = turnState.pendingSticker != null
        val stickerBeforeText = hasPendingSticker && kotlin.random.Random.nextFloat() < 0.5f

        // [P0 FIX] 空内容保护和日志记录
        if (processedText.isBlank() && aiContent.isNotBlank()) {
            SecureLog.w("ChatViewModel", "WARNING: processedText is blank but aiContent has ${aiContent.length} chars. Original: '${aiContent.take(80)}'")
        }

        val aiMessageId = if (segments.size <= 1) {
            // 单条回复，走原有逻辑
            val safeProcessed = processedText.ifBlank {
                if (aiContent.isNotBlank()) {
                    SecureLog.w("ChatViewModel", "Falling back to zero-width space. aiContent length=${aiContent.length}")
                    "\u200B"
                } else {
                    SecureLog.w("ChatViewModel", "Both processedText and aiContent are blank, storing empty message")
                    ""
                }
            }
            if (stickerBeforeText) {
                flushPendingSticker()
            }
            val voiceBar = synthesizeVoiceBarOrNull(safeProcessed)
            val id = commitCoordinator.commitAssistantText(
                companionId = companionId,
                text = safeProcessed,
                turn = turn,
                audioPath = voiceBar?.path,
                durationMs = voiceBar?.durationMs,
            )
            SecureLog.d("ChatViewModel", "$logMessage, length=${aiContent.length}, id=$id, voiceBar=${voiceBar != null}")
            if (!stickerBeforeText && turnState.pendingSticker != null) {
                flushPendingSticker()
            }
            delay(100)
            broadcastAiMessage(id, safeProcessed)
            id
        } else {
            // 多条回复：每段间隔0.8~2秒，模拟打字
            if (stickerBeforeText) {
                flushPendingSticker()
            }
            var lastId = -1L
            for ((index, segment) in segments.withIndex()) {
                if (index > 0) {
                    delay(800L + kotlin.random.Random.nextLong(1200L))
                }
                val safeSegment = segment.ifBlank { "\u200B" }
                val voiceBar = synthesizeVoiceBarOrNull(safeSegment)
                val id = commitCoordinator.commitAssistantText(
                    companionId = companionId,
                    text = safeSegment,
                    turn = turn,
                    audioPath = voiceBar?.path,
                    durationMs = voiceBar?.durationMs,
                )
                lastId = id
                SecureLog.d(
                    "ChatViewModel",
                    "$logMessage segment ${index + 1}/${segments.size}, length=${segment.length}, id=$id, voiceBar=${voiceBar != null}"
                )
            }
            if (!stickerBeforeText && turnState.pendingSticker != null) {
                flushPendingSticker()
            }
            delay(100)
            // 微信侧一次同步完整回复，保留 App 已展示的分段顺序。
            if (lastId > 0) {
                broadcastAiMessage(lastId, segments.joinToString("\n"))
            }
            lastId
        }

        // 兜底：关闭展示或无思考时清掉流式临时行；正常路径已在 commit 后 remove
        if (!showReasoning || reasoning.isNullOrBlank()) {
            StreamingReasoningMessagePipeline.removeStreaming(companionId, turn.turnId)
        }
        // autoCollapse 仅影响列表项展开态，不再维护 ephemeral StateFlow

        // Broadcast stale sticker message if any
        if (turnState.lastStickerMsgId > 0) {
            broadcastWeChatMessage(turnState.lastStickerMsgId, turnState.lastStickerContent)
            turnState.lastStickerMsgId = -1
            turnState.lastStickerContent = ""
        }

        turn.releaseIndexer()

        return DeliveredResponse(
            messageId = aiMessageId,
            aiContent = aiContent,
            segments = segments,
            userContentForMemory = userContentForMemory,
            allowFollowUpMessage = settings.allowFollowUpMessage
        )
    }

    /**
     * 消息可见后的后处理：记忆提取、概率追问。
     * 语音条已在 [deliverResponse] 入库时合成，此处不再自动播放。
     * 不得再驱动顶部「对方正在输入」状态。
     */
    suspend fun afterDeliver(delivered: DeliveredResponse) {
        // Save memory
        if (delivered.userContentForMemory != null && delivered.aiContent.isNotBlank()) {
            runCatching {
                withTimeoutOrNull(TimeoutBudgets.CHAT_VM_MEMORY_EXTRACT_MS) {
                    memoryProvider.extractAndSaveFromConversation(
                        userInput = delivered.userContentForMemory,
                        aiResponse = delivered.aiContent,
                        companionId = companionId,
                        groupId = null
                    )
                }
            }.onFailure {
                SecureLog.e("ChatViewModel", "Memory save failed: ${it.message}")
            }
        }

        // 连续追问：AI回复后按概率触发追问
        triggerFollowUpIfNeeded(delivered.aiContent, delivered.allowFollowUpMessage)
    }

    /**
     * VOICE_BAR 模式下为单段正文合成持久音频；失败则退回纯文字消息。
     * 合成结果随 commit 一次写入，重进聊天不会二次合成。
     */
    private suspend fun synthesizeVoiceBarOrNull(text: String): com.lianyu.ai.feature.chat.voice.VoiceBarAudio? {
        if (text.isBlank() || text == "\u200B") return null
        return runCatching { chatTtsController.synthesizeOnly(text) }
            .onFailure { SecureLog.w("ChatViewModel", "Voice bar synth failed: ${it.message}") }
            .getOrNull()
    }

    // ── 辅助方法（从 ChatViewModel 迁移）──

    private fun broadcastAiMessage(messageId: Long, finalContent: String) {
        if (finalContent.isNotBlank() && finalContent != "\u200B") {
            broadcastWeChatMessage(messageId, finalContent)
        }
    }

    private fun broadcastWeChatMessage(messageId: Long, finalContent: String? = null) {
        WeChatProactiveSync.enqueue(companionId, messageId, finalContent)
        SecureLog.d("ChatViewModel", "Enqueue WeChat proactive message, companionId=$companionId, messageId=$messageId, hasFinalContent=${!finalContent.isNullOrBlank()}")
    }

    /**
     * 连续追问：AI回复后按概率触发追问，让对话继续下去。
     * 条件：1) 设置允许追问 2) AI回复不含问句 3) 50%概率触发
     */
    private fun triggerFollowUpIfNeeded(aiContent: String, allowFollowUp: Boolean) {
        if (!allowFollowUp) return
        if (questionRegex.containsMatchIn(aiContent)) return
        if (kotlin.random.Random.nextFloat() > 0.5f) return

        applicationApiScope.launch {
            try {
                delay(ChatConstants.FOLLOW_UP_BASE_DELAY_MS + kotlin.random.Random.nextLong(ChatConstants.FOLLOW_UP_RANDOM_DELAY_MS))

                val history = contextResolver.getShortHistoryForAi(companionId, shortLimit = ChatConstants.SHORT_HISTORY_LIMIT)
                // 取 companion 数据用于 AI 调用——通过回调从 ChatViewModel 获取
                val companionInfo = companionInfoProvider?.invoke() ?: return@launch
                val followUp = aiService.generateFollowUpQuestion(
                    companionInfo, history.map { msg ->
                        com.lianyu.ai.domain.AiChatMessage(
                            isFromUser = msg.isFromUser,
                            content = msg.content,
                            timestamp = msg.timestamp,
                            type = if (msg.type == com.lianyu.ai.database.model.MessageType.IMAGE)
                                com.lianyu.ai.domain.AiMessageType.IMAGE
                            else com.lianyu.ai.domain.AiMessageType.TEXT,
                            companionId = companionId
                        )
                    }, aiContent
                ) ?: return@launch

                // 追问消息安全检查
                val followUpSafety = ContentFilter.checkOutputSafety(followUp)
                if (!followUpSafety.isSafe) {
                    SecureLog.w("ChatViewModel", "Follow-up safety violation: ${followUpSafety.reason}")
                    return@launch
                }

                val followUpMsg = ChatMessage(
                    companionId = companionId,
                    content = followUp,
                    isFromUser = false,
                    timestamp = System.currentTimeMillis()
                )
                val msgId = messageWriter.enqueueChat(followUpMsg)
                broadcastWeChatMessage(msgId, followUp)
                SecureLog.d("ChatViewModel", "Follow-up question sent: $followUp")
            } catch (e: Exception) {
                SecureLog.w("ChatViewModel", "Follow-up question failed: ${e.message}")
            }
        }
    }

    private fun splitIntoSegments(text: String): List<String> {
        return MessageSegmenter.split(text, MessageSegmenter.SplitMode.SIMPLE)
    }

    /**
     * Queue a sticker for this turn instead of sending it immediately.
     */
    private suspend fun sendStickerMessage(sticker: StickerInfo): Long {
        return turnState.stickerMutex.withLock {
            if (turnState.stickerSentThisTurn) return@withLock -1
            turnState.stickerSentThisTurn = true
            turnState.pendingSticker = sticker
            -1
        }
    }

    /**
     * Persist the pending sticker message and record its broadcast metadata.
     */
    private suspend fun flushPendingSticker(): Long {
        val sticker = turnState.pendingSticker ?: return -1
        turnState.pendingSticker = null
        val stickerId = sticker.description
            ?: sticker.fileName?.removePrefix("sticker_")?.removeSuffix(".png")?.takeIf { it.isNotBlank() }
            ?: sticker.name
        val stickerContent = "[$stickerId]"
        val stickerMessage = ChatMessage(
            companionId = companionId,
            content = stickerContent,
            isFromUser = false,
            timestamp = System.currentTimeMillis()
        )
        val msgId = messageWriter.enqueueChat(stickerMessage)
        if (msgId > 0) {
            turnState.lastStickerMsgId = msgId
            turnState.lastStickerContent = stickerContent
        }
        return msgId
    }

    // companionInfoProvider 由 ChatViewModel 注入，用于 triggerFollowUp 获取当前 companion 数据
    var companionInfoProvider: (() -> com.lianyu.ai.domain.AiCompanionInfo?)? = null
}

