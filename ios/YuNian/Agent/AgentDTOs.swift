import Foundation

/// Rust Agent 的三个 JSON 入参契约 —— 字段名取自 Rust 源码，**不要凭猜修改**。
///
/// 这些结构体没有 `#[serde(rename_all)]`，因此 JSON 键就是 Rust 字段名的 snake_case。
/// 键名写错**不会报错**（serde 忽略未知键、可选字段取默认值），
/// 只会表现为「设置不生效」「模型看不到历史」这类难以定位的问题。
///
/// 权威来源：
///   - `settings_json`    → `agent-native/src/agent.rs` 的 `orchestration_options()` / `setting_str()`
///   - `credentials_json` → `agent-native/src/native_gateway.rs` 的 `provider_headers()` / `all_api_keys()`
///   - `history_json`     → `agent-native/src/agent.rs` 的 `run_turn_inner()` 起段
///
/// 回归测试：`YuNianTests/AgentDTOsTests.swift`（逐个锁住键名）。

/// 统一的 JSON 编码器：键排序保证输出确定（便于断言与比对）。
enum AgentJSON {
    static func encode(_ value: some Encodable) -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        guard let data = try? encoder.encode(value),
              let string = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return string
    }
}

// MARK: - history_json

/// 对话角色。`tool` 是工具结果回灌。
enum AgentHistoryRole: String, Codable, Sendable {
    case system
    case user
    case assistant
    case tool
}

/// OpenAI 兼容的 tool_call 结构。
///
/// Rust 对 `tool_calls` 是**透传**（只做 `m.get("tool_calls")` 的存在性判断），
/// 因此形状必须与上游协议一致：`{"id":..,"type":"function","function":{"name":..,"arguments":..}}`。
struct AgentToolCall: Codable, Sendable, Equatable {
    struct Function: Codable, Sendable, Equatable {
        var name: String
        /// **字符串**形式的 JSON，不是嵌套对象 —— 与 OpenAI 协议一致。
        var arguments: String

        init(name: String, arguments: String) {
            self.name = name
            self.arguments = arguments
        }
    }

    var id: String
    var type: String
    var function: Function

    init(id: String, name: String, arguments: String) {
        self.id = id
        self.type = "function"
        self.function = Function(name: name, arguments: arguments)
    }
}

/// 一条历史消息。
///
/// `_agent_preserve_system` 是 Rust 的**内部保留标记**：只有标记为 true 的 system 消息
/// 才能在「人设替换」时存活（世界书注入、`[回合状态 N/M]` 运行时摘要）。
/// Rust 会在发往上游协议前剥掉它，所以设置是安全的 ——
/// 但**只对 system 消息有意义**，设到 user/assistant 上是无意义的。
struct AgentHistoryMessage: Codable, Sendable, Equatable {
    var role: AgentHistoryRole
    var content: String
    var toolCallId: String?
    var toolCalls: [AgentToolCall]?
    var preserveSystem: Bool?
    /// 思考模型的推理内容（OpenAI 兼容的 `reasoning_content`）。
    ///
    /// 语义（据 `agent.rs:1476`）：**思考模型要求工具调用对应的推理内容原样回灌**，
    /// Rust 在回合内自己构造 assistant 消息时会把模型返回的 reasoning 带上去。
    /// 宿主只需要在**回放一条历史 assistant 消息**时设置它；
    /// 不设也能工作（Rust 的回合内链路不依赖历史里的这个键）。
    var reasoningContent: String?

    enum CodingKeys: String, CodingKey {
        case role
        case content
        case toolCallId = "tool_call_id"
        case toolCalls = "tool_calls"
        case preserveSystem = "_agent_preserve_system"
        case reasoningContent = "reasoning_content"
    }

    init(
        role: AgentHistoryRole,
        content: String,
        toolCallId: String? = nil,
        toolCalls: [AgentToolCall]? = nil,
        preserveSystem: Bool? = nil,
        reasoningContent: String? = nil
    ) {
        self.role = role
        self.content = content
        self.toolCallId = toolCallId
        self.toolCalls = toolCalls
        self.preserveSystem = preserveSystem
        self.reasoningContent = reasoningContent
    }

    /// 人设替换时需要存活的世界书 / 运行时注入用这个。
    static func system(_ content: String, preserve: Bool = false) -> Self {
        Self(role: .system, content: content, preserveSystem: preserve ? true : nil)
    }

    static func user(_ content: String) -> Self {
        Self(role: .user, content: content)
    }

    static func assistant(
        _ content: String,
        toolCalls: [AgentToolCall]? = nil,
        reasoningContent: String? = nil
    ) -> Self {
        Self(
            role: .assistant,
            content: content,
            toolCalls: toolCalls,
            reasoningContent: reasoningContent
        )
    }

    static func tool(_ content: String, toolCallId: String) -> Self {
        Self(role: .tool, content: content, toolCallId: toolCallId)
    }
}

// MARK: - settings_json

/// 提示词编排与运行时设置。
///
/// ## ⚠️ 键集合必须与 Android `AgentFacade.buildSettingsJson` 一致
/// Android **只发 4 个键**：`role` / `timezone` / `working_memory_limit`（恒 200）/
/// `image_gen_rules`（非空白才发）。全仓所有调用点都走这个构造器，没有别的写入方。
///
/// 因此 iOS 若多发键，Rust 会把它**注入 system prompt**，两端提示词就不同
/// （`prompt_orchestrator.rs` 的 `环境上下文` 块：`owner_name` → `群主：x`，
/// `session_id` → `会话ID：x`）。这是**行为分歧**，不是无害的冗余。
///
/// 历史上这里多发过 `session_id`（值为臆造的 `"ios-default"`），已删除。
/// 现存 `owner_name` 是**有意的 iOS 侧功能**，见该字段注释。
struct AgentSettings: Codable, Sendable, Equatable {

    /// 角色：GIRLFRIEND / BOYFRIEND / FRIEND / MENTOR。Rust 默认 "GIRLFRIEND"。
    var role: String?
    /// 设备时区，供编排器渲染 `设备时区：` 一行。
    var timezone: String?
    /// ⚠️ **有意的跨端分歧（需产品确认，勿当作无害配置）**
    ///
    /// Android **从不发送** `owner_name`，即 Android 的提示词里没有「群主：」一行。
    /// iOS 侧做了「用户昵称」功能（`RootView` 昵称输入 → `AppEnvironment.setOwnerName`
    /// → Keychain），因此会在 iOS 提示词里多出 `群主：{昵称}` 一行。
    ///
    /// 后果：同一用户、同一输入，两端的 system prompt 不同 → AI 行为可能不同。
    /// 保留它是因为这是明确的产品功能而非移植失误；若要对齐 Android，
    /// 把 `setOwnerName` 的注入关闭即可（`AppEnvironment` 里的赋值处有标注）。
    var ownerName: String?
    /// 短记忆上限，Rust 默认 **200**。
    var workingMemoryLimit: Int?
    /// 生图协议文本（Rust 侧 `setting_str("image_gen_rules")` 读取）。M3 接入。
    var imageGenRules: String?

    enum CodingKeys: String, CodingKey {
        case role
        case timezone
        case ownerName = "owner_name"
        case workingMemoryLimit = "working_memory_limit"
        case imageGenRules = "image_gen_rules"
    }

    init(
        role: String? = nil,
        timezone: String? = nil,
        ownerName: String? = nil,
        workingMemoryLimit: Int? = nil,
        imageGenRules: String? = nil
    ) {
        self.role = role
        self.timezone = timezone
        self.ownerName = ownerName
        self.workingMemoryLimit = workingMemoryLimit
        self.imageGenRules = imageGenRules
    }

    /// 用系统当前时区填充 `timezone`。
    ///
    /// 这一步**不能省**：Rust 侧 `chrono::Local::now()` 与注入的 `timezone`
    /// 当前并不联动（见文档 §4.2 的 R8），留空会让时间感知退化为 UTC。
    ///
    /// 对应 Android `buildSettingsJson()`：`role` + `timezone` + `working_memory_limit`。
    /// **不发** `session_id`（Android 无此键）。
    static func withSystemTimezone(role: String = "GIRLFRIEND") -> AgentSettings {
        AgentSettings(
            role: role,
            timezone: TimeZone.current.identifier,
            workingMemoryLimit: 200
        )
    }

    func jsonString() -> String { AgentJSON.encode(self) }
}

// MARK: - credentials_json

/// API 凭证覆盖。
///
/// **为什么必须有**：Room 里的 `api_configs.apiKey` 是 Tink 密文（`enc:v4:...`），
/// Rust 无法解密，必须由宿主解密后经此传入明文。PARTNER 的会话头同样走这里。
struct AgentCredentials: Codable, Sendable, Equatable {

    /// 明文主 API Key（Rust `all_api_keys()` 优先取它）。
    var apiKey: String?
    /// 逗号分隔的额外 Key（Rust 会 split(',') 并 trim）。
    var extraApiKeys: String?
    /// PARTNER：注入为 `X-LianYu-Session` 头。
    var session: String?
    /// PARTNER：用于 `X-LianYu-Client-Id` 头，并作为签名回调的 `client_id` 入参。
    var clientId: String?

    enum CodingKeys: String, CodingKey {
        case apiKey = "api_key"
        case extraApiKeys = "extra_api_keys"
        case session
        case clientId = "client_id"
    }

    init(
        apiKey: String? = nil,
        extraApiKeys: String? = nil,
        session: String? = nil,
        clientId: String? = nil
    ) {
        self.apiKey = apiKey
        self.extraApiKeys = extraApiKeys
        self.session = session
        self.clientId = clientId
    }

    /// 从 Keychain 装配（iOS 侧的凭证真源）。
    ///
    /// Rust 侧读取点（`native_gateway.rs`）：
    ///   `api_key` / `extra_api_keys` → `all_api_keys()`
    ///   `session` / `client_id`      → `provider_headers()` 的 PARTNER 分支
    ///
    /// - Parameters:
    ///   - isPartner: 当前启用配置是否为 PARTNER。
    ///     **false 时不下发 session / client_id** —— 对应 Android
    ///     `syncRuntimeConfig` 的 `sessionToken = if (isPartner) ... else null`。
    ///     非 PARTNER 走 OpenAI 标准 Bearer，发这两个键没有意义，
    ///     且会让发出的 JSON 与 Android 不同。
    ///   - apiKey: 已经解析好的 API Key。**调用方必须传**（第 186 轮）。
    ///
    ///     ⚠️ 为什么不让本方法自己读 Keychain：第 186 轮起 key 是
    ///     **按配置**存放的（`api_key_<configId>`），只有调用方知道当前
    ///     生效的是哪条配置。让这里自己读，就会退回到那个"所有配置共用
    ///     一个槽"的旧行为 —— 也就是本次要修的那个 bug。
    static func fromKeychain(isPartner: Bool, apiKey: String) -> AgentCredentials {
        AgentCredentials(
            apiKey: apiKey.isEmpty ? nil : apiKey,
            extraApiKeys: nil,
            session: isPartner ? KeychainStore.string(for: KeychainStore.Key.partnerToken) : nil,
            clientId: isPartner ? KeychainStore.string(for: KeychainStore.Key.partnerClientId) : nil
        )
    }

    func jsonString() -> String { AgentJSON.encode(self) }

    /// 自定义编码 —— 逐字复刻 Kotlin `AgentFacade.buildCredentialsJson`：
    /// ```kotlin
    /// if (!sessionToken.isNullOrBlank() && !clientId.isNullOrBlank()) {
    ///     json.put("session", sessionToken); json.put("client_id", clientId)
    /// }
    /// if (!apiKey.isNullOrBlank()) json.put("api_key", apiKey)
    /// if (!extraApiKeys.isNullOrBlank()) json.put("extra_api_keys", extraApiKeys)
    /// return if (json.length() == 0) "{}" else json.toString()
    /// ```
    ///
    /// ## ⚠️ 两条不能省的规则（JSONEncoder 默认行为都不满足）
    /// 1. **空白串等同 nil，一律省略**。Kotlin 用 `isNullOrBlank()`
    ///    （nil / 空串 / 全空白都算）。而 `JSONEncoder` 会把 `""` 原样写出成
    ///    `{"api_key":"","session":""}` —— Rust 侧 `provider_headers`
    ///    会因此带上空会话头，与 Android 行为不同。
    /// 2. **`session` 与 `client_id` 要么都写、要么都不写**。少任一个时
    ///    Kotlin 连另一个也不写。若分别判断，会出现「只有 session」的挂载形态。
    ///
    /// 注：值保持原样（不 trim），与 Kotlin `put` 的语义一致。
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)

        func nonBlank(_ value: String?) -> String? {
            guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            else { return nil }
            return value
        }

        let session = nonBlank(self.session)
        let clientId = nonBlank(self.clientId)
        if let session, let clientId {
            try container.encode(session, forKey: .session)
            try container.encode(clientId, forKey: .clientId)
        }
        if let apiKey = nonBlank(self.apiKey) {
            try container.encode(apiKey, forKey: .apiKey)
        }
        if let extraApiKeys = nonBlank(self.extraApiKeys) {
            try container.encode(extraApiKeys, forKey: .extraApiKeys)
        }
    }
}

// MARK: - 回合请求组装

enum AgentRequestBuilder {

    /// 主对话路径的回合上限 —— **必须是 6**，与 Android 一致。
    ///
    /// 权威来源 `AgentDialogueCoordinator.kt`：
    /// ```kotlin
    /// val request = AgentTurnRequest(..., maxRounds = 6u, ...)
    /// ```
    /// （另有委托路径 `DelegationCoordinatorImpl` 用 3u、评估用例自定，均非聊天路径。）
    ///
    /// ## 为什么不能随手改大
    /// Rust `agent.rs` 的循环是 `while rounds_used < max_rounds`，
    /// 另有单回合工具调用硬上限 `MAX_TOOL_CALLS_PER_TURN = 64`。
    /// 因此这个值直接决定**一个回合里模型最多能调几轮工具**：
    ///   - 调大 → 同一问题下模型可以反复调工具，成本/时延上升，行为也与 Android 不同
    ///   - 调小 → 复杂问题可能没跑完就被截断
    ///
    /// ⚠️ 此外**不能传 0**：`agent.rs:1087` 是
    /// `if request.max_rounds == 0 { 1 } else { request.max_rounds }`，
    /// 传 0 会被 Rust 兜底成 1 轮，导致每个回合只能调一轮工具。
    ///
    /// 历史教训：这里曾写 16，理由是「避免 0 被兜底」—— 意图没错，
    /// 但取值是我自己定的，与 Android 的 6 不符，已改正。
    static let defaultMaxRounds: UInt32 = 6

    /// 编码历史为 `history_json`。
    /// Rust 接受数组或单个对象，这里统一输出数组。
    static func encodeHistory(_ messages: [AgentHistoryMessage]) -> String {
        AgentJSON.encode(messages)
    }

    // ⚠️ 此处**曾**有一个 `sessionId(companionId:)` 生成 `settings.session_id`
    // （返回值恒为 "ios-default"）。已删除：Android 的 `buildSettingsJson`
    // 从不发 `session_id`，多发会让 Rust 在 system prompt 注入一行「会话ID：」，
    // 使两端提示词分叉。Android 侧确认过全仓无其它 settings 写入方。
}
