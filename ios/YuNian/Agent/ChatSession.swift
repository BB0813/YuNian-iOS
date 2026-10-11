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
    ///
    /// ⚠️ `isSticker` 必须是**显式字段**，不能靠"文本是 [数字]"反推。
    /// 第 120 轮前的实现就是反推的：用户手动打 `[123]` 会被渲染成表情，
    /// 而模型真发的表情反而不一定带得上格式。协议约定归约定，
    /// 展示层要有自己的真值。
    struct Message: Identifiable, Equatable {
        let id = UUID()
        let role: AgentHistoryRole
        var text: String
        /// 是否为表情消息（内容仍按 Android 约定存 `[entryId]`）。
        var isSticker: Bool = false
        /// 生图产出的图片字节（第 165 轮）。
        ///
        /// ⚠️ 与 `isSticker` 同理：**显式字段**，不靠文本反推。
        /// Android 侧图片消息是 `type=IMAGE` + `linkString=<文件路径>`
        /// （ImageGenTrigger.kt:370-381），iOS 侧消息是内存数组、
        /// 无落库路径，故直接把字节带在消息上。
        ///
        /// 展示层 `ChatView` 据此渲染；为 nil 时走普通文本气泡。
        var imageData: Data?
        /// 画面描述（对应 Android 的 `searchContent`）。
        /// 供将来回喂模型与 UI 说明，当前不进历史。
        var imagePrompt: String?
        /// 消息时间。
        ///
        /// ## 为什么现在才加（第 202 轮）
        /// 审计已记过这个缺口：`Message` 原先只有 `UUID id`，**没有任何时间信息**，
        /// 因此聊天页无法插入时间分隔线 —— 而 Android 规格 §5 明确要求
        /// 「相邻可见消息间隔 ≥5 分钟时插入 `TimeDivider`」。
        /// 这与 Apple Messages 的行为也一致。
        ///
        /// 加它是**加法式改动**：带默认值，所有既有构造点不受影响；
        /// 从数据库装载时用真实时间戳，实时追加时用当前时间。
        var timestamp: Date

        /// 本轮思考过程（REASONING）。
        ///
        /// ## 为什么挂到消息上，而不是只留一个实时 buffer（第 202 轮）
        /// Android 规格 §5 明确：`REASONING` 是**与助手 turn 关联的正式消息**
        /// （含 duration），只有「**负 ID** 表示临时流态」。
        /// 也就是说思考过程在 Android 上是**持久保留**的。
        ///
        /// iOS 上一版在 `complete()` 里直接把 `reasoningText` 清空，
        /// 回复一结束思考过程就消失 —— 那是保真度缺口，不是设计选择。
        /// 现在把它附到该轮产出的助手消息上，回复完成后仍可展开回看。
        ///
        /// 注意：它**不进模型上下文**（与 Android 一致：REASONING 不参与
        /// 上下文/摘要/未读），`historyForRequest()` 只取 `text`。
        var reasoning: String?

        /// 表情消息对应的**文件名**。
        ///
        /// ## 为什么要有它（第 202 轮）
        /// `send_sticker` 的事件里本来就同时带着 `entry_id` 和 `file_name`：
        /// ```
        /// entry_id=123;file_name=custom_1699_1.png
        /// ```
        /// 而 `updateStickers(stickers: [String])` 下发的**只是标签名**，
        /// 生成绑定原文写明它「供 builtin_send_sticker 精确匹配校验与
        /// 无匹配报告展示」—— 它不负责产出本地 id，所以事件里的 `entry_id`
        /// 不保证落在本地 `sticker_entries.id` 空间里。
        ///
        /// 真机症状：表情库里 25 张都正常显示，模型发出来后聊天页**只有占位符**。
        /// 因此这里把 `file_name` 一并带上：**文件名是物理标识**，
        /// 渲染时直接按它读文件，不再依赖任何一侧的 id 分配。
        var stickerFileName: String?

        /// 表情图片的**原始字节**。
        ///
        /// ## 为什么让消息自己带图（第 202 轮）
        /// 前三次修的都是「ViewModel 事后按 id / 文件名解析」那条路 ——
        /// 结果**连诊断字都没出现过**，说明那条路根本没跑到这条消息上。
        /// 在同一条路上继续试是浪费轮次。
        ///
        /// 改成**在源头带上**：`applyEvents` 拿到事件时就直接把文件读进来，
        /// 渲染层只用它，不再依赖任何异步解析、sink 触发或 id 映射。
        /// 代价是一张表情常驻内存（几十 KB），换掉的是整条链路上的中间环节。
        var stickerImageData: Data?

        init(role: AgentHistoryRole, text: String, isSticker: Bool = false,
             imageData: Data? = nil, imagePrompt: String? = nil,
             timestamp: Date = Date(), reasoning: String? = nil,
             stickerFileName: String? = nil, stickerImageData: Data? = nil) {
            self.role = role
            self.text = text
            self.isSticker = isSticker
            self.imageData = imageData
            self.imagePrompt = imagePrompt
            self.timestamp = timestamp
            self.reasoning = reasoning
            self.stickerFileName = stickerFileName
            self.stickerImageData = stickerImageData
        }
    }

    @Published private(set) var messages: [Message] = []
    @Published private(set) var streamingText: String = ""
    @Published private(set) var reasoningText: String = ""
    @Published private(set) var isRunning = false
    @Published private(set) var lastError: String?
    @Published private(set) var lastRoundsUsed: UInt32 = 0

    /// 本轮是否已经把助手正文追加到 UI 并落库。
    ///
    /// ⚠️ 第 205 轮：现在**一回合只提交一次**（`commitTurn`），
    /// 所以它实际拦住的是"同一回合被提交两次"，而不是"每个 `.done` 提交一次"。
    /// （`.done` 是**每轮**一次的 —— 见 `consumer` 里的说明。）
    private var assistantCommittedThisTurn = false

    /// 最后一轮 `.done` 带回来的正文与结束原因。
    ///
    /// 用途只有一个：**流式累计与 `result.finalText` 都为空时的最后兜底**
    /// （例如 Anthropic 降级路径只回调一次 `on_done`）。
    /// ⚠️ 不要用它当"回合最终文本"——多轮时那只是**最后一轮**的内容，
    /// 会丢掉前面几轮（这正是"回复被截断"的旧病根）。
    private var lastRoundFullText = ""
    private var lastRoundFinishReason = ""

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

        // ⚠️ 第 166 轮：落库。此前 `messages` 是纯内存数组，App 一重启
        // 聊天历史全丢 —— 现在写入 SQLite（messages + message_bodies + FTS 索引）。
        //
        // conversationId 用 companionId（iOS 侧无群聊数据模型，恒 chat）。
        // 落库失败**不阻断发送**：打招呼/首轮对话比持久化更重要，
        // 失败只进日志。
        let conversationId = companionId ?? 0
        if let repo = messageRepository(for: environment) {
            do {
                _ = try repo.insert(
                    conversationId: conversationId,
                    conversationType: .chat,
                    isFromUser: true,
                    senderId: 0,
                    type: "text",
                    content: trimmed,
                    searchContent: trimmed
                )
            } catch {
                log.error("用户消息落库失败：\(error.localizedDescription, privacy: .public)")
            }
        }

        messages.append(Message(role: .user, text: trimmed))
        streamingText = ""
        reasoningText = ""
        lastError = nil
        assistantCommittedThisTurn = false
        lastRoundFullText = ""
        lastRoundFinishReason = ""
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

        // 本轮开始打点：REASONING 行的 durationMs 由它算（见 turnStartedAt）
        turnStartedAt = Date()

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
                    // ⚠️ 第 205 轮：**`onDone` 不是回合结束信号，它是"每一轮"的信号。**
                    //
                    // Rust 的 `run_turn_inner` 在**轮次循环里**每轮调一次
                    // `native.send_stream(...)`（agent.rs:1251-1252），而
                    // `send_stream` 每被调一次就 `sink.on_done(...)`
                    // （native_gateway.rs:926 / :1222）。
                    // 于是工具轮 / 多轮叙述的回合会来**多次** `.done`。
                    //
                    // 之前在这里直接 `complete()` → 第一次 `.done` 就把
                    // `assistantCommittedThisTurn` 置真，**后续轮次的正文整段丢掉**
                    // （这就是"回复被截断"的真身：留下的往往是第一轮的引子）。
                    // 现在这里只记录本轮结果，提交统一放在回合末（见 `commitTurn`）。
                    self.lastRoundFullText = fullText
                    self.lastRoundFinishReason = finishReason
                    // 轮次边界补一个空行：多轮叙述（"边调边说"）的各轮文本
                    // 会累积进同一个气泡，不加分隔就会粘成一段。
                    // 末尾多出来的空行在 `commitTurn` 里会被 trim 掉。
                    if !self.streamingText.isEmpty, !self.streamingText.hasSuffix("\n\n") {
                        self.streamingText += "\n\n"
                    }
                case let .error(message):
                    self.lastError = message
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
        // 提交**一次**：整回合的正文 + 整回合的思考过程（第 205 轮）。
        commitTurn(result: result, environment: environment)

        // ⚠️ 第 160 轮：回合结束后尝试生图。
        // Kotlin 在 `ChatGenerationManager.kt:1419-1456` 用**独立协程**做这件事，
        // 绝不阻塞聊天主流程；这里同样放在 `defer` 之外、`isRunning` 置回 false
        // **之前**调用，保证用户看到的"停止"状态与生图互斥。
        await maybeTriggerImageGen(
            userText: trimmed,
            aiText: result.finalText,
            environment: environment
        )
    }

    // MARK: - 生图协调

    /// 回合结束后的生图尝试。
    ///
    /// ## 权威来源（第 160 轮）
    /// Kotlin `ChatGenerationManager.kt:1419-1456`：
    /// 独立协程 + 总开关预检（:1420）+ 异常全吞（生图失败绝不影响聊天）。
    ///
    /// ## iOS 侧的开关从哪来
    /// Kotlin 用 DataStore 的 `image_gen_enabled`（默认 false）。
    /// iOS 侧没有那份设置，故此处**恒不自动触发**，除非：
    /// - 用户已在「AI 生图」页保存过配置（见 `ImageGenStore`）
    ///
    /// 这样默认行为与 Kotlin 的 `DEFAULT_IMAGE_GEN_ENABLED = false` 一致，
    /// 不会在用户没要生图时突然弹图。
    ///
    /// ⚠️ 未做：per-companion override（Kotlin 的
    /// `ChatDetailSettingsStore` 那套）。iOS 恒传空 override。
    private func maybeTriggerImageGen(
        userText: String,
        aiText: String,
        environment: AppEnvironment
    ) async {
        // ⚠️ 第 161 轮：`var` 而非 `let` —— saveLastGenAt 闭包要改它。
        guard let loaded = ImageGenStore.load() else { return }   // 未配置 → 不触发

        // ⚠️ 第 203 轮（P0 ②）：改用**「生图」线路**指向的渠道，而不是
        // 「当前启用渠道」。理由很直接：当前启用渠道是**对话**用的那家，
        // 它多半只提供对话模型；拿它去打 /images/generations 是注定的 404。
        // 取法由 `ApiConfigRepository.resolve` 统一给出（绑定 → 行；无绑定 → 当前启用），
        // 所以**没配过多线路的库行为与改动前完全一致**。
        guard let repo = environment.apiConfigs,
              let route = (try? repo.resolve(purpose: .image)) ?? nil else { return }
        var prefs = loaded

        // apiKey 取一次，避免闭包里重复读 Keychain
        // 第 186 轮：按**该条配置**解析 key（不再是全局单槽）
        let apiKey = KeychainStore.resolvedAPIKey(configId: route.configId)

        var coordinator = ImageGenCoordinator(
            deps: .init(
                global: prefs.global,
                override: .init(),
                mainConnection: (route.baseUrl, apiKey),
                configBaseUrl: route.baseUrl,
                configApiKey: apiKey,
                // 第 204 轮：模型名也是「生图线路」的一部分 ——
                // 不传的话请求体是 `{"model": ""}`，任何服务端都拒。
                imageModel: route.model,
                lastGenAtMs: prefs.lastGenAtMs,
                saveLastGenAt: { ms in
                    prefs.lastGenAtMs = ms
                    ImageGenStore.save(prefs)
                }
            )
        )
        // ⚠️ 第 162 轮：闭包里必须显式 self.（ChatSession 是 class，
        // 闭包是 escaping）。CI（ca98194）报 3 处
        // "reference to property 'messages'/'lastError' in closure
        //  requires explicit use of 'self'"。
        coordinator.onMessage = { [weak self] (text: String, isError: Bool) in
            self?.messages.append(Message(role: .assistant, text: text, isSticker: false))
            if isError { self?.lastError = text }
        }
        coordinator.onImages = { [weak self] (datas: [Data], prompt: String) in
            // 一条图片消息一张图（Kotlin ImageGenTrigger.kt:367-384）。
            // ⚠️ 第 165 轮：把字节带进消息，不再是 "[图片]" 文本占位。
            // text 仍存 "[图片]"，与 Android 的系统保留标签约定一致
            // （ImageGenTrigger.kt:408-409），但真值在 imageData 上。
            //
            // ⚠️ 第 167 轮：写文件 + 落库 + 填 linkString。
            // 上一版只在内存里带字节 —— App 一重启图就没了。
            // Kotlin 把图存 `getExternalFilesDir/generated_images`
            // （ImageGenerationProvider.kt:51-55 明确**不可用 cache**）；
            // iOS 侧对应物是 Application Support 下的 generated_images。
            //
            // ⚠️ 落库失败**不阻断**渲染：图已经在屏幕上了，
            // 重启后少这一条比"这条消息挡住下一张"好。
            for data in datas {
                self?.messages.append(
                    Message(role: .assistant, text: "[图片]", isSticker: false,
                            imageData: data, imagePrompt: prompt))
            // 需要 repo 才能落库；拿不到就只在内存里显示
            //（拿不到 database 的情况极少，且上面已 guard 过一次）。
            if let repo = self?.messageRepository(for: environment) {
                self?.persistImage(data: data, prompt: prompt, repo: repo)
            }
            }
        }

        _ = await coordinator.run(
            userText: userText,
            aiText: aiText,
            companionId: companionId ?? 0
        )
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
        // ⚠️ 第 205 轮：思考过程**也走事件流**，别只认 sink。
        //
        // Rust 在解析响应时会额外推一条 `kind == "reasoning"` 的事件
        // （agent.rs:1303-1311），它与 `sink.on_reasoning_delta` 是**两条路**：
        //   · 流式请求：装配好的响应体里也带 reasoning_content → 事件是重复的
        //   · 非流式请求（`stream_sink.is_none()`，如 Anthropic 降级路径）
        //     或任何 sink 没交付增量的情况 → **事件是唯一来源**
        // 此前这里只处理 `kind == "sticker"`，那条事件被静默丢掉 ——
        // 于是"有时候有思考过程、有时候没有"多了一个成因。
        //
        // 只在 sink 什么都没给时才用事件，避免与增量拼成两遍。
        if reasoningText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
           let last = events.last(where: { $0.kind == "reasoning" }),
           !last.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            reasoningText = last.text
        }

        for event in events where event.kind == "sticker" {
            let fields = parseKeyValues(event.extra)
            let entryId = fields["entry_id"] ?? ""
            guard !entryId.isEmpty else { continue }

            // ⚠️ 第 202 轮：**不能只信 entry_id**。
            //
            // 真机症状：表情库里 25 张都正常显示（说明行在、文件也在），
            // 但模型发出来的表情在聊天页**只能渲染成占位符**，甚至什么都没有。
            //
            // 成因：`updateStickers(stickers: [String])` 下发的**只是标签名**，
            // 生成绑定的原文写明它「供 builtin_send_sticker 精确匹配校验与
            // 无匹配报告展示」—— 它**本来就不负责产出本地 entry_id**。
            // 因此事件里的 `entry_id` 来自 Rust 侧存储实现，其 id 空间
            // 不保证与本地 `sticker_entries.id` 一致。
            //
            // 而事件同时带着 `file_name`（agent.rs 的 KV 串格式：
            // `entry_id=123;file_name=custom_1699_1.png`）。**文件名是物理标识**，
            // 不依赖任何一侧的 id 分配 —— 所以拿它兜底反查本地行。
            let database = environment.database
            let eventFileName = fields["file_name"] ?? ""

            var resolvedId: Int64?
            if let numericId = Int64(entryId), let database,
               StickerBubbleModel(entryId: numericId, database: database) != nil {
                resolvedId = numericId
            } else if !eventFileName.isEmpty, let database {
                resolvedId = (try? database.pool.read { db in
                    try Int64.fetchOne(
                        db,
                        sql: "SELECT id FROM sticker_entries WHERE fileName = ? LIMIT 1",
                        arguments: [eventFileName])
                }) ?? nil
            }

            guard let resolvedId else {
                log.error("表情事件落不到本地条目，已丢弃：entry_id=\(entryId, privacy: .public) file_name=\(eventFileName, privacy: .public)")
                continue
            }

            // 内容 = "[<entryId>]"，与 Android 约定一致；
            // 同时带上 file_name 与**图片字节**供渲染层直接使用
            // （不依赖任何一侧的 id 空间，也不依赖事后异步解析）。
            var fileName = eventFileName
            if fileName.isEmpty, let database,
               let model = StickerBubbleModel(entryId: resolvedId, database: database) {
                fileName = model.fileName
            }
            var imageData: Data?
            if !fileName.isEmpty, let dir = try? AppPaths.stickersDirectory() {
                imageData = try? Data(contentsOf: dir.appendingPathComponent(fileName))
            }

            messages.append(
                Message(role: .assistant, text: "[\(resolvedId)]", isSticker: true,
                        stickerFileName: fileName.isEmpty ? nil : fileName,
                        stickerImageData: imageData))
            // ⚠️ 第 204 轮：**必须落库**。
            //
            // 在此之前只 append 到内存数组 —— 于是"重启后表情整条消失"，
            // 与上一轮修的"思考过程离开聊天页就丢"是**同一类缺口**：
            // 界面上看得见、库里没有，重载路径自然拿不回来。
            //
            // Android 侧一直是这样做的：`AiResponseFinalizer.flushPendingSticker`
            // 把表情当**普通 ChatMessage** 落库（`content = "[$stickerId]"`、
            // `isFromUser = false`），靠全仓统一的正文约定在渲染层还原。
            persistProtocolMessage(text: "[\(resolvedId)]", isFromUser: false,
                                   environment: environment)
            log.info("表情已落地：entry_id=\(resolvedId, privacy: .public) file=\(fileName, privacy: .public) bytes=\(imageData?.count ?? -1, privacy: .public)")
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

    /// 用户手动发送一个表情。
    ///
    /// ## 为什么需要（第 120 轮）
    /// 在此之前表情只能由**模型**通过 `send_sticker` 发出，用户被绑在
    /// "等模型心情好"上。而 `sticker_pick` 的挑选逻辑本就是共享的 ——
    /// 用户手动选只是把"谁发起"换掉，链路完全复用。
    ///
    /// ## 发送前先落一次使用计数
    /// 与 `StickerToolBridge` 命中后的处理一致（`modelUsageCount + 1`
    /// 改为 `userUsageCount + 1`），否则偏好统计里用户发的不算，越用它越排后面。
    func sendSticker(entryId: Int64, in environment: AppEnvironment) {
        guard let database = environment.database else { return }
        // ⚠️ StickerBubbleModel 没实现 Equatable，不能 `== nil`。
        // 它 init? 失败即返回 nil，故用 let 绑定判断存在性。
        guard let _ = StickerBubbleModel(entryId: entryId, database: database) else {
            lastError = "表情不存在或已被删除"
            return
        }
        // 累加用户使用计数（对齐 Android recordUsage 的 USER 来源）
        try? database.pool.write { db in
            try db.execute(
                sql: """
                    UPDATE sticker_entries
                    SET userUsageCount = userUsageCount + 1, lastUsedAt = ?
                    WHERE id = ?
                    """,
                arguments: [Int64(Date().timeIntervalSince1970 * 1000), entryId])
        }

        messages.append(Message(role: .user, text: "[\(entryId)]", isSticker: true))
        // 用户发的表情同样要落库（第 204 轮）—— 否则重启后自己发的那张也消失。
        persistProtocolMessage(text: "[\(entryId)]", isFromUser: true, environment: environment)
        lastError = nil
    }

    /// 把一条**协议文本消息**（表情的 `[<id>]`）落库。
    ///
    /// ## 为什么与用户文本消息共用一个 insert
    /// 表情在全仓的约定就是"普通 TEXT 消息，正文 `[id]`"
    /// （`AiResponseFinalizer.kt:448-449`），所以它落库时 `type` 也是 `"text"`，
    /// 重载时由 `stickerId(fromContent:)` 反推回 `isSticker`。
    ///
    /// ## `searchContent` 留空
    /// `[12]` 进 FTS 索引只会变成搜索噪声（用户搜不到任何有意义的东西），
    /// Android 的贴纸消息同样没有可搜内容。这与"用户文本消息
    /// searchContent = 正文"的参数不同，是**有意**的。
    private func persistProtocolMessage(text: String, isFromUser: Bool,
                                        environment: AppEnvironment) {
        guard let repo = messageRepository(for: environment) else { return }
        do {
            _ = try repo.insert(
                conversationId: companionId ?? 0,
                conversationType: .chat,
                isFromUser: isFromUser,
                senderId: 0,
                type: "text",
                content: text,
                searchContent: ""
            )
        } catch {
            // 落库失败不阻断渲染：表情已经在屏幕上了，
            // 重启后少这一条比"这条挡住下一个表情"好（与图片落库同一条取舍）。
            log.error("表情落库失败：\(error.localizedDescription, privacy: .public)")
        }
    }

    /// 取消当前回合（Rust 侧的 `turn_cancel` 标志会让重试循环中断）。
    func cancel(in environment: AppEnvironment) {
        environment.runtime?.cancelCurrentTurn()
    }

    // MARK: - 内部

    /// 提交本回合的助手正文与思考过程（**一回合只调一次**）。
    ///
    /// ## 为什么从 `complete(fullText:finishReason:)` 改成这样（第 205 轮）
    /// 旧版把提交挂在 StreamSink 的 `.done` 上，而 **`.done` 是每轮一次的**
    /// （见 `consumer` 里的说明）—— 多轮工具回合因此只有第一轮的正文被留下。
    ///
    /// ## 正文取哪一个
    /// 1. **流式累计**（`streamingText`）优先：那是用户**已经逐字看到**的内容，
    ///    多轮里每一轮的增量都在里面。落库与所见一致，不会再出现
    ///    "屏幕上有一句、切窗回来少一截"。
    /// 2. 累计为空（例如 Anthropic 降级路径只回调一次 `on_done` 而没走增量）→
    ///    用 `result.finalText`（引擎给的回合最终文本）。
    /// 3. 仍为空 → 用最后一轮 `.done` 带回来的文本。
    ///
    /// ## 正文为空但**有思考过程**时不再丢弃
    /// Rust 对"思考型模型把 max_tokens 花在推理上"的情形会明确报 error
    /// （agent.rs:1516-1531 的 `finish_reason=length` 分支）。此前这里因为
    /// `text.isEmpty` 早退，而 `reasoningText` **已经在早退之前被清空** ——
    /// 用户看到的是"想了一下，然后什么都没了"，且重启后也查不到。
    /// 现在即便没有正文，也把思考过程落一行、留一条消息（渲染层不画空气泡）。
    private func commitTurn(result: AgentTurnResult, environment: AppEnvironment) {
        let streamed = streamingText
        var text: String
        if !streamed.isEmpty {
            text = streamed
        } else if !result.finalText.isEmpty {
            text = result.finalText
        } else {
            text = lastRoundFullText
        }
        // 去掉轮次分隔（以及模型输出）留下的**尾部**空行 —— 只动尾部，
        // 不碰正文内部的换行（模型自己排的段落要保留）。
        while text.hasSuffix("\n") { text.removeLast() }
        let reasoning = reasoningText.trimmingCharacters(in: .whitespacesAndNewlines)

        streamingText = ""
        reasoningText = ""

        guard !assistantCommittedThisTurn else { return }
        guard !text.isEmpty || !reasoning.isEmpty else {
            // 空回合：**把结束原因写进日志**。
            // 否则"发了消息什么反应都没有"在日志里是一片空白，
            // 而 `finish_reason=length` 与"模型真的返回空"要修的地方完全不同。
            // ⚠️ guard 的 else 是闭包上下文，属性访问必须显式 `self.`。
            self.log.error("回合无任何可见输出：finish=\(self.lastRoundFinishReason, privacy: .public) rounds=\(result.roundsUsed) err=\(result.error ?? "无", privacy: .public)")
            return
        }
        assistantCommittedThisTurn = true

        messages.append(Message(role: .assistant, text: text,
                                reasoning: reasoning.isEmpty ? nil : reasoning))

        // ⚠️ 第 166 轮：AI 回复落库。`searchContent` 与 `content` 同值 ——
        // Android 侧图片消息在这里存画面描述（ImageGenTrigger.kt:381），
        // 文本消息两者一致（MessageSearchTokenizer 会再切词）。
        // 落库失败不阻断对话（同上）。
        if let repo = messageRepository(for: environment) {
            // 一轮一个 turnId：**正文行与 REASONING 行共用它配对**。
            // 不用"插入顺序/时间戳"配对 —— 两行时间戳会完全相同，
            // 顺序取决于 `history()` 的 ORDER BY，那太脆。
            let turnId = UUID().uuidString
            do {
                // ⚠️ 正文为空（思考型模型把预算花在推理上、finish_reason=length）
                // **也要写这一行**：REASONING 行靠 `turnId` 与正文行配对
                // （`loadHistory` 的 pairing），没有正文行的话那一行会被
                // "行序兜底"挂到**下一轮**的助手消息上 —— 思考过程就跑错轮了。
                // 渲染层对"正文为空但有思考过程"的消息不画气泡（见 ChatView）。
                _ = try repo.insert(
                    conversationId: companionId ?? 0,
                    conversationType: .chat,
                    isFromUser: false,
                    senderId: 0,
                    type: "text",
                    content: text,
                    searchContent: text,
                    turnId: turnId
                )

                // ⚠️ 第 202 轮：思考过程**必须落库**。
                //
                // 此前它只挂在内存里的 `Message.reasoning` 上 ——
                // 真机症状：能展开看到完整推理，但**一切窗再回来就没了**。
                //
                // Android 规格 §5 写得很明确：`REASONING` 是「与助手 turn 关联、
                // **含 duration**」的正式消息，**只有负 ID 那条是临时流态**
                // —— 也就是说它本来就该持久保留。而 `messages` 表
                // 恰好有 `type` / `turnId` / `durationMs` 三列，结构上早就支持。
                //
                // 它**不进上下文/摘要/未读**：`historyForRequest()` 只取 `text`，
                // 这里只是多存一行供回看。
                if !reasoning.isEmpty {
                    _ = try repo.insert(
                        conversationId: companionId ?? 0,
                        conversationType: .chat,
                        isFromUser: false,
                        senderId: 0,
                        type: Self.reasoningType,
                        content: reasoning,
                        searchContent: "",
                        turnId: turnId,
                        durationMs: lastTurnDurationMs
                    )
                }
            } catch {
                log.error("AI 回复落库失败：\(error.localizedDescription, privacy: .public)")
            }
        }
    }

    /// 落库用的 REASONING 类型标记。
    ///
    /// 小写：与既有的 `"text"` / `"image"` 一致（iOS 侧存 MessageType 序列名），
    /// 而 `loadHistory` 会按它把这一行排除出「可见消息」。
    static let reasoningType = "reasoning"

    /// 正文形如 `[123]`（纯数字）时返回 id，否则 nil。
    ///
    /// 只在**重载**路径用：库里没有 `isSticker` 列，而正文 `[<id>]`
    /// 是全仓统一约定（Android 的 Sticker 同样是 TEXT 投影，靠正文判定）。
    /// 这不与 live 路径「不要靠文本反推」的告诫冲突 ——
    /// 那条说的是建模时有条件就该带显式字段；重载路径没有别的持久信号。
    private static func stickerId(fromContent content: String) -> Int64? {
        guard content.hasPrefix("["), content.hasSuffix("]"), content.count > 2 else { return nil }
        return Int64(content.dropFirst().dropLast())
    }

    /// 本轮开始时刻 —— 用于 REASONING 行的 `durationMs`。
    /// Android 规格 §5：REASONING「与助手 turn 关联，**含 duration**」。
    private var turnStartedAt = Date()

    /// 本轮已耗时（毫秒）。
    private var lastTurnDurationMs: Int {
        max(0, Int(Date().timeIntervalSince(turnStartedAt) * 1000))
    }

    /// 构造消息 repository（第 166 轮）。
    ///
    /// 没有数据库时返回 nil —— 调用方据此跳过错库。
    private func messageRepository(for environment: AppEnvironment) -> MessageRepository? {
        guard let database = environment.database else { return nil }
        return MessageRepository(database: database)
    }

    /// 装载该会话的历史消息（第 166 轮）。
    ///
    /// ⚠️ **不覆盖已有消息**。若上层已灌入消息，这里只补数据库里更早的部分。
    ///
    /// ## 去重是近似的（如实记录）
    /// `Message` 没有 dbId 字段，只能用「role + text + 类型」近似判重。
    /// 精确去重需要给 `Message` 加 `messageId` —— 那会动所有构造点，
    /// 不是一行能完事，属下轮。
    func loadHistory(from environment: AppEnvironment, limit: Int = 100) {
        guard companionId != nil else { return }     // 未绑定伴侣无从查
        guard let repo = messageRepository(for: environment) else { return }
        guard let rows = try? repo.history(
            conversationId: companionId ?? 0, type: .chat, limit: limit) else { return }
        guard !rows.isEmpty else { return }

        var seen = Set<String>()
        for m in messages {
            seen.insert("\(m.role.rawValue)|\(m.text)|\(m.isSticker)|\(m.imageData?.count ?? -1)")
        }

        // ⚠️ 第 202 轮：先把 REASONING 行按 turnId 收起来。
        //
        // 它们是**正式消息**（Android 规格 §5：与助手 turn 关联、含 duration，
        // 只有负 ID 那条是临时流态），但**不是可见气泡** ——
        // 必须挂到同 turn 的助手消息上，而**不能**作为普通消息渲染。
        // 漏掉这一步的后果：思考过程会以一条文字气泡出现在对话里，比丢更糟。
        //
        // 用 turnId 配对而不是行顺序：两行时间戳完全相同，
        // 顺序取决于 `history()` 的 ORDER BY，太脆。
        var reasoningByTurn: [String: String] = [:]
        for r in rows where r.type == Self.reasoningType {
            guard let turnId = r.turnId, !turnId.isEmpty else { continue }
            reasoningByTurn[turnId] = r.content
        }

        // 兜底：turnId 没落上（旧行、或写入路径漏了）时，按**行顺序**配对 ——
        // REASONING 行紧邻它所属的助手行。两者都试，尽量不让思考过程丢。
        var reasoningByRowIndex: [Int: String] = [:]
        var pendingReasoning: String?
        for (index, r) in rows.enumerated() {
            if r.type == Self.reasoningType {
                pendingReasoning = r.content.isEmpty ? nil : r.content
                continue
            }
            if r.isFromUser {
                // 用户消息是轮次边界：跨轮的思考过程不该挂到下一轮
                pendingReasoning = nil
                continue
            }
            if let pending = pendingReasoning {
                reasoningByRowIndex[index] = pending
                pendingReasoning = nil
            }
        }

        var loaded: [Message] = []
        for (index, r) in rows.enumerated() {
            // REASONING 行不进可见消息（它已按 turnId / 行序收进上面两张表）
            if r.type == Self.reasoningType { continue }

            // ⚠️ 第 204 轮：键里的 `isSticker` 必须**按正文约定算**，不能写死 false。
            // 内存里的表情消息 `isSticker == true`，而库行反推出来的也是 true ——
            // 写死 false 会让同一条消息在两边的键不同，重载时就**重复插入一条**。
            let key = "\(r.isFromUser ? "user" : "assistant")|\(r.content)"
                + "|\(Self.stickerId(fromContent: r.content) != nil)|-1"
            if seen.contains(key) { continue }

            // 数据库存的是毫秒时间戳；转成 Date 供聊天页插入时间分隔线。
            let stamp = Date(timeIntervalSince1970: TimeInterval(r.timestamp) / 1000)

            // ⚠️ 第 167 轮：图片消息从 linkString 读文件回来。
            // 上一版读到 "[图片]" 就只是个文本占位 —— 重启后图没了。
            // 读不到文件时退回文本（消息仍可见），与 Android
            // "资源缺失时显示描述"的处理一致。
            if r.type == "image" {
                if let link = r.linkString,
                   let data = FileManager.default.contents(atPath: link) {
                    loaded.append(Message(role: r.isFromUser ? .user : .assistant,
                                          text: r.content, isSticker: false,
                                          imageData: data,
                                          imagePrompt: r.searchContent,
                                          timestamp: stamp))
                    continue
                }
            }
            let stickerId = Self.stickerId(fromContent: r.content)
            loaded.append(Message(role: r.isFromUser ? .user : .assistant,
                                  text: r.content,
                                  isSticker: stickerId != nil,
                                  timestamp: stamp,
                                  reasoning: r.turnId.flatMap { reasoningByTurn[$0] } ?? reasoningByRowIndex[index]))
        }
        guard !loaded.isEmpty else { return }
        messages.insert(contentsOf: loaded, at: 0)
    }

    /// 把生图结果落盘 + 落库（第 167 轮）。
    ///
    /// ## 顺序很关键
    /// 先写库拿到 `messageId`，再用它命名文件 —— 这样
    /// `linkString`（Android 侧的绝对路径）与文件名永远对得上，
    /// 不会出现"库里有 messageId 27、磁盘上却是 gen_28.png"。
    ///
    /// ## Kotlin 对应
    /// `ImageGenTrigger.kt:370-381` 的 `ChatMessage` 构造：
    /// `content = "[图片]"`（系统保留标签）、`type = IMAGE`、
    /// `linkString = image.filePath`、`searchContent = prompt`。
    /// iOS 侧 type 用字符串 "image"（MessageType 序列名）。
    private func persistImage(data: Data, prompt: String, repo: MessageRepository) {
        do {
            let messageId = try repo.insert(
                conversationId: companionId ?? 0,
                conversationType: .chat,
                isFromUser: false,
                senderId: 0,
                type: "image",
                content: "[图片]",
                searchContent: prompt
            )
            let url = try AppPaths.generatedImageURL(messageId: messageId)
            try data.write(to: url, options: .atomic)
            // 把路径回填进 linkString（Kotlin 的附件就是它，无独立附件表）。
            try repo.updateLinkString(messageId: messageId, linkString: url.path)
        } catch {
            // 落盘/落库失败只进日志：图已在屏幕上显示，
            // 用户此刻的体验不受影响；重启后少这一条是可接受的损失。
            log.error("生图落库失败：\(error.localizedDescription, privacy: .public)")
        }
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
    /// 消息喂给模型时的正文（第 168 轮）。
    ///
    /// ## Kotlin 对应
    /// `ChatMessage.contentForModel()`（ChatTypeConverters.kt:37-43）：
    /// type == IMAGE **且** searchContent 非空时，给 content 附加一段
    /// 「系统注记」。content 本身不变 —— 渲染层仍按 "[图片]" 标签渲染气泡。
    ///
    /// ⚠️ **措辞逐字对齐，别顺手"优化"**。
    /// Kotlin 的注释（ChatTypeConverters.kt:33-35）记着一个真实事故：
    /// 早期版本写成 `[图片]（画面：xxx）`，模型会把这段**原样抄进回复**，
    /// 导致画面描述泄漏成独立文本气泡（BUG-1 的根因诱导源）。
    /// 现在的写法用「系统注记 + 显式禁止模仿」，让模型没有可照抄的模板。
    ///
    /// 没有它，用户追问「再生成一张」时模型看不到上一张画的是什么 ——
    /// Kotlin 的测试 `图片消息送给模型时附带画面描述`（Test:438-459）正是锁这条。
    ///
    /// 写成 `nonisolated static` 有两个原因：
    /// 1. 便于单测（否则被 private 挡住，测试只能间接验证）；
    /// 2. ⚠️ ChatSession 是 `@MainActor`（ObservableObject），
    ///    static 方法默认继承 main-actor 隔离 —— 同步的 XCTest
    ///    是非隔离上下文，直接调会编译不过
    ///    （CI 2ceaabc 报 9 处 "call to main actor-isolated static method
    ///     'contentForModel' in a synchronous nonisolated context"）。
    ///    本方法是纯字符串变换，不碰任何隔离状态，故可安全 nonisolated。
    nonisolated static func contentForModel(_ message: Message) -> String {
        guard message.imageData != nil,
              let prompt = message.imagePrompt,
              !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else {
            return message.text
        }
        return "\(message.text)（系统注记：该图画面描述为 \(prompt)；"
            + "此注记仅用于你理解图片内容，禁止在回复中输出任何「画面：」或括号包裹的画面描述）"
    }

    private func historyForRequest() -> [AgentHistoryMessage] {
        let raw = messages.map { message in
            // ⚠️ 第 168 轮：图片消息走 contentForModel 附加系统注记。
            let text = Self.contentForModel(message)
            switch message.role {
            case .user: return AgentHistoryMessage.user(text)
            case .assistant: return AgentHistoryMessage.assistant(text)
            case .system: return AgentHistoryMessage.system(text, preserve: true)
            case .tool: return AgentHistoryMessage.tool(text, toolCallId: "")
            }
        }
        return DialogueHistoryPolicy.sanitizeForModel(raw)
    }
}
