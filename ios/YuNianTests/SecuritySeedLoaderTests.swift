import XCTest
// ⚠️ 第 68 轮：本文件用 Int.fetchOne，`@testable import YuNian`
// 不会转出 GRDB 的类型。
import GRDB
@testable import YuNian

/// `SecuritySeedLoader` 的双重编码还原与数据装载测试。
///
/// ## 为什么这个测试重要
/// 安全基线数据里 94/284 条违禁词、317/583 条过滤正则，在 Android 侧就是
/// 「UTF-8 字节被按 Latin-1 解读」的乱码。iOS **如实保留**了该形态（默认不修复），
/// 因此：
///   1. 装载路径必须能正确读出这些乱码串（不能解码失败或截断）；
///   2. 还原函数必须正确，以便团队决定修复时一键启用；
///   3. 必须有一处测试把「当前是有意不修复」这件事固定下来，
///      避免以后有人「顺手修好」而造成两端过滤强度分歧。
final class SecuritySeedLoaderTests: XCTestCase {

    // MARK: - 双重编码还原

    /// 金标：`児童ポルノ` 的 UTF-8 字节被按 Latin-1 解读后，再还原回原文。
    func testRestoreDoubleEncodedGoldenVector() {
        let mojibake = "\u{00E5}\u{0085}\u{0090}\u{00E7}\u{00AB}\u{00A5}"
            + "\u{00E3}\u{0083}\u{009D}\u{00E3}\u{0083}\u{00AB}\u{00E3}\u{0083}\u{008E}"
        let expected = "\u{5150}\u{7AE5}\u{30DD}\u{30EB}\u{30CE}"   // 児童ポルノ

        XCTAssertEqual(mojibake.count, 15, "乱码形态应为 15 个字符")
        XCTAssertEqual(SecuritySeedLoader.restoreDoubleEncoded(mojibake), expected)
        XCTAssertEqual(expected.count, 5, "还原后应为 5 个字符")
    }

    /// ASCII 条目本就是正确的，还原必须原样返回（不能误伤）。
    func testRestoreLeavesASCIIUntouched() {
        for text in ["bioweapon", "baby porn", "(?i)(nsfw|adult)", "DAN", ""] {
            XCTAssertEqual(SecuritySeedLoader.restoreDoubleEncoded(text), text)
        }
    }

    /// 含 Latin-1 之外字符的串不可能是双重编码产物，必须原样返回。
    func testRestoreReturnsOriginalWhenNotRestorable() {
        let alreadyCorrect = "児童ポルノ"          // 正常日文，标量 > 0xFF
        XCTAssertEqual(SecuritySeedLoader.restoreDoubleEncoded(alreadyCorrect), alreadyCorrect)

        // 合法 Latin-1 但拼不出合法 UTF-8 的情况
        let notUTF8 = "\u{00FF}\u{00FE}"
        XCTAssertEqual(SecuritySeedLoader.restoreDoubleEncoded(notUTF8), notUTF8)
    }

    // MARK: - 装载

    func testLoadReadsBundledSeed() throws {
        // 该资源随 App 打包；若缺失说明 XcodeGen 没把 Resources/ 当资源收进去
        let seed = try SecuritySeedLoader.load(bundle: Bundle(for: type(of: self)))
        XCTAssertFalse(seed.keywords.isEmpty, "违禁词不应为空")
        XCTAssertFalse(seed.quizQuestions.isEmpty, "题目不应为空")
        XCTAssertFalse(seed.filterPatterns.isEmpty, "过滤词表不应为空")
    }

    /// 结构自检：等级与类型枚举值必须与 Android 侧一致。
    func testSeedLevelsAndTypesMatchAndroid() throws {
        let seed = try SecuritySeedLoader.load(bundle: Bundle(for: type(of: self)))
        let levels = Set(seed.keywords.map(\.level))
        XCTAssertTrue(
            levels.isSubset(of: ["EXTREME", "CRITICAL", "SEVERE", "HIGH", "MEDIUM", "LOW"]),
            "出现未知等级：\(levels)"
        )
        let types = Set(seed.keywords.map(\.type))
        XCTAssertEqual(types, ["PATTERN", "KEYWORD"])
        // PATTERN 必须带正则，KEYWORD 必须不带
        for k in seed.keywords {
            if k.type == "PATTERN" {
                XCTAssertNotNil(k.pattern, "PATTERN 条目应带 pattern：\(k.level)")
            } else {
                XCTAssertNil(k.pattern, "KEYWORD 条目不应带 pattern")
            }
        }
    }

    /// 过滤词表的等级键必须与 keywords 的等级集合一致。
    func testFilterPatternLevelsMatchKeywordLevels() throws {
        let seed = try SecuritySeedLoader.load(bundle: Bundle(for: type(of: self)))
        XCTAssertEqual(
            Set(seed.filterPatterns.keys),
            ["EXTREME", "CRITICAL", "SEVERE", "HIGH", "MEDIUM", "LOW"]
        )
    }

    /// **锁住「当前有意不修复」这件事。**
    ///
    /// 若将来把 `restoreDoubleEncodedEntries` 改成 true，本测试会失败 ——
    /// 这是刻意的：改这个开关必须同时改 Android 侧，否则两端过滤强度分歧。
    /// 届时请连同本测试与 README 一起更新。
    func testDoubleEncodedEntriesAreIntentionallyPreserved() throws {
        XCTAssertFalse(
            SecuritySeedLoader.restoreDoubleEncodedEntries,
            "启用还原前必须同时修改 Android 的 d() 等价实现，避免跨端过滤强度分歧"
        )

        let seed = try SecuritySeedLoader.load(bundle: Bundle(for: type(of: self)))
        XCTAssertGreaterThan(seed.diagnostics.keywordsDoubleEncoded, 0)
        XCTAssertGreaterThan(seed.diagnostics.filterPatternsDoubleEncoded, 0)

        // 默认不还原：装载结果里应当仍能找到「标量 ≤ 0xFF 且非 ASCII」的条目
        let stillMojibake = seed.keywords.contains { k in
            k.keyword.unicodeScalars.contains { $0.value > 0x7F && $0.value <= 0xFF }
        }
        XCTAssertTrue(stillMojibake, "默认配置下应保留乱码形态")
    }

    /// 诊断数字必须与数据自洽（生成器统计与装载结果不能打架）。
    func testDiagnosticsCountsAreConsistent() throws {
        let seed = try SecuritySeedLoader.load(bundle: Bundle(for: type(of: self)))
        XCTAssertEqual(seed.diagnostics.keywordsTotal, seed.keywords.count)

        let filterTotal = seed.filterPatterns.values.reduce(0) { $0 + $1.count }
        XCTAssertEqual(seed.diagnostics.filterPatternsTotal, filterTotal)
    }

    // MARK: - 播种

    func testSeedIfEmptyIsIdempotent() throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("yunian-seed-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let database = try YuNianDatabase(databaseURL: tmp.appendingPathComponent("yunian_database"))

        let first = try SecuritySeedLoader.seedIfEmpty(database)
        XCTAssertFalse(first.contains("+0"), "首次播种应写入数据：\(first)")

        let second = try SecuritySeedLoader.seedIfEmpty(database)
        XCTAssertEqual(second, "keywords +0 / quiz +0", "二次播种应因表非空而跳过")
    }

    /// 建库时应当已播种供应商预设（对应 Room onCreate），
    /// 与安全基线是两件事，容易混淆，这里一并锁住。
    func testDatabaseBootstrapSeedsProviderPresets() throws {
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("yunian-preset-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tmp) }

        let database = try YuNianDatabase(databaseURL: tmp.appendingPathComponent("yunian_database"))
        let count = try database.pool.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM api_provider_presets") ?? 0
        }
        XCTAssertEqual(count, YuNianSeed.apiProviderPresets.count)
        XCTAssertEqual(count, 13)
    }
}
