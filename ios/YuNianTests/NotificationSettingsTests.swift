import XCTest
import GRDB

@testable import YuNian

/// 通知与角标（第 227 轮）。
///
/// ## 这套测试锁的三件事
/// 1. **角标口径与列表一致**：角标显示 3、点进去一条新消息都没有，
///    是最伤信任的那种 bug —— 所以 `unreadTotal()` 直接复用单会话未读逻辑，
///    这里用"两段口径必须相等"把它钉住。
/// 2. **设置持久化**：读不到 → 默认值（不猜）；存了 → 读回来一致。
/// 3. **时间夹取**：越界的时/分会被 `UNCalendarNotificationTrigger`
///    **静默丢弃**（不报错、也不响），表现为"提醒设了但从来不响" —— 极难排查，
///    所以这条必须有测试。
final class NotificationSettingsTests: XCTestCase {

    private func makeEnvironment() throws -> (YuNianDatabase, ApiConfigRepository, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("notify_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let database = try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database"))
        return (database, ApiConfigRepository(database: database), dir)
    }

    // MARK: - 时间夹取

    func testNormalizedTimeClampsOutOfRange() {
        XCTAssertEqual(NotificationSettings.normalizedTime(hour: 25, minute: 61).hour, 23)
        XCTAssertEqual(NotificationSettings.normalizedTime(hour: 25, minute: 61).minute, 59)
        XCTAssertEqual(NotificationSettings.normalizedTime(hour: -1, minute: -5).hour, 0)
        XCTAssertEqual(NotificationSettings.normalizedTime(hour: -1, minute: -5).minute, 0)
        XCTAssertEqual(NotificationSettings.normalizedTime(hour: 20, minute: 5).hour, 20)
    }

    /// 存进库里的时间也必须是夹过的 —— 否则界面显示 25:61、触发器却不存在。
    func testSaveClampsAndPersists() throws {
        let (_, repo, dir) = try makeEnvironment()
        defer { try? FileManager.default.removeItem(at: dir) }
        var settings = NotificationSettings()
        settings.dailyEnabled = true
        settings.dailyHour = 99
        settings.dailyMinute = 99
        settings.dailyText = "该休息啦"
        try settings.save(to: repo)

        let loaded = NotificationSettings.load(from: repo)
        XCTAssertEqual(loaded.dailyHour, 23)
        XCTAssertEqual(loaded.dailyMinute, 59)
        XCTAssertTrue(loaded.dailyEnabled)
        XCTAssertEqual(loaded.dailyText, "该休息啦")
    }

    // MARK: - 默认值与往返

    func testDefaultsWhenNothingStored() throws {
        let (_, repo, dir) = try makeEnvironment()
        defer { try? FileManager.default.removeItem(at: dir) }
        let settings = NotificationSettings.load(from: repo)
        XCTAssertTrue(settings.badgeEnabled, "角标默认开（系统级约定）")
        XCTAssertTrue(settings.replyDoneEnabled, "回复提醒默认开")
        XCTAssertFalse(settings.dailyEnabled, "每日提醒默认关 —— 默认开等于装完就天天弹")
        XCTAssertEqual(settings.dailyHour, 20)
        XCTAssertEqual(settings.dailyMinute, 0)
    }

    /// 没有数据库（`repo = nil`）时也不能崩，给默认值。
    func testLoadWithoutDatabaseReturnsDefaults() {
        let settings = NotificationSettings.load(from: nil)
        XCTAssertTrue(settings.badgeEnabled)
        XCTAssertFalse(settings.dailyEnabled)
    }

    func testRoundTrip() throws {
        let (_, repo, dir) = try makeEnvironment()
        defer { try? FileManager.default.removeItem(at: dir) }
        var settings = NotificationSettings()
        settings.badgeEnabled = false
        settings.replyDoneEnabled = false
        settings.dailyEnabled = true
        settings.dailyHour = 8
        settings.dailyMinute = 30
        try settings.save(to: repo)

        let loaded = NotificationSettings.load(from: repo)
        XCTAssertEqual(loaded, settings, "存什么读什么（含关闭状态）")
    }

    /// 空文案不该覆盖成空 —— 否则通知弹出来是空壳。
    func testBlankDailyTextFallsBackToDefault() throws {
        let (_, repo, dir) = try makeEnvironment()
        defer { try? FileManager.default.removeItem(at: dir) }
        try repo.setMetaValue("", forKey: NotificationSettings.dailyTextKey)
        XCTAssertEqual(NotificationSettings.load(from: repo).dailyText,
                       NotificationSettings().dailyText)
    }

    // MARK: - 布尔取值（`"0"` 必须被读成 false）

    /// ## 第 227 轮 CI 抓到的真 bug
    /// 存的是 `"1"`/`"0"`，而 `Bool("0")` 在 Swift 里是 **nil** ——
    /// 于是"关掉的开关读回来又是开的"，用户只会以为设置没保存。
    func testBoolValueAcceptsNumericForm() {
        XCTAssertTrue(NotificationSettings.boolValue("1", fallback: false))
        XCTAssertFalse(NotificationSettings.boolValue("0", fallback: true))
        XCTAssertTrue(NotificationSettings.boolValue("true", fallback: false))
        XCTAssertFalse(NotificationSettings.boolValue("false", fallback: true))
        // 认不出的值才回落默认，而不是一律当成 false
        XCTAssertTrue(NotificationSettings.boolValue("wat", fallback: true))
        XCTAssertTrue(NotificationSettings.boolValue(nil, fallback: true))
    }

    /// 直接复现 bug 现场：显式写 `"0"` 之后读回来必须是关。
    func testStoredZeroReadsBackAsFalse() throws {
        let (_, repo, dir) = try makeEnvironment()
        defer { try? FileManager.default.removeItem(at: dir) }
        try repo.setMetaValue("0", forKey: NotificationSettings.badgeKey)
        try repo.setMetaValue("0", forKey: NotificationSettings.replyDoneKey)
        let loaded = NotificationSettings.load(from: repo)
        XCTAssertFalse(loaded.badgeEnabled, "写了 0 就该是关")
        XCTAssertFalse(loaded.replyDoneEnabled, "写了 0 就该是关")
    }

    // MARK: - 角标口径

    /// **角标总数 = 各会话未读之和**，且思考过程不算未读（口径与列表一致）。
    func testUnreadTotalMatchesPerCompanionSum() throws {
        let (database, _, dir) = try makeEnvironment()
        defer { try? FileManager.default.removeItem(at: dir) }
        let companions = CompanionRepository(database: database)
        let messages = MessageRepository(database: database)
        let conversations = ConversationRepository(database: database)

        let a = try companions.create(name: "小鱼", personality: "温柔")
        let b = try companions.create(name: "阿离", personality: "冷淡")

        // A：一条用户消息（不算未读）+ 两条 AI 回复（算未读）+ 一条思考过程（不算）
        _ = try messages.insert(conversationId: a, conversationType: .chat, isFromUser: true,
                                senderId: 0, type: "TEXT", content: "在吗", searchContent: "在吗")
        _ = try messages.insert(conversationId: a, conversationType: .chat, isFromUser: false,
                                senderId: a, type: "TEXT", content: "在的", searchContent: "在的")
        _ = try messages.insert(conversationId: a, conversationType: .chat, isFromUser: false,
                                senderId: a, type: "REASONING", content: "thinking", searchContent: "thinking")
        // B：一条 AI 回复
        _ = try messages.insert(conversationId: b, conversationType: .chat, isFromUser: false,
                                senderId: b, type: "TEXT", content: "嗯", searchContent: "嗯")

        let perCompanion = try conversations.unreadCount(companionId: a)
                         + (try conversations.unreadCount(companionId: b))
        XCTAssertEqual(try conversations.unreadTotal(), perCompanion,
                       "角标口径必须与逐会话未读完全一致")
        XCTAssertEqual(try conversations.unreadTotal(), 2, "思考过程不算未读")
    }

    /// 全部已读 → 角标归零（不是"减一"，是 0）。
    func testUnreadTotalIsZeroAfterMarkingRead() throws {
        let (database, _, dir) = try makeEnvironment()
        defer { try? FileManager.default.removeItem(at: dir) }
        let companions = CompanionRepository(database: database)
        let messages = MessageRepository(database: database)
        let conversations = ConversationRepository(database: database)

        let id = try companions.create(name: "小鱼", personality: "温柔")
        _ = try messages.insert(conversationId: id, conversationType: .chat, isFromUser: false,
                                senderId: id, type: "TEXT", content: "你好呀", searchContent: "你好呀")
        XCTAssertEqual(try conversations.unreadTotal(), 1)

        _ = try conversations.markReadThroughLatest(companionId: id)
        XCTAssertEqual(try conversations.unreadTotal(), 0)
    }
}
