package com.yunian.ai.agent

import com.yunian.ai.agent.uniffi.AgentEvent

/**
 * 通道不支持气泡/表情事件时的文本适配；不重复追加已作为 bubble 发出的 finalText。
 *
 * 可见性说明：本对象**必须 public**——群聊侧（`feature:groupchat` 的 `GroupChatViewModel`）
 * 在 confirm_pending 终局也要用它兜出可见文案，以保证与通道侧逐字一致；Kotlin 的 `internal`
 * 只在模块内可见，跨模块无法复用（与 [AgentConfirmGuardLog] 同一手法）。它不是对外契约的一部分。
 */
object AgentTurnReplyText {
    fun resolve(events: List<AgentEvent>, finalText: String, finishedReason: String): String? {
        val visible = events.mapNotNull { event ->
            when (event.kind) {
                "bubble" -> event.text.takeIf { it.isNotBlank() }
                "sticker" -> event.text.takeIf { it.isNotBlank() }?.let { "[$it]" }
                else -> null
            }
        }.joinToString("\n")
        if (finishedReason == "confirm_pending") {
            // 兜底：通道侧确认门会先自动拒绝并重跑；走到这里说明仍无法完成本轮，
            // 必须给用户一个明确交代，不能静默。
            return listOf(visible, "这一步需要你在 App 内确认后才能执行（本次未执行）。").filter { it.isNotBlank() }.joinToString("\n")
        }
        return visible.ifBlank { finalText }.takeIf { it.isNotBlank() }
    }
}
