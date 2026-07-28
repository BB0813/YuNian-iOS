package com.lianyu.ai.common

/**
 * 环境关心（时间/睡/吃/到家）冷却的纯逻辑。
 * 无 Android 依赖，便于单测；持久化见 [EnvAnchorStore]。
 */
object EnvAnchorCooldown {

    /**
     * 是否仍在冷却窗内。
     * [lastAtMs] <= 0 表示从未记过锚点。
     */
    fun isCoolingDown(
        lastAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        cooldownMs: Long = ChatConstants.ENV_ANCHOR_COOLDOWN_MS,
    ): Boolean {
        if (lastAtMs <= 0L) return false
        if (cooldownMs <= 0L) return false
        return nowMs - lastAtMs < cooldownMs
    }

    /**
     * 文本是否像「环境/生活关心」类（睡、吃、到家、报时催促等）。
     * 用于：判定是否应写入 lastEnvAnchorAt，以及最近 AI 是否已提过同类。
     */
    fun looksLikeEnvCare(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false

        val keywords = listOf(
            // 睡 / 休息
            "睡", "早点休息", "早休息", "别熬夜", "熬夜", "还不睡", "该睡了",
            "去睡", "睡觉", "晚安", "好困", "休息吧", "别太晚",
            // 吃
            "吃饭", "吃了没", "吃过了", "记得吃饭", "有没有吃饭", "饿不饿",
            "午饭", "晚饭", "早餐", "吃点东西", "别空腹",
            // 到家 / 出行
            "到家", "回家了", "在路上", "出门了没", "安全到家", "到了吗",
            // 机械报时 / 时段任务感
            "现在都", "都几点了", "这么晚了", "这么早", "注意身体", "多喝水",
            "天气", "降温", "加衣服",
        )
        return keywords.any { t.contains(it) }
    }

    /**
     * 最近若干条 AI 文本是否已含环境关心。
     */
    fun recentAiHasEnvCare(
        aiTexts: Iterable<String>,
        lookback: Int = ChatConstants.ENV_ANCHOR_RECENT_LOOKBACK,
    ): Boolean {
        return aiTexts
            .asSequence()
            .take(lookback.coerceAtLeast(0))
            .any { looksLikeEnvCare(it) }
    }

    /**
     * 是否允许本轮主动环境锚点。
     * DataStore 冷却 或 最近 AI 已提过 → 禁止。
     */
    fun allowEnvAnchor(
        lastAtMs: Long,
        recentAiTexts: Iterable<String>,
        nowMs: Long = System.currentTimeMillis(),
        cooldownMs: Long = ChatConstants.ENV_ANCHOR_COOLDOWN_MS,
    ): Boolean {
        if (isCoolingDown(lastAtMs, nowMs, cooldownMs)) return false
        if (recentAiHasEnvCare(recentAiTexts)) return false
        return true
    }

    /**
     * 写入 prompt 的冷却指令。
     * [allowEnvAnchor]=true 时返回空串（不额外打扰）。
     */
    fun buildCooldownDirective(allowEnvAnchor: Boolean): String {
        if (allowEnvAnchor) return ""
        return """
=== 环境关心冷却中 ===
- 本轮禁止主动提时间/时段、催睡、问吃了没、问到家了没、念天气日程等同类关心。
- 只跟用户话题或按人设轻聊；用户明确问时间/日期时再答。
- 禁止把同一关心当背景音乐再扫一遍。
""".trimIndent()
    }

    /**
     * 主动消息专用：在时间上下文后追加的硬约束。
     */
    fun buildProactiveEnvPolicy(allowEnvAnchor: Boolean): String {
        return if (allowEnvAnchor) {
            """
主动环境策略：本条相当于 OPENING/重连，环境信息最多轻提一次，也可按人设完全不提。
若决定提，只一句带过，不要展开成催睡/问吃/到家任务清单。
""".trimIndent()
        } else {
            """
主动环境策略：环境关心冷却中或最近已提过同类。
本轮禁止再提睡/吃/到家/报时/天气关心；只做话题延续或情绪轻触，不要扫深夜安全机制。
""".trimIndent()
        }
    }
}
