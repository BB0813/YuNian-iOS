import SwiftUI
import GRDB   // StatementArguments（ids 逐个绑定）

/// 消息搜索界面（第 142 轮发现 FTS 只写不读，此处补上查询侧消费方）。
///
/// ## 背景
/// `YuNianDatabase.searchMessageIds(matching:)` 与 FTS 的写入/维护侧早已实现并通过
/// 真实 SQLite 验证，但 iOS 侧**没有任何界面调用它** —— 也就是 FTS 索引只写不读。
/// 本视图是那个缺失的消费方。
///
/// ## 与 Android 的对应
/// Android 聊天页顶部有搜索框，输入后走 `MessageDao` 的 FTS 查询。
/// 这里做最小可用版：全局搜索（跨会话），结果按 rowid 倒序（新→旧）。
struct MessageSearchView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @State private var query = ""
    @State private var results: [MessageRow] = []
    @State private var searched = false

    /// 一条命中（只取展示需要的列）。
    struct MessageRow: Identifiable {
        var id: Int64 { messageId }
        let messageId: Int64
        let conversationId: Int64
        let conversationType: String
        let isFromUser: Bool
        let timestamp: Int64
        let snippet: String
    }

    var body: some View {
        List {
            if searched && results.isEmpty {
                ContentUnavailableView {
                    Label("没有找到", systemImage: "magnifyingglass")
                } description: {
                    Text(MessageSearchTokenizer.matchQuery(query).map { _ in
                        "换个关键词试试；中文检索用字/词均可（内部分词处理）"
                    } ?? "输入至少一个字符")
                }
            }
            ForEach(results) { row in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(row.isFromUser ? "我" : "对方")
                            .font(.caption2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(row.isFromUser ? Color.blue.opacity(0.15) : Color.gray.opacity(0.15))
                            .clipShape(Capsule())
                        Spacer()
                        Text(Self.formatTimestamp(row.timestamp))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    Text(row.snippet)
                        .font(.callout)
                        .lineLimit(3)
                        .textSelection(.enabled)
                    Text("\(row.conversationType == "chat" ? "单聊" : "群聊") #\(row.conversationId)")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                }
                .padding(.vertical, 2)
            }
        }
        .listStyle(.plain)
        .navigationTitle("搜索消息")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, placement: .navigationBarDrawMode(displayMode: .automatic), prompt: "搜索消息内容")
        .onSubmit(of: .search) { runSearch() }
        .onChange(of: query) { _, new in
            // 与 Android 一致：清空即回到未搜索态，不做实时检索（输入中文时逐字查无意义）
            if new.isEmpty {
                results = []
                searched = false
            }
        }
    }

    private func runSearch() {
        searched = true
        guard let database = environment.database, let repo = try? MessageRepository(database: database) else {
            results = []
            return
        }
        // ① FTS 命中 message id
        let ids = (try? database.searchMessageIds(matching: query, limit: 50)) ?? []
        guard !ids.isEmpty else {
            results = []
            return
        }
        // ② 回查元数据 + 正文
        let marks = ids.map { _ in "?" }.joined(separator: ",")
        do {
            let rows = try database.pool.read { db in
                try Row.fetchAll(db, sql: """
                    SELECT m.id AS messageId, m.conversationId, m.conversationType,
                           m.isFromUser, m.timestamp, b.content
                    FROM messages m
                    LEFT JOIN message_bodies b ON b.messageId = m.id
                    WHERE m.id IN (\(marks))
                    """, arguments: StatementArguments(ids))
            }
            results = rows.map { r in
                MessageRow(
                    messageId: r["messageId"] as Int64? ?? 0,
                    conversationId: r["conversationId"] as Int64? ?? 0,
                    conversationType: r["conversationType"] as String? ?? "",
                    isFromUser: (r["isFromUser"] as Int? ?? 0) != 0,
                    timestamp: r["timestamp"] as Int64? ?? 0,
                    snippet: r["content"] as String? ?? ""
                )
            }
        } catch {
            results = []
        }
    }

    private static func formatTimestamp(_ ms: Int64) -> String {
        let d = Date(timeIntervalSince1970: Double(ms) / 1000)
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "yyyy-MM-dd HH:mm"
        return f.string(from: d)
    }
}
