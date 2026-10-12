import Foundation
import GRDB
import os

/// 世界书运行时同步 —— 对应 Android `WorldbookRepository.syncActiveToRuntime`。
///
/// ## 为什么这件事重要
/// Rust 的 `AgentRuntime::set_worldbook` 是**覆盖式**注入世界书内容的入口。
/// Android 在**每个回合前**都会调用它（`ChatGenerationManager`），
/// 而 iOS 侧此前从未调用 —— 也就是说用户配的世界书完全没有进入提示词。
/// 这与 `orchestrator: nil`（第 24 轮）是同一类「该接没接」的缺口。
///
/// ## 覆盖范围（第 209 轮复核：**两支都已实现**）
/// Android 的分支是：
/// ```kotlin
/// val payload = if (companionId > 0L) synthForCompanion(companionId)   // 按伴侣合成
///               else dao.active()?.json ?: ""                          // 全局启用书原文
/// AgentFacade.setWorldbook(context, payload)   // blank → null
/// ```
/// iOS 两支都在：`companionId <= 0` 取启用书原文（对应 `WorldbookDao.active()`：
/// `SELECT json FROM worldbooks WHERE enabled = 1 LIMIT 1`）；
/// `companionId > 0` 走下面的 `synthForCompanion`（绑定规则 / 按 content 去重 /
/// 排序 / `insertion_order` 重编号 / 顶层 assemble 全按契约实现）。
/// `ChatSession.send` 在每回合前调用 `syncActiveToRuntime`。
///
/// ⚠️ **这段注释此前写着「`companionId > 0` 的合成尚未移植」—— 那是过期的。**
/// 函数早已写好、也真的被调用；注释没跟着改，会让后来人以为世界书根本没进提示词。
/// "代码已改、注释没改"比没有注释更糟（第 209 轮修正）。
enum WorldbookRuntimeSync {

    private static let log = Logger(subsystem: "com.yunian.ai", category: "worldbook.sync")

    /// 同步「当前生效的世界书」到运行时。对应 Android 的 `syncActiveToRuntime`。
    ///
    /// - Parameter companionId: 伴侣 ID。`<= 0` 走全局路径；`> 0` 走按伴侣路径
    ///   （尚未移植，见类注释）。
    static func syncActiveToRuntime(
        _ runtime: AgentRuntime,
        database: YuNianDatabase,
        companionId: Int64 = 0
    ) {
        let payload: String?

        if companionId > 0 {
            payload = synthForCompanion(database, companionId: companionId)
        } else {
            payload = activeGlobalBookJson(database)
        }

        // Android: setWorldbook(if (json.isBlank()) null else json) —— 空串等价于不注入
        runtime.setWorldbook(json: (payload?.isEmpty == false) ? payload : nil)
    }

    /// 全局启用书的 JSON 原文。对应 `WorldbookDao.active()`。
    /// 注意：不做 `json != "{}"` 过滤 —— Android 这里只按 `enabled = 1` 取，
    /// 全局启用书的 JSON 原文。对应 `WorldbookDao.active()`。
    /// 注意：不做 `json != "{}"` 过滤 —— Android 这里只按 `enabled = 1` 取，
    /// "{}" 由 Rust 侧当作空书处理。
    private static func activeGlobalBookJson(_ database: YuNianDatabase) -> String? {
        do {
            return try database.pool.read { db in
                try String.fetchOne(
                    db, sql: "SELECT json FROM worldbooks WHERE enabled = 1 LIMIT 1"
                )
            }
        } catch {
            log.error("读取启用世界书失败：\(String(describing: error), privacy: .public)")
            return nil
        }
    }

    /// 某个角色这一轮**真正会带上**的世界书行（引擎绑定规则的**唯一来源**）。
    ///
    /// ## 为什么必须抽出来（第 219 轮）
    /// 角色页要显示「世界书 当前生效 N 本」。如果那里自己再写一遍过滤，
    /// 迟早会和引擎不一致 —— 表现是**界面说 0 本、引擎其实带了**（或反过来），
    /// 而两边各自看都正常、都不报错。这类"双份契约互相掩盖"在这个仓库里出过多次，
    /// 所以规则只留这一处，`synthForCompanion` 与界面都从这里取。
    ///
    /// 规则（对应 Android `effectiveRows`）：`enabled = 1` 且 json 非空且不为 `{}`，
    /// 然后按绑定关系筛：
    /// - 没有任何绑定 → 全局书 + 自己的书（**这也意味着别的角色的专属书不会漏进来**）
    /// - 有绑定 → 自己的书 + 被绑定的全局书
    private static func effectiveBookRows(_ database: YuNianDatabase,
                                         companionId: Int64) -> [BookRow] {
        let rows: [BookRow]
        do {
            rows = try database.pool.read { db in
                try BookRow.fetchAll(db, sql: """
                    SELECT id, name, json, companionId FROM worldbooks WHERE enabled = 1
                    """)
            }
        } catch {
            log.error("读取世界书失败：\(String(describing: error), privacy: .public)")
            return []
        }
        let boundIds = companionId > 0 ? boundWorldbookIds(database, companionId: companionId) : Set<Int64>()
        return rows.filter { row in
            let j = row.json.trimmingCharacters(in: .whitespaces)
            guard !j.isEmpty, j != "{}" else { return false }
            if boundIds.isEmpty {
                return companionId <= 0 || row.companionId == nil || row.companionId == companionId
            }
            return row.companionId == companionId || (row.companionId == nil && boundIds.contains(row.id))
        }
    }

    /// 「这个角色当前会用到哪些世界书」的**给界面看的摘要**。
    ///
    /// `injectsAnything = false` 是有意义的一种状态：书**规则上生效**，
    /// 但里面没有可用条目（全被停用/内容为空）→ 引擎合成结果为空串，
    /// 模型其实什么都没收到。界面必须把这两种情况分开说，
    /// 否则用户会以为"显示生效就一定注入了"。
    struct EffectiveWorldbooks: Equatable {
        var count: Int
        var names: [String]
        var injectsAnything: Bool
    }

    static func effectiveWorldbooks(_ database: YuNianDatabase,
                                    companionId: Int64) -> EffectiveWorldbooks {
        let effective = effectiveBookRows(database, companionId: companionId)
        let payload = synthForCompanion(database, companionId: companionId)
        return EffectiveWorldbooks(count: effective.count,
                                   names: effective.map(\.name),
                                   injectsAnything: !payload.isEmpty)
    }

    /// 合成某伴侣当前生效的世界书 —— 对应 Android `synthForCompanion`。
    ///
    /// ## 一个省掉整张枚举映射表的等价化简
    /// Android 走「解码 JSON → StEntry → 过滤/去重/排序/重编号 → `entryToJson` 再编码」，
    /// 其中 `position` / `role` 要经 `stToPosition` → `positionToSt` 往返。
    /// 但**源和目标都是同一个 JSON**，而 Android 写入时已用同一编解码器规范化过；
    /// 因此这里直接**保留原始字符串**，与 Android 的输出等价
    ///（也顺带避免了我尚未读取 `positionToSt` / `roleToSt` 的枚举映射）。
    static func synthForCompanion(_ database: YuNianDatabase, companionId: Int64) -> String {
        // ① 绑定规则：只在 `effectiveBookRows` 里写一遍（见那里的说明）
        let effective = effectiveBookRows(database, companionId: companionId)
        if effective.isEmpty { return "" }

        // ② 逐本解码 → 过滤
        struct Item {
            let id: Int64, keys: [String], content: String, insertionOrder: Int64
            let priority: Int, caseSensitive: Bool, useRegex: Bool, constant: Bool
            let position: Any?, role: Any?, scanDepth: Int64, depth: Int64?
            let bookId: Int64, bookName: String, sortOrder: Int
        }

        var items: [Item] = []
        for row in effective {
            guard let entries = decodeEntries(row.json) else { continue }
            for e in entries {
                guard e.enabled, let content = e.content, !content.isEmpty else { continue }
                items.append(Item(
                    id: e.id, keys: e.keys, content: content, insertionOrder: e.insertionOrder,
                    priority: e.priority, caseSensitive: e.caseSensitive, useRegex: e.useRegex,
                    constant: e.constant, position: e.position, role: e.role,
                    scanDepth: e.scanDepth, depth: e.depth,
                    bookId: row.id, bookName: row.name, sortOrder: e.sortOrder
                ))
            }
        }
        if items.isEmpty { return "" }

        // ③ 按 content 去重：priority 高者胜；**并列时后出现者胜**（>= 语义）
        var best: [String: Item] = [:]
        for item in items {
            if let prev = best[item.content] {
                if item.priority >= prev.priority { best[item.content] = item }
            } else {
                best[item.content] = item
            }
        }

        // ④ 排序：priority 降序，其次 id 升序
        let ordered = best.values.sorted { a, b in
            a.priority != b.priority ? a.priority > b.priority : a.id < b.id
        }

        // ⑤ insertion_order 全局重编号 1..N（Rust 稳定排序，键必须唯一）
        let renumbered = ordered.enumerated().map { ($1, Int64($0 + 1)) }

        // ⑥ 顶层 scan_depth = 条目 scanDepth 众数（并列取较大），空 → 10
        let scanDepth = dominantScanDepth(ordered.map(\.scanDepth))

        // ⑦ 组装 entries
        let entriesJson: [[String: Any]] = renumbered.map { item, order -> [String: Any] in
            var obj: [String: Any] = [
                "id": item.id, "keys": item.keys, "secondary_keys": [],
                "content": item.content,
                // ★ 必须显式写出：Rust 侧 enabled 默认 true，省略会让禁用条目意外生效
                "enabled": true,
                "insertion_order": order,
                "case_sensitive": item.caseSensitive,
                "use_regex": item.useRegex,
                "constant": item.constant,
                "scan_depth": item.scanDepth,
                "extensions": ["_bookId": item.bookId, "_bookName": item.bookName,
                               "_sortOrder": item.sortOrder, "_priority": item.priority],
            ]
            if let p = item.position { obj["position"] = p }
            if let r = item.role { obj["role"] = r }
            // depth 仅在源 JSON 显式带了 depth 时才写 —— 对应 Android 的
            // 「仅当 injectionPosition == AT_DEPTH 才写 depth」。因位置字符串原样保留，
            // 用「源里有没有这个键」等价表达，比猜 AT_DEPTH 的字符串值更可靠。
            if let d = item.depth { obj["depth"] = d }
            return obj
        }

        // ⑧ 顶层（assemble）：name 取 core?.name ?: "世界书"
        let name = effective.first?.name ?? "世界书"
        let root: [String: Any] = [
            "name": name, "entries": entriesJson,
            "scan_depth": scanDepth, "token_budget": 10_000,
        ]

        guard let data = try? JSONSerialization.data(withJSONObject: root),
              let text = String(data: data, encoding: .utf8)
        else { return "" }

        // 运行时断言（对应 Android 的 entryCount 复核）
        let actual = decodeEntryCount(text)
        if actual != entriesJson.count {
            log.warning("世界书合成条目数异常：期望 \(entriesJson.count) 实际 \(actual)")
        }
        return text
    }

    // MARK: - 界面预览用的条目摘要

    /// 一条世界书条目的摘要（**仅供界面预览**）。
    ///
    /// 与引擎合成走的是**同一个解码器**（`decodeEntries`）——
    /// 这正是它存在的理由：界面若自己再写一份解析，两边迟早分叉，
    /// 表现是"界面说有 12 条、实际只注入了 9 条"，而且查不出来。
    struct EntrySummary: Equatable, Identifiable {
        /// ForEach 用的唯一键。
        ///
        /// ⚠️ 不能直接用 ST 的条目 id：array 形态里条目常常**没有 id**（回落 0），
        /// 多个条目会共用同一个 id，`ForEach` 会错行/丢行。
        var id: String
        /// 显示序号（1 起，按 `insertion_order` 排序后的位置）。
        var order: Int
        /// ST 条目 id（0 = 源 JSON 里没有）。
        var entryId: Int64
        var keys: [String]
        var content: String
        var enabled: Bool
        /// `constant` 条目是"常驻注入"，不受关键词触发（界面上要能看出来）。
        var constant: Bool
        var caseSensitive: Bool
        var useRegex: Bool
        /// 注入位置 / 角色（原样保留的字符串，见 `synthForCompanion` 的说明）。
        var position: String
        var role: String
        var scanDepth: Int64
        var depth: Int64?
        var priority: Int
        /// 引擎合成时参与去重与排序的关键：同一 `content` 只留 priority 最高的。
        var insertionOrder: Int64
    }

    /// 把存库的世界书 JSON 解析成条目摘要（解析不了时返回空数组）。
    static func entrySummaries(_ raw: String) -> [EntrySummary] {
        guard let entries = decodeEntries(raw) else { return [] }
        return entries.enumerated().map { offset, entry in
            EntrySummary(
                id: "\(offset)-\(entry.id)",
                order: offset + 1,
                entryId: entry.id,
                keys: entry.keys,
                content: entry.content ?? "",
                enabled: entry.enabled,
                constant: entry.constant,
                caseSensitive: entry.caseSensitive,
                useRegex: entry.useRegex,
                position: Self.describe(entry.position),
                role: Self.describe(entry.role),
                scanDepth: entry.scanDepth,
                depth: entry.depth,
                priority: entry.priority,
                insertionOrder: entry.insertionOrder
            )
        }
    }

    /// 顶层 `name`（世界书名；缺失时给空串，界面自己决定回退文案）。
    static func bookName(_ raw: String) -> String {
        guard let data = raw.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let name = root["name"] as? String
        else { return "" }
        return name
    }

    /// 这个 JSON 是否"看起来像一本世界书"（导入时要给出可读的失败原因）。
    ///
    /// ⚠️ `entries` **必须是容器**（对象或数组）—— 与 `decodeEntries` 认的两种形态一致。
    /// 只判"键在不在"会让 `{"entries": 3}` 通过，然后落库成一本 0 条目的空书
    /// （第 214 轮由 `CharacterCardExportTests` 的 CI 失败暴露出来，一并收紧）。
    static func looksLikeWorldbook(_ raw: String) -> Bool {
        guard let data = raw.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let entries = root["entries"]
        else { return false }
        return entries is [String: Any] || entries is [[String: Any]]
    }

    /// `Any?` 转成可显示的字符串（位置/角色既可能是字符串也可能是数字/对象）。
    private static func describe(_ value: Any?) -> String {
        switch value {
        case nil: return ""
        case let text as String: return text
        case let number as NSNumber: return number.stringValue
        default: return String(describing: value!)
        }
    }

    // MARK: - 解码辅助

    private struct BookRow: FetchableRecord, Decodable {
        let id: Int64, name: String, json: String, companionId: Int64?
    }

    private struct StEntry {
        var id: Int64, keys: [String], content: String?
        var enabled: Bool, insertionOrder: Int64, priority: Int
        var caseSensitive: Bool, useRegex: Bool, constant: Bool
        var position: Any?, role: Any?, scanDepth: Int64, depth: Int64?, sortOrder: Int
    }

    private static let ORDER_BASE: Int64 = 4_294_967_296   // 2^32
    private static let DEFAULT_SCAN_DEPTH: Int64 = 10
    private static let DEFAULT_DEPTH: Int64 = 4

    /// 解析 `worldbooks.json`，支持 ST World Info 的 **map 与 array 两种形态**。
    /// 默认值与 `decodeEntry` 一致，含 `priority` 的 ORDER_BASE 回落。
    private static func decodeEntries(_ raw: String) -> [StEntry]? {
        guard let data = raw.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let container = root["entries"]
        else { return nil }

        var out: [StEntry] = []
        if let map = container as? [String: Any] {
            for (key, value) in map {
                guard let obj = value as? [String: Any] else { continue }
                out.append(decodeEntry(fallbackId: Int64(key) ?? 0, obj: obj))
            }
        } else if let arr = container as? [[String: Any]] {
            for obj in arr {
                out.append(decodeEntry(fallbackId: 0, obj: obj))
            }
        } else {
            return nil
        }
        return out.sorted { $0.insertionOrder < $1.insertionOrder }
    }

    private static func decodeEntry(fallbackId: Int64, obj: [String: Any]) -> StEntry {
        let keys = (obj["keys"] as? [String])?.filter { !$0.isEmpty } ?? []
        let ext = obj["extensions"] as? [String: Any]
        let innerId = (obj["id"] as? Int64) ?? 0
        let insertionOrder = (obj["insertion_order"] as? Int64) ?? 0
        let priority: Int
        if let p = ext?["_priority"] as? Int {
            priority = p
        } else {
            let raw = ORDER_BASE - insertionOrder
            priority = Int(min(max(raw, Int64(Int.min)), Int64(Int.max)))
        }
        return StEntry(
            id: innerId > 0 ? innerId : fallbackId,
            keys: keys,
            content: obj["content"] as? String,
            enabled: (obj["enabled"] as? Bool) ?? true,
            insertionOrder: insertionOrder,
            priority: priority,
            caseSensitive: (obj["case_sensitive"] as? Bool) ?? false,
            useRegex: (obj["use_regex"] as? Bool) ?? false,
            constant: (obj["constant"] as? Bool) ?? false,
            position: obj["position"], role: obj["role"],
            scanDepth: max((obj["scan_depth"] as? Int64) ?? DEFAULT_SCAN_DEPTH, 1),
            // 源 JSON 没有 depth 就保持 nil —— 上面据此决定是否写 depth 键
            depth: obj["depth"] as? Int64,
            sortOrder: (ext?["_sortOrder"] as? Int) ?? 0
        )
    }

    private static func dominantScanDepth(_ depths: [Int64]) -> Int64 {
        guard !depths.isEmpty else { return DEFAULT_SCAN_DEPTH }
        var counts: [Int64: Int] = [:]
        for d in depths { counts[d, default: 0] += 1 }
        let maxCount = counts.values.max() ?? 0
        return counts.filter { $0.value == maxCount }.map(\.key).max() ?? DEFAULT_SCAN_DEPTH
    }

    private static func decodeEntryCount(_ raw: String) -> Int {
        guard let data = raw.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let container = root["entries"]
        else { return 0 }
        if let map = container as? [String: Any] { return map.count }
        if let arr = container as? [[String: Any]] { return arr.count }
        return 0
    }

    /// 从 `companions.lorebookIdsJson` 解析绑定的世界书 id 集合。
    private static func boundWorldbookIds(_ database: YuNianDatabase, companionId: Int64) -> Set<Int64> {
        do {
            return try database.pool.read { db in
                guard let raw = try String.fetchOne(
                    db, sql: "SELECT lorebookIdsJson FROM companions WHERE id = ?",
                    arguments: [companionId]
                ), let data = raw.data(using: .utf8),
                    let arr = try? JSONSerialization.jsonObject(with: data) as? [Int64]
                else { return Set<Int64>() }
                return Set(arr)
            }
        } catch {
            return Set<Int64>()
        }
    }
}

// MARK: - 已提取、待实现的契约（来自 WorldbookJsonCodec.kt / WorldbookRepository.kt）
//
// `synthForCompanion(companionId)` 的完整流水线：
//
// 1. **有效书集合** `effectiveRows(companionId)`：
//    - 前提：`enabled && json.isNotBlank() && json != "{}"`
//    - 已绑定（`companions.lorebookIdsJson` 非空）：
//      `it.companionId == companionId || (it.companionId == null && it.id in boundIds)`
//    - 未绑定：`companionId <= 0 || it.companionId == null || it.companionId == companionId`
//
// 2. **条目来源 = `worldbooks.json` 列，不是 `lorebook_entries` 表**
//    （我第 84 轮修正了此前的错误记录）：
//       WorldbookJsonCodec.entries(row.json) → ST 条目
//       → 过滤 !st.enabled
//       → toEntity(lorebookId = 0L)     ← id 取自 ST 条目的 id，lorebookId 强制为 0
//       → content 空白者跳过
//    注意 `lorebook_entries` 表**不参与合成**；它是 UI 编辑器用的结构化视图。
//
// 3. **按 content 去重**：保留 priority 更高者；**并列时保留后出现者**
//    （更晚的书覆盖早的 —— LinkedHashMap + `>=` 实现的语义）
//
// 4. **排序**：`priority` 降序，其次 `entry.id` 升序
//
// 5. **insertion_order 全局重编号**：1..N（Rust 用 sort_by_key 稳定排序，键必须唯一）
//
// 6. **顶层**（assemble）：
//    { "entries": [...], "scan_depth": dominantScanDepth(...), "token_budget": 10_000 }
//    name 取 core?.name ?: "世界书"，description 为 null
//
// 7. 条目级字段与来源（`entryToJson`，共 13 + extensions）：
//    id / keys / secondary_keys(=[]) / content
//    enabled ← **必须显式写出**（Rust 默认 true，省略会让禁用条目意外生效）
//    insertion_order ← 上面重编号后的值
//    case_sensitive / use_regex / constant ← 布尔
//    position ← positionToSt(injectionPosition)（字符串）
//    role ← roleToSt(role)
//    scan_depth ← coerceAtLeast(1)
//    depth ← 仅当 injectionPosition == AT_DEPTH
//    extensions ← {_bookId, _bookName, _sortOrder, _priority, _createdAt, _updatedAt}
//
// 8. 调用时机：**每个回合前**（覆盖式注入）。当前 iOS 接在 ChatSession.send。
//
// 9. **解码侧契约**（`WorldbookJsonCodec.decodeEntry`，iOS 合成前要把存库 JSON 解回来）：
//    - `entries` 既可能是 **map 形态**（`{"条目名": {...}}`）也可能是 **array 形态**
//      （`[{...}]`），两者都要支持（`WorldbookRepository` 与 `entryCount` 都分流处理）
//    - `id` 取内层 `id`，缺失/≤0 时**回落到 map 键**
//    - `keys` 数组里**空串项被丢弃**
//    - `enabled` 默认 **true**、`constant`/`case_sensitive`/`use_regex` 默认 false
//    - `depth` 默认 DEFAULT_DEPTH 且 `coerceAtLeast(1)`；`scan_depth` 默认 DEFAULT_SCAN_DEPTH 同样 clamp
//    - `priority`：先取 `extensions._priority`；**缺失时回落
//      `insertionOrderToPriority(insertion_order)`（ORDER_BASE − insertionOrder）**
//      —— 这条最容易漏，漏了会让没有 `_priority` 的旧书所有条目优先级相同
//    - `sortOrder` 默认 0；`createdAt`/`updatedAt` 默认 0
//
// 相关表结构（Room v45）：
//   worldbooks(id, name, json, enabled, companionId, updatedAt)
//   companions(..., apiConfigId, ...) + lorebookIdsJson（绑定世界书 id 列表的 JSON）
