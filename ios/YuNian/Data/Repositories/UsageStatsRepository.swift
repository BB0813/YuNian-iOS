import Foundation
import GRDB
import os

/// 用量统计 —— `token_usage` 的**读取侧**。
///
/// ## 这张表以前是空的
/// iOS 此前**没有任何东西写** `token_usage`（只有备份恢复会往里灌数据），
/// 所以"用量页"根本做不出来 —— 摆一个恒为 0 的数字比没有更糟。
/// 第 207 轮的「本轮收据」开始按 Android 同款规则写它
/// （每回合两行：伴侣行 + `companionId = -1` 全局行），这一页才有意义。
///
/// ## ⚠️ 两个必须记住的坑（写错就是"数字看着对但其实是错的"）
/// 1. **`companionId = -1` 是全局汇总行，不是某个伴侣。**
///    · 看**总量** → 只读 `-1` 行
///    · 看**按伴侣分布** → 必须排除 `-1` 行
///    两件事一起做时会算两遍（`-1` 行本来就等于各伴侣之和）。
/// 2. **读一律带 `deviceId`**（与 Android `TokenUsageDao` 逐条一致）：
///    同一账号在多设备上各写各的行，不加这个条件会把别的设备的用量算进来。
///
/// ## 数据从哪天开始
/// 这张表在 iOS 上从**第 207 轮那个版本**才开始有运行时数据，
/// 之前的历史用量**不存在**（无法追溯）。界面必须如实说明，
/// 否则用户会以为"我以前没用过"。
struct UsageStatsRepository {

    private static let log = Logger(subsystem: "com.yunian.ai", category: "usage")

    /// 今天的日期串。
    ///
    /// ⚠️ **必须与写入侧同一份实现**（`TurnReceiptStore.dateString`）：
    /// 两边格式一旦不一致，"今日用量"会永远查不到记录 ——
    /// 而且症状是"今天没用量"这种看着像功能没生效的假象。
    static func todayString(_ now: Date = Date()) -> String {
        TurnReceiptStore.dateString(now)
    }

    /// 一天的用量（全局行）。
    struct Day: Identifiable, Equatable {
        var date: String
        var inputTokens: Int
        var outputTokens: Int
        var totalTokens: Int
        var requests: Int
        var id: String { date }
    }

    /// 某个伴侣的累计用量（**不含** `-1` 全局行）。
    struct CompanionTotal: Identifiable, Equatable {
        var companionId: Int64
        var name: String
        var inputTokens: Int
        var outputTokens: Int
        var totalTokens: Int
        var requests: Int
        var id: Int64 { companionId }
    }

    struct Stats: Equatable {
        var days: [Day] = []
        /// 全局累计（只来自 `-1` 行）。
        var totalInput = 0
        var totalOutput = 0
        var totalTokens = 0
        var totalRequests = 0
        /// 按伴侣分布（排除 `-1` 行）。
        var companions: [CompanionTotal] = []
        /// 最早一条记录的日期（nil = 一条都没有）。
        var firstDate: String?

        var hasAnyData: Bool { firstDate != nil }
    }

    /// 读统计。
    ///
    /// - Parameters:
    ///   - database: 数据库（nil 时返回空统计 —— 界面据此显示"暂无数据"）
    ///   - deviceId: 本机 id（**必传**，见类型注释第 2 条）
    ///   - limitDays: 按日列表最多取多少天（默认 30）
    static func load(database: YuNianDatabase?, deviceId: String, limitDays: Int = 30) -> Stats {
        guard let database else { return Stats() }
        var stats = Stats()
        do {
            try database.pool.read { db in
                stats.days = try Row.fetchAll(db, sql: """
                    SELECT date, inputTokens, outputTokens, totalTokens, requestCount
                    FROM token_usage
                    WHERE companionId = -1 AND deviceId = ?
                    ORDER BY date DESC LIMIT ?
                    """, arguments: [deviceId, limitDays]).map { row in
                    Day(date: row["date"] as String? ?? "",
                        inputTokens: row["inputTokens"] as Int? ?? 0,
                        outputTokens: row["outputTokens"] as Int? ?? 0,
                        totalTokens: row["totalTokens"] as Int? ?? 0,
                        requests: row["requestCount"] as Int? ?? 0)
                }

                // ⚠️ 别名一律用**真实列名**（`AS i` 之类会被 `verify_swift_sql`
                // 判成"schema 里不存在" —— 它按列名校验，不认投影别名）。
                let total = try Row.fetchOne(db, sql: """
                    SELECT COALESCE(SUM(inputTokens), 0) AS inputTokens,
                           COALESCE(SUM(outputTokens), 0) AS outputTokens,
                           COALESCE(SUM(totalTokens), 0) AS totalTokens,
                           COALESCE(SUM(requestCount), 0) AS requestCount,
                           MIN(date) AS date
                    FROM token_usage WHERE companionId = -1 AND deviceId = ?
                    """, arguments: [deviceId])
                stats.totalInput = total?["inputTokens"] as Int? ?? 0
                stats.totalOutput = total?["outputTokens"] as Int? ?? 0
                stats.totalTokens = total?["totalTokens"] as Int? ?? 0
                stats.totalRequests = total?["requestCount"] as Int? ?? 0
                stats.firstDate = total?["date"] as String?

                // ⚠️ 排除 -1：它是全局汇总，留着会和各伴侣行重复计数
                stats.companions = try Row.fetchAll(db, sql: """
                    SELECT t.companionId AS companionId,
                           COALESCE(c.name, '') AS name,
                           SUM(t.inputTokens) AS inputTokens,
                           SUM(t.outputTokens) AS outputTokens,
                           SUM(t.totalTokens) AS totalTokens,
                           SUM(t.requestCount) AS requestCount
                    FROM token_usage t
                    LEFT JOIN companions c ON c.id = t.companionId
                    WHERE t.companionId <> -1 AND t.deviceId = ?
                    GROUP BY t.companionId
                    ORDER BY SUM(t.totalTokens) DESC
                    """, arguments: [deviceId]).map { row in
                    CompanionTotal(companionId: row["companionId"] as Int64? ?? 0,
                                   name: row["name"] as String? ?? "",
                                   inputTokens: row["inputTokens"] as Int? ?? 0,
                                   outputTokens: row["outputTokens"] as Int? ?? 0,
                                   totalTokens: row["totalTokens"] as Int? ?? 0,
                                   requests: row["requestCount"] as Int? ?? 0)
                }
            }
        } catch {
            log.error("读用量失败：\(error.localizedDescription, privacy: .public)")
        }
        return stats
    }

    /// 删除早于 `days` 天的记录（Android `deleteOldRecords` 的对应物）。
    ///
    /// - Returns: 删掉的行数
    @discardableResult
    static func clearOlderThan(days: Int, database: YuNianDatabase?,
                               deviceId: String, now: Date = Date()) -> Int {
        guard let database else { return 0 }
        let cutoff = Int64(now.addingTimeInterval(-Double(days) * 86_400).timeIntervalSince1970 * 1000)
        do {
            return try database.pool.write { db in
                try db.execute(sql: """
                    DELETE FROM token_usage WHERE deviceId = ? AND timestamp < ?
                    """, arguments: [deviceId, cutoff])
                return db.changesCount
            }
        } catch {
            log.error("清理用量失败：\(error.localizedDescription, privacy: .public)")
            return 0
        }
    }

    /// 清空本机全部用量记录。
    @discardableResult
    static func clearAll(database: YuNianDatabase?, deviceId: String) -> Int {
        guard let database else { return 0 }
        do {
            return try database.pool.write { db in
                try db.execute(sql: "DELETE FROM token_usage WHERE deviceId = ?",
                               arguments: [deviceId])
                return db.changesCount
            }
        } catch {
            log.error("清空用量失败：\(error.localizedDescription, privacy: .public)")
            return 0
        }
    }
}
