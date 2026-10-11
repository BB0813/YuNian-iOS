import GRDB
import XCTest

/// 角色卡导入（第 213 轮）。
///
/// ## 这一套测试要回答的问题
/// 1. **解析真的走引擎**（JSON 与 PNG 两条路），不是本机另写一套；
/// 2. 卡片字段**落到引擎会读的那几列**（`ContextProbe.makeProfile` 复核）；
/// 3. 内嵌世界书导入后**真的会被这个角色的回合带上**
///    （用 `synthForCompanion` 端到端验 —— 只看"表里多了一行"是不够的）；
/// 4. 开场白如实处理：**不落库**，也不许悄悄塞进别的列。
@testable import YuNian
final class CharacterCardImportTests: XCTestCase {

    private func makeEnv() throws -> (CompanionRepository, YuNianDatabase, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("card_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let database = try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database"))
        return (CompanionRepository(database: database), database, dir)
    }

    // 与 Rust 侧单测同构的样例：V1 扁平 + V2 嵌套（entries 为 map）
    private static let v1JSON = """
    {"name":"小恋","description":"咖啡馆主理人","personality":"温柔","scenario":"",
     "first_mes":"你好呀","mes_example":""}
    """

    private static let v2JSON = """
    {"spec":"chara_card_v2","spec_version":"2.0",
     "data":{"name":"测试卡","description":"设定正文","personality":"外冷内热",
             "scenario":"","first_mes":"hi","mes_example":"","system_prompt":"不要提搬家",
             "character_book":{"name":"内嵌世界","entries":{
               "1":{"keys":["咖啡"],"content":"她只喝美式","insertion_order":1,"enabled":true}}}}}
    """

    /// 构造最小 PNG：签名 + tEXt(chara=base64 JSON) + IEND。
    /// 与 Rust 单测的做法一致 —— **不校验 CRC**，所以不需要 zlib。
    private static func makePNG(json: String) -> Data {
        let b64 = Data(json.utf8).base64EncodedString()
        var data = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        let text = Data("chara\0\(b64)".utf8)
        data.append(contentsOf: bigEndianBytes(UInt32(text.count)))
        data.append(Data("tEXt".utf8))
        data.append(text)
        data.append(contentsOf: [0, 0, 0, 0])          // CRC 占位
        data.append(contentsOf: bigEndianBytes(0))
        data.append(Data("IEND".utf8))
        data.append(contentsOf: [0, 0, 0, 0])
        return data
    }

    /// 手写大端字节。
    ///
    /// ⚠️ 刻意不用 `withUnsafeBytes(of:)` 那套惯用法：这里要的是**最不容易出歧义**的写法
    /// （第 208 轮吃过"macOS 的 Swift 对复杂表达式直接崩"的教训）。
    private static func bigEndianBytes(_ value: UInt32) -> [UInt8] {
        [UInt8(value >> 24 & 0xFF), UInt8(value >> 16 & 0xFF),
         UInt8(value >> 8 & 0xFF), UInt8(value & 0xFF)]
    }

    // MARK: - 解析

    func testParseV1JSON() throws {
        let preview = try XCTUnwrap(CharacterCardImporter.preview(json: Self.v1JSON))
        XCTAssertEqual(preview.name, "小恋")
        XCTAssertEqual(preview.description, "咖啡馆主理人")
        XCTAssertEqual(preview.personality, "温柔")
        XCTAssertEqual(preview.firstMes, "你好呀")
        XCTAssertFalse(preview.hasWorld, "V1 没有内嵌世界书")
    }

    func testParseV2JSONWithEmbeddedWorldMapForm() throws {
        let preview = try XCTUnwrap(CharacterCardImporter.preview(json: Self.v2JSON))
        XCTAssertEqual(preview.name, "测试卡")
        XCTAssertEqual(preview.systemPrompt, "不要提搬家")
        XCTAssertTrue(preview.hasWorld, "V2 的 character_book 要被提取出来")
        XCTAssertEqual(preview.worldEntryCount, 1)
    }

    func testParsePNGExtractsCharaChunk() throws {
        let png = Self.makePNG(json: Self.v1JSON)
        XCTAssertTrue(CharacterCardImporter.isPNG(png))
        let preview = try XCTUnwrap(CharacterCardImporter.preview(png: png))
        XCTAssertEqual(preview.name, "小恋")
    }

    func testParseBytesAutoDetectsPNGAndJSON() throws {
        XCTAssertEqual(CharacterCardImporter.preview(bytes: Self.makePNG(json: Self.v1JSON))?.name, "小恋")
        XCTAssertEqual(CharacterCardImporter.preview(bytes: Data(Self.v2JSON.utf8))?.name, "测试卡")
    }

    func testFailuresReturnNil() {
        XCTAssertNil(CharacterCardImporter.preview(json: "不是 JSON"))
        XCTAssertNil(CharacterCardImporter.preview(json: ""))
        // PNG 签名正确但没有 chara chunk
        let barePNG = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        XCTAssertNil(CharacterCardImporter.preview(png: barePNG))
        XCTAssertFalse(CharacterCardImporter.isPNG(Data("x".utf8)))
    }

    // MARK: - 落位（引擎可见字段）

    func testSaveMapsCardFieldsToEngineVisibleColumns() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }

        let preview = try XCTUnwrap(CharacterCardImporter.preview(json: Self.v2JSON))
        let result = try CharacterCardImporter.save(preview, importWorld: false,
                                                    companions: companions, database: database)
        let companion = try XCTUnwrap(try companions.fetch(id: result.companionId))
        let profile = try XCTUnwrap(ContextProbe.makeProfile(companion, role: "GIRLFRIEND"))

        XCTAssertEqual(profile.name, "测试卡")
        XCTAssertEqual(profile.personality, "外冷内热")          // 性格 → 人设
        XCTAssertEqual(profile.backstory, "设定正文")             // description → 背景
        XCTAssertEqual(profile.customPrompt, "不要提搬家")        // system_prompt → 自定义指令
        XCTAssertNil(profile.speakingStyle, "卡片没有这个字段 → 不写空串")
        XCTAssertNil(profile.age)
        XCTAssertNil(profile.rawPrompt)
    }

    /// 开场白**不落库**，也不许悄悄塞进别的列（塞进哪一列都会污染提示词）。
    func testFirstMessageIsNotPersistedAnywhere() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }

        let preview = try XCTUnwrap(CharacterCardImporter.preview(json: Self.v1JSON))
        let result = try CharacterCardImporter.save(preview, importWorld: false,
                                                    companions: companions, database: database)
        let companion = try XCTUnwrap(try companions.fetch(id: result.companionId))

        let first = "你好呀"
        XCTAssertNotEqual(companion.personality, first)
        XCTAssertNotEqual(companion.backstory, first)
        XCTAssertNotEqual(companion.systemPrompt, first)
        XCTAssertNotEqual(companion.rawPrompt, first)
        XCTAssertNotEqual(companion.speakingStyle, first)
        XCTAssertFalse(companion.lorebookIdsJson.contains(first))
    }

    func testEmptyCardNameFallsBackToReadableName() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }

        let preview = try XCTUnwrap(CharacterCardImporter.preview(json: #"{"name":"","personality":"x"}"#))
        let result = try CharacterCardImporter.save(preview, importWorld: false,
                                                    companions: companions, database: database)
        XCTAssertEqual(try companions.fetch(id: result.companionId)?.name, "未命名角色",
                       "name 是 NOT NULL 列，不能写空串")
    }

    // MARK: - 内嵌世界书是否真的会被带上

    /// **端到端**：导入后 `synthForCompanion` 必须能合成出卡片世界书里的条目。
    /// 只断言"worldbooks 多了一行"是不够的 —— 那行可能根本没进这条链路。
    func testImportedWorldbookActuallyFeedsTheEngineForThatCompanion() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }

        let preview = try XCTUnwrap(CharacterCardImporter.preview(json: Self.v2JSON))
        let result = try CharacterCardImporter.save(preview, importWorld: true,
                                                    companions: companions, database: database)

        let worldbookId = try XCTUnwrap(result.worldbookId, "有内嵌世界书时应导入")
        let book = try XCTUnwrap(WorldbookRepository(database: database).list().first { $0.id == worldbookId })
        XCTAssertEqual(book.companionId, result.companionId, "落成该角色的专属书")
        XCTAssertTrue(book.enabled)

        let payload = WorldbookRuntimeSync.synthForCompanion(database, companionId: result.companionId)
        XCTAssertTrue(payload.contains("她只喝美式"), "引擎该拿到卡片世界书的条目：\(payload)")
        // 合成的顶层 name 取的是**表里那本书的名字**（不是卡片内 character_book 的名字），
        // 所以这里改判"合成结果本身是一本可解析的世界书、且条目数对得上"。
        XCTAssertTrue(WorldbookRuntimeSync.looksLikeWorldbook(payload))
        XCTAssertEqual(WorldbookRuntimeSync.entrySummaries(payload).count, 1)
    }

    func testImportWorldDisabledWritesNoBook() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }

        let preview = try XCTUnwrap(CharacterCardImporter.preview(json: Self.v2JSON))
        let result = try CharacterCardImporter.save(preview, importWorld: false,
                                                    companions: companions, database: database)
        XCTAssertNil(result.worldbookId)
        XCTAssertTrue(WorldbookRepository(database: database).list().isEmpty)
    }

    /// 卡片没有世界书时，开关即使为 true 也不该造出一本空书。
    func testImportWorldWithNoEmbeddedBookWritesNothing() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }

        let preview = try XCTUnwrap(CharacterCardImporter.preview(json: Self.v1JSON))
        let result = try CharacterCardImporter.save(preview, importWorld: true,
                                                    companions: companions, database: database)
        XCTAssertNil(result.worldbookId)
        XCTAssertTrue(WorldbookRepository(database: database).list().isEmpty)
    }

    func testSaveWithoutCompanionRepositoryThrows() throws {
        let (_, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let preview = try XCTUnwrap(CharacterCardImporter.preview(json: Self.v1JSON))
        XCTAssertThrowsError(try CharacterCardImporter.save(preview, importWorld: false,
                                                            companions: nil, database: database))
    }

    func testImportErrorHasReadableMessage() {
        XCTAssertEqual(CharacterCardImporter.ImportError.environmentNotReady.message, "运行环境未就绪")
    }
}
