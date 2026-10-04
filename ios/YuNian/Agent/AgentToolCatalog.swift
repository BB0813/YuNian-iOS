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
    private(set) static var definitions: [ToolDefinition] = []
}
