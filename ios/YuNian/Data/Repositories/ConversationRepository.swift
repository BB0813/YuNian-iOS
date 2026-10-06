import Foundation

/// 会话列表数据 —— 对应 Android `HomeViewModel` + `ConversationSummaryDao`
/// + `CompanionDao.getAllCompanions()`。
///
/// ## 权威来源（第 170 轮，子代理只读勘察）
/// `feature/profile/.../HomeViewModel.kt`（98 行）：
///
/// | 项 | Kotlin | 行号 |
/// |---|---|---|
/// | 伴侣列表（**决定顺序**） | `SELECT * FROM companions ORDER BY updatedAt DESC` | CompanionDao.kt:14 |
/// | 摘要表 | `SELECT * FROM conversation_summary WHERE sessionType='chat' ORDER BY isPinned DESC, lastMessageTimestamp DESC` | ConversationSummaryDao.kt:13 |
/// | 组装 | `companions.map { ... }` —— summary 的排序**被 associateBy 丢掉** | HomeViewModel.kt:74 |
/// | 未读 | `(summary?.unreadCount ?: 0) > 0` —— **布尔，不显示条数** | HomeViewModel.kt:87 |
/// | 最后消息 preview | `content.take(100)` | ChatRepository.kt:372 |
///
/// ⚠️ **顺序陷阱**：DAO 的 SQL 写了 `ORDER BY isPinned DESC, lastMessageTimestamp DESC`，
/// 但 ViewModel 用 `summaries.associateBy { it.sessionId }` 转 Map 后**完全丢弃**，
/// 最终顺序由 `companions` 的 `updatedAt DESC` 决定 —— 置顶排序在 UI 上不生效。
/// 这是 Android 的现状。iOS 若要"修正"它，就是与 Android 分叉，
/// 故此处**照搬现状**（按 updatedAt DESC），并在注释中记下。
///
/// ⚠️ **`conversation_summary` 表在 iOS schema 里存在但没人写过**
/// （第 62 轮建表至今无写入方）。本实现改为**直接联表查 messages** 取最后一条，
/// 不依赖那张物化冗余表 —— Android 侧它是写入时增量维护的，
/// iOS 侧没有那套维护逻辑，硬用会得到恒空列表。
struct ConversationRepository {

    private let database: YuNianDatabase

    init(database: YuNianDatabase) {
        self.database = database
    }

    /// 列表项。
    struct Row: Identifiable, Equatable {
        let companionId: Int64
        var name: String
        var avatarUrl: String?
        /// 最后一条消息正文（已截到 100 字符）。nil = 从未聊过。
        var lastMessage: String?
        var lastMessageAt: Int64?
        /// 是否来自用户（副标题是否要加"我："前缀由 UI 决定）。
        var lastMessageFromUser: Bool = false
        /// 有无未读 —— **布尔，不显示条数**（Android HomeViewModel.kt:87）。
        var hasUnread: Bool = false
        /// 亲密度（Android 侧 companions 表有该字段，列表 UI 未用，此处保留备用）。
        var intimacy: Int = 0

        var id: Int64 { companionId }

        /// 列表副标题：最后消息，或 Kotlin 的兜底文案（HomeScreen.kt:538）。
        var preview: String {
            guard let lastMessage, !lastMessage.isEmpty else {
                return "还没有聊天记录，开始聊天吧"
            }
            return lastMessage
        }
    }

    // MARK: - 查询

    /// 取会话列表。
    ///
    /// ## 顺序
    /// `ORDER BY c.updatedAt DESC` —— 与 Android 的
    /// `CompanionDao.getAllCompanions()` 一致（HomeViewModel.kt:74 + CompanionDao.kt:14）。
    ///
    /// ## 最后一条消息
    /// Android 靠物化的 `conversation_summary.lastMessagePreview`
    /// （ChatRepository.kt:372 写入时维护，`take(100)`）。
    /// iOS 侧没有那个维护链路，故用相关子查询直接取：
    /// 与 `MessageDao.getLastMessageSync`（:225）同一口径
    /// (`ORDER BY timestamp DESC, id DESC LIMIT 1`)，
    /// 但**排除 REASONING**（Kotlin 的未读统计排它，
    /// 而 rebuildSummaryForChat 只显式排 TOOL_ACTIVITY ——
    /// 这里取更严格的那个，避免列表里出现"思考过程"）。
    func rows(limit: Int = 200) throws -> [Row] {
        try database.pool.read { db in
            let sql = """
                SELECT
                    c.id            AS companionId,
                    c.name          AS name,
                    c.avatarUrl     AS avatarUrl,
                    c.intimacy      AS intimacy,
                    last.content    AS lastContent,
                    last.ts         AS lastTs,
                    last.fromUser   AS lastFromUser
                FROM companions c
                LEFT JOIN (
                    SELECT m.conversationId, m.timestamp AS ts, m.isFromUser AS fromUser,
                           b.content AS content,
                           ROW_NUMBER() OVER (
                               PARTITION BY m.conversationId
                               ORDER BY m.timestamp DESC, m.id DESC
                           ) AS rn
                    FROM messages m
                    LEFT JOIN message_bodies b ON b.messageId = m.id
                    WHERE m.conversationType = 'chat'
                      AND m.type NOT IN ('REASONING', 'TOOL_ACTIVITY')
                ) last ON last.companionId = c.id AND last.rn = 1
                ORDER BY c.updatedAt DESC
                LIMIT ?
                """
            let rows = try Row.fetchAll(db, sql: sql, arguments: [limit])
            return rows.map { r in
                var row = Row(
                    companionId: r["companionId"] as Int64? ?? 0,
                    name: r["name"] as String? ?? "",
                    avatarUrl: r["avatarUrl"] as String?,
                    lastMessage: r["lastContent"] as String?
                        .map { String($0.prefix(100)) },      // take(100)
                    lastMessageAt: r["lastTs"] as Int64?,
                    intimacy: r["intimacy"] as Int? ?? 0
                )
                row.lastMessageFromUser = (r["lastFromUser"] as Int? ?? 0) != 0
                return row
            }
        }
    }

    /// 未读判定。
    ///
    /// Android 用 `conversation_summary.unreadCount > 0`
    /// （HomeViewModel.kt:87），而 unreadCount 由
    /// `MessageDao.getUnreadMessageCount`（:419）那段 cursor 语义维护。
    ///
    /// iOS 侧没有 read-through cursor 表，故简化为
    /// **"存在 AI 侧消息即视为有未读"** —— 这是有意的降级：
    /// 进过对话页后应当已读，但 iOS 的 ChatView 目前**不写已读游标**
    /// （`markReadThroughLatest` 未复刻）。
    ///
    /// ⚠️ 如实记录：因此这个未读标记在 iOS 上会**偏乐观**（聊过就恒亮）。
    /// 精确复刻需要 read-through cursor，属下轮。
    func hasUnread(companionId: Int64) throws -> Bool {
        try database.pool.read { db in
            let count: Int = try Row.fetchOne(db, sql: """
                SELECT COUNT(*) AS c
                FROM messages
                WHERE conversationId = ? AND conversationType = 'chat'
                  AND isFromUser = 0
                  AND type NOT IN ('REASONING', 'TOOL_ACTIVITY')
                """, arguments: [companionId])?["c"] ?? 0
            return count > 0
        }
    }

    /// 会话数量（首页计数文本用，对应 HomeScreen.kt:185-189）。
    func chatCount() throws -> Int {
        try database.pool.read { db in
            try Int.fetchOne(db, sql: "SELECT COUNT(*) AS c FROM companions") ?? 0
        }
    }
}
