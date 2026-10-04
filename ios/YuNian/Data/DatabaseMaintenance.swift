import Foundation
import GRDB
import os

/// 数据库维护 —— 对应 Android 侧 `DataCleanupManager`。
///
/// ## 职责
/// 1. 把每个会话的热消息裁到 `hotMessagesPerConversation` 条，超出的**归档**（不是删除）
/// 2. `PRAGMA optimize` —— 让 SQLite 更新查询计划器统计
/// 3. `PRAGMA wal_checkpoint(PASSIVE)` —— 回收 WAL
///
/// ## 为什么必须做
/// 不做的话热表无限增长：单聊/群聊每条消息都进 `messages`，
/// 索引与 FTS 索引同步膨胀，最终拖慢分页与检索。
///
/// ## 为什么是「归档」而不是「删除」
/// 归档保留完整历史与检索能力（FTS 的 rowid 就是 messageId，行移动后索引仍有效，
/// 见 `MessageRepository.archiveOldest`）。用户翻历史时仍能读到，只是不在热表里。
///
/// ## ⚠️ 会话类型取值
/// 必须是 `"chat"` / `"group"`（小写业务字面量），**不是枚举名** ——
/// 这一点由 `ConversationType` 保证，而它的正确性由
/// `Tools/verify_literals.py` 对着 Android 源码交叉核对。
/// 第 8 轮这里曾写错，导致所有查询静默返回空结果。
enum DatabaseMaintenance {

    private static let log = Logger(subsystem: "com.yunian.ai", category: "maintenance")

    /// 对应 Android 的 `HOT_MESSAGES_PER_CONVERSATION`。
    static let hotMessagesPerConversation = 5_000

    /// 对应 Android 的 `CLEANUP_INTERVAL_HOURS`。
    static let intervalHours: Double = 24

    /// 上次维护时间戳的持久化键。
    /// Android 用 SharedPreferences（`data_cleanup` / `last_cleanup_time`）；
    /// iOS 用 UserDefaults —— 非机密，放 Keychain 不合适。
    static let lastRunDefaultsKey = "yunian.maintenance.lastRunMs"

    /// 需要维护的会话类型。Android 侧字面量就是 `listOf("chat", "group")`。
    static let maintainedTypes: [MessageRepository.ConversationType] = [.chat, .group]

    struct Outcome: Sendable, Equatable {
        var archived: Int = 0
        var conversationsScanned: Int = 0
        var skipped: Bool = false
        var skipReason: String = ""

        var summary: String {
            if skipped { return "跳过（\(skipReason)）" }
            return "归档 \(archived) 条 / 扫描 \(conversationsScanned) 个会话"
        }
    }

    /// 上次维护时间（毫秒）；0 表示从未跑过。
    static var lastRunMilliseconds: Int64 {
        get { Int64(UserDefaults.standard.double(forKey: lastRunDefaultsKey)) }
        set { UserDefaults.standard.set(Double(newValue), forKey: lastRunDefaultsKey) }
    }

    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// 是否已超过间隔（对应 Android `cleanupIfNeeded` 的时间窗判断）。
    static func isDue(now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Bool {
        let last = lastRunMilliseconds
        if last == 0 { return true }
        let elapsed = now - last
        // 时间倒流（用户改系统时间）也视为到期
        if elapsed < 0 { return true }
        return Double(elapsed) >= intervalHours * 60 * 60 * 1000
    }

    /// 到点才执行 —— 对应 Android 的 `cleanupIfNeeded`。
    ///
    /// Android 侧由 WorkManager 每日触发（约束：电量不低 + 设备空闲）；
    /// iOS 侧应在 M5 的 `BGTaskScheduler` 里以同样节奏调用本方法，
    /// 并在 App 启动时补一次「上次距今是否已超 24h」的检查。
    @discardableResult
    static func runIfNeeded(
        database: YuNianDatabase,
        messages: MessageRepository,
        force: Bool = false,
        now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) -> Outcome {
        guard force || isDue(now: now) else {
            return Outcome(skipped: true, skipReason: "距上次不足 \(Int(intervalHours)) 小时")
        }
        do {
            var outcome = try perform(database: database, messages: messages)
            outcome.skipped = false
            lastRunMilliseconds = now
            log.info("数据库维护完成：\(outcome.summary, privacy: .public)")
            return outcome
        } catch {
            // 与 Android 一致：失败不更新 lastRun，下次仍会重试
            log.error("数据库维护失败：\(String(describing: error), privacy: .public)")
            var outcome = Outcome()
            outcome.skipped = true
            outcome.skipReason = "失败：" + String(describing: error)
            return outcome
        }
    }

    /// 无条件执行一次维护（测试与「立即清理」入口用）。
    @discardableResult
    static func perform(
        database: YuNianDatabase,
        messages: MessageRepository
    ) throws -> Outcome {
        var outcome = Outcome()

        for type in maintainedTypes {
            let conversationIds = try messages.distinctConversationIds(type: type)
            for conversationId in conversationIds {
                outcome.conversationsScanned += 1
                let archived = try messages.archiveOldMessages(
                    conversationId: conversationId,
                    type: type,
                    retainCount: hotMessagesPerConversation
                )
                outcome.archived += archived

                if archived > 0 {
                    // Android 侧在此处 evict 内存缓存（MessageCache.evictChat / evictGroup）。
                    // iOS 侧目前没有等价的消息缓存层（分页直接走 GRDB），
                    // 因此这里只留钩子。引入缓存时必须在此处接入，否则会读到已归档的消息。
                    awaitCacheEviction(conversationId: conversationId, type: type)
                }
            }
        }

        // PRAGMA optimize：让查询计划器更新统计（SQLite 建议在连接关闭前或定期执行）
        try database.pool.write { db in
            try db.execute(sql: "PRAGMA optimize")
        }

        // WAL checkpoint（PASSIVE）：不阻塞读者，尽力回收 WAL。
        // ⚠️ 这是唯一一处**故意忽略返回行**的查询 —— PRAGMA 会返回一行结果，
        //    用 execute 会报错，因此走 fetch。
        _ = try database.pool.read { db in
            try Row.fetchOne(db, sql: "PRAGMA wal_checkpoint(PASSIVE)")
        }

        return outcome
    }

    /// 内存缓存驱逐钩子。
    ///
    /// iOS 侧暂无消息内存缓存（列表分页直接查 GRDB），因此当前为空实现。
    /// **若将来引入缓存，必须在这里接上** —— 否则归档后界面仍会显示已移出热表的消息。
    private static func awaitCacheEviction(
        conversationId: Int64,
        type: MessageRepository.ConversationType
    ) {
        log.debug("归档后需驱逐缓存：\(type.rawValue, privacy: .public)#\(conversationId)")
    }

    /// 测试 / 「恢复默认」用。
    static func resetLastRun() {
        UserDefaults.standard.removeObject(forKey: lastRunDefaultsKey)
    }
}
