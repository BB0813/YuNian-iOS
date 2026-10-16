import Foundation

/// 对话链路里的生图协调器。
///
/// ## 权威来源（第 159 轮）
/// `feature/chat/.../ui/viewmodel/ChatGenerationManager.kt:1419-1456`
///（独立协程，绝不阻塞聊天主流程）→ `ImageGenServiceImpl.generateForReply`
/// → `ImageGenTrigger.kt`（判定引擎，第 156 轮已复刻）。
///
/// ## 与 Kotlin 的对应
/// | 项 | Kotlin | 行号 |
/// |---|---|---|
/// | 触发调用点 | 回合结束后，独立协程 | ChatGenerationManager.kt:1419-1456 |
/// | 前置开关预检 | `getImageGenEnabled()` 直接 return | :1420 |
/// | 触发判定 | `coordinator.maybeTriggerImage(...)` | ImageGenTrigger.kt:301 |
/// | prompt 拼装 | `buildPrompt` | :231-250 |
/// | 冷却落点 | `saveLastGenAt(now)` **在生图前** | :346-347 |
/// | 写消息 | 一张图一条，`content = "[图片]"` | :370-381 |
/// | 失败提示 | `emitMessage(..., isError)` | :393-400 |
///
/// ## 未做（如实记录）
/// - **未接入 ChatSession 的 send 生命周期**。本版是独立可调用单元，
///   由 ChatSession 在回合结束时调用；真正的自动挂载属链路改造，
///   需要确认「回合结束」在 iOS 侧的精确时点（Kotlin 是 finalText 落地后）。
/// - **未写消息库**。iOS 侧消息是内存数组（`ChatSession.messages`），
///   没有 MessageRepository 的 IMAGE 落库路径。
///   故生图结果经 `onMessage` 回调交回调用方渲染。
struct ImageGenCoordinator {

    /// Kotlin `ImageGenDeps`（ImageGenTrigger.kt:270-289）里与本版相关的几项。
    struct Deps {
        /// 全局生图配置。
        var global: ImageGenTrigger.GlobalConfig
        /// 单伴侣覆盖（iOS 侧目前没有 per-companion 生图配置，
        /// 恒传空 override —— 与 Kotlin 默认值行为一致）。
        var override = ImageGenTrigger.CompanionOverride()
        /// 主 API 连接（connectionMode == "auto" 时使用）。
        var mainConnection: (baseUrl: String, apiKey: String)?
        /// 渠道页已保存的 baseUrl（用于生图请求）。
        /// ⚠️ 与 `mainConnection` 分开：前者是当前启用的 api_configs 行，
        /// 后者是 Kotlin 的"主连接"概念。iOS 侧两者通常同源。
        var configBaseUrl: String
        /// Keychain 里的 apiKey。
        var configApiKey: String
        /// 生图模型名（第 204 轮补）。
        ///
        /// ## 为什么必须传进来
        /// Kotlin 的 `generateImage(baseUrl, apiKey, **model**, prompt, size, count)`
        /// 一直带 model，而 iOS 移植时**漏了这一项** ——
        /// `ImageGenClient.buildAttempts` 里的 `Attempt(model: "")` 是空串，
        /// 于是请求体永远是 `{"model": "", …}`，任何服务端都会拒。
        /// 也就是说：**在补上它之前，「生图线路」指向哪条渠道都无法真正出图**。
        var imageModel: String = ""
        /// 该伴侣上次生图的 epoch 毫秒；0 表示从未生过。
        var lastGenAtMs: Int64 = 0
        /// 写回上次生图时间。
        var saveLastGenAt: (Int64) -> Void = { _ in }
    }

    /// 协调结果。
    struct Outcome {
        var triggered: Bool
        var reason: ImageGenTrigger.Reason
        var matchedKeyword: String?
        var prompt: String?
        /// 生成的图片数量（落库/渲染成功的计数）。
        var imagesWritten: Int = 0
        /// 给用户的可见提示（含失败原因）。
        var message: String?
        var isError: Bool = false
    }

    /// 生图成功时回调（每个 Outcome 至多一次）。
    var onMessage: ((String, Bool) -> Void)?
    /// 生图成功、图片已解码时回调。
    ///
    /// ⚠️ 第 160 轮：没有这个回调，协调器跑完只返回一个 count，
    /// 调用方拿不到图 —— 那这个类就是白跑。
    /// Kotlin 侧对应 `writeMessage: suspend (ChatMessage) -> Long`
    /// （ImageGenTrigger.kt:276），一张图调一次。
    var onImages: (([Data], String) -> Void)?

    private let deps: Deps
    private let client = ImageGenClient()

    init(deps: Deps) {
        self.deps = deps
    }

    /// Kotlin `generateForReply` 的 iOS 对应（`ImageGenServiceImpl.kt:46-109`）。
    ///
    /// - Parameters:
    ///   - userText: 用户这轮发的原文
    ///   - aiText: 助手这轮的文本（Kotlin Agent 路径 = finalText + 所有
    ///     `kind == "bubble"` 的文本；iOS 侧传 `finalText`，见下方说明）
    ///   - companionId: 伴侣 id（用于冷却时间戳与日志）
    @MainActor
    mutating func run(
        userText: String,
        aiText: String,
        companionId: Int64
    ) async -> Outcome {

        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let effective = ImageGenTrigger.resolveEffective(
            global: deps.global,
            override: deps.override,
            mainConnection: deps.mainConnection
        )

        // 判定（randomRoll 每次只抽一次，Kotlin :283）
        let decision = ImageGenTrigger.decide(
            global: deps.global,
            effective: effective,
            userText: userText,
            aiText: aiText,
            lastGenAtMs: deps.lastGenAtMs,
            nowMs: nowMs,
            randomRoll: Int.random(in: 0..<100)
        )

        guard decision.triggered else {
            return Outcome(triggered: false, reason: decision.reason,
                           matchedKeyword: decision.matchedKeyword, prompt: nil)
        }

        // prompt 拼装（Kotlin :329-333）
        let prompt = ImageGenTrigger.buildPrompt(
            aiText: aiText,
            userText: userText,
            matchedKeyword: decision.matchedKeyword,
            template: deps.global.promptTemplate
        )
        if prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            // Kotlin :331-333 的「触发后又反悔」分支 —— 必须保留：
            // 判定说"该画"，但拼出来的描述是空的，这时**不能**往下走
            //（会得到一个空的生图请求），而要如实回报"没配置"。
            return Outcome(triggered: false, reason: .notConfigured,
                           matchedKeyword: decision.matchedKeyword, prompt: nil)
        }

        return await generateAndHandOff(
            prompt: prompt,
            reason: decision.reason,
            matchedKeyword: decision.matchedKeyword,
            companionId: companionId
        )
    }

    /// **用户主动要一张图**（第 228 轮，BACKLOG B2 的入口）。
    ///
    /// 与 `run(userText:aiText:companionId:)` 的唯一区别是**判定段**：
    /// 自动路径要靠关键词、冷却与概率决定"要不要画"；
    /// 手动路径是用户直接点的按钮 —— 再拿关键词去卡它就成了"点了没反应" ✗。
    /// 其余（线路解析、模型校验、生成、回调）走**同一段代码**。
    ///
    /// - Returns: `.triggered == false` 且 `isError == true` 时，
    ///   `message` 是给用户看的失败原因（未配生图模型 / 接口报错 / 没返回图片）。
    @MainActor
    mutating func runManual(prompt rawPrompt: String, companionId: Int64) async -> Outcome {
        guard let prompt = Self.manualPrompt(rawPrompt) else {
            let msg = ImageGenClient.ImageGenError.emptyPrompt.message
            onMessage?(msg, true)
            return Outcome(triggered: false, reason: .notConfigured, matchedKeyword: nil,
                           prompt: nil, imagesWritten: 0, message: msg, isError: true)
        }
        return await generateAndHandOff(
            prompt: prompt,
            // 手动 = 用户点名要一张，语义上最贴近"命中关键词"这一档
            //（`Reason` 是跨端共享的枚举，**不加新 case** —— 那会破坏对齐）。
            reason: .keyword,
            matchedKeyword: nil,
            companionId: companionId
        )
    }

    /// 手动输入的清洗：去空白；空则返回 nil（由调用方给出"描述为空"）。
    ///
    /// 顺手**封顶长度**：生图接口对 prompt 有长度限制，超长会得到
    /// 一个语焉不详的 400；与其让服务端拒，不如在这里截断并说明。
    static func manualPrompt(_ raw: String, limit: Int = 800) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(limit))
    }

    /// 两条件路径共用的「生成 + 回调」段。
    ///
    /// ⚠️ 冷却「先扣后画」在此处执行（Kotlin :346-347：失败也计入冷却，刻意保留）。
    /// 手动路径**也**扣 —— 否则用户手动出一张后，自动路径可能紧接着再出一张，
    /// 变成一次对话两张图。
    @MainActor
    private mutating func generateAndHandOff(
        prompt: String,
        reason: ImageGenTrigger.Reason,
        matchedKeyword: String?,
        companionId: Int64
    ) async -> Outcome {
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let effective = ImageGenTrigger.resolveEffective(
            global: deps.global,
            override: deps.override,
            mainConnection: deps.mainConnection
        )

        deps.saveLastGenAt(nowMs)

        onMessage?("配图生成中…", false)

        let baseUrl = effective.baseUrl.isEmpty ? deps.configBaseUrl : effective.baseUrl
        let apiKey = effective.apiKey.isEmpty ? deps.configApiKey : effective.apiKey

        // 模型名：走「生图线路」解析出来的那一个（第 204 轮）。
        // 空模型名没有合理的降级 —— 与其发一个注定被拒的请求，
        // 不如直接说清楚该去哪填（Kotlin 会照发 `"model": ""` 并让服务端报错）。
        if deps.imageModel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            let msg = ImageGenClient.ImageGenError.noModel.message
            onMessage?(msg, true)
            return Outcome(triggered: false, reason: reason, matchedKeyword: matchedKeyword,
                           prompt: prompt, imagesWritten: 0, message: msg, isError: true)
        }

        var images: [Data] = []
        do {
            images = try await client.generate(
                baseUrl: baseUrl,
                apiKey: apiKey,
                model: deps.imageModel,
                prompt: prompt,
                size: deps.global.size,
                count: deps.global.count
            )
        } catch {
            let msg = "配图生成失败：\(error.localizedDescription)"
            onMessage?(msg, true)
            return Outcome(triggered: false, reason: reason, matchedKeyword: matchedKeyword,
                           prompt: prompt, imagesWritten: 0, message: msg, isError: true)
        }

        guard !images.isEmpty else {
            let msg = ImageGenClient.ImageGenError.noImageData.message
            onMessage?(msg, true)
            return Outcome(triggered: false, reason: reason, matchedKeyword: matchedKeyword,
                           prompt: prompt, imagesWritten: 0, message: msg, isError: true)
        }

        // Kotlin :385-387 —— 全成功。
        // ⚠️ 第 160 轮：把图交回调用方（一张图一次的语义由调用方处理）。
        onImages?(images, prompt)
        onMessage?("配图已生成", false)
        return Outcome(
            triggered: true,
            reason: reason,
            matchedKeyword: matchedKeyword,
            prompt: prompt,
            imagesWritten: images.count,
            message: nil,
            isError: false
        )
    }
}
