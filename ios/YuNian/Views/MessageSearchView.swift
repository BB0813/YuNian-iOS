import SwiftUI

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
        ScrollView {
            VStack(alignment: .leading, spacing: YuNianTheme.Space.standard) {
                if searched && results.isEmpty {
                    emptyState
                }
                ForEach(results) { row in
                    YuNianGlassCard { row(row) }
                }
                Spacer(minLength: YuNianTheme.Space.pageTop)
            }
            .padding(.horizontal, YuNianTheme.Space.page)
            .padding(.top, YuNianTheme.Space.standard)
        }
        .background(colors.background.ignoresSafeArea())
        .navigationTitle("搜索消息")
        .navigationBarTitleDisplayMode(.inline)
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
}
