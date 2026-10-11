import Foundation
import os

/// 工具目录与执行器装配（M3）。
///
/// ## 对应 Android 的两段式
/// ```kotlin
/// tools = (AgentFacade.memoryToolDefinitions(context) +      // ← 来自 Rust MemorySelector
///     AgentFacade.skillToolDefinitions() +                   // ← Kotlin 侧定义
///     AgentFacade.toolDefinitionsFor(companionId, availableTools()))
/// ```
/// iOS 目前只做前两段（第三段依赖尚未实现的领域 ToolRegistry）。
///
/// ## `load_skill` 不是可选项
/// 两处 Rust 逻辑以它存在为前提：
/// ```rust
/// // prompt_orchestrator.rs:270 —— 技能目录菜单的注入条件
/// || options.available_tools.iter().any(|tool| tool == "load_skill")
/// ```
/// 不加它，`[可用技能目录]` 菜单永远不会注入，模型也就不知道有技能可用。
///
/// ## 执行器
/// 记忆工具直接转发 Rust（`MemorySelector.executeMemoryTool`），
/// `load_skill` 走 `SkillSelector.loadContent` —— 两端语义由此保持一致。
enum AgentToolCatalog {

    private static let log = Logger(subsystem: "com.yunian.ai", category: "agent.tools")

    /// 装配本轮要下发的工具定义，并注册对应执行器。
    ///
    /// - Parameters:
    ///   - memory: 记忆选择器（提供定义与执行）
    ///   - skill: 技能选择器（提供 `load_skill` 的正文读取）
    ///   - host: 工具宿主（Rust 通过它回调执行），类型是 `AgentToolHostImpl`
    ///
    /// ⚠️ **不接收 companionId**：`load_skill` 必须用**调用时**的伴侣，
    /// 而不是装配时冻结的值。见该执行器里的说明。
    static func install(
        memory: MemorySelector?,
        skill: SkillSelector?,
        host: AgentToolHostImpl
    ) {
        var definitions: [ToolDefinition] = []

        // ── 记忆工具（定义来自 Rust，天然一致）──
        if let memory {
            definitions.append(contentsOf: memory.memoryToolDefinitions())
            for tool in memory.memoryToolDefinitions() {
                // ⚠️ 第 77 轮：闭包原本写成 `{ _, argsJson, contextJson in`（3 参），
                // 但 `AgentToolHostImpl.Handler` 是 **2 参**（argumentsJSON, contextJSON）
                // —— toolName 已由 `execute` 按 register 时的 key 查表得到，
                // 不需要再传给 handler。
                // CI 报：Contextual closure type '@Sendable (String, String) -> String'
                // expects 2 arguments, but 3 were used in closure body。
                host.register(tool.name) { argsJson, contextJson in
                    memory.executeMemoryTool(name: tool.name, argsJson: argsJson, contextJson: contextJson)
                }
            }
        }

        // ── 设备类工具（M4 批次2）──
        definitions.append(contentsOf: DeviceTools.allDefinitions())
        DeviceTools.install(host: host)

        // ── load_skill（定义逐字对应 Android skillToolDefinitions()）──
        if let skill {
            let loadSkill = ToolDefinition(
                name: "load_skill",
                description: "按需加载一个技能的完整操作说明。触发条件：当系统提示词中 [可用技能目录] 列出了某技能、且当前用户请求与该技能描述的场景匹配时，必须先调用本工具加载其完整规则再执行。约束：与当前对话无关的技能不要加载；加载后按技能正文执行。",
                parametersJson: #"{"type":"object","properties":{"skill_id":{"type":"string","minLength":1,"description":"技能 ID（目录中的编号名，如 builtin_chat_tool_protocol）"}},"required":["skill_id"],"additionalProperties":false}"#,
                category: ToolCategory.chat,
                toolsets: ["skill"],
                available: true
            )
            definitions.append(loadSkill)

            // ⚠️ 第 77 轮：handler 是 2 参（argumentsJSON, contextJSON），
            // toolName 由 execute 按 register key 查表得到，不传进来。
            host.register("load_skill") { argsJson, contextJson in
                // ⚠️ companionId 必须从 contextJson 现取，**不能**用装配时冻结的值。
                // 曾按装配时传入写，导致：
                //   1. install 发生在 boot()，那会儿还没有绑定伴侣 → 恒为 nil
                //   2. Rust 的 `load_content` 用 companion_id 过滤技能列表
                //      （`list_skills(nil)` 只返回全局技能）
                //   3. 于是伴侣专属技能的 load_skill 必然返回「未找到」
                // contextJson 形如 {"companion_id":123,"group_id":null}
                // （见 memory_selector.rs:350 的同一份上下文约定）。
                let companionId = Self.companionId(fromContextJson: contextJson)

                guard let data = argsJson.data(using: .utf8),
                      let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let skillId = (obj["skill_id"] as? String)?.trimmingCharacters(in: .whitespaces),
                      !skillId.isEmpty
                else {
                    return "load_skill 失败：缺少 skill_id 参数"
                }
                guard let content = skill.loadContent(skillId: skillId, companionId: companionId),
                      !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                else {
                    return "未找到技能 \(skillId) 或其正文为空"
                }
                return content
            }
        }

        installedDefinitionNames = definitions.map(\.name)
        self.definitions = definitions
        log.info("已装配 \(definitions.count) 个工具：\(installedDefinitionNames.joined(separator: ", "), privacy: .public)")
    }

    /// 从 Rust 传来的 contextJson 里取 companion_id。
    private static func companionId(fromContextJson json: String) -> Int64? {
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }
        if let n = obj["companion_id"] as? NSNumber { return n.int64Value }
        if let s = obj["companion_id"] as? String { return Int64(s) }
        return nil
    }

    /// 已装配的工具名（自检面板显示用）。
    private(set) static var installedDefinitionNames: [String] = []

    /// 本轮下发给 Rust 的工具定义（由 `install(...)` 填充）。
    ///
    /// ⚠️ 这是**完整目录**。真正下发给模型的是 `gatedDefinitions(_:)` ——
    /// 别直接把这一份传给 `AgentTurnRequest.tools`，那会绕过用户的可见性开关。
    private(set) static var definitions: [ToolDefinition] = []

    /// 下发给模型的工具定义：先按**模型可见性**过滤，再按**写入确认**改类别。
    ///
    /// ## 可见性
    /// 命中被隐藏工具集的工具**直接不出现在列表里** —— 模型连"有这个工具但不可用"
    /// 都看不到（`available=false` 仍会告诉模型它存在）。`toolsets` 为空的
    /// 「通用」工具不受开关影响（它们没有归属，开关也无从表达）。
    ///
    /// ## 写入确认
    /// 需要确认的工具在**下发副本**里把 `category` 改成 `.commerce` ——
    /// Rust 对 Commerce 默认要确认（agent.rs:1347-1372）：未批准时停止回合、
    /// 产出 `confirm_request` 事件、`finished_reason="confirm_pending"`，
    /// 宿主 `approveTool` / `rejectTool` 之后重跑回合。
    /// Android 走的是同一条链（`AiTool.requiresConfirmation`
    /// → `deriveToolCategory` → Commerce），这里只是把映射放在宿主侧。
    static func gatedDefinitions(_ settings: ToolSettings) -> [ToolDefinition] {
        gated(definitions, settings: settings)
    }

    /// 门控的**纯函数**形态（便于单测：不必真装一遍目录）。
    ///
    /// 单测要能验"隐藏工具集之后少了哪些、需要确认的类别被改成什么"，
    /// 而 `definitions` 是 `install(...)` 之后才有值的静态量 ——
    /// 把判定抽成收 `tools` 参数的纯函数，测试就能直接喂一份构造出来的目录。
    static func gated(_ tools: [ToolDefinition], settings: ToolSettings) -> [ToolDefinition] {
        tools.compactMap { tool in
            if !settings.hiddenToolsets.isEmpty,
               tool.toolsets.contains(where: { settings.hiddenToolsets.contains($0) }) {
                return nil
            }
            guard settings.confirmTools.contains(tool.name) else { return tool }
            return ToolDefinition(
                name: tool.name,
                description: tool.description,
                parametersJson: tool.parametersJson,
                category: .commerce,
                toolsets: tool.toolsets,
                available: tool.available
            )
        }
    }

    /// 目录里出现过的工具集（界面据此生成开关，**不硬编码** ——
    /// 将来 Rust 加了新工具集，界面自动多一项）。
    static func knownToolsets() -> [String] {
        Array(Set(definitions.flatMap(\.toolsets))).sorted()
    }
}

/// 工具可见性与写入确认策略（P0 ③ 的**后两维**）。
///
/// ## 三个维度，此前只做了第一个
/// 「系统连接」这类页面要回答的是三件事：
///   1. **系统授权** —— 相机/麦克风/相册…（已做：三态 + 申请入口）
///   2. **模型可见性** —— 这条能力对应的工具要不要给模型看（本类型管这个）
///   3. **写入确认** —— 模型发起的**写操作**要不要先问过用户（本类型也管这个）
///
/// ## 存哪
/// `app_meta`（与 `feature_route` 同一个 KV 表）。**不新增表、不改 schema** ——
/// 那张表与 Android Room 共用且有闸门逐字比对。
/// 值用 JSON 数组：空数组 `[]` 也是一个**有效值**（"我明确把开关全关了"），
/// 而 `setMetaValue(nil/"")` 是**删键**（= 回到默认）—— 两者语义不同，不能混。
struct ToolSettings: Equatable {

    /// 被隐藏的工具集（默认空 = 全部可见，与改动前行为一致）。
    var hiddenToolsets: Set<String> = []

    /// 执行前需要用户确认的工具。**默认包含全部写操作** ——
    /// 对齐 Android 的既有裁定：「默认需确认，授权后自主」。
    var confirmTools: Set<String> = ToolSettings.defaultConfirmTools

    static let hiddenToolsetsKey = "tool_gate.hidden_toolsets"
    static let confirmToolsKey = "tool_gate.confirm_tools"

    /// 默认需要确认的写操作。
    ///
    /// ⚠️ **写死的名字必须有去处**：`verify_unique_types` 之类的闸门抓不到
    /// "工具改名后这里的字符串没跟着改"。所以测试里有一条把它们与
    /// 实际目录对上的断言（`ToolGateTests`）。
    static let defaultConfirmTools: Set<String> = [
        // 记忆写入
        "save_memory", "consolidate_memory",
        // 设备动作（会改剪贴板 / 拉起别的 App / 弹通知）
        "device_set_clipboard", "device_open_url", "device_notify",
    ]

    /// 只读的工具（界面上标出来，免得用户以为"不确认 = 危险"）。
    static let readOnlyTools: Set<String> = [
        "recall_memory", "load_skill",
        "device_get_time", "device_battery_status", "device_get_clipboard",
    ]

    // MARK: - 读写

    static func load(from repo: ApiConfigRepository) -> ToolSettings {
        var settings = ToolSettings()
        // ⚠️ 键**不存在** → 用默认；键存在但值为 `[]` → 用户明确关掉了全部。
        if let raw = try? repo.metaValue(forKey: hiddenToolsetsKey),
           let decoded = decodeSet(raw) {
            settings.hiddenToolsets = decoded
        }
        if let raw = try? repo.metaValue(forKey: confirmToolsKey),
           let decoded = decodeSet(raw) {
            settings.confirmTools = decoded
        }
        return settings
    }

    func save(to repo: ApiConfigRepository) throws {
        try repo.setMetaValue(Self.encodeSet(hiddenToolsets), forKey: Self.hiddenToolsetsKey)
        try repo.setMetaValue(Self.encodeSet(confirmTools), forKey: Self.confirmToolsKey)
    }

    private static func decodeSet(_ raw: String?) -> Set<String>? {
        guard let raw, let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [String]
        else { return nil }
        return Set(array)
    }

    private static func encodeSet(_ set: Set<String>) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: Array(set).sorted()),
              let text = String(data: data, encoding: .utf8) else { return "[]" }
        return text
    }
}
