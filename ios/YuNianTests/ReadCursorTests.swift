import XCTest

/// 已读游标与未读计数。
///
/// 权威来源：`MessageDao.kt:419`（未读 SQL）+
/// `ConversationSummaryDao.kt:43-44`（markReadThroughLatest）+ 其测试。
///
/// ⚠️ 重点测**同一毫秒内多条消息**的边界 —— Kotlin 用
/// `(timestamp, id)` 二元组比较而不是单纯比时间，原因就在这。
@testable import YuNian
final class ReadCursorTests: XCTestCase {

    private var db: YuNianDatabase!
    private var repo: ConversationRepository!
    private var tmpDir: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        // 与仓内其它测试同一模式：临时目录 + YuNianDatabase(databaseURL:)
        tmpDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("readcursor_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tmpDir, withIntermediateDirectories: true)
        db = try YuNianDatabase(databaseURL: tmpDir.appendingPathComponent("yunian_database"))
        repo = ConversationRepository(database: db)

        // 造一个伴侣 + 若干消息
        _ = try db.pool.write { d in
            try d.execute(sql: """
                INSERT INTO companions (id, name, personality, intimacy, lorebookIdsJson,
                                        createdAt, updatedAt)
                VALUES (1, '小雨', '温柔', 0, '[]', 0, 0)
                """)
        }
    }

    override func tearDownWithError() throws {
        if let d = tmpDir { try? FileManager.default.removeItem(at: d) }
        db = nil; repo = nil
        try super.tearDownWithError()
    }

    // MARK: - 辅助

    /// 插一条 AI 消息，返回 (timestamp, messageId)。
    @discardableResult
    private func insertAI(_ ts: Int64, type: String = "text") -> (ts: Int64, id: Int64) {
        let mid: Int64 = try! db.pool.write { d in
            try d.execute(sql: """
                INSERT INTO messages (conversationId, conversationType, isFromUser,
                                      senderId, timestamp, type, fileFormat)
                VALUES (1, 'chat', 0, 0, ?, ?, 'TEXT')
                """, arguments: [ts, type])
            let id = d.lastInsertedRowID
            try d.execute(sql: """
                INSERT OR REPLACE INTO message_bodies (messageId, content, searchContent, linkString)
                VALUES (?, ?, ?, '')
                """, arguments: [id, "msg-\(ts)-\(id)", "msg-\(ts)-\(id)"])
            return id
        }
        return (ts, mid)
    }

    // MARK: - 无游标

    /// 从未读过 → 全部 AI 消息都算未读（Kotlin 的 `IS NULL` 分支）
    func testNoCursorCountsAllAiMessages() throws {
        _ = insertAI(1000)
        _ = insertAI(2000)
        XCTAssertEqual(try repo.unreadCount(companionId: 1), 2)
    }

    /// 用户消息不计入未读（`isFromUser = 0`）
    func testUserMessagesNotCounted() throws {
        _ = try db.pool.write { d in
            try d.execute(sql: """
                INSERT INTO messages (conversationId, conversationType, isFromUser,
                                      senderId, timestamp, type, fileFormat)
                VALUES (1, 'chat', 1, 0, 1000, 'text', 'TEXT')
                """)
        }
        _ = insertAI(1000)
        XCTAssertEqual(try repo.unreadCount(companionId: 1), 1)
    }

    /// TOOL_ACTIVITY / REASONING 不计入
    func testToolAndReasoningExcluded() throws {
        _ = insertAI(1000)
        _ = insertAI(2000, type: "TOOL_ACTIVITY")
        _ = insertAI(3000, type: "REASONING")
        XCTAssertEqual(try repo.unreadCount(companionId: 1), 1)
    }

    // MARK: - markReadThroughLatest

    /// 标记已读后未读归零
    func testMarkReadThroughLatestClearsUnread() throws {
        _ = insertAI(1000)
        _ = insertAI(2000)
        XCTAssertEqual(try repo.unreadCount(companionId: 1), 2)
        XCTAssertTrue(try repo.markReadThroughLatest(companionId: 1))
        XCTAssertEqual(try repo.unreadCount(companionId: 1), 0)
    }

    /// ⚠️ **核心边界**：同一毫秒内的多条消息。
    ///
    /// Kotlin 用 `timestamp = :ts AND id > :id` 处理这个 —— 只比时间的话，
    /// 同刻的后到消息会被误判成"已读"（因为它们 timestamp 不大于游标）。
    func testSameTimestampBoundaryUsesId() throws {
        let a = insertAI(5000)          // 同刻第一条
        let b = insertAI(5000)          // 同刻第二条
        XCTAssertLessThan(a.id, b.id)

        // 只读到 a 的位置：b 应仍是未读
        try db.pool.write { d in
            try d.execute(sql: """
                INSERT INTO conversation_summary
                  (sessionId, sessionType, lastMessageId, lastMessagePreview,
                   lastMessageTimestamp, lastMessageIsFromUser,
                   readThroughMessageTimestamp, readThroughMessageId,
                   unreadCount, isPinned, isMuted)
                VALUES (1, 'chat', ?, '', ?, 0, ?, ?, 0, 0, 0)
                """, arguments: [a.id, a.ts, a.ts, a.id])
        }
        XCTAssertEqual(try repo.unreadCount(companionId: 1), 1,
                       "同一毫秒内 id 更大的 b 必须仍是未读")

        // 推到 b 之后：归零
        XCTAssertTrue(try repo.markReadThroughLatest(companionId: 1))
        XCTAssertEqual(try repo.unreadCount(companionId: 1), 0)
    }

    /// 推到最新后，更新的消息重新变成未读
    func testNewMessageAfterReadBecomesUnread() throws {
        _ = insertAI(1000)
        XCTAssertTrue(try repo.markReadThroughLatest(companionId: 1))
        XCTAssertEqual(try repo.unreadCount(companionId: 1), 0)

        _ = insertAI(2000)      // 新消息
        XCTAssertEqual(try repo.unreadCount(companionId: 1), 1)
    }

    /// TOOL_ACTIVITY 是最新时不推进游标
    /// （Kotlin rebuildSummaryForChat:427 的"最新是卡片则维持原状"）
    func testToolActivityLatestDoesNotAdvanceCursor() throws {
        _ = insertAI(1000)
        XCTAssertTrue(try repo.markReadThroughLatest(companionId: 1))

        // 来一条更新的 TOOL_ACTIVITY
        _ = insertAI(2000, type: "TOOL_ACTIVITY")
        XCTAssertTrue(try repo.markReadThroughLatest(companionId: 1))

        // 再来一条普通消息，应只有它未读（TOOL_ACTIVITY 仍不计）
        _ = insertAI(3000)
        XCTAssertEqual(try repo.unreadCount(companionId: 1), 1)
    }

    /// 没有任何消息时 markRead 返回 false（无可推）
    func testMarkReadWithNoMessagesReturnsFalse() throws {
        XCTAssertFalse(try repo.markReadThroughLatest(companionId: 1))
        // 仍能正常数未读
        XCTAssertEqual(try repo.unreadCount(companionId: 1), 0)
    }

    // MARK: - 群聊（第 175 轮）

    /// `chat_groups` 表**有写入方**（BackupImporter.swift:143），
    /// 所以备份恢复后群聊应出现在列表里。
    ///
    /// ⚠️ 这条测试是对我自己第 170 轮错误注释的反证：
    /// 我当时写"无写入方"，据此省掉了整个群聊区。
    func testGroupRowsFromBackupImport() throws {
        try db.pool.write { d in
            try d.execute(sql: """
                INSERT INTO chat_groups (id, name, avatarUrl, companionIds, createdAt, updatedAt)
                VALUES (7, '周末聚会', NULL, '[1,2,3]', 0, 0)
                """)
            try d.execute(sql: """
                INSERT INTO chat_groups (id, name, avatarUrl, companionIds, createdAt, updatedAt)
                VALUES (9, '二人世界', NULL, '[]', 0, 0)
                """)
        }
        let rows = try repo.groupRows()
        XCTAssertEqual(rows.count, 2)
        // 副标题 = member count（Kotlin "${size} 人"）
        XCTAssertEqual(rows.first(where: { $0.groupId == 7 })?.memberLine, "3 人")
        XCTAssertEqual(rows.first(where: { $0.groupId == 9 })?.memberLine, "0 人")

        // ⚠️ 第 191 轮：删掉了这里原本的
        //     XCTAssertEqual(try repo.groupCount(), 2)
        //
        // `groupCount()` 是一个独立的 `SELECT COUNT(*) FROM chat_groups`
        // 查询，而它要数的东西 `groupRows().count` 已经有了 ——
        // 同一个数字的两种来源，正是本会话反复批评的"两处各自维护"。
        // 界面上用的是 `groups.count`，那个查询从未被调用。
        //
        // 所以删的是**冗余实现**，不是覆盖：
        // "备份导入的群聊会出现且人数正确" 由上面两条断言保证。
        //
        // 附：我当初误删它的过程本身值得记 ——
        //     `find_dead_swift` 的输出被我 `Select-Object -Last 7` 截断，
        //     没看到分组标题，于是把"仅测试引用"误读成"完全未引用"。
        //     **截断输出会改变结论，不只是少看点东西。**
    }

    /// `companionIds` 是坏 JSON 时不当崩，memberCount 记 0。
    func testGroupRowsTolerateBadJson() throws {
        try db.pool.write { d in
            try d.execute(sql: """
                INSERT INTO chat_groups (id, name, avatarUrl, companionIds, createdAt, updatedAt)
                VALUES (1, '坏数据', NULL, 'not-json', 0, 0)
                """)
        }
        let rows = try repo.groupRows()
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].memberCount, 0)
    }

    /// 群聊未读**不参与**单聊的未读计数（conversationType 隔离）。
    func testGroupMessagesDoNotAffectChatUnread() throws {
        _ = try db.pool.write { d in
            try d.execute(sql: """
                INSERT INTO messages (conversationId, conversationType, isFromUser,
                                      senderId, timestamp, type, fileFormat)
                VALUES (1, 'group', 0, 0, 1000, 'text', 'TEXT')
                """)
        }
        // companionId=1 是 chat 类型，不该数到 group 的消息
        XCTAssertEqual(try repo.unreadCount(companionId: 1), 0)
    }
}
