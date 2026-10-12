import GRDB
import XCTest

/// 角色页（角色中枢）第 219 轮。
///
/// ## 这一页最大的风险是"理直气壮地骗人"
/// 副标题里的数字是它全部价值所在。一旦角色页的世界书本数与引擎合成规则不一致，
/// 界面会显示「生效 0 本」，而实际每个回合都注入了一本 —— 两边各自看都正常。
/// 所以这里逐项用**真实库**验：数字必须与引擎/列表的既有口径一致。
@testable import YuNian
final class CompanionHubTests: XCTestCase {

    private func makeEnv() throws -> (CompanionRepository, YuNianDatabase, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("hub_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let database = try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database"))
        return (CompanionRepository(database: database), database, dir)
    }

    private static let bookJSON = """
    {"name":"她的世界","entries":{
      "1":{"keys":["咖啡"],"content":"她只喝美式","insertion_order":1,"enabled":true}}}
    """

    private static let emptyBookJSON = """
    {"name":"空书","entries":{}}
    """

    private func makeStats(_ database: YuNianDatabase,
                           _ companion: CompanionRepository.Companion) -> CompanionHubStats {
        CompanionHubStats.load(database: database, companion: companion, apiConfigs: nil)
    }

    // MARK: - 世界书：必须与引擎规则一致

    /// 没有任何书 → 不假装有；且文案要区分"没带"与"带了但空"。
    func testNoWorldbookAtAll() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "阿念", personality: "温柔")
        let companion = try XCTUnwrap(try companions.fetch(id: id))

        let stats = makeStats(database, companion)
        XCTAssertEqual(stats.worldbooksEffective, 0)
        XCTAssertEqual(stats.worldbooksOwned, 0)
        XCTAssertFalse(stats.worldbookInjectsAnything)
        XCTAssertTrue(stats.worldbookSubtitle.contains("没有专属世界书"), stats.worldbookSubtitle)
    }

    /// 自己的书（启用）→ 生效 1 本，且**确实会注入**。
    func testOwnEnabledBookIsEffectiveAndInjects() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "阿念", personality: "温柔")
        _ = WorldbookRepository(database: database).save(name: "她的世界", json: Self.bookJSON,
                                                         companionId: id)
        let companion = try XCTUnwrap(try companions.fetch(id: id))

        let stats = makeStats(database, companion)
        XCTAssertEqual(stats.worldbooksOwned, 1)
        XCTAssertEqual(stats.worldbooksEffective, 1)
        XCTAssertTrue(stats.worldbookInjectsAnything)
        XCTAssertEqual(stats.worldbookSubtitle, "生效 1 本")
    }

    /// 停用的书 → **不生效**（引擎读 enabled = 1）。
    func testDisabledBookIsNotEffective() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "阿念", personality: "温柔")
        let repo = WorldbookRepository(database: database)
        let bookId = repo.save(name: "停用的", json: Self.bookJSON, companionId: id)
        _ = repo.setEnabled(false, id: bookId)
        let companion = try XCTUnwrap(try companions.fetch(id: id))

        let stats = makeStats(database, companion)
        XCTAssertEqual(stats.worldbooksOwned, 1, "专属书仍然是 1 本（只是停用了）")
        XCTAssertEqual(stats.worldbooksEffective, 0, "停用就不该算生效")
        XCTAssertTrue(stats.worldbookSubtitle.contains("当前不会带上"), stats.worldbookSubtitle)
    }

    /// **别人**的专属书不该算到这个角色头上（绑定规则里最容易写错的一条）。
    func testOtherCompanionsBookIsNotEffective() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let mine = try companions.create(name: "我的", personality: "x")
        let other = try companions.create(name: "别人的", personality: "x")
        _ = WorldbookRepository(database: database).save(name: "别人的书", json: Self.bookJSON,
                                                         companionId: other)
        let companion = try XCTUnwrap(try companions.fetch(id: mine))

        let stats = makeStats(database, companion)
        XCTAssertEqual(stats.worldbooksOwned, 0)
        XCTAssertEqual(stats.worldbooksEffective, 0, "别的角色的专属书不能漏进来")
    }

    /// 规则上生效、但**书里没有可用条目** → 引擎合成结果是空串，实际不注入。
    /// 这两种状态必须分开说，否则用户以为"显示生效就一定注入了"。
    func testEffectiveButEmptyBookSaysNotInjecting() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "阿念", personality: "温柔")
        _ = WorldbookRepository(database: database).save(name: "空书", json: Self.emptyBookJSON,
                                                         companionId: id)
        let companion = try XCTUnwrap(try companions.fetch(id: id))

        let stats = makeStats(database, companion)
        XCTAssertEqual(stats.worldbooksEffective, 1)
        XCTAssertFalse(stats.worldbookInjectsAnything, "空书不该被说成「已注入」")
        XCTAssertTrue(stats.worldbookSubtitle.contains("条目为空"), stats.worldbookSubtitle)
    }

    /// 与引擎合成函数**同源**：角色页说"不注入"时，引擎的合成结果必须真的是空。
    func testSubtitleAgreesWithEngineSynth() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "阿念", personality: "温柔")
        let books = WorldbookRepository(database: database)
        _ = books.save(name: "空书", json: Self.emptyBookJSON, companionId: id)
        let companion = try XCTUnwrap(try companions.fetch(id: id))

        let stats = makeStats(database, companion)
        let payload = WorldbookRuntimeSync.synthForCompanion(database, companionId: id)
        XCTAssertEqual(stats.worldbookInjectsAnything, !payload.isEmpty,
                       "界面说的必须与引擎合成结果一致")

        // 再补一本有内容的：两边都要跟着变
        _ = books.save(name: "有内容的", json: Self.bookJSON, companionId: id)
        let stats2 = makeStats(database, companion)
        let payload2 = WorldbookRuntimeSync.synthForCompanion(database, companionId: id)
        XCTAssertTrue(!payload2.isEmpty)
        XCTAssertEqual(stats2.worldbookInjectsAnything, !payload2.isEmpty)
        XCTAssertEqual(stats2.worldbooksEffective, 2, "两本都生效（合成会合并）")
    }

    // MARK: - 记忆

    func testMemoryCountsAreScopedToCompanion() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let mine = try companions.create(name: "我的", personality: "x")
        let other = try companions.create(name: "别人的", personality: "x")
        let now = Int64(Date().timeIntervalSince1970 * 1000)

        try insertMemory(database, companionId: mine, content: "喜欢美式", deleted: false, now: now)
        try insertMemory(database, companionId: mine, content: "删掉的", deleted: true, now: now)
        try insertMemory(database, companionId: other, content: "别人的记忆", deleted: false, now: now)

        let counts = MemoryRepository(database: database).counts(companionId: mine)
        XCTAssertEqual(counts.active, 1)
        XCTAssertEqual(counts.deleted, 1)

        let companion = try XCTUnwrap(try companions.fetch(id: mine))
        let stats = makeStats(database, companion)
        XCTAssertEqual(stats.memoryActive, 1)
        XCTAssertEqual(stats.memoryDeleted, 1)
        XCTAssertTrue(stats.memorySubtitle.contains("1 条参与召回"), stats.memorySubtitle)
        XCTAssertTrue(stats.memorySubtitle.contains("可恢复"), stats.memorySubtitle)
    }

    func testMemorySubtitleWhenEmpty() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "阿念", personality: "x")
        let companion = try XCTUnwrap(try companions.fetch(id: id))
        let stats = makeStats(database, companion)
        XCTAssertEqual(stats.memoryActive, 0)
        XCTAssertEqual(stats.memorySubtitle, "还没有记住任何事")
    }

    /// 群聊记忆（scope = GROUP）不属于任何单个角色，不能算进来。
    func testGroupMemoryNotCountedForCompanion() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "阿念", personality: "x")
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try insertMemory(database, companionId: id, content: "群里的", deleted: false,
                         now: now, scope: "GROUP", sourceId: id)
        XCTAssertEqual(MemoryRepository(database: database).counts(companionId: id).active, 0)
    }

    // MARK: - 会话 / 人设 / 语音

    func testConversationSubtitleUsesListCounts() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "阿念", personality: "x")
        let messages = MessageRepository(database: database)
        for text in ["一", "二", "三"] {
            _ = try messages.insert(conversationId: id, conversationType: .chat,
                                    isFromUser: true, senderId: 0, type: "text",
                                    content: text, searchContent: text)
        }
        let companion = try XCTUnwrap(try companions.fetch(id: id))
        let stats = makeStats(database, companion)
        XCTAssertEqual(stats.messages, 3, "与首页列表同一口径")
        XCTAssertTrue(stats.conversationSubtitle.contains("共 3 条"), stats.conversationSubtitle)
    }

    func testConversationSubtitleWhenNeverChatted() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "阿念", personality: "x")
        let companion = try XCTUnwrap(try companions.fetch(id: id))
        XCTAssertEqual(makeStats(database, companion).conversationSubtitle, "还没有聊过")
    }

    func testPersonaSummaryOnlyUsesFilledFields() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let plain = try companions.create(name: "空的", personality: "")
        let empty = try XCTUnwrap(try companions.fetch(id: plain))
        XCTAssertEqual(CompanionHubStats.personaSummary(for: empty), "还没写人设")

        let rich = try companions.create(name: "阿念", personality: "外冷内热", age: 22,
                                        backstory: "杭州", speakingStyle: "轻声")
        let companion = try XCTUnwrap(try companions.fetch(id: rich))
        let summary = CompanionHubStats.personaSummary(for: companion)
        XCTAssertTrue(summary.contains("22 岁"), summary)
        XCTAssertTrue(summary.contains("外冷内热"), summary)
        XCTAssertTrue(summary.contains("轻声"), summary)
        XCTAssertFalse(summary.contains("杭州"), "不在摘要里的字段不该硬塞")
    }

    /// 摘要有长度上限（副标题超长会把行撑坏 —— 正是苏晚瑄反馈那类问题的同源）。
    func testPersonaSummaryIsCapped() throws {
        let (companions, database, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "阿念", personality: String(repeating: "长", count: 80))
        let companion = try XCTUnwrap(try companions.fetch(id: id))
        let summary = CompanionHubStats.personaSummary(for: companion)
        XCTAssertLessThanOrEqual(summary.count, 41, "40 字 + 省略号")
        XCTAssertTrue(summary.hasSuffix("…"), summary)
    }

    func testVoiceSubtitle() {
        var stats = CompanionHubStats()
        XCTAssertEqual(stats.voiceSubtitle, "跟随全局音色")
        stats.voiceOverride = "zh-CN-XiaoxiaoNeural"
        XCTAssertTrue(stats.voiceSubtitle.contains("已单独指定"), stats.voiceSubtitle)
        XCTAssertTrue(stats.voiceSubtitle.contains("zh-CN-XiaoxiaoNeural"), stats.voiceSubtitle)
    }

    // MARK: - 记忆页的作用域过滤（角色页 → 记忆页）

    func testMemoryViewScopedFilter() {
        let all = [
            MemoryRepository.Memory(id: 1, content: "我的", memoryType: "SEMANTIC", scope: "COMPANION",
                                    source: "manual", sourceId: 7, importance: 1, accessCount: 0,
                                    observedAt: 0, expiresAt: nil, isDeleted: false),
            MemoryRepository.Memory(id: 2, content: "别人的", memoryType: "SEMANTIC", scope: "COMPANION",
                                    source: "manual", sourceId: 8, importance: 1, accessCount: 0,
                                    observedAt: 0, expiresAt: nil, isDeleted: false),
            MemoryRepository.Memory(id: 3, content: "群里的", memoryType: "SEMANTIC", scope: "GROUP",
                                    source: "manual", sourceId: 7, importance: 1, accessCount: 0,
                                    observedAt: 0, expiresAt: nil, isDeleted: false),
        ]
        XCTAssertEqual(MemoryView.scoped(all, companionId: nil).count, 3, "nil = 不过滤（我 → 记忆）")
        XCTAssertEqual(MemoryView.scoped(all, companionId: 7).map(\.id), [1],
                       "同 id 但 scope 是 GROUP 的不算这个角色的")
    }

    // MARK: - 辅助

    private func insertMemory(_ database: YuNianDatabase, companionId: Int64, content: String,
                              deleted: Bool, now: Int64, scope: String = "COMPANION",
                              sourceId: Int64? = nil) throws {
        // ⚠️ `tags` 是 `TEXT NOT NULL` **且没有默认值** —— 漏了它会直接违反非空约束
        //（其余省略的列都有 DEFAULT）。这里照 `BackupImporter` 的写法给空串。
        try database.pool.write { db in
            try db.execute(sql: """
                INSERT INTO unified_memories
                  (memoryType, scope, source, content, sourceId, createdAt, updatedAt,
                   lastAccessedAt, observedAt, tags, isDeleted, deviceId)
                VALUES ('SEMANTIC', ?, 'manual', ?, ?, ?, ?, ?, ?, '', ?, ?)
                """, arguments: [scope, content, sourceId ?? companionId, now, now, now, now,
                                 deleted ? 1 : 0, DeviceIdentity.deviceId])
        }
    }
}
