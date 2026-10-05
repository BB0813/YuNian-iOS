import Foundation
import os
import Combine   // ObservableObject / @Published —— 不依赖 SwiftUI 的传递导入

/// 一次对话会话的状态与回合驱动。
///
/// ## 线程模型（关键，来自 Rust 侧审计）
/// `runTurnStream` 是**阻塞**调用：内部做阻塞 HTTP、`sleep` 退避，并有回合级全局 `Mutex`。
/// 因此本类把它提交到 `AgentHostThreading.turnQueue`，用 continuation 桥回 `async`。
/// 主线程只消费 `AsyncStream` 的增量。
///
/// ## 死锁防护
/// 若 Rust 因非流式降级或异常路径**没有**回调 `onDone` / `onError`，
/// `AsyncStream` 就不会结束，`await consumer.value` 会永久挂起。
/// 因此阻塞调用返回后**强制收束** sink（`closeAll()`），这是必须的，不是冗余。
@MainActor
final class ChatSession: ObservableObject {

    /// 会话内的一条消息（仅用于展示；发给 Rust 的历史是 `AgentHistoryMessage`）。
    struct Message: Identifiable, Equatable {
        let id = UUID()
        let role: AgentHistoryRole
        var text: String
    }

    @Published private(set) var messages: [Message] = []
    @Published private(set) var streamingText: String = ""
    @Published private(set) var reasoningText: String = ""
    @Published private(set) var isRunning = false
    @Published private(set) var lastError: String?
    @Published private(set) var lastRoundsUsed: UInt32 = 0

    /// 当前会话绑定的伴侣。
    ///
    /// Rust 的 `load_companion` 按这个 id 读 `companions` 表拿到人设；
    /// nil 时回合仍能跑，但没有伴侣人设（Rust 侧人设注入会缺失）。
    var companionId: Int64?

    private let log = Logger(subsystem: "com.yunian.ai", category: "chat")

    init(companionId: Int64? = nil) {
        self.companionId = companionId
    }

    /// 发送一条用户消息并驱动一轮 Agent 回合。
    func send(_ text: String, in environment: AppEnvironment) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !isRunning else { return }
        guard let runtime = environment.runtime else {
            lastError = "Agent 未就绪"
            return
        }

        let sink = environment.streamSink
        let toolHost = environment.toolHost
        let companionId = self.companionId

        // 输入安全检查门：对应 Android `startSendMessage` 的那一段。
        // ⚠️ 它**不是**每回合无条件执行的 —— 仅在「全局无启用配置 && 当前伴侣无可用绑定」时
        // 才检查（详见 ChatInputGuard 的注释）。按直觉改成每回合检查会造成跨端不一致。
        if let database = environment.database {
            let activeEnabled = (try? environment.apiConfigs?.activeConfig()) != nil
            switch ChatInputGuard.evaluate(
                text: trimmed, database: database,
                companionId: companionId ?? 0, activeEnabled: activeEnabled
            ) {
            case .blocked(let reason):
                lastError = "内容违规: \(reason)"
                return
            case .checkFailed:
                lastError = "安全检查异常"
                return
            case .needsCheck:
                // Android 在这一支的末尾报「请先配置API」（先安全、后引导）
                lastError = "请先配置API：我 → API设置 → 添加密钥"
                return
            case .notChecked:
                break
            }
        }

        messages.append(Message(role: .user, text: trimmed))
        streamingText = ""
        reasoningText = ""
        lastError = nil
        isRunning = true
        defer { isRunning = false }

        // 对应 Android `AgentDialogueCoordinator` 在构造请求前的 `syncRuntimeConfig()`：
        // 每回合都重新同步 settings / credentials / stickers。
        // `AgentRuntime.setWorldbook` 是覆盖式注入，故世界书也必须每回合同步
        // （Android 侧由 `WorldbookRepository.syncActiveToRuntime` 负责，此前 iOS 漏了）。
        environment.syncRuntimeConfig()
        if let database = environment.database {
            WorldbookRuntimeSync.syncActiveToRuntime(
                runtime, database: database, companionId: companionId ?? 0
            )
        }

        let request = AgentTurnRequest(
            groupId: nil,
            historyJson: AgentRequestBuilder.encodeHistory(historyForRequest()),
            // ⚠️ **已知功能性缺口：iOS 侧没有任何 session/global 工具。**
            //
            // Android 主对话路径（`AgentDialogueCoordinator.kt`）传的是真实工具列表：
            // ```kotlin
            // tools = (AgentFacade.memoryToolDefinitions(context) +
            //     AgentFacade.skillToolDefinitions() +
            //     AgentFacade.toolDefinitionsFor(companionId, ToolRegistry.availableTools()))
            //     .distinctBy { it.name }
            // ```
            //
            // ## ⚠️ 但要注意：这不等于「模型看不到任何工具」
            // Rust `agent.rs::available_tool_definitions` 的拼接顺序是
            // `builtin → global → request.tools → core_plugin`，
            // **内置工具（send_sticker / emit_bubble / emit_segmented）不受
            // `request.tools` 门控，永远会注入模型**。
            // 因此 iOS 上模型**能**看到并调用这三个内置工具 ——
            // 缺的是记忆/技能/领域工具，以及 `send_sticker` 依赖的表情标签表。
            // （把这两件事分清很重要：否则会误判「反正没工具，stickers 也不用管」。）
            //
            // 影响面：
            //   - 模型无法主动读写记忆、调用技能、查询用户资料
            //   - `send_sticker` 会因为 `stickers` 为空而匹配不到标签
            //
            // 补全路径：按 Android 的三段式实现 `ToolDefinition` 组装，
            // 并经 `AgentToolHost` 提供调用实现。
            // 工具定义：记忆工具（Rust 提供）+ load_skill。
            //
            // ⚠️ 这不是可选项：`prompt_orchestrator.rs:270` 的技能目录菜单注入
            // 以 `available_tools` 含 `load_skill` 为前提；不加它，模型永远看不到技能目录。
            // 另注意内置工具（send_sticker 等）不受此字段门控、恒注入。
            // 领域工具（ToolRegistry）尚未移植，属已记录缺口。
            tools: AgentToolCatalog.definitions,
            maxRounds: AgentRequestBuilder.defaultMaxRounds,
            toolChoice: "auto",
            stickerProbability: 0,
            image: nil,
            systemPrompt: nil,
            companionNameMapJson: nil
        )

        let stream = sink.makeStream()

        let consumer = Task { @MainActor [weak self] in
            for await event in stream {
                guard let self else { return }
                switch event {
                case let .textDelta(delta):
                    self.streamingText += delta
                case let .reasoningDelta(delta):
                    self.reasoningText += delta
                case let .done(fullText, finishReason):
                    self.complete(fullText: fullText, finishReason: finishReason)
                case let .error(message):
                    self.lastError = message
                    self.complete(fullText: self.streamingText, finishReason: "error")
                }
            }
        }

        let result: AgentTurnResult = await withCheckedContinuation { continuation in
            AgentHostThreading.turnQueue.async {
                let outcome = runtime.runTurnStream(
                    request: request,
                    companionId: companionId,
                    toolHost: toolHost,
                    sink: sink
                )
                continuation.resume(returning: outcome)
            }
        }

        // 强制收束：Rust 未回调 onDone/onError 时（非流式降级 / 异常路径）
        // AsyncStream 不会自行结束，这里必须显式关闭，否则 await consumer.value 永久挂起。
        sink.closeAll()
        await consumer.value

        lastRoundsUsed = result.roundsUsed
        if let error = result.error, !error.isEmpty {
            lastError = error
            log.error("回合失败：\(error, privacy: .public)")
        }
        // ⚠️ 第 119 轮：消费回合内产生的 AgentEvent。
        // StreamSink 只有 text/reasoning/done/error 四个回调，**没有事件回调**
        // （已核对生成的绑定 L3733-3755），sticker 等事件只存在于
        // `AgentTurnResult.events` 里。此前完全没读它 ——
        // 于是模型调 send_sticker 成功也无声无息。
        applyEvents(result.events, environment: environment)
        // 兜底：sink 没给出任何文本时，用结果里的最终文本落地
        if streamingText.isEmpty, !result.finalText.isEmpty {
            complete(fullText: result.finalText, finishReason: result.finishedReason)
        }
    }

    // MARK: - 事件落地

    /// 把回合事件转成界面消息。
    ///
    /// ## 表情消息的编码约定（**与 Android 全仓统一**）
    /// `AiResponseFinalizer.kt:448-449`：
    /// > 贴纸消息编码约定：**普通 TEXT 消息，内容为 `[stickerId]`**，
    /// > 由渲染层识别为表情包（`MessageType` 无 STICKER 枚举，全仓库统一此约定）。
    ///
    /// 所以这里 text 存 `[<entryId>]`，渲染层据此判断是不是表情。
    ///
    /// `AgentEvent.extra` 的格式（agent.rs:441-444）**不是 JSON**，是 KV 串：
    ///     entry_id=123;file_name=custom_1699_1.png
    /// 需要手工解析，不能想当然按 JSON 解。
    private func applyEvents(_ events: [AgentEvent], environment: AppEnvironment) {
        for event in events where event.kind == "sticker" {
            let fields = parseKeyValues(event.extra)
            let entryId = fields["entry_id"] ?? ""
            guard !entryId.isEmpty else { continue }

            // 内容 = "[<entryId>]"，与 Android 约定一致
            messages.append(Message(role: .assistant, text: "[\(entryId)]"))
            log.info("表情已落地：entry_id=\(entryId, privacy: .public)")
        }
    }

    /// 解析 `k=v;k=v` 形式的 KV 串。
    private func parseKeyValues(_ raw: String) -> [String: String] {
        var out: [String: String] = [:]
        for part in raw.split(separator: ";") {
            let kv = part.split(separator: "=", maxSplits: 1)
            if kv.count == 2 {
                out[String(kv[0]).trimmingCharacters(in: .whitespaces)] =
                    String(kv[1]).trimmingCharacters(in: .whitespaces)
            }
        }
        return out
    }

    /// 取消当前回合（Rust 侧的 `turn_cancel` 标志会让重试循环中断）。
    func cancel(in environment: AppEnvironment) {
        environment.runtime?.cancelCurrentTurn()
    }

    // MARK: - 内部

    private func complete(fullText: String, finishReason: String) {
        let text = fullText.isEmpty ? streamingText : fullText
        streamingText = ""
        reasoningText = ""
        guard !text.isEmpty else { return }
        messages.append(Message(role: .assistant, text: text))
    }

    /// 组装发给 Rust 的历史。
    ///
    /// 说明 `systemPrompt` 传 nil：system prompt 完全由 Rust 侧编排器组装
    /// （`AgentRuntime` 持有 `PromptOrchestrator`）。若将来要由宿主提供，
    /// 应走 `AgentTurnRequest.systemPrompt`，**不要**塞进历史 ——
    /// 历史里的 system 消息会被 Rust 的人设替换逻辑处理，语义不同。
    ///
    /// ## ⚠️ 必须经过 `DialogueHistoryPolicy.sanitizeForModel`
    /// Rust 的注释明确说 `history_json` 是「Kotlin 侧 AiDialogueHistoryPolicy 产物」
    /// （`agent.rs:1059`、`native_gateway.rs:12`）——**模型看到的是清洗过的历史**。
    /// 不清洗的后果：把 toast / API 错误提示 / 空消息也喂给模型，
    /// 且相邻同角色消息不合并（协议上通常是 user/assistant 交替，
    /// 连续两条同角色可能被上游拒绝或行为异常）。
    private func historyForRequest() -> [AgentHistoryMessage] {
        let raw = messages.map { message in
            switch message.role {
            case .user: return AgentHistoryMessage.user(message.text)
            case .assistant: return AgentHistoryMessage.assistant(message.text)
            case .system: return AgentHistoryMessage.system(message.text, preserve: true)
            case .tool: return AgentHistoryMessage.tool(message.text, toolCallId: "")
            }
        }
        return DialogueHistoryPolicy.sanitizeForModel(raw)
    }
}
