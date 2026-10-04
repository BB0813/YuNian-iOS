import Foundation
import GRDB
import os

/// 消息仓储 —— 含 `message_search_index`（FTS）的维护。
///
/// ## 为什么这个文件必须存在
/// FTS 表**没有外键**，一致性完全靠应用层手工维护。Android 侧把这件事分散在
/// `MessageDao` 的 14 个写/删点上。iOS 侧若不逐一复刻，后果不是报错而是
/// **两端检索结果分叉**（「在 Android 搜得到、在 iOS 搜不到」）。
///
/// ## 一条容易做错的顺序约定
/// `deleteOldest(...)` 与 `deleteForConversation(...)` **必须先删 FTS 行，再删元数据行**。
/// 因为删 FTS 用的子查询是 `rowid IN (SELECT id FROM messages WHERE ...)` ——
/// 一旦元数据先被删掉，子查询就返回空集，FTS 行会永久残留（成为无法清理的脏索引，
/// 并让已删除的消息仍能被搜到）。Android 侧对应 `MessageDao.deleteOldMessagesForConversation`。
///
/// ## 归档不触碰 FTS
/// `archiveOldest(...)` 只是把行从 `messages` 复制到 `archived_messages` 再删热数据。
/// FTS 的 `rowid` 就是 `messageId`，行移动后依然有效 —— 检索归档走同一张 FTS 表。
final class MessageRepository {

    private let database: YuNianDatabase
    private let log = Logger(subsystem: "com.yunian.ai", category: "repo.message")

    init(database: YuNianDatabase) {
        self.database = database
    }

    /// 会话类型。
    ///
    /// ⚠️ 取值是**小写** `"chat"` / `"group"`，不是枚举名。
    /// `messages.conversationType` 是**普通 TEXT 列**（不是枚举列，不走 Room 的
    /// TypeConverter），因此存的是业务字面量而非 `value.name`。
    ///
    /// 依据：`ConversationRef` 的 `init` 块强制约束
    /// ```kotlin
    /// require(conversationType == "chat" || conversationType == "group")
    /// ```
    /// 且全仓所有赋值点（`StoredMessage` / `ChatViewModel` / `GroupChatPager` /
    /// `DataCleanupManager` 等）用的都是这两个小写字面量。
    ///
    /// 对比：`type` 与 `fileFormat` 是**枚举列**，Room 转换器按 `value.name` 存
    /// **大写**（`"TEXT"` / `"IMAGE"` …），`@SerialName("text")` 只影响 JSON 序列化。
    /// 这两个区分很容易搞混，写反了查询会静默返回空结果。
    enum ConversationType: String {
        case chat = "chat"
        case group = "group"
    }

    enum RepositoryError: Error, CustomStringConvertible {
        case invalidRetainCount(Int)

        var description: String {
            switch self {
            case let .invalidRetainCount(n):
                return "retainCount 必须 >= 0，实际 \(n)（对应 Android 的 require(retainCount >= 0)）"
            }
        }
    }

    struct StoredMessage: Sendable, Equatable {
        let id: Int64
        let conversationId: Int64
        let conversationType: String
        let isFromUser: Bool
        let senderId: Int64
        let timestamp: Int64
        let type: String
        let content: String
        let searchContent: String
    }

    // MARK: - 写入

    /// 插入一条消息（元数据 + 正文 + 检索索引）。整批在一个事务里。
    ///
    /// 对应 Android `MessageDao.insertStoredMessage` → `insertBody`：
    /// `insertMessage` → `insertBodyRecord` → `upsertSearchIndex(indexTokens(searchContent))`。
    @discardableResult
    func insert(
        conversationId: Int64,
        conversationType: ConversationType,
        isFromUser: Bool,
        senderId: Int64,
        type: String,
        fileFormat: String = "TEXT",
        content: String,
        searchContent: String,
        timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        turnId: String? = nil,
        eventIndex: Int? = nil,
        durationMs: Int? = nil,
        anchorMessageId: Int64? = nil
    ) throws -> Int64 {
        try database.pool.write { db in
            try db.execute(sql: """
            INSERT INTO messages
              (conversationId, conversationType, isFromUser, senderId, timestamp, type,
               fileFormat, turnId, eventIndex, durationMs, anchorMessageId)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """, arguments: [
                conversationId, conversationType.rawValue, isFromUser ? 1 : 0, senderId,
                timestamp, type, fileFormat, turnId, eventIndex, durationMs, anchorMessageId,
            ])
            let messageId = db.lastInsertedRowID

            try db.execute(sql: """
            INSERT OR REPLACE INTO message_bodies (messageId, content, searchContent, linkString)
            VALUES (?,?,?,?)
            """, arguments: [messageId, content, searchContent, ""])

            try self.upsertSearchIndex(db, messageId: messageId, searchContent: searchContent)
            return messageId
        }
    }

    /// 更新正文并同步刷新检索索引。
    ///
    /// 对应 Android `MessageDao.updateMessageContent`：**只有真的更新到行才重建索引**
    /// （`if (updated > 0)`）。少这个判断会导致「更新不存在的消息」也写入 FTS 脏行。
    @discardableResult
    func updateContent(messageId: Int64, content: String, searchContent: String) throws -> Bool {
        try database.pool.write { db in
            try db.execute(sql: """
            UPDATE message_bodies SET content = ?, searchContent = ? WHERE messageId = ?
            """, arguments: [content, searchContent, messageId])

            guard db.changesCount > 0 else { return false }
            try self.upsertSearchIndex(db, messageId: messageId, searchContent: searchContent)
            return true
        }
    }

    // MARK: - 删除

    /// 删除单条消息：FTS → 正文 → 元数据。
    ///
    /// 顺序不敏感（按 id 精确删），但仍先删 FTS 保持一致风格。
    func delete(messageId: Int64) throws {
        try database.pool.write { db in
            try self.removeSearchIndex(db, messageId: messageId)
            try db.execute(sql: "DELETE FROM message_bodies WHERE messageId = ?", arguments: [messageId])
            try db.execute(sql: "DELETE FROM messages WHERE id = ?", arguments: [messageId])
        }
    }

    /// 清空一个会话。
    ///
    /// ⚠️ **FTS 必须先删**：其子查询依赖 `messages` / `archived_messages` 仍然存在。
    /// 对应 Android `deleteHotSearchIndexForConversation` + `deleteArchivedSearchIndexForConversation`
    /// 在 `deleteMessageMetadataForConversation` 之前执行。
    func deleteForConversation(
        conversationId: Int64,
        type: ConversationType
    ) throws {
        try database.pool.write { db in
            // 1) 先删热数据的 FTS 行
            try db.execute(sql: """
            DELETE FROM message_search_index WHERE rowid IN (
              SELECT id FROM messages WHERE conversationId = ? AND conversationType = ?
            )
            """, arguments: [conversationId, type.rawValue])
            // 2) 再删归档数据的 FTS 行
            try db.execute(sql: """
            DELETE FROM message_search_index WHERE rowid IN (
              SELECT id FROM archived_messages WHERE conversationId = ? AND conversationType = ?
            )
            """, arguments: [conversationId, type.rawValue])
            // 3) 然后才删元数据与正文
            try db.execute(sql: """
            DELETE FROM message_bodies WHERE messageId IN (
              SELECT id FROM messages WHERE conversationId = ? AND conversationType = ?
            )
            """, arguments: [conversationId, type.rawValue])
            try db.execute(sql: """
            DELETE FROM messages WHERE conversationId = ? AND conversationType = ?
            """, arguments: [conversationId, type.rawValue])
            try db.execute(sql: """
            DELETE FROM archived_message_bodies WHERE messageId IN (
              SELECT id FROM archived_messages WHERE conversationId = ? AND conversationType = ?
            )
            """, arguments: [conversationId, type.rawValue])
            try db.execute(sql: """
            DELETE FROM archived_messages WHERE conversationId = ? AND conversationType = ?
            """, arguments: [conversationId, type.rawValue])
        }
    }

    /// 删除会话中最旧的 N 条消息。
    ///
    /// ⚠️ **FTS 必须先删**（见类型注释里的说明）。对应 Android
    /// `MessageDao.deleteOldMessagesForConversation`。
    @discardableResult
    func deleteOldest(
        conversationId: Int64,
        type: ConversationType,
        count: Int
    ) throws -> Int {
        guard count > 0 else { return 0 }
        return try database.pool.write { db in
            try db.execute(sql: """
            DELETE FROM message_search_index WHERE rowid IN (
              SELECT id FROM messages
              WHERE conversationId = ? AND conversationType = ?
              ORDER BY timestamp ASC, id ASC LIMIT ?
            )
            """, arguments: [conversationId, type.rawValue, count])

            try db.execute(sql: """
            DELETE FROM message_bodies WHERE messageId IN (
              SELECT id FROM messages
              WHERE conversationId = ? AND conversationType = ?
              ORDER BY timestamp ASC, id ASC LIMIT ?
            )
            """, arguments: [conversationId, type.rawValue, count])

            try db.execute(sql: """
            DELETE FROM messages WHERE id IN (
              SELECT id FROM messages
              WHERE conversationId = ? AND conversationType = ?
              ORDER BY timestamp ASC, id ASC LIMIT ?
            )
            """, arguments: [conversationId, type.rawValue, count])
            return db.changesCount
        }
    }

    // MARK: - 归档

    /// 把会话裁剪到只保留最新的 `retainCount` 条热消息，更旧的移入归档。
    ///
    /// 对应 Android `MessageDao.archiveOldMessages(conversationId, type, retainCount)`。
    ///
    /// ## ⚠️ 语义要点：这是「裁到 N 条」，不是「移走最旧的 N 条」
    /// Android 的实现分三步（本方法逐字对应）：
    ///   1. **求边界**：取最新 `retainCount` 条，再取其中最旧的一条，得到 `(timestamp, id)`
    ///   2. 把**严格早于边界**的全部复制到归档（元数据 + 正文）
    ///   3. 把严格早于边界的从热表删除
    ///
    /// 两种语义在「消息数远大于 retainCount」时结果相同，但在**消息数少于阈值**时
    /// 差别是灾难性的：`ORDER BY ... LIMIT retainCount` 的写法会把不足阈值的会话
    /// **整段归档清空**，而正确的边界法此时返回 0（无需归档）。
    ///
    /// 另外边界法天然幂等：再跑一次时边界之外已无行，`changesCount` 为 0。
    ///
    /// ## 正文的删除靠外键级联
    /// 第 3 步只删 `messages`；`message_bodies` 通过
    /// `ON DELETE CASCADE`（schema v45 的 2 个外键之一）连带删除。
    /// 因此**必须确保 `PRAGMA foreign_keys = ON`** —— `YuNianDatabase` 已显式开启。
    ///
    /// ## 归档不触碰 FTS
    /// FTS 的 `rowid` 就是 `messageId`，行在热/冷表之间移动后索引依旧有效。
    @discardableResult
    func archiveOldMessages(
        conversationId: Int64,
        type: ConversationType,
        retainCount: Int
    ) throws -> Int {
        guard retainCount >= 0 else {
            throw RepositoryError.invalidRetainCount(retainCount)
        }

        return try database.pool.write { db in
            // ① 求边界。retainCount == 0 表示全部归档（边界为 NULL）。
            let boundaryTimestamp: Int64?
            let boundaryId: Int64?

            if retainCount == 0 {
                boundaryTimestamp = nil
                boundaryId = nil
            } else {
                guard let row = try Row.fetchOne(db, sql: """
                SELECT timestamp, id FROM messages
                WHERE id IN (
                    SELECT id FROM messages
                    WHERE conversationId = ? AND conversationType = ?
                    ORDER BY timestamp DESC, id DESC LIMIT ?
                )
                ORDER BY timestamp ASC, id ASC LIMIT 1
                """, arguments: [conversationId, type.rawValue, retainCount]) else {
                    // 消息总数 <= retainCount，无需归档
                    return 0
                }
                boundaryTimestamp = row["timestamp"]
                boundaryId = row["id"]
            }

            // 边界条件：严格早于 (boundaryTimestamp, boundaryId)；边界为 NULL 时表示全部
            let boundaryClause = """
            (? IS NULL OR timestamp < ? OR (timestamp = ? AND id < ?))
            """
            let boundaryArgs: StatementArguments = [
                boundaryTimestamp, boundaryTimestamp, boundaryTimestamp, boundaryId,
            ]

            // ② 复制元数据到归档
            try db.execute(sql: """
            INSERT OR REPLACE INTO archived_messages
            SELECT * FROM messages
            WHERE conversationId = ? AND conversationType = ? AND \(boundaryClause)
            """, arguments: [conversationId, type.rawValue] + boundaryArgs)

            // ③ 复制正文到归档（显式列，不用 SELECT * —— 两表列顺序相同但显式更稳）
            try db.execute(sql: """
            INSERT OR REPLACE INTO archived_message_bodies
              (messageId, content, searchContent, linkString)
            SELECT messageId, content, searchContent, linkString FROM message_bodies
            WHERE messageId IN (
                SELECT id FROM messages
                WHERE conversationId = ? AND conversationType = ? AND \(boundaryClause)
            )
            """, arguments: [conversationId, type.rawValue] + boundaryArgs)

            // ④ 从热表删除元数据（正文由外键级联删除）
            try db.execute(sql: """
            DELETE FROM messages WHERE id IN (
                SELECT id FROM messages
                WHERE conversationId = ? AND conversationType = ? AND \(boundaryClause)
            )
            """, arguments: [conversationId, type.rawValue] + boundaryArgs)

            return db.changesCount
        }
    }

    // MARK: - 会话枚举

    /// 列出某类型下所有出现过消息的会话 id。
    /// 对应 Android `MessageDao.getDistinctConversationIds(type)`。
    ///
    /// ⚠️ 只看 `messages`（热表）—— 归档表不参与，与 Android 一致。
    /// 若某会话的消息已被全部归档，它不会出现在这里，也就不会再被维护任务处理。
    func distinctConversationIds(type: ConversationType) throws -> [Int64] {
        try database.pool.read { db in
            try Int64.fetchAll(
                db,
                sql: "SELECT DISTINCT conversationId FROM messages WHERE conversationType = ?",
                arguments: [type.rawValue]
            )
        }
    }

    /// 会话内当前的热消息条数（诊断 / 测试用）。
    func hotMessageCount(conversationId: Int64, type: ConversationType) throws -> Int {
        try database.pool.read { db in
            try Int.fetchOne(
                db,
                sql: """
                SELECT COUNT(*) FROM messages
                WHERE conversationId = ? AND conversationType = ?
                """,
                arguments: [conversationId, type.rawValue]
            ) ?? 0
        }
    }

    // MARK: - 检索

    /// 热数据全文检索。查询串经 `MessageSearchTokenizer.matchQuery` 转换。
    ///
    /// SQL 与 Android `MessageDao.searchMessagesByMatch` 逐字对应 ——
    /// 注意 MATCH 位于 `EXISTS` 子查询内部，`messageId` 属于 `message_bodies` 的作用域。
    func searchHot(
        conversationId: Int64,
        type: ConversationType,
        query: String,
        limit: Int = 50
    ) throws -> [StoredMessage] {
        guard let match = MessageSearchTokenizer.matchQuery(query) else { return [] }
        return try database.pool.read { db in
            try Row.fetchAll(db, sql: """
            SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp,
                   type, (SELECT searchContent FROM message_bodies WHERE messageId = messages.id) AS searchContent
            FROM messages
            WHERE conversationId = ? AND conversationType = ?
              AND EXISTS (
                SELECT 1 FROM message_bodies
                WHERE messageId = messages.id
                  AND messageId IN (
                    SELECT rowid FROM message_search_index
                    WHERE message_search_index MATCH ?
                  )
              )
            ORDER BY timestamp DESC, id DESC LIMIT ?
            """, arguments: [conversationId, type.rawValue, match, limit])
                .map(Self.storedMessage(from:))
        }
    }

    /// 归档数据全文检索。对应 Android `searchArchivedMessageMetadataByMatch`。
    func searchArchived(
        conversationId: Int64,
        type: ConversationType,
        query: String,
        limit: Int = 50
    ) throws -> [StoredMessage] {
        guard let match = MessageSearchTokenizer.matchQuery(query) else { return [] }
        return try database.pool.read { db in
            try Row.fetchAll(db, sql: """
            SELECT id, conversationId, conversationType, isFromUser, senderId, timestamp,
                   type, (SELECT searchContent FROM archived_message_bodies WHERE messageId = archived_messages.id) AS searchContent
            FROM archived_messages
            WHERE conversationId = ? AND conversationType = ?
              AND EXISTS (
                SELECT 1 FROM archived_message_bodies
                WHERE messageId = archived_messages.id
                  AND messageId IN (
                    SELECT rowid FROM message_search_index
                    WHERE message_search_index MATCH ?
                  )
              )
            ORDER BY timestamp DESC, id DESC LIMIT ?
            """, arguments: [conversationId, type.rawValue, match, limit])
                .map(Self.storedMessage(from:))
        }
    }

    /// 热 + 归档一起检索，按时间倒序合并。
    func search(
        conversationId: Int64,
        type: ConversationType,
        query: String,
        limit: Int = 50
    ) throws -> [StoredMessage] {
        let hot = try searchHot(conversationId: conversationId, type: type, query: query, limit: limit)
        let cold = try searchArchived(conversationId: conversationId, type: type, query: query, limit: limit)
        return (hot + cold)
            .sorted { ($0.timestamp, $0.id) > ($1.timestamp, $1.id) }
            .prefix(limit)
            .map { $0 }
    }

    // MARK: - 内部

    /// 写入 FTS 索引。`rowid` 必须显式等于 `messageId`
    /// （Android 侧靠 `MessageSearchIndex` 的 `@PrimaryKey rowid` 保证）。
    private func upsertSearchIndex(_ db: Database, messageId: Int64, searchContent: String) throws {
        let tokens = MessageSearchTokenizer.indexTokens(searchContent)
        try db.execute(
            sql: "INSERT OR REPLACE INTO message_search_index(rowid, tokens) VALUES (?, ?)",
            arguments: [messageId, tokens]
        )
    }

    private func removeSearchIndex(_ db: Database, messageId: Int64) throws {
        try db.execute(
            sql: "DELETE FROM message_search_index WHERE rowid = ?",
            arguments: [messageId]
        )
    }

    private static func storedMessage(from row: Row) -> StoredMessage {
        StoredMessage(
            id: row["id"] as Int64? ?? 0,
            conversationId: row["conversationId"] as Int64? ?? 0,
            conversationType: row["conversationType"] as String? ?? "",
            isFromUser: (row["isFromUser"] as Int? ?? 0) != 0,
            senderId: row["senderId"] as Int64? ?? 0,
            timestamp: row["timestamp"] as Int64? ?? 0,
            type: row["type"] as String? ?? "TEXT",
            content: row["content"] as String? ?? "",
            searchContent: row["searchContent"] as String? ?? ""
        )
    }
}
