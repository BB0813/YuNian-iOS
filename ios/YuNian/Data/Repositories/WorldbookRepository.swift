import Foundation
import GRDB
import os

/// 世界书仓储（读 + 启用开关 + 导入/删除）。
///
/// ## iOS 之前的状况（比技能更隐蔽）
/// 表 `worldbooks(id, name, json, enabled, companionId, updatedAt)` 一直在，
/// `WorldbookRuntimeSync` 每回合也会把它推给引擎 —— 但 **iOS 没有任何界面、
/// 也没有任何东西写这张表**，所以世界书实际恒为空：
/// 引擎拿到的永远是 `nil`（= 不注入），而界面上看不出任何异常。
///
/// ## ⚠️ 三个必须记住的事实
/// 1. **条目来源是 `worldbooks.json` 这一列，不是 `lorebook_entries` 表。**
///    `lorebook_entries` 只是给结构化编辑器用的视图，**不参与合成**
///    （见 `WorldbookRuntimeSync` 末尾的契约清单第 2 条）。
/// 2. **引擎取"第一本启用的"**：`SELECT json FROM worldbooks WHERE enabled = 1 LIMIT 1`
///    （全局路径）—— 同时启用多本时**只有一本生效**，而且哪一本取决于行序。
///    所以界面必须把"当前生效的是哪本"显出来，并在多本同时启用时给出警告。
/// 3. `companionId = nil` = 全局书；非 nil = 该角色专属（按伴侣合成时才用得到）。
struct WorldbookRepository {

    private let database: YuNianDatabase
    private let log = Logger(subsystem: "com.yunian.ai", category: "repo.worldbook")

    init(database: YuNianDatabase) {
        self.database = database
    }

    struct Book: Sendable, Equatable, Identifiable {
        var id: Int64
        var name: String
        var json: String
        var enabled: Bool
        var companionId: Int64?
        var updatedAt: Int64

        /// 条目数（解析不出来时 0）。
        var entryCount: Int {
            WorldbookRuntimeSync.entrySummaries(json).count
        }

        /// JSON 是否像一本世界书（导入校验与列表提示都用它）。
        var isParsable: Bool {
            WorldbookRuntimeSync.looksLikeWorldbook(json)
        }
    }

    // MARK: - 读

    func list() -> [Book] {
        (try? database.pool.read { db in
            try Row.fetchAll(db, sql: """
                SELECT id, name, json, enabled, companionId, updatedAt
                FROM worldbooks
                ORDER BY enabled DESC, updatedAt DESC
                """).map(row(from:))
        }) ?? []
    }

    /// **引擎真正会用的那一本**（全局路径）。
    ///
    /// 逐字对应 `WorldbookRuntimeSync.activeGlobalBookJson` 的查询，
    /// 所以界面显示"当前生效"与实际注入的必然是同一本 ——
    /// 这里若自己写成"最后更新的那本"，界面就会撒谎。
    func activeGlobalBook() -> Book? {
        (try? database.pool.read { db in
            try Row.fetchOne(db, sql: """
                SELECT id, name, json, enabled, companionId, updatedAt
                FROM worldbooks WHERE enabled = 1 LIMIT 1
                """).map(row(from:))
        }) ?? nil
    }

    func counts() -> (all: Int, enabled: Int) {
        let books = list()
        return (books.count, books.filter(\.enabled).count)
    }

    // MARK: - 写

    /// 新建（或按名字覆盖）一本世界书，返回新行的 id（失败 -1）。
    ///
    /// - Parameter replaceExistingID: 传 nil 时按名字查重：同名则覆盖（避免导入两次出现两本）。
    @discardableResult
    func save(name: String, json: String, companionId: Int64? = nil,
              replaceExistingID: Int64? = nil) -> Int64 {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let finalName = trimmedName.isEmpty ? "未命名世界书" : trimmedName
        do {
            return try database.pool.write { db -> Int64 in
                var targetId = replaceExistingID
                if targetId == nil {
                    targetId = try Int64.fetchOne(
                        db, sql: "SELECT id FROM worldbooks WHERE name = ? LIMIT 1",
                        arguments: [finalName])
                }
                if let id = targetId {
                    try db.execute(sql: """
                        UPDATE worldbooks SET name = ?, json = ?, companionId = ?, updatedAt = ?
                        WHERE id = ?
                        """, arguments: [finalName, json, companionId, now, id])
                    return id
                }
                try db.execute(sql: """
                    INSERT INTO worldbooks (name, json, enabled, companionId, updatedAt)
                    VALUES (?, ?, 1, ?, ?)
                    """, arguments: [finalName, json, companionId, now])
                return db.lastInsertedRowID
            }
        } catch {
            log.error("保存世界书失败：\(error.localizedDescription, privacy: .public)")
            return -1
        }
    }

    /// 启用 / 停用。
    ///
    /// ⚠️ **不做"自动停用其它本"**：引擎是 `LIMIT 1`，同时启用多本时只有一本生效，
    /// 但默默改掉用户其它书的开关是更坏的行为。界面改为**显式警告 + 一键只留这本**。
    @discardableResult
    func setEnabled(_ enabled: Bool, id: Int64) -> Bool {
        do {
            let changed = try database.pool.write { db -> Int in
                try db.execute(
                    sql: "UPDATE worldbooks SET enabled = ?, updatedAt = ? WHERE id = ?",
                    arguments: [enabled ? 1 : 0,
                                Int64(Date().timeIntervalSince1970 * 1000), id]
                )
                return db.changesCount
            }
            return changed > 0
        } catch {
            log.error("切换世界书失败：\(error.localizedDescription, privacy: .public)")
            return false
        }
    }

    /// 只保留这一本启用（其余全部停用）。给"多本同时启用"的警告提供一键处置。
    @discardableResult
    func enableOnly(id: Int64) -> Int {
        do {
            return try database.pool.write { db -> Int in
                let now = Int64(Date().timeIntervalSince1970 * 1000)
                try db.execute(
                    sql: "UPDATE worldbooks SET enabled = 0, updatedAt = ? WHERE id <> ?",
                    arguments: [now, id])
                try db.execute(
                    sql: "UPDATE worldbooks SET enabled = 1, updatedAt = ? WHERE id = ?",
                    arguments: [now, id])
                return db.changesCount
            }
        } catch {
            log.error("只保留一本失败：\(error.localizedDescription, privacy: .public)")
            return 0
        }
    }

    @discardableResult
    func delete(id: Int64) -> Bool {
        do {
            let changed = try database.pool.write { db -> Int in
                try db.execute(sql: "DELETE FROM worldbooks WHERE id = ?", arguments: [id])
                return db.changesCount
            }
            return changed > 0
        } catch {
            log.error("删除世界书失败：\(error.localizedDescription, privacy: .public)")
            return false
        }
    }

    // MARK: - 映射

    private func row(from row: Row) -> Book {
        Book(
            id: row["id"] as Int64? ?? 0,
            name: row["name"] as String? ?? "",
            json: row["json"] as String? ?? "",
            enabled: (row["enabled"] as Int? ?? 0) != 0,
            companionId: row["companionId"] as Int64?,
            updatedAt: row["updatedAt"] as Int64? ?? 0
        )
    }
}
