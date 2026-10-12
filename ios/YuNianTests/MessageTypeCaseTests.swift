import GRDB
import XCTest

/// 消息类型串的大小写契约（第 223 轮，用户可见 bug）。
///
/// ## 这条 bug 长什么样
/// 会话列表里"最后一条消息"显示成了一句**英文思考过程**
/// （`The user's message seems playful with a cat e…`）——
/// 用户因此反馈"回复为什么是英文"。
///
/// ## 根因
/// Android 的 `MessageType` 有两套写法，很容易混：
/// - `@SerialName("reasoning")` —— 那是 **kotlinx.serialization**（备份 JSON），**小写**；
/// - `AppDatabase.Converters.fromMessageType(v) = v.name` —— 那才是 **Room 落库**的值，
///   即枚举名 **`"REASONING"`（大写）**；读取端还会 `uppercase()` 容错。
///
/// iOS 原先**写入用小写**、**SQL 过滤用大写** —— 自己写的行自己排不掉，
/// 于是思考过程被当成可见消息（还撑大了未读数）。
///
/// ## 这套测试锁什么
/// 1. **写入**与 Room 一致（大写）；
/// 2. **过滤**大小写都不敏感 —— 兼容已经落库的小写老行，也兼容 Android 导入的数据；
/// 3. 三条口径（最后一条消息 / 可见条数 / 未读）对**两种大小写**都不算数。
@testable import YuNian
final class MessageTypeCaseTests: XCTestCase {

    private func makeEnv() throws -> (MessageRepository, ConversationRepository,
                                      CompanionRepository, YuNianDatabase, URL) {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("typecase_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let database = try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database"))
        return (MessageRepository(database: database),
                ConversationRepository(database: database),
                CompanionRepository(database: database),
                database, dir)
    }

    private func insert(_ repo: MessageRepository, companion: Int64, text: String,
                        type: String, fromUser: Bool = false) throws -> Int64 {
        try repo.insert(conversationId: companion, conversationType: .chat,
                        isFromUser: fromUser, senderId: fromUser ? 0 : companion,
                        type: type, content: text, searchContent: text)
    }

    /// **常量必须与 Room 存的值一致**：大写枚举名。
    /// 这条断言是"以后别再改回小写"的那道门。
    func testReasoningTypeMatchesRoomStoredValue() {
        XCTAssertEqual(ChatSession.reasoningType, "REASONING",
                       "Room 存的是枚举名（大写）；@SerialName 的小写只用于备份 JSON")
    }

    /// 小写（历史遗留行）与大写（新写入 / Android 导入）都**不能**出现在会话列表里。
    func testReasoningRowsAreExcludedRegardlessOfCase() throws {
        let (messages, conversations, companions, _, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "小鱼", personality: "温柔")

        try insert(messages, companion: id, text: "你好呀", type: "text", fromUser: true)
        try insert(messages, companion: id, text: "The user's message seems playful", type: "reasoning")
        try insert(messages, companion: id, text: "另一种大小写", type: "REASONING")
        try insert(messages, companion: id, text: "工具卡片", type: "tool_activity")
        try insert(messages, companion: id, text: "TOOL 大写", type: "TOOL_ACTIVITY")

        let row = try XCTUnwrap(try conversations.rows().first)
        XCTAssertEqual(row.messageCount, 1, "只该数那一条用户消息")
        XCTAssertEqual(row.lastMessage, "你好呀",
                       "最后一条消息不能是思考过程（这正是用户看到的英文那句）")
    }

    /// 未读数也不能被思考过程撑大。
    func testUnreadCountIgnoresReasoningRows() throws {
        let (messages, conversations, companions, _, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "小鱼", personality: "温柔")

        // 先读一次，把已读游标推到现在
        XCTAssertTrue(try conversations.markReadThroughLatest(companionId: id) == false,
                      "还没有消息时无可读")

        try insert(messages, companion: id, text: "user", type: "text", fromUser: true)
        _ = try conversations.markReadThroughLatest(companionId: id)

        // 之后只来思考过程（两种大小写）—— 都不该算未读
        try insert(messages, companion: id, text: "thinking…", type: "REASONING")
        try insert(messages, companion: id, text: "thinking…", type: "reasoning")
        XCTAssertEqual(try conversations.unreadCount(companionId: id), 0,
                       "思考过程不是给用户看的消息，不该产生未读")

        // 真来一条可见消息才算未读
        try insert(messages, companion: id, text: "真的回复", type: "text")
        XCTAssertEqual(try conversations.unreadCount(companionId: id), 1)
    }

    /// 已读游标也只推到**可见**消息上（不能被思考过程顶到前面）。
    func testReadCursorIgnoresReasoningRows() throws {
        let (messages, conversations, companions, _, dir) = try makeEnv()
        defer { try? FileManager.default.removeItem(at: dir) }
        let id = try companions.create(name: "小鱼", personality: "温柔")

        let visible = try insert(messages, companion: id, text: "看得见的", type: "text")
        try insert(messages, companion: id, text: "看不见的", type: "reasoning")

        _ = try conversations.markReadThroughLatest(companionId: id)
        let cursor = try XCTUnwrap(try conversations.readCursor(companionId: id))
        XCTAssertEqual(cursor.messageId, visible, "游标应停在最后一条可见消息上")
    }
}
