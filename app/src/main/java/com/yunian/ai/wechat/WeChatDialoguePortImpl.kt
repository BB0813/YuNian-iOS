package com.yunian.ai.wechat

import android.content.Context
import com.yunian.ai.common.AppSettingsStore
import com.yunian.ai.common.BanManager
import com.yunian.ai.common.ContentFilter
import com.yunian.ai.common.SecureLog
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
import com.yunian.ai.domain.imagegen.ImageGenProtocol
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.wechat.WeChatContentKind
import com.yunian.ai.domain.wechat.WeChatDialoguePort
import com.yunian.ai.domain.wechat.WeChatDialogueRequest
import com.yunian.ai.domain.wechat.WeChatDialogueResult
import com.yunian.ai.domain.wechat.WeChatInboundMessage
import com.yunian.ai.feature.wechat.WeChatDebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WeChatDialoguePortImpl(
    private val appContext: Context,
) : WeChatDialoguePort {

    private val aiService: AiServiceProvider
        get() = ServiceRegistry.getOrThrow(AiServiceProvider::class.java)

    private val chatRepository: ChatRepository
        get() = ServiceRegistry.getOrThrow(ChatRepository::class.java)

    private val messageWriter: MessageWriteCoordinator
        get() = ServiceRegistry.getOrThrow(MessageWriteCoordinator::class.java)

    private val companionRepository: CompanionRepository
        get() = ServiceRegistry.getOrThrow(CompanionRepository::class.java)

    private val memoryProvider: MemoryProvider
        get() = ServiceRegistry.getOrThrow(MemoryProvider::class.java)

    private val appSettingsStore = AppSettingsStore(appContext.applicationContext)

    override suspend fun generateReply(request: WeChatDialogueRequest): WeChatDialogueResult =
        withContext(Dispatchers.IO) {
            val companionId = request.companionId
            val inbound = request.inbound
            WeChatDebugLog.log("[Dialogue] generateReply START companion=$companionId")

            if (BanManager.isBanned(appContext)) {
                WeChatDebugLog.log("[Dialogue] generateReply BLOCKED by BanManager")
                return@withContext WeChatDialogueResult(replyText = "", blocked = true)
            }

            val companion = companionRepository.getCompanionById(companionId)
                ?: return@withContext WeChatDialogueResult(
                    replyText = "",
                    blocked = true,
                )

            val imagePath = inbound.primaryImagePath()
            if (imagePath != null) {
                return@withContext generateVisionReply(
                    companionId = companionId,
                    companion = companion,
                    imagePath = imagePath,
                )
            }

            val text = inbound.primaryText?.trim().orEmpty()
            if (text.isBlank()) {
                return@withContext WeChatDialogueResult(replyText = "", blocked = true)
            }

            generateTextReply(
                companionId = companionId,
                companion = companion,
                text = text,
            )
        }

    private suspend fun generateTextReply(
        companionId: Long,
        companion: com.yunian.ai.database.model.CompanionEntity,
        text: String,
    ): WeChatDialogueResult {
        val filterResult = ContentFilter.checkInput(text)
        if (filterResult.isViolating) {
            android.util.Log.w(TAG, "Input blocked by safety filter: ${filterResult.reason}")
            BanManager.recordViolation(appContext, filterResult.level)
            val blockedResponse = "抱歉，我无法处理这个话题。"
            val blockedMsg = ChatMessage(
                companionId = companionId,
                content = blockedResponse,
                isFromUser = false,
                timestamp = System.currentTimeMillis(),
            )
            val blockedId = messageWriter.enqueueChat(blockedMsg)
            return WeChatDialogueResult(
                replyText = blockedResponse,
                blocked = true,
                assistantMessageId = blockedId.takeIf { it > 0 },
            )
        }

        val userMessage = ChatMessage(
            companionId = companionId,
            content = text,
            isFromUser = true,
            timestamp = System.currentTimeMillis(),
        )
        messageWriter.enqueueChat(userMessage)
        companionRepository.updateTimestamp(companionId)

        val history = chatRepository.getRecentMessagesSync(companionId, limit = 30)
            .filterDecrypted()

        // 生图协议与 App 内聊天完全一致：总开关关闭时 rules 为空串（零行为变化）。
        val aiCompanionInfo = companion.toAiCompanionInfo().withImageGenRules()
        WeChatDebugLog.log("[Dialogue] Calling aiService.sendMessage for companion=$companionId")
        val aiResponse = try {
            aiService.sendMessage(aiCompanionInfo, history.toAiChatMessages(), 0)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "sendMessage failed", e)
            WeChatDebugLog.log("[Dialogue] aiService.sendMessage FAILED: ${e.message}")
            AiResponse(content = e.message ?: "API 错误")
        }
        val aiResponseRaw = aiResponse.content
        // 画面描述绝不能出现在微信消息或聊天记录里：统一走 ImageGenProtocol 清洗。
        val aiResponseText = ImageGenProtocol.sanitizeForDisplay(aiResponseRaw)
            .let { stripped ->
                if (stripped.isNotBlank()) {
                    stripped
                } else if (ImageGenProtocol.isPromptOnly(aiResponseRaw)) {
                    IMAGE_GEN_ONLY_REPLY_TEXT
                } else {
                    aiResponseRaw
                }
            }
        WeChatDebugLog.log("[Dialogue] aiService.sendMessage done reply_len=${aiResponseText.length}")

        if (aiResponseText.isNotBlank()) {
            val outputSafety = ContentFilter.checkOutputSafety(aiResponseText)
            if (!outputSafety.isSafe) {
                android.util.Log.w(
                    TAG,
                    "AI output blocked by safety filter: ${outputSafety.level} - ${outputSafety.reason}",
                )

                val blockedResponse = "抱歉，我无法回应这个话题。"
                val blockedMsg = ChatMessage(
                    companionId = companionId,
                    content = blockedResponse,
                    isFromUser = false,
                    timestamp = System.currentTimeMillis(),
                )
                val blockedId = messageWriter.enqueueChat(blockedMsg)
                return WeChatDialogueResult(
                    replyText = blockedResponse,
                    blocked = true,
                    assistantMessageId = blockedId.takeIf { it > 0 },
                )
            }
        }

        val contentToStore = aiResponseText.ifBlank { "API返回空内容" }

        val assistantMessageIds = listOf(
            messageWriter.enqueueChat(
                ChatMessage(
                    companionId = companionId,
                    content = contentToStore,
                    isFromUser = false,
                    timestamp = System.currentTimeMillis(),
                )
            )
        ).filter { it > 0L }
        val aiMessageId = assistantMessageIds.lastOrNull() ?: -1L

        if (aiMessageId > 0) {
            companionRepository.updateTimestamp(companionId)
            companionRepository.increaseIntimacy(companionId, 2)
            runCatching {
                memoryProvider.extractAndSaveFromConversation(
                    userInput = text,
                    aiResponse = aiResponseText,
                    companionId = companionId,
                )
            }.onFailure {
                android.util.Log.e(TAG, "Memory save failed: ${it.message}")
            }
        }

        return WeChatDialogueResult(
            replyText = aiResponseText,
            stickerLabels = extractStickerLabels(aiResponseText),
            blocked = false,
            assistantMessageId = aiMessageId.takeIf { it > 0 },
            assistantMessageIds = assistantMessageIds,
        )
    }

    private suspend fun generateVisionReply(
        companionId: Long,
        companion: com.yunian.ai.database.model.CompanionEntity,
        imagePath: String,
    ): WeChatDialogueResult {
        val userMessage = ChatMessage(
            companionId = companionId,
            content = imagePath,
            isFromUser = true,
            timestamp = System.currentTimeMillis(),
            type = MessageType.IMAGE,
            linkString = imagePath,
        )
        messageWriter.enqueueChat(userMessage)
        companionRepository.updateTimestamp(companionId)

        val history = chatRepository.getRecentMessagesSync(companionId, limit = 30)
            .filterDecrypted()

        val aiResponse = try {
            aiService.sendMessageWithImage(
                companion.toAiCompanionInfo(),
                history.toAiChatMessages(),
                imagePath,
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "sendMessageWithImage failed", e)
            return WeChatDialogueResult(
                replyText = "图片识别过程中出现错误: ${e.message}. 请稍后重试或发送文字描述。",
                blocked = false,
            )
        }
        val responseText = aiResponse.content

        if (responseText.isNotBlank()) {
            val outputSafety = ContentFilter.checkOutputSafety(responseText)
            if (!outputSafety.isSafe) {
                android.util.Log.w(
                    TAG,
                    "Vision AI output blocked by safety filter: ${outputSafety.level} - ${outputSafety.reason}",
                )
                val blockedResponse = "抱歉，我无法回应这个话题。"
                val blockedMsg = ChatMessage(
                    companionId = companionId,
                    content = blockedResponse,
                    isFromUser = false,
                    timestamp = System.currentTimeMillis(),
                )
                val blockedId = messageWriter.enqueueChat(blockedMsg)
                return WeChatDialogueResult(
                    replyText = blockedResponse,
                    blocked = true,
                    assistantMessageId = blockedId.takeIf { it > 0 },
                )
            }
        }

        val assistantMessageIds = listOf(
            messageWriter.enqueueChat(
                ChatMessage(
                    companionId = companionId,
                    content = responseText,
                    isFromUser = false,
                    timestamp = System.currentTimeMillis(),
                )
            )
        ).filter { it > 0L }
        val aiMessageId = assistantMessageIds.lastOrNull() ?: -1L

        runCatching {
            memoryProvider.extractAndSaveFromConversation(
                userInput = "[图片]",
                aiResponse = responseText,
                companionId = companionId,
            )
        }.onFailure {
            android.util.Log.e(TAG, "Memory save failed for vision: ${it.message}")
        }

        return WeChatDialogueResult(
            replyText = responseText,
            stickerLabels = extractStickerLabels(responseText),
            blocked = false,
            assistantMessageId = aiMessageId.takeIf { it > 0 },
            assistantMessageIds = assistantMessageIds,
        )
    }

    private fun WeChatInboundMessage.primaryImagePath(): String? {
        parts.firstOrNull { it.kind == WeChatContentKind.IMAGE }
            ?.media
            ?.localPath
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return parts.firstNotNullOfOrNull { part ->
            part.media?.localPath?.takeIf { path ->
                path.isNotBlank() && part.kind == WeChatContentKind.IMAGE
            }
        }
    }

    private fun extractStickerLabels(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val systemTags = setOf("语音", "图片", "视频", "文件", "位置", "红包", "转账")
        return Regex("\\[([^\\[\\]]+?)\\]")
            .findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() && it !in systemTags }
            .distinct()
            .toList()
    }

    private fun com.yunian.ai.database.model.CompanionEntity.toAiCompanionInfo() = AiCompanionInfo(
        id = id,
        name = name,
        personality = personality,
        age = age,
        backstory = backstory,
        speakingStyle = speakingStyle,
        systemPrompt = systemPrompt,
    )

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

    private fun ChatMessage.toAiChatMessage() = AiChatMessage(
        isFromUser = isFromUser,
        content = content,
        timestamp = timestamp,
        type = when (type) {
            MessageType.IMAGE -> AiMessageType.IMAGE
            else -> AiMessageType.TEXT
        },
        companionId = companionId,
    )

    private fun List<ChatMessage>.toAiChatMessages() = map { it.toAiChatMessage() }

    companion object {
        private const val TAG = "WeChatDialoguePort"

        /** 模型整条回复只有画面描述时的占位文案（不含方括号，避免被当成表情包标签） */
        private const val IMAGE_GEN_ONLY_REPLY_TEXT = "（正在为你配图…）"
    }
}
