import XCTest
@testable import YuNian

/// `DialogueHistoryPolicy` 的行为测试 —— 与 Android `AiDialogueHistoryPolicy` 等价。
///
/// 期望值由 `AiDialoguePolicy.kt` 的逻辑转写后交叉验证得到（含下面这些边界情形）。
/// 这层清洗**不是可选的**：Rust 的两处注释明确说 `history_json` 是
/// 「Kotlin 侧 AiDialogueHistoryPolicy 产物」，即模型看到的是清洗过的历史。
final class DialogueHistoryPolicyTests: XCTestCase {

    private func sanitize(_ history: [(role: AgentHistoryRole, content: String)])
        -> [(role: AgentHistoryRole, content: String)]
    {
        DialogueHistoryPolicy.sanitizeForModel(
            history.map { AgentHistoryMessage(role: $0.role, content: $0.content) }
        ).map { ($0.role, $0.content) }
    }

    /// 把消息序列渲染成 [String]，用于 XCTAssertEqual。
    ///
    /// ⚠️ 第 92 轮：上一轮我的自动改写脚本把本函数**插进了 `sanitize` 的
    /// 签名中间**（在 `-> [...]` 之后、函数体的 `{` 之前），
    /// 结果 `sanitize` 的函数体失去归属 —— CI 报 "expected declaration"。
    /// 现在移到 sanitize 完整定义之后。
    ///
    /// 为什么需要它：[(role:content:)] 是**具名元组**数组，
    /// 而 Swift 元组不能遵循 Equatable（元组无法加 extension）——
    /// CI 曾报 6 处 "cannot conform to 'Equatable'"。
    /// 不改被测代码，只在测试侧渲染成可比较的字符串。
    private func render(_ msgs: [(role: AgentHistoryRole, content: String)]) -> [String] {
        msgs.map { "\($0.role.rawValue):\($0.content)" }
    }

    // MARK: - 过滤

    func testEmptyHistoryStaysEmpty() {
        XCTAssertTrue(sanitize([]).isEmpty)
    }

    func testBlankContentIsDropped() {
        XCTAssertTrue(sanitize([(.user, "   ")]).isEmpty)
        XCTAssertTrue(sanitize([(.user, "\u{200B}")]).isEmpty,
                      "零宽空格本身即视为空")
    }

    func testOperationalMessagesAreDropped() {
        XCTAssertTrue(sanitize([(.user, "网络连接超时")]).isEmpty)
        XCTAssertTrue(sanitize([(.user, "模型名未配置。")]).isEmpty,
                      "精确匹配也算操作性内容")
        XCTAssertTrue(sanitize([(.user, "[TOAST]发送失败")]).isEmpty,
                      "toast 前缀先剥离再判断")
        XCTAssertTrue(sanitize([(.user, "工具执行超时：xxx")]).isEmpty)
    }

    /// 空内容返回 **false**（不是操作性内容，只是空）——
    /// 这与直觉相反，但 Kotlin 明确 `if (text.isEmpty()) return false`。
    /// 由上一条「空白被丢」覆盖其最终效果，这里单独锁住 `isOperationalContent` 的语义。
    func testIsOperationalContentReturnsFalseForEmpty() {
        XCTAssertFalse(DialogueHistoryPolicy.isOperationalContent(""))
        XCTAssertFalse(DialogueHistoryPolicy.isOperationalContent("   "))
        XCTAssertFalse(DialogueHistoryPolicy.isOperationalContent("[TOAST]"))
    }

    func testNonOperationalTextIsKept() {
        XCTAssertEqual(
            render(sanitize([(.user, "你好"), (.assistant, "在的")])),
            render([(.user, "你好"), (.assistant, "在的")])
        )
    }

    /// ⚠️ 关键细节：判空/判操作性用的是**清洗后**的 content，
    /// 但保留下来的消息仍带**原始** content（含零宽空格）。
    /// 若把消息内容也替换成清洗后的版本，发往模型的内容就变了。
    func testZeroWidthSpaceIsStrippedForChecksButKeptInContent() {
        let result = sanitize([(.user, "\u{200B}hello")])
        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result[0].content, "\u{200B}hello",
                       "零宽空格不应被从消息内容里抹掉")
    }

    /// `[工具调用结果]` 但角色不是 TOOL 时，只保留 USER。
    func testToolResultWithoutToolRoleKeepsOnlyUser() {
        let result = sanitize([
            (.user, "[工具调用结果] x"),
            (.assistant, "[工具调用结果] y"),
        ])
        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result[0].role, .user)
    }

    /// 角色为 TOOL 的 `[工具调用结果]` 正常保留。
    func testToolResultWithToolRoleIsKept() {
        let result = sanitize([(.tool, "[工具调用结果] z")])
        XCTAssertEqual(result.count, 1)
        XCTAssertEqual(result[0].role, .tool)
    }

    // MARK: - 合并

    func testConsecutiveSameRoleAreMerged() {
        XCTAssertEqual(
            render(sanitize([(.user, "a"), (.user, "b"), (.assistant, "c")])),
            render([(.user, "a\nb"), (.assistant, "c")])
        )
    }

    func testMergeUsesTrimEndAndTrimStart() {
        XCTAssertEqual(
            render(sanitize([(.user, "a  "), (.user, "  b")])),
            render([(.user, "a\nb")])
        )
    }

    /// TOOL 角色**不参与**合并（Kotlin 的 `last.role != TOOL` 条件）。
    func testToolRoleIsNeverMerged() {
        XCTAssertEqual(
            render(sanitize([(.tool, "t1"), (.tool, "t2")])),
            render([(.tool, "t1"), (.tool, "t2")])
        )
    }

    func testThreeConsecutiveAreAllMerged() {
        XCTAssertEqual(
            render(sanitize([(.user, "a"), (.user, "b"), (.user, "c")])),
            render([(.user, "a\nb\nc")])
        )
    }

    /// 过滤在合并**之前**：被丢掉的消息不应触发合并逻辑。
    func testFilteringHappensBeforeMerging() {
        XCTAssertEqual(
            render(sanitize([(.user, "a"), (.user, "网络连接超时"), (.user, "b")])),
            render([(.user, "a\nb")]),
            "中间的操作性消息应被丢掉，然后 a/b 才合并"
        )
    }
}
