package com.yunian.ai.agent

import com.yunian.ai.agent.uniffi.AgentTurnResult
import com.yunian.ai.common.ContentFilter
import com.yunian.ai.domain.dialogue.DialogueCompletion
import com.yunian.ai.domain.dialogue.DialogueOutputEvent
import com.yunian.ai.domain.dialogue.DialogueTurnSnapshot
import com.yunian.ai.domain.imagegen.ImageGenProtocol

/**
 * 结构化回合投影（P3-3b）：把 Rust 回合的 [AgentTurnResult] 翻译成领域契约
 * [DialogueTurnSnapshot]，供通道侧按自身能力投影（P3-3c 消费）。
 *
 * ## 调用位置（安全论证的前提）
 *
 * 调用方 `AgentDialogueCoordinator` **只允许**在本轮文本走完以下两步之后调用本对象：
 * 1. `agentConfirmGuard.drive(...)` —— 确认门守卫已收束（拿到的就是终局 result）；
 * 2. `sanitizeImageGen(...)`（ImageGenProtocol 画面描述清洗）+
 *    `ContentFilter.checkOutputSafety(...)`（输出安全过滤）。
 *
 * 因此 `project` 收到的 `cleanedReplyText` 就是**已清洗 + 已过滤**的外发文本，而
 * [project] 自己又对每一段进入契约的文本再跑一遍同一套清洗与过滤，
 * 于是「只过滤 replyText、却把原始 `event.text` 附进 DTO」这条泄漏路径在结构上不成立：
 * 任何一段没通过 `sanitizeForDisplay` 或 `isOutputSafe` 的文本都会被丢弃（只计数，不留内容）。
 *
 * ## 丢弃策略（白名单，不是黑名单）
 *
 * - 只有 `kind == "bubble"` / `"sticker"` 两种原生事件能变成可见输出；
 * - 其余一切 kind（`usage` / `reasoning` / `confirm_request` / `status` 以及未知 kind）
 *   一律丢弃，只累加 [DialogueTurnSnapshot.droppedInternalEventCount]；
 * - 所有 `extra`（sticker 的 `entry_id=…;file_name=…`、`companion_id/group_id`、
 *   confirm_request 的工具参数 JSON）**一律不读**——它们不是稳定领域协议，也可能是内部细节。
 *
 * ## 文本兜底（与改动前逐字一致）
 *
 * 事件流里没有任何可见片段时（Rust 异常路径），回退到调用方传进来的
 * `cleanedReplyText`（= `AgentTurnReplyText.resolve` 的产物，已清洗 + 已过滤）作为单条气泡；
 * 已经有可见片段时**绝不**再追加它，因此「bubble 与 finalText 相同」不会重复。
 */
object DialogueTurnMapper {

    /** 原生文本气泡事件 kind（`agent.rs:370` / `:556` / `:1403`，三处 `extra` 恒为空串）。 */
    const val EVENT_BUBBLE: String = "bubble"

    /** 原生表情包事件 kind（`agent.rs:438` / `:463`）。 */
    const val EVENT_STICKER: String = "sticker"

    /**
     * `confirm_pending` 终局的兜底交代文案。
     *
     * 与 [AgentTurnReplyText.resolve] 里那一句**必须逐字相同**（同一句在两个地方出现，
     * 由 `DialogueTurnMapperTest` 直接比对两者，任何一边改动都会红）。
     */
    const val CONFIRM_PENDING_NOTICE: String = "这一步需要你在 App 内确认后才能执行（本次未执行）。"

    /**
     * 把一轮终局结果投影成领域快照。
     *
     * @param result 守卫收束后的终局结果（`AgentConfirmGuard.drive` 之后）。
     * @param cleanedReplyText 本轮最终外发文本（已过 ImageGenProtocol 清洗与输出安全过滤）。
     * @param sanitizeForDisplay 单段文本清洗（默认 ImageGenProtocol；测试可注入替身）。
     * @param isOutputSafe 单段文本输出安全判定（默认 ContentFilter；测试可注入替身——
     *   真实实现底层走 `android.util.Log`，在纯 JVM 单测里是抛 `Stub!` 的空壳）。
     */
    fun project(
        result: AgentTurnResult,
        cleanedReplyText: String,
        sanitizeForDisplay: (String) -> String = ImageGenProtocol::sanitizeForDisplay,
        isOutputSafe: (String) -> Boolean = { ContentFilter.checkOutputSafety(it).isSafe },
    ): DialogueTurnSnapshot {
        val completion = DialogueCompletion.fromFinishedReason(result.finishedReason)
        val events = mutableListOf<DialogueOutputEvent>()
        var dropped = 0

        for (event in result.events) {
            when (event.kind) {
                EVENT_BUBBLE -> {
                    // 逐条清洗：画面描述（[[生图: …]] / （画面：…））绝不能随气泡进契约。
                    val text = sanitizeForDisplay(event.text)
                    if (text.isBlank() || !isOutputSafe(text)) {
                        dropped += 1
                        continue
                    }
                    events += DialogueOutputEvent.Bubble(text)
                }

                EVENT_STICKER -> {
                    // 标签即 event.text；extra 里的 entry_id / file_name 不读（非稳定协议）。
                    val label = event.text.trim()
                    if (label.isEmpty()) {
                        dropped += 1
                        continue
                    }
                    val piece = DialogueOutputEvent.Sticker(label)
                    if (!isOutputSafe(piece.text)) {
                        dropped += 1
                        continue
                    }
                    events += piece
                }

                else -> dropped += 1
            }
        }

        if (completion == DialogueCompletion.CONFIRM_PENDING) {
            events += DialogueOutputEvent.Notice(CONFIRM_PENDING_NOTICE)
        }

        // 文本兜底：仅当事件流完全没有可见片段时才用外发文本补一条（防重复，见类注释）。
        // 这里同样过一遍 isOutputSafe：即使调用方误传了未过滤文本，也不会进入契约。
        if (events.isEmpty() && cleanedReplyText.isNotBlank() && isOutputSafe(cleanedReplyText)) {
            events += DialogueOutputEvent.Bubble(cleanedReplyText)
        }

        return DialogueTurnSnapshot(
            events = events,
            completion = completion,
            droppedInternalEventCount = dropped,
        )
    }
}
