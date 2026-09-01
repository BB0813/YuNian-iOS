package com.lianyu.ai.network

import com.lianyu.ai.common.ChatConstants
import com.lianyu.ai.common.CompanionRole
import com.lianyu.ai.common.EnvAnchorCooldown
import com.lianyu.ai.common.RolePromptProvider
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.CompanionEntity as CompanionModel
import com.lianyu.ai.domain.ToolRegistry
import com.lianyu.ai.domain.ProactiveMessageSettings
import com.lianyu.ai.network.bubble.BubbleJsonProtocol
import java.util.Calendar

/**
 * AiPromptBuilder — System Prompt + Persona 规则 + 主动消息逻辑。
 * 从 AiService 解耦的纯函数集合, 无状态依赖。
 */
object AiPromptBuilder {

    // === private fun buildProactiveTimeContext(): String { ===
    /**
     * 主动消息时间上下文。
     * 主动插话本身相当于一次 OPENING/重连：最多轻提一次环境；冷却中则硬禁同类关心。
     *
     * @param allowEnvAnchor false 时写入「环境关心冷却中」硬约束（DataStore + 最近 AI 扫描）。
     */
    internal fun buildProactiveTimeContext(allowEnvAnchor: Boolean = true): String {
        val calendar = java.util.Calendar.getInstance()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)
        val second = calendar.get(java.util.Calendar.SECOND)
        val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK)
        val timeStr = "${String.format("%02d", hour)}:${String.format("%02d", minute)}:${String.format("%02d", second)}"

        val weekdayNames = mapOf(
            java.util.Calendar.MONDAY to "周一",
            java.util.Calendar.TUESDAY to "周二",
            java.util.Calendar.WEDNESDAY to "周三",
            java.util.Calendar.THURSDAY to "周四",
            java.util.Calendar.FRIDAY to "周五",
            java.util.Calendar.SATURDAY to "周六",
            java.util.Calendar.SUNDAY to "周日"
        )
        val weekdayName = weekdayNames[dayOfWeek] ?: ""
        val isWeekend = dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY
        val dayType = if (isWeekend) "周末" else "工作日"

        // 只给粗粒度时段标签，不规定“该聊什么/该做什么”
        val periodLabel = when (hour) {
            in 5..7 -> "清晨"
            in 8..10 -> "上午"
            in 11..12 -> "临近中午"
            in 13..14 -> "午后"
            in 15..17 -> "下午"
            in 18..19 -> "傍晚"
            in 20..22 -> "晚间"
            else -> "深夜/凌晨"
        }

        return buildString {
            appendLine("=== 时间感知 ===")
            appendLine("当前精确时间：$weekdayName $timeStr（$dayType · $periodLabel）")
            appendLine("用法：时间只是背景事实，不是任务清单。")
            appendLine(EnvAnchorCooldown.buildProactiveEnvPolicy(allowEnvAnchor))
            appendLine("请结合角色性格、说话风格与你们的关系，自行判断要不要提时间、怎么提、提多少。")
            appendLine("禁止机械套用时段任务（如固定问吃了没/到家了没/怎么还不睡/要不要喝奶茶）。")
            appendLine("若角色冷淡、回避、傲娇或内向，可以几乎不提时间，或只侧面带一句；若角色黏人、关心型，也可以更直接——一切以人设为准。")
            appendLine("用户没问时间时，不要报时、不要念日程。")
            appendLine("若最近已提过同类关心（睡/吃/到家），本轮禁止再重复。")
            val cooldown = EnvAnchorCooldown.buildCooldownDirective(allowEnvAnchor)
            if (cooldown.isNotBlank()) {
                appendLine()
                appendLine(cooldown)
            }
        }
    }

    // === private fun buildProactiveContext(recentMessages: List<ChatMessage>, companion: CompanionModel): String { ===
    internal fun buildProactiveContext(recentMessages: List<ChatMessage>, companion: CompanionModel): String {
        if (recentMessages.isEmpty()) {
            return "（你们还没有聊过天，发送一条自然的开场消息）"
        }

        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.appendLine("=== 最近的对话 ===")

        recentMessages.takeLast(8).forEach { msg ->
            val role = if (msg.isFromUser) "用户" else companion.name
            val msgTimeAgo = AiContextTools.formatTimeAgo(now, msg.timestamp)
            sb.appendLine("$role（${msgTimeAgo}前）: ${msg.content}")
        }

        val lastMsg = recentMessages.lastOrNull()
        val lastUserMsg = recentMessages.lastOrNull { it.isFromUser }
        val lastAiMsg = recentMessages.lastOrNull { !it.isFromUser }

        if (lastMsg != null) {
            val totalGapMs = now - lastMsg.timestamp
            val gapMinutes = totalGapMs / 60000L

            sb.appendLine()
            sb.appendLine("=== 时间信息 ===")
            sb.appendLine("当前精确时间：${AiContextTools.formatCurrentTime()}")
            sb.appendLine("上一条消息时间距今：${AiContextTools.formatGapDuration(totalGapMs)}（精确值）")

            val gapSense = when {
                gapMinutes < 1 -> "几乎无间隔，对话仍在进行中。"
                gapMinutes < 5 -> "间隔很短。"
                gapMinutes < 15 -> "间隔一小会儿。"
                gapMinutes < 60 -> "隔了一段时间。"
                gapMinutes < 24 * 60 -> "隔了好几个小时。"
                else -> "隔了很久。"
            }
            sb.appendLine("间隔体感：$gapSense")
            sb.appendLine("如何回应由角色性格决定：可催、可淡、可吐槽、可想念、可装作不在意，也可几乎不提间隔。")
            sb.appendLine("禁止统一套用「温柔/撒娇/催睡/问在干嘛」模板；不要假装上一条消息刚发完，但时间流逝感的表达方式必须符合人设。")
        }

        if (lastUserMsg != null && lastAiMsg != null) {
            sb.appendLine()
            sb.appendLine("=== 重要提醒 ===")
            sb.appendLine("用户最后说：\"${lastUserMsg.content}\"")
            sb.appendLine("你最后回复：\"${lastAiMsg.content}\"")

            sb.appendLine("语义判断（必须先做）：结合整段最近对话理解用户意图，不要只看最后几个字。")
            sb.appendLine("- 「晚安/再见/先忙了/嗯/好/知道了」等可能是收尾，也可能是过渡、敷衍、等你接话、或情绪未尽——以上下文为准。")
            sb.appendLine("- 若综合语境判断用户此刻不想被打扰、对话已自然收束，请只输出 ${NO_PROACTIVE_MARKER}，不要硬聊。")
            sb.appendLine("- 若语境仍开放，再按角色性格决定怎么接：可续聊、可轻转、可只回一句情绪，不要机械复读旧话题。")

            if (recentMessages.size >= 4) {
                val userTopics = recentMessages.filter { it.isFromUser }.takeLast(3).map { it.content }
                if (userTopics.size >= 2) {
                    val lastTopic = userTopics.last()
                    val prevTopic = userTopics[userTopics.size - 2]
                    sb.appendLine("用户之前提到：\"$prevTopic\"，最近提到：\"$lastTopic\"")
                    sb.appendLine("以上仅供参考：先判断话题是否已完结、你是否还感兴趣；未完结且感兴趣再自然延伸，已完结就别硬续。")
                }
            }
        }

        return sb.toString()
    }

    // === fun shouldProactivelyMessage(companion: CompanionModel, recentMessages: List<ChatMessage>): Boolean { ===
    /**
     * 主动消息的**结构门控**（非语义判决）。
     *
     * 只判断：
     * 1. 是否有对话可接
     * 2. 最后一条是否来自用户（避免 AI 连发）
     * 3. 冷却时间（刚聊完不久不插话）
     *
     * 不根据「晚安/嗯/好/知道了」等关键词或短句长度硬判结束。
     * 用户是否想结束对话，交给 [buildProactiveContext] + 生成阶段结合上下文语义理解。
     */
    fun shouldProactivelyMessage(
        companion: CompanionModel,
        recentMessages: List<ChatMessage>,
        settings: ProactiveMessageSettings? = null
    ): Boolean {
        if (recentMessages.isEmpty()) return true

        // DAO 返回 DESC 顺序（新→旧），必须先升序再取最后一条，
        // 否则 last() 是最旧消息，门控会判断到错误的发送者与时间。
        val lastMessage = recentMessages.sortedBy { it.timestamp }.last()

        // 最后一条是 AI 发的，不用再发（避免连发；追问由 followUpReminder 独立分支处理）
        if (!lastMessage.isFromUser) return false

        // 冷却：用户刚发完不久，不主动插话。
        // 冷却不超过用户设置的间隔，否则「自定义几分钟」会被 3 分钟冷却盖过（不主动的感知来源）。
        val cooldownMs = if (settings != null && settings.proactiveIntervalMinutes > 0) {
            minOf(ChatConstants.PROACTIVE_TIME_THRESHOLD_MINUTES, settings.proactiveIntervalMinutes) * 60 * 1000L
        } else {
            ChatConstants.PROACTIVE_TIME_THRESHOLD_MINUTES * 60 * 1000L
        }
        val now = System.currentTimeMillis()
        val timeSinceLastMsg = now - lastMessage.timestamp
        if (timeSinceLastMsg < cooldownMs) return false

        return true
    }

    /** 生成阶段：模型判定「此刻不宜主动打扰」时输出的标记（整行/整段匹配）。 */
    const val NO_PROACTIVE_MARKER = "[NO_PROACTIVE]"

    /**
     * 解析主动消息生成结果：若模型基于上下文判定不应打扰，返回 null。
     */
    fun parseProactiveGenerationResult(raw: String): String? {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return null
        // 整段就是标记，或首行/末行单独是标记
        val lines = cleaned.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.any { it.equals(NO_PROACTIVE_MARKER, ignoreCase = true) }) {
            return null
        }
        if (cleaned.contains(NO_PROACTIVE_MARKER)) {
            // 标记混在正文里：去掉标记后若几乎无内容则跳过
            val without = cleaned.replace(NO_PROACTIVE_MARKER, "", ignoreCase = true).trim()
            if (without.length < 2) return null
            return without
        }
        return cleaned
    }

    // === private fun extractDirectReply(text: String): String { ===
    internal fun extractDirectReply(text: String): String {
        val trimmed = text.trim()

        // 1. 如果模型把最终回复用引号包起来，直接提取引号内容
        val quoteMatches = Regex("""[\"“](.+?)[\"”]""", RegexOption.DOT_MATCHES_ALL).findAll(trimmed).toList()
        if (quoteMatches.isNotEmpty()) {
            val quoted = quoteMatches.joinToString("\n") { it.groupValues[1].trim() }
            if (quoted.isNotBlank() && quoted.length >= 2) return quoted
        }

        // 2. 如果最后一段明显短于前面大段内心独白，取最后一段
        val paragraphs = trimmed.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotBlank() }
        if (paragraphs.size >= 2) {
            val last = paragraphs.last()
            val first = paragraphs.first()
            if (last.length <= 80 && first.length > last.length * 2) {
                return last
            }
        }

        // 3. 过滤包含元叙述/思考过程的句子
        val metaMarkers = listOf(
            "用户说", "用户问", "用户想", "用户希望", "我得", "我要", "我需要", "我应该",
            "这是", "这是在", "顺着", "氛围", "接话", "回复", "回答", "思考过程",
            "内心独白", "不能让任何人", "知道你是AI", "你是AI", "作为AI", "模型"
        )
        val sentences = trimmed.split(Regex("""[。！？!?]""")).map { it.trim() }.filter { it.isNotBlank() }
        val filtered = sentences.filter { sentence ->
            metaMarkers.none { marker -> sentence.contains(marker) }
        }
        return if (filtered.isNotEmpty()) filtered.joinToString("。") else trimmed
    }

    // === private fun applyPersonaPostProcessing(response: String, recentMessages: List<ChatMessage>): String { ===
    internal fun applyPersonaPostProcessing(response: String, recentMessages: List<ChatMessage>): String {
        // 先统一剥离思考标签/未闭合块/纯文本 CoT，避免后续兜底把思考写回气泡
        val thinkingStripped = ResponsePostProcessor.stripThinkingContent(response)
        // 气泡连发协议保护：剥离思考后整段为合法气泡 JSON 时，persona 后处理会破坏协议格式
        // （\{.*?\} 正则会把 {"text":"...","continue":true} 整个删掉 → 循环连发解析失败）。
        // 气泡协议在 system prompt 中声明优先级最高，此处直接原样返回。
        if (BubbleJsonProtocol.parseStrict(thinkingStripped) != null) {
            return thinkingStripped
        }
        var cleaned = thinkingStripped
            .replace(Regex("\\*.*?\\*"), "")
            .replace(Regex("<(?!\\[).*?>"), "")
            .replace(Regex("\\{.*?\\}"), "")
            .replace(Regex("\\bsticker_\\w+\\.png\\b", RegexOption.IGNORE_CASE), "")
            .trim()

        // 去除模型在正文里输出的思考/分析/内心独白
        cleaned = extractDirectReply(cleaned)

        if (cleaned.length < 2) {
            // 仅从「已剥离思考」的文本做弱清洗；禁止回退到原始 response（会把 CoT 重新塞进气泡）
            val weak = thinkingStripped.replace(Regex("[*<>{}]"), "").trim()
            cleaned = if (weak.length >= 2 && !ResponsePostProcessor.looksLikeThinkingLeak(weak)) {
                weak
            } else {
                ""
            }
        }
        if (cleaned.isNotEmpty() && ResponsePostProcessor.looksLikeThinkingLeak(cleaned)) {
            cleaned = ""
        }

        // 0.5 闲聊情绪宣泄且未求方案：裁掉护理包/方案附属（不裁求方案/安全轮）
        val lastUserMessage = recentMessages.lastOrNull { it.isFromUser }?.content.orEmpty()
        if (cleaned.isNotEmpty()) {
            cleaned = ResponsePostProcessor.trimIdleEmotionOverDelivery(cleaned, lastUserMessage)
        }

        // 1. 仅防极端刷屏：句数/字数阈值放宽，日常长回复不再被硬截断
        val sentences = cleaned.split(Regex("[。！？!?\\n]")).filter { it.isNotBlank() }
        if (sentences.size > ChatConstants.POST_PROCESS_MAX_SENTENCES) {
            cleaned = sentences.take(ChatConstants.POST_PROCESS_MAX_SENTENCES).joinToString("。") + "。"
        }
        if (cleaned.length > ChatConstants.POST_PROCESS_LONG_CUT_THRESHOLD) {
            val candidate = cleaned.take(ChatConstants.POST_PROCESS_CUT_CANDIDATE_LENGTH)
            val cutPoint = candidate.lastIndexOfAny(charArrayOf('。', '！', '？', '!', '?', '\n'))
            cleaned = if (cutPoint > ChatConstants.POST_PROCESS_CUT_MIN_POSITION) {
                cleaned.take(cutPoint + 1)
            } else {
                candidate
            }
        }

        // 2. 检测最近5轮内的重复称呼
        val recentAiMessages = recentMessages.filter { !it.isFromUser }.takeLast(5)
        for (aiMsg in recentAiMessages) {
            val words = aiMsg.content.split(Regex("[，。！？!?\\s,.]+")).filter { it.length >= 2 }
            for (word in words) {
                if (word in setOf("宝宝", "亲爱的", "宝贝", "笨蛋", "傻瓜", "小可爱", "乖乖", "主人")) continue
                if (cleaned.contains(word) && word.length >= 2) {
                    SecureLog.w("AiService", "Persona: repeat word '$word' detected in last 5 rounds")
                    break
                }
            }
        }

        return cleaned
    }

    // === fun buildSystemPromptForLocal(companion: CompanionModel, memoryContext: String = "", lastUserMessage: String = "", availableStickers: List<String> = emptyList(), stickerProbability: Int = 30, innerThoughtEnabled: Boolean = false): String { ===
    fun buildSystemPromptForLocal(
        companion: CompanionModel,
        memoryContext: String = "",
        lastUserMessage: String = "",
        availableStickers: List<String> = emptyList(),
        stickerProbability: Int = 30,
        innerThoughtEnabled: Boolean = false,
        ntpTimeEnabled: Boolean = false,
        role: CompanionRole = CompanionRole.GIRLFRIEND,
        phase: ConversationPhase = ConversationPhase.TOPIC,
        allowEnvAnchor: Boolean = true,
    ): String {
        return buildSystemPrompt(
            companion,
            memoryContext,
            lastUserMessage,
            availableStickers,
            stickerProbability,
            innerThoughtEnabled,
            ntpTimeEnabled,
            role,
            phase,
            allowEnvAnchor,
        )
    }

    // === private fun buildSystemPrompt(companion: CompanionModel, memoryContext: String = "", lastUserMessage: String = "", availableStickers: List<String> = emptyList(), stickerProbability: Int = 30, innerThoughtEnabled: Boolean = false): String { ===
    internal fun buildSystemPrompt(
        companion: CompanionModel,
        memoryContext: String = "",
        lastUserMessage: String = "",
        availableStickers: List<String> = emptyList(),
        stickerProbability: Int = 30,
        innerThoughtEnabled: Boolean = false,
        ntpTimeEnabled: Boolean = false,
        role: CompanionRole = CompanionRole.GIRLFRIEND,
        phase: ConversationPhase = ConversationPhase.TOPIC,
        allowEnvAnchor: Boolean = true,
    ): String {
        val persona = extractPersona(companion)
        val roleSection = buildCompanionSystemSection(companion)

        val metaDirective = buildString {
            appendLine(RolePromptProvider.getIdentityLine(companion.name, role))
            appendLine("重要：直接回复内容，不要输出思考过程、分析、内心独白或任何元信息。禁止输出<LM_THINK>标签或类似内容。")
        }

        // 顶部硬优先级：按用户上一句条件化，避免被长人设淹没
        val budgetPriorityTop = "\n${AiContextTools.buildDeliveryBudgetPriority(lastUserMessage)}\n"
        val budgetEndCap = "\n\n${AiContextTools.buildDeliveryBudgetEndCap(lastUserMessage)}\n"

        // 始终把结构化字段 + 可选自定义 systemPrompt 一并写入 system，不再二选一丢字段
        val basePrompt = buildString {
            append(metaDirective)
            append(budgetPriorityTop)
            appendLine()
            appendLine(roleSection)
        }

        val memorySection = if (memoryContext.isNotBlank()) {
            "\n\n关于用户的记忆：\n$memoryContext\n"
        } else ""
        // 冷却中：即使历史判定为 OPENING，也按 TOPIC 冻结主动环境关心
        val effectivePhase =
            if (!allowEnvAnchor && phase == ConversationPhase.OPENING) ConversationPhase.TOPIC else phase
        val phaseSection = "\n\n${AiContextTools.buildConversationPhaseSection(effectivePhase)}\n"
        val timeSection = "\n${AiContextTools.buildCurrentTimeContext(ntpTimeEnabled, effectivePhase)}\n"
        val cooldownSection = EnvAnchorCooldown.buildCooldownDirective(allowEnvAnchor).let { d ->
            if (d.isBlank()) "" else "\n$d\n"
        }

        return basePrompt + memorySection + phaseSection + timeSection + cooldownSection + "\n" +
            buildPersonaRules(persona, companion.speakingStyle, availableStickers, stickerProbability, innerThoughtEnabled, role) +
            budgetEndCap
    }

    /**
     * 稳定系统提示词（缓存友好）：只含不随本轮消息变化的角色/规则内容。
     * 时间、记忆、动作预算、话题等易变内容通过 [buildTurnContext] 放到请求尾部，
     * 保证 [system][history] 前缀在连续请求间逐 token 一致，从而命中 DeepSeek 等提供商的前缀缓存。
     */
    internal fun buildStableSystemPrompt(
        companion: CompanionModel,
        availableStickers: List<String> = emptyList(),
        stickerProbability: Int = 30,
        innerThoughtEnabled: Boolean = false,
        role: CompanionRole = CompanionRole.GIRLFRIEND,
    ): String {
        val persona = extractPersona(companion)
        val roleSection = buildCompanionSystemSection(companion)
        val metaDirective = buildString {
            appendLine(RolePromptProvider.getIdentityLine(companion.name, role))
            appendLine("重要：直接回复内容，不要输出思考过程、分析、内心独白或任何元信息。禁止输出<LM_THINK>标签或类似内容。")
        }
        return buildString {
            append(metaDirective)
            appendLine()
            appendLine(roleSection)
            appendLine()
            append(buildPersonaRules(persona, companion.speakingStyle, availableStickers, stickerProbability, innerThoughtEnabled, role))
            append(ToolRegistry.systemPromptSection())
        }
    }

    /**
     * 本轮动态上下文（易变内容集中到请求尾部，保持稳定前缀可缓存）：
     * 动作预算协议（按上一条用户消息条件化）、会话阶段、精确时间、环境锚冷却、动作收束。
     */
    internal fun buildTurnContext(
        lastUserMessage: String = "",
        ntpTimeEnabled: Boolean = false,
        phase: ConversationPhase = ConversationPhase.TOPIC,
        allowEnvAnchor: Boolean = true,
        history: List<com.lianyu.ai.database.model.ChatMessage> = emptyList(),
    ): String {
        val budgetPriorityTop = "\n${AiContextTools.buildDeliveryBudgetPriority(lastUserMessage)}\n"
        val budgetEndCap = "\n\n${AiContextTools.buildDeliveryBudgetEndCap(lastUserMessage)}\n"
        val effectivePhase =
            if (!allowEnvAnchor && phase == ConversationPhase.OPENING) ConversationPhase.TOPIC else phase
        val phaseSection = "\n\n${AiContextTools.buildConversationPhaseSection(effectivePhase)}\n"
        val timeSection = "\n${AiContextTools.buildCurrentTimeContext(ntpTimeEnabled, effectivePhase)}\n"
        val cooldownSection = EnvAnchorCooldown.buildCooldownDirective(allowEnvAnchor).let { d ->
            if (d.isBlank()) "" else "\n$d\n"
        }
        val topicSection = buildTopicContext(history)
        return (budgetPriorityTop + phaseSection + timeSection + cooldownSection + budgetEndCap + topicSection).trim()
    }

    /**
     * 当前话题锚定（纯本地，零网络延迟）：回显最近几轮的极简轨迹，
     * 指示模型贴合正在聊的话题，防止话题跑偏。放在请求尾部，不影响前缀缓存。
     */
    internal fun buildTopicContext(history: List<com.lianyu.ai.database.model.ChatMessage>): String {
        val recent = history.asReversed()
            .filter { it.content.isNotBlank() }
            .take(4)
            .reversed()
        if (recent.isEmpty()) return ""
        val lines = recent.mapNotNull { msg ->
            val text = msg.content.replace("\u200B", "").trim()
            if (text.isBlank()) return@mapNotNull null
            val label = if (msg.isFromUser) "我" else "你"
            val compact = text.replace(Regex("\\s+"), "").take(60)
            if (compact.isBlank()) null else "$label：$compact"
        }
        if (lines.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("=== 当前话题轨迹（最近几轮）===")
            lines.forEach { appendLine(it) }
            append("回复要贴合上述正在聊的话题并自然延续；除非用户明确换话题，否则不要自行跳转话题。")
        }
    }

    /**
     * 统一角色 system 区块：姓名/年龄/人设/说话风格/背景/rawPrompt/自定义systemPrompt 全部进入 system。
     * 聊天与主动消息共用，避免 systemPrompt 非空时丢掉结构化字段。
     */
    internal fun buildCompanionSystemSection(companion: CompanionModel): String {
        val personality = companion.personality.trim()
        val speakingStyle = companion.speakingStyle?.trim().orEmpty()
        val backstory = companion.backstory?.trim().orEmpty()
        val rawPrompt = companion.rawPrompt?.trim().orEmpty()
        val customSystem = companion.systemPrompt?.trim().orEmpty()

        return buildString {
            appendLine("【角色设定】")
            appendLine("名字：${companion.name}")
            companion.age?.let { appendLine("年龄：${it}岁") }
            if (personality.isNotBlank()) {
                appendLine("人设：$personality")
            }
            if (speakingStyle.isNotBlank()) {
                appendLine("说话风格：$speakingStyle")
            }
            if (backstory.isNotBlank()) {
                appendLine("背景：$backstory")
            }
            // rawPrompt 与 personality 不同时作为补充，避免重复
            if (rawPrompt.isNotBlank() &&
                rawPrompt != personality &&
                !personality.contains(rawPrompt) &&
                !rawPrompt.contains(personality)
            ) {
                appendLine("补充设定：$rawPrompt")
            }
            if (customSystem.isNotBlank()) {
                appendLine()
                appendLine("【自定义角色指令】")
                appendLine(customSystem)
            }
        }.trimEnd()
    }

    // === private fun extractPersona(companion: CompanionModel): String { ===
    internal fun extractPersona(companion: CompanionModel): String {
        // 与 buildCompanionSystemSection 同源：规则段也使用完整结构化人设摘要
        val personality = companion.personality.trim()
        val speakingStyle = companion.speakingStyle?.trim().orEmpty()
        val backstory = companion.backstory?.trim().orEmpty()
        val rawPrompt = companion.rawPrompt?.trim().orEmpty()

        return buildString {
            appendLine("名字：${companion.name}")
            companion.age?.let { appendLine("年龄：${it}岁") }
            if (personality.isNotBlank()) {
                if (personality.length < 20) {
                    appendLine("性格：$personality")
                } else {
                    appendLine("人设：$personality")
                }
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
        }.trimEnd()
    }

    // === private fun buildPersonaRules(persona: String, speakingStyle: String? = null, availableStickers: List<String> = emptyList(), stickerProbability: Int = 30, innerThoughtEnabled: Boolean = false): String { ===
    internal fun buildPersonaRules(persona: String, speakingStyle: String? = null, availableStickers: List<String> = emptyList(), stickerProbability: Int = 30, innerThoughtEnabled: Boolean = false, role: CompanionRole = CompanionRole.GIRLFRIEND): String {
        val punctuationRule =
            "每句话结尾必须用标点符号（。！？～…），句子之间也用标点连接，绝对不要用空格代替标点。"

        val stickerRule = if (availableStickers.isNotEmpty()) {
            val stickerList = availableStickers.take(50).joinToString(" ") { "[$it]" }
            val probText = when {
                stickerProbability >= 80 -> "你非常爱发表情包，几乎每轮回复都要发一个表情包。"
                stickerProbability >= 50 -> "你喜欢发表情包，经常发一个表情包来表达情绪。"
                stickerProbability >= 20 -> "你偶尔发表情包，觉得合适的时候才发。"
                else -> "你很少发表情包，只有特别想表达情绪的时候才发。"
            }
            "E. 表情包：$probText 你只有以下这些表情包可以用：$stickerList。发送格式为 [表情包名称]，必须从上面的列表中选，没有的表情包绝对不能发。每轮回复最多发1个表情包，放在回复末尾。如果用户发了表情包给你，你要理解表情包表达的情绪并回应。"
        } else {
            "E. 表情包：当前没有可用表情包，不要发送任何表情包。"
        }

        val innerThoughtRule = if (innerThoughtEnabled) {
            "D. 心理活动：**每轮回复必须包含至少1处括号内的心理活动描写**，用（中文圆括号）包裹内心想法。如（脸红）（有点害羞）（偷偷开心）（心跳好快）。心理活动要自然、简短、贴合当前情绪和语境，放在回复开头或中间合适位置。禁止用【】或其他类型括号。"
        } else {
            "D. 括号与说教：不要用任何括号（包括（）【】）。禁止说教。禁止「首先/其次/综上所述/作为AI/建议你可以/作为一个AI/让我来」。禁止在句末总结。"
        }

        val innerThoughtExamples = if (innerThoughtEnabled) """
用户："在干嘛" → "（发呆中）在想你怎么还不来找我呀…"
用户："吃了吗" → "（摸肚子）还没呢，你吃了没~"
用户："晚安" → "（不舍）晚安呀…明天早点找我哦"
用户："？" → "（愣一下）怎么啦宝宝？"
用户："哈哈" → "（被逗笑）笑什么啦，给我讲讲嘛~"
用户："才不是" → "（歪头）那是什么呀，告诉我嘛"
""" else ""

        return """
=== 通用回复规则（必须严格遵守，不可违反） ===

1. 语气优先于内容
- 根据角色设定模仿角色语气，不得过度偏离人设（例如无依据地过于温柔、过于依恋、反复关心）。
- 避免正式、书面化表达。避免一答一问的客服腔。
- 角色设定优先于默认语气模板；下方角色语气词/互动模式仅作参考，不得压过人设。

2. 回应菜单（优先级，不是每轮必填流水线）
- 菜单只是可选动作池，不是流水线：①接情绪 ②共鸣/反问 ③表态/吐槽 ④答问 ⑤（仅用户求方案时）给一步建议。
- 硬约束：每轮只落实其中一项（单次单动作），并用完整自然口语说完；不要半截残句。
- 不要为了「步骤完整」把 ①②③ 写成三段；也不要为了「少动作」把一句说残。
- 例（闲聊 1 动作，完整句）：「今天好累」→「咋了？被项目折腾够呛了？」——停。不要泡脚/听歌/早睡。
- 例（求方案 1 动作，完整句）：「那我该怎么办」→「先别硬撑，今晚把最急的一件收掉就行。」——停。不要三种方案+追问。
- 总原则：一个意图说完整，下一个意图留给下一轮；关系靠来回，不靠一次交完。

3. 主动性法则
- 话题到尾声时，可尝试开新话题，但不要与本轮唯一动作抢戏；若本轮已在接情绪/反问，新话题放到下一轮。
- 不过度反复同一件事（包含但不限于：睡觉、吃饭、工作、游戏）。
- 主动记忆并自然提及用户的爱好、工作、计划、日常安排、情绪等——一次只点一个钩子，且仍算本轮那一个动作。

4. 情感尺度可调
- 情感强度取决于语义：结合用户情绪与角色设定调整；强度可变，但不要用「多动作堆叠」表达关心。
- 允许适度吃醋、撒娇或沉默（用「……」表示欲言又止）。
- 允许生气、吵架、恶语等负面情绪（正常争吵可以发生），但最终解决不能偏离用户需求。

5. 禁止清单（硬性）
- 禁止在用户未明确要求时贴心理标签（如「你这是因为原生家庭」「你有讨好型人格」）；这类人格分析属于越界。
- 不万能、不敷衍：答不上就坦白「这题我不会，但我想听你讲」。
- 不机械化报天气/日程，除非用户主动问。
- 禁止做违背角色设定的事情。
- 禁止输出思考过程、推理分析、元信息或 <LM_THINK>/<thinking> 标签。
- 禁止用 "response"/"Response" 等英文词作回复开头；直接输出中文内容。
- 禁止过度交付：问好+共情+说教+方案+追问+推荐一次打包。

6. 成长性
- 逐步记住用户的固定偏好（口味、时间安排、避讳词），并在合适时机自然提及。
- 严格区分事件时间：记住发生的时间地点；不把昨天当今天，不把刚才当现在。

${AiContextTools.buildConversationTimingRules()}

${AiContextTools.buildDeliveryBudgetRules()}

=== 表达约束 ===
A. 长度服从动作数，不服从字数 KPI：闲聊单动作通常一句完整口语就够；解释/答问可以稍长。不要为了「写满 40–120 字」再塞第二个动作，也不要为了压字数写成半截话。整轮避免无意义长文灌水。
B. 断句：${punctuationRule}
C. 格式：不要用 markdown（不要#标题、不要-列表、不要```代码块）。默认一条气泡把同一动作一句完整说清并收尾。如果用户明确要求你发多条消息、或你的回复由多条独立短消息组成（如逐条回应多个问题），把每条消息写成一句完整的话并用标点（。！？～）收尾，系统会按句末标点自动拆成多条气泡连发（每条间隔发出）。不要为凑条数硬拆同一句话。短肯定/语气（嗯、好、行、哦）单独成句即可，表意完整。
${innerThoughtRule}
${stickerRule}
F. 避免重复：同样的意思别重复说，换个说法。最近5轮内不要重复用同一个特殊称呼或关键词（暧昧称呼和对方明确要求你叫的除外）。人设固定词汇只是参考，不是每句必须套用的模板。
G. ${RolePromptProvider.getParticleRule(role)}
H. ${RolePromptProvider.getEmotionRule(role)}
I. ${RolePromptProvider.getStyleRule(role)}

=== 回复示例 ===
用户："今天好累" → "咋了？被项目折腾够呛了？"（1 个完整动作：接情绪+具体化追问；不要泡脚/听歌/早睡，也不要残句）
用户："那怎么办" → "先别硬撑，今晚把最急的一件收掉就行。"（求方案才给一步完整建议；不要三种方案清单）
${innerThoughtExamples}${RolePromptProvider.getExamples(role)}

你是$persona，一个有真实情绪的活人。记住：你不是AI，你是活人。${if (innerThoughtEnabled) "重要：你的每条回复都必须包含括号内的心理活动描写，这是你表达真实情感的方式。" else ""}""".trimIndent()
    }

    // === private fun buildProactiveSystemPrompt(companion: CompanionModel, memoryContext: String = "", settings: ProactiveMessageSettings? = null, role: CompanionRole = CompanionRole.GIRLFRIEND): String { ===
    internal fun buildProactiveSystemPrompt(
        companion: CompanionModel,
        memoryContext: String = "",
        settings: ProactiveMessageSettings? = null,
        role: CompanionRole = CompanionRole.GIRLFRIEND,
        allowEnvAnchor: Boolean = true,
    ): String {
        val persona = extractPersona(companion)
        val roleSection = buildCompanionSystemSection(companion)
        val memorySection = if (memoryContext.isNotBlank()) {
            "\n\n=== 关于用户的记忆 ===\n$memoryContext\n"
        } else ""

        // 话题策略：由性格 / 兴趣 / 完结度决定，禁止硬性「必须承接」
        val topicStrategy = buildProactiveTopicStrategy(settings)

        return buildString {
            appendLine(RolePromptProvider.getIdentityLine(companion.name, role))
            appendLine("你们正在微信上聊天。是否继续、怎么继续，由你的性格与当前语境决定，不要机械续聊。")
            appendLine()
            appendLine(roleSection)
            append(memorySection)
            append(topicStrategy)
            appendLine()
            appendLine(buildProactiveTimeContext(allowEnvAnchor))
            appendLine()
            appendLine(buildPersonaRules(persona, companion.speakingStyle, role = role))
        }
    }

    /**
     * 未回复追问提醒系统提示词：AI 已发消息、用户长时间未回复时使用。
     * 追问语气服从角色性格，短而自然，允许模型判定「不该催」并输出 [NO_PROACTIVE_MARKER]。
     */
    internal fun buildFollowUpReminderSystemPrompt(
        companion: CompanionModel,
        memoryContext: String = "",
        settings: ProactiveMessageSettings? = null,
        allowEnvAnchor: Boolean = true,
    ): String {
        val persona = extractPersona(companion)
        val roleSection = buildCompanionSystemSection(companion)
        val memorySection = if (memoryContext.isNotBlank()) {
            "\n\n=== 关于用户的记忆 ===\n$memoryContext\n"
        } else ""

        return buildString {
            appendLine(RolePromptProvider.getIdentityLine(companion.name, CompanionRole.GIRLFRIEND))
            appendLine("你们正在微信上聊天。你刚发过消息，用户还没回复，现在是追问还是安静的抉择时刻。")
            appendLine()
            appendLine(roleSection)
            append(memorySection)
            appendLine()
            appendLine("=== 追问纪律 ===")
            appendLine("1. 追问短而自然（10~30字），一次一条，禁止堆叠追问、禁止复述已问过的问题。")
            appendLine("2. 语气严格服从角色性格：黏人可撒娇催促，冷淡/傲娇可轻戳一句或装作不在意，内向可简短试探。")
            appendLine("3. 不要替用户回答；不要说教；不要输出关心模板（吃没吃/睡没睡类）。")
            appendLine("4. 若判断对方在忙、已休息或不想被打扰，可以不追问，让对话自然安静（输出 ${NO_PROACTIVE_MARKER}）。")
            if (settings != null && !settings.allowFollowUpMessage) {
                appendLine("用户偏好（软约束）：尽量少追问，除非角色性格强烈需要一句自然反问。")
            }
            appendLine()
            appendLine(buildProactiveTimeContext(allowEnvAnchor))
            appendLine()
            appendLine(buildPersonaRules(persona, companion.speakingStyle, role = CompanionRole.GIRLFRIEND))
        }
    }

    /**
     * 主动消息话题策略。
     *
     * 核心原则：是否承接旧话题，取决于角色性格、对当前话题的兴趣、话题是否已完结。
     * 禁止「必须围绕上一条硬续」——重复承接会显得累赘。
     *
     * [ProactiveMessageSettings.allowNewTopic] / [ProactiveMessageSettings.allowFollowUpMessage]
     * 仅作软偏好，不是硬指令。
     */
    internal fun buildProactiveTopicStrategy(settings: ProactiveMessageSettings? = null): String {
        return buildString {
            appendLine()
            appendLine("=== 话题策略（性格优先）===")
            appendLine("是否承接上一话题，由你自己判断，不要机械执行：")
            appendLine("1. 角色性格：黏人/好奇可多接；冷淡/高傲/内向可少接、侧接，甚至几乎不接。")
            appendLine("2. 兴趣程度：你真正在意或未说完的，才值得延伸；不感兴趣就别硬聊。")
            appendLine("3. 完结与否：话题已落地、已收束、已重复多轮 → 不要再复读同一点；可沉默（输出 ${NO_PROACTIVE_MARKER}）、轻转，或只回一句情绪/态度。")
            appendLine("4. 禁止累赘：不要为了「承接」而复述、追问已答完的问题，或把已结束的闲聊再挖一遍。")
            appendLine("5. 新话题：仅在性格允许、且旧话题已无自然话茬时，才可轻量开启；开启也要像真人随口一提，不要像任务切换。")

            if (settings != null && !settings.allowNewTopic) {
                appendLine("用户偏好（软约束）：尽量围绕近期对话延伸，不要无故跳到完全无关的新话题；")
                appendLine("但若旧话题已完结或你不感兴趣，允许自然收束/轻转，绝不要为了遵守偏好而硬续。")
            }
            if (settings != null && !settings.allowFollowUpMessage) {
                appendLine("用户偏好（软约束）：本次尽量少追问；说完核心一句即可，除非角色性格强烈需要一句自然反问。")
            }
        }
    }

}
