package com.yunian.ai.domain

import com.yunian.ai.domain.dialogue.DialogueTurnSnapshot

/**
 * 统一 AI 对话中间层（通道桥接层 ↔ Agent 核心）。
 *
 * 微信 / QQ 等外部通道的桥接层只负责消息收发（监听、映射、分段发送等通道特有逻辑）；
 * AI 回合、安全检查、落库、记忆提取、亲密度更新等全部收敛到此接口，
 * 由 core:agent 实现（[com.yunian.ai.agent.AgentDialogueCoordinator]）并经
 * [ServiceRegistry] 注入，避免各 feature 通道模块依赖 AI 管线。
 *
 * 落库语义：本接口负责「用户消息 + AI 回复」的入库（通道消息来源外部，
 * 必须落库形成对话上下文）；app 内对话（UI / 通知 / 语音条）已由调用方落库，
 * 不走本接口。
 */
interface DialogueCoordinator {

    /**
     * 生成一轮对话回复（文本或视觉）。
     *
     * 内部流程（对齐「决策在 Rust」）：封禁检查 → 输入安全过滤 → 落库用户消息
     * → 读历史 → AgentRuntime.run_turn（含 syncRuntimeConfig）→ 输出安全过滤
     * → 落库 AI 回复 → 记忆提取 / 亲密度更新。
     *
     * @return [DialogueResult.replyText] 为可直接发送的回复文本（blocked 时亦可能
     *         携带安全话术），由桥接层负责通道发送。
     *         [DialogueResult.turn] 为同一轮的结构化投影（可选，尾部新增）：
     *         只支持文本的调用方继续用 [DialogueResult.replyText]，行为不变。
     */
    suspend fun generateReply(request: DialogueRequest): DialogueResult
}

/** 中间层对话请求。[text] 与 [imagePath] 至少一个非空。 */
data class DialogueRequest(
    /** 目标伴侣 ID */
    val companionId: Long,
    /** 文本消息内容 */
    val text: String? = null,
    /** 图片本地路径（视觉链路，走 run_turn 的 image 输入） */
    val imagePath: String? = null,
    /**
     * 调用方通道标识（取值见 [ChannelKeys]）——**通道自身的身份，不参与授权判定**。
     *
     * 通道维度的**唯一来源是调用方**：本请求自身无法从 companionId / 文本里推断出消息是从
     * QQ 来的还是从微信来的，因此每个桥接层必须显式声明自己的通道。
     * 用途：通道侧日志 / 观测，以及 P3 的通道插件化（每个通道插件显式声明自己是谁）。
     *
     * 工具授权（[CapabilityGrantStore]）**只按 (伴侣 × 工具) 命中**，装配期折叠在
     * `AgentFacade.toolDefinitionsFor`：同一条授权在 App 内单聊、群聊、QQ、微信上
     * 得到完全相同的结果；通道之间的差异只剩「有没有确认界面」。
     *
     * 默认值 [ChannelKeys.UNSPECIFIED] 表示「调用方没声明自己是谁」，
     * 便于观测时把它与真实通道区分开。新增通道请显式传入自己的 [ChannelKeys] 常量。
     */
    val channelKey: String = ChannelKeys.UNSPECIFIED,
)

/** 中间层对话结果。 */
data class DialogueResult(
    /** 可直接发送的回复文本 */
    val replyText: String,
    /** 是否被安全机制拦截（违规/封禁），桥接层可据此决定发送策略 */
    val blocked: Boolean = false,
    /** 已落库的 AI 回复消息 ID（未落库时为 null） */
    val assistantMessageId: Long? = null,

    /**
     * 本轮 Agent 回合的**结构化投影**（尾部新增，可选；契约见
     * [com.yunian.ai.domain.dialogue.DialogueTurnSnapshot]）。
     *
     * - 快照里的每段文本都是**已清洗 + 已过滤**的输出，可直接发送；
     * - `null` 表示本轮没有结构化输出（回合没跑起来 / 失败 / 被安全拦截）——
     *   此时调用方必须回退到 [replyText]（与本次改动前的行为完全一致）。
     *
     * 兼容性：本字段追加在末尾且带默认值，前三个字段与 [DialogueCoordinator.generateReply]
     * 的签名逐字未变，既有调用方无需改动。
     */
    val turn: DialogueTurnSnapshot? = null,
)
