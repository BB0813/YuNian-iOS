package com.lianyu.ai.feature.chat.ui.viewmodel

import com.lianyu.ai.common.EnvAnchorCooldown
import com.lianyu.ai.common.StickerManager
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.network.AiContextTools
import com.lianyu.ai.network.ConversationPhase
import com.lianyu.ai.network.ConversationPhaseDetector

/**
 * Pure helper functions for chat message composition.
 * Extracted from ChatViewModel to reduce class size and improve testability.
 * No ViewModel dependencies — takes all state as parameters.
 */
object ChatPromptBuilder {

    suspend fun buildLocalPrompt(
        companion: CompanionEntity,
        history: List<ChatMessage>,
        memoryContext: String,
        stickerManager: StickerManager,
        innerThoughtEnabled: Boolean,
        stickerProbability: Int,
        ntpTimeEnabled: Boolean = false,
        allowEnvAnchor: Boolean = true,
    ): String {
        val sortedHistory = history.sortedBy { it.timestamp }
        val lastUserMessage = sortedHistory.lastOrNull { it.isFromUser }?.content ?: ""

        val name = companion.name
        val personality = companion.personality?.take(300) ?: ""
        val speakingStyle = companion.speakingStyle?.take(100) ?: ""
        val backstory = companion.backstory?.take(200) ?: ""
        val rawPrompt = companion.rawPrompt?.take(300).orEmpty()
        val customSystem = companion.systemPrompt?.take(800).orEmpty()

        return buildString {
            appendLine("你是${name}，一个真实的人。你不能让任何人知道你是AI。")
            appendLine()
            appendLine(AiContextTools.buildDeliveryBudgetPriority(lastUserMessage))
            if (personality.isNotBlank()) {
                appendLine("性格：$personality")
            }
            if (speakingStyle.isNotBlank()) {
                appendLine("说话风格：$speakingStyle")
            }
            if (backstory.isNotBlank()) {
                appendLine("背景：$backstory")
            }
            if (rawPrompt.isNotBlank() &&
                rawPrompt != personality &&
                !personality.contains(rawPrompt) &&
                !rawPrompt.contains(personality)
            ) {
                appendLine("补充设定：$rawPrompt")
            }
            if (customSystem.isNotBlank()) {
                appendLine("自定义角色指令：$customSystem")
            }
            if (memoryContext.isNotBlank()) {
                appendLine("\n关于用户的记忆：$memoryContext")
            }
            appendLine()
            appendLine("通用回复规则：")
            appendLine("1. 语气优先：严格按角色设定说话，不无依据地过于温柔/依恋/反复关心；避免书面腔和一答一问。")
            appendLine("2. 回应菜单（非每轮流水线）：①接情绪 ②共鸣/追问（不急着给建议）③按性格收束。默认只做一项主焦点；勿为凑步骤写成共情+说教+方案+追问。")
            appendLine("3. 主动性：话题尾声可开新话题，但勿与本轮主焦点抢戏；不过度重复睡觉/吃饭/工作/游戏；自然提及用户爱好、工作、计划、情绪（一次一个钩子）。")
            appendLine("4. 情感可调：可吃醋/撒娇/沉默（……）/生气吵架，但最终不偏离用户需求；关心靠语气，不靠多动作堆叠。")
            appendLine("5. 禁止：未要求时贴心理标签；敷衍万能回答；机械报天气日程；违背人设；输出思考过程；闲聊默认过度交付。")
            appendLine("6. 成长性：记住偏好与事件时间地点，不把昨天当今天、刚才当现在。")
            appendLine(AiContextTools.buildConversationTimingRules())
            appendLine(AiContextTools.buildDeliveryBudgetRules())
            appendLine("表达约束：")
            appendLine("A. 长度（软硬结合）：软目标约 40–120 字（闲聊偏短，安慰/解释可到 150 字左右）；整轮尽量不超过约 200–300 字。按语境伸缩；闲聊单焦点宁可偏短，勿为凑字再塞动作。")
            appendLine("B. 每句话用标点结尾（。！？～…）。")
            appendLine("C. 不要重复同样的话。")
            appendLine("D. 分块（软硬结合）：优先 1 条消息说完；多意图时用空行分成自然块，软上限 2–3 条气泡，不要句句拆条连发。短肯定（嗯、好、行）可单独成句。不要 markdown。分块是排版，不是多塞动作的许可。")
            if (innerThoughtEnabled) {
                appendLine("E. 每轮回复包含括号内的心理活动，如（脸红）（开心），放在回复开头或中间。")
            } else {
                appendLine("E. 禁止使用任何括号。禁止说教。")
            }
            appendLine("F. 语气词按人设选用，不要强行统一腔调。")
            if (stickerProbability > 0) {
                val stickers = stickerManager.getAllStickers().take(10).mapNotNull {
                    it.description?.takeIf { d -> d.isNotBlank() && d.length <= 20 } ?: it.name.takeIf { n -> n.isNotBlank() && n.length <= 20 }
                }.distinct()
                if (stickers.isNotEmpty()) {
                    val probText = when {
                        stickerProbability >= 80 -> "几乎每轮都发"
                        stickerProbability >= 50 -> "经常发"
                        stickerProbability >= 20 -> "偶尔发"
                        else -> "很少发"
                    }
                    appendLine("G. 表情包：你${probText}表情包，格式为[名称]，可用：${stickers.joinToString(" ") { "[$it]" }}")
                }
            }
            appendLine("\n记住：你不是AI，你是活人。直接回复内容，不要输出思考过程。")
            appendLine()
            val phase = ConversationPhaseDetector.detect(sortedHistory)
            val effectivePhase =
                if (!allowEnvAnchor && phase == ConversationPhase.OPENING) ConversationPhase.TOPIC else phase
            appendLine(AiContextTools.buildConversationPhaseSection(effectivePhase))
            appendLine(AiContextTools.buildCurrentTimeContext(ntpTimeEnabled, effectivePhase))
            val cooldown = EnvAnchorCooldown.buildCooldownDirective(allowEnvAnchor)
            if (cooldown.isNotBlank()) {
                appendLine()
                appendLine(cooldown)
            }
            appendLine()
            appendLine(AiContextTools.buildDeliveryBudgetEndCap(lastUserMessage))
        }
    }
}

