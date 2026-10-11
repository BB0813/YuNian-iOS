import GRDB
import XCTest

/// 本轮收据（P1）。
///
/// 这一套测试锁的都是**会静默出错**的地方：
/// 用量解析（多轮相加、缺字段）、审计行与用量行真的写进去了、
/// 收据的"两种口径"不被混成一个数、以及历史截断的方向。
@testable import YuNian
final class TurnReceiptTests: XCTestCase {

    private func makeDatabase() throws -> (YuNianDatabase, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("receipt_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return (try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database")), dir)
    }

    // MARK: - 用量解析

    func testUsageParsesStandardKeys() {
        let usage = TurnReceipt.Usage.parse(
            json: #"{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150}"#)
        XCTAssertEqual(usage?.promptTokens, 120)
        XCTAssertEqual(usage?.completionTokens, 30)
        XCTAssertEqual(usage?.totalTokens, 150)
        XCTAssertEqual(usage?.requests, 1)
    }

    /// 缺 `total_tokens` → 按输入+输出兜底（有些中转不给这一项）。
    func testUsageFallsBackToSumWhenTotalMissing() {
        let usage = TurnReceipt.Usage.parse(
            json: #"{"prompt_tokens":10,"completion_tokens":5}"#)
        XCTAssertEqual(usage?.totalTokens, 15)
    }

    /// 缺关键字段 / 非数字 → nil（宁可不显示，也不显示一个编出来的数）。
    func testUsageRejectsIncompleteOrInvalid() {
        XCTAssertNil(TurnReceipt.Usage.parse(json: #"{"completion_tokens":5}"#))
        XCTAssertNil(TurnReceipt.Usage.parse(json: #"{"prompt_tokens":5}"#))
        XCTAssertNil(TurnReceipt.Usage.parse(json: #"{"prompt_tokens":"很多","completion_tokens":5}"#))
        XCTAssertNil(TurnReceipt.Usage.parse(json: "not json"))
        XCTAssertNil(TurnReceipt.Usage.parse(json: ""))
    }

    /// **多轮要相加**：每一轮都把上下文重发了一遍，真实花费就是各轮之和。
    func testUsageSumsAcrossRounds() {
        let events = [
            AgentEvent(kind: "usage", text: #"{"prompt_tokens":100,"completion_tokens":10,"total_tokens":110}"#, extra: ""),
            AgentEvent(kind: "text", text: "忽略我", extra: ""),
            AgentEvent(kind: "usage", text: #"{"prompt_tokens":200,"completion_tokens":20,"total_tokens":220}"#, extra: ""),
        ]
        let usage = TurnReceipt.Usage.parseAll(events: events)
        XCTAssertEqual(usage?.promptTokens, 300)
        XCTAssertEqual(usage?.completionTokens, 30)
        XCTAssertEqual(usage?.totalTokens, 330)
        XCTAssertEqual(usage?.requests, 2, "两次请求要如实计两次")
    }

    /// 坏的那条事件被跳过，好的仍然算进去（一条脏数据不该让整轮无用量）。
    func testUsageSkipsMalformedEvents() {
        let events = [
            AgentEvent(kind: "usage", text: "{坏了", extra: ""),
            AgentEvent(kind: "usage", text: #"{"prompt_tokens":7,"completion_tokens":3}"#, extra: ""),
        ]
        XCTAssertEqual(TurnReceipt.Usage.parseAll(events: events)?.totalTokens, 10)
    }

    func testUsageNilWhenNoEvents() {
        XCTAssertNil(TurnReceipt.Usage.parseAll(events: [
            AgentEvent(kind: "sticker", text: "entry_id=1", extra: ""),
        ]))
        XCTAssertNil(TurnReceipt.Usage.parseAll(events: []))
    }

    // MARK: - 片段 JSON

    func testSegmentsRoundTrip() {
        let segments = [
            TurnReceipt.Segment(id: "persona.core", source: "persona", layer: 1,
                                lifetime: "stable", chars: 321),
            TurnReceipt.Segment(id: "memory.recall.companion_1", source: "memory", layer: 5,
                                lifetime: "task", chars: 88),
        ]
        let json = TurnReceiptStore.encodeSegments(segments)
        XCTAssertEqual(TurnReceiptStore.decodeSegments(json), segments)
    }

    func testSegmentsEmptyAndGarbage() {
        XCTAssertEqual(TurnReceiptStore.encodeSegments([]), "[]")
        XCTAssertTrue(TurnReceiptStore.decodeSegments("[]").isEmpty)
        XCTAssertTrue(TurnReceiptStore.decodeSegments("not json").isEmpty)
        // 缺 id 的项被丢掉，其余保留
        XCTAssertEqual(TurnReceiptStore.decodeSegments(#"[{"source":"env"},{"id":"a"}]"#).count, 1)
    }

    /// 层名与来源名对未知值要回退（引擎加新层时界面不能空白）。
    func testSegmentTitlesFallBack() {
        let known = TurnReceipt.Segment(id: "x", source: "memory", layer: 5,
                                        lifetime: "task", chars: 1)
        XCTAssertEqual(known.sourceTitle, "记忆")
        XCTAssertEqual(known.layerTitle, "L5 记忆")

        let unknown = TurnReceipt.Segment(id: "y", source: "brand_new", layer: 9,
                                          lifetime: "task", chars: 1)
        XCTAssertEqual(unknown.sourceTitle, "brand_new")
        XCTAssertEqual(unknown.layerTitle, "L9")
    }

    /// 本机估算 = 各片段字数之和（它不是 token，界面文案也照此写）。
    func testEstimatedCharsSums() {
        let receipt = TurnReceipt(
            turnId: "t", timestamp: 0, companionId: 1, provider: "", model: "",
            segments: [
                TurnReceipt.Segment(id: "a", source: "env", layer: 0, lifetime: "status", chars: 10),
                TurnReceipt.Segment(id: "b", source: "memory", layer: 5, lifetime: "task", chars: 32),
            ],
            usage: nil, roundsUsed: 1, finishReason: "stop", toolNames: [],
            systemPromptHash: "", skipped: []
        )
        XCTAssertEqual(receipt.estimatedChars, 42)
    }

    // MARK: - 落库（prompt_audit / token_usage）

    func testAuditRowIsWrittenAndReadBack() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }

        let receipt = TurnReceipt(
            turnId: "turn-abc", timestamp: 1_700_000_000_000, companionId: 7,
            provider: "openai", model: "gpt-x",
            segments: [TurnReceipt.Segment(id: "persona.core", source: "persona",
                                           layer: 1, lifetime: "stable", chars: 120)],
            usage: nil, roundsUsed: 2, finishReason: "stop",
            toolNames: ["load_skill", "recall_memory"], systemPromptHash: "abc123",
            skipped: ["历史消息只送最近 100 条，最早的 3 条未送入"]
        )
        TurnReceiptStore.saveAudit(receipt, database: database)

        let loaded = TurnReceiptStore.loadAudit(turnId: "turn-abc", database: database)
        XCTAssertEqual(loaded?.segments, receipt.segments)
        XCTAssertEqual(loaded?.roundsUsed, 2)
        XCTAssertEqual(loaded?.toolNames, receipt.toolNames)
        XCTAssertEqual(loaded?.systemPromptHash, "abc123")
        // 回读的收据**没有** token（prompt_audit 不存用量）—— 界面据此如实说明
        XCTAssertNil(loaded?.usage)
        XCTAssertNil(TurnReceiptStore.loadAudit(turnId: "不存在", database: database))
    }

    /// 用量按 (companionId, date, deviceId) 累加，且**写两行**：
    /// 伴侣行 + `companionId = -1` 全局行（Android 同款）。
    func testTokenUsageUpsertsTwoRowsAndAccumulates() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }

        let now = Date()
        let first = TurnReceipt.Usage(promptTokens: 100, completionTokens: 20,
                                      totalTokens: 120, requests: 1)
        let second = TurnReceipt.Usage(promptTokens: 50, completionTokens: 5,
                                       totalTokens: 55, requests: 2)
        TurnReceiptStore.recordTokenUsage(first, companionId: 3, database: database, now: now)
        TurnReceiptStore.recordTokenUsage(second, companionId: 3, database: database, now: now)

        let date = TurnReceiptStore.dateString(now)
        try database.pool.read { db in
            let rows = try Row.fetchAll(db, sql: """
                SELECT companionId, inputTokens, outputTokens, totalTokens, requestCount
                FROM token_usage WHERE date = ? ORDER BY companionId
                """, arguments: [date])
            XCTAssertEqual(rows.count, 2, "伴侣行 + 全局行（companionId = -1）")
            XCTAssertEqual(rows[0]["companionId"] as Int64?, -1)
            XCTAssertEqual(rows[1]["companionId"] as Int64?, 3)
            for row in rows {
                XCTAssertEqual(row["inputTokens"] as Int?, 150)
                XCTAssertEqual(row["outputTokens"] as Int?, 25)
                XCTAssertEqual(row["totalTokens"] as Int?, 175)
                XCTAssertEqual(row["requestCount"] as Int?, 3)
            }
        }
    }

    /// 日期串必须是 `yyyy-MM-dd`（与 Android 共用一张表，格式不一致会让两端对不上）。
    func testDateStringFormat() {
        var components = DateComponents()
        components.year = 2026; components.month = 10; components.day = 11
        components.hour = 12
        let calendar = Calendar(identifier: .gregorian)
        let date = calendar.date(from: components)!
        XCTAssertEqual(TurnReceiptStore.dateString(date), "2026-10-11")
    }

    // MARK: - 历史截断方向

    func testTruncateKeepsMostRecent() {
        let history = (1...5).map { AgentHistoryMessage.user("消息\($0)") }
        let (kept, dropped) = ChatSession.truncateHistory(history, limit: 3)
        XCTAssertEqual(dropped, 2)
        XCTAssertEqual(kept.count, 3)
        // ⚠️ 方向：留下的必须是**最近三条**（字段名是 content，不是 text）
        XCTAssertEqual(kept.map(\.content), ["消息3", "消息4", "消息5"])
    }

    func testTruncateNoOpWhenUnderLimit() {
        let history = (1...3).map { AgentHistoryMessage.user("消息\($0)") }
        let (kept, dropped) = ChatSession.truncateHistory(history, limit: 100)
        XCTAssertEqual(dropped, 0)
        XCTAssertEqual(kept.count, 3)
    }

    /// limit <= 0 视为"不限制"（而不是"什么都不送"）——
    /// 后者会让模型收到一个空历史，症状是"它突然不记得任何事"。
    func testTruncateZeroLimitMeansUnlimited() {
        let history = (1...3).map { AgentHistoryMessage.user("消息\($0)") }
        let (kept, dropped) = ChatSession.truncateHistory(history, limit: 0)
        XCTAssertEqual(kept.count, 3)
        XCTAssertEqual(dropped, 0)
    }

    // MARK: - 上下文选项

    func testContextSettingsRoundTrip() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let repo = ApiConfigRepository(database: database)

        // 默认值与既有行为一致（历史 100 / 短期记忆 200）
        let initial = ContextSettings.load(from: repo)
        XCTAssertEqual(initial.historyLimit, 100)
        XCTAssertEqual(initial.workingMemoryLimit, 200)

        var saved = ContextSettings()
        saved.historyLimit = 30
        saved.workingMemoryLimit = 60
        try saved.save(to: repo)
        XCTAssertEqual(ContextSettings.load(from: repo), saved)
    }

    /// 越界值被忽略（回到默认）—— 手改过 app_meta 或将来改了范围都不会
    /// 让引擎收到一个荒唐的值。
    func testContextSettingsIgnoresOutOfRange() throws {
        let (database, dir) = try makeDatabase()
        defer { try? FileManager.default.removeItem(at: dir) }
        let repo = ApiConfigRepository(database: database)
        try repo.setMetaValue("99999", forKey: ContextSettings.historyLimitKey)
        try repo.setMetaValue("0", forKey: ContextSettings.workingMemoryLimitKey)

        let loaded = ContextSettings.load(from: repo)
        XCTAssertEqual(loaded.historyLimit, ContextSettings.defaultHistoryLimit)
        XCTAssertEqual(loaded.workingMemoryLimit, ContextSettings.defaultWorkingMemoryLimit)
    }
}
