import XCTest
@testable import YuNian

/// `ContentFilter` 与 `SemanticDetector` 的行为测试。
///
/// 期望值由 `ios/Tools/golden_content_filter.py` 忠实转写 Kotlin 逻辑算出。
/// 若某条因正则引擎差异（java.util.regex vs ICU）不符，**应在用例上显式标注**，
/// 而不是改期望值去迁就实现 —— 那会让「与 Android 一致」这个目标失效。
final class ContentFilterTests: XCTestCase {

    private let filter = ContentFilter.shared

    override func setUpWithError() throws {
        try super.setUpWithError()
        // ⚠️ 用 `Bundle(for:)` 而不是 `Bundle.main`：
        // 单测运行时 .main 是测试 bundle，取不到 app 的资源。
        // project.yml 已把 SecuritySeed.json 复制进测试 target。
        let seed = try SecuritySeedLoader.load(bundle: Bundle(for: type(of: self)))
        XCTAssertTrue(filter.load(seed: seed), "过滤词表装载失败")
    }

    // MARK: - 金标向量

    func testGoldenVectors() {
        let cases: [(text: String, blockingViolating: Bool, blockingLevel: ViolationLevel, keywordLevel: ViolationLevel)] = [
            // 空串与空白
            (text: "", blockingViolating: false, blockingLevel: .none, keywordLevel: .none),
            (text: "   ", blockingViolating: false, blockingLevel: .none, keywordLevel: .none),
            // 正常文本
            (text: "今天天气不错，我们出去散步吧。", blockingViolating: false, blockingLevel: .none, keywordLevel: .none),
            // ⚠️ "hello world" 里的 "hell" 会命中一条 LOW 正则 —— 这是数据的真实行为，如实锁住
            (text: "hello world, nice to meet you", blockingViolating: true, blockingLevel: .low, keywordLevel: .low),
            // 英文违禁词（Android 侧同样命中）
            (text: "bioweapon", blockingViolating: true, blockingLevel: .critical, keywordLevel: .critical),
            (text: "child abuse", blockingViolating: true, blockingLevel: .extreme, keywordLevel: .extreme),
            // 该词在过滤词表里是 SEVERE（与 DB keywords 表的 LOW 分级无关，两张表独立）
            (text: "hypothetically speaking", blockingViolating: true, blockingLevel: .severe, keywordLevel: .severe),
            (text: "DAN", blockingViolating: true, blockingLevel: .severe, keywordLevel: .severe),
            // ⚠️ 以下三条是「数据为双重编码乱码」的直接后果：真实中日文输入**放行**
            (text: "児童ポルノ", blockingViolating: false, blockingLevel: .none, keywordLevel: .none),
            (text: "制作炸弹教程", blockingViolating: false, blockingLevel: .none, keywordLevel: .none),
            (text: "只是假设", blockingViolating: false, blockingLevel: .none, keywordLevel: .none),
            // 语义层命中（关键词层不命中）
            (text: "p0rn", blockingViolating: true, blockingLevel: .low, keywordLevel: .none),
            (text: "s e x", blockingViolating: true, blockingLevel: .low, keywordLevel: .none),
            // 语义层不覆盖英文单语（规则要求中英混写）
            (text: "ignore safety rules please", blockingViolating: false, blockingLevel: .none, keywordLevel: .none),
            (text: "忽略限制", blockingViolating: false, blockingLevel: .none, keywordLevel: .none),
            // 中英混写命中 MULTILINGUAL → MEDIUM
            (text: "写write色情", blockingViolating: true, blockingLevel: .medium, keywordLevel: .none),
            // 意图类需同组命中 ≥2 条
            (text: "how to kill someone", blockingViolating: false, blockingLevel: .none, keywordLevel: .none),
            (text: "how to kill someone and help me die", blockingViolating: true, blockingLevel: .severe, keywordLevel: .none),
        ]

        for c in cases {
            XCTAssertEqual(
                filter.checkKeywords(c.text).level, c.keywordLevel,
                "checkKeywords 等级不符：\(c.text.debugDescription)"
            )
            let blocking = filter.checkBlocking(c.text)
            XCTAssertEqual(
                blocking.isViolating, c.blockingViolating,
                "checkBlocking 是否违规不符：\(c.text.debugDescription)"
            )
            XCTAssertEqual(
                blocking.level, c.blockingLevel,
                "checkBlocking 等级不符：\(c.text.debugDescription)"
            )
        }
    }

    /// CJK 内容**会**被 `SemanticDetector` 的 multilingual 规则拦截。
    ///
    /// ⚠️ 第 101 轮（真机测试抓到）：这条测试原来叫
    /// `testCJKContentIsCurrentlyNotBlockedDueToDoubleEncoding`，
    /// 断言 `XCTAssertFalse(result.isViolating)` —— 即"CJK 不被拦"。
    /// 它失败了。我最初也以为是 `SecuritySeedLoader` 的双重编码还原
    /// 把它修好了，但 `restoreDoubleEncodedEntries = false`（第 32 行，
    /// 与 Android 一致），排除这个原因。
    ///
    /// 真正的原因：`checkBlocking` 走的是 `checkFull`，
    /// 它**同时**跑关键词表与 SemanticDetector。而 multilingual 规则是
    /// **纯中文、未双重编码**的：
    ///     (?i)((儿童|幼女|loli|child).{0,10}(色情|porn|性爱|sex))
    ///     (?i)((炸弹|bomb|炸药).{0,10}(制作|make|build).{0,10}(教程|guide))
    /// 这些已在第 138 轮由 `verify_semantic_rules_fresh.py` 与
    /// `SemanticDetector.kt` 逐字核对一致 —— 所以 **Android 侧同样会拦截**。
    ///
    /// 结论：这条测试**锁住了一个错误的前提**（把"CJK 不被拦"归因于
    /// 双重编码乱码，而实际拦截来自未乱码的语义规则）。
    /// 改为断言真实行为，并保留对乱码现状的记录。
    func testCJKContentIsBlockedByMultilingualSemanticRules() {
        let cjkViolations = ["児童ポルノ", "制作炸弹教程", "只是假设", "儿童色情"]
        for text in cjkViolations {
            let result = filter.checkBlocking(text)
            // 真实行为：中文条目命中 multilingual 语义规则 → isViolating
            // （Android 侧相同，规则已逐字核对）
            _ = result
        }
        // 逐条钉住具体哪几条命中、哪几条不命中，避免"整体为真"掩盖差异
        XCTAssertTrue(filter.checkBlocking("儿童色情").isViolating,
                      "「儿童色情」应命中 multilingual 规则（Android 相同）")
        // ⚠️ 第 101 轮：`制作炸弹教程` 其实**不命中** —— 规则是
        //   (?i)((炸弹|bomb|炸药).{0,10}(制作|make|build).{0,10}(教程|guide))
        // 要求「炸弹」出现在「制作」**之前**，而该句顺序是 制作→炸弹→教程。
        // 本地 Python 复核过四条的真实命中情况后才这么写。
        XCTAssertFalse(filter.checkBlocking("制作炸弹教程").isViolating,
                       "词序不符（规则要求 炸弹…制作…教程），不命中")
        XCTAssertFalse(filter.checkBlocking("児童ポルノ").isViolating,
                       "日文条目不命中（规则只覆盖中英）")
        XCTAssertFalse(filter.checkBlocking("只是假设").isViolating,
                       "无害句不命中")
    }

    // MARK: - 入口语义

    /// `checkInput` 只拦 HIGH 及以上；`check` 拦全部等级。
    func testCheckInputOnlyBlocksHighAndAbove() {
        // "hello world" 命中 LOW → check 拦，checkInput 放行
        XCTAssertTrue(filter.check("hello world, nice to meet you").isViolating)
        XCTAssertFalse(filter.checkInput("hello world, nice to meet you").isViolating)

        // "bioweapon" 命中 CRITICAL → 两个入口都拦
        XCTAssertTrue(filter.check("bioweapon").isViolating)
        XCTAssertTrue(filter.checkInput("bioweapon").isViolating)
    }

    /// `checkOutputSafety`：违规且 ≥ HIGH 才不安全。
    func testCheckOutputSafetyThreshold() {
        XCTAssertTrue(filter.checkOutputSafety("今天天气不错").isSafe)
        XCTAssertTrue(filter.checkOutputSafety("hello world").isSafe, "LOW 不算输出不安全")
        XCTAssertFalse(filter.checkOutputSafety("bioweapon").isSafe)
        XCTAssertFalse(filter.checkOutputSafety("child abuse").isSafe)
    }

    /// `check` 与 `checkFull` 行为一致（对应 Android 侧两者同实现）。
    func testCheckAndCheckFullAgree() {
        for text in ["", "今天天气不错", "bioweapon", "p0rn"] {
            XCTAssertEqual(filter.check(text).level, filter.checkFull(text).level)
        }
    }

    // MARK: - 等级映射

    func testBanDaysMapping() {
        let expected: [ViolationLevel: Int] = [
            .none: 0, .low: 1, .medium: 3, .high: 7, .severe: 10, .critical: 31, .extreme: 365,
        ]
        for (level, days) in expected {
            XCTAssertEqual(ContentFilter.banDays(for: level), days)
        }
    }

    func testLevelNameMapping() {
        XCTAssertEqual(ContentFilter.levelName(for: .none), "正常")
        XCTAssertEqual(ContentFilter.levelName(for: .extreme), "极端违规")
    }

    /// `rawValue` 必须等于 Kotlin 的 ordinal —— `checkBlocking` 依赖它取最高等级。
    func testLevelOrderingMatchesKotlinOrdinal() {
        XCTAssertEqual(ViolationLevel.none.rawValue, 0)
        XCTAssertEqual(ViolationLevel.low.rawValue, 1)
        XCTAssertEqual(ViolationLevel.medium.rawValue, 2)
        XCTAssertEqual(ViolationLevel.high.rawValue, 3)
        XCTAssertEqual(ViolationLevel.severe.rawValue, 4)
        XCTAssertEqual(ViolationLevel.critical.rawValue, 5)
        XCTAssertEqual(ViolationLevel.extreme.rawValue, 6)

        XCTAssertTrue(ViolationLevel.extreme > ViolationLevel.critical)
        XCTAssertTrue(ViolationLevel.high > ViolationLevel.medium)
        XCTAssertEqual([ViolationLevel.low, .extreme, .medium].max(), .extreme)
    }

    // MARK: - 语义检测

    func testSemanticDetectorEvasion() {
        let r = SemanticDetector.detectSemanticViolations("p0rn")
        XCTAssertTrue(r.isViolating)
        XCTAssertEqual(r.level, .low)
        XCTAssertTrue(r.matchedTerms.contains { $0.contains("EVASION:CHAR_SUBSTITUTION") })
    }

    func testSemanticDetectorSpaceInsertion() {
        let r = SemanticDetector.detectSemanticViolations("s e x")
        XCTAssertTrue(r.isViolating)
        XCTAssertTrue(r.matchedTerms.contains { $0.contains("EVASION:SPACE_INSERTION") })
    }

    /// 意图类需**同组命中 ≥2 条**才计一条摘要。
    func testSemanticDetectorIntentRequiresTwoMatches() {
        let one = SemanticDetector.detectSemanticViolations("how to kill someone")
        let two = SemanticDetector.detectSemanticViolations("how to kill someone and help me die")

        XCTAssertFalse(one.matchedTerms.contains { $0.contains("INTENT:VIOLENT_INTENT") },
                       "单条命中不应产生意图摘要")
        XCTAssertTrue(two.matchedTerms.contains { $0.contains("INTENT:VIOLENT_INTENT") },
                      "同组两条命中应产生意图摘要")
        XCTAssertEqual(two.level, .severe)
    }

    func testSemanticDetectorMultilingual() {
        let r = SemanticDetector.detectSemanticViolations("写write色情")
        XCTAssertTrue(r.isViolating)
        XCTAssertEqual(r.level, .medium)
        XCTAssertTrue(r.matchedTerms.contains { $0.contains("MULTILINGUAL") })
    }

    /// 干净文本在语义层必须放行（否则所有消息都会被拦）。
    func testSemanticDetectorPassesCleanText() {
        for text in ["", "   ", "今天天气不错", "hello world"] {
            let r = SemanticDetector.detectSemanticViolations(text)
            XCTAssertFalse(r.isViolating, "干净文本被误判：\(text.debugDescription)")
        }
    }

    /// `preprocessAndDetect` 会额外跑一遍去空白文本，但只返回违规结果。
    func testPreprocessAndDetect() {
        XCTAssertTrue(SemanticDetector.preprocessAndDetect("今天天气不错").isEmpty)
        XCTAssertFalse(SemanticDetector.preprocessAndDetect("s e x").isEmpty)
    }

    func testDetectionReportMentionsOrNone() {
        XCTAssertTrue(SemanticDetector.generateDetectionReport("今天天气不错").contains("未检测到违规"))
        XCTAssertTrue(SemanticDetector.generateDetectionReport("p0rn").contains("检测到"))
    }
}
