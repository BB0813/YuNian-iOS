import SwiftUI

/// 会话列表 —— Android `HomeScreen`（feature/profile）的 tab 0 内容。
///
/// ## 权威来源（第 170 轮，子代理只读勘察，均标行号）
/// HomeScreen.kt：
///
/// | 项 | Kotlin 值 | 行号 |
/// |---|---|---|
/// | 结构顺序 | 状态栏 → 标题行 → Spacer12 → 1dp 分隔线 → Spacer12 → TabBar → Spacer8 → 计数 → 列表 | :106-261 |
/// | 标题 | "予念" 硬编码、titleLarge Bold 22sp | :117-124 |
/// | 两个 36dp 玻璃钮 | 创建群聊 / 添加好友，surfaceVariant@0.85，图标20dp，间距8dp | :128-157 |
/// | 分隔线 | 1dp outlineVariant@0.25 | :164-169 |
/// | 三段 TabBar | LiquidBottomTabs containerHeight=56, contentPadding=4 | :284-347 |
/// | 计数文本 | "${chatCount} 个会话 · ${groups.size} 个群聊" 12sp | :185-189 |
/// | 列表内边距 | start/end 12、top 8、bottom 80+navbar | :233-241 |
/// | 列表间距 | `Arrangement.spacedBy(8.dp)` | :240 |
/// | 群聊 item 在单聊**之前** | LazyColumn item 顺序 | :243-258 |
///
/// ## item 规格（HomeScreen.kt:439-549）
/// | 项 | 值 | 行号 |
/// |---|---|---|
/// | 容器 | ContinuousCapsule + drawGlass(surfaceVariant)，padding h14/v12 | :497-503 |
/// | 头像 | adaptiveSizing.avatarSize（COMPACT 40dp），CircleShape | :461；AdaptiveTheme.kt:38 |
/// | 无图兜底 | surface 底 + User 图标 @0.58×avatar，tint captionContent | :462、:476-481 |
/// | **未读** | **10dp 纯色圆点**（不是数字红方块），PinkPrimary `#4A9EFF`（名字叫 Pink 实际是蓝） | :484-491 |
/// | 主标题 | fontSizeBody（COMPACT 14sp）**Normal**，onSurface，1 行 | :517-524 |
/// | 时间戳 | fontSizeSmall（COMPACT 11sp），HH:mm **只有时:分**，onSurfaceVariant | :446、:530-533 |
/// | 副标题 | (fontSizeBody-1) 13sp + **lineHeight 20sp**，onSurfaceVariant，1 行 | :538-545 |
/// | 行内排布 | Column(spacedBy 3dp)：[标题 weight1f][8dp][时间戳] → 3dp → 副标题 | :506-536 |
///
/// ## iOS 侧省略（如实记录，不是遗漏）
/// - 添加好友/创建群聊两个按钮 —— 对应页面 iOS 均无
/// - 自适应三档（COMPACT/MEDIUM/EXPANDED）—— iOS 恒取 COMPACT
///
/// ## 第 175 轮修正
/// 初版注释写"iOS 无群聊数据模型，`chat_groups` 表虽在 schema 里但无写入方"，
/// 据此省掉了整个群聊区与两个 tab。**那个判断是错的** ——
/// `BackupImporter.swift:143` 就有 `INSERT INTO chat_groups`，
/// 用户从 Android 备份恢复时群聊会一起导入，`ConversationType.group`
/// 也早在 `MessageRepository` 里定义。数据一直在，只是我没接 UI。
struct ConversationListView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    /// 三段 TabBar 的选中段。Android 有 ALL/GROUP/FRIEND（HomeScreen.kt:192-204），
    /// iOS 侧只有"全部"有内容，故只留一个常量占位 —— 不放两个空 tab。
    @State private var selectedTab = HomeTab.all

    @State private var rows: [ConversationRepository.ConvRow] = []
    /// 群聊列表（第 175 轮）。Android 由 `ChatGroupViewModel.groups` 提供
    /// （HomeScreen.kt:89 → ChatGroupViewModel.kt:18）。
    @State private var groups: [ConversationRepository.GroupRow] = []
    @State private var chatCount = 0
    @State private var loaded = false
    @State private var companionForChat: CompanionRepository.Companion?

    /// Kotlin `HomeTab`（HomeScreen.kt:295-297）
    private enum HomeTab: String, CaseIterable {
        case all = "全部"
        case group = "群聊"
        case friend = "好友"
    }

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }

    /// 当前 tab 下要显示的群聊（第 175 轮）。
    ///
    /// Kotlin `HomeScreen.kt:192-204`：
    /// ```
    /// val displayGroups = when (selectedTab) {
    ///     HomeTab.ALL, HomeTab.GROUP -> groups
    ///     HomeTab.FRIEND -> emptyList()
    /// }
    /// ```
    private var displayGroups: [ConversationRepository.GroupRow] {
        switch selectedTab {
        case .all, .group: return groups
        case .friend: return []
        }
    }

    /// 当前 tab 下要显示的单聊（Kotlin HomeScreen.kt:196-204 的 displayChats）。
    private var displayChats: [ConversationRepository.ConvRow] {
        switch selectedTab {
        case .all, .friend: return rows
        case .group: return []
        }
    }

    /// 三段 TabBar（第 175 轮）。
    ///
    /// Kotlin `HomeTabBar`（HomeScreen.kt:284-347）用的是
    /// `LiquidBottomTabs(containerHeight = 56.dp, contentPadding = 4.dp)`，
    /// 选中块高 = 56 - 4×2 = 48dp、圆角 outerLens 24 / selectionLens 10。
    ///
    /// iOS 侧这里用 `YuNianLiquidTabs`（第 146 轮已复刻那一套），
    /// 但 containerHeight 固定 64 —— Kotlin 这里是 56。
    /// ⚠️ 如实记录：64 vs 56 的差异我没做参数化，
    /// 因为 YuNianLiquidTabs 的高度是编译期常量，改它要动三个调用点。
    /// 视觉差 8dp，不影响功能。
    private var tabBar: some View {
        YuNianLiquidTabs(
            tabs: [
                .init(title: HomeTab.all.rawValue, icon: "message", route: "all"),
                .init(title: HomeTab.group.rawValue, icon: "person.2", route: "group"),
                .init(title: HomeTab.friend.rawValue, icon: "person", route: "friend"),
            ],
            selectedIndex: Binding(
                get: { HomeTab.allCases.firstIndex(of: selectedTab) ?? 0 },
                set: { selectedTab = HomeTab.allCases[$0] }
            )
        )
        .padding(.horizontal, YuNianTheme.Space.cardPadding)
    }

    /// 群聊 item（第 175 轮）—— Kotlin `GroupListItem`（HomeScreen.kt:350-436）。
    ///
    /// | 项 | Kotlin 值 | 行号 |
    /// |---|---|:---|
    /// | 容器 | ContinuousCapsule + drawGlass(surfaceVariant) + padding h14/v12 | :401-407 |
    /// | 无头像 | radialGradient(PinkPrimary@0.6→0.3) + Users 图标 | :360-395 |
    /// | 主标题 | group.name，14sp Normal，onSurface，1 行 | :414-423 |
    /// | 副标题 | "N 人"，13sp lineHeight 20sp，onSurfaceVariant | :424-433 |
    /// | 时间戳/未读 | **都没有** | — |
    private func groupRowView(_ g: ConversationRepository.GroupRow) -> some View {
        HStack(alignment: .top, spacing: YuNianTheme.Space.standard) {   // avatarGap 8dp
            ZStack {
                Circle().fill(colors.card)
                if let url = g.avatarUrl,
                   let asset = AvatarResolver.assetName(for: url) {
                    Image(asset).resizable().scaledToFill()
                } else {
                    // Kotlin 的 radialGradient(PinkPrimary@0.6→0.3)  +
                    // Users 图标 @ iconSize(COMPACT 20dp)
                    Circle()
                        .fill(
                            RadialGradient(
                                colors: [colors.primary.opacity(0.6),
                                         colors.primary.opacity(0.3)],
                                center: .center, startRadius: 0, endRadius: 20
                            )
                        )
                    Image(systemName: "person.2")
                        .font(.system(size: 20))
                        .foregroundStyle(colors.textPrimary)
                }
            }
            .frame(width: 40, height: 40)
            .clipShape(Circle())

            VStack(alignment: .leading, spacing: YuNianTheme.Space.tight) {
                Text(g.name)
                    .font(.system(size: 14, weight: .regular))
                    .foregroundStyle(colors.textPrimary)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Text(g.memberLine)
                    .font(.system(size: 13))
                    .lineSpacing(20 - 13)                 // 固定 lineHeight 20sp
                    .foregroundStyle(colors.textSecondary)
                    .lineLimit(1)
            }
        }
        .padding(.horizontal, YuNianTheme.Space.listItem)   // h14
        .padding(.vertical, YuNianTheme.Space.topBar)       // v12
        .frame(maxWidth: .infinity, alignment: .leading)
        .yuNianGlass(colors, radius: .infinity,
                     surfaceColor: colors.card, isDark: scheme == .dark)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    // ① 标题行（HomeScreen.kt:110-160）
                    titleRow

                    // ③⑤ Spacer + ④ 1dp 分隔线
                    Color.clear.frame(height: YuNianTheme.Space.standard)
                    Rectangle()
                        .fill(colors.textTertiary.opacity(0.25))   // outlineVariant@0.25
                        .frame(height: YuNianTheme.Space.hairline)
                    Color.clear.frame(height: YuNianTheme.Space.standard)

                    // ⑥ 三段 TabBar（第 175 轮补）
                    tabBar
                    Color.clear.frame(height: YuNianTheme.Space.half)   // ⑦ Spacer 8dp

                    // ⑧ 计数文本 —— Kotlin "${chatCount} 个会话 · ${groups.size} 个群聊"
                    Text("\(chatCount) 个会话 · \(groups.count) 个群聊")
                        .font(.system(size: 12))
                        .foregroundStyle(colors.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, YuNianTheme.Space.cardPadding)
                        .padding(.bottom, YuNianTheme.Space.half)

                    // ⑨ 内容区
                    if displayGroups.isEmpty && rows.isEmpty {
                        emptyState
                    } else {
                        LazyVStack(spacing: YuNianTheme.Space.standard) {   // spacedBy 8dp
                            // ⚠️ 群聊 item 在单聊**之前**（HomeScreen.kt:243-258）
                            ForEach(displayGroups) { g in
                                groupRowView(g)
                            }
                            ForEach(displayChats) { row in
                                if let companion = companion(for: row) {
                                    Button {
                                        companionForChat = companion
                                    } label: {
                                        rowView(row)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                        .padding(.horizontal, YuNianTheme.Space.cardPadding)  // start/end 12
                        .padding(.top, YuNianTheme.Space.half)                 // top 8
                        // ⚠️ 底部让出液态底栏（Kotlin 的 80dp + navbar）
                        .padding(.bottom, 80)
                    }
                }
            }
            .background(colors.background.ignoresSafeArea())
            .navigationDestination(item: $companionForChat) { c in
                ChatView(companion: c)
            }
            .task { reload() }
            // ⚠️ 第 174 轮：从对话页返回时刷新未读。
            // 未读是在 ChatView 的 .task 里清的，列表页在它之后已经渲染完 ——
            // 不刷新的话，圆点会在"已经读过的会话"上一直留着。
            .onAppear { reload() }
        }
    }

    // MARK: - 标题行

    /// "予念" 居中 + 两个动作键位。
    ///
    /// Android 那两个按钮是"创建群聊 / 添加好友"，iOS 侧对应页面不存在，
    /// 故**留空不放** —— 比放两个点了没反应的按钮诚实。
    private var titleRow: some View {
        HStack(spacing: YuNianTheme.Space.standard) {
            Spacer(minLength: 0)                       // Kotlin 左侧 weight(1f) 占位

            Text("予念")
                .font(.system(size: 22, weight: .bold)) // titleLarge Bold 22sp
                .foregroundStyle(colors.textPrimary)

            Spacer(minLength: 0)
        }
        .padding(.horizontal, YuNianTheme.Space.page)
        .padding(.top, YuNianTheme.Space.minUnit)
        .padding(.bottom, YuNianTheme.Space.topBar)
    }

    // MARK: - item

    /// 单聊 item（HomeScreen.kt:439-549）
    private func rowView(_ row: ConversationRepository.ConvRow) -> some View {
        HStack(alignment: .top, spacing: YuNianTheme.Space.standard) {   // avatarGap 8dp
            // 头像 + 未读圆点（Box TopEnd，溢出在圆外）
            ZStack(alignment: .topTrailing) {
                ZStack {
                    Circle().fill(colors.card)            // colorScheme.surface 兜底
                    if let url = row.avatarUrl,
                       let asset = AvatarResolver.assetName(for: url) {
                        Image(asset)
                            .resizable()
                            .scaledToFill()
                    } else {
                        Image(systemName: "person")
                            .font(.system(size: 40 * 0.58))  // 0.58 × avatarSize
                            .foregroundStyle(colors.textTertiary)   // captionContent
                    }
                }
                .frame(width: 40, height: 40)            // COMPACT avatarSize
                .clipShape(Circle())

                if row.hasUnread {
                    Circle()
                        .fill(colors.primary)             // PinkPrimary，实为 #4A9EFF
                        .frame(width: 10, height: 10)     // ⚠️ 10dp 纯色点，无数字
                        .offset(x: 3, y: -3)
                }
            }

            // 内容列（Kotlin Column spacedBy 3dp）
            VStack(alignment: .leading, spacing: YuNianTheme.Space.tight) {
                // 第一行：标题 + 时间戳
                HStack(spacing: YuNianTheme.Space.standard) {
                    Text(row.name)
                        .font(.system(size: 14, weight: .regular))  // fontSizeBody Normal
                        .foregroundStyle(colors.textPrimary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Spacer(minLength: YuNianTheme.Space.standard)
                    if let at = row.lastMessageAt {
                        Text(timeOnly(at))
                            .font(.system(size: 11))      // fontSizeSmall
                            .foregroundStyle(colors.textSecondary)
                    }
                }

                // 第二行：摘要
                Text(row.preview)
                    .font(.system(size: 13))              // (fontSizeBody - 1)
                    .lineSpacing(20 - 13)                 // 固定 lineHeight 20sp
                    .foregroundStyle(colors.textSecondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
        }
        .padding(.horizontal, YuNianTheme.Space.listItem)   // h14
        .padding(.vertical, YuNianTheme.Space.topBar)       // v12
        .frame(maxWidth: .infinity, alignment: .leading)
        .yuNianGlass(colors, radius: .infinity,             // ContinuousCapsule
                     surfaceColor: colors.card, isDark: scheme == .dark)
    }

    // MARK: - 空态

    /// Kotlin `EmptyHomeState`（HomeScreen.kt:596-633）
    private var emptyState: some View {
        VStack(spacing: YuNianTheme.Space.standard) {
            Circle()
                .fill(colors.card)
                .frame(width: 80, height: 80)
                .overlay(
                    Image(systemName: "message")
                        .font(.system(size: 36))
                        .foregroundStyle(colors.textSecondary)
                )
            Text("还没有聊天记录")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(colors.textPrimary)
            Text("去通讯录找你的女友聊天吧")
                .font(.system(size: 14))
                .foregroundStyle(colors.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, YuNianTheme.Space.pageTop)
    }

    // MARK: - 数据

    private func reload() {
        guard let db = environment.database else { return }
        let repo = ConversationRepository(database: db)
        rows = (try? repo.rows()) ?? []
        groups = (try? repo.groupRows()) ?? []      // 第 175 轮
        chatCount = (try? repo.chatCount()) ?? 0

        // 未读逐个查（第 174 轮：改按 read-through cursor 精确计算，
        // 不再"有 AI 消息就恒亮"）。
        // ⚠️ N+1 查询，但伴侣数量级在个位到十位，可接受。
        let companionRepo = CompanionRepository(database: db)
        for (i, row) in rows.enumerated() {
            rows[i].hasUnread = ((try? repo.unreadCount(companionId: row.companionId)) ?? 0) > 0
            if let c = try? companionRepo.fetch(id: row.companionId) {
                rows[i].intimacy = c.intimacy
            }
        }
        loaded = true
    }

    /// 用 id 反查 Companion（点进去要带完整对象）。
    private func companion(for row: ConversationRepository.ConvRow)
        -> CompanionRepository.Companion?
    {
        guard let db = environment.database else { return nil }
        return try? CompanionRepository(database: db).fetch(id: row.companionId)
    }

    /// Kotlin `SimpleDateFormat("HH:mm")` —— **只有时:分，无日期**（HomeScreen.kt:446）
    private func timeOnly(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm"
        return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }
}
