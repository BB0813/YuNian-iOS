import XCTest
@testable import YuNian

/// `SkillContentParser` 的行为测试。
///
/// 这个解析器虽小，但**两处关键路径都依赖它**：
///   1. `getSkillContent` 返回给模型的是剥离 frontmatter 后的 body
///   2. `saveSkill` 用解析出的 name / description 覆盖索引字段
/// 所以必须与 Android `SkillContentParser` 逐字等价。
///
/// 纯函数、无平台依赖 —— 这类代码是本项目里少数能在没有 macOS 时**确定正确**的部分。
final class SkillContentParserTests: XCTestCase {

    private func parse(_ s: String) -> SkillContentParser.ParsedSkill {
        SkillContentParser.parse(s)
    }

    // MARK: - 无 frontmatter 的情形

    func testPlainTextIsReturnedUnchanged() {
        let r = parse("hello world")
        XCTAssertNil(r.name)
        XCTAssertNil(r.description)
        XCTAssertEqual(r.body, "hello world")
    }

    /// 少于 3 行不可能构成 frontmatter（开标记 + 至少一项 + 闭标记）
    func testTooFewLinesIsTreatedAsPlainText() {
        let twoLines = "---\nname: X"
        let r = parse(twoLines)
        XCTAssertNil(r.name)
        XCTAssertEqual(r.body, twoLines, "原样返回，不做任何剥离")
    }

    func testFirstLineMustBeDelimiter() {
        let r = parse("name: X\n---\nbody")
        XCTAssertNil(r.name)
        XCTAssertEqual(r.body, "name: X\n---\nbody")
    }

    func testMissingClosingDelimiterIsTreatedAsPlainText() {
        let s = "---\nname: X\ndescription: Y\nbody without close"
        let r = parse(s)
        XCTAssertNil(r.name)
        XCTAssertNil(r.description)
        XCTAssertEqual(r.body, s, "没有闭合 --- 时整段按正文处理")
    }

    // MARK: - 正常解析

    func testBasicFrontmatter() {
        let r = parse("""
        ---
        name: TestSkill
        description: A test skill
        ---
        Body text
        """)
        XCTAssertEqual(r.name, "TestSkill")
        XCTAssertEqual(r.description, "A test skill")
        XCTAssertEqual(r.body, "Body text")
    }

    func testBodyKeepsInternalNewlinesAndIsTrimmed() {
        let r = parse("---\nname: X\n---\n\nline1\nline2\n\n")
        XCTAssertEqual(r.body, "line1\nline2", "两端空白被 trim，内部换行保留")
    }

    func testEmptyFrontmatterYieldsBody() {
        let r = parse("---\n---\nBody")
        XCTAssertNil(r.name)
        XCTAssertNil(r.description)
        XCTAssertEqual(r.body, "Body")
    }

    /// 只识别 name / description，其它键忽略但不影响解析。
    func testUnknownKeysAreIgnored() {
        let r = parse("---\nversion: 3\nauthor: someone\nname: Kept\n---\nB")
        XCTAssertEqual(r.name, "Kept")
        XCTAssertNil(r.description)
        XCTAssertEqual(r.body, "B")
    }

    /// 注释行与空行跳过。
    func testCommentsAndBlankLinesAreSkipped() {
        let r = parse("---\n# a comment\n\nname: N\n---\nB")
        XCTAssertEqual(r.name, "N")
        XCTAssertEqual(r.body, "B")
    }

    // MARK: - 取值细节

    /// Kotlin 是 `trim('"').trim('\'')`：依次剥离双引号、再剥单引号。
    func testQuotesAreStripped() {
        XCTAssertEqual(parse("---\nname: \"Double\"\n---\nB").name, "Double")
        XCTAssertEqual(parse("---\nname: 'Single'\n---\nB").name, "Single")
        XCTAssertEqual(parse("---\nname: \"'Both'\"\n---\nB").name, "Both")
        XCTAssertEqual(parse("---\nname:   Spaced   \n---\nB").name, "Spaced")
    }

    /// 必须用**第一个**冒号切分 —— 值里可以含冒号（如 URL）。
    func testValueMayContainColons() {
        let r = parse("---\ndescription: see https://example.com/x\n---\nB")
        XCTAssertEqual(r.description, "see https://example.com/x")
    }

    /// 冒号在行首（key 为空）时该行被忽略（Kotlin: `if (sep <= 0) continue`）。
    func testLeadingColonLineIsIgnored() {
        let r = parse("---\n: novalue\nname: N\n---\nB")
        XCTAssertEqual(r.name, "N")
    }

    /// 值可以为空字符串（Kotlin 不做非空判断）。
    func testEmptyValueIsPreserved() {
        let r = parse("---\nname:\ndescription:\n---\nB")
        XCTAssertEqual(r.name, "")
        XCTAssertEqual(r.description, "")
    }

    // MARK: - 换行兼容

    /// Kotlin 的 `lines()` 会把 \r\n 与 \r 都当换行；
    /// 若不归一化，CRLF 文件的正文会带上 \r。
    func testCRLFIsHandledLikeKotlinLines() {
        let r = parse("---\r\nname: Win\r\n---\r\nBody line\r\nSecond")
        XCTAssertEqual(r.name, "Win")
        XCTAssertEqual(r.body, "Body line\nSecond", "正文里不应残留 \\r")
    }

    /// 闭标记前后允许空白（Kotlin 用 `lines[i].trim() == "---"`）。
    func testDelimitersAllowSurroundingWhitespace() {
        let r = parse("  ---  \nname: X\n\t---\t\nB")
        XCTAssertEqual(r.name, "X")
        XCTAssertEqual(r.body, "B")
    }

    /// 第一行的判定同样允许空白。
    func testFirstLineAllowsWhitespace() {
        let r = parse("---   \nname: X\n---\nB")
        XCTAssertEqual(r.name, "X")
    }
}
