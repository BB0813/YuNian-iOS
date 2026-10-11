import GRDB
import XCTest

/// 记忆页：分桶判定 + 仓储读侧。
///
/// 分桶写错的表现是"界面说会注入、实际没注入" —— 比不显示更误导，
/// 所以判定规则必须与引擎逐条对齐（`memory_selector.rs:135/169`）。
@testable import YuNian
final class MemoryBrowseTests: XCTestCase {

    private func makeDatabase() throws -> (YuNianDatabase, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("memory_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return (try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database")), dir)
    }

    private func memory(id: Int64 = 1, content: String = "内容",
                        type: String = "SEMANTIC", importance: Double = 0.5,
                        observedAt: Int64 = 1_700_000_000_000,
                        expiresAt: Int64? = nil, isDeleted: Bool = false,
                        accessCount: Int = 1, scope: String = "COMPANION")
        -> MemoryRepository.Memory {
        MemoryRepository.Memory(id: id, content: content, memoryType: type, scope: scope,
                                source: "agent", sourceId: 1, importance: importance,
                                accessCount: accessCount, observedAt: observedAt,
                                expiresAt: expiresAt, isDeleted: isDeleted)
    }

    // MARK: - 分桶

    func testBucketMatchesEngineRules() {
        let now: Int64 = 1_700_000_100_000
        // WORKING 且有效 → 短期（每回合注入）
        XCTAssertEqual(MemoryView.bucket(of: memory(type: "WORKING"), now: now), .working)
        // 其它类型且有效 → 长期（按召回）
        XCTAssertEqual(MemoryView.bucket(of: memory(type: "SEMANTIC"), now: now), .longTerm)
        XCTAssertEqual(MemoryView.bucket(of: memory(type: "EPISODIC"), now: now), .longTerm)
        // 已软删除 → 不再注入（哪怕类型是 WORKING）
        XCTAssertEqual(MemoryView.bucket(of: memory(type: "WORKING", isDeleted: true), now: now), .inactive)
        // 已过期 → 不再注入（边界：等于 now 也算过期）
        XCTAssertEqual(MemoryView.bucket(of: memory(expiresAt: now - 1), now: now), .inactive)
        XCTAssertEqual(MemoryView.bucket(of: memory(expiresAt: now), now: now), .inactive)
        // 未过期 → 照常
        XCTAssertEqual(MemoryView.bucket(of: memory(expiresAt: now + 1), now: now), .longTerm)
    }

    func testCounts() {
        let now: Int64 = 1_700_000_100_000
        let counts = MemoryView.counts([
            memory(id: 1, type: "WORKING"),
            memory(id: 2, type: "SEMANTIC"),
            memory(id: 3, type: "EPISODIC"),
            memory(id: 4, type: "WORKING", isDeleted: true),
            memory(id: 5, type: "SEMANTIC", expiresAt: now - 1),
        ], now: now)
        XCTAssertEqual(counts.working, 1)
        XCTAssertEqual(counts.longTerm, 2)
        XCTAssertEqual(counts.inactive, 2)
    }

    // MARK: - 分组 / 过滤 / 排序

    func testGroupsOrderIsStableAndInactiveHiddenByDefault() {
        let now: Int64 = 1_700_000_100_000
        let items = [
            memory(id: 1, type: "SEMANTIC"),
            memory(id: 2, type: "WORKING"),
            memory(id: 3, type: "SEMANTIC", isDeleted: true),
        ]
        let groups = MemoryView.groups(items, query: "", showInactive: false, sort: .importance, now: now)
        // 顺序固定：短期 → 长期（不再注入默认隐藏）
        XCTAssertEqual(groups.map(\.bucket), [.working, .longTerm])

        let withInactive = MemoryView.groups(items, query: "", showInactive: true, sort: .importance, now: now)
        XCTAssertEqual(withInactive.map(\.bucket), [.working, .longTerm, .inactive])
    }

    func testQueryFiltersByContentCaseInsensitively() {
        let now: Int64 = 1_700_000_100_000
        let items = [
            memory(id: 1, content: "喜欢喝美式 Coffee"),
            memory(id: 2, content: "住在杭州"),
        ]
        let hit = MemoryView.groups(items, query: "coffee", showInactive: false, sort: .importance, now: now)
        XCTAssertEqual(hit.flatMap(\.items).map(\.id), [1])

        let all = MemoryView.groups(items, query: "   ", showInactive: false, sort: .importance, now: now)
        XCTAssertEqual(all.flatMap(\.items).count, 2, "空白查询 = 不过滤")

        let none = MemoryView.groups(items, query: "不存在的词", showInactive: false, sort: .importance, now: now)
        XCTAssertTrue(none.isEmpty, "没有命中时不该出现空分组")
    }

    func testSortByImportanceAndByRecent() {
        let now: Int64 = 1_700_000_100_000
        let items = [
            memory(id: 1, importance: 0.2, observedAt: 300),
            memory(id: 2, importance: 0.9, observedAt: 100),
            memory(id: 3, importance: 0.5, observedAt: 200),
        ]
        let byImportance = MemoryView.groups(items, query: "", showInactive: false,
                                             sort: .importance, now: now).flatMap(\.items).map(\.id)
        XCTAssertEqual(byImportance, [2, 3, 1])

        let byRecent = MemoryView.groups(items, query: "", showInactive: false,
                                         sort: .recent, now: now).flatMap(\.items).map(\.id)
        XCTAssertEqual(byRecent, [1, 3, 2])
    }

    /// 重要度相同时用时间兜底（否则顺序不稳定，列表会"自己跳"）。
    func testSortTieBreaksAreDeterministic() {
        let now: Int64 = 1_700_000_100_000
        let items = [
            memory(id: 1, importance: 0.5, observedAt: 100),
            memory(id: 2, importance: 0.5, observedAt: 200),
        ]
        let byImportance = MemoryView.groups(items, query: "", showInactive: false,
                                             sort: .importance, now: now).flatMap(\.items).map(\.id)
        XCTAssertEqual(byImportance, [2, 1], "重要度相同 → 新的在前")
    }

    // MARK: - 文案回退

    func testTypeTitlesFallBack() {
        XCTAssertEqual(MemoryView.typeTitle("WORKING"), "短期")
        XCTAssertEqual(MemoryView.typeTitle("EPISODIC"), "情景")
        XCTAssertEqual(MemoryView.typeTitle("SEMANTIC"), "语义")
        XCTAssertEqual(MemoryView.typeTitle("BRAND_NEW"), "BRAND_NEW")
        XCTAssertEqual(MemoryView.typeTitle(""), "未标注")
    }

    // MARK: - 仓储读侧（真实库）

    private func insert(_ database: YuNianDatabase, id: Int64, content: String,
                        type: String, observedAt: Int64, expiresAt: Int64?,
                        isDeleted: Int, importance: Double = 0.5) throws {
        try database.pool.write { db in
            try db.execute(sql: """
                INSERT INTO unified_memories
                  (id, memoryType, scope, source, content, summary, confidence, importance,
                   sourceId, createdAt, updatedAt, lastAccessedAt, observedAt, expiresAt,
                   accessCount, tags, deviceId, isDeleted)
                VALUES (?, ?, 'COMPANION', 'agent', ?, '', 1.0, ?, 1, ?, ?, ?, ?, ?, 1, '', ?, ?)
                """, arguments: [id, type, content, importance,
                                 observedAt, observedAt, observedAt, observedAt, expiresAt,
                                 DeviceIdentity.deviceId, isDeleted])
        }
    }

    func testRepositoryActiveExcludesDeletedAndExpired() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let now: Int64 = 1_700_000_100_000

        try insert(database, id: 1, content: "有效", type: "SEMANTIC",
                   observedAt: 100, expiresAt: nil, isDeleted: 0)
        try insert(database, id: 2, content: "过期", type: "SEMANTIC",
                   observedAt: 100, expiresAt: now - 1, isDeleted: 0)
        try insert(database, id: 3, content: "已删", type: "WORKING",
                   observedAt: 100, expiresAt: nil, isDeleted: 1)

        let repo = MemoryRepository(database: database)
        XCTAssertEqual(repo.listActive(now: now).map(\.id), [1], "只有未删且未过期的才算有效")
        XCTAssertEqual(repo.listAll().count, 3, "listAll 不过滤（含软删除）")

        let counts = repo.counts(now: now)
        XCTAssertEqual(counts.active, 1)
        XCTAssertEqual(counts.total, 3)
        XCTAssertEqual(counts.deleted, 2)
    }

    func testSoftDeleteRemovesFromActiveButKeepsRow() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let now: Int64 = 1_700_000_100_000

        try insert(database, id: 7, content: "会被删", type: "SEMANTIC",
                   observedAt: 100, expiresAt: nil, isDeleted: 0)
        let repo = MemoryRepository(database: database)
        XCTAssertEqual(repo.listActive(now: now).count, 1)

        XCTAssertTrue(repo.softDelete(id: 7))
        XCTAssertTrue(repo.listActive(now: now).isEmpty, "软删除后不再进提示词")
        XCTAssertEqual(repo.listAll().count, 1, "记录本身仍在库里（可被备份带走）")
    }
}
