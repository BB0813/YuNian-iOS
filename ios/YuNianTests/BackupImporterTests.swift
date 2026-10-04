import XCTest
@testable import YuNian

/// `BackupImporter` 测试。
///
/// 夹具按 Android `BackupData.kt`（kotlinx.serialization）的字段形状构造。
/// 重点覆盖三条「极易做错且不报错」的语义：
///   ① 不保留 ID，按 name 重映射
///   ② `deviceId` 改写成导入设备 ID（与第 10 轮修的 `listMemories` 过滤直接相关）
///   ③ `anchorMessageId` 必须二阶段回填
final class BackupImporterTests: XCTestCase {

    private var dir: URL!
    private var database: YuNianDatabase!
    private var importer: BackupImporter!

    override func setUpWithError() throws {
        try super.setUpWithError()
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("backup-import-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        database = try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database"))
        importer = BackupImporter(database: database)
    }

    override func tearDownWithError() throws {
        if let dir { try? FileManager.default.removeItem(at: dir) }
        database = nil
        importer = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具

    /// 一个最小的合法备份：1 个伴侣 + 2 条消息（其中一条 anchor 指向另一条）。
    private func makeBackup(
        companionId: Int64 = 100,
        companionName: String = "小鱼",
        deviceId: String = "android-source-device",
        anchor: Bool = true
    ) -> String {
        let anchorMsgId: Int64 = 900
        let anchoredMsgId: Int64 = 901
        var messages: [[String: Any]] = [[
            "id": anchorMsgId, "companionId": companionId, "content": "第一条",
            "isFromUser": true, "timestamp": 1_000, "type": "TEXT",
            "searchContent": "", "fileFormat": "TEXT", "linkString": "",
        ]]
        var anchored: [String: Any] = [
            "id": anchoredMsgId, "companionId": companionId, "content": "第二条",
            "isFromUser": false, "timestamp": 2_000, "type": "TEXT",
            "searchContent": "", "fileFormat": "TEXT", "linkString": "",
        ]
        if anchor { anchored["anchorMessageId"] = anchorMsgId }
        messages.append(anchored)

        let obj: [String: Any] = [
            "version": 1, "exportedAt": 1_700_000_000_000, "appVersion": "test",
            "companions": [[
                "id": companionId, "name": companionName, "avatarUrl": nil, "age": 22,
                "personality": "温柔", "backstory": nil, "speakingStyle": nil,
                "tags": nil, "rawPrompt": nil, "systemPrompt": nil,
                "intimacy": 7, "apiConfigId": nil, "createdAt": 1_000, "updatedAt": 2_000,
            ]],
            "chatMessages": messages,
            "chatGroups": [],
            "groupMessages": [],
            "memoryEntries": [[
                "id": 500, "companionId": companionId, "content": "喜欢下雨天",
                "category": "FACT", "importance": 0.8, "context": "",
                "accessCount": 1, "timestamp": 1_000, "lastAccessed": 1_000,
                "deviceId": deviceId,
            ]],
            "tempMemories": [],
            "tokenUsages": [[
                "id": 600, "companionId": companionId, "date": "2026-08-07",
                "inputTokens": 10, "outputTokens": 20, "totalTokens": 30,
                "requestCount": 1, "timestamp": 1_000, "deviceId": deviceId,
            ]],
            "unifiedMemories": [[
                "id": 700, "memoryType": "SEMANTIC", "scope": "COMPANION",
                "source": "CHAT", "content": "用户怕打雷", "summary": "",
                "confidence": 1.0, "importance": 0.6, "sourceId": companionId,
                "createdAt": 1_000, "updatedAt": 1_000, "observedAt": 1_000,
                "expiresAt": nil, "accessCount": 1, "tags": "", "deviceId": deviceId,
            ]],
            "diaries": [[
                "id": 800, "companionId": companionId, "title": "雨天",
                "content": "一起去散步了", "mood": 3, "date": 1_000,
                "weather": "雨", "tags": "", "deviceId": deviceId,
            ]],
        ]
        return String(data: try! JSONSerialization.data(withJSONObject: obj), encoding: .utf8)!
    }

    // MARK: - ① ID 重映射

    /// 伴侣按 name 复用已有 ID；消息/记忆改指向新 ID。
    func testCompanionMatchedByNameAndChildrenRemapped() throws {
        // 本地先有一个同名伴侣（ID 1）
        let localId = try database.pool.write { db -> Int64 in
            try db.execute(sql: """
                INSERT INTO companions (name, personality, intimacy, lorebookIdsJson, createdAt, updatedAt)
                VALUES ('小鱼', '本地人设', 3, '[]', 0, 0)
                """)
            return db.lastInsertedRowID
        }

        let r = try importer.importBackup(makeBackup(companionId: 100))
        XCTAssertEqual(r.companionsMatched, 1, "应按名字命中已有伴侣")
        XCTAssertEqual(r.companionsCreated, 0)

        // 消息必须落到本地伴侣下，而不是快照里的 id=100
        let rows = try database.pool.read { db in
            try Row.fetchAll(db, sql: """
                SELECT conversationId FROM messages WHERE conversationType = 'chat'
                """)
        }
        XCTAssertEqual(rows.count, 2, "两条消息都应入库")
        for r in rows {
            XCTAssertEqual(r["conversationId"] as? Int64, localId, "消息应指向本地伴侣 ID")
        }
        XCTAssertEqual(r.chatMessagesInserted, 2)
    }

    /// 本地无同名伴侣 → 新建，并获得新 ID。
    func testCompanionCreatedWhenNameNotFound() throws {
        let r = try importer.importBackup(makeBackup(companionId: 100))
        XCTAssertEqual(r.companionsCreated, 1)
        XCTAssertEqual(r.companionsMatched, 0)

        let ids = try database.pool.read { db in
            try Int64.fetchAll(db, sql: "SELECT id FROM companions")
        }
        XCTAssertEqual(ids, [1], "新伴侣应得到自增 ID 1，而非快照里的 100")
    }

    // MARK: - ② deviceId 改写（最关键）

    /// 导入的记忆/token/日记/diary 的 deviceId 必须是**本机** ID，
    /// 不能是快照里的源设备 ID —— 否则 `listMemories` 的 deviceId 过滤会让它们召回不到。
    func testDeviceIdIsRewrittenToLocal() throws {
        let sourceDevice = "android-source-device"
        XCTAssertNotEqual(DeviceIdentity.deviceId, sourceDevice, "前提：两者必须不同")

        _ = try importer.importBackup(makeBackup(deviceId: sourceDevice))

        let tables = ["memory_entries", "token_usage", "diary_entries", "unified_memories"]
        for t in tables {
            let ids = try database.pool.read { db in
                try String.fetchAll(db, sql: "SELECT DISTINCT deviceId FROM \(t)")
            }
            XCTAssertEqual(ids, [DeviceIdentity.deviceId],
                           "\(t) 的 deviceId 应为本机 ID，实际 \(ids)")
        }
    }

    // MARK: - ③ anchorMessageId 二阶段

    /// 锚点必须在全部消息插入后才回填，且指向正确的新消息 ID。
    func testAnchorMessageIdFilledInSecondPass() throws {
        _ = try importer.importBackup(makeBackup(anchor: true))

        let rows = try database.pool.read { db in
            try Row.fetchAll(db, sql: """
                SELECT id, anchorMessageId FROM messages WHERE conversationType = 'chat'
                ORDER BY timestamp
                """)
        }
        let first = try XCTUnwrap(rows.first)
        let second = try XCTUnwrap(rows.last)
        let firstId = try XCTUnwrap(first["id"] as? Int64)
        let anchorValue = try XCTUnwrap(second["anchorMessageId"] as? Int64)

        XCTAssertEqual(anchorValue, firstId,
                       "第二条的锚点应指向第一条的**新** ID（而非快照里的 900）")
        XCTAssertNotEqual(anchorValue, 900)
    }

    /// 无锚点的备份不应产生 NULL 以外的异常值。
    func testNoAnchorLeavesNull() throws {
        _ = try importer.importBackup(makeBackup(anchor: false))
        let nulls = try database.pool.read { db in
            try Int.fetchOne(db, sql: """
                SELECT COUNT(*) FROM messages WHERE conversationType = 'chat'
                  AND anchorMessageId IS NULL
                """) ?? 0
        }
        XCTAssertEqual(nulls, 2, "两条都应无锚点")
    }

    // MARK: - 去重（可重复导入）

    /// 同一备份导入两次：伴侣不重复、消息不重复（按 timestamp+isFromUser+content）。
    func testReimportIsIdempotent() throws {
        _ = try importer.importBackup(makeBackup())
        let r2 = try importer.importBackup(makeBackup())

        XCTAssertEqual(r2.companionsCreated, 0, "第二次应命中已有伴侣")
        XCTAssertEqual(r2.chatMessagesInserted, 0, "第二次不应插入重复消息")

        let counts = try database.pool.read { db in
            (
                companions: try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM companions") ?? 0,
                messages: try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM messages") ?? 0
            )
        }
        XCTAssertEqual(counts.companions, 1)
        XCTAssertEqual(counts.messages, 2)
    }

    // MARK: - 群消息哨兵

    /// `companionId == -1` 是系统发送者哨兵，**不进 ID 映射**、必须原样保留。
    func testGroupMessageSystemSenderSentinel() throws {
        let obj: [String: Any] = [
            "version": 1, "exportedAt": 0, "appVersion": "test",
            "companions": [["id": 100, "name": "小鱼", "personality": "温柔",
                           "intimacy": 0, "createdAt": 0, "updatedAt": 0]],
            "chatMessages": [],
            "chatGroups": [["id": 200, "name": "群1", "avatarUrl": nil,
                            "companionIds": "100", "createdAt": 0, "updatedAt": 0]],
            "groupMessages": [[
                "id": 300, "groupId": 200, "companionId": -1, "content": "系统消息",
                "timestamp": 1_000, "searchContent": "", "fileFormat": "TEXT",
                "linkString": "",
            ]],
            "memoryEntries": [], "tempMemories": [], "tokenUsages": [],
            "unifiedMemories": [], "diaries": [],
        ]
        let json = String(data: try! JSONSerialization.data(withJSONObject: obj), encoding: .utf8)!
        let r = try importer.importBackup(json)
        XCTAssertEqual(r.groupMessagesInserted, 1)

        let senders = try database.pool.read { db in
            try Int64.fetchAll(db, sql: "SELECT DISTINCT senderId FROM messages WHERE conversationType = 'group'")
        }
        XCTAssertEqual(senders, [-1], "系统发送者哨兵 -1 必须原样保留")
    }

    // MARK: - 错误处理

    func testMalformedJSONThrows() {
        XCTAssertThrowsError(try importer.importBackup("not json"))
        XCTAssertThrowsError(try importer.importBackup("[1,2,3]"), "数组不是对象")
    }
}
