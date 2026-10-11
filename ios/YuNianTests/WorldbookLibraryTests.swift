import GRDB
import XCTest

/// 世界书页（第 209 轮）：条目摘要解析 + 仓储 + 「引擎到底用哪一本」。
///
/// 最后一类最要紧：引擎是 `SELECT json ... WHERE enabled = 1 LIMIT 1`，
/// 界面若显示成"最后更新的那本"就会**撒谎**（用户以为生效的是 A，实际注入的是 B）。
@testable import YuNian
final class WorldbookLibraryTests: XCTestCase {

    private func makeDatabase() throws -> (YuNianDatabase, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("worldbook_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return (try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database")), dir)
    }

    // MARK: - 条目摘要（与引擎同一个解码器）

    private static let mapForm = """
    {
      "name": "map 形态",
      "entries": {
        "0": {"id": 7, "keys": ["咖啡", ""], "content": "只记得美式", "enabled": true,
              "insertion_order": 0, "scan_depth": 12, "case_sensitive": true},
        "1": {"id": 8, "keys": [], "content": "常驻设定", "constant": true,
              "insertion_order": 5, "position": "before_char"}
      },
      "scan_depth": 10
    }
    """

    private static let arrayForm = """
    {
      "name": "array 形态",
      "entries": [
        {"keys": ["下雨"], "content": "一下雨她就想起那天", "insertion_order": 0},
        {"keys": ["生日"], "content": "生日是三月三", "insertion_order": 1, "enabled": false}
      ]
    }
    """

    func testMapFormParsing() {
        let entries = WorldbookRuntimeSync.entrySummaries(Self.mapForm)
        XCTAssertEqual(entries.count, 2)
        // 顺序 = insertion_order 升序
        XCTAssertEqual(entries.map(\.order), [1, 2])
        let first = entries[0]
        XCTAssertEqual(first.entryId, 7)
        XCTAssertEqual(first.keys, ["咖啡"], "keys 里的空串要被丢掉")
        XCTAssertEqual(first.content, "只记得美式")
        XCTAssertEqual(first.scanDepth, 12)
        XCTAssertTrue(first.caseSensitive)
        XCTAssertFalse(first.constant)
        let second = entries[1]
        XCTAssertTrue(second.constant)
        XCTAssertEqual(second.position, "before_char")
        XCTAssertEqual(second.scanDepth, 10, "缺 scan_depth → 默认 10")
    }

    /// array 形态里条目**没有 id** → 回落 0；此时 `EntrySummary.id` 必须是**唯一键**，
    /// 否则 ForEach 会错行/丢行。
    func testArrayFormParsingAndUniqueIdentifiers() {
        let entries = WorldbookRuntimeSync.entrySummaries(Self.arrayForm)
        XCTAssertEqual(entries.count, 2)
        XCTAssertEqual(entries.map(\.entryId), [0, 0], "源里没有 id")
        XCTAssertEqual(Set(entries.map(\.id)).count, 2, "界面的唯一键不能重复")
        XCTAssertFalse(entries[1].enabled, "enabled 显式 false 要读出来")
        XCTAssertTrue(entries[0].enabled, "缺 enabled → 默认 true")
    }

    /// ⚠️ priority 的回落规则最容易漏：没有 `extensions._priority` 时
    /// 用 `ORDER_BASE − insertion_order`。漏了会让旧书所有条目优先级相同。
    func testPriorityFallsBackToOrderBaseMinusInsertionOrder() {
        let entries = WorldbookRuntimeSync.entrySummaries(Self.arrayForm)
        XCTAssertEqual(entries[0].priority, Int(4_294_967_296 - 0))
        XCTAssertEqual(entries[1].priority, Int(4_294_967_296 - 1))

        let withExplicit = """
        {"entries": [{"keys": ["k"], "content": "c", "insertion_order": 3,
                      "extensions": {"_priority": 42}}]}
        """
        XCTAssertEqual(WorldbookRuntimeSync.entrySummaries(withExplicit).first?.priority, 42)
    }

    func testBookNameAndShapeCheck() {
        XCTAssertEqual(WorldbookRuntimeSync.bookName(Self.mapForm), "map 形态")
        XCTAssertEqual(WorldbookRuntimeSync.bookName("{}"), "")
        XCTAssertTrue(WorldbookRuntimeSync.looksLikeWorldbook(Self.mapForm))
        XCTAssertTrue(WorldbookRuntimeSync.looksLikeWorldbook(Self.arrayForm))
        XCTAssertFalse(WorldbookRuntimeSync.looksLikeWorldbook(#"{"foo": 1}"#))
        XCTAssertFalse(WorldbookRuntimeSync.looksLikeWorldbook("不是 JSON"))
        XCTAssertFalse(WorldbookRuntimeSync.looksLikeWorldbook(""))
        // `entries` 必须是容器：只判"键在不在"会让这种形状落库成一本 0 条目的空书
        XCTAssertFalse(WorldbookRuntimeSync.looksLikeWorldbook(#"{"entries": 3}"#))
        XCTAssertFalse(WorldbookRuntimeSync.looksLikeWorldbook(#"{"entries": "x"}"#))
        XCTAssertFalse(WorldbookRuntimeSync.looksLikeWorldbook(#"{"entries": null}"#))
    }

    func testEntriesOfGarbageJSONIsEmpty() {
        XCTAssertTrue(WorldbookRuntimeSync.entrySummaries("").isEmpty)
        XCTAssertTrue(WorldbookRuntimeSync.entrySummaries("[]").isEmpty)
        XCTAssertTrue(WorldbookRuntimeSync.entrySummaries(#"{"entries": 3}"#).isEmpty)
    }

    // MARK: - 仓储

    func testSaveInsertsAndReplacesByName() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let repo = WorldbookRepository(database: database)

        let first = repo.save(name: "设定集", json: Self.mapForm)
        XCTAssertGreaterThan(first, 0)
        XCTAssertEqual(repo.list().count, 1)

        // 同名再存 → 覆盖，不新增一行
        _ = repo.save(name: "设定集", json: Self.arrayForm)
        XCTAssertEqual(repo.list().count, 1)
        XCTAssertEqual(repo.list().first?.json, Self.arrayForm)
    }

    func testSaveWithEmptyNameUsesFallback() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let repo = WorldbookRepository(database: database)

        _ = repo.save(name: "   ", json: Self.mapForm)
        XCTAssertEqual(repo.list().first?.name, "未命名世界书",
                       "空名字要有可读的回落，不能写空串进库")
    }

    /// 界面显示的「当前生效」必须与引擎取的是**同一本**（`LIMIT 1`）。
    func testActiveGlobalBookMatchesEngineSemantics() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let repo = WorldbookRepository(database: database)

        let a = repo.save(name: "先插入", json: Self.mapForm)
        let b = repo.save(name: "后插入", json: Self.arrayForm)
        repo.setEnabled(true, id: a)
        repo.setEnabled(true, id: b)
        XCTAssertEqual(repo.counts().enabled, 2)

        // 引擎侧同一查询
        let engineJson = try database.pool.read { db in
            try String.fetchOne(db, sql: "SELECT json FROM worldbooks WHERE enabled = 1 LIMIT 1")
        }
        XCTAssertEqual(repo.activeGlobalBook()?.json, engineJson,
                       "界面显示的生效书必须等于引擎实际取到的那一本")
        XCTAssertEqual(repo.activeGlobalBook()?.name, "先插入", "LIMIT 1 = 行序在前的先命中")
    }

    func testNoEnabledBookMeansNoInjection() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let repo = WorldbookRepository(database: database)
        let id = repo.save(name: "x", json: Self.mapForm)
        _ = repo.setEnabled(false, id: id)

        XCTAssertNil(repo.activeGlobalBook(), "一本都没启用 = 引擎不注入（正常状态）")
        XCTAssertEqual(repo.counts().enabled, 0)
    }

    func testEnableOnlyDisablesTheRest() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let repo = WorldbookRepository(database: database)

        let a = repo.save(name: "A", json: Self.mapForm)
        let b = repo.save(name: "B", json: Self.arrayForm)
        repo.setEnabled(true, id: a)
        repo.setEnabled(true, id: b)

        repo.enableOnly(id: b)
        XCTAssertEqual(repo.counts().enabled, 1)
        XCTAssertEqual(repo.activeGlobalBook()?.name, "B")
    }

    func testDelete() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let repo = WorldbookRepository(database: database)
        let id = repo.save(name: "要删的", json: Self.mapForm)

        XCTAssertTrue(repo.delete(id: id))
        XCTAssertTrue(repo.list().isEmpty)
        XCTAssertFalse(repo.delete(id: id), "删不存在的行要返回 false，不抛")
    }

    func testSetEnabledOnMissingRowReturnsFalse() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        XCTAssertFalse(WorldbookRepository(database: database).setEnabled(true, id: 9999))
    }

    func testBookEntryCountAndParsability() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let repo = WorldbookRepository(database: database)
        _ = repo.save(name: "好的一本", json: Self.mapForm)
        _ = repo.save(name: "坏的一本", json: #"{"foo":1}"#)

        let books = repo.list()
        let good = try XCTUnwrap(books.first { $0.name == "好的一本" })
        let bad = try XCTUnwrap(books.first { $0.name == "坏的一本" })
        XCTAssertEqual(good.entryCount, 2)
        XCTAssertTrue(good.isParsable)
        XCTAssertEqual(bad.entryCount, 0)
        XCTAssertFalse(bad.isParsable, "不是世界书的 JSON 要在列表里被标出来")
    }

    /// 角色专属书（companionId 非 nil）与全局书并存时，全局路径只认启用者。
    func testCompanionScopedBookKeepsItsOwner() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let repo = WorldbookRepository(database: database)
        let id = repo.save(name: "她的专属", json: Self.mapForm, companionId: 7)
        XCTAssertEqual(repo.list().first { $0.id == id }?.companionId, 7)
        XCTAssertEqual(repo.activeGlobalBook()?.id, id,
                       "activeGlobalBook 不按归属过滤（与引擎查询一致）—— 归属只影响按伴侣合成")
    }
}
