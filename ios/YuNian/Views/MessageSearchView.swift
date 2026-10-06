import SwiftUI
// ⚠️ 第 130 轮：runSearch() 回查元数据用了 StatementArguments（GRDB）。
// verify_imports 关卡要求显式 import —— 正是它该有的行为。
import GRDB

/// 消息搜索 —— 第 129 轮套上设计系统。
///
/// ## 数据与分词
/// 检索走 `MessageRepository.searchMessageIds`，查询串经
/// `MessageSearchTokenizer.matchQuery` 生成 FTS `MATCH` 表达式
/// （逐字复刻自 Android，回归测试在 `YuNianTests`）。
///
/// ## 与 Android 的一致性
/// 清空即回到未搜索态，不做实时检索（输入中文时逐字查无意义）。
struct MessageSearchView: View {

    @EnvironmentObject private var environment: AppEnvironment
    /// ⚠️ 第 129 轮：语义色跟随系统明暗。
    @Environment(\.colorScheme) private var scheme
    /// 第 133 轮：玻璃顶栏的返回按钮用。
    /// 这些页面由 RootView 的 NavigationLink push 进来，
    /// 系统不自动给可见返回钮，故自绘顶栏需要它。
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var results: [MessageRow] = []
    @State private var searched = false

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }

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
        // 第 133 轮：改用 YuNianGlassPage（对照 Android GlassTopBar），
        // 不再用系统 NavigationBar。
        YuNianGlassPage(title: "搜索消息", onBack: { dismiss() }) {
            VStack(alignment: .leading, spacing: YuNianTheme.Space.standard) {
                if searched && results.isEmpty {
                    emptyState
                }
                ForEach(results) { item in
                    // ⚠️ 参数名不能叫 row —— 会遮蔽同名的 row(_:) 方法，
                    // CI 报 "cannot call value of non-function type
                    // 'MessageSearchView.MessageRow'"。
                    YuNianGlassCard { row(item) }
                }
                Spacer(minLength: YuNianTheme.Space.pageTop)
            }
            .padding(.horizontal, YuNianTheme.Space.page)
            .padding(.top, YuNianTheme.Space.standard)
        }
        // ⚠️ 第 89 轮：这里原来写的是
        //   .searchable(text:placement:.navigationBarDrawMode(displayMode:.automatic), prompt:)
        // CI 报 "type 'SearchFieldPlacement' has no member 'navigationBarDrawMode'"。
        // **那个成员名是我在第 68 轮凭印象编的** —— 当时我还"验证"过
        // iOS 17 API 覆盖并得出"全覆盖、无 iOS 18+ API"的结论，
        // 而那个结论完全建立在记忆上，没有任何权威来源。
        //
        // 已两次被"凭印象写 API 名"咬到（第 23 轮 percentEncodedPath、
        // 本轮 navigationBarDrawMode）。不再猜第三个：退回**一定存在**的
        // `.automatic` placement，它不指定 drawer 行为，由系统决定。
        .searchable(text: $query, placement: .automatic, prompt: "搜索消息内容")
        .onSubmit(of: .search) { runSearch() }
        .onChange(of: query) { _, new in
            // 与 Android 一致：清空即回到未搜索态，不做实时检索（输入中文时逐字查无意义）
            if new.isEmpty {
                results = []
                searched = false
            }
        }
    }

    // MARK: - 子视图

    private var emptyState: some View {
        VStack(spacing: YuNianTheme.Space.cardPadding) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 40))
                .foregroundStyle(colors.textTertiary)
            Text("没有找到")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(colors.textPrimary)
            Text(MessageSearchTokenizer.matchQuery(query).map { _ in
                    "换个关键词试试；中文检索用字/词均可（内部分词处理）"
                } ?? "输入至少一个字符")
                .font(.system(size: 12))
                .foregroundStyle(colors.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, YuNianTheme.Space.page)
        }
        .padding(.top, YuNianTheme.Space.pageTop)
    }

    private func row(_ row: MessageRow) -> some View {
        VStack(alignment: .leading, spacing: YuNianTheme.Space.half) {
            HStack(spacing: YuNianTheme.Space.half) {
                Text(row.isFromUser ? "我" : "对方")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(colors.primary)
                    .padding(.horizontal, YuNianTheme.Space.standard)
                    .padding(.vertical, YuNianTheme.Space.tight)
                    .background(colors.primary.opacity(0.12))
                    .clipShape(Capsule())

                Spacer()

                Text(Self.formatTimestamp(row.timestamp))
                    .font(.system(size: 10))
                    .foregroundStyle(colors.textTertiary)
            }

            Text(row.snippet)
                .font(.system(size: 14))
                .foregroundStyle(colors.textPrimary.opacity(0.9))
                .lineLimit(3)
                .textSelection(.enabled)

            Text("\(row.conversationType == "chat" ? "单聊" : "群聊") #\(row.conversationId)")
                .font(.system(size: 10))
                .foregroundStyle(colors.textTertiary)
        }
    }

    static func formatTimestamp(_ ms: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(ms) / 1000)
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd HH:mm"
        return f.string(from: date)
    }

    // MARK: - 检索

    /// ⚠️ 第 130 轮：补回整体重写时丢掉的 `runSearch()`。
    /// 我重写这个页面只搬了 UI，把真正干活的检索函数漏了，
    /// 而且第一版补的还是凭印象写的（`repo.rows(for:)` 根本不存在）。
    /// 这次**从 git 历史恢复原来的实现** —— 它才是真正跑通过的版本。
    private func runSearch() {
        searched = true
        guard let database = environment.database,
              let repo = try? MessageRepository(database: database) else {
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
}
