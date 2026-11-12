import XCTest
@testable import YuNian

/// `ToolTrim` 的规则测试（第 242 轮）。
///
/// 这组测试守的是**保守性**，不是"省得够不够多"：
/// 自动裁剪一旦误判，用户会在"真要用时发现工具没了"，
/// 而那种失败**很难归因**（用户不会想到是裁剪）。
final class ToolTrimTests: XCTestCase {

    // MARK: - 不裁的情形（最重要的几条）

    func test_chatAndMemoryAreNeverTrimmed() {
        // 一句最普通的话，什么线索都没有
        let d = ToolTrim.decide(userText: "今天好累啊")
        // 只允许裁 device；chat / memory 永远不在可裁集合里
        XCTAssertEqual(d.droppedToolsets, ["device"])
        XCTAssertFalse(ToolTrim.trimmableToolsets.contains("chat"),
                       "chat 是产品地基，永远不能裁")
        XCTAssertFalse(ToolTrim.trimmableToolsets.contains("memory"),
                       "memory 猜错会永久丢记忆（不可逆），永远不能裁")
    }

    func test_emptyTextKeepsEverythingConservative() {
        // 空输入（比如纯图片消息）也要按"没有设备意图"处理，
        // 但不应该崩，也不该裁掉 chat/memory
        let d = ToolTrim.decide(userText: "")
        XCTAssertEqual(d.droppedToolsets, ["device"])
    }

    // MARK: - 命中设备线索 → 整组保留

    func test_timeQuestionKeepsDeviceTools() {
        let d = ToolTrim.decide(userText: "现在几点了")
        XCTAssertTrue(d.droppedToolsets.isEmpty, "问时间必须保留 device 组")
        XCTAssertTrue(d.matchedCues.contains("几点"))
    }

    func test_clipboardIntentKeepsDeviceTools() {
        let d = ToolTrim.decide(userText: "把这段话复制到剪贴板")
        XCTAssertTrue(d.droppedToolsets.isEmpty)
    }

    func test_batteryKeepsDeviceTools() {
        let d = ToolTrim.decide(userText: "手机还有多少电量")
        XCTAssertTrue(d.droppedToolsets.isEmpty)
    }

    func test_reminderKeepsDeviceTools() {
        let d = ToolTrim.decide(userText: "提醒我十点吃药")
        XCTAssertTrue(d.droppedToolsets.isEmpty)
    }

    func test_openUrlKeepsDeviceTools() {
        let d = ToolTrim.decide(userText: "帮我打开 https://example.com")
        XCTAssertTrue(d.droppedToolsets.isEmpty)
    }

    func test_englishUrlCueIsCaseInsensitive() {
        let d = ToolTrim.decide(userText: "OPEN HTTP link please")
        XCTAssertTrue(d.droppedToolsets.isEmpty, "关键词匹配必须大小写无关")
    }

    // MARK: - 合并语义：只能并集，不能覆盖用户设置

    func test_mergeNeverResurrectsUserHiddenToolsets() {
        var settings = ToolSettings()
        settings.hiddenToolsets = ["memory", "skill"]

        // 这轮裁 device
        let d = ToolTrim.decide(userText: "今天好累")
        let merged = ToolTrim.merge(d, into: settings)

        // 用户关掉的必须还在
        XCTAssertTrue(merged.hiddenToolsets.contains("memory"))
        XCTAssertTrue(merged.hiddenToolsets.contains("skill"))
        // 自动裁的也加上
        XCTAssertTrue(merged.hiddenToolsets.contains("device"))
    }

    func test_mergeWithNoTrimReturnsSettingsUnchanged() {
        var settings = ToolSettings()
        settings.hiddenToolsets = ["skill"]

        // 命中线索 → 不裁
        let d = ToolTrim.decide(userText: "几点了")
        let merged = ToolTrim.merge(d, into: settings)

        XCTAssertEqual(merged.hiddenToolsets, ["skill"],
                       "不裁剪时不能动用户设置")
    }

    // MARK: - 解释文案

    func test_reasonExplainsWhyWhenTrimming() {
        let d = ToolTrim.decide(userText: "今天好累啊")
        XCTAssertTrue(d.isTrimming)
        XCTAssertFalse(d.reason.isEmpty, "用户要能看到这轮为什么少了工具")
    }

    func test_reasonNamesTheCueWhenKeeping() {
        let d = ToolTrim.decide(userText: "现在几点")
        XCTAssertFalse(d.isTrimming)
        XCTAssertTrue(d.reason.contains("几点") || d.reason.contains("保留"),
                      "保留时也该说明依据：\(d.reason)")
    }
}
