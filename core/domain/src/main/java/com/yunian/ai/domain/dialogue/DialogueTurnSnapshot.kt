package com.yunian.ai.domain.dialogue

/**
 * 一轮 Agent 回合的**结构化输出契约**（P3-3b，通道投影的数据面）。
 *
 * 背景（已核实）：Agent 的回合事件流**本来就**跨 UniFFI 回到了 Kotlin
 * （`AgentEvent(kind, text, extra)`，见 `lianyu_agent.kt:7650-7659`；
 * `AgentTurnResult.events` 的反序列化见 `:7867-7892`），但通道侧拿到的只有
 * `AgentTurnReplyText.resolve(...)` 压平后的一个 String——表情包还得靠正则从文本里再抠回来
 * （`WeChatDialoguePortImpl.extractStickerLabels`）。压平点太早，通道没有可投影之物。
 *
 * 本包提供**通道可消费的稳定领域协议**：把「原生事件」翻译成「领域输出事件」，
 * 由 `core:agent` 的 `DialogueTurnMapper` 在 **AgentConfirmGuard 之后、ImageGenProtocol 清洗
 * 与输出 ContentFilter 之后**产出（详见映射器的安全论证）。
 *
 * 三条不变量（映射器保证，`DialogueTurnMapperTest` 钉死）：
 * 1. [DialogueTurnSnapshot.events] 中每一段文本都是**已清洗 + 已过滤**的输出，
 *    **绝不携带原始 `AgentEvent.text`**；
 * 2. 内部事件（`usage` / `reasoning` / `confirm_request` 以及任何未知 kind）的内容、
 *    以及**所有 `extra`**（`entry_id=…;file_name=…`、`companion_id/group_id`、工具参数 JSON 等），
 *    一律不进入本契约的任何字段，只保留一个计数
 *    （[DialogueTurnSnapshot.droppedInternalEventCount]，供观测，禁止展示）；
 * 3. 被安全拦截的回合（blocked）**不产出快照**（`DialogueResult.turn == null`），
 *    调用方继续按旧行为发送 `DialogueResult.replyText` 里的安全话术。
 *
 * 依赖约束：本文件只依赖 Kotlin 标准库——不得引入 uniffi / Android / 第三方 JSON 类型。
 */

/**
 * 一轮 Agent 回合的终局原因（**对外稳定取值**）。
 *
 * 取值来源（已核实，`agent-native/src/agent.rs`）：`completed`(:1460)、
 * `max_rounds`(:1458 length 与 :1475 轮数上限)、`confirm_pending`(:1288)、
 * `state_stop`(:1102)、`error`(:1202/:1214/:1421/:1442)、
 * `max_tool_calls`(:1326)、`max_text`(:1453)。
 *
 * 原生注释只声明了 5 种（`lianyu_agent.kt:7872-7873`），Rust 实际多产
 * `max_tool_calls` / `max_text` 两种，故一并显式建模；
 * 未识别的取值折叠为 [UNKNOWN]，既不崩溃也不把原生字符串直接外泄。
 */
enum class DialogueCompletion {
    /** 正常结束（模型给出正文并收束）。 */
    COMPLETED,

    /** 达到最大轮数 / 模型 finish_reason=length 截断。 */
    MAX_ROUNDS,

    /** 回合在确认门前提前结束，等待 App 内确认（无确认界面的通道由守卫自动拒绝后重跑）。 */
    CONFIRM_PENDING,

    /** 状态机主动停止（`TurnAction::Stop`）。 */
    STATE_STOP,

    /** 回合失败（模型 / 网络 / 解析错误）。 */
    ERROR,

    /** 工具调用次数达上限。 */
    MAX_TOOL_CALLS,

    /** 单回合气泡文本字符数达上限。 */
    MAX_TEXT,

    /** 未识别的原因（Rust 新增取值时的安全落点）。 */
    UNKNOWN;

    companion object {
        /** 原生 `AgentTurnResult.finished_reason` → 稳定领域取值。 */
        fun fromFinishedReason(finishedReason: String): DialogueCompletion = when (finishedReason) {
            "completed" -> COMPLETED
            "max_rounds" -> MAX_ROUNDS
            "confirm_pending" -> CONFIRM_PENDING
            "state_stop" -> STATE_STOP
            "error" -> ERROR
            "max_tool_calls" -> MAX_TOOL_CALLS
            "max_text" -> MAX_TEXT
            else -> UNKNOWN
        }
    }
}

/**
 * 一轮回合里**可对外发送**的输出片段。
 *
 * [text] 是该片段在纯文本通道上的等价表示（[Sticker] 为 `[标签]`，与
 * `AgentTurnReplyText.resolve` 的文本兜底逐字一致），保证「只支持文本的通道」拿到的东西
 * 与改动前完全相同。
 */
sealed interface DialogueOutputEvent {

    /** 可直接展示 / 发送的文本。 */
    val text: String

    /** 模型文本气泡。 */
    data class Bubble(override val text: String) : DialogueOutputEvent

    /**
     * 表情包。
     *
     * 只携带**标签**：原生 `extra` 的 `entry_id=…;file_name=…`（`agent.rs:440-443`）
     * 与 `companion_id/group_id`（`agent.rs:465`）都不是稳定领域协议，不进契约；
     * 表情落地继续走各通道既有的标签匹配链路（微信 = `WeChatDialogueResult.stickerLabels`）。
     */
    data class Sticker(val label: String) : DialogueOutputEvent {
        override val text: String get() = "[$label]"
    }

    /**
     * 宿主自撰提示（非模型正文），目前只有 `confirm_pending` 终局的兜底交代。
     *
     * 与安全拦截话术同类：由宿主写死、不含模型原始输出，因此不参与输出内容过滤。
     */
    data class Notice(override val text: String) : DialogueOutputEvent
}

/**
 * 一轮 Agent 回合的结构化投影（`com.yunian.ai.domain.DialogueResult.turn` 的取值类型）。
 *
 * `null` 与「空快照」语义不同：
 * - `turn == null`：本轮**没有**结构化输出（回合没跑起来 / 失败 / 被安全拦截），
 *   调用方必须回退到 `DialogueResult.replyText`（旧行为）；
 * - `events.isEmpty()`：回合跑了但没有可见输出（例如模型只输出了画面描述）。
 */
data class DialogueTurnSnapshot(
    /** 按产出顺序排列的可见输出片段（已清洗、已过滤）。 */
    val events: List<DialogueOutputEvent> = emptyList(),

    /** 本轮终局原因。 */
    val completion: DialogueCompletion = DialogueCompletion.UNKNOWN,

    /**
     * 未进入 [events] 的事件数（内部 kind / 未知 kind / 未通过输出过滤的片段）。
     *
     * **仅计数、不含任何内容**，供通道侧观测与排障，禁止当作内容展示或外发。
     */
    val droppedInternalEventCount: Int = 0,
) {
    init {
        require(droppedInternalEventCount >= 0) { "droppedInternalEventCount must be >= 0" }
    }
}
