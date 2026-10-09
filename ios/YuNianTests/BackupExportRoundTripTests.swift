import XCTest
import GRDB

/// 备份**导出 → 导入往返**（第 197 轮）。
///
/// ## 为什么这是导出侧最重要的测试
/// 导出的正确性最终只能靠"Android 能导入"验证，而我无法跨端试。
/// 但 **iOS 的导入器本身就是那份格式的忠实消费者**
/// （字段清单即从它 `parse()` 反推，见 `BackupImporter` 头部规格），
/// 所以「导出 → 过一遍导入器 → 数据还在」是本地能拿到的最强证据。
///
/// ## 它专门能抓什么
/// - 分区名写错（如把 `tempMemories` 写成 `tempMemoriesList`）→ 该分区静默丢失
/// - 字段名写错 → 导入侧走默认值（**静默丢内容**，不报错）
/// - 单聊/群聊的 `companionId` 取值写反 → 消息挂到错误的伴侣下
/// - **表名与分区名不一致**那三处（`temp_memory` / `token_usage` / `diary_entries`）
///
/// ⚠️ 断言刻意**查目标库的实际行**，而不是只看 `Result` 的计数字段 ——
/// 计数字段可能因为"插进去了但值不对"而照样为 1。
@testable import YuNian
final class BackupExportRoundTripTests: XCTestCase {

    private var srcDir: URL!
    private var dstDir: URL!
    private var src: YuNianDatabase!
    private var dst: YuNianDatabase!

    override func setUpWithError() throws {
        try super.setUpWithError()
        let base = FileManager.default.temporaryDirectory
        srcDir = base.appendingPathComponent("bx_src_\(UUID().uuidString)", isDirectory: true)
        dstDir = base.appendingPathComponent("bx_dst_\(UUID().uuidString)", isDirectory: true)
        for d in [srcDir!, dstDir!] {
            try FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        }
        src = try YuNianDatabase(databaseURL: srcDir.appendingPathComponent("yunian_database"))
        dst = try YuNianDatabase(databaseURL: dstDir.appendingPathComponent("yunian_database"))
    }

    override func tearDownWithError() throws {
        for d in [srcDir, dstDir] { if let d { try? FileManager.default.removeItem(at: d) } }
        src = nil; dst = nil
        try super.tearDownWithError()
    }

    /// 往源库塞一份最小但**跨 5 个分区**的数据。
    private func seedSource() throws {
        try src.pool.write { db in
            try db.execute(sql: """
                INSERT INTO companions
                  (id, name, age, personality, tags, intimacy, lorebookIdsJson, createdAt, updatedAt)
                VALUES (1, '小念', 20, '温柔而倔强', '["日常"]', 42, '[]', 1000, 2000)
                """)
            try db.execute(sql: """
                INSERT INTO chat_groups (id, name, companionIds, createdAt, updatedAt)
                VALUES (7, '二人世界', '1', 1000, 2000)
                """)
            // 单聊消息（conversationId = companionId = 1）
            try db.execute(sql: """
                INSERT INTO messages
                  (id, conversationId, conversationType, isFromUser, senderId, timestamp,
                   type, fileFormat)
                VALUES (1, 1, 'chat', 1, 0, 1500, 'TEXT', 'TEXT')
                """)
            try db.execute(sql: """
                INSERT INTO message_bodies (messageId, content, searchContent, linkString)
                VALUES (1, '你好呀', '你好呀', '')
                """)
            // 群聊消息（conversationId = groupId = 7，senderId = companionId = 1）
            try db.execute(sql: """
                INSERT INTO messages
                  (id, conversationId, conversationType, isFromUser, senderId, timestamp,
                   type, fileFormat)
                VALUES (2, 7, 'group', 0, 1, 1600, 'TEXT', 'TEXT')
                """)
            try db.execute(sql: """
                INSERT INTO message_bodies (messageId, content, searchContent, linkString)
                VALUES (2, '群里的回复', '群里的回复', '')
                """)
            // 记忆（表名是 memory_entries，分区名是 memoryEntries）
            try db.execute(sql: """
                INSERT INTO memory_entries
                  (companionId, content, category, importance, context, accessCount,
                   timestamp, lastAccessed, deviceId)
                VALUES (1, '她喜欢下雨天', 'FACT', 0.8, '', 3, 1700, 1800, '')
                """)
            // 临时记忆（表名单数 temp_memory、分区名复数 tempMemories）
            try db.execute(sql: """
                INSERT INTO temp_memory (companionId, userInput, botResponse, timestamp, deviceId)
                VALUES (1, '在吗', '在的', 1900, '')
                """)
            // 日记（表名 diary_entries、分区名 diaries）
            try db.execute(sql: """
                INSERT INTO diary_entries
                  (companionId, title, content, mood, date, weather, tags, deviceId)
                VALUES (1, '雨', '今天下雨了', 2, 2000, '雨', '', '')
                """)
        }
    }

    /// 明文 JSON 必须含全部 9 个分区键（缺一个都意味着该分区会静默丢失）。
    func testPlaintextJSONHasAllNineSections() throws {
        try seedSource()
        let plain = try BackupExporter.makePlaintextJSON(database: src)
        let root = try XCTUnwrap(
            JSONSerialization.jsonObject(with: plain) as? [String: Any]
        )
        for key in BackupExporter.sectionKeys {
            XCTAssertNotNil(root[key], "缺分区键 \(key) —— 导入侧会静默拿到空数组")
        }
        XCTAssertEqual(root.count, 9, "顶层不应有第 10 个键（多余键导入侧不读，别误导）")
    }

    /// 导出后各分区计数正确 —— 尤其那三处「表名与分区名不一致」的。
    func testSectionCounts() throws {
        try seedSource()
        let (_, outcome) = try BackupExporter.exportFile(database: src, password: "pw")
        XCTAssertEqual(outcome.sectionCounts["companions"], 1)
        XCTAssertEqual(outcome.sectionCounts["chatGroups"], 1)
        XCTAssertEqual(outcome.sectionCounts["chatMessages"], 1, "单聊消息只应有 1 条")
        XCTAssertEqual(outcome.sectionCounts["groupMessages"], 1, "群聊消息只应有 1 条")
        XCTAssertEqual(outcome.sectionCounts["memoryEntries"], 1)
        XCTAssertEqual(outcome.sectionCounts["tempMemories"], 1, "temp_memory 表 → tempMemories 分区")
        XCTAssertEqual(outcome.sectionCounts["diaries"], 1, "diary_entries 表 → diaries 分区")
        XCTAssertEqual(outcome.sectionCounts["tokenUsages"], 0)
        XCTAssertEqual(outcome.sectionCounts["unifiedMemories"], 0)
        // ⚠️ 第 198 轮：这里原来写 6，实际 7 —— **是我数错了，不是导出器错**。
        // seed 覆盖 7 个分区各 1 条：companions / chatGroups / chatMessages /
        // groupMessages / memoryEntries / tempMemories / diaries。
        // （tokenUsages 与 unifiedMemories 为 0。）
        // CI 只报了这一条，上面 9 条分区计数断言全过 —— 说明导出正确、算术错。
        XCTAssertEqual(outcome.totalRecords, 7)
    }

    /// ⚠️ **核心**：导出 → 导入一个空库 → 数据仍在。
    func testExportThenImportPreservesData() throws {
        try seedSource()
        let (blob, _) = try BackupExporter.exportFile(database: src, password: "pw")

        // 容器必须能被导入路径解开（密码正确）
        let outcome = BackupImportService.importFile(data: blob, password: "pw", database: dst)
        XCTAssertTrue(outcome.ok, "导入失败：\(outcome.summary)")

        // 查目标库的**实际行**（不看计数字段）
        let names = try dst.pool.read { db in
            try String.fetchAll(db, sql: "SELECT name FROM companions ORDER BY id")
        }
        XCTAssertEqual(names, ["小念"], "伴侣名必须还原")

        let personalities = try dst.pool.read { db in
            try String.fetchAll(db, sql: "SELECT personality FROM companions")
        }
        XCTAssertEqual(personalities, ["温柔而倔强"], "非键字段也必须还原")

        let contents = try dst.pool.read { db in
            try String.fetchAll(db, sql: "SELECT content FROM message_bodies ORDER BY messageId")
        }
        XCTAssertEqual(contents.count, 2, "两条消息体都应还原")
        XCTAssertTrue(contents.contains("你好呀"), "单聊正文丢失 —— 可能是 companionId 字段名错")
        XCTAssertTrue(contents.contains("群里的回复"), "群聊正文丢失 —— 可能是 groupId 字段名错")

        let memories = try dst.pool.read { db in
            try String.fetchAll(db, sql: "SELECT content FROM memory_entries")
        }
        XCTAssertEqual(memories, ["她喜欢下雨天"], "记忆分区未还原")
    }

    /// 密码错 → 导入必须失败，而不是"导入了空数据"。
    func testWrongPasswordFailsImport() throws {
        try seedSource()
        let (blob, _) = try BackupExporter.exportFile(database: src, password: "right")
        let outcome = BackupImportService.importFile(data: blob, password: "wrong", database: dst)
        XCTAssertFalse(outcome.ok, "密码错却报告成功 —— 用户会以为备份恢复了")
    }

    /// 空库导出：9 个分区都在，都是空数组；容器仍合法。
    func testEmptyDatabaseExportsEmptySections() throws {
        let (blob, outcome) = try BackupExporter.exportFile(database: src, password: "pw")
        XCTAssertEqual(outcome.totalRecords, 0)
        for key in BackupExporter.sectionKeys {
            XCTAssertEqual(outcome.sectionCounts[key], 0, "\(key) 应为 0")
        }
        XCTAssertNoThrow(try BackupCrypto.parse(blob), "空库的容器也必须是合法 .lybk")
        XCTAssertTrue(BackupImportService.importFile(data: blob, password: "pw", database: dst).ok)
    }
}
