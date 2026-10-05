import Foundation
import GRDB
import os

/// 记忆仓储（读侧，M3 设置界面用）。
///
/// ## 与 Rust 召回路径的关系（先验证过才做）
/// 曾担心「iOS 的 `tools: []` 会让记忆注入失效，那这个界面就只能看不能用」。
/// 核实 `prompt_orchestrator.rs::build_user_context` 后确认：
/// 它调 `memory.select_working(...)`，**只看 `PromptOrchestrator` 持有的
/// MemorySelector，完全不看 `request.tools`**。
/// 而该 selector 已在修复 `orchestrator: nil` 时一并注入。
/// **所以这里显示的记忆确实是会进提示词的那批**，界面有实际价值。
final class MemoryRepository {

    private let database: YuNianDatabase
    private let log = Logger(subsystem: "com.yunian.ai", category: "repo.memory")

    init(database: YuNianDatabase) {
        self.database = database
    }

    /// 一条记忆（设置界面展示用）。
    struct Memory: Sendable, Equatable, Identifiable {
        var id: Int64
        var content: String
        var memoryType: String
        var scope: String
        var source: String
        var sourceId: Int64
        var importance: Double
        var accessCount: Int
        var observedAt: Int64
        var expiresAt: Int64?
        var isDeleted: Bool
    }

    // MARK: - 读

    /// 当前**有效**记忆（会进提示词的那批）。
    ///
    /// SQL 逐字对应 Android `UnifiedMemoryDao.getAllActive`：
    /// ```sql
    /// WHERE deviceId = :deviceId AND isDeleted = 0
    ///   AND (expiresAt IS NULL OR expiresAt > :now)
    /// ORDER BY importance DESC, observedAt DESC
    /// ```
    /// 这与 `AgentStores.listMemories` 的过滤条件一致 —— 两处必须同时保持，
    /// 否则界面显示的与会话实际用的不是同一批。
    func listActive(now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> [Memory] {
        (try? database.pool.read { db in
            try Row.fetchAll(db, sql: """
            SELECT id, content, memoryType, scope, source, sourceId, importance,
                   accessCount, observedAt, expiresAt, isDeleted
            FROM unified_memories
            WHERE deviceId = ? AND isDeleted = 0
              AND (expiresAt IS NULL OR expiresAt > ?)
            ORDER BY importance DESC, observedAt DESC
            """, arguments: [DeviceIdentity.deviceId, now]).map(row(from:))
        }) ?? []
    }

    /// 全部记忆（含软删除，供管理界面显示"已删除"）。
    /// 对应 Android `getAllSync`：`ORDER BY observedAt DESC`，**不过滤 isDeleted**。
    func listAll() -> [Memory] {
        (try? database.pool.read { db in
            try Row.fetchAll(db, sql: """
            SELECT id, content, memoryType, scope, source, sourceId, importance,
                   accessCount, observedAt, expiresAt, isDeleted
            FROM unified_memories
            WHERE deviceId = ?
            ORDER BY observedAt DESC
            """, arguments: [DeviceIdentity.deviceId]).map(row(from:))
        }) ?? []
    }

    /// 计数摘要（自检面板 / 界面标题用）。
    func counts(now: Int64 = Int64(Date().timeIntervalSince1970 * 1000))
        -> (active: Int, total: Int, deleted: Int)
    {
        (try? database.pool.read { db -> (Int, Int, Int) in
            let total = try Int.fetchOne(
                db, sql: "SELECT COUNT(*) FROM unified_memories WHERE deviceId = ?",
                arguments: [DeviceIdentity.deviceId]) ?? 0
            let active = try Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM unified_memories
                WHERE deviceId = ? AND isDeleted = 0
                  AND (expiresAt IS NULL OR expiresAt > ?)
                """, arguments: [DeviceIdentity.deviceId, now]) ?? 0
            return (active, total, total - active)
        }) ?? (0, 0, 0)
    }

    /// 软删除一条记忆（对应 Android `softDelete(id, now)`）。
    @discardableResult
    func softDelete(id: Int64) -> Bool {
        do {
            let changed = try database.pool.write { db -> Int in
                try db.execute(
                    sql: "UPDATE unified_memories SET isDeleted = 1, updatedAt = ? WHERE id = ?",
                    arguments: [Int64(Date().timeIntervalSince1970 * 1000), id]
                )
                return db.changesCount
            }
            return changed > 0
        } catch {
            log.error("softDelete 失败：\(String(describing: error), privacy: .public)")
            return false
        }
    }

    // MARK: - 映射

    private func row(from row: Row) -> Memory {
        Memory(
            id: row["id"] as Int64? ?? 0,
            content: row["content"] as String? ?? "",
            memoryType: row["memoryType"] as String? ?? "",
            scope: row["scope"] as String? ?? "",
            source: row["source"] as String? ?? "",
            sourceId: row["sourceId"] as Int64? ?? 0,
            importance: row["importance"] as Double? ?? 0,
            accessCount: row["accessCount"] as Int? ?? 0,
            observedAt: row["observedAt"] as Int64? ?? 0,
            expiresAt: row["expiresAt"] as Int64?,
            isDeleted: (row["isDeleted"] as Int? ?? 0) != 0
        )
    }
}
