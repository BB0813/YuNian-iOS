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
        var decision = ImageGenTrigger.decide(
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
        var prompt = ImageGenTrigger.buildPrompt(
            aiText: aiText,
            userText: userText,
            matchedKeyword: decision.matchedKeyword,
            template: deps.global.promptTemplate
        )
        if prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            // Kotlin :331-333 的「触发后又反悔」分支
            decision = ImageGenTrigger.Decision(
                triggered: false, reason: .notConfigured,
                matchedKeyword: decision.matchedKeyword, prompt: nil)
            return Outcome(triggered: false, reason: .notConfigured,
                           matchedKeyword: decision.matchedKeyword, prompt: nil)
        }

        // ⚠️ 冷却「先扣后画」（Kotlin :346-347）——失败也计入冷却。
        // 这是刻意保留的 Kotlin 行为，不是疏漏。
        deps.saveLastGenAt(nowMs)

        onMessage?("配图生成中…", false)

        // 生图（ImageGenClient，第 150 轮）
        let baseUrl = effective.baseUrl.isEmpty
            ? deps.configBaseUrl
            : effective.baseUrl
        let apiKey = effective.apiKey.isEmpty
            ? deps.configApiKey
            : effective.apiKey

        var images: [Data] = []
        do {
            images = try await client.generate(
                baseUrl: baseUrl,
                apiKey: apiKey,
                prompt: prompt,
                size: deps.global.size,
                count: deps.global.count
            )
        } catch {
            // Kotlin :393-400 —— generate 失败
            let msg = "配图生成失败：\(error.localizedDescription)"
            onMessage?(msg, true)
            return Outcome(triggered: false, reason: decision.reason,
                           matchedKeyword: decision.matchedKeyword, prompt: prompt,
                           imagesWritten: 0, message: msg, isError: true)
        }

        guard !images.isEmpty else {
            let msg = "配图生成失败：接口未返回图片数据"
            onMessage?(msg, true)
            return Outcome(triggered: false, reason: decision.reason,
                           matchedKeyword: decision.matchedKeyword, prompt: prompt,
                           imagesWritten: 0, message: msg, isError: true)
        }

        // Kotlin :385-387 —— 全成功。
        // ⚠️ 第 160 轮：把图交回调用方（一张图一次的语义由调用方处理）。
        // Kotlin 在 ImageGenTrigger.kt:367-384 里逐张 writeMessage。
        onImages?(images, prompt)
        onMessage?("配图已生成", false)
        return Outcome(
            triggered: true,
            reason: decision.reason,
            matchedKeyword: decision.matchedKeyword,
            prompt: prompt,
            imagesWritten: images.count,
            message: nil,
            isError: false
        )
    }
}
