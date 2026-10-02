package com.yunian.ai.feature.wechat.data

import com.yunian.ai.domain.dialogue.DialogueOutputEvent
import com.yunian.ai.domain.dialogue.DialogueTurnSnapshot

/**
 * 微信通道的回合投影（P3-3c）：把中间层的回合结果投影成
 * 「待清洗 + 发送的文本」与「表情标签」两件事。
 *
 * ## 消费判定（**防双发**的唯一入口**）
 *
 * - `turn == null` → 回退 `replyText`，且 [eventStickerLabels] 为 `null`：
 *   桥接层走**改动前逐字一致**的旧路径（表情标签由文本正则发现）；
 * - `turn != null` → 文本来源换成 `events`，表情标签**只**来自
 *   [DialogueOutputEvent.Sticker] 事件，`replyText` 在该分支上不可达。
 *
 * 两者由 [of] 的 `if` 严格互斥，不存在「events 发一遍、replyText 再发一遍」的路径。
 *
 * ## 为什么文本也要换成 events
 *
 * 硬约束是「turn 非空时只消费 events」。若文本仍取 `replyText` 而只把表情换成事件，
 * 就等于同时消费了两者。好在这不会改变任何内容：契约里
 * [DialogueOutputEvent.text] 就是该片段在纯文本通道上的等价表示，且
 * `AgentTurnReplyText.resolve` 正是用 `"\n"` 连接同一批片段产出 `replyText` 的，
 * 所以「events 连接」与「replyText」在 turn 非空时逐字相同（唯一差别是被输出安全过滤
 * 丢弃的片段——那正是结构化契约存在的意义）。
 *
 * ## eventStickerLabels 为什么是 nullable 而不是 emptyList
 *
 * `null` 与 `emptyList()` 语义不同，必须区分：
 * - `null` = 没有结构化快照 → 走旧正则路径（含「文本里找不到标签就按规则随机配一个」的兜底）；
 * - `emptyList()` = 有快照且本轮**没有**表情事件 → 不发任何表情，且旧兜底必须关闭
 *   （否则会发出模型从未要求过的表情）。
 */
internal data class WeChatTurnProjection(
    /** 待走既有清洗 / outbox / 图片镜像路径的文本来源。 */
    val sourceText: String,

    /**
     * 结构化快照给出的表情标签；`null` = 无快照（回退路径，表情仍由文本正则发现）。
     */
    val eventStickerLabels: List<String>?,

    /** 是否来自结构化快照（`false` = 回退 `replyText` 的旧路径）。 */
    val fromTurn: Boolean,
) {
    companion object {
        fun of(replyText: String, turn: DialogueTurnSnapshot?): WeChatTurnProjection {
            if (turn == null) {
                return WeChatTurnProjection(
                    sourceText = replyText,
                    eventStickerLabels = null,
                    fromTurn = false,
                )
            }
            return WeChatTurnProjection(
                sourceText = turn.events.joinToString("\n") { it.text },
                eventStickerLabels = turn.events
                    .filterIsInstance<DialogueOutputEvent.Sticker>()
                    .map { it.label }
                    .distinct(),
                fromTurn = true,
            )
        }
    }
}
