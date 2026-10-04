import XCTest
@testable import YuNian

/// `WorldbookRuntimeSync` 测试。
///
/// 重点锁定合成流水线里「不报错、只悄悄产出不同内容」的四个变换：
/// priority 回落、去重并列、排序、insertion_order 重编号。
final class WorldbookRuntimeSyncTests: XCTestCase {

    private var dir: URL!
    private var database: YuNianDatabase!

    override func setUpWithError() throws {
        try super.setUpWithError()
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("worldbook-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        database = try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database"))
    }

    override func tearDownWithError() throws {
        if let dir { try? FileManager.default.removeItem(at: dir) }
        database = nil
        try super.tearDownWithError()
    }

    // MARK: - 夹具辅助

    private func insertBook(
        name: String, json: String, companionId: Int64? = nil, enabled: Bool = true
    ) throws -> Int64 {
        try database.pool.write { db in
            try db.execute(sql: """
                INSERT INTO worldbooks (name, json, enabled, companionId, updatedAt)
                VALUES (?, ?, ?, ?, 0)
                """, arguments: [name, json, enabled ? 1 : 0, companionId])
            return db.lastInsertedRowID
        }
    }

    private func insertCompanion(name: String, lorebookIds: String = "[]") throws -> Int64 {
        try database.pool.write { db in
            try db.execute(sql: """
                INSERT INTO companions
                  (name, personality, intimacy, lorebookIdsJson, createdAt, updatedAt)
                VALUES (?, 'p', 0, ?, 0, 0)
                """, arguments: [name, lorebookIds])
            return db.lastInsertedRowID
        }
    }

    /// 取合成结果里的 entries 数组（断言用）。
    private func entries(_ synth: String) throws -> [[String: Any]] {
        let data = try XCTUnwrap(synth.data(using: .utf8))
        let root = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
        return try XCTUnwrap(root["entries"] as? [[String: Any]])
    }

    // MARK: - ① priority 回落

    /// 没有 `extensions._priority` 时，priority 必须是 `ORDER_BASE − insertion_order`
    /// （= 2^32 − insertion_order），而不是 0。漏这条会让所有条目优先级相同 →
    /// 去重退化为"保留后者" → 注入内容集合不同。
    func testPriorityFallsBackToOrderBaseMinusInsertionOrder() throws {
        // insertion_order = 4294967196 → priority = 100
        try insertBook(name: "b", json: """
            {"entries": {"e1": {"id": 7, "content": "甲", "keys": ["a"],
                                "insertion_order": 4294967196, "enabled": true,
                                "position": "before_char", "role": "system"}}}
            """)
        let synth = WorldbookRuntimeSync.synthForCompanion(database, companionId: 0)
        let arr = try entries(synth)
        XCTAssertEqual(arr.count, 1)
        let ext = try XCTUnwrap(arr[0]["extensions"] as? [String: Any])
        XCTAssertEqual(ext["_priority"] as? Int, 100,
                       "priority 应为 ORDER_BASE(2^32) − insertion_order")
        // insertion_order 被重编号为 1
        XCTAssertEqual(arr[0]["insertion_order"] as? Int64, 1)
    }

    // MARK: - ② 去重

    /// 同 content 且 priority 相等 → **后出现者胜**（Android 的 `>=` 语义）。
    func testDedupTieKeepsLaterBook() throws {
        try insertBook(name: "早书", json: """
            {"entries": [{"id": 1, "content": "同一句话", "keys": [],
                          "insertion_order": 1, "enabled": true,
                          "extensions": {"_priority": 5, "_bookName": "早书"}}]}
            """)
        try insertBook(name: "晚书", json: """
            {"entries": [{"id": 2, "content": "同一句话", "keys": [],
                          "insertion_order": 1, "enabled": true,
                          "extensions": {"_priority": 5, "_bookName": "晚书"}}]}
            """)
        let arr = try entries(WorldbookRuntimeSync.synthForCompanion(database, companionId: 0))
        XCTAssertEqual(arr.count, 1, "同 content 应去重为一条")
        let ext = try XCTUnwrap(arr[0]["extensions"] as? [String: Any])
        XCTAssertEqual(ext["_bookName"] as? String, "晚书",
                       "并列时应保留后出现的书（更晚的书覆盖早的）")
    }

    /// 同 content 但 priority 不同 → 高者胜（与出现顺序无关）。
    func testDedupKeepsHigherPriority() throws {
        try insertBook(name: "低优先级", json: """
            {"entries": [{"id": 1, "content": "同一句话", "keys": [],
                          "insertion_order": 1, "enabled": true,
                          "extensions": {"_priority": 1, "_bookName": "低优先级"}}]}
            """)
        try insertBook(name: "高优先级", json: """
            {"entries": [{"id": 2, "content": "同一句话", "keys": [],
                          "insertion_order": 1, "enabled": true,
                          "extensions": {"_priority": 99, "_bookName": "高优先级"}}]}
            """)
        let arr = try entries(WorldbookRuntimeSync.synthForCompanion(database, companionId: 0))
        let ext = try XCTUnwrap(arr[0]["extensions"] as? [String: Any])
        XCTAssertEqual(ext["_bookName"] as? String, "高优先级",
                       "优先级高者应胜出，即使它出现得更早")
    }

    // MARK: - ③ 排序与重编号

    /// 排序：priority 降序；其次 id 升序。重编号后 insertion_order 必须是 1..N。
    func testSortAndRenumber() throws {
        try insertBook(name: "b", json: """
            {"entries": [
              {"id": 30, "content": "低", "keys": [], "insertion_order": 1, "enabled": true,
               "extensions": {"_priority": 1}},
              {"id": 10, "content": "高一", "keys": [], "insertion_order": 2, "enabled": true,
               "extensions": {"_priority": 50}},
              {"id": 20, "content": "高二", "keys": [], "insertion_order": 3, "enabled": true,
               "extensions": {"_priority": 50}}
            ]}
            """)
        let arr = try entries(WorldbookRuntimeSync.synthForCompanion(database, companionId: 0))
        XCTAssertEqual(arr.map { $0["id"] as? Int64 }, [10, 20, 30],
                       "priority 降序，同 priority 时 id 升序")
        XCTAssertEqual(arr.map { $0["insertion_order"] as? Int64 }, [1, 2, 3],
                       "insertion_order 必须重编号为 1..N（Rust 稳定排序键须唯一）")
    }

    // MARK: - ④ 过滤

    /// 禁用条目与空白 content 的条目都必须跳过。
    func testDisabledAndBlankContentAreSkipped() throws {
        try insertBook(name: "b", json: """
            {"entries": [
              {"id": 1, "content": "保留", "keys": [], "insertion_order": 1, "enabled": true},
              {"id": 2, "content": "禁用", "keys": [], "insertion_order": 2, "enabled": false},
              {"id": 3, "content": "", "keys": [], "insertion_order": 3, "enabled": true}
            ]}
            """)
        let arr = try entries(WorldbookRuntimeSync.synthForCompanion(database, companionId: 0))
        XCTAssertEqual(arr.count, 1)
        XCTAssertEqual(arr[0]["content"] as? String, "保留")
    }

    /// `enabled` 必须**显式写 true** —— Rust 默认 true，省略会让禁用条目意外生效。
    func testEnabledIsAlwaysExplicitlyWritten() throws {
        try insertBook(name: "b", json: """
            {"entries": [{"id": 1, "content": "无 enabled 字段", "keys": [],
                          "insertion_order": 1}]}
            """)
        let arr = try entries(WorldbookRuntimeSync.synthForCompanion(database, companionId: 0))
        XCTAssertEqual(arr.count, 1)
        XCTAssertEqual(arr[0]["enabled"] as? Bool, true,
                       "即使源 JSON 没有 enabled，输出也必须显式带 true")
    }

    // MARK: - ⑤ 双形态

    /// `entries` 既可能是 array 也可能是 map；两者都要能解。
    func testEntriesAcceptsMapForm() throws {
        try insertBook(name: "b", json: """
            {"entries": {"自定义键名": {"id": 5, "content": "map 形态", "keys": [],
                                        "insertion_order": 1, "enabled": true}}}
            """)
        let arr = try entries(WorldbookRuntimeSync.synthForCompanion(database, companionId: 0))
        XCTAssertEqual(arr.count, 1)
        XCTAssertEqual(arr[0]["content"] as? String, "map 形态")
        // id 缺失/≤0 时回落到 map 键 —— 这里键不是数字，故回落为 0
        XCTAssertEqual(arr[0]["id"] as? Int64, 5, "有内层 id 时用内层 id")
    }

    func testEntriesAcceptsArrayForm() throws {
        try insertBook(name: "b", json: """
            {"entries": [{"id": 9, "content": "array 形态", "keys": [],
                          "insertion_order": 1, "enabled": true}]}
            """)
        let arr = try entries(WorldbookRuntimeSync.synthForCompanion(database, companionId: 0))
        XCTAssertEqual(arr.count, 1)
        XCTAssertEqual(arr[0]["content"] as? String, "array 形态")
    }

    // MARK: - ⑥ depth 只在源有时才写

    func testDepthOnlyWrittenWhenSourceHasIt() throws {
        try insertBook(name: "b", json: """
            {"entries": [
              {"id": 1, "content": "有 depth", "keys": [], "insertion_order": 1,
               "enabled": true, "depth": 7},
              {"id": 2, "content": "无 depth", "keys": [], "insertion_order": 2, "enabled": true}
            ]}
            """)
        let arr = try entries(WorldbookRuntimeSync.synthForCompanion(database, companionId: 0))
        XCTAssertNotNil(arr[0]["depth"], "源 JSON 有 depth → 输出应带")
        XCTAssertNil(arr[1]["depth"], "源 JSON 无 depth → 输出不应带（等价 Android 的 AT_DEPTH 条件）")
    }

    // MARK: - ⑦ 顶层与空态

    func testTopLevelFields() throws {
        try insertBook(name: "我的书", json: """
            {"entries": [{"id": 1, "content": "甲", "keys": [], "insertion_order": 1,
                          "enabled": true, "scan_depth": 10}]}
            """)
        let data = try XCTUnwrap(
            WorldbookRuntimeSync.synthForCompanion(database, companionId: 0).data(using: .utf8))
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(root["name"] as? String, "我的书")
        XCTAssertEqual(root["token_budget"] as? Int, 10_000)
        XCTAssertEqual(root["scan_depth"] as? Int64, 10, "scan_depth 取条目众数")
    }

    /// 没有生效条目时返回空串 —— Android 同样返回 ""（调用方据此不注入）。
    func testEmptyResultWhenNoEntries() throws {
        try insertBook(name: "空书", json: """
            {"entries": [{"id": 1, "content": "禁用的", "keys": [],
                          "insertion_order": 1, "enabled": false}]}
            """)
        XCTAssertEqual(WorldbookRuntimeSync.synthForCompanion(database, companionId: 0), "")
    }

    // MARK: - ⑧ 绑定规则

    /// 未绑定：专属书（companionId 匹配）∪ 全部全局书（companionId 为 null）。
    func testUnboundCompanionGetsDedicatedPlusGlobalBooks() throws {
        try insertBook(name: "专属", json: """
            {"entries": [{"id": 1, "content": "专属内容", "keys": [],
                          "insertion_order": 1, "enabled": true}]}
            """, companionId: 42)
        try insertBook(name: "全局", json: """
            {"entries": [{"id": 2, "content": "全局内容", "keys": [],
                          "insertion_order": 1, "enabled": true}]}
            """, companionId: nil)
        try insertBook(name: "别人的专属", json: """
            {"entries": [{"id": 3, "content": "别人的", "keys": [],
                          "insertion_order": 1, "enabled": true}]}
            """, companionId: 99)
        _ = try insertCompanion(name: "小鱼", lorebookIds: "[]")

        let arr = try entries(WorldbookRuntimeSync.synthForCompanion(database, companionId: 42))
        let contents = Set(arr.compactMap { $0["content"] as? String })
        XCTAssertEqual(contents, ["专属内容", "全局内容"],
                       "未绑定 → 专属书 ∪ 全局书；别人的专属书不进来")
    }
}
