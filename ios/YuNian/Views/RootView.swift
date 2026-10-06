import SwiftUI

/// 根视图 —— 用户视角的首页。
///
/// ## 设计依据
/// 逐条对照 Android 首页 `feature/profile/.../HomeScreen.kt:95-262`：
/// - 自绘顶栏（非 NavigationBar）：状态栏 inset + `padding(horizontal=16)` +
///   `padding(top=4,bottom=10)`，标题 "予念" 22sp Bold（117-124）
/// - 右侧两个 36dp 玻璃动作键，底 `surfaceVariant@0.85`，
///   图标 20dp `onSurface`，`spacedBy(8)`（128-157）
/// - 1dp 分割线 `outlineVariant@0.25`（164-169）
/// - 会话列表 `LazyColumn(spacedBy(8))`，`contentPadding(bottom=80dp+navBars)`
struct RootView: View {

    @EnvironmentObject private var environment: AppEnvironment
    /// 昵称（owner_name）。⚠️ 不属于渠道配置，故留在首屏而不进 ChannelConfigView。
    @State private var ownerNameDraft = ""
    /// 预选的服务商（进配置页时带上；根视图默认 OPENAI）。
    ///
    /// ⚠️ 为什么必须有这个选择器而不是默认一个：选错 provider 的后果是
    /// 请求打到**错误的 baseUrl**（静默失败或 404）—— 正是最难看懂的那类故障。
    /// provider 由用户显式决定，代码不替他猜。
    @State private var providerDraft = "OPENAI"
    /// 当前一级 tab 索引（0 予念 / 1 通讯录 / 2 我）。
    /// 第 141 轮加 —— 对应 Android MainScreen.kt:121 的 lastTabPage。
    @State private var selectedTab = 0

    var body: some View {
        NavigationStack {
            Group {
                if let error = environment.startupError {
                    failureView(error)
                } else if environment.runtime != nil {
                    tabbedView
                } else {
                    ProgressView("正在启动…")
                }
            }
            .background(Color.clear)
            .toolbar(.hidden, for: .navigationBar)
        }
    }

    // MARK: - 三个一级 tab

    /// 第 141 轮：按 Android 的一级 tab 结构重组。
    ///
    /// Kotlin 侧（MainNavGraph.kt:436-518）：
    /// - `home` / `contacts` / `profile` 三页放进 `HorizontalPager`
    /// - `FloatingGlassBottomNav` 作为**浮层**盖在 pager 之上（align BottomCenter）
    /// - 选中提交是两段式：先更新视觉索引，等一帧再 scrollToPage
    ///   （MainBottomBar.kt:79-91）
    ///
    /// SwiftUI 侧用 `TabView(.page)` + 底部浮层等价实现。
    ///
    /// ⚠️ 第 178 轮：iOS 26 下套 `GlassEffectContainer`。
    ///
    /// 不包容器的话，每个 `glassEffect` 是**相互隔离**的孤岛 ——
    /// 而 Liquid Glass 的招牌行为恰恰是"相邻玻璃之间会融合、拉伸出连续曲面"
    /// （Apple 26 代 HIG）。包了容器，底部玻璃栏与页面里其它玻璃
    /// （卡片、顶栏动作键）才会一起参与融合。
    ///
    /// iOS 17–25 没有这个容器类型，直接走原结构。
    @ViewBuilder
    private var tabbedView: some View {
        if #available(iOS 26.0, *), YuNianGlassStyle.current() == .liquidGlass {
            GlassEffectContainer(spacing: YuNianTheme.Space.standard) {
                tabbedContent
            }
        } else {
            tabbedContent
        }
    }

    /// tab 容器本体（两档共用）。
    private var tabbedContent: some View {
        ZStack(alignment: .bottom) {
            TabView(selection: $selectedTab) {
                // ⚠️ 第 170 轮：tab 0 换成会话列表（ConversationListView），
                // 对齐 Android HomeScreen（feature/profile，底部导航第 1 个 tab）。
                // 原来的 homeView（功能入口聚合页）降级为 tab 2「我」页的入口 ——
                // 那批次级入口（渠道/记忆/技能/诊断）在这里仍然可达。
                ConversationListView()
                    .tag(0)
                ContactsView()
                    .tag(1)
                ProfileView()
                    .tag(2)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))

            // 液态玻璃底栏（浮层）
            YuNianLiquidTabs(
                tabs: [
                    .init(title: "予念", icon: "message", route: "home"),
                    .init(title: "通讯录", icon: "person.2", route: "contacts"),
                    .init(title: "我", icon: "person", route: "profile"),
                ],
                selectedIndex: $selectedTab
            )
            .padding(.horizontal, YuNianTheme.Space.page)
            .padding(.bottom, YuNianTheme.Space.half)
        }
    }

    // MARK: - 首页


    // MARK: - 渠道状态

    private var channelReady: Bool {
        // 只依据「库里有没有一条启用配置」判断 —— 那正是 Rust 每回合要用的东西。
        environment.apiConfigs?.tryActiveConfig() != nil
    }

    private var channelTitle: String {
        if let active = environment.apiConfigs?.tryActiveConfig(),
           YuNianSeed.apiProviderPresets.contains(where: { $0.provider == active.provider }) {
            return active.name.isEmpty ? presetName(active.provider) : active.name
        }
        return "未配置渠道"
    }

    private var channelSubtitle: String {
        guard let active = environment.apiConfigs?.tryActiveConfig() else {
            return "点此选择服务商并填入 API Key"
        }
        let url = active.baseUrl.isEmpty ? "（未填地址）" : active.baseUrl
        return "\(active.model.isEmpty ? "未填模型" : active.model) · \(url)"
    }

    private func presetName(_ provider: String) -> String {
        YuNianSeed.apiProviderPresets.first { $0.provider == provider }?.displayName ?? provider
    }

    // MARK: - 启动失败

    private func failureView(_ error: String) -> some View {
        VStack(spacing: YuNianTheme.Space.cardPadding) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.largeTitle)
                .foregroundStyle(YuNianTheme.Palette.danger)
            Text("启动失败").font(.headline)
            Text(error)
                .font(.caption)
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
            NavigationLink("查看诊断") { DiagnosticsView() }
                .buttonStyle(.bordered)
        }
    }
}
