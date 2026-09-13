package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.common.ContentFilter
import com.yunian.ai.common.SecureLog
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.repository.ChatRepository
import com.yunian.ai.database.repository.MessageWriteCoordinator
import com.yunian.ai.database.repository.filterDecrypted
import com.yunian.ai.domain.AiServiceProvider
import com.yunian.ai.domain.AiCompanionInfo
import com.yunian.ai.domain.AiChatMessage
import com.yunian.ai.domain.imagegen.ImageGenProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Follow-up question trigger — after AI replies, probabilistically sends a follow-up
 * question to keep the conversation going.
 *
 * Extracted from ChatViewModel to reduce class size.
 */
internal object ChatFollowUpTrigger {

    private val QUESTION_REGEX = Regex("[?？]|吗|呢|什么|怎么|为什么|多少|哪|谁|几|是不是|有没有|能不能|会不会|要不要|好不好")

    /** 防连环追问：两次自动追问的最小间隔（30 分钟），避免「AI 自问自答」。 */
    private const val FOLLOW_UP_MIN_INTERVAL_MS = 30 * 60 * 1000L

    /** 自动追问触发概率：仅在「话头停在 AI 侧」时以低概率触发。 */
    private const val FOLLOW_UP_TRIGGER_PROBABILITY = 0.15f

    @Volatile
    private var lastFollowUpAt: Long = 0L

    /**
     * Trigger a follow-up question if conditions are met:
     * 1) Settings allow follow-up messages
     * 2) AI reply doesn't already contain a question（话头已递回）
     * 3) AI reply doesn't contain interaction markers targeting the user（话头已递回）
     * 4) At least 30 minutes since last follow-up
     * 5) 15% probability
     *
     * @param scope Coroutine scope to launch the follow-up in (application-level).
     * @param aiContent The AI's reply content.
     * @param allowFollowUp Whether follow-up is enabled in settings.
     * @param companionId The companion ID.
     * @param companion The companion info for prompt building.
     * @param chatRepository Repository for fetching history and saving messages.
     * @param aiService AI service for generating the follow-up question.
     * @param broadcastCallback Callback to broadcast the follow-up message to WeChat.
     */
    fun triggerFollowUpIfNeeded(
        scope: CoroutineScope,
        aiContent: String,
        allowFollowUp: Boolean,
        companionId: Long,
        companion: AiCompanionInfo,
        chatRepository: ChatRepository,
        messageWriter: MessageWriteCoordinator,
        aiService: AiServiceProvider,
        broadcastCallback: (Long, String) -> Unit
    ) {
        if (!allowFollowUp) return
        if (QUESTION_REGEX.containsMatchIn(aiContent)) return
        if (looksLikeTurnBackToUser(aiContent)) return
        val now = System.currentTimeMillis()
        if (now - lastFollowUpAt < FOLLOW_UP_MIN_INTERVAL_MS) return
        if (kotlin.random.Random.nextFloat() > FOLLOW_UP_TRIGGER_PROBABILITY) return
        lastFollowUpAt = now

        scope.launch {
            try {
                delay(2000L + kotlin.random.Random.nextLong(3000L))

                val history = chatRepository.getRecentMessagesSync(companionId, 10).filterDecrypted()
                val followUp = aiService.generateFollowUpQuestion(
                    companion, history.toAiChatMessages(), aiContent
                ) ?: return@launch

                // 追问消息安全检查
                val followUpSafety = ContentFilter.checkOutputSafety(followUp)
                if (!followUpSafety.isSafe) {
                    SecureLog.w("ChatViewModel", "Follow-up safety violation: ${followUpSafety.reason}")
                    return@launch
                }

                // 追问同样可能夹带生图标签/画面描述：落库前统一清洗，
                // 否则会污染气泡与会话列表摘要（未读预览看到的会是提示词原文）。
                val followUpClean = ImageGenProtocol.sanitizeForDisplay(followUp)
                if (followUpClean.isBlank()) return@launch
                val followUpMsg = ChatMessage(
                    companionId = companionId,
                    content = followUpClean,
                    isFromUser = false,
                    timestamp = System.currentTimeMillis()
                )
                val msgId = messageWriter.enqueueChat(followUpMsg)
                broadcastCallback(msgId, followUpClean)
                SecureLog.d("ChatViewModel", "Follow-up question sent: $followUp")
            } catch (e: Exception) {
                SecureLog.w("ChatViewModel", "Follow-up question failed: ${e.message}")
            }
        }
    }

    /**
     * 主回复是否已把「话头」递回给用户。
     * 只要回复已指向用户（第二人称），或带互动语气（调侃/撒娇/威胁/反问），
     * 就认为话头已递回，不再自动追加追问——避免「AI 自问自答」的观感。
     */
    private fun looksLikeTurnBackToUser(text: String): Boolean {
        if (text.contains("你")) return true
        val interactiveMarkers = listOf(
            "吧", "呀", "嘛", "哼", "啦", "呗", "哈哈", "嘿嘿", "嘻嘻",
            "~", "～", "等着", "看你", "找你", "来找我", "算账", "不信", "才怪",
        )
        return interactiveMarkers.any { text.contains(it) }
    }
}

