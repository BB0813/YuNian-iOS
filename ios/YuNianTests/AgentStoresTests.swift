import XCTest
@testable import YuNian

/// `AgentStores` 的 JSON 契约测试。
///
/// ## 为什么重要且此前缺失
/// 这些方法是 Rust 通过 foreign trait 回调的**唯一数据来源**：
/// 键名写错 → Rust 取不到 → 静默失效。而我此前只从 Rust 源码**逐键核对过**
/// （`verify_swift_conformance.py`），从没有在运行时验证过映射结果。
///
/// ## 为什么可测
/// `AgentStores` 的方法是普通 Swift 方法，**不需要 Rust 引擎** ——
/// 直接建库、插行、调用、断言 JSON 即可。
final class AgentStoresTests: XCTestCase {

    private var database: YuNianDatabase!
    private var stores: AgentStores!
    private var dir: URL!

    override func setUpWithError() throws {
        try super.setUp()
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("agent-stores-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        database = try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database"))
        stores = AgentStores(database: database)
    }

    override func tearDownWithError() throws {
        if let dir { try? FileManager.default.removeItem(at: dir) }
        database = nil
        stores = nil
        try super.tearDownWithError()
    }

    // MARK: - listMemories

    /// Rust `MemoryMeta` 期望的 snake_case 键集合（agent.rs 的 serde 字段）。
    func testListMemoriesProducesRustContractKeys() throws {
        try insertMemory(deviceId: DeviceIdentity.deviceId, content: "一条记忆")

        let json = try object(stores.listMemories(scopeJson: #"{"scope":null}"#))
        XCTAssertEqual(json.count, 1, "应返回 1 条（scope 为 null = 全部）")

        let item = try XCTUnwrap(json.first)
        XCTAssertEqual(
            Set(item.keys),
            ["id", "content", "memory_type", "scope", "source_id", "importance",
             "confidence", "access_count", "observed_at", "last_accessed_at",
             "expires_at", "tags"],
            "键集合必须与 Rust 的 MemoryMeta 一致"
        )
        XCTAssertEqual(item["content"] as? String, "一条记忆")
        XCTAssertEqual(item["memory_type"] as? String, "SEMANTIC")
        XCTAssertEqual(item["scope"] as? String, "COMPANION")
        XCTAssertEqual(item["source_id"] as? Int, 0)
        XCTAssertNotNil(item["expires_at"], "expires_at 必须存在（Android 用 NULL 占位）")
    }

    /// 召回过滤：别的设备 / 软删除 / 已过期都不该出现。
    /// 这三条写错都是静默错误（不报错，只是召回不该召回的记忆）。
    func testListMemoriesFiltersDeviceDeletedAndExpired() throws {
        try insertMemory(deviceId: "other-device", content: "别的设备")
        try insertMemory(deviceId: DeviceIdentity.deviceId, content: "软删除", isDeleted: true)
        try insertMemory(deviceId: DeviceIdentity.deviceId, content: "已过期",
                         expiresAt: Int64(Date().timeIntervalSince1970 * 1000) - 1000)
        try insertMemory(deviceId: DeviceIdentity.deviceId, content: "有效")

        let json = try object(stores.listMemories(scopeJson: #"{"scope":null}"#))
        let contents = json.compactMap { $0["content"] as? String }
        XCTAssertEqual(contents, ["有效"], "只应剩下有效记忆，实际：\(contents)")
    }

    /// `expires_at` 无值时为 null（Android 的 `JSONObject.NULL`），不是 0 也不是缺键。
    func testListMemoriesExpiresAtIsNullWhenAbsent() throws {
        try insertMemory(deviceId: DeviceIdentity.deviceId, content: "无过期")
        let json = try object(stores.listMemories(scopeJson: #"{"scope":null}"#))
        let item = try XCTUnwrap(json.first)
        XCTAssertTrue(item.keys.contains("expires_at"), "键必须存在")
        XCTAssertTrue(item["expires_at"] is NSNull, "无值时应为 null，实际 \(String(describing: item["expires_at"]))")
    }

    // MARK: - insertMemory

    /// 空白 content 必须返回 ""（Android `buildCredentialsJson` 式的放弃写入）。
    func testInsertMemoryRejectsBlankContent() {
        XCTAssertEqual(
            stores.insertMemory(metaJson: #"{"content":"   "}"#), "",
            "空白 content 应返回空串且不落库")
        XCTAssertEqual(
            stores.insertMemory(metaJson: #"{"content":""}"#), "",
            "空 content 应返回空串")
    }

    /// session / client_id 要么都写要么都不写 —— 复刻 Android 的 buildCredentialsJson。
    func testCredentialsSessionAndClientIdAreAllOrNothing() throws {
        // 通过 fromKeychain(isPartner:) 的等价逻辑：这里直接验 JSON 形态
        let onlySession = try object(AgentCredentials(session: "s").jsonString())
        XCTAssertNil(onlySession["session"])
        XCTAssertNil(onlySession["client_id"])

        let both = try object(AgentCredentials(session: "s", clientId: "c").jsonString())
        XCTAssertEqual(both["session"] as? String, "s")
        XCTAssertEqual(both["client_id"] as? String, "c")
    }

    /// `source` 由 scope 决定：GROUP → GROUP_CHAT，其它 → CHAT。
    func testInsertMemorySourceDerivesFromScope() throws {
        _ = stores.insertMemory(metaJson: """
            {"content":"群记忆","scope":"GROUP"}
            """)
        let rows = try rows(sql: "SELECT source FROM unified_memories")
        XCTAssertEqual(rows.compactMap { $0["source"] as? String }, ["GROUP_CHAT"],
                       "GROUP scope 应对应 GROUP_CHAT")
    }

    // MARK: - 辅助

    private func insertMemory(
        deviceId: String, content: String, isDeleted: Bool = false, expiresAt: Int64? = nil
    ) throws {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try database.pool.write { db in
            try db.execute(sql: """
                INSERT INTO unified_memories
                  (memoryType, scope, source, content, summary, confidence, importance,
                   sourceId, createdAt, updatedAt, lastAccessedAt, observedAt, expiresAt,
                   temporalAnchor, accessCount, tags, isDeleted, version, deviceId)
                VALUES ('SEMANTIC','COMPANION','CHAT',?,'',1.0,0.5,0,?,?,?,?,?,'',1,'',?,1,?)
                """, arguments: [
                    content, now, now, now, now, expiresAt, isDeleted ? 1 : 0, deviceId,
                ])
        }
    }

    private func rows(sql: String, arguments: StatementArguments = StatementArguments())
        throws -> [[String: Any]]
    {
        try database.pool.read { db in
            try Row.fetchAll(db, sql: sql, arguments: arguments).map { row in
                var out: [String: Any] = [:]
                for name in row.columnNames { out[name] = row[name] }
                return out
            }
        }
    }
}
