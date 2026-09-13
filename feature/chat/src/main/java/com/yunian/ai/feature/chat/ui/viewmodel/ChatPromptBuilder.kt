package com.yunian.ai.feature.chat.ui.viewmodel

import com.yunian.ai.common.EnvAnchorCooldown
import com.yunian.ai.common.StickerManager
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.network.AiContextTools
import com.yunian.ai.network.ConversationPhase
import com.yunian.ai.network.ConversationPhaseDetector

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
        // 自定义角色指令不截断（放宽至 4000 字符防失控）：用户写多长就带多长
        val customSystem = companion.systemPrompt?.take(4000).orEmpty()

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
            appendLine("2. 单次单动作：每轮只做一个核心社交意图（纯共情/纯反问/纯表态/纯答问；求方案才给一步），并用完整口语说完。严禁问好+共情+反问+方案打包，也严禁半截残句。")
            appendLine("3. 镜像前置：开口先回表层情绪或表层问句；延伸只能一项且写进同一完整句靠后，闲聊未求方案则不写延伸。")
            appendLine("4. 主动性：话题尾声可开新话题，但勿与本轮唯一动作抢戏；一次只点一个钩子。")
            appendLine("5. 情感可调：可吃醋/撒娇/沉默（……）/生气吵架；关心靠语气，不靠多动作堆叠。")
            appendLine("6. 禁止：未要求时贴心理标签；敷衍万能回答；机械报天气日程；违背人设；输出思考过程；过度交付；残句式回复。")
            appendLine("7. 成长性：记住偏好与事件时间地点，不把昨天当今天、刚才当现在。")
            appendLine(AiContextTools.buildConversationTimingRules())
            appendLine(AiContextTools.buildDeliveryBudgetRules())
            appendLine("表达约束：")
            appendLine("A. 长度服从动作数：闲聊单动作通常一句完整口语即可；解释/答问可稍长。不要为凑字再塞第二个动作，也不要为压字数写残句。")
            appendLine("B. 每句话用标点结尾（。！？～…），表意收住。")
            appendLine("C. 不要重复同样的话。")
            appendLine("D. 每条回复 = 一条气泡：把同一动作用一句完整口语说完并收尾；不要用空行/换行分块（连发由系统连发机制处理）。不要 markdown。")
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
                // 自定义表情语义清单（gated：未导入任何自定义表情时整段不拼，保持零变化）
                val customStickers = stickerManager.getPromptStickers().filter { it.isCustom }
                if (customStickers.isNotEmpty()) {
                    appendLine("G2. 用户还教了你专属表情包，含义如下，请在语境匹配时优先使用：")
                    com.yunian.ai.common.CustomStickerPrompt.buildLines(customStickers).forEach { appendLine("    $it") }
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

