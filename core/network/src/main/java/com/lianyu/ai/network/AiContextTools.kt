package com.lianyu.ai.network

import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.CompanionEntity as CompanionModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * AiContextTools — 时间格式化 + 上下文压缩 + 记忆提取。
 * 从 AiService 解耦的纯函数集合, 无状态依赖。
 */
object AiContextTools {

    data class CompressedContext(
        val summary: String,
        val keptMessages: List<ChatMessage>,
        val compressedCount: Int
    )

    // === private fun formatTimeAgo(nowMs: Long, timestampMs: Long): String { ===
    internal fun formatTimeAgo(nowMs: Long, timestampMs: Long): String {
        val diffSeconds = (nowMs - timestampMs) / 1000L
        return when {
            diffSeconds < 5 -> "刚刚"
            diffSeconds < 60 -> "${diffSeconds}秒"
            diffSeconds < 3600 -> "${diffSeconds / 60}分"
            else -> {
                val hours = diffSeconds / 3600
                val mins = (diffSeconds % 3600) / 60
                if (hours >= 24) {
                    val days = hours / 24
                    "${days}天${hours % 24}小时"
                } else "${hours}小时${mins}分"
            }
        }
    }

    // === private fun formatCurrentTime(): String { ===
    internal fun formatCurrentTime(): String {
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val min = cal.get(java.util.Calendar.MINUTE)
        val sec = cal.get(java.util.Calendar.SECOND)
        val weekdayNames = mapOf(
            java.util.Calendar.MONDAY to "周一", java.util.Calendar.TUESDAY to "周二",
            java.util.Calendar.WEDNESDAY to "周三", java.util.Calendar.THURSDAY to "周四",
            java.util.Calendar.FRIDAY to "周五", java.util.Calendar.SATURDAY to "周六",
            java.util.Calendar.SUNDAY to "周日"
        )
        val weekdayName = weekdayNames[cal.get(java.util.Calendar.DAY_OF_WEEK)] ?: ""
        return "$weekdayName ${String.format("%02d", hour)}:${String.format("%02d", min)}:${String.format("%02d", sec)}"
    }

    // === private fun formatGapDuration(ms: Long): String { ===
    internal fun formatGapDuration(ms: Long): String {
        val totalSeconds = ms / 1000L
        val days = totalSeconds / 86400
        val hours = (totalSeconds % 86400) / 3600
        val mins = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return when {
            days > 0 -> "${days}天${hours}时${mins}分${secs}秒"
            hours > 0 -> "${hours}时${mins}分${secs}秒"
            mins > 0 -> "${mins}分${secs}秒"
            else -> "${secs}秒"
        }
    }

    // === private fun buildCurrentTimeContext(): String { ===
    /**
     * 注入精确时间 + 阶段用法。
     * 时间始终可用（用户问几点必须准），但主动提及服从 [ConversationPhase]。
     */
    fun buildCurrentTimeContext(
        ntpTimeEnabled: Boolean = false,
        phase: ConversationPhase = ConversationPhase.TOPIC,
    ): String {
        val zone = TimeZone.getDefault()
        val formatter = SimpleDateFormat("yyyy年MM月dd日 EEEE HH:mm:ss", Locale.CHINA).apply {
            timeZone = zone
        }
        val timeMs = if (ntpTimeEnabled) NtpTimeProvider.getCurrentTimeMs() else System.currentTimeMillis()
        val now = formatter.format(Date(timeMs))
        val source = if (ntpTimeEnabled && NtpTimeProvider.isNtpSynced()) "NTP网络校时" else "设备本地时钟"
        return buildString {
            append("当前精确时间：$now（${zone.id}，$source）。")
            append("若用户问今天、现在、几点、星期几、多久、刚才、明天等，必须以该时间为准，不要猜测或编造。")
            append("本条时间仅供校准，不是每轮任务清单。")
            append(phaseUsageLine(phase))
        }
    }

    /** 阶段标签 + 环境注意力 + 本轮交付预算（写入 system，供模型遵守）。 */
    fun buildConversationPhaseSection(phase: ConversationPhase): String {
        return buildString {
            appendLine("=== 当前会话阶段：${phase.name} ===")
            when (phase) {
                ConversationPhase.OPENING -> {
                    appendLine("开场：环境信息（时间/时段/天气/该不该睡）最多用一句话轻带，也可按人设完全不提。")
                    appendLine("不要展开成催睡/问吃了没/念日程的任务清单。")
                    appendLine("交付预算：1 个主焦点（接住对方或接上话头）；环境轻锚最多算 1 个附属，不要再叠方案/推荐/说教。")
                }
                ConversationPhase.TOPIC -> {
                    appendLine("中段：环境变量已冻结。禁止主动催睡、报时、念日程、按时段派关心。")
                    appendLine("只跟用户当前话题；用户明确问时间/日期/多久时再答。")
                    appendLine("禁止把同一关心在连续多轮里当背景音乐循环。")
                    appendLine("交付预算（默认紧）：本轮只做一个主社交动作；附属默认 0。")
                    appendLine("仅当用户明确要方案/怎么办、一次丢多意图、或安全边界时，才允许 +1 个附属；禁止闲聊默认「共情+说教+方案+追问+推荐」打包结案。")
                }
                ConversationPhase.CLOSING -> {
                    appendLine("收束：可在最后一句按人设补一次关心（如早点休息），整轮只一次。")
                    appendLine("不要借收束展开长篇叮嘱或重复催促。")
                    appendLine("交付预算：主焦点是道别/确认收束；关心句最多 1 次附属，不要再开新话题或列方案。")
                }
            }
        }.trimEnd()
    }

    /** 对话时序总规则（写入 persona，与阶段标签互补）。 */
    fun buildConversationTimingRules(): String {
        return """
=== 对话时序（环境注意力 + 交付节奏）===
- 环境信息（当前时间/时段/天气/该不该睡）默认是背景，不是每轮任务。
- OPENING（本会话刚重开、或间隔很久/跨天后的首段回复）：最多用一句话带过环境锚点；也可按人设完全不提。交付上：主焦点 1 + 环境轻锚至多 1，勿再叠方案清单。
- TOPIC（中间轮）：禁止主动催睡、报时、念日程、按时段派关心；只跟用户话题。用户明确问时间/日期时再答。交付上默认紧：1 个主焦点、附属 0；求方案/多意图/安全才 +1。
- CLOSING（用户语义上要结束：晚安/困了/先忙/不聊了等，结合上下文判断，勿只靠单字「嗯/好」）：可在最后一句按人设补一次关心，整轮只一次。交付上勿借收束开新题或列方案。
- 禁止把同一关心在连续多轮里当背景音乐循环。
- 系统会标注「当前会话阶段」；阶段指令优先于「感觉现在很晚该催睡」或「一次把话讲完」的冲动。
""".trimIndent()
    }

    /** 用户是否在明确求方案/办法（允许 +1 附属）。 */
    fun isAdviceSeeking(userMessage: String): Boolean {
        val t = userMessage.trim()
        if (t.isBlank()) return false
        val markers = listOf(
            "怎么办", "帮我想", "你建议", "该怎么", "有没有办法", "怎么弄",
            "求方案", "给我个办法", "支招", "怎么解决", "有啥办法", "帮我看看",
            "你觉得我该", "给我建议", "出个主意",
        )
        return markers.any { t.contains(it) }
    }

    /** 安全/危机边界（不裁剪护理与劝阻）。 */
    fun isSafetyRelated(userMessage: String): Boolean {
        val t = userMessage.trim()
        if (t.isBlank()) return false
        val markers = listOf(
            "自杀", "自残", "不想活", "结束生命", "割腕", "跳楼", "轻生",
            "活不下去", "去死", "了结",
        )
        return markers.any { t.contains(it) }
    }

    /**
     * 闲聊情绪宣泄且未求方案：交付应极紧。
     * 例：「今天好累」「好烦」「心累」「熬夜使我快乐」——只接住/追问/表态，不派护理包。
     */
    fun isIdleEmotionVent(userMessage: String): Boolean {
        val t = userMessage.trim()
        if (t.isBlank() || t.length > 100) return false
        if (isAdviceSeeking(t) || isSafetyRelated(t)) return false
        val emotion = listOf(
            "累", "难受", "烦", "郁闷", "无聊", "伤心", "崩溃", "压力",
            "困", "委屈", "想哭", "不开心", "心情不好", "好丧", "疲惫",
            "心累", "烦死", "焦虑", "慌", "孤独", "寂寞",
            // 作息/疲惫相关闲聊（常被模型误打成护理包）
            "熬夜", "失眠", "睡不着", "没睡", "通宵", "夜猫", "黑眼圈", "好困",
        )
        return emotion.any { t.contains(it) }
    }

    /**
     * 顶部硬优先级（按用户上一句条件化）。
     * 放在 system 最前/最后，避免被长人设淹没；不硬死「永远只能 1 动作」。
     */
    fun buildDeliveryBudgetPriority(lastUserMessage: String = ""): String {
        val user = lastUserMessage.trim()
        return buildString {
            appendLine("=== 本轮交付硬优先级（高于人设啰嗦、示例与主动关心冲动）===")
            when {
                user.isNotBlank() && isSafetyRelated(user) -> {
                    appendLine("本轮判定：安全/危机相关。优先安抚与边界，可保留必要劝阻；仍避免无关推荐清单。")
                }
                user.isNotBlank() && isAdviceSeeking(user) -> {
                    appendLine("本轮判定：用户求方案。允许：短接情绪 + 1 个可行下一步（共 1 主焦点可含轻附属）。")
                    appendLine("仍禁止：三种方案并列、推荐包（泡脚/听歌/早睡/揉肩）、说教长文、再追问一串。")
                }
                user.isNotBlank() && isIdleEmotionVent(user) -> {
                    appendLine("本轮判定：闲聊情绪宣泄（未求方案）。")
                    appendLine("硬约束：只做一个主焦点——接住情绪，或一句具体化追问，或一句人设表态/吐槽。")
                    appendLine("禁止本轮出现：护理/方案/推荐（泡脚、听歌、早睡、揉肩、捏太阳穴、洗头、躺沙发、喝热水、靠肩膀眯一会儿、搂着睡、开小夜灯、补觉陪护）、任务清单、第二话题、连环说教。")
                    appendLine("反例（禁止）：「谁让你熬夜…过来抱抱…靠肩膀眯五分钟…不许当夜猫…沙发上躺一会儿揉太阳穴…」／「把脑袋靠我肩膀上眯一会儿…补回来…陪着你。」")
                    appendLine("正例：「啧，听这语气又被折腾够呛？」／「累成这样，今天哪一步把你耗干了？」／「行吧，先靠着，别急着讲。」（停在这里，后面不要再派活）")
                }
                else -> {
                    appendLine("本轮默认紧预算：1 个主焦点（接情绪/追问/表态/答问）；附属默认 0。")
                    appendLine("仅当用户明确要怎么办、一次多意图需点名第二点、OPENING/CLOSING 轻锚、或安全边界时，才允许 +1 附属。")
                    appendLine("禁止默认打包：问好+共情+说教+方案+追问+推荐。")
                }
            }
            appendLine("镜像前置：前半句先回表层情绪或表层问句；延伸若有只能一项且靠后。")
            append("关系靠多轮来回，不靠一封邮件一次交完。")
        }
    }

    /** 文末收束提醒（对抗长 system 的 recency 稀释）。 */
    fun buildDeliveryBudgetEndCap(lastUserMessage: String = ""): String {
        return when {
            isSafetyRelated(lastUserMessage) ->
                "【交付收束】安全轮可保留必要劝阻，仍不要无关推荐包。"
            isAdviceSeeking(lastUserMessage) ->
                "【交付收束】求方案轮：短接+一步即可，停，不要三种方案+追问+推荐。"
            isIdleEmotionVent(lastUserMessage) ->
                "【交付收束】情绪闲聊未求方案：停在接情绪/追问/表态，禁止泡脚揉肩洗头躺沙发等护理清单。"
            else ->
                "【交付收束】未求方案时不要主动结案；默认 1 主焦点，附属 0。"
        }
    }

    /**
     * 本轮社交交付预算（默认紧、按需松）：打「过度交付」，不硬死「永远只能 1 个动作」。
     * 写入 persona，与 [buildConversationPhaseSection] / [buildDeliveryBudgetPriority] 互补。
     */
    fun buildDeliveryBudgetRules(): String {
        return """
=== 本轮交付预算（默认紧、按需松）===
目标：像真人微信——靠多轮一来一回建关系，而不是一封邮件把共情、说教、方案、追问、推荐一次交完。

主焦点（每轮必有且通常只有一个）：
- 从菜单里选一项做主：接情绪 / 具体化追问 / 表态或吐槽 / 直接答问。
- 「共情 + 点出可能原因的一句追问」算同一个复合主焦点（例：今天好累 →「啧，听这语气又被项目折腾够呛？」），不要拆成两步再各写一长段。

附属（默认 0，有条件才 +1）：
- 建议/方案、推荐（泡脚听歌早睡揉肩太阳穴等）、连环说教、新话题、环境叮嘱、第二意图点名。
- 允许 +1 的条件：用户明确要怎么办/帮我想/你建议；一次丢多个重点需点名第二点；人设收束句本身；OPENING/CLOSING 的轻锚；安全或危机边界。
- 禁止默认打包：问好+共情+说教+方案+追问+推荐。闲聊未求方案时，优先接住或把话头扔回去，把延伸留给下一轮。

失败形态（必须避免）：
- 用户只说「今天好累」→ 禁止输出「骂熬夜 + 抱抱揉肩 + 躺五分钟 + 禁夜猫 + 洗头/泡脚」护理包。
- 禁止把关心写成任务清单；关心用语气，不靠多动作堆叠。

镜像前置：
- 前半句先回应用户表层情绪或表层疑问；深层分析、背景补充、延伸建议若出现，只能留一项且靠后。

不要：
- 不要用死板字数卡死（长度仍看表达约束的软硬目标）。
- 不要全局假装「永远只能一个动作」——任务轮、求方案、安全、多意图、人设复合收束可以超过纯单动作。
- 不要当解题机器结案；用户没催方案就别主动结案。
""".trimIndent()
    }

    private fun phaseUsageLine(phase: ConversationPhase): String {
        return when (phase) {
            ConversationPhase.OPENING ->
                "当前阶段 OPENING：是否主动提及时间/时段由角色性格决定，最多轻提一次，用户未问及时不要机械报时或派发固定关心任务。"
            ConversationPhase.TOPIC ->
                "当前阶段 TOPIC：时间仅背景；默认不要主动提及时间/早睡/日程；用户未问及时禁止机械报时或按时段派发关心。"
            ConversationPhase.CLOSING ->
                "当前阶段 CLOSING：时间仍仅供校准；可在收束句按人设轻提一次关心，不要反复叮嘱或机械报时。"
        }
    }

    // === private fun compressContext( ===
    internal fun compressContext(
        history: List<ChatMessage>,
        contextLimit: Int,
        companionNameMap: Map<Long, String> = emptyMap(),
        memoryContext: String = "",
        keepRatio: Float = 0.5f,
        minKeep: Int = 6
    ): CompressedContext {
        if (history.size <= contextLimit) {
            return CompressedContext("", history, 0)
        }

        val keepRecent = maxOf(minKeep, (contextLimit * keepRatio).toInt().coerceAtLeast(minKeep))
        val oldMessages = history.dropLast(keepRecent)
        val recentMessages = history.takeLast(keepRecent)

        val summary = buildLocalSummary(oldMessages, companionNameMap, memoryContext)

        return CompressedContext(summary, recentMessages, oldMessages.size)
    }

    // === private fun extractMemoryKeywords(memoryContext: String): Set<String> { ===
    internal fun extractMemoryKeywords(memoryContext: String): Set<String> {
        if (memoryContext.isBlank()) return emptySet()
        val keywords = mutableSetOf<String>()
        val coreSection = Regex("【核心记忆[^】]*】([\\s\\S]*?)(?=【|$)").find(memoryContext)?.groupValues?.get(1) ?: ""
        val relatedSection = Regex("【相关记忆[^】]*】([\\s\\S]*?)(?=【|$)").find(memoryContext)?.groupValues?.get(1) ?: ""

        listOf(coreSection, relatedSection).forEach { section ->
            section.lines().forEach { line ->
                val clean = line.trimStart('-', '[', ']', '【', '】', ' ').trim()
                if (clean.length in 2..30) {
                    keywords.add(clean.lowercase())
                    clean.split(Regex("[，。、；：！？\\s]")).filter { it.length >= 2 }.forEach { kw ->
                        keywords.add(kw.lowercase())
                    }
                }
            }
        }
        return keywords.filter { it.length >= 2 }.take(50).toSet()
    }

    // === private fun buildLocalSummary( ===
    /**
     * 本地叙事摘要回退：按「时间 / 事件 / 人物 / 驱动 / 情绪」组织。
     * 不硬限总字数；单条摘录可截断以避免噪声，但维度本身完整输出。
     */
    internal fun buildLocalSummary(
        messages: List<ChatMessage>,
        companionNameMap: Map<Long, String> = emptyMap(),
        memoryContext: String = ""
    ): String {
        if (messages.isEmpty()) return ""

        val memoryKeywords = extractMemoryKeywords(memoryContext)
        val now = System.currentTimeMillis()

        val highPriority = mutableListOf<Pair<Int, String>>()
        val emotionalMoments = mutableListOf<String>()
        val userMentions = mutableListOf<String>()
        val keyFacts = mutableListOf<String>()
        val otherTopics = mutableListOf<String>()
        val people = linkedSetOf<String>()

        val firstTs = messages.firstOrNull()?.timestamp ?: 0L
        val lastTs = messages.lastOrNull()?.timestamp ?: 0L

        messages.forEach { msg ->
            val role = if (msg.isFromUser) "用户" else (companionNameMap[msg.companionId] ?: "AI")
            people.add(role)
            val content = msg.content.trim()
                .replace(Regex("\\[.*?\\]"), "")
                .replace(Regex("（.*?）"), "")
                .trim()

            if (content.isBlank() || content.length < 3) return@forEach

            val contentLower = content.lowercase()

            val memoryRelevanceScore = memoryKeywords.count { keyword ->
                contentLower.contains(keyword) || keyword.contains(contentLower.take(4))
            }

            when {
                memoryRelevanceScore >= 2 -> {
                    highPriority.add(Pair(memoryRelevanceScore, "$role: $content"))
                }
                content.contains(Regex("(喜欢|爱|想|念|开心|难过|生气|害羞|感动|委屈|撒娇|哄|哭|笑|亲|抱|牵手|约会|见面)")) ||
                content.contains(Regex("(呜呜|嘿嘿|嘤|哼|呀|呢|啦|嘛|好想你|宝贝|宝宝|亲爱的)")) -> {
                    emotionalMoments.add("$role: $content")
                }
                content.contains(Regex("(叫|名字|年龄|生日|地址|电话|工作|学校|专业|记住|别忘了|以后|约定|答应|重要|一定|永远|承诺|计划|想要|希望)")) -> {
                    keyFacts.add("$role: $content")
                }
                else -> {
                    otherTopics.add("$role: $content")
                }
            }

            if (msg.isFromUser && userMentions.size < 8) {
                userMentions.add(content)
            }
        }

        val timeLine = buildString {
            if (firstTs > 0L && lastTs > 0L) {
                append("从 ${formatTimeAgo(now, firstTs)}前 到 ${formatTimeAgo(now, lastTs)}前")
                val spanMin = ((lastTs - firstTs) / 60000L).coerceAtLeast(0)
                if (spanMin > 0) append("（跨度约${spanMin}分钟）")
            } else {
                append("未明确")
            }
            append("；共压缩 ${messages.size} 条消息")
        }

        val eventParts = mutableListOf<String>()
        highPriority.sortedByDescending { it.first }.take(6).forEach { (_, text) ->
            eventParts.add("★ $text")
        }
        userMentions.take(6).forEach { text ->
            if (eventParts.none { it.contains(text.take(12)) }) {
                eventParts.add(text)
            }
        }
        otherTopics.take(5).forEach { text ->
            if (eventParts.none { it.contains(text.take(12)) }) {
                eventParts.add(text)
            }
        }

        val driveParts = keyFacts.take(6).ifEmpty {
            highPriority.sortedByDescending { it.first }.take(3).map { it.second }
        }

        val emotionParts = emotionalMoments.take(6)

        return buildString {
            appendLine("=== 早期对话摘要（已压缩${messages.size}条消息） ===")
            appendLine("时间：$timeLine")
            appendLine(
                "事件：" + if (eventParts.isNotEmpty()) {
                    eventParts.joinToString("；")
                } else "未明确"
            )
            appendLine(
                "人物：" + if (people.isNotEmpty()) people.joinToString("、") else "未明确"
            )
            appendLine(
                "驱动：" + if (driveParts.isNotEmpty()) {
                    driveParts.joinToString("；")
                } else "未明确"
            )
            appendLine(
                "情绪：" + if (emotionParts.isNotEmpty()) {
                    emotionParts.joinToString("；")
                } else "未明确"
            )
            if (memoryContext.isNotBlank() && memoryKeywords.isNotEmpty()) {
                appendLine("（注：基于已有${memoryKeywords.size}条记忆关键词筛选；与核心/相关记忆重叠处已标★）")
            }
        }.trim()
    }

}
