import Foundation

/// 上下文账本（Context Ledger）—— 第 229 轮。
///
/// # 它和 `TurnReceipt` 是什么关系
///
/// `TurnReceipt`（第 207 轮）回答的是「这一轮大概带了哪些材料」，
/// 数据只到「片段名 + 字符数」。本类型回答的是**更硬的四个问题**：
///
/// 1. 模型**实际收到**了什么（按通道分组，含绕过片段机制的工具 Schema / 世界书 / 消息窗口）
/// 2. **没收到**什么，以及**为什么**（原因枚举，不是自由文本）
/// 3. 各占多少（本机估算 vs 供应商回执，**两种口径不能相加**）
/// 4. 和上一轮比**变了什么**（`statusLine`）
///
/// # 为什么解析要同时支持 v1 与 v2
///
/// `prompt_audit.fragments_json` 里的历史行是 **v1（裸数组）**，
/// 新行是 **v2（带 `"v":2` 的对象）**。两种都要能打开 ——
/// 用户翻旧账本时不能因为格式升级而看到空白。
///
/// v1 的兼容策略（三条，见 `decodeLegacy`）：
/// - 所有片段按 `admitted` 处理：现有片段**确实全部真送进请求**，不是猜测
/// - 只有 `chars` 没有 `tokens` → 按 `bytes/3.5` 估算并**标注"旧记录"**
/// - **不补**世界书/工具那两块：当时确实没记账，**倒填就是篡改历史**
struct ContextLedger: Equatable {

    /// 结构版本：1 = 旧裸数组，2 = 本轮的带状态对象。
    var version: Int

    var channels: [Channel]
    var toolLedger: ToolLedger
    var estimate: Estimate
    var diff: Diff?

    /// 是不是从旧记录（v1）还原出来的 —— 界面据此显示"旧记录"提示。
    var isLegacy: Bool

    // MARK: - 分区（账本最重要的信息架构）

    /// ⑤ 本轮带入的材料：**只列 sent == true**。
    ///
    /// 用户先看"真送进去了什么" —— 这是他要的答案。
    /// `conversation_window` 在 Aru 里不列进材料列表（独立成区块），
    /// 但**必须在账本里**，所以这里保留、由界面决定摆放位置。
    var admitted: [Channel] { channels.filter { $0.sent } }

    /// ⑥ 降级区块：**只列 sent == false**，且排除 `not_applicable`。
    ///
    /// 为什么排除 `not_applicable`：那是"我们没有这个能力"，
    /// 不是"这一轮没带" —— 混进降级区会让用户以为丢了内容。
    var degraded: [Channel] {
        channels.filter { !$0.sent && $0.status != .notApplicable }
    }

    /// 本实现没有的通道（单独一块，说明用）。
    var notApplicable: [Channel] {
        channels.filter { $0.status == .notApplicable }
    }

    /// 已送入材料的总 token。
    var admittedTokens: Int { admitted.reduce(0) { $0 + $1.tokens } }

    /// 工具 Schema 占已送入材料的比例（0...1）。
    ///
    /// 这是账本存在的**第一个实际用途**：Aru 那边它占 91.8%，
    /// 而 YuNian 只有约 10 个工具 —— 这个数字会直接告诉我们
    /// "工具裁剪该不该做"，而不是照抄别人的优先级。
    var toolSchemaShare: Double {
        let total = admittedTokens
        guard total > 0 else { return 0 }
        return Double(toolLedger.schemaTokens) / Double(total)
    }

    // MARK: - 嵌套类型

    struct Channel: Equatable, Identifiable {
        /// 稳定 id（英文）；`Identifiable` 直接用它
        var id: String
        /// 中文显示名
        var cn: String
        var status: Status
        var sent: Bool
        var tokens: Int
        var chars: Int
        var bytes: Int
        /// 纯数量（`×N`）
        var items: Int
        /// 复合计数标签（如「原话 0 · 语义命中 0 · 对话总结 3」）。
        /// 非空时界面显示它而不是 `×N`。
        var itemsLabel: String?
        var depth: Depth
        var owner: String
        /// 跨轮/状态标记行（界面第二行）。降级项固定「本次未发送」。
        var statusLine: String
        var reason: SkipReason?
        /// 原因的三分类 —— **UI 必须按它分开引导**
        var severity: Severity?
        /// 原因的一句话说明（"为什么"）
        var reasonHint: String?
        /// 结构化证据（"怎么复现"）
        var evidence: [String: String]
        /// 修复路径（"怎么办"）
        var fixPath: String?
        var fragmentCount: Int
    }

    /// 通道状态（四态）。
    enum Status: String, Equatable {
        case admitted
        case recorded
        case planned
        /// 本实现没有这条通道 —— 这是我们对 Aru 三态的补充
        case notApplicable = "not_applicable"
    }

    enum Depth: String, Equatable {
        case focused, full, index, summary
    }

    /// 未准入原因 —— 与 Rust `ledger::SkipReason` **必须逐字一致**。
    enum SkipReason: String, Equatable {
        case groupDisabled = "group_disabled"
        case imageGenerationUnavailable = "image_generation_unavailable"
        case voiceGenerationUnavailable = "voice_generation_unavailable"
        case embeddingUnavailable = "embedding_unavailable"
        case worldbookUnbound = "worldbook_unbound"
        case skippedNoTrigger = "skipped_no_trigger"
        case memoryEmpty = "memory_empty"
        case notApplicable = "not_applicable"
    }

    /// 三分类 —— 决定 UI 怎么引导，**不是装饰**。
    enum Severity: String, Equatable {
        /// 用户可改（关着的开关）→ 给"去开启"+ 跳转
        case actionable
        /// 配置缺失（线路/绑定/数据）→ 给"去配线路"+ 跳转
        case config
        /// 正常状态（本轮没命中）→ **不警示、不跳转**
        case normal
        /// 本实现无此通道 → 说明性
        case notApplicable = "not_applicable"
    }

    struct ToolLedger: Equatable {
        var visible: [String]
        var hidden: [HiddenTool]
        var perTool: [ToolCost]
        var schemaTokens: Int
        var schemaBytes: Int

        struct HiddenTool: Equatable, Identifiable {
            var name: String
            var reason: SkipReason?
            var severity: Severity?
            var id: String { name }
        }

        struct ToolCost: Equatable, Identifiable {
            var name: String
            var group: String
            var tokens: Int
            var bytes: Int
            var id: String { name }
        }
    }

    struct Estimate: Equatable {
        var tokens: Int
        var chars: Int
        var bytes: Int
        /// 估算算法（如 `bytes_per_token:3.5`）—— 写进数据，数字才可复算
        var method: String
        /// 永远是 true：它不是分词器
        var isRoughEstimate: Bool
    }

    /// 双口径对照。
    ///
    /// ⚠️ **`percent` 为 nil 时界面必须整行不渲染**（不是显示 0%、也不是 `—%`）。
    /// 理由：一行里只有"差异"绝对依赖供应商回执，没有回执就不该给对比 ——
    /// 显示 0% 是撒谎，显示 `—%` 容易被读成"未知但接近 0"。
    struct Diff: Equatable {
        /// `(供应商 − 本机) / 本机`，单位百分比
        var percent: Double?
        var providerInputTokens: Int?
        var formula: String

        /// 按 Aru 实测确认的公式算差异。
        ///
        /// **符号与基数都必须是这个**（三轮实测验算全部吻合）：
        /// ```
        /// (29198 − 30877) / 30877 = −5.44%  → 账本显示 −5.4%
        /// ```
        /// 写成 `(本机 − 供应商) / 供应商` 会得到 +5.8%，符号反、基数也不同，
        /// 与任何外部对照都不可比。
        static func compute(provider: Int?, local: Int) -> Diff {
            let formula = "(provider−local)/local"
            guard let provider, local > 0 else {
                return Diff(percent: nil, providerInputTokens: provider, formula: formula)
            }
            let value = (Double(provider) - Double(local)) / Double(local) * 100
            return Diff(percent: value, providerInputTokens: provider, formula: formula)
        }

        /// 展示用（保留 1 位、带正负号）；无数据返回 nil 让界面整行隐藏。
        var percentText: String? {
            guard let percent else { return nil }
            return String(format: "%+.1f%%", percent)
        }
    }
}

// MARK: - 解码

extension ContextLedger {

    /// 从 `prompt_audit.fragmentsJson` 解出账本。
    ///
    /// 自动识别 v1（裸数组）/ v2（对象 + `"v"`）。
    /// 返回 nil 只在**完全无法解析**时 —— 界面据此回落到旧的 `TurnReceipt` 展示。
    static func decode(fragmentsJson: String) -> ContextLedger? {
        guard let data = fragmentsJson.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) else { return nil }

        if let obj = root as? [String: Any], let version = obj["v"] as? Int, version >= 2 {
            return decodeV2(obj)
        }
        if let arr = root as? [[String: Any]] {
            return decodeLegacy(arr)
        }
        return nil
    }

    // MARK: v2

    private static func decodeV2(_ obj: [String: Any]) -> ContextLedger {
        let channels = (obj["channels"] as? [[String: Any]] ?? []).map { raw -> Channel in
            let reason = (raw["reason"] as? String).flatMap(SkipReason.init(rawValue:))
            return Channel(
                id: raw["id"] as? String ?? "",
                cn: raw["cn"] as? String ?? (raw["id"] as? String ?? ""),
                status: Status(rawValue: raw["status"] as? String ?? "") ?? .admitted,
                sent: raw["sent"] as? Bool ?? false,
                tokens: raw["tokens"] as? Int ?? 0,
                chars: raw["chars"] as? Int ?? 0,
                bytes: raw["bytes"] as? Int ?? 0,
                items: raw["items"] as? Int ?? 0,
                itemsLabel: raw["itemsLabel"] as? String,
                depth: Depth(rawValue: raw["depth"] as? String ?? "") ?? .full,
                owner: raw["owner"] as? String ?? "—",
                statusLine: raw["statusLine"] as? String ?? "",
                reason: reason,
                // severity 以数据为准（Rust 已算好）；缺失时按原因推
                severity: (raw["severity"] as? String).flatMap(Severity.init(rawValue:))
                    ?? reason.map(severityOf),
                reasonHint: raw["reasonHint"] as? String ?? reason.map(hintOf),
                evidence: (raw["evidence"] as? [String: Any] ?? [:]).reduce(into: [:]) {
                    $0[$1.key] = ($1.value as? String) ?? "\($1.value)"
                },
                fixPath: raw["fixPath"] as? String,
                fragmentCount: raw["fragmentCount"] as? Int ?? 0
            )
        }

        let toolRaw = obj["toolLedger"] as? [String: Any] ?? [:]
        let toolLedger = ToolLedger(
            visible: toolRaw["visible"] as? [String] ?? [],
            hidden: (toolRaw["hidden"] as? [[String: Any]] ?? []).map { h in
                let reason = (h["reason"] as? String).flatMap(SkipReason.init(rawValue:))
                return ToolLedger.HiddenTool(
                    name: h["name"] as? String ?? "",
                    reason: reason,
                    severity: (h["severity"] as? String).flatMap(Severity.init(rawValue:))
                        ?? reason.map(severityOf)
                )
            },
            perTool: (toolRaw["perTool"] as? [[String: Any]] ?? []).map { t in
                ToolLedger.ToolCost(
                    name: t["name"] as? String ?? "",
                    group: t["group"] as? String ?? "",
                    tokens: t["tokens"] as? Int ?? 0,
                    bytes: t["bytes"] as? Int ?? 0
                )
            },
            schemaTokens: toolRaw["schemaTokens"] as? Int ?? 0,
            schemaBytes: toolRaw["schemaBytes"] as? Int ?? 0
        )

        let estRaw = obj["localEstimate"] as? [String: Any] ?? [:]
        let estimate = Estimate(
            tokens: estRaw["tokens"] as? Int ?? 0,
            chars: estRaw["chars"] as? Int ?? 0,
            bytes: estRaw["bytes"] as? Int ?? 0,
            method: estRaw["method"] as? String ?? "未知",
            isRoughEstimate: estRaw["isRoughEstimate"] as? Bool ?? true
        )

        let diffRaw = obj["diff"] as? [String: Any] ?? [:]
        let diff = Diff(
            percent: diffRaw["percent"] as? Double,
            providerInputTokens: (diffRaw["providerInputTokens"] as? NSNumber)?.intValue,
            formula: diffRaw["formula"] as? String ?? Diff.compute(provider: nil, local: 0).formula
        )

        return ContextLedger(version: 2, channels: channels, toolLedger: toolLedger,
                             estimate: estimate, diff: diff, isLegacy: false)
    }

    // MARK: v1（历史行）

    /// 旧格式：裸数组 `[{"id","source","layer","lifetime","chars"}]`。
    ///
    /// 三条兼容规则见类型注释。这里**只做能诚实地做的事**：
    /// 已知的片段名映射到中文通道名，未知的归「未登记片段」；
    /// token 按 `bytes/3.5` 估（v1 没有 bytes，用 `chars*3` 近似 UTF-8 中文）。
    private static func decodeLegacy(_ arr: [[String: Any]]) -> ContextLedger {
        var byChannel: [String: (tokens: Int, chars: Int, bytes: Int, count: Int)] = [:]
        for raw in arr {
            let id = raw["id"] as? String ?? ""
            guard !id.isEmpty else { continue }
            let chars = raw["chars"] as? Int ?? 0
            // v1 只存 chars。中文按 3 字节估，英文按 1 —— 取 3 是**故意偏高**：
            // 宁可高估（与"向上取整"同理），不要让合计看着比实际小。
            let bytes = chars * 3
            let tokens = Int(ceil(Double(bytes) / 3.5))
            let key = legacyChannelKey(id)
            let cur = byChannel[key] ?? (0, 0, 0, 0)
            byChannel[key] = (cur.tokens + tokens, cur.chars + chars,
                              cur.bytes + bytes, cur.count + 1)
        }

        let channels = byChannel.map { key, agg -> Channel in
            let spec = Self.channelSpec(key)
            return Channel(
                id: key, cn: spec.0,
                status: .admitted, sent: true,
                tokens: agg.tokens, chars: agg.chars, bytes: agg.bytes,
                items: agg.count, itemsLabel: nil,
                depth: spec.2, owner: spec.1,
                // 旧记录没有跨轮标记 —— 如实留空，界面据此不渲染第二行
                statusLine: "",
                reason: nil, severity: nil, reasonHint: nil,
                evidence: [:], fixPath: nil,
                fragmentCount: agg.count
            )
        }.sorted { lhs, rhs in
            // 按通道表顺序排（与 v2 一致），未知的排最后
            let li = Self.channelOrder(lhs.id), ri = Self.channelOrder(rhs.id)
            return li == ri ? lhs.id < rhs.id : li < ri
        }

        let totalTokens = channels.reduce(0) { $0 + $1.tokens }
        let totalChars = channels.reduce(0) { $0 + $1.chars }
        let totalBytes = channels.reduce(0) { $0 + $1.bytes }

        return ContextLedger(
            version: 1,
            channels: channels,
            // 旧记录**没有**工具账 —— 留空并让界面标注，而不是伪造
            toolLedger: ToolLedger(visible: [], hidden: [], perTool: [],
                                   schemaTokens: 0, schemaBytes: 0),
            estimate: Estimate(tokens: totalTokens, chars: totalChars, bytes: totalBytes,
                               method: "legacy:chars*3/3.5", isRoughEstimate: true),
            diff: Diff.compute(provider: nil, local: totalTokens),
            isLegacy: true
        )
    }

    /// v1 的片段 id → 通道 id（与 Rust `channel_for_fragment` 同表）。
    static func legacyChannelKey(_ fragmentId: String) -> String {
        switch fragmentId {
        case "env.static_meta": return "runtime_clock"
        case "persona.identity", "persona.reply_language": return "system_prompt"
        case "safety.injection_note": return "safety_note"
        case "skill.chat_protocol", "skill.injected", "skill.memory_procedural": return "skills"
        case "memory.recall": return "memory"
        case "worldbook.inject", "lorebook.inject": return "worldbook"
        case "tools.overview": return "tools_overview"
        default: return "unmapped"
        }
    }

    /// 通道 → (中文名, 归属, 深度, 顺序)。
    static func channelSpec(_ id: String) -> (String, String, Depth) {
        switch id {
        case "runtime_clock": return ("请求时间", "PromptOrchestrator", .focused)
        case "conversation_window": return ("消息窗口", "ChatSession", .full)
        case "system_prompt": return ("系统指令", "PromptOrchestrator", .full)
        case "safety_note": return ("安全说明", "PromptOrchestrator", .full)
        case "skills": return ("技能", "SkillSelector", .full)
        case "memory": return ("记忆召回", "MemorySelector", .full)
        case "worldbook": return ("世界书", "LorebookRuntime", .index)
        case "tools_overview": return ("工具一览", "AgentRuntime", .index)
        case "tool_schemas": return ("工具 Schema", "AgentRuntime", .summary)
        default: return ("未登记片段", "—", .full)
        }
    }

    static func channelOrder(_ id: String) -> Int {
        let order = ["runtime_clock", "conversation_window", "system_prompt",
                     "safety_note", "skills", "memory", "worldbook",
                     "tools_overview", "tool_schemas", "tools_hidden"]
        return order.firstIndex(of: id) ?? order.count
    }

    /// 原因 → 三分类（数据里缺 `severity` 时的兜底，与 Rust 同表）。
    static func severityOf(_ reason: SkipReason) -> Severity {
        switch reason {
        case .groupDisabled: return .actionable
        case .imageGenerationUnavailable, .voiceGenerationUnavailable,
             .embeddingUnavailable, .worldbookUnbound, .memoryEmpty: return .config
        case .skippedNoTrigger: return .normal
        case .notApplicable: return .notApplicable
        }
    }

    /// 原因 → 一句话（自写措辞，不抄 Aru 文案）。
    static func hintOf(_ reason: SkipReason) -> String {
        switch reason {
        case .groupDisabled: return "你关掉了这个工具组"
        case .imageGenerationUnavailable: return "生图线路还没配"
        case .voiceGenerationUnavailable: return "朗读线路还没配"
        case .embeddingUnavailable: return "向量线路还没配，记忆召回用不了"
        case .worldbookUnbound: return "世界书没有绑定到这个角色"
        case .skippedNoTrigger: return "本轮没有命中，无需处理"
        case .memoryEmpty: return "记忆库还是空的"
        case .notApplicable: return "本实现没有这条通道"
        }
    }
}
