package com.yunian.ai.feature.qqbot.data

import com.yunian.ai.domain.DialogueResult
import com.yunian.ai.domain.dialogue.DialogueOutputEvent

/**
 * QQ 通道的出站投影（P3-3c）：把中间层的回合结果投影成「本通道实际要发的一条文本」。
 *
 * ## 只决定「发什么」，不决定「怎么发、什么时候发」
 *
 * 投影结果交回 [QQBotChatBridge] 既有的 [QQBotSentenceSplitter.split] + 500ms 节流 +
 * `sendTextMessage` 链路，因此分段数、发送间隔、typing 时序、生图顺序与改动前逐字相同。
 *
 * ## 消费判定（**防双发**的唯一入口**）
 *
 * - `turn == null` → 回退 [DialogueResult.replyText]（与改动前逐字一致）；
 * - `turn != null` → **只**消费 `turn.events`；`replyText` 在该分支上不可达。
 *
 * 两者由 [resolveSendText] 的 `?:` 严格互斥：桥接层拿到的就是唯一待发文本，
 * 结构上不存在「events 发一遍、replyText 再发一遍」的路径。
 *
 * ## 逐事件映射（QQ 能力下的诚实投影）
 *
 * | 事件 | 本通道动作 |
 * |---|---|
 * | [DialogueOutputEvent.Bubble] | 发文本（走既有分段 + 节流 + sendTextMessage 链路） |
 * | [DialogueOutputEvent.Sticker] | 发文本等价形式 `[label]`（本通道**无**表情通路） |
 * | [DialogueOutputEvent.Notice] | 发文本（宿主自撰提示，与气泡同路） |
 *
 * Sticker 退化为 `[label]` 不是保守取值，而是**已核实的事实**：
 * `QQBotChannelAdapter.CAPABILITIES.stickers = false`（「feature:qqbot 全模块无
 * StickerManager 引用，无表情通路」），`QQBotMessageRepository` 只暴露
 * `sendTextMessage` / `sendImageMessage` 两个出站口。
 * [DialogueOutputEvent.Sticker.text] 的定义就是 `"[label]"`，且与
 * `AgentTurnReplyText.resolve` 写进 `replyText` 的字面量逐字相同，
 * 因此「走事件」与「走旧 replyText」在本通道产出完全一致的内容。
 */
internal object QQBotOutboundProjection {

    /**
     * 单个事件在本通道的文本投影（顺序即产出顺序）。
     *
     * 写成显式 `when` 而不是一句 `event.text`：三种事件「本通道发什么」是本次改动的
     * 决策表，必须留在代码里可读、可审（Sticker 的退化判定尤其不能藏在注释里）。
     */
    fun textOf(event: DialogueOutputEvent): String = when (event) {
        // 气泡与宿主自撰提示：都是可直接发送的文本，走同一条文本链路。
        is DialogueOutputEvent.Bubble, is DialogueOutputEvent.Notice -> event.text

        // 表情：本通道无表情通路 → 退化为文本等价形式 "[label]"。
        is DialogueOutputEvent.Sticker -> event.text
    }

    /**
     * 事件流 → 一条待发文本。
     *
     * 连接符 `"\n"` 与 `AgentTurnReplyText.resolve` 逐字相同；而 `'\n'` 本身是
     * [QQBotSentenceSplitter.split] 的分隔符，所以「先连接再分段」与改动前「直接分段 replyText」
     * 得到完全相同的分段序列（分段数、每段内容、节流次数都不变）。
     */
    fun textOf(events: List<DialogueOutputEvent>): String =
        events.map { textOf(it) }
            .filter { it.isNotBlank() }
            .joinToString("\n")

    /**
     * **消费判定（防双发）**：`turn` 非空时只走 `events`，`replyText` 在 `?:` 左侧不可达。
     *
     * 空事件流是合法的（`DialogueTurnMapper` 只在 `cleanedReplyText` 为空 / 不安全时
     * 才产出空快照），此时返回空串，桥接层照旧走既有的 blank 早返回 —— 与改动前一致。
     */
    fun resolveSendText(result: DialogueResult): String =
        result.turn?.let { textOf(it.events) } ?: result.replyText
}
