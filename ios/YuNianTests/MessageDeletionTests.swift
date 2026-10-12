import GRDB
import XCTest

/// 删除单条消息（第 221 轮，用户反馈"补一个删除句子"）。
///
/// ## 为什么这一套要单独写
/// "删一条"看起来是最简单的操作，但它有两条**看不见的契约**：
/// 1. **FTS 必须先删**（`message_search_index` 的维护依赖 messages 行还在）；
///    顺序错了不会报错，只会留下搜得到、点不开的幽灵结果；
/// 2. 删完之后**首页那条会话的"共 N 条"要跟着变** —— 两处口径必须同源，
///    否则用户会觉得"删了但列表说还有 5 条"。
@testable import YuNian
final class MessageDeletionTests: XCTestCase {

    private func makeEnv() throws -> (MessageRepository, ConversationRepository,
                                      CompanionRepository, YuNianDatabase, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("msgdel_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let database = try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database"))
        return (MessageRepository(database: database),
                ConversationRepository(database: database),
                CompanionRepository(database: database),
                database, dir)
    }

    @discardableResult
    private func insert(_ repo: MessageRepository, companion: Int64, text: String,
                        fromUser: Bool = false) throws -> Int64 {
        try repo.insert(conversationId: companion, conversationType: .chat,
                        isFromUser: fromUser, senderId: fromUser ? 0 : companion,
                        type: "text", content: text, searchContent: text)
    }

    // MARK: - 只删这一条

    func testDeleteOneMessageLeavesTheRest() throws {
        let (messages, _, companions, _, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "阿念", personality: "温柔")
        try insert(messages, companion: id, text: "第一句", fromUser: true)
        let middle = try insert(messages, companion: id, text: "第二句")
        try insert(messages, companion: id, text: "第三句")

        try messages.delete(messageId: middle)

        let texts = try messages.history(conversationId: id, type: .chat).map(\.content)
        XCTAssertEqual(texts, ["第一句", "第三句"], "只该少掉中间那一条")
    }

    /// 删完首页那条会话的「共 N 条」必须跟着变 —— 与列表同一口径。
    func testDeleteUpdatesConversationListCount() throws {
        let (messages, conversations, companions, _, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "阿念", personality: "温柔")
        for text in ["一", "二", "三"] {
            try insert(messages, companion: id, text: text, fromUser: true)
        }
        XCTAssertEqual(try conversations.rows().first?.messageCount, 3)

        // ⚠️ 注意可选解包层级：`Array.last` 本身是可选，
        // `XCTUnwrap` 要包住**元素**而不是数组（CI 上就是这里报的
        // "value of optional type 'HistoryRow?' must be unwrapped"）。
        let last = try XCTUnwrap(try messages.history(conversationId: id, type: .chat).last)
        try messages.delete(messageId: last.messageId)

        let row = try XCTUnwrap(try conversations.rows().first)
        XCTAssertEqual(row.messageCount, 2, "删掉一条后列表数字要跟着变")
        XCTAssertEqual(row.lastMessage, "二", "最后一条消息也要回退到新的最后一条")
    }

    /// **FTS 行必须一起消失** —— 否则会出现"搜得到、点不开"的幽灵结果。
    /// 这条断言特意直接查 `message_search_index`：走 `search()` 只能证明"搜不到"，
    /// 证明不了那一行确实被清了。
    func testSearchIndexRowIsRemoved() throws {
        let (messages, _, companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "阿念", personality: "温柔")
        let target = try insert(messages, companion: id, text: "咖啡因过敏", fromUser: true)

        let before = try database.pool.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM message_search_index WHERE rowid = ?",
                             arguments: [target]) ?? -1
        }
        XCTAssertEqual(before, 1, "插入后应有一条 FTS 行")

        try messages.delete(messageId: target)

        let after = try database.pool.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM message_search_index WHERE rowid = ?",
                             arguments: [target]) ?? -1
        }
        XCTAssertEqual(after, 0, "删除必须连 FTS 行一起清（顺序错了会留幽灵结果）")

        // 正文行同样要清掉，否则库会越用越胖
        let bodies = try database.pool.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM message_bodies WHERE messageId = ?",
                             arguments: [target]) ?? -1
        }
        XCTAssertEqual(bodies, 0)
    }

    // MARK: - 边界

    func testDeleteMissingIdIsHarmless() throws {
        let (messages, _, companions, _, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "阿念", personality: "温柔")
        try insert(messages, companion: id, text: "留着", fromUser: true)

        XCTAssertNoThrow(try messages.delete(messageId: 999_999))
        XCTAssertEqual(try messages.history(conversationId: id, type: .chat).count, 1,
                       "删不存在的 id 不该影响别的消息")
    }

    /// `HistoryRow.messageId` 就是库行 id —— 界面靠它认"这条能不能删"。
    func testHistoryRowCarriesTheDatabaseId() throws {
        let (messages, _, companions, _, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "阿念", personality: "温柔")
        let inserted = try insert(messages, companion: id, text: "给我个 id", fromUser: true)

        let row = try XCTUnwrap(try messages.history(conversationId: id, type: .chat).first)
        XCTAssertEqual(row.messageId, inserted)
    }
}
