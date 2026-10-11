import XCTest

/// 备份/恢复的结果展示（第 215 轮）。
///
/// ## 这套测试锁的是什么
/// 主要是**一处会撒谎的 UI**：导入失败原本被写成
/// `status = .ok(outcome.summary)` —— 从不看 `outcome.ok`，
/// 于是"解密失败：密码错误"会带**绿色对勾**显示出来。
/// 抽成纯函数后，这条映射就能被断言钉住。
/// 另外还锁"字典顺序"：分节计数来自 `[String: Int]`，不排序会让每次渲染顺序不同。
@testable import YuNian
final class BackupReportTests: XCTestCase {

    private func importDetail(companions: Int = 1, matched: Int = 0, groups: Int = 0,
                              chat: Int = 0, groupMsgs: Int = 0, memories: Int = 0,
                              unified: Int = 0, temp: Int = 0, token: Int = 0,
                              diaries: Int = 0) -> BackupImporter.Result {
        var result = BackupImporter.Result()
        result.companionsCreated = companions
        result.companionsMatched = matched
        result.groupsCreated = groups
        result.chatMessagesInserted = chat
        result.groupMessagesInserted = groupMsgs
        result.memoriesInserted = memories
        result.unifiedMemoriesInserted = unified
        result.tempMemoriesInserted = temp
        result.tokenUsagesInserted = token
        result.diariesInserted = diaries
        return result
    }

    // MARK: - 导入：失败绝不能被当成成功

    func testFailedImportIsReportedAsFailure() {
        let outcome = BackupImportService.Outcome(
            ok: false, message: "解密失败：密码错误或文件已损坏", detail: nil)
        let report = BackupReport.imported(outcome)

        XCTAssertFalse(report.ok, "失败必须是失败 —— 否则界面上是绿色对勾")
        XCTAssertTrue(report.title.contains("导入未完成"))
        XCTAssertTrue(report.title.contains("密码错误"))
        XCTAssertTrue(report.lines.isEmpty)
        XCTAssertNotNil(report.hint, "失败要给出下一步（确认口令后可重试）")
    }

    func testFailedImportWithoutMessageStillReadable() {
        let report = BackupReport.imported(
            BackupImportService.Outcome(ok: false, message: "", detail: nil))
        XCTAssertFalse(report.ok)
        XCTAssertEqual(report.title, "导入未完成")
    }

    func testSuccessfulImportListsPerSectionCounts() {
        let outcome = BackupImportService.Outcome(
            ok: true, message: "导入完成",
            detail: importDetail(companions: 2, matched: 1, groups: 1, chat: 30,
                                 groupMsgs: 4, memories: 5, unified: 6, temp: 7,
                                 token: 8, diaries: 9))
        let report = BackupReport.imported(outcome)

        XCTAssertTrue(report.ok)
        XCTAssertEqual(report.title, "导入完成")
        let map = Dictionary(uniqueKeysWithValues: report.lines.map { ($0.label, $0.value) })
        XCTAssertEqual(map["伴侣"], "新增 2 · 匹配 1")
        XCTAssertEqual(map["群组"], "新增 1")
        XCTAssertEqual(map["聊天消息"], "新增 30")
        XCTAssertEqual(map["群消息"], "新增 4")
        XCTAssertEqual(map["记忆"], "新增 5")
        XCTAssertEqual(map["统一记忆"], "新增 6")
        XCTAssertEqual(map["临时记忆"], "新增 7")
        XCTAssertEqual(map["用量记录"], "新增 8")
        XCTAssertEqual(map["日记"], "新增 9")
        XCTAssertEqual(report.lines.count, 9)
    }

    /// 成功但没带明细（后端没回 detail）→ 仍然是成功，只是没有明细行。
    func testSuccessfulImportWithoutDetail() {
        let report = BackupReport.imported(
            BackupImportService.Outcome(ok: true, message: "导入完成", detail: nil))
        XCTAssertTrue(report.ok)
        XCTAssertTrue(report.lines.isEmpty)
    }

    // MARK: - 导出

    func testExportReportShowsTotalsAndSections() {
        let counts = ["companions": 3, "chatMessages": 120, "diaries": 0]
        let report = BackupReport.export(
            BackupExporter.Outcome(sectionCounts: counts, plaintextBytes: 2048, containerBytes: 3072))

        XCTAssertTrue(report.ok)
        XCTAssertTrue(report.title.contains("123"), "总条数取各分区之和：\(report.title)")
        let map = Dictionary(uniqueKeysWithValues: report.lines.map { ($0.label, $0.value) })
        XCTAssertEqual(map["分区"], "3 个")
        XCTAssertEqual(map["明文体积"], "2.0 KB")
        XCTAssertEqual(map["加密后"], "3.0 KB")
        XCTAssertEqual(map["伴侣"], "3 条")
        XCTAssertEqual(map["聊天消息"], "120 条")
        XCTAssertEqual(map["日记"], "0 条", "0 也要显示 —— 用户要知道这类到底有没有带过来")
        XCTAssertTrue(report.hint?.contains("口令") ?? false)
    }

    // MARK: - 分节顺序与文案

    /// 顺序必须**稳定**：先按固定的 `sectionKeys`，未知分区按名字排在后面。
    func testSectionLinesOrderIsStable() {
        let counts = ["diaries": 1, "companions": 1, "zzz_unknown": 1, "aaa_unknown": 1,
                      "chatMessages": 1]
        let labels = BackupReport.sectionLines(counts).map(\.label)

        XCTAssertEqual(labels.first, "伴侣", "sectionKeys 的第一个")
        XCTAssertEqual(labels.dropLast(2), ["伴侣", "聊天消息", "日记"],
                       "已知分区按 sectionKeys 顺序，不是字典顺序")
        XCTAssertEqual(Array(labels.suffix(2)), ["aaa_unknown", "zzz_unknown"],
                       "未知分区按名字排序补在后面")
    }

    func testSectionLinesIgnoresMissingSections() {
        XCTAssertTrue(BackupReport.sectionLines([:]).isEmpty)
    }

    func testSectionTitlesCoverAllSectionKeysAndFallBack() {
        for key in BackupExporter.sectionKeys {
            XCTAssertNotEqual(BackupReport.sectionTitle(key), key,
                              "sectionKeys 里的每个分区都应有中文名（新增分区要补这里）")
        }
        XCTAssertEqual(BackupReport.sectionTitle("brand_new"), "brand_new")
    }

    // MARK: - 体积格式化

    func testByteFormatting() {
        XCTAssertEqual(BackupReport.bytes(0), "0 B")
        XCTAssertEqual(BackupReport.bytes(1023), "1023 B")
        XCTAssertEqual(BackupReport.bytes(1024), "1.0 KB")
        XCTAssertEqual(BackupReport.bytes(1536), "1.5 KB")
        XCTAssertEqual(BackupReport.bytes(1_048_576), "1.0 MB")
        XCTAssertEqual(BackupReport.bytes(1_073_741_824), "1.0 GB")
    }

    // MARK: - 抛异常那条路

    func testGenericFailureReport() {
        let report = BackupReport.failure("读不到这个文件")
        XCTAssertFalse(report.ok)
        XCTAssertEqual(report.title, "读不到这个文件")
        XCTAssertTrue(report.lines.isEmpty)
        XCTAssertNil(report.hint)
    }
}
