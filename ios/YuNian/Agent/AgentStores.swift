import Foundation
import GRDB
import os

/// Rust 三个存储回调（`MemoryStore` / `SkillStore` / `StickerPreferenceStore`）的 Swift 实现。
///
/// ## ⚠️ 两个必须小心的地方
///
/// **1. 数据库列名是 camelCase，而 Rust 的 JSON 契约是 snake_case。**
///    schema v45 的列名如 `memoryType` / `sourceId` / `lastAccessedAt`，
///    但 Rust 侧 record 字段是 `memory_type` / `source_id` / `last_accessed_at`
///    （已核实：这些 struct **没有** `#[serde(rename_all)]`）。
///    本文件显式做这层映射，不要依赖 Swift 的 `JSONEncoder` 默认行为。
///
/// **2. `unified_memories.embedding` 是 BLOB，编码为 little-endian Float32。**
///    依据 `core/network/.../EmbeddingService.kt`：
///        floatsToBytes: ByteBuffer.allocate(n*4).order(LITTLE_ENDIAN) → asFloatBuffer().put
///        bytesToFloats: ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
///    两端都是小端（初查时曾误判为「编码大端、解码小端」，实为 grep 漏了续行）。
///
/// ## 关于 `embed_text`
/// 需要调用嵌入服务（Android 侧走 `EmbeddingService`）。M1 阶段返回 nil，
/// Rust 侧会把 None 当作「本机无嵌入能力」正常降级（不做向量召回，回退关键词召回）。
/// 这是**有意的降级**，不是占位；接入时机见里程碑 M3。
final class AgentStores {
    private let database: YuNianDatabase
    private let log = Logger(subsystem: "com.yunian.ai", category: "agent.store")

    init(database: YuNianDatabase) {
        self.database = database
    }

    // MARK: - JSON 辅助（显式 snake_case）

    private func encodeJSON(_ object: Any) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: object),
              let string = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return string
    }

    private func encodeArray(_ array: [[String: Any]]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: array),
              let string = String(data: data, encoding: .utf8) else {
            return "[]"
        }
        return string
    }

    private func decodeObject(_ json: String) -> [String: Any] {
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        return object
    }

    private static func littleEndianFloats(from blob: Data?) -> [Float]? {
        guard let blob, blob.count % 4 == 0, !blob.isEmpty else { return nil }
        var floats = [Float](repeating: 0, count: blob.count / 4)
        _ = floats.withUnsafeMutableBytes { target in
            blob.copyBytes(to: target, count: blob.count)
        }
        return floats.map { Float(bitPattern: UInt32(littleEndian: $0.bitPattern)) }
    }
}

// MARK: - MemoryStore

extension AgentStores: MemoryStore {

    /// `scope_json` 形如 `{"scope":"COMPANION","source_id":123}`；`{"scope":null}` 表示全部。
    ///
    /// ## 与 Android `MemoryStoreImpl.listMemories` 逐项对齐
    /// 过滤条件（对应 `UnifiedMemoryDao.getAllActiveSync` / `getByScopeSync`）：
    ///   `deviceId = ?` AND `isDeleted = 0` AND `(expiresAt IS NULL OR expiresAt > now)`
    /// 排序：`importance DESC, observedAt DESC`
    ///
    /// ⚠️ 三处曾经写错、都会静默返回错误结果的地方：
    ///   1. **缺 `deviceId` 过滤** → 会返回其它设备写入的记忆
    ///   2. **缺过期过滤** → 已过期记忆会被召回
    ///   3. **排序用了 `lastAccessedAt DESC`** → 应为 `observedAt DESC`
    func listMemories(scopeJson: String) -> String {
        let spec = decodeObject(scopeJson)

        // 与 Android 一致：scope 无法解析（含 "null"/空）时按「全部」处理
        let scopeRaw = (spec["scope"] as? String)?
            .trimmingCharacters(in: .whitespaces)
        let scope = (scopeRaw?.isEmpty == false && scopeRaw != "null") ? scopeRaw : nil

        // 与 Android 一致：source_id 缺失或为 null 时取 0（而不是「不过滤」）
        let sourceId: Int64 = (spec["source_id"] as? NSNumber)?.int64Value ?? 0

        do {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            let rows = try database.pool.read { db -> [[String: Any]] in
                var sql = """
                SELECT id, content, memoryType, scope, sourceId, importance, confidence,
                       accessCount, observedAt, lastAccessedAt, expiresAt, tags, embedding
                FROM unified_memories
                WHERE deviceId = ?
                  AND isDeleted = 0
                  AND (expiresAt IS NULL OR expiresAt > ?)
                """
                var arguments: [DatabaseValueConvertible] = [DeviceIdentity.deviceId, now]
                if let scope {
                    sql += " AND scope = ?"
                    arguments.append(scope)
                    sql += " AND sourceId = ?"
                    arguments.append(sourceId)
                }
                sql += " ORDER BY importance DESC, observedAt DESC"

                return try Row.fetchAll(db, sql: sql, arguments: StatementArguments(arguments))
                    .map { self.memoryMeta(from: $0) }
            }
            return encodeArray(rows)
        } catch {
            log.error("listMemories 失败：\(String(describing: error), privacy: .public)")
            return "[]"
        }
    }

    /// 保存记忆：`id` 为空串时新建；否则按主键 REPLACE 更新。返回 id（失败返回空串）。
    ///
    /// ## 与 Android `MemoryStoreImpl.insertMemory` 对齐的要点
    ///   - `content` 为空白 → **直接返回 ""**（不落库）。曾漏掉这条，会写进空记忆
    ///   - `source` 由 scope 决定：`GROUP` → `GROUP_CHAT`，否则 `CHAT`。
    ///     曾硬编码为 `CHAT`，群聊记忆的来源会标错
    ///   - `confidence` 默认 **1.0**（不是 0.5）；`accessCount` 默认 **1** 且下限为 1
    ///   - `createdAt`：更新既有行时读入参的 `created_at`，新建时取 now
    ///   - `importance` 被 `coerceIn(0, 1)`
    func insertMemory(metaJson: String) -> String {
        let meta = decodeObject(metaJson)
        let providedId = (meta["id"] as? String).flatMap(Int64.init)
        let now = Int64(Date().timeIntervalSince1970 * 1000)

        let content = (meta["content"] as? String) ?? ""
        guard !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            log.warning("insertMemory 收到空白 content，按 Android 行为放弃写入")
            return ""
        }

        let scope = (meta["scope"] as? String)?.uppercased() ?? "COMPANION"
        let memoryType = (meta["memory_type"] as? String)?.uppercased() ?? "SEMANTIC"
        let source = scope == "GROUP" ? "GROUP_CHAT" : "CHAT"
        let sourceId = (meta["source_id"] as? NSNumber)?.int64Value ?? 0
        let confidence = (meta["confidence"] as? NSNumber)?.doubleValue ?? 1.0
        let importance = min(max((meta["importance"] as? NSNumber)?.doubleValue ?? 0.5, 0), 1)
        let accessCount = max((meta["access_count"] as? NSNumber)?.intValue ?? 1, 1)
        let createdAt = providedId != nil
            ? ((meta["created_at"] as? NSNumber)?.int64Value ?? now)
            : now
        let lastAccessedAt = (meta["last_accessed_at"] as? NSNumber)?.int64Value ?? now
        let observedAt = (meta["observed_at"] as? NSNumber)?.int64Value ?? now
        let expiresAt = meta["expires_at"] as? NSNumber

        do {
            let newId = try database.pool.write { db -> Int64 in
                // ⚠️ `NULLIF(?, 0)` 不可省。
                // `unified_memories.id` 是 `INTEGER PRIMARY KEY AUTOINCREMENT`，
                // 而新建时 id 传 0 —— SQLite 会把 0 当作**合法的 rowid 0 显式插入**，
                // 于是第二次新建就会 `INSERT OR REPLACE` 掉第一条，**永远只剩一条记忆**。
                // Room 对 autoGenerate 主键正是绑定 `nullif(?, 0)` 来把 0 变 NULL 触发自增。
                // （已用真实 SQLite 验证：不加 NULLIF 时两条记录只剩一条。）
                try db.execute(sql: """
                INSERT OR REPLACE INTO unified_memories
                  (id, memoryType, scope, source, content, summary, confidence, importance,
                   sourceId, createdAt, updatedAt, lastAccessedAt, observedAt, expiresAt,
                   temporalAnchor, accessCount, tags, fuzzyHints, mergedFrom,
                   isDeleted, version, deviceId, embeddingModel)
                VALUES (NULLIF(?, 0),?,?,?,?,'',?,?,?,?,?,?,?,?,'',?,?,'','',0,1,?,'')
                """, arguments: [
                    providedId ?? 0,
                    memoryType, scope, source, content,
                    confidence, importance,
                    sourceId, createdAt, now, lastAccessedAt, observedAt,
                    expiresAt,
                    accessCount,
                    (meta["tags"] as? String) ?? "",
                    DeviceIdentity.deviceId,
                ])
                return db.lastInsertedRowID
            }
            return String(newId)
        } catch {
            log.error("insertMemory 失败：\(String(describing: error), privacy: .public)")
            return ""
        }
    }

    /// 按 metaJson 局部更新：未提供的字段保持原值（先读后写，与 Android 一致）。
    func updateMemory(metaJson: String) -> Bool {
        let meta = decodeObject(metaJson)
        guard let id = (meta["id"] as? String).flatMap(Int64.init) else { return false }

        do {
            let ok = try database.pool.write { db -> Bool in
                // 先读现有行（排除软删除），与 Android `getByIdSync` 一致
                guard let existing = try Row.fetchOne(db, sql: """
                    SELECT memoryType, scope, content, importance, confidence, sourceId,
                           observedAt, expiresAt, tags, accessCount
                    FROM unified_memories WHERE id = ? AND isDeleted = 0
                    """, arguments: [id]) else {
                    return false
                }
                let now = Int64(Date().timeIntervalSince1970 * 1000)

                let content = (meta["content"] as? String) ?? (existing["content"] as String? ?? "")
                let memoryType = (meta["memory_type"] as? String)?.uppercased()
                    ?? (existing["memoryType"] as String? ?? "SEMANTIC")
                let scope = (meta["scope"] as? String)?.uppercased()
                    ?? (existing["scope"] as String? ?? "COMPANION")
                let importance = (meta["importance"] as? NSNumber)?.doubleValue
                    ?? (existing["importance"] as Double? ?? 0.5)
                let confidence = (meta["confidence"] as? NSNumber)?.doubleValue
                    ?? (existing["confidence"] as? Double? ?? 1.0)
                let sourceId = (meta["source_id"] as? NSNumber)?.int64Value
                    ?? (existing["sourceId"] as Int64? ?? 0)
                let observedAt = (meta["observed_at"] as? NSNumber)?.int64Value
                    ?? (existing["observedAt"] as? Int64? ?? now)
                let lastAccessedAt = (meta["last_accessed_at"] as? NSNumber)?.int64Value ?? now
                // expires_at 显式为 null 时清空；否则取入参或保持原值
                let expiresAt: Int64?
                if meta.keys.contains("expires_at"), meta["expires_at"] is NSNull {
                    expiresAt = nil
                } else {
                    // ⚠️ 第 79 轮：CI 报 "cannot convert value of type 'Int64??' to
                    // expected argument type 'Int64'"（AgentStores.swift:230）。
                    // 原因是 `existing` 是 `[String: Any]`，`existing["expiresAt"] as? Int64`
                    // 得到的是 `Int64?`（字典取值本身已是一层可选，再 as? 又是一层）。
                    // 用 `?? nil` 拍平一层即可 —— 不是逻辑改动。
                    expiresAt = (meta["expires_at"] as? NSNumber)?.int64Value
                        ?? (existing["expiresAt"] as? Int64?) ?? nil
                }
                let tags = (meta["tags"] as? String) ?? (existing["tags"] as String? ?? "")
                let accessCount = max(
                    (meta["access_count"] as? NSNumber)?.intValue
                        ?? (existing["accessCount"] as? Int? ?? 1),
                    1
                )

                try db.execute(sql: """
                UPDATE unified_memories SET
                  content = ?, memoryType = ?, scope = ?, importance = ?, confidence = ?,
                  sourceId = ?, lastAccessedAt = ?, observedAt = ?, expiresAt = ?, tags = ?,
                  accessCount = ?, updatedAt = ?, version = version + 1
                WHERE id = ?
                """, arguments: [
                    content, memoryType, scope, importance, confidence, sourceId,
                    lastAccessedAt, observedAt, expiresAt, tags, accessCount, now, id,
                ])
                return db.changesCount > 0
            }
            return ok
        } catch {
            log.error("updateMemory 失败：\(String(describing: error), privacy: .public)")
            return false
        }
    }

    /// 软删除（`isDeleted = 1` + `updatedAt`），与 Android `softDelete(id, now)` 一致。
    func deleteMemory(id: String) -> Bool {
        guard let memoryId = Int64(id) else { return false }
        do {
            let changed = try database.pool.write { db -> Int in
                try db.execute(
                    sql: "UPDATE unified_memories SET isDeleted = 1, updatedAt = ? WHERE id = ?",
                    arguments: [Int64(Date().timeIntervalSince1970 * 1000), memoryId]
                )
                return db.changesCount
            }
            return changed > 0
        } catch {
            log.error("deleteMemory 失败：\(String(describing: error), privacy: .public)")
            return false
        }
    }

    /// 读记忆正文（对应 Android `getByIdSync`，排除软删除）。
    func getMemoryContent(id: String) -> String? {
        guard let memoryId = Int64(id) else { return nil }
        return try? database.pool.read { db in
            try String.fetchOne(
                db,
                sql: "SELECT content FROM unified_memories WHERE id = ? AND isDeleted = 0",
                arguments: [memoryId]
            )
        } ?? nil
    }

    /// 返回 `{"last_activity_at":ms,"last_consolidated_at":ms}`（0 表示无记录）。
    ///
    /// ## ⚠️ 数据来源与直觉不同，且必须如实复刻
    /// Android `MemoryStoreImpl.getActivityTimestamps` 取的是**消息表**，不是记忆表：
    /// ```kotlin
    /// val recent   = getRecentMessageMetadataSync(cid, "chat", 1).firstOrNull()
    /// val archived = getLastArchivedMessageMetadata(cid, "chat")
    /// (recent ?: archived)?.timestamp ?: 0L
    /// ```
    /// 三个必须照做的点：
    ///   1. 来源是 `messages` / `archived_messages`，**不是** `unified_memories`
    ///   2. `recent ?: archived` 是**热表优先**，不是取两者最大值 ——
    ///      热表非空时归档时间被完全忽略
    ///   3. 会话类型**硬编码 `"chat"`**，不考虑群聊
    ///   4. `companionId` 为 nil 时返回 **0**，不是全局最大值
    func getActivityTimestamps(companionId: Int64?) -> String {
        let lastActivity: Int64
        if let companionId {
            lastActivity = (try? database.pool.read { db -> Int64 in
                let hot = try Int64.fetchOne(db, sql: """
                    SELECT timestamp FROM messages
                    WHERE conversationId = ? AND conversationType = 'chat'
                    ORDER BY timestamp DESC, id DESC LIMIT 1
                    """, arguments: [companionId])
                if let hot { return hot }
                return try Int64.fetchOne(db, sql: """
                    SELECT timestamp FROM archived_messages
                    WHERE conversationId = ? AND conversationType = 'chat'
                    ORDER BY timestamp DESC, id DESC LIMIT 1
                    """, arguments: [companionId]) ?? 0
            }) ?? 0
        } else {
            lastActivity = 0
        }

        let lastConsolidated = Self.lastConsolidatedMilliseconds(companionId: companionId)
        return encodeJSON([
            "last_activity_at": lastActivity,
            "last_consolidated_at": lastConsolidated,
        ])
    }

    /// 记录整理时间。Android 用 SharedPreferences（`agent_memory_prefs`）按 companion 维度存，
    /// 键名 `consolidated_at_<id>` / `consolidated_at_global`。iOS 用 UserDefaults 存同名键 ——
    /// 非机密数据，且键名保持一致便于跨端排查。
    func setLastConsolidatedAt(now: Int64, companionId: Int64?) -> Bool {
        UserDefaults.standard.set(now, forKey: Self.consolidatedKey(companionId: companionId))
        return true
    }

    /// M1 阶段有意返回 nil（无嵌入能力）——Rust 会降级为关键词召回。
    /// 接入时机见里程碑 M3（Android 侧走 `EmbeddingProvider.embed`）。
    func embedText(text: String) -> String? {
        nil
    }

    // MARK: 内部

    /// 与 Android `keyFor` 完全一致的键名。
    static func consolidatedKey(companionId: Int64?) -> String {
        companionId == nil ? "consolidated_at_global" : "consolidated_at_\(companionId!)"
    }

    static func lastConsolidatedMilliseconds(companionId: Int64?) -> Int64 {
        Int64(UserDefaults.standard.double(forKey: consolidatedKey(companionId: companionId)))
    }

    /// 组装 Rust `MemoryMeta` 期望的 snake_case JSON。
    ///
    /// 注意 `expires_at` **始终出现**（无值时为 null）—— 与 Android `toMetaJson` 的
    /// `put("expires_at", row.expiresAt ?: JSONObject.NULL)` 一致。serde 对
    /// `Option<i64>` 而言「缺键」与「null」等价，但保持一致更便于两端对比。
    private func memoryMeta(from row: Row) -> [String: Any] {
        var meta: [String: Any] = [
            "id": String(row["id"] as Int64? ?? 0),
            "content": row["content"] as String? ?? "",
            "memory_type": row["memoryType"] as String? ?? "SEMANTIC",
            "scope": row["scope"] as String? ?? "COMPANION",
            "source_id": row["sourceId"] as Int64? ?? 0,
            "importance": Double(row["importance"] as Double? ?? 0),
            "confidence": Double(row["confidence"] as Double? ?? 0),
            "access_count": row["accessCount"] as Int? ?? 0,
            "observed_at": row["observedAt"] as Int64? ?? 0,
            "last_accessed_at": row["lastAccessedAt"] as Int64? ?? 0,
            "expires_at": row["expiresAt"] as Int64? ?? NSNull(),
            "tags": row["tags"] as String? ?? "",
        ]
        if let embedding = Self.littleEndianFloats(from: row["embedding"] as Data?) {
            meta["embedding"] = embedding.map { Double($0) }
        }
        return meta
    }
}

// MARK: - SkillStore

extension AgentStores: SkillStore {

    /// 技能索引 JSON 数组（**含禁用项**，由 Rust 侧过滤 enabled）。
    ///
    /// ⚠️ companionId 为 nil 时**只返回全局技能**（`companionId IS NULL`），
    /// 不是「返回全部」。Android 的 SQL 是：
    /// ```sql
    /// WHERE (companionId IS NULL OR companionId = :companionId) ORDER BY updatedAt DESC
    /// ```
    /// 当参数为 NULL 时该条件退化为 `companionId IS NULL`。
    /// 我原先写成 `(? IS NULL OR companionId IS NULL OR companionId = ?)`，
    /// 会在 nil 时把各伴侣的私有技能一并返回 —— 属于**越权可见**。
    func listSkills(companionId: Int64?) -> String {
        do {
            let rows = try database.pool.read { db -> [[String: Any]] in
                try Row.fetchAll(db, sql: """
                SELECT skillId, name, description, category, tags, tools, enabled,
                       companionId, version, updatedAt
                FROM agent_skills
                WHERE (companionId IS NULL OR companionId = ?)
                ORDER BY updatedAt DESC
                """, arguments: [companionId]).map { self.skillMeta(from: $0) }
            }
            return encodeArray(rows)
        } catch {
            log.error("listSkills 失败：\(String(describing: error), privacy: .public)")
            return "[]"
        }
    }

    /// 读技能正文：索引 → 文件 → SHA-256 校验 → **剥离 frontmatter**。
    /// 不存在 / 已禁用 / 校验失败 → nil。
    ///
    /// ## 与 Android `SkillStoreImpl.getSkillContent` 的两处关键差异（均已修正）
    /// 1. **哈希校验是强制的**，不是「contentHash 非空才校验」。
    ///    Android 无条件比较 `sha256(content) != row.contentHash` 并返回 null。
    ///    我原先加了 `if !contentHash.isEmpty` 的豁免，等于对空哈希的行**跳过完整性校验**。
    /// 2. **返回的是 `SkillContentParser.parse(content).body`**，即剥离了 frontmatter 的正文。
    ///    不剥离的话，模型会看到 `---\nname: ...\n---` 这段元数据头。
    func getSkillContent(skillId: String) -> String? {
        do {
            let record = try database.pool.read { db -> (String, Bool)? in
                guard let row = try Row.fetchOne(
                    db,
                    sql: "SELECT contentHash, enabled FROM agent_skills WHERE skillId = ?",
                    arguments: [skillId]
                ) else { return nil }
                return (row["contentHash"] as String? ?? "", (row["enabled"] as Int? ?? 0) != 0)
            }
            guard let record else { return nil }
            let (expectedHash, enabled) = record
            guard enabled else { return nil }

            let url = try AppPaths.agentSkillsDirectory()
                .appendingPathComponent(skillId, isDirectory: true)
                .appendingPathComponent("content.md", isDirectory: false)
            guard FileManager.default.fileExists(atPath: url.path) else { return nil }
            let content = try String(contentsOf: url, encoding: .utf8)

            // 强制校验（与 Android 一致：无条件比较）
            let actual = DeviceIdentity.sha256Hex(Data(content.utf8))
            guard actual == expectedHash else {
                log.warning("技能 \(skillId, privacy: .public) 正文校验失败，按不存在处理")
                return nil
            }

            // 剥离 frontmatter：返回给模型的是 body
            return SkillContentParser.parse(content).body
        } catch {
            log.error("getSkillContent 失败：\(String(describing: error), privacy: .public)")
            return nil
        }
    }

    /// 保存技能：写 content.md → 写 meta.json → 写索引，返回新 version；失败返回 -1。
    ///
    /// ## 与 Android `SkillStoreImpl.saveSkill` 对齐的要点
    ///   - `skill_id` 为空 → **生成 UUID**（我原先直接返回 -1，导入社区技能会失败）
    ///   - **frontmatter 覆盖**：`name` / `description` 优先取 SKILL.md 里的声明
    ///     （`parsed.x ?: meta.x`，且 name 的每级回退都要求非空白，最终兜底为 skillId）
    ///   - `category` 用 `parseCategory` 解析，无法识别时回落 **`CUSTOM`**（不是空串）
    ///   - `contentPath` 存**相对路径** `agent_skills/<id>/content.md`，不是绝对路径
    ///   - 先写文件再写索引（FS 是唯一事实源）
    ///   - 更新既有行时 version+1，新建时 version=1
    func saveSkill(metaJson: String, content: String) -> Int32 {
        let meta = decodeObject(metaJson)

        // skill_id 为空 → 生成 UUID（对应 Kotlin 的 `?: UUID.randomUUID().toString()`）
        let providedId = (meta["skill_id"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let skillId = (providedId?.isEmpty == false) ? providedId! : UUID().uuidString

        // frontmatter 覆盖
        let parsed = SkillContentParser.parse(content)
        let name = [parsed.name, meta["name"] as? String]
            .compactMap { $0 }
            .first { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            ?? skillId
        let description = parsed.description ?? (meta["description"] as? String) ?? ""

        let category = Self.parseSkillCategory(meta["category"] as? String)
        let tags = (meta["tags"] as? String) ?? ""
        let tools = (meta["tools"] as? [Any])?
            .compactMap { $0 as? String }
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            .joined(separator: ",") ?? ""
        let enabled = (meta["enabled"] as? Bool) ?? true

        // companion_id：显式 null → nil；有键 → 取值；无键 → nil
        // （对应 `isNull` / `has` 的分支，三种情况在 Android 里都归为 null 或取值）
        let companionId: Int64?
        if meta["companion_id"] is NSNull || meta["companion_id"] == nil {
            companionId = nil
        } else {
            companionId = (meta["companion_id"] as? NSNumber)?.int64Value
        }

        do {
            let dir = try AppPaths.agentSkillsDirectory()
                .appendingPathComponent(skillId, isDirectory: true)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

            // ① 先写正文（对应 writeContent）
            try content.write(
                to: dir.appendingPathComponent("content.md", isDirectory: false),
                atomically: true, encoding: .utf8
            )
            // ② 再写元数据快照（对应 writeMeta）。
            //    注意：Android 写的是 `meta.toString()`（JSONObject 重新序列化，键序可能变化）；
            //    这里写原始入参，语义等价且更保真。
            try metaJson.write(
                to: dir.appendingPathComponent("meta.json", isDirectory: false),
                atomically: true, encoding: .utf8
            )

            let now = Int64(Date().timeIntervalSince1970 * 1000)
            let hash = DeviceIdentity.sha256Hex(Data(content.utf8))
            let length = Int64(content.utf8.count)
            let contentPath = "agent_skills/\(skillId)/content.md"   // 相对路径

            let version = try database.pool.write { db -> Int32 in
                let existing = try Int32.fetchOne(
                    db, sql: "SELECT version FROM agent_skills WHERE skillId = ?",
                    arguments: [skillId]
                )
                let next: Int32 = existing.map { $0 + 1 } ?? 1

                if existing != nil {
                    try db.execute(sql: """
                    UPDATE agent_skills SET
                      name = ?, description = ?, category = ?, tags = ?, tools = ?,
                      enabled = ?, companionId = ?, contentPath = ?, contentHash = ?,
                      contentLength = ?, version = ?, updatedAt = ?
                    WHERE skillId = ?
                    """, arguments: [
                        name, description, category, tags, tools,
                        enabled ? 1 : 0, companionId, contentPath, hash,
                        length, next, now, skillId,
                    ])
                } else {
                    try db.execute(sql: """
                    INSERT INTO agent_skills
                      (skillId, name, description, category, tags, tools, enabled, companionId,
                       contentPath, contentHash, contentLength, version, createdAt, updatedAt)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, arguments: [
                        skillId, name, description, category, tags, tools,
                        enabled ? 1 : 0, companionId, contentPath, hash,
                        length, next, now, now,
                    ])
                }
                return next
            }
            return version
        } catch {
            log.error("saveSkill 失败：\(String(describing: error), privacy: .public)")
            return -1
        }
    }

    /// 先删索引行再删目录。
    ///
    /// ⚠️ 返回值语义：Android 是 `deleteBySkillId(...) > 0` ——
    /// **索引行不存在时返回 false**。我原先无条件返回 true，
    /// 会让 Rust 误以为删除成功（例如重复删除同一技能）。
    func deleteSkill(skillId: String) -> Bool {
        do {
            let deleted = try database.pool.write { db -> Int in
                try db.execute(sql: "DELETE FROM agent_skills WHERE skillId = ?", arguments: [skillId])
                return db.changesCount
            }

            // 目录删除失败不影响索引已删除的事实（与 Android 一致：忽略 deleteDir 的返回值）
            let dir = try AppPaths.agentSkillsDirectory().appendingPathComponent(skillId, isDirectory: true)
            if FileManager.default.fileExists(atPath: dir.path) {
                try? FileManager.default.removeItem(at: dir)
            }

            return deleted > 0
        } catch {
            log.error("deleteSkill 失败：\(String(describing: error), privacy: .public)")
            return false
        }
    }

    /// 技能检索（**仅启用项**）。
    ///
    /// ⚠️ 三处对齐要点：
    ///   1. Rust 的 `search_skills(query, limit)` **不传 companionId**，
    ///      Android 侧硬编码 `dao.searchSkills(null, ...)`。
    ///      该实参为 NULL 时 SQL 的 `(companionId IS NULL OR companionId = NULL)`
    ///      退化为 **`companionId IS NULL`** —— 只搜全局技能，不含各伴侣的私有技能。
    ///      （不要写成 `= 0`，那会把「属于 id=0 的技能」也搜出来。）
    ///   2. `AND enabled = 1` —— **禁用技能不该被检索到**，我原先漏了
    ///   3. `limit.coerceAtLeast(1)` —— limit 下限为 1
    func searchSkills(query: String, limit: UInt32) -> String {
        do {
            let rows = try database.pool.read { db -> [[String: Any]] in
                let pattern = "%\(query)%"
                return try Row.fetchAll(db, sql: """
                SELECT skillId, name, description, category, tags, tools, enabled,
                       companionId, version, updatedAt
                FROM agent_skills
                WHERE companionId IS NULL
                  AND enabled = 1
                  AND (name LIKE ? OR description LIKE ? OR tags LIKE ?)
                ORDER BY updatedAt DESC LIMIT ?
                """, arguments: [pattern, pattern, pattern, Int(max(limit, 1))])
                    .map { self.skillMeta(from: $0) }
            }
            return encodeArray(rows)
        } catch {
            log.error("searchSkills 失败：\(String(describing: error), privacy: .public)")
            return "[]"
        }
    }

    /// 启动巡检：扫描 FS 目录 vs Room 索引，返回「目录存在但无索引行」的 skillId。
    ///
    /// 对应 Android `SkillStoreImpl.reconcile()`。文件系统是唯一事实源，
    /// 因此目录有而索引无 = 索引损坏，可用 meta.json 快照重建。
    func reconcileSkills() -> [String] {
        do {
            let dirs = try FileManager.default
                .contentsOfDirectory(
                    at: try AppPaths.agentSkillsDirectory(),
                    includingPropertiesForKeys: [.isDirectoryKey]
                )
                .filter { (try? $0.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) == true }
                .map(\.lastPathComponent)

            let indexed = try database.pool.read { db -> Set<String> in
                Set(try String.fetchAll(db, sql: "SELECT skillId FROM agent_skills"))
            }
            return dirs.filter { !indexed.contains($0) }.sorted()
        } catch {
            log.error("reconcileSkills 失败：\(String(describing: error), privacy: .public)")
            return []
        }
    }

    // MARK: 内部

    /// 对应 Android `parseCategory`：无法识别时回落 `CUSTOM`。
    static func parseSkillCategory(_ raw: String?) -> String {
        let normalized = (raw ?? "").trimmingCharacters(in: .whitespaces).uppercased()
        // 与 SkillCategory 枚举名对齐；未知名回落 CUSTOM
        let known: Set<String> = [
            "GENERAL", "WRITING", "CODING", "ANALYSIS", "CREATIVE",
            "PRODUCTIVITY", "LANGUAGE", "CUSTOM",
        ]
        return known.contains(normalized) ? normalized : "CUSTOM"
    }

    private func skillMeta(from row: Row) -> [String: Any] {
        let toolsRaw = row["tools"] as String? ?? ""
        // ⚠️ companion_id 无值时为 **null**，不是 0 —— 对应 Android 的
        // `put("companion_id", row.companionId ?: JSONObject.NULL)`。
        // 用 0 会让 Rust 把全局技能误判为属于 id=0 的伴侣。
        let companionId: Any = (row["companionId"] as Int64?).map { $0 as Any } ?? NSNull()
        return [
            "skill_id": row["skillId"] as String? ?? "",
            "name": row["name"] as String? ?? "",
            "description": row["description"] as String? ?? "",
            "category": row["category"] as String? ?? "CUSTOM",
            "tags": row["tags"] as String? ?? "",
            "tools": toolsRaw.isEmpty ? [] : toolsRaw.split(separator: ",").map(String.init),
            "enabled": (row["enabled"] as Int? ?? 0) != 0,
            "companion_id": companionId,
            "version": row["version"] as Int? ?? 0,
            "updated_at": row["updatedAt"] as Int64? ?? 0,
        ]
    }
}

// MARK: - StickerPreferenceStore

extension AgentStores: StickerPreferenceStore {

    func listEntries() -> String {
        do {
            let rows = try database.pool.read { db -> [[String: Any]] in
                try Row.fetchAll(db, sql: """
                SELECT id, tags, userUsageCount, modelUsageCount, lastUsedAt, createdAt
                FROM sticker_entries
                ORDER BY id ASC
                """).map { row -> [String: Any] in
                    return [
                        "id": row["id"] as Int64? ?? 0,
                        "tags": Self.splitCsv(row["tags"] as String? ?? ""),
                        "user_usage_count": row["userUsageCount"] as Int64? ?? 0,
                        "model_usage_count": row["modelUsageCount"] as Int64? ?? 0,
                        "last_used_ms": row["lastUsedAt"] as Int64? ?? 0,
                        "created_ms": row["createdAt"] as Int64? ?? 0,
                    ]
                }
            }
            return encodeArray(rows)
        } catch {
            log.error("listEntries 失败：\(String(describing: error), privacy: .public)")
            return "[]"
        }
    }

    /// 落一条 usage log + 累加对应计数列。
    ///
    /// `source` 由 **Rust 侧**产生：`sticker_preference.rs::source_str` 把
    /// `StickerSource::User/Model` 映射为 `"user"` / `"model"`。
    ///
    /// ⚠️ 对未知 source **不做任何累加** —— 对应 Android `StickerPreferenceStoreImpl` 的
    /// `when (source) { "user" -> ...; "model" -> ...; else -> Unit }`。
    /// 不要写成「非 user 即 model」：那会让将来新增的 source 值静默污染 model 计数。
    func recordUsage(stickerId: UInt64, source: String, timestampMs: UInt64, contextTagsJson: String) -> Bool {
        let countColumn: String
        switch source {
        case "user": countColumn = "userUsageCount"
        case "model": countColumn = "modelUsageCount"
        default:
            // 与 Android 一致：未知 source 只落 log，不动计数列
            return insertUsageLogOnly(
                stickerId: stickerId, source: source,
                timestampMs: timestampMs, contextTagsJson: contextTagsJson
            )
        }

        do {
            try database.pool.write { db in
                try db.execute(sql: """
                INSERT INTO sticker_usage_log (stickerId, source, timestamp, contextTags)
                VALUES (?, ?, ?, ?)
                """, arguments: [
                    Int64(bitPattern: stickerId),
                    source,
                    Int64(bitPattern: timestampMs),
                    contextTagsJson,
                ])

                try db.execute(sql: """
                UPDATE sticker_entries
                SET \(countColumn) = \(countColumn) + 1, lastUsedAt = ?
                WHERE id = ?
                """, arguments: [Int64(bitPattern: timestampMs), Int64(bitPattern: stickerId)])
            }
            return true
        } catch {
            log.error("recordUsage 失败：\(String(describing: error), privacy: .public)")
            return false
        }
    }

    /// 未知 source 的路径：只落 log，不加计数。
    private func insertUsageLogOnly(
        stickerId: UInt64, source: String, timestampMs: UInt64, contextTagsJson: String
    ) -> Bool {
        do {
            try database.pool.write { db in
                try db.execute(sql: """
                INSERT INTO sticker_usage_log (stickerId, source, timestamp, contextTags)
                VALUES (?, ?, ?, ?)
                """, arguments: [
                    Int64(bitPattern: stickerId),
                    source,
                    Int64(bitPattern: timestampMs),
                    contextTagsJson,
                ])
            }
            log.warning("recordUsage 收到未知 source=\(source, privacy: .public)，仅落 log 不改计数")
            return true
        } catch {
            log.error("recordUsage 失败：\(String(describing: error), privacy: .public)")
            return false
        }
    }

    /// 历史使用记录（**时间降序，最新在前**；limit 为上限条数）。
    ///
    /// ## ⚠️ 顺序必须降序 —— 这一条有两个互相矛盾的文档，以代码为准
    /// - Rust 的 trait 注释写的是「按时间**升序**，供漂移窗口分析」
    /// - Kotlin `StickerPreferenceStoreImpl` 的类注释明确指出那是**过时表述**，
    ///   实际必须降序
    ///
    /// 证据在消费端 `sticker_preference.rs::check_drift`：
    /// ```rust
    /// let user = points.iter().filter(|p| p.source == "user").collect();
    /// let recent  = &user[..w];    // ← 取**前** w 个当作「近期窗口」
    /// let history = &user[w..];
    /// ```
    /// `user[..w]` 被当作 recent，因此数组**必须最新在前**。
    /// 若按升序返回，漂移检测会把**最旧的** w 条当成近期窗口 —— 结论完全反过来。
    /// （我最初就是照 Rust 那句过时注释加了 `.reversed()`，已修正。）
    ///
    /// 另：`limit` 下限为 1（对应 `limit.toInt().coerceAtLeast(1)`）。
    func usageHistory(limit: UInt64) -> String {
        do {
            let rows = try database.pool.read { db -> [[String: Any]] in
                try Row.fetchAll(db, sql: """
                SELECT stickerId, source, timestamp, contextTags
                FROM sticker_usage_log
                ORDER BY timestamp DESC LIMIT ?
                """, arguments: [Int(max(limit, 1))]).map { row -> [String: Any] in
                    return [
                        "sticker_id": row["stickerId"] as Int64? ?? 0,
                        "source": row["source"] as String? ?? "model",
                        "timestamp_ms": row["timestamp"] as Int64? ?? 0,
                        "context_tags": Self.parseContextTags(row["contextTags"] as String? ?? ""),
                    ]
                }
            }
            return encodeArray(rows)
        } catch {
            log.error("usageHistory 失败：\(String(describing: error), privacy: .public)")
            return "[]"
        }
    }

    // MARK: 内部

    /// 对应 Android `splitCsv`：按逗号切分 → **逐项 trim** → 丢弃空项。
    ///
    /// 早先我用 `split(separator: ",")` 直接映射，既没 trim 也没过滤空项，
    /// `"a, b, ,c"` 会得到 `["a"," b","","c"]` 而不是 `["a","b","c"]`。
    static func splitCsv(_ csv: String) -> [String] {
        csv.split(separator: ",", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }

    /// 对应 Android `parseContextTags`：
    /// 以 `[` 开头先按 JSON 数组解析，失败则按 CSV 兜底；否则直接按 CSV。
    static func parseContextTags(_ raw: String) -> [String] {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("[") {
            if let data = trimmed.data(using: .utf8),
               let parsed = try? JSONSerialization.jsonObject(with: data) as? [String] {
                return parsed
            }
            return splitCsv(trimmed)
        }
        return splitCsv(trimmed)
    }
}
