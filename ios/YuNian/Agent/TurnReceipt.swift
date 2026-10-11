import CryptoKit
import Foundation
import GRDB
import os

/// **本轮收据** —— 这一轮模型到底收到了什么。
///
/// ## 为什么值得做（照 Aru 的 `contextRuntime` 那一套，不抄皮肤抄结构）
/// 用户会问"它怎么忘了"、"这次为什么答得怪"；开发时要答"上下文为什么超了"。
/// 把上下文当字符串拼的链路**答不了**这些问题 —— 必须留下**装箱清单**：
/// 带了哪些材料、各占多少、哪些被跳过（以及为什么）、供应商回执的用量。
///
/// ## 数据全部来自**引擎的真实产物**，不是本机模拟
/// | 部分 | 来源 |
/// |---|---|
/// | 材料片段（id / source / layer / lifetime / 字数） | `PromptOrchestrator.buildFragments(...)`，**与回合同一套 options** |
/// | 系统提示词指纹 | 同一批片段拼接后的 SHA-256（与 Android `prompt_audit.systemPromptHash` 同口径） |
/// | 工具名 | 本回合实际下发的工具（`AgentToolCatalog.gatedDefinitions`） |
/// | 供应商回执用量 | `AgentTurnResult.events` 里 `kind == "usage"` 的事件（Rust `agent.rs:1294` 推的） |
/// | 轮数 / 结束原因 | `AgentTurnResult` |
///
/// ## ⚠️ 两种口径**不能相加**（这一条是事实，不是保守）
/// 「本机估算」（按片段字数）与「供应商回执」（真实 token）是两把尺子：
/// 前者在请求前就能算，后者只有服务端知道（含缓存命中与新算输入）。
/// 所以界面上**并列显示、各自标注**，绝不加成一个"总用量"。
/// Token 那一节还会写明"缓存读 / 新算输入来自供应商回执"。
struct TurnReceipt: Equatable {

    /// 与本轮助手消息共用的 `turnId`（`prompt_audit.sessionId` 存的也是它）。
    var turnId: String
    var timestamp: Int64
    var companionId: Int64
    var provider: String
    var model: String
    /// 本机估算的材料清单（逐片段）。
    var segments: [Segment]
    /// 供应商回执（可能没有：降级路径或供应商不回 `usage`）。
    var usage: Usage?
    var roundsUsed: UInt32
    var finishReason: String
    /// 本回合实际下发给模型的工具名。
    var toolNames: [String]
    /// 系统提示词指纹（SHA-256 前 16 位，够比对变更）。
    var systemPromptHash: String
    /// 被**主动跳过**的部分及原因（"用户关闭了 X"这类），逐条写出来。
    var skipped: [String]

    /// 一个材料片段（本机估算口径）。
    struct Segment: Equatable, Identifiable {
        /// 片段唯一标识，如 `memory.recall.companion_42`。
        var id: String
        /// safety / skill / memory / env / persona / lorebook …
        var source: String
        /// 0..=5，拼装顺序依据（L0 环境 → L5 记忆）。
        var layer: UInt8
        /// stable / task / status / external
        var lifetime: String
        /// 字符数（本机估算的**唯一**依据就是它）。
        var chars: Int

        /// 界面上的中文层名（未知层回退成 `L<n>`）。
        var layerTitle: String {
            switch layer {
            case 0: return "L0 环境"
            case 1: return "L1 人设"
            case 2: return "L2 安全"
            case 3: return "L3 技能"
            case 4: return "L4 记忆技能"
            case 5: return "L5 记忆"
            default: return "L\(layer)"
            }
        }

        /// 来源的中文名（未知来源回退成原名 —— 引擎加了新来源也不会显示空白）。
        var sourceTitle: String {
            switch source {
            case "safety": return "安全说明"
            case "skill": return "技能"
            case "memory": return "记忆"
            case "env": return "环境"
            case "persona": return "人设"
            case "lorebook": return "世界书"
            default: return source
            }
        }
    }

    /// 供应商回执的用量（真实 token）。
    struct Usage: Equatable {
        var promptTokens: Int
        var completionTokens: Int
        var totalTokens: Int
        /// 本回合累计了几次请求（多轮工具调用会 > 1）。
        var requests: Int

        static func + (lhs: Usage, rhs: Usage) -> Usage {
            Usage(promptTokens: lhs.promptTokens + rhs.promptTokens,
                  completionTokens: lhs.completionTokens + rhs.completionTokens,
                  totalTokens: lhs.totalTokens + rhs.totalTokens,
                  requests: lhs.requests + rhs.requests)
        }

        /// 从用量事件的 JSON 解析一次回执。
        ///
        /// 键名与 OpenAI 兼容协议一致（Rust 原样把 `usage` 对象序列化出来，
        /// 见 agent.rs:1294-1301）：`prompt_tokens` / `completion_tokens` / `total_tokens`。
        /// 缺 `total_tokens` 时按 prompt + completion 兜底。
        static func parse(json: String) -> Usage? {
            guard let data = json.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            else { return nil }
            func int(_ key: String) -> Int? {
                if let n = obj[key] as? NSNumber { return n.intValue }
                if let n = obj[key] as? Double { return Int(n) }
                return nil
            }
            guard let prompt = int("prompt_tokens"), let completion = int("completion_tokens") else {
                return nil
            }
            return Usage(promptTokens: prompt,
                         completionTokens: completion,
                         totalTokens: int("total_tokens") ?? (prompt + completion),
                         requests: 1)
        }

        /// 把一整个回合里所有 `usage` 事件累加起来（**多轮要相加**：
        /// 每一轮都把上下文重发了一遍，真实花费就是各轮之和）。
        static func parseAll(events: [AgentEvent]) -> Usage? {
            let parsed = events
                .filter { $0.kind == "usage" }
                .compactMap { Usage.parse(json: $0.text) }
            guard !parsed.isEmpty else { return nil }
            return parsed.dropFirst().reduce(parsed[0], +)
        }
    }

    // MARK: - 采集

    /// 采集本轮的材料清单。
    ///
    /// ⚠️ **必须在请求之前调用**，且用与回合**同一套** options ——
    /// 记忆/技能的选择会随回合推进而变化，事后重算会得到一份"看起来对、
    /// 其实不是这一轮"的清单（那比没有收据更糟）。
    ///
    /// - Parameters:
    ///   - orchestrator: 与 `AgentGlobalConfig` 同一个编排器
    ///   - options: 与回合完全一致的编排选项
    ///   - query: 本轮的用户输入（记忆/技能召回按它选）
    static func captureManifest(orchestrator: PromptOrchestrator?,
                                companion: CompanionProfile?,
                                companionId: Int64?,
                                groupId: Int64?,
                                query: String,
                                options: PromptOrchestratorOptions,
                                toolNames: [String],
                                toolSettings: ToolSettings,
                                contextSettings: ContextSettings,
                                droppedHistory: Int) -> TurnManifest {
        // ── 被跳过的部分：**逐条写明白**（这一节才是"它怎么忘了"的答案）──
        var skipped: [String] = []
        if droppedHistory > 0 {
            skipped.append("历史消息只送最近 \(contextSettings.historyLimit) 条，"
                + "最早的 \(droppedHistory) 条未送入")
        }
        if !toolSettings.hiddenToolsets.isEmpty {
            skipped.append("用户关闭了工具集："
                + toolSettings.hiddenToolsets.sorted().joined(separator: " / ")
                + "（对应工具未下发给模型）")
        }
        if toolNames.isEmpty {
            skipped.append("本回合没有可下发的工具")
        }

        guard let orchestrator else {
            return TurnManifest(segments: [], systemPromptHash: "",
                                toolNames: toolNames, skipped: skipped + ["编排器未就绪，材料清单无法采集"])
        }
        let fragments = orchestrator.buildFragments(
            companion: companion, companionId: companionId, groupId: groupId,
            query: query, options: options
        )
        let segments = fragments.compactMap { fragment -> Segment? in
            let content = fragment.content
            guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            return Segment(id: fragment.id, source: fragment.source, layer: fragment.layer,
                           lifetime: fragment.lifetime, chars: content.count)
        }
        // 指纹按**片段顺序 + 内容**算：内容变了指纹就要变
        // （与 Android 的 systemPromptHash 同用途：变更检测/去重）。
        let joined = fragments
            .filter { !$0.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .map { "\($0.id)\u{1}\($0.content)" }
            .joined(separator: "\u{2}")
        let digest = SHA256.hash(data: Data(joined.utf8))
        let hash = digest.compactMap { String(format: "%02x", $0) }.joined()
        return TurnManifest(segments: segments,
                            systemPromptHash: String(hash.prefix(16)),
                            toolNames: toolNames,
                            skipped: skipped)
    }

    /// 本机估算：材料一共多少字符（**不是 token** —— 界面上要这么写）。
    var estimatedChars: Int { segments.reduce(0) { $0 + $1.chars } }
}

/// 请求前采集好的材料清单（与回合结束后的用量一起组成 `TurnReceipt`）。
///
/// 单独一个类型，是为了让"采集"与"组装"的时序不可混淆：
/// 清单**只能**在请求之前算（记忆/技能召回随对话变化），
/// 而用量**只能**在回合之后拿。两者拼在一起才是收据。
struct TurnManifest: Equatable {
    var segments: [TurnReceipt.Segment]
    var systemPromptHash: String
    var toolNames: [String]
    /// 被主动跳过的部分及原因（逐条人话）。
    var skipped: [String]
}

// MARK: - 落库 / 回读
/// 收据的持久化。
///
/// ## 为什么落在 `prompt_audit` 与 `token_usage` 两张**既有**表里
/// 这两张表是 schema v45 里与 Android 共用的、且有闸门逐字比对 ——
/// 里面本来就有为此准备的列（`fragmentsJson` / `systemPromptHash` / `toolNames`
/// 与 `inputTokens` / `outputTokens` / `requestCount`）。
/// **iOS 此前一行都没写过**（`token_usage` 只有备份恢复会写），
/// 于是"用量页做不了"、"为什么这样回复无从查"两件事同时成立。
///
/// 不新增表、不改 schema。
enum TurnReceiptStore {

    private static let log = Logger(subsystem: "com.yunian.ai", category: "agent.receipt")

    /// 写入一条审计行。`fragmentsJson` 的结构与 Android `dry_run` 的摘要一致：
    /// `[{"id","source","layer","lifetime","chars"}]`。
    static func saveAudit(_ receipt: TurnReceipt, database: YuNianDatabase?) {
        guard let database else { return }
        let fragmentsJson = encodeSegments(receipt.segments)
        do {
            try database.pool.write { db in
                try db.execute(sql: """
                    INSERT INTO prompt_audit
                      (timestamp, companionId, groupId, sessionId, promptVersion,
                       fragmentsJson, roundsUsed, systemPromptHash, toolNames)
                    VALUES (?, ?, NULL, ?, 3, ?, ?, ?, ?)
                    """, arguments: [
                        receipt.timestamp,
                        receipt.companionId,
                        receipt.turnId,
                        fragmentsJson,
                        Int(receipt.roundsUsed),
                        receipt.systemPromptHash,
                        receipt.toolNames.joined(separator: ","),
                    ])
            }
        } catch {
            // 审计失败不影响对话
            log.error("提示词审计落库失败：\(error.localizedDescription, privacy: .public)")
        }
    }

    /// 按日累计用量。
    ///
    /// **两行**：一行记在该伴侣名下、一行记在 `companionId = -1`（全局），
    /// 与 Android `TokenUsageRepository.recordTokenUsage` 逐字一致 ——
    /// 单写伴侣行会让"总计"把跨伴侣的用量漏掉，而这两张表的唯一索引
    /// 正是 `(companionId, date, deviceId)`。
    static func recordTokenUsage(_ usage: TurnReceipt.Usage,
                                 companionId: Int64,
                                 database: YuNianDatabase?,
                                 now: Date = Date()) {
        guard let database else { return }
        let date = Self.dateString(now)
        let timestamp = Int64(now.timeIntervalSince1970 * 1000)
        do {
            try database.pool.write { db in
                for id in [companionId, -1] {
                    try db.execute(sql: """
                        INSERT INTO token_usage
                          (companionId, date, inputTokens, outputTokens, totalTokens,
                           requestCount, timestamp, deviceId)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(companionId, date, deviceId) DO UPDATE SET
                          inputTokens = inputTokens + excluded.inputTokens,
                          outputTokens = outputTokens + excluded.outputTokens,
                          totalTokens = totalTokens + excluded.totalTokens,
                          requestCount = requestCount + excluded.requestCount,
                          timestamp = excluded.timestamp
                        """, arguments: [
                            id, date,
                            usage.promptTokens, usage.completionTokens, usage.totalTokens,
                            usage.requests, timestamp, DeviceIdentity.deviceId,
                        ])
                }
            }
        } catch {
            log.error("用量落库失败：\(error.localizedDescription, privacy: .public)")
        }
    }

    /// 按 `turnId` 回读审计行（重启后仍能看清单：材料/轮数/工具/指纹）。
    ///
    /// ⚠️ **没有 token**：`prompt_audit` 不存用量，而 `token_usage` 是按日聚合的。
    /// 所以重启后打开的收据里，"供应商回执"那一节要如实显示"本轮未留存"，
    /// 而不是拿当天的累计值冒充。
    static func loadAudit(turnId: String, database: YuNianDatabase?) -> TurnReceipt? {
        guard let database, !turnId.isEmpty else { return nil }
        do {
            return try database.pool.read { db -> TurnReceipt? in
                guard let row = try Row.fetchOne(db, sql: """
                    SELECT timestamp, companionId, fragmentsJson, roundsUsed,
                           systemPromptHash, toolNames
                    FROM prompt_audit WHERE sessionId = ? ORDER BY id DESC LIMIT 1
                    """, arguments: [turnId]) else { return nil }
                return TurnReceipt(
                    turnId: turnId,
                    timestamp: row["timestamp"] as Int64? ?? 0,
                    companionId: row["companionId"] as Int64? ?? 0,
                    provider: "",
                    model: "",
                    segments: decodeSegments(row["fragmentsJson"] as String? ?? "[]"),
                    usage: nil,
                    roundsUsed: UInt32(row["roundsUsed"] as Int? ?? 0),
                    finishReason: "",
                    toolNames: (row["toolNames"] as String? ?? "")
                        .split(separator: ",").map(String.init).filter { !$0.isEmpty },
                    systemPromptHash: row["systemPromptHash"] as String? ?? "",
                    skipped: []
                )
            }
        } catch {
            log.error("读取收据失败：\(error.localizedDescription, privacy: .public)")
            return nil
        }
    }

    // MARK: - JSON

    static func encodeSegments(_ segments: [TurnReceipt.Segment]) -> String {
        let array: [[String: Any]] = segments.map { segment in
            [
                "id": segment.id,
                "source": segment.source,
                "layer": Int(segment.layer),
                "lifetime": segment.lifetime,
                "chars": segment.chars,
            ]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: array),
              let text = String(data: data, encoding: .utf8) else { return "[]" }
        return text
    }

    static func decodeSegments(_ json: String) -> [TurnReceipt.Segment] {
        guard let data = json.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else { return [] }
        return array.compactMap { item in
            guard let id = item["id"] as? String else { return nil }
            let layer = (item["layer"] as? NSNumber)?.uint8Value ?? 0
            return TurnReceipt.Segment(
                id: id,
                source: item["source"] as? String ?? "",
                layer: layer,
                lifetime: item["lifetime"] as? String ?? "",
                chars: (item["chars"] as? NSNumber)?.intValue ?? 0
            )
        }
    }

    /// Android 同款日期串（`yyyy-MM-dd`，本机时区）—— 两边共用一张表，
    /// 格式不一致会让"今天用了多少"在两个端上对不上。
    static func dateString(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }
}

// MARK: - 与真实回合**同源**的编排参数

/// 组装"与真实回合完全一致"的编排器与选项。
///
/// ## 为什么必须只有一份
/// 回合的参数由 **Rust 自己**组装（`agent.rs::orchestration_options`），
/// 宿主看不到；而收据要显示"模型收到了什么"，就必须在宿主侧重算一遍。
/// 这份重算一旦与 Rust 那套分叉，收据就会给出**看起来精确的假证据** ——
/// 那比没有收据更糟。
///
/// 所以：**诊断页与回合都在这里取参数**，任何一处都不许自己另写一份。
/// 每个字段都注明了它在 Rust 侧的对应位置，改动时照着核。
enum ContextProbe {

    /// 与 `AgentGlobalConfig.orchestrator` 同一个编排器。
    static func makeOrchestrator(stores: AgentStores?) -> PromptOrchestrator? {
        guard let stores else { return nil }
        return PromptOrchestrator(memory: MemorySelector(store: stores),
                                  skill: SkillSelector(store: stores))
    }

    /// 逐项对齐 `agent.rs:963-990` 的 `orchestration_options`。
    ///
    /// ⚠️ 四个"宿主改不了"的字段（`memoryLimit` / `skillLimit` /
    /// `includeMemorySkill` / `includeSafetyNote`）写的是 Rust 的
    /// `PromptOrchestratorOptions::default()` 值 —— Rust 不读任何 settings 键，
    /// 所以界面上也**不给**对应开关（见 `ContextSettings` 的说明）。
    static func makeOptions(ownerName: String?,
                            timezone: String,
                            workingMemoryLimit: Int,
                            toolNames: [String]) -> PromptOrchestratorOptions {
        PromptOrchestratorOptions(
            memoryLimit: 5,
            skillLimit: 3,
            includeMemorySkill: true,
            includeSafetyNote: true,
            availableTools: toolNames,
            deviceId: DeviceIdentity.deviceId,
            timezone: timezone,
            // iOS 从不发 session_id（与 Android 一致：那会让服务端做会话粘滞）
            sessionId: nil,
            ownerName: ownerName,
            companionNameMapJson: nil,
            workingMemoryLimit: UInt32(max(0, workingMemoryLimit))
        )
    }

    /// 伴侣档案 —— 与 `native_gateway.rs::to_profile` 逐字段对应。
    ///
    /// Rust 是从库里现读的（`load_companion_profile`），宿主侧只能照着同一行
    /// 重建；字段少一个，收据里就会少一个片段。
    static func makeProfile(_ companion: CompanionRepository.Companion?,
                            role: String) -> CompanionProfile? {
        guard let companion else { return nil }
        return CompanionProfile(
            name: companion.name,
            age: companion.age.map(Int64.init),
            personality: companion.personality,
            speakingStyle: companion.speakingStyle,
            backstory: companion.backstory,
            rawPrompt: companion.rawPrompt,
            customPrompt: companion.systemPrompt,
            role: role
        )
    }
}

/// 上下文选项 —— **只放真能生效的**。
///
/// ## 为什么只有两项
/// 用户在"上下文"这件事上能控制什么，取决于**引擎从哪里读**：
///
/// | 选项 | 引擎读法 | 能否真生效 |
/// |---|---|---|
/// | 历史条数上限 | 宿主侧 `historyForRequest()` 自己截断 | ✅ 本机就管用 |
/// | WORKING 短期记忆上限 | Rust 读 `settings_json.working_memory_limit`（agent.rs:986） | ✅ 下回合生效 |
/// | 记忆召回条数 `memory_limit` | Rust 用 `PromptOrchestratorOptions::default()`（固定 5），**不读任何 settings 键** | ❌ 放上去就是假开关 |
/// | L2 安全说明 / L4 记忆技能说明 | 同样写死在 default 里（prompt_orchestrator.rs:117-118） | ❌ 同上 |
///
/// 所以这里**只放前两项**。后两项要做得先让 Rust 读对应 settings 键（一次跨端改动）。
/// 界面上宁可少一个开关，也不要一个拨了没反应的开关 ——
/// 那类"假生效"是这一轮之前踩过最多的一类坑。
struct ContextSettings: Equatable {

    /// 送入模型的历史消息**条数上限**（本机截断）。
    var historyLimit: Int = ContextSettings.defaultHistoryLimit
    /// WORKING 短期记忆注入上限（引擎读 `settings_json.working_memory_limit`）。
    var workingMemoryLimit: Int = ContextSettings.defaultWorkingMemoryLimit

    /// 与 `loadHistory(limit:)` 的既有行为一致。
    static let defaultHistoryLimit = 100
    /// 与 `AppEnvironment` 此前的硬编码值一致。
    static let defaultWorkingMemoryLimit = 200

    static let historyLimitRange = 10...200
    static let workingMemoryLimitRange = 20...500

    static let historyLimitKey = "context.history_limit"
    static let workingMemoryLimitKey = "context.working_memory_limit"

    static func load(from repo: ApiConfigRepository) -> ContextSettings {
        var settings = ContextSettings()
        // ⚠️ `try?` 已拍平成一层可选（Swift 5+），只解一次。
        if let raw = try? repo.metaValue(forKey: historyLimitKey),
           let value = Int(raw), historyLimitRange.contains(value) {
            settings.historyLimit = value
        }
        if let raw = try? repo.metaValue(forKey: workingMemoryLimitKey),
           let value = Int(raw), workingMemoryLimitRange.contains(value) {
            settings.workingMemoryLimit = value
        }
        return settings
    }

    func save(to repo: ApiConfigRepository) throws {
        try repo.setMetaValue(String(historyLimit), forKey: Self.historyLimitKey)
        try repo.setMetaValue(String(workingMemoryLimit), forKey: Self.workingMemoryLimitKey)
    }
}
