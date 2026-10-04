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
/// ## ⚠️ 当前实现的范围：只覆盖 Android 的「全局」路径
/// Android 的分支是：
/// ```kotlin
/// val payload = if (companionId > 0L) synthForCompanion(companionId)   // 按伴侣合成
///               else dao.active()?.json ?: ""                          // 全局启用书原文
/// AgentFacade.setWorldbook(context, payload)   // blank → null
/// ```
/// iOS 目前实现了 `companionId <= 0` 的那一支（取启用书的 `json` 原文，
/// 对应 `WorldbookDao.active()`：`SELECT json FROM worldbooks WHERE enabled = 1 LIMIT 1`）。
///
/// **`companionId > 0` 的合成尚未移植。** 该分支的行为是「宁可不注入，也不注入错的内容」：
/// 传 `nil` 并打日志 —— 因为 `effectiveRows` 的绑定规则
/// （专属书 ∪ boundIds 命中的全局书）、`entryToJson` 的 13 个字段映射、
/// `assemble` 的顶层结构都与直接传某本书不同，硬塞一本全局书会**静默注入错误内容**。
/// 契约已从 `WorldbookJsonCodec.kt` 提取完毕（见本文件末尾的清单）。
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

    /// 合成某伴侣当前生效的世界书 —— 对应 Android `synthForCompanion`。
    ///
    /// ## 一个省掉整张枚举映射表的等价化简
    /// Android 走「解码 JSON → StEntry → 过滤/去重/排序/重编号 → `entryToJson` 再编码」，
    /// 其中 `position` / `role` 要经 `stToPosition` → `positionToSt` 往返。
    /// 但**源和目标都是同一个 JSON**，而 Android 写入时已用同一编解码器规范化过；
    /// 因此这里直接**保留原始字符串**，与 Android 的输出等价
    ///（也顺带避免了我尚未读取 `positionToSt` / `roleToSt` 的枚举映射）。
    static func synthForCompanion(_ database: YuNianDatabase, companionId: Int64) -> String {
        let rows: [BookRow]
        do {
            rows = try database.pool.read { db in
                try BookRow.fetchAll(db, sql: """
                    SELECT id, name, json, companionId FROM worldbooks WHERE enabled = 1
                    """)
            }
        } catch {
            log.error("读取世界书失败：\(String(describing: error), privacy: .public)")
            return ""
        }

        // ① 绑定规则（对应 effectiveRows）：enabled && json.isNotBlank() && json != "{}"
        let boundIds = companionId > 0 ? boundWorldbookIds(database, companionId: companionId) : Set<Int64>()
        let effective = rows.filter { row in
            let j = row.json.trimmingCharacters(in: .whitespaces)
            guard !j.isEmpty, j != "{}" else { return false }
            if boundIds.isEmpty {
                return companionId <= 0 || row.companionId == nil || row.companionId == companionId
            }
            return row.companionId == companionId || (row.companionId == nil && boundIds.contains(row.id))
        }

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
