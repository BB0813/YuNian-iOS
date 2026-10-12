import XCTest
import GRDB

@testable import YuNian

/// 表情清单解析 + 导入命名/补名（第 226 轮）。
///
/// ## 这一套为什么值得写
/// 用户的原始诉求：「导入的时候**括号里框选的部分**明明可以作为表情的昵称，
/// 为什么现在直接以文件名命名？」
///
/// 查下来是两个各自都不报错的缺陷叠在一起：
/// 1. 解析器**已经**把括号内容当名字（`StickerListParser`）——
///    但 `StickerLibraryRepository.Entry.displayName` 的实现与它自己的注释相反
///    （注释写「优先 description」，代码 `{ fileName }`），而且查询**根本没读 description**
///    → 库里存着名字，界面全是 `custom_1791…`；
/// 2. 重复导入直接跳过 → 已经导错名字的那批**没法修**。
///
/// 名字不是装饰：`deriveTags` 由它派生 tags，tags 是 Rust `builtin_send_sticker`
/// 精确匹配的唯一依据 —— 名字错了，模型就挑不中这张表情。
final class StickerImportNamingTests: XCTestCase {

    // MARK: - 样本：用户实际粘贴的那份清单

    /// 用户真实清单的前三条（原样，含全角括号、行内换行与落单引号）。
    private let sample = """
        呆猫八条

        https://pic1.imgdb.cn/i/033tXnF2WYWeRbA78SURXJ.jpg（小猫手里拿红包配文"九九成稀罕物)
        https://pic1.imgdb.cn/i/033tWPWeOnGQafzIhKOQly.jpg（小猫躲在墙边偷看）
        https://pic1.imgdb.cn/i/033tY82Oq1MCIZIgWKWAML.jpg（小猫趴下眼睛亮晶晶配文"咪"）
        """

    func testParsesOneStickerPerLink() {
        let items = StickerListParser.parse(sample)
        XCTAssertEqual(items.count, 3)
        XCTAssertEqual(items[0].urlString, "https://pic1.imgdb.cn/i/033tXnF2WYWeRbA78SURXJ.jpg")
    }

    /// **核心诉求**：括号内容就是名字（而不是文件名）。
    func testParentheticalBecomesTheName() {
        let items = StickerListParser.parse(sample)
        XCTAssertEqual(items[0].name, "小猫手里拿红包配文九九成稀罕物",
                       "落单的引号也要清掉，否则名字里挂着一个孤零零的 \"")
        XCTAssertEqual(items[1].name, "小猫躲在墙边偷看")
        XCTAssertEqual(items[2].name, "小猫趴下眼睛亮晶晶配文咪")
    }

    /// 名字不该退化成 URL 的文件名（这正是用户抱怨的现象）。
    func testNameIsNotTheFileName() {
        for item in StickerListParser.parse(sample) {
            XCTAssertFalse(item.name.hasPrefix("033t"), "名字不能是图床文件名：\(item.name)")
            XCTAssertFalse(item.name.contains(".jpg"), "名字里不该出现文件名：\(item.name)")
        }
    }

    /// ## 回归：括号不能跨条目配对（第 226 轮 CI 抓到的真 bug）
    /// 第 1 条的 `（` 是**半角闭**，而它后面第 2 条是**全角闭** ——
    /// 只按"配对宽度"找闭合括号时，第 1 条的 `（` 会一直找到第 2 条的 `）`，
    /// 把中间**整条链接**当成名字吞进去（于是名字里出现 `.jpg`）。
    func testParentheticalNeverSpansToTheNextEntry() {
        for item in StickerListParser.parse(sample) {
            XCTAssertFalse(item.name.contains("://"), "名字里不该出现链接：\(item.name)")
            XCTAssertFalse(item.name.contains("）"), "名字里不该残留括号：\(item.name)")
            XCTAssertFalse(item.name.contains("（"))
        }
    }

    /// Word 复制出来的清单常见**全角开、半角闭**，必须认。
    func testMixedWidthBrackets() {
        let items = StickerListParser.parse("https://a.cn/9.jpg（全角开半角闭)")
        XCTAssertEqual(items.first?.name, "全角开半角闭")
    }

    /// 括号内容里若真的套着链接，宁可不要（回落到链接前那段文字），
    /// 也不能让名字变成一条 URL。
    func testParentheticalContainingLinkIsRejected() {
        let items = StickerListParser.parse("小猫：https://a.cn/10.jpg（见 https://b.cn/x.jpg）")
        XCTAssertEqual(items.first?.name, "小猫")
    }

    /// 「名称：链接」写法（括号在链接**之前**）也要认。
    func testNameBeforeLinkStillWorks() {
        let items = StickerListParser.parse("小猫拿红包：https://pic1.imgdb.cn/i/aaa.jpg")
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items[0].name, "小猫拿红包")
    }

    /// 两者都没有 → 才回落文件名。
    func testFallsBackToFileNameWhenNothingElse() {
        let items = StickerListParser.parse("https://pic1.imgdb.cn/i/033tAAA.jpg")
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items[0].name, "033tAAA")
    }

    /// 四种括号都要认（全角/半角圆括号、方括号、角括号），且半角括号在链接后同样生效。
    func testAllFourBracketStyles() {
        let cases: [(String, String)] = [
            ("https://a.cn/1.jpg（全角圆括号）", "全角圆括号"),
            ("https://a.cn/2.jpg(半角圆括号)", "半角圆括号"),
            ("https://a.cn/3.jpg【方头括号】", "方头括号"),
            ("https://a.cn/4.jpg[半角方括号]", "半角方括号"),
        ]
        for (line, expected) in cases {
            let items = StickerListParser.parse(line)
            XCTAssertEqual(items.first?.name, expected, "解析失败：\(line)")
        }
    }

    /// 空括号不该被当成名字（否则名字成了空串，又回落文件名）。
    func testEmptyParentheticalIsIgnored() {
        let items = StickerListParser.parse("https://a.cn/5.jpg（）")
        XCTAssertEqual(items.first?.name, "5")
    }

    /// 没有扩展名的图床链接（历史上踩过的坑：要求扩展名会一条都解析不出）。
    func testLinkWithoutExtensionStillParses() {
        let items = StickerListParser.parse("https://u2.fukit.cn/4uhF9bynr（没有扩展名）")
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items.first?.name, "没有扩展名")
    }

    // MARK: - 标签派生（名字 → tags，模型就按这个挑）

    /// 名字里的分隔符会被拆成多个标签（最多 3 个），这是跨端契约。
    func testDeriveTagsSplitsName() {
        let tags = StickerImportRepository.deriveTags(
            description: "小猫,拿红包、开心", fileName: "custom_1_1.jpg")
        XCTAssertEqual(tags, "小猫,拿红包,开心")
    }

    /// 名字为空才回落文件名 —— 也是契约的一部分。
    func testDeriveTagsFallsBackToFileName() {
        XCTAssertEqual(StickerImportRepository.deriveTags(description: "", fileName: "custom_1_1.jpg"),
                       "custom_1_1")
    }

    /// 用户手填标签时的规范化：trim / 去空 / 去重 / 最多 3 个。
    func testNormalizeTags() {
        XCTAssertEqual(StickerImportRepository.normalizeTags([" 开心 ", "开心", "", "撒娇", "生气", "多余"]),
                       "开心,撒娇,生气")
    }

    // MARK: - 补名字：重复导入不再"跳过"，而是更新元数据

    private func makeDatabase() throws -> (YuNianDatabase, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("sticker_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let db = try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database"))
        return (db, dir)
    }

    /// 同一张图（同 hash）第二次导入：**不新增行**，但名字与标签被更新。
    func testSecondImportBackfillsTheName() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let repo = StickerImportRepository(database: database)
        let bytes = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] + Array(repeating: 7, count: 64))

        // 第一次：没有名字（模拟"旧版本没解析括号"或清单里没写描述）
        let first = try repo.importSticker(data: bytes, fileName: "custom_1_1.png",
                                           description: "", userTags: [])
        guard case .inserted = first else { return XCTFail("第一次应当是新增") }

        // 第二次：同一张图，带了名字 → 应当更新而不是跳过
        let second = try repo.importSticker(data: bytes, fileName: "custom_2_2.png",
                                            description: "小猫拿红包", userTags: [])
        guard case let .updated(entry) = second else { return XCTFail("第二次应当是更新") }
        XCTAssertEqual(entry.name, "小猫拿红包")

        // 库里仍然只有一行，且文件名没变（还是第一次那份）
        let library = StickerLibraryRepository(database: database)
        let rows = try library.entries()
        XCTAssertEqual(rows.count, 1, "补名字不该新增行")
        XCTAssertEqual(rows[0].fileName, "custom_1_1.png", "库仍应指向第一次写入的文件")
        XCTAssertEqual(rows[0].displayName, "小猫拿红包", "展示名应当变成清单里的名字")
        XCTAssertEqual(rows[0].tags, ["小猫拿红包"], "标签要跟着名字一起更新")
    }

    /// 第二次导入若**没有**名字，仍按重复处理（不把已有名字清空）。
    func testNamelessSecondImportKeepsExistingName() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let repo = StickerImportRepository(database: database)
        let bytes = Data([0xFF, 0xD8, 0xFF] + Array(repeating: 3, count: 32))

        _ = try repo.importSticker(data: bytes, fileName: "custom_1_1.jpg",
                                   description: "小猫生气", userTags: [])
        XCTAssertThrowsError(try repo.importSticker(data: bytes, fileName: "custom_2_2.jpg",
                                                    description: "", userTags: [])) { error in
            XCTAssertEqual(error as? StickerImportRepository.ImportError, .duplicate)
        }
        let rows = try StickerLibraryRepository(database: database).entries()
        XCTAssertEqual(rows.count, 1)
        XCTAssertEqual(rows[0].displayName, "小猫生气", "空名字不该把已有名字覆盖掉")
    }

    /// 标签聚合表（模型侧的 Top-N 兜底）要跟着更新。
    func testTagStatsFollowTheUpdatedName() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let repo = StickerImportRepository(database: database)
        let bytes = Data([0x47, 0x49, 0x46] + Array(repeating: 1, count: 16))

        _ = try repo.importSticker(data: bytes, fileName: "custom_1_1.gif",
                                   description: "旧名字", userTags: [])
        _ = try repo.importSticker(data: bytes, fileName: "custom_2_2.gif",
                                   description: "小猫拿红包", userTags: [])

        let stats = try StickerLibraryRepository(database: database).tagStats()
        XCTAssertEqual(stats.map(\.tag), ["小猫拿红包"], "旧标签应被清掉")
    }

    /// ## 预览页的改名与删除（第 226 轮）
    /// 改名要**同时更新标签**（名字派生标签，标签决定模型挑不挑得中）；
    /// 删除要**同时删文件**，否则留下永远没人清的孤儿文件。
    func testRenameUpdatesTagsAndStats() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let repo = StickerImportRepository(database: database)
        let library = StickerLibraryRepository(database: database)
        let bytes = Data([0x89, 0x50, 0x4E, 0x47] + Array(repeating: 5, count: 16))
        let outcome = try repo.importSticker(data: bytes, fileName: "custom_9_9.png",
                                             description: "旧名字", userTags: [])
        let id = try XCTUnwrap(library.entries().first?.id)
        if case .inserted = outcome {} else { XCTFail("应当是新增") }

        try repo.renameSticker(id: id, name: "小猫拿红包", userTags: ["开心", "撒娇"])

        let row = try XCTUnwrap(library.entries().first)
        XCTAssertEqual(row.displayName, "小猫拿红包")
        XCTAssertEqual(row.tags, ["开心", "撒娇"], "手填标签优先，与导入同一条规则")
        XCTAssertEqual(try library.tagStats().map(\.tag), ["开心", "撒娇"], "聚合表要跟着重建")
    }

    /// 不填标签时按名字自动拆分（与导入一致，两条路不能得出不同结果）。
    func testRenameWithoutTagsDerivesFromName() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let repo = StickerImportRepository(database: database)
        let library = StickerLibraryRepository(database: database)
        let bytes = Data([0x47, 0x49, 0x46] + Array(repeating: 2, count: 16))
        _ = try repo.importSticker(data: bytes, fileName: "custom_8_8.gif",
                                   description: "旧的", userTags: [])
        let id = try XCTUnwrap(library.entries().first?.id)

        try repo.renameSticker(id: id, name: "小猫,拿红包", userTags: [])

        XCTAssertEqual(try library.entries().first?.tags, ["小猫", "拿红包"])
    }

    /// 删除：库行与文件都要消失，标签聚合表也要跟着少掉。
    func testDeleteRemovesRowAndFile() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let repo = StickerImportRepository(database: database)
        let library = StickerLibraryRepository(database: database)
        let bytes = Data([0xFF, 0xD8, 0xFF] + Array(repeating: 9, count: 16))
        _ = try repo.importSticker(data: bytes, fileName: "custom_7_7.jpg",
                                   description: "要删的", userTags: [])
        let id = try XCTUnwrap(library.entries().first?.id)
        let fileURL = try XCTUnwrap(try? AppPaths.stickersDirectory().appendingPathComponent("custom_7_7.jpg"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: fileURL.path), "导入后文件应当存在")

        try repo.deleteSticker(id: id)

        XCTAssertEqual(try library.count(), 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fileURL.path), "文件必须一起删掉")
        XCTAssertTrue(try library.tagStats().isEmpty, "标签聚合要跟着清干净")
    }
    /// 两个口径必须一致：徽标上的 N 张要等于列表里的条数，
    /// 否则用户看到的数字与打开后看到的张数对不上。
    /// ## 「我」页那行显示的是**张数**，不是标签数
    /// 两个口径必须一致：徽标上的 N 张要等于列表里的条数。
    func testCountMatchesEntries() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let repo = StickerImportRepository(database: database)
        let library = StickerLibraryRepository(database: database)

        XCTAssertEqual(try library.count(), 0, "空库应当是 0（徽标显示「0 张」，而不是 —）")

        for index in 0..<3 {
            let bytes = Data([0x89, 0x50, 0x4E, 0x47] + Array(repeating: UInt8(index), count: 8))
            _ = try repo.importSticker(data: bytes, fileName: "custom_\(index)_1.png",
                                       description: "表情 \(index)", userTags: [])
        }
        XCTAssertEqual(try library.count(), 3)
        XCTAssertEqual(try library.count(), try library.entries().count, "两个口径必须一致")
    }
}
