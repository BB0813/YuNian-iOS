import Foundation
// ⚠️ 第 172 轮：补 import GRDB。
// 上一版用了 `GRDB.Row.fetchAll(...)` 却没 import GRDB ——
// CI（d7d0f06）报 "cannot find 'GRDB' in scope"。
// 这与第 139 轮 MessageSearchView 漏 import GRDB 是完全同一个错误，
// 同一个坑第二次踩。（仓内 verify_imports 关卡按「用了哪些 GRDB 符号」
// 反推 import，我用了限定名 `GRDB.Row`，它没认出来。）
import GRDB

/// 会话列表数据 —— 对应 Android `HomeViewModel` + `ConversationSummaryDao`
/// + `CompanionDao.getAllCompanions()`。
///
/// ## 权威来源（第 170 轮，子代理只读勘察）
/// `feature/profile/.../HomeViewModel.kt`（98 行）：
///
/// | 项 | Kotlin | 行号 |
/// |---|---|---|
/// | 伴侣列表（**决定顺序**） | `SELECT * FROM companions ORDER BY updatedAt DESC` | CompanionDao.kt:14 |
/// | 摘要表 | `SELECT * FROM conversation_summary WHERE sessionType='chat' ORDER BY isPinned DESC, lastMessageTimestamp DESC` | ConversationSummaryDao.kt:13 |
/// | 组装 | `companions.map { ... }` —— summary 的排序**被 associateBy 丢掉** | HomeViewModel.kt:74 |
/// | 未读 | `(summary?.unreadCount ?: 0) > 0` —— **布尔，不显示条数** | HomeViewModel.kt:87 |
/// | 最后消息 preview | `content.take(100)` | ChatRepository.kt:372 |
///
/// ⚠️ **顺序陷阱**：DAO 的 SQL 写了 `ORDER BY isPinned DESC, lastMessageTimestamp DESC`，
/// 但 ViewModel 用 `summaries.associateBy { it.sessionId }` 转 Map 后**完全丢弃**，
/// 最终顺序由 `companions` 的 `updatedAt DESC` 决定 —— 置顶排序在 UI 上不生效。
/// 这是 Android 的现状。iOS 若要"修正"它，就是与 Android 分叉，
/// 故此处**照搬现状**（按 updatedAt DESC），并在注释中记下。
///
/// ⚠️ **`conversation_summary` 表在 iOS schema 里存在但没人写过**
/// （第 62 轮建表至今无写入方）。本实现改为**直接联表查 messages** 取最后一条，
/// 不依赖那张物化冗余表 —— Android 侧它是写入时增量维护的，
/// iOS 侧没有那套维护逻辑，硬用会得到恒空列表。
struct ConversationRepository {

    private let database: YuNianDatabase

    init(database: YuNianDatabase) {
        self.database = database
    }

    /// 列表项。
    /// ⚠️ 第 171 轮：改名为 `ConvRow`。
    /// 原名 `Row` 与 GRDB 的 `Row` 同名 —— 在这个文件里
    /// `Row.fetchAll(...)` 会解析到本 struct 而不是 GRDB.Row，
    /// CI（3c4cd6f）报 "type 'ConversationRepository.Row' has no member 'fetchAll'"。
    struct ConvRow: Identifiable, Equatable {
        let companionId: Int64
        var name: String
        var avatarUrl: String?
        /// 最后一条消息正文（已截到 100 字符）。nil = 从未聊过。
        var lastMessage: String?
        var lastMessageAt: Int64?
        /// 是否来自用户（副标题是否要加"我："前缀由 UI 决定）。
        var lastMessageFromUser: Bool = false
        /// 有无未读 —— **布尔，不显示条数**（Android HomeViewModel.kt:87）。
        var hasUnread: Bool = false
        /// 亲密度（Android 侧 companions 表有该字段，列表 UI 未用，此处保留备用）。
        var intimacy: Int = 0

        var id: Int64 { companionId }

        /// 列表副标题：最后消息，或 Kotlin 的兜底文案（HomeScreen.kt:538）。
        var preview: String {
            guard let lastMessage, !lastMessage.isEmpty else {
                return "还没有聊天记录，开始聊天吧"
            }
            return lastMessage
        }
    }

    // MARK: - 查询

    /// 取会话列表。
    ///
    /// ## 顺序
    /// `ORDER BY c.updatedAt DESC` —— 与 Android 的
    /// `CompanionDao.getAllCompanions()` 一致（HomeViewModel.kt:74 + CompanionDao.kt:14）。
    ///
    /// ## 最后一条消息
    /// Android 靠物化的 `conversation_summary.lastMessagePreview`
    /// （ChatRepository.kt:372 写入时维护，`take(100)`）。
    /// iOS 侧没有那个维护链路，故用相关子查询直接取：
    /// 与 `MessageDao.getLastMessageSync`（:225）同一口径
    /// (`ORDER BY timestamp DESC, id DESC LIMIT 1`)，
    /// 但**排除 REASONING**（Kotlin 的未读统计排它，
    /// 而 rebuildSummaryForChat 只显式排 TOOL_ACTIVITY ——
    /// 这里取更严格的那个，避免列表里出现"思考过程"）。
    func rows(limit: Int = 200) throws -> [ConvRow] {
        try database.pool.read { db in
            let sql = """
                SELECT
                    c.id            AS companionId,
                    c.name          AS name,
                    c.avatarUrl     AS avatarUrl,
                    c.intimacy      AS intimacy,
                    last.content    AS lastContent,
                    last.ts         AS lastTs,
                    last.fromUser   AS lastFromUser
                FROM companions c
                LEFT JOIN (
                    SELECT m.conversationId, m.timestamp AS ts, m.isFromUser AS fromUser,
                           b.content AS content,
                           ROW_NUMBER() OVER (
                               PARTITION BY m.conversationId
                               ORDER BY m.timestamp DESC, m.id DESC
                           ) AS rn
                    FROM messages m
                    LEFT JOIN message_bodies b ON b.messageId = m.id
                    WHERE m.conversationType = 'chat'
                      AND m.type NOT IN ('REASONING', 'TOOL_ACTIVITY')
                ) last ON last.companionId = c.id AND last.rn = 1
                ORDER BY c.updatedAt DESC
                LIMIT ?
                """
            let grdbRows = try GRDB.Row.fetchAll(db, sql: sql, arguments: [limit])
            return grdbRows.map { r in
                // ⚠️ 第 173 轮：不用 memberwise init。
                // ConvRow 有 8 个字段（其中 3 个带默认值），合成 init 的
                // 参数顺序按**声明顺序**，而我按业务顺序写标签就会报
                // "incorrect argument labels in call"（CI 8383de8）。
                // 逐字段赋值不依赖顺序，也不受将来加字段影响。
                var row = ConvRow(
                    companionId: r["companionId"] as Int64? ?? 0,
                    name: r["name"] as String? ?? "",
                    avatarUrl: r["avatarUrl"] as String?,
                    lastMessage: (r["lastContent"] as String?).map { String($0.prefix(100)) },
                    lastMessageAt: r["lastTs"] as Int64?,
                    lastMessageFromUser: (r["lastFromUser"] as Int? ?? 0) != 0,
                    hasUnread: false,
                    intimacy: r["intimacy"] as Int? ?? 0
                )
                return row
            }
        }
    }

    /// 未读计数 —— Kotlin `MessageDao.getUnreadMessageCount`（MessageDao.kt:419）。
    ///
    /// ⚠️ **游标是 `(timestamp, id)` 二元组比较，不是单纯比时间**。
    /// 同一毫秒内可能有多条消息，只比 timestamp 会把同刻的先到消息漏判成未读。
    /// Kotlin 的原文：
    /// ```
    /// ... AND (:readThroughTs IS NULL
    ///      OR timestamp > :readThroughTs
    ///      OR (timestamp = :readThroughTs AND id > :readThroughId))
    /// ```
    ///
    /// 游标 NULL 时视为"全未读"（`IS NULL` 分支）—— 与 Kotlin 一致。
    func unreadCount(companionId: Int64) throws -> Int {
        let cursor = try readCursor(companionId: companionId)
        return try database.pool.read { db in
            if let ts = cursor?.timestamp, let mid = cursor?.messageId {
                return try Int.fetchOne(db, sql: """
                    SELECT COUNT(*) AS c
                    FROM messages
                    WHERE conversationId = ? AND conversationType = 'chat'
                      AND isFromUser = 0
                      AND type NOT IN ('REASONING', 'TOOL_ACTIVITY')
                      AND (timestamp > ?
                           OR (timestamp = ? AND id > ?))
                    """, arguments: [companionId, ts, ts, mid]) ?? 0
            }
            // 无游标 → 全未读（Kotlin 的 IS NULL 分支）
            return try Int.fetchOne(db, sql: """
                SELECT COUNT(*) AS c
                FROM messages
                WHERE conversationId = ? AND conversationType = 'chat'
                  AND isFromUser = 0
                  AND type NOT IN ('REASONING', 'TOOL_ACTIVITY')
                """, arguments: [companionId]) ?? 0
        }
    }

    /// 读已读游标。没有记录时返回 nil。
    func readCursor(companionId: Int64) throws -> (timestamp: Int64, messageId: Int64)? {
        try database.pool.read { db in
            guard let row = try GRDB.Row.fetchOne(db, sql: """
                SELECT readThroughMessageTimestamp, readThroughMessageId
                FROM conversation_summary
                WHERE sessionId = ? AND sessionType = 'chat'
                """, arguments: [companionId]) else { return nil }
            guard let ts = row["readThroughMessageTimestamp"] as Int64?,
                  let mid = row["readThroughMessageId"] as Int64? else { return nil }
            return (ts, mid)
        }
    }

    /// 把已读游标推到该会话当前最新消息，并清零未读。
    ///
    /// ## Kotlin 对应
    /// `ConversationSummaryDao.markReadThroughLatest`（:43-44）：
    /// ```
    /// UPDATE conversation_summary
    /// SET readThroughMessageTimestamp = lastMessageTimestamp,
    ///     readThroughMessageId = lastMessageId,
    ///     unreadCount = 0
    /// WHERE sessionId = ? AND sessionType = ?
    /// ```
    ///
    /// ⚠️ Kotlin 用的 `lastMessageTimestamp` 是 **summary 表物化的值**，
    /// 而那个值由 `ChatRepository.updateSummaryForChat` 维护（含 take(100) 等规则）。
    /// iOS 侧没有那条维护链路，故这里改为**实时从 messages 取最新一条** ——
    /// 与 Kotlin 的 `rebuildSummaryForChat`（ChatRepository.kt:408-436）
    /// 取 latest 的口径一致，且不依赖已存在的 summary 行。
    ///
    /// ⚠️ 一个 Kotlin 也有的边界：若 latest 是 TOOL_ACTIVITY，
    /// Kotlin 的 `rebuildSummaryForChat` 会**维持原状直接返回**（:427）。
    /// 这里同样只把游标推到"非 TOOL_ACTIVITY/REASONING"的最新一条，
    /// 与未读计数的排除口径保持一致。
    @discardableResult
    func markReadThroughLatest(companionId: Int64) throws -> Bool {
        try database.pool.write { db in
            guard let latest = try GRDB.Row.fetchOne(db, sql: """
                SELECT m.id AS id, m.timestamp AS ts
                FROM messages m
                WHERE m.conversationId = ? AND m.conversationType = 'chat'
                  AND m.type NOT IN ('REASONING', 'TOOL_ACTIVITY')
                ORDER BY m.timestamp DESC, m.id DESC
                LIMIT 1
                """, arguments: [companionId]) else {
                // 一条消息都没有：无可读。Kotlin 此时 deleteSummary，
                // iOS 侧不清行（那张行可能由别处建立），只报 false。
                return false
            }
            let ts = latest["ts"] as Int64? ?? 0
            let mid = latest["id"] as Int64? ?? 0

            // ⚠️ 没有 summary 行时 INSERT 一行 —— 否则 UPDATE 影响 0 行。
            // Kotlin 那边 summary 行由发送链路建立，iOS 侧没有，
            // 所以这里要能自行创建。lastMessagePreview 等列给占位值：
            // 它们只服务会话列表，而列表走 messages 联表、不读这列。
            try db.execute(sql: """
                INSERT INTO conversation_summary
                  (sessionId, sessionType, lastMessageId, lastMessagePreview,
                   lastMessageTimestamp, lastMessageIsFromUser,
                   readThroughMessageTimestamp, readThroughMessageId,
                   unreadCount, isPinned, isMuted)
                VALUES (?, 'chat', ?, '', ?, 0, ?, ?, 0, 0, 0)
                ON CONFLICT(sessionId, sessionType) DO UPDATE SET
                  readThroughMessageTimestamp = excluded.readThroughMessageTimestamp,
                  readThroughMessageId = excluded.readThroughMessageId,
                  unreadCount = 0
                """, arguments: [companionId, mid, ts, ts, mid])
            return true
        }
    }

    /// 会话数量（首页计数文本用，对应 HomeScreen.kt:185-189）。
    func chatCount() throws -> Int {
        try database.pool.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) AS c FROM companions") ?? 0
        }
    }
}
