import XCTest
@testable import YuNian

/// `settings_json` / `credentials_json` / `history_json` 的**键名契约测试**。
///
/// ## 为什么需要
/// 这三份 JSON 是 Swift 与 Rust 之间的隐式协议，而 Rust 侧 **没有 `#[serde(rename_all)]`**，
/// 键名必须逐字是 snake_case。写错**不会报错**：
///   - `settings_json` 里键名错了 → serde 忽略未知键 → 该设置静默不生效
///   - `credentials_json` 里键名错了 → `api_key` 取不到 → 走库里的 Tink 密文 → 认证失败
///   - `history_json` 里键名错了 → 模型看不到历史（但不会报错）
///
/// 因此这里**逐个断言键名**，而不是只断言「能编码成 JSON」。
///
/// 断言方式用「解码后比对键集合」而非「比对完整字符串」：
/// 前者精确锁定契约，且不因键排序变化而误报。
final class AgentDTOsTests: XCTestCase {

    private func object(_ json: String) throws -> [String: Any] {
        let data = try XCTUnwrap(json.data(using: .utf8))
        return try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
    }

    private func array(_ json: String) throws -> [[String: Any]] {
        let data = try XCTUnwrap(json.data(using: .utf8))
        return try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        )
    }

    // MARK: - settings_json

    func testSettingsUsesSnakeCaseKeysFromRust() throws {
        let settings = AgentSettings(
            role: "BOYFRIEND",
            timezone: "Asia/Shanghai",
            ownerName: "小明",
            workingMemoryLimit: 200,
            imageGenRules: "规则"
        )
        let json = try object(settings.jsonString())

        // Rust 侧读取点：orchestration_options() 的 str_opt(...) 与 setting_str(...)
        //
        // ⚠️ 键集合**不含** session_id：Android 的 buildSettingsJson 从不发它，
        // 多发会让 Rust 往 system prompt 注入一行「会话ID：」造成两端提示词分叉。
        // owner_name 是**有意的跨端分歧**（Android 不发，iOS 的昵称功能发）。
        XCTAssertEqual(Set(json.keys), [
            "role", "timezone", "owner_name",
            "working_memory_limit", "image_gen_rules",
        ])
        XCTAssertNil(json["session_id"], "不得发送 session_id（Android 无此键）")
        XCTAssertEqual(json["role"] as? String, "BOYFRIEND")
        XCTAssertEqual(json["timezone"] as? String, "Asia/Shanghai")
        XCTAssertEqual(json["owner_name"] as? String, "小明")
        XCTAssertEqual(json["working_memory_limit"] as? Int, 200)
        XCTAssertEqual(json["image_gen_rules"] as? String, "规则")
    }

    /// `withSystemTimezone()` 是默认构造路径，必须与 Android 的默认
    /// `buildSettingsJson()` 发同样的键（role / timezone / working_memory_limit），
    /// 且**不发** session_id。
    func testDefaultSettingsMatchAndroidBuildSettingsJson() throws {
        let json = try object(AgentSettings.withSystemTimezone().jsonString())
        XCTAssertEqual(Set(json.keys), ["role", "timezone", "working_memory_limit"])
        XCTAssertEqual(json["role"] as? String, "GIRLFRIEND")
        XCTAssertEqual(json["working_memory_limit"] as? Int, 200)
        XCTAssertEqual(json["timezone"] as? String, TimeZone.current.identifier)
    }

    // MARK: - credentials_json（复刻 Android buildCredentialsJson）

    /// 全空白串等同 nil，一律省略 —— 对应 Kotlin 的 `isNullOrBlank()`。
    func testCredentialsOmitsBlankStrings() throws {
        let json = try object(
            AgentCredentials(apiKey: "   ", extraApiKeys: "", session: "\t", clientId: "").jsonString()
        )
        XCTAssertTrue(json.isEmpty, "全空白时必须是 {}，实际 \(json)")
        XCTAssertEqual(
            AgentCredentials(apiKey: "  ").jsonString(), "{}",
            "Android 在 length()==0 时返回 \"{}\""
        )
    }

    /// 非空白的值保持原样（不 trim），与 Kotlin `put` 一致。
    func testCredentialsKeepsValuesUnmodified() throws {
        let json = try object(
            AgentCredentials(apiKey: " sk-padded ").jsonString()
        )
        XCTAssertEqual(json["api_key"] as? String, " sk-padded ")
    }

    /// `session` 与 `client_id` **要么都写、要么都不写** —— 少任一个时
    /// Kotlin 连另一个也不写。
    func testCredentialsSessionAndClientIdAreAllOrNothing() throws {
        let onlySession = try object(
            AgentCredentials(session: "sess").jsonString()
        )
        XCTAssertNil(onlySession["session"], "只有 session 时不应发送")
        XCTAssertNil(onlySession["client_id"])

        let onlyClient = try object(
            AgentCredentials(clientId: "cli").jsonString()
        )
        XCTAssertNil(onlyClient["session"], "只有 client_id 时不应发送")
        XCTAssertNil(onlyClient["client_id"])

        let both = try object(
            AgentCredentials(session: "sess", clientId: "cli").jsonString()
        )
        XCTAssertEqual(both["session"] as? String, "sess")
        XCTAssertEqual(both["client_id"] as? String, "cli")
    }

    /// 空白的一方不应让另一方「半发送」。
    func testCredentialsBlankSessionDoesNotSendClientId() throws {
        let json = try object(
            AgentCredentials(session: "   ", clientId: "cli").jsonString()
        )
        XCTAssertNil(json["session"])
        XCTAssertNil(json["client_id"])
    }

    /// 正常全量装配：4 个键齐全。
    func testCredentialsFullShape() throws {
        let json = try object(
            AgentCredentials(
                apiKey: "k1", extraApiKeys: "k2,k3", session: "sess-1", clientId: "cli-1"
            ).jsonString()
        )
        XCTAssertEqual(Set(json.keys), ["api_key", "extra_api_keys", "session", "client_id"])
        XCTAssertEqual(json["api_key"] as? String, "k1")
        XCTAssertEqual(json["extra_api_keys"] as? String, "k2,k3")
        XCTAssertEqual(json["session"] as? String, "sess-1")
        XCTAssertEqual(json["client_id"] as? String, "cli-1")
    }

    /// nil 字段必须**省略**而不是写成 null。
    /// Rust 用 `as_str()` / `as_u64()` 取值，null 与缺失都返回 None、行为等价，
    /// 但省略更干净，也让两端下发内容可直接 diff。
    func testSettingsOmitsNilFields() throws {
        let json = try object(AgentSettings(role: "GIRLFRIEND").jsonString())
        XCTAssertEqual(Set(json.keys), ["role"])
        XCTAssertFalse(AgentSettings().jsonString().contains("null"))
    }

    /// 时区必须被填上 —— 否则 Rust 的时间感知会退化为 UTC（文档 §4.2 的 R8）。
    func testSettingsWithSystemTimezoneAlwaysCarriesTimezone() throws {
        let json = try object(AgentSettings.withSystemTimezone().jsonString())
        let timezone = try XCTUnwrap(json["timezone"] as? String)
        XCTAssertFalse(timezone.isEmpty)
        XCTAssertEqual(json["role"] as? String, "GIRLFRIEND")
    }

    // MARK: - credentials_json

    func testCredentialsUsesSnakeCaseKeysFromRust() throws {
        let credentials = AgentCredentials(
            apiKey: "sk-test",
            extraApiKeys: "k2,k3",
            session: "sess-1",
            clientId: "cli-1"
        )
        let json = try object(credentials.jsonString())

        // Rust 侧读取点：all_api_keys() 的 cred.get("api_key") / ("extra_api_keys")
        //                 provider_headers() 的 cred.get("session") / ("client_id")
        XCTAssertEqual(Set(json.keys), ["api_key", "extra_api_keys", "session", "client_id"])
        XCTAssertEqual(json["api_key"] as? String, "sk-test")
        XCTAssertEqual(json["extra_api_keys"] as? String, "k2,k3")
        XCTAssertEqual(json["session"] as? String, "sess-1")
        XCTAssertEqual(json["client_id"] as? String, "cli-1")
    }

    /// PARTNER 的会话键名是 `session` 与 `client_id` ——
    /// 它们分别对应 `X-LianYu-Session` / `X-LianYu-Client-Id` 头。
    /// 锁住这两个名字，因为一旦写错就是「PARTNER 静默 401」。
    func testPartnerSessionKeysMatchHeaderInjection() throws {
        let json = try object(
            AgentCredentials(session: "s", clientId: "c").jsonString()
        )
        XCTAssertNotNil(json["session"])
        XCTAssertNotNil(json["client_id"])
        XCTAssertNil(json["x_lianyu_session"], "不该用头名当 JSON 键")
    }

    // MARK: - history_json

    func testHistoryIsArrayOfRoleContentObjects() throws {
        let messages: [AgentHistoryMessage] = [
            .system("人设"),
            .user("你好"),
            .assistant("在的"),
        ]
        let json = try array(AgentRequestBuilder.encodeHistory(messages))

        XCTAssertEqual(json.count, 3)
        XCTAssertEqual(json[0]["role"] as? String, "system")
        XCTAssertEqual(json[0]["content"] as? String, "人设")
        XCTAssertEqual(json[1]["role"] as? String, "user")
        XCTAssertEqual(json[2]["role"] as? String, "assistant")
        // 普通 system 消息不带保留标记
        XCTAssertNil(json[0]["_agent_preserve_system"])
    }

    /// `_agent_preserve_system` 是 Rust 的内部保留标记，必须能正确下发。
    /// 只有世界书注入 / `[回合状态 N/M]` 这类系统消息才需要它。
    func testPreserveSystemMarkerIsEmittedOnlyForSystemMessages() throws {
        let messages: [AgentHistoryMessage] = [
            .system("worldbook", preserve: true),
            .user("hi"),
        ]
        let json = try array(AgentRequestBuilder.encodeHistory(messages))

        XCTAssertEqual(json[0]["_agent_preserve_system"] as? Bool, true)
        XCTAssertNil(json[1]["_agent_preserve_system"])
    }

    func testToolMessageUsesSnakeCaseToolCallId() throws {
        let json = try array(
            AgentRequestBuilder.encodeHistory([.tool("ok", toolCallId: "call_1")])
        )
        XCTAssertEqual(json[0]["tool_call_id"] as? String, "call_1")
        XCTAssertNil(json[0]["toolCallId"], "不该出现 camelCase 键")
    }

    /// `tool_calls` 的形状必须与 OpenAI 协议一致（Rust 对它是透传）。
    func testAssistantToolCallsUseOpenAiShape() throws {
        let message = AgentHistoryMessage.assistant(
            "",
            toolCalls: [AgentToolCall(id: "call_1", name: "emit", arguments: "{}")]
        )
        let json = try array(AgentRequestBuilder.encodeHistory([message]))

        let toolCalls = try XCTUnwrap(json[0]["tool_calls"] as? [[String: Any]])
        XCTAssertEqual(toolCalls.count, 1)
        XCTAssertEqual(toolCalls[0]["id"] as? String, "call_1")
        XCTAssertEqual(toolCalls[0]["type"] as? String, "function")

        let function = try XCTUnwrap(toolCalls[0]["function"] as? [String: Any])
        XCTAssertEqual(function["name"] as? String, "emit")
        // arguments 是**字符串**形式的 JSON，不是嵌套对象
        XCTAssertEqual(function["arguments"] as? String, "{}")
    }

    /// 思考模型的推理内容键名是 `reasoning_content`（Rust 透传，不回灌到上游协议之外）。
    /// 回放带工具调用的历史 assistant 消息时需要它。
    func testAssistantReasoningContentKey() throws {
        let message = AgentHistoryMessage.assistant("答复", reasoningContent: "想了一下")
        let json = try array(AgentRequestBuilder.encodeHistory([message]))

        XCTAssertEqual(json[0]["reasoning_content"] as? String, "想了一下")
        XCTAssertNil(json[0]["reasoningContent"], "不该出现 camelCase 键")
    }

    /// 不设置时不能出现 null 或无意义的空字段。
    func testAssistantWithoutReasoningOmitsKey() throws {
        let json = try array(AgentRequestBuilder.encodeHistory([.assistant("答复")]))
        XCTAssertNil(json[0]["reasoning_content"])
    }

    /// 空历史必须编码成 `[]` 而不是 `{}` 或 `null` —— Rust 侧靠 `as_array()` 判断。
    func testEmptyHistoryEncodesAsEmptyArray() {
        XCTAssertEqual(AgentRequestBuilder.encodeHistory([]), "[]")
    }

    // MARK: - 回合参数

    /// Rust 侧 `if request.max_rounds == 0 { 1 }` —— 宿主不该依赖这个兜底。
    ///
    /// 同时锁住**取值**：Android 主对话路径 `AgentDialogueCoordinator` 用的是 `6u`，
    /// 这个值决定一个回合里模型最多能调几轮工具，改大改小都会与 Android 行为不同。
    func testDefaultMaxRoundsMatchesAndroidDialoguePath() {
        XCTAssertGreaterThan(AgentRequestBuilder.defaultMaxRounds, 0)
        XCTAssertEqual(AgentRequestBuilder.defaultMaxRounds, 6,
                       "必须等于 Android 主对话路径的 maxRounds = 6u")
    }

    // ⚠️ 此处**曾**有 testSessionIdIsStablePerCompanion 断言
    // `AgentRequestBuilder.sessionId(...)`。该函数已删除：
    // Android 从不发 settings.session_id，iOS 发会让 Rust 在 system prompt
    // 注入一行「会话ID：」，造成两端提示词分叉。
}
