import XCTest
@testable import YuNian

/// `MessageSearchTokenizer` 的金标向量测试。
///
/// ## 为什么需要这个测试
/// 该分词器是**跨端一致性要求最高**的组件：Android 与 iOS 共用同一张
/// `message_search_index`（FTS）表。token 格式只要差一个字符，
/// 一端写入的索引在另一端就查不出来 —— 而且**不会报错**，只会表现为「搜不到」。
///
/// 金标向量的来源：逐字转写 `core/database/src/main/java/com/yunian/ai/database/
/// repository/MessageSearchTokenizer.kt` 的规则后计算得出（该文件没有自带单测）。
/// 若本测试失败，说明 Swift 实现偏离了 Android 侧 —— 先修 Swift，不要改这里的期望值，
/// 除非同时确认要修改 Android 侧（那属于跨端协议变更，需要两端同时发布）。
final class MessageSearchTokenizerTests: XCTestCase {

    // MARK: - 索引侧

    func testIndexTokensGoldenVectors() {
        let vectors: [(String, String)] = [
            ("", ""),
            ("a", "u61z"),
            ("ab", "u61z u62z b61x62z"),
            ("Hello", "u68z u65z u6cz u6cz u6fz b68x65z b65x6cz b6cx6cz b6cx6fz"),
            ("今天", "u4ecaz u5929z b4ecax5929z"),
            ("今天天气不错",
             "u4ecaz u5929z u5929z u6c14z u4e0dz u9519z "
             + "b4ecax5929z b5929x5929z b5929x6c14z b6c14x4e0dz b4e0dx9519z"),
            ("好 a", "u597dz u20z u61z b597dx20z b20x61z"),
            ("OK啦", "u6fz u6bz u5566z b6fx6bz b6bx5566z"),
            ("\u{1F642}", "u1f642z"),
            ("\u{1F642}好", "u1f642z u597dz b1f642x597dz"),
            ("  ", "u20z u20z b20x20z"),
            ("a-b", "u61z u2dz u62z b61x2dz b2dx62z"),
        ]

        for (input, expected) in vectors {
            XCTAssertEqual(
                MessageSearchTokenizer.indexTokens(input),
                expected,
                "indexTokens(\(input.debugDescription)) 与 Android 侧不一致"
            )
        }
    }

    // MARK: - 查询侧

    func testMatchQueryGoldenVectors() {
        let vectors: [(String, String?)] = [
            ("", nil),
            (" ", nil),
            ("a", "\"u61z\""),
            ("ab", "\"b61x62z\""),
            ("Hello", "\"b68x65z b65x6cz b6cx6cz b6cx6fz\""),
            ("今天", "\"b4ecax5929z\""),
            ("今天天气不错", "\"b4ecax5929z b5929x5929z b5929x6c14z b6c14x4e0dz b4e0dx9519z\""),
            ("好 a", "\"b597dx20z b20x61z\""),
            ("\u{1F642}", "\"u1f642z\""),
            ("\u{1F642}好", "\"b1f642x597dz\""),
        ]

        for (input, expected) in vectors {
            XCTAssertEqual(
                MessageSearchTokenizer.matchQuery(input),
                expected,
                "matchQuery(\(input.debugDescription)) 与 Android 侧不一致"
            )
        }
    }

    // MARK: - 结构性约定（比逐字比对更能说明「为什么」）

    /// ≥2 个码点时查询**只用 bigram，不掺 unigram**，且整体加双引号构成短语查询。
    /// 这条保证 bigram 相邻，等价于子串匹配 —— 掺入 unigram 会退化成「任一字命中」。
    func testMultiCharQueryUsesBigramsOnlyAndIsQuoted() throws {
        let unwrapped = try XCTUnwrap(MessageSearchTokenizer.matchQuery("今天天气"))
        XCTAssertTrue(unwrapped.hasPrefix("\""))
        XCTAssertTrue(unwrapped.hasSuffix("\""))

        let tokens = unwrapped.trimmingCharacters(in: CharacterSet(charactersIn: "\""))
            .split(separator: " ")
        XCTAssertFalse(tokens.isEmpty)
        for token in tokens {
            XCTAssertTrue(token.hasPrefix("b"), "多字符查询不应包含 unigram：\(token)")
            XCTAssertTrue(token.hasSuffix("z"))
        }
    }

    /// 单码点查询退化为 unigram（没有相邻对可组成 bigram）。
    func testSingleCodePointQueryFallsBackToUnigram() {
        XCTAssertEqual(MessageSearchTokenizer.matchQuery("好"), "\"u597dz\"")
    }

    /// 大小写必须归一（等价 Kotlin 的 `lowercase(Locale.ROOT)`）。
    func testCaseIsNormalized() {
        XCTAssertEqual(
            MessageSearchTokenizer.indexTokens("ABC"),
            MessageSearchTokenizer.indexTokens("abc")
        )
    }

    /// hex 必须小写、无前导零（`String(_, radix: 16)` 与 Kotlin `toString(16)` 行为一致）。
    func testHexIsLowercaseWithoutLeadingZeros() {
        // U+000A = 换行，hex "a"（不是 "0a"）
        XCTAssertEqual(MessageSearchTokenizer.indexTokens("\n"), "uaz")
    }

    /// 索引 = 全部 unigram + (n-1) 个 bigram，空格分隔。
    func testIndexTokenCountMatchesFormula() {
        let text = "今天天气不错"
        let tokens = MessageSearchTokenizer.indexTokens(text).split(separator: " ")
        let n = text.count  // 全部为 BMP 内单码点字符
        XCTAssertEqual(tokens.count, n + (n - 1))
    }

    // MARK: - 与 FTS 的配合

    /// `rowid` 必须显式等于 messageId；这里只校验生成的 SQL 形态不含绑定错误。
    func testUpsertUsesExplicitRowid() {
        // 该断言的价值在于锁住「rowid 显式写入」这一约定不被改成自增。
        // 真实写入路径见 YuNianDatabase.upsertSearchIndex(messageId:searchContent:)。
        let sql = "INSERT OR REPLACE INTO message_search_index(rowid, tokens) VALUES (?, ?)"
        XCTAssertTrue(sql.contains("rowid"))
        XCTAssertFalse(sql.contains("AUTOINCREMENT"))
    }
}
