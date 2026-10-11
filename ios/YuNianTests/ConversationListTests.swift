import GRDB
import XCTest

/// 会话列表：可见条数、搜索过滤、清空聊天记录（第 216 轮）。
///
/// ## 这套测试要挡住的三类错
/// 1. **条数口径不一致**：列表的"共 N 条"、最后一条消息、未读统计三者若口径不同，
///    用户会看到"共 12 条"却只数得出 9 条 —— 然后开始怀疑数据丢了。
/// 2. **搜索漏结果**：搜索写错的表现是"明明有的会话搜不到"，界面上看不出异常。
/// 3. **清空后未读还在**：`conversation_summary` 那行的已读游标/未读数不清掉，
///    就会出现"记录清空了，列表还标着未读"。
@testable import YuNian
final class ConversationListTests: XCTestCase {

    private func makeEnv() throws -> (ConversationRepository, CompanionRepository,
                                      MessageRepository, YuNianDatabase, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("conv_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let database = try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database"))
        return (ConversationRepository(database: database),
                CompanionRepository(database: database),
                MessageRepository(database: database),
                database, dir)
    }

    @discardableResult
    private func insert(_ messages: MessageRepository, companion: Int64, text: String,
                        type: String = "text", fromUser: Bool = false) throws -> Int64 {
        try messages.insert(conversationId: companion, conversationType: .chat,
                            isFromUser: fromUser, senderId: fromUser ? 0 : companion,
                            type: type, content: text, searchContent: text)
    }

    // MARK: - 可见条数

    func testVisibleMessageCountExcludesReasoningAndToolActivity() throws {
        let (conv, companions, messages, _, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }

        let id = try companions.create(name: "阿念", personality: "温柔")
        try insert(messages, companion: id, text: "你好", fromUser: true)
        try insert(messages, companion: id, text: "你也好")
        try insert(messages, companion: id, text: "（思考过程）", type: "REASONING")
        try insert(messages, companion: id, text: "（工具调用）", type: "TOOL_ACTIVITY")

        let row = try XCTUnwrap(try conv.rows().first)
        XCTAssertEqual(row.messageCount, 2, "只数用户看得到的消息 —— 与最后一条消息、未读统计同一口径")
        XCTAssertEqual(row.lastMessage, "你也好", "最后一条也不该是 REASONING")
    }

    /// iOS ChatSession 真机保存的是小写 reasoning；旧测试只覆盖了大写。
    func testLowercaseReasoningIsExcludedFromPreviewCountAndUnread() throws {
        let (conv, companions, messages, _, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }

        let id = try companions.create(name: "回归角色", personality: "x")
        try insert(messages, companion: id, text: "你好", fromUser: true)
        try insert(messages, companion: id, text: "你好呀")
        XCTAssertTrue(try conv.markReadThroughLatest(companionId: id))
        try insert(messages, companion: id, text: "内部推理", type: "reasoning")
        try insert(messages, companion: id, text: "内部工具事件", type: "tool_activity")

        let row = try XCTUnwrap(try conv.rows().first)
        XCTAssertEqual(row.lastMessage, "你好呀")
        XCTAssertEqual(row.messageCount, 2)
        XCTAssertEqual(try conv.unreadCount(companionId: id), 0)
        XCTAssertTrue(try conv.markReadThroughLatest(companionId: id))
    }

    func testConversationWithoutMessagesHasZeroCountAndFallbackPreview() throws {
        let (conv, companions, _, _, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }

        _ = try companions.create(name: "新角色", personality: "x")
        let row = try XCTUnwrap(try conv.rows().first)
        XCTAssertEqual(row.messageCount, 0)
        XCTAssertNil(row.lastMessage)
        XCTAssertEqual(row.preview, "还没有聊天记录，开始聊天吧")
    }

    // MARK: - 搜索（纯函数）

    func testFilterMatchesNameOrLastMessageIgnoringCase() throws {
        // ⚠️ 逐字段给全：`avatarUrl` / `lastMessageAt` 是**没有默认值**的可选属性，
        // 成员合成 init 的参数顺序按声明顺序 —— 不写清楚就会踩"参数标签不匹配"。
        let rows = [
            ConversationRepository.ConvRow(companionId: 1, name: "阿念", avatarUrl: nil,
                                           lastMessage: "今天下雨", lastMessageAt: nil),
            ConversationRepository.ConvRow(companionId: 2, name: "Alice", avatarUrl: nil,
                                           lastMessage: "hello", lastMessageAt: nil),
            ConversationRepository.ConvRow(companionId: 3, name: "小满", avatarUrl: nil,
                                           lastMessage: nil, lastMessageAt: nil),
        ]
        XCTAssertEqual(ConversationRepository.filter(rows, query: "").count, 3, "空查询 = 不过滤")
        XCTAssertEqual(ConversationRepository.filter(rows, query: "   ").count, 3, "只有空白也不过滤")
        XCTAssertEqual(ConversationRepository.filter(rows, query: "阿念").map(\.companionId), [1])
        XCTAssertEqual(ConversationRepository.filter(rows, query: "下雨").map(\.companionId), [1],
                       "命中最后一条消息")
        XCTAssertEqual(ConversationRepository.filter(rows, query: "alice").map(\.companionId), [2],
                       "名字不区分大小写")
        XCTAssertEqual(ConversationRepository.filter(rows, query: "HELLO").map(\.companionId), [2],
                       "消息不区分大小写")
        XCTAssertTrue(ConversationRepository.filter(rows, query: "不存在的词").isEmpty)
        XCTAssertFalse(ConversationRepository.filter(rows, query: "无")
            .contains { $0.companionId == 3 },
                       "lastMessage 为 nil 不该崩、也不该被当成命中")
    }

    // MARK: - 清空聊天记录

    func testClearConversationRemovesMessagesAndKeepsCompanion() throws {
        let (conv, companions, messages, _, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }

        let id = try companions.create(name: "阿念", personality: "温柔")
        try insert(messages, companion: id, text: "一", fromUser: true)
        try insert(messages, companion: id, text: "二")

        try conv.clearConversation(companionId: id, messages: messages)

        XCTAssertTrue(try messages.history(conversationId: id, type: .chat).isEmpty)
        XCTAssertEqual(try conv.rows().first?.messageCount, 0)
        XCTAssertEqual(try conv.rows().first?.lastMessage, nil)
        XCTAssertNotNil(try companions.fetch(id: id), "清记录不删角色")
    }

    /// **已读游标/未读计数必须一起清掉** —— 否则"记录清空了，列表还标着未读"。
    func testClearConversationAlsoDropsSummarySoUnreadGoesAway() throws {
        let (conv, companions, messages, _, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }

        let id = try companions.create(name: "阿念", personality: "温柔")
        try insert(messages, companion: id, text: "一", fromUser: true)
        try insert(messages, companion: id, text: "二")

        // 先制造一个 summary 行（含已读游标），并把"未读"造出来
        XCTAssertTrue(try conv.markReadThroughLatest(companionId: id))
        try insert(messages, companion: id, text: "三")
        XCTAssertGreaterThan(try conv.unreadCount(companionId: id), 0)
        XCTAssertNotNil(try conv.readCursor(companionId: id))

        try conv.clearConversation(companionId: id, messages: messages)

        XCTAssertEqual(try conv.unreadCount(companionId: id), 0, "清空后不该还有未读")
        XCTAssertNil(try conv.readCursor(companionId: id), "summary 行要一起删掉")
    }

    func testClearConversationWithoutRepositoryStillDropsSummary() throws {
        let (conv, companions, messages, _, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }

        let id = try companions.create(name: "阿念", personality: "温柔")
        try insert(messages, companion: id, text: "一")
        _ = try conv.markReadThroughLatest(companionId: id)

        // messages 传 nil 时只清 summary（防御性路径，仍要能跑通）
        try conv.clearConversation(companionId: id, messages: nil)
        XCTAssertNil(try conv.readCursor(companionId: id))
    }

    func testClearEmptyConversationIsHarmless() throws {
        let (conv, companions, messages, _, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "没聊过", personality: "x")
        try conv.clearConversation(companionId: id, messages: messages)
        XCTAssertEqual(try conv.rows().first?.messageCount, 0)
    }

    /// 清空**只影响目标会话** —— 别的角色的记录不该被碰。
    func testClearConversationDoesNotTouchOtherCompanions() throws {
        let (conv, companions, messages, _, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }

        let mine = try companions.create(name: "甲的", personality: "x")
        let other = try companions.create(name: "乙的", personality: "x")
        try insert(messages, companion: mine, text: "我的")
        try insert(messages, companion: other, text: "别人的")

        try conv.clearConversation(companionId: mine, messages: messages)

        XCTAssertEqual(try messages.history(conversationId: other, type: .chat).count, 1)
        let rows = try conv.rows()
        XCTAssertEqual(rows.first { $0.companionId == other }?.messageCount, 1)
        XCTAssertEqual(rows.first { $0.companionId == mine }?.messageCount, 0)
    }
}
