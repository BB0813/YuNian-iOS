import GRDB
import XCTest

/// 角色卡导出（第 214 轮）——与导入对称的那一半。
///
/// ## 最要紧的两条断言
/// 1. **往返**：导出的 JSON 能被**引擎的导入器**原样吃回来（字段对得上）。
///    自己写一套"看起来对"的 JSON 没有意义 —— 别人（或我们自己）导不进来就等于没导出。
/// 2. **世界书往返**：专属世界书 → `character_book` → 再导入后**条目数一致**。
///    只断言"JSON 里有那个键"是不够的。
@testable import YuNian
final class CharacterCardExportTests: XCTestCase {

    private func makeEnv() throws -> (CompanionRepository, YuNianDatabase, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("cardexport_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let database = try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database"))
        return (CompanionRepository(database: database), database, dir)
    }

    private static let bookJSON = """
    {"name":"她的世界","entries":{
      "1":{"keys":["咖啡"],"content":"她只喝美式","insertion_order":1,"enabled":true},
      "2":{"keys":["雨天"],"content":"下雨天她话很少","insertion_order":2,"enabled":true}}}
    """

    private func makeCompanion(_ repo: CompanionRepository,
                               speakingStyle: String? = nil) throws -> Int64 {
        try repo.create(name: "阿念", personality: "外冷内热", age: 22,
                        backstory: "在杭州开咖啡馆", speakingStyle: speakingStyle,
                        avatarUrl: nil, tags: nil,
                        rawPrompt: nil, systemPrompt: "不要提搬家")
    }

    // MARK: - 往返

    func testExportedJSONRoundTripsThroughEngineImporter() throws {
        let (repo, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }

        let id = try makeCompanion(repo)
        let companion = try XCTUnwrap(try repo.fetch(id: id))
        let exported = CharacterCardExporter.export(companion: companion, database: database)

        // 关键：用**引擎的导入器**读回来
        let preview = try XCTUnwrap(CharacterCardImporter.preview(json: exported.json))
        XCTAssertEqual(preview.name, "阿念")
        XCTAssertEqual(preview.personality, "外冷内热")
        XCTAssertEqual(preview.description, "在杭州开咖啡馆", "背景 → description → 再导入回背景")
        XCTAssertEqual(preview.systemPrompt, "不要提搬家")
        XCTAssertTrue(preview.firstMes.isEmpty, "开场白本来就没存，导出必然为空")
    }

    /// 世界书往返：条目数必须一致（不只是"JSON 里有 character_book 这个键"）。
    func testOwnedWorldbookRoundTripsWithSameEntryCount() throws {
        let (repo, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }

        let id = try makeCompanion(repo)
        let bookId = WorldbookRepository(database: database)
            .save(name: "她的世界", json: Self.bookJSON, companionId: id)
        XCTAssertGreaterThan(bookId, 0)

        let companion = try XCTUnwrap(try repo.fetch(id: id))
        let exported = CharacterCardExporter.export(companion: companion, database: database)
        XCTAssertEqual(exported.embeddedBookName, "她的世界")
        XCTAssertEqual(exported.embeddedEntryCount, 2)

        let preview = try XCTUnwrap(CharacterCardImporter.preview(json: exported.json))
        XCTAssertTrue(preview.hasWorld, "内嵌世界书要能被导入器识别")
        XCTAssertEqual(preview.worldEntryCount, 2, "条目数必须一致")
    }

    /// 导出的对象结构是标准 V2（spec / data）。
    func testExportedShapeIsV2() throws {
        let (repo, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try makeCompanion(repo)
        let companion = try XCTUnwrap(try repo.fetch(id: id))

        let json = CharacterCardExporter.export(companion: companion, database: database).json
        let root = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        XCTAssertEqual(root["spec"] as? String, "chara_card_v2")
        XCTAssertEqual(root["spec_version"] as? String, "2.0")
        let data = try XCTUnwrap(root["data"] as? [String: Any])
        XCTAssertEqual(data["name"] as? String, "阿念")
        XCTAssertNotNil(data["first_mes"], "空也要有这个键（导入侧按缺省读）")
    }

    // MARK: - 世界书：只在"自己的 + 启用的"里取

    func testNoOwnedBookMeansNoCharacterBookKey() throws {
        let (repo, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try makeCompanion(repo)
        let companion = try XCTUnwrap(try repo.fetch(id: id))

        let exported = CharacterCardExporter.export(companion: companion, database: database)
        XCTAssertNil(exported.embeddedBookName)
        XCTAssertEqual(exported.ownedBookCount, 0)
        XCTAssertFalse(exported.json.contains("character_book"))
    }

    /// 别的角色的书不该被带出去。
    func testOtherCompanionsBookIsNotEmbedded() throws {
        let (repo, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let mine = try makeCompanion(repo)
        let other = try repo.create(name: "另一个", personality: "x")
        _ = WorldbookRepository(database: database).save(name: "别人的书", json: Self.bookJSON,
                                                         companionId: other)

        let companion = try XCTUnwrap(try repo.fetch(id: mine))
        let exported = CharacterCardExporter.export(companion: companion, database: database)
        XCTAssertNil(exported.embeddedBookName)
        XCTAssertFalse(exported.json.contains("别人的书"))
    }

    /// 停用的专属书不导出（**启用**才算"生效"）。
    func testDisabledOwnedBookIsNotEmbedded() throws {
        let (repo, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try makeCompanion(repo)
        let books = WorldbookRepository(database: database)
        let bookId = books.save(name: "停用的", json: Self.bookJSON, companionId: id)
        _ = books.setEnabled(false, id: bookId)

        let companion = try XCTUnwrap(try repo.fetch(id: id))
        let exported = CharacterCardExporter.export(companion: companion, database: database)
        XCTAssertNil(exported.embeddedBookName)
        XCTAssertEqual(exported.ownedBookCount, 0, "停用的不算")
    }

    /// 多本专属书：只嵌一本，但**如实回报总数**（界面据此提示"只带了第一本"）。
    ///
    /// ⚠️ 不断言"嵌的是哪一本"：两本书可能落在同一毫秒，而列表排序按 `updatedAt DESC`
    /// 对并列没有稳定次序 —— 断言具体名字会变成一条会偶发失败的测试。
    func testMultipleOwnedBooksReportCountAndEmbedOne() throws {
        let (repo, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try makeCompanion(repo)
        let books = WorldbookRepository(database: database)
        _ = books.save(name: "第一本", json: Self.bookJSON, companionId: id)
        _ = books.save(name: "第二本", json: Self.bookJSON, companionId: id)

        let companion = try XCTUnwrap(try repo.fetch(id: id))
        let exported = CharacterCardExporter.export(companion: companion, database: database)
        XCTAssertEqual(exported.ownedBookCount, 2)
        let name = try XCTUnwrap(exported.embeddedBookName)
        XCTAssertTrue(["第一本", "第二本"].contains(name), "只能是这两本之一，实际：\(name)")
        XCTAssertEqual(exported.embeddedEntryCount, 2)
    }

    // MARK: - 已知损失

    /// 说话风格：卡片格式没有这个字段 → 并入 description。
    /// ⚠️ 再导入时它会落在「背景」里，**不会**回到「说话风格」——这是有损的，
    /// 所以这里把行为固定下来（而不是假装无损）。
    func testSpeakingStyleIsMergedIntoDescription() throws {
        let (repo, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try makeCompanion(repo, speakingStyle: "轻声细语")
        let companion = try XCTUnwrap(try repo.fetch(id: id))

        let exported = CharacterCardExporter.export(companion: companion, database: database)
        let preview = try XCTUnwrap(CharacterCardImporter.preview(json: exported.json))
        XCTAssertTrue(preview.description.contains("轻声细语"), "别把信息丢掉")
        XCTAssertTrue(preview.description.contains("在杭州开咖啡馆"))
    }

    func testEmptySpeakingStyleDoesNotAddNoise() throws {
        let (repo, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try makeCompanion(repo)
        let companion = try XCTUnwrap(try repo.fetch(id: id))
        let exported = CharacterCardExporter.export(companion: companion, database: database)
        XCTAssertFalse(exported.json.contains("说话风格"))
    }

    // MARK: - 文件名与工具

    func testFileNameSanitization() {
        XCTAssertEqual(CharacterCardExporter.sanitizedFileName("阿念"), "阿念")
        XCTAssertEqual(CharacterCardExporter.sanitizedFileName("a/b:c*d?e\"f<g>h|i"), "a_b_c_d_e_f_g_h_i")
        XCTAssertEqual(CharacterCardExporter.sanitizedFileName("带\n换行"), "带_换行")
        XCTAssertEqual(CharacterCardExporter.sanitizedFileName("   "), "character-card")
        XCTAssertEqual(CharacterCardExporter.sanitizedFileName(""), "character-card")
        XCTAssertEqual(CharacterCardExporter.sanitizedFileName(String(repeating: "长", count: 60)).count, 40)
    }

    func testWorldbookObjectValidation() {
        XCTAssertNotNil(CharacterCardExporter.worldbookObject(from: Self.bookJSON))
        XCTAssertNil(CharacterCardExporter.worldbookObject(from: "不是 JSON"))
        XCTAssertNil(CharacterCardExporter.worldbookObject(from: #"{"foo":1}"#),
                     "没有 entries 就不是世界书 —— 塞进卡片会让对方的导入器整张卡失败")
        XCTAssertNil(CharacterCardExporter.worldbookObject(from: #"{"entries":"x"}"#))
    }

    func testExportWithoutDatabaseStillProducesValidCard() throws {
        let (repo, _, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try makeCompanion(repo)
        let companion = try XCTUnwrap(try repo.fetch(id: id))

        let exported = CharacterCardExporter.export(companion: companion, database: nil)
        XCTAssertNil(exported.embeddedBookName)
        XCTAssertNotNil(CharacterCardImporter.preview(json: exported.json))
    }
}
