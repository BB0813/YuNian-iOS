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

    var body: some View {
        NavigationStack {
            Group {
                if let error = environment.startupError {
                    failureView(error)
                } else if environment.runtime != nil {
                    homeView
                } else {
                    ProgressView("正在启动…")
                }
            }
            .background(Color.clear)
            .toolbar(.hidden, for: .navigationBar)
        }
    }

    // MARK: - 首页

    private var homeView: some View {
        @Environment(\.colorScheme) var scheme
        let colors = YuNianTheme.colors(scheme)
        let isDark = scheme == .dark

        return ScrollView {
            VStack(alignment: .leading, spacing: 0) {

                // ── 自绘顶栏（HomeScreen.kt:104-157）──────────────────
                HStack(spacing: YuNianTheme.Space.standard) {
                    // Android 左侧有个 weight(1f) 的占位 Box（HomeScreen.kt:115），
                    // 让标题居中。这里用 Spacer 等价实现。
                    Spacer(minLength: 0)

                    Text("予念")
                        .font(.system(size: 22, weight: .bold))
                        .foregroundStyle(colors.textPrimary)

                    Spacer(minLength: 0)

                    // 两个 36dp 玻璃动作键。Android 是「通讯录 / 我的」，
                    // iOS 侧对应这两个最常用的次级入口。
                    glassAction(systemImage: "wrench.and.screwdriver",
                                colors: colors, isDark: isDark) {
                        // 诊断页：开发者自检项收在这里
                    }
                    glassAction(systemImage: "gearshape",
                                colors: colors, isDark: isDark) { }
                }
                .padding(.horizontal, YuNianTheme.Space.page)
                .padding(.top, YuNianTheme.Space.minUnit)
                .padding(.bottom, YuNianTheme.Space.topBar)

                // 1dp 分割线 —— HomeScreen.kt:164-169
                Rectangle()
                    .fill(colors.textTertiary.opacity(0.25))
                    .frame(height: YuNianTheme.Space.hairline)

                // ── 主体 ────────────────────────────────────────────
                VStack(alignment: .leading, spacing: YuNianTheme.Space.standard) {

                    YuNianSectionTitle(title: "对话")

                    // ⚠️ NavigationLink 必须真的包住卡片，否则首屏不可导航。
                    // 第 123 轮重写时我一度把链接丢了，首页变成死页 —— 已修。
                    NavigationLink {
                        ChatView()
                    } label: {
                        YuNianGlassCard {
                            VStack(alignment: .leading, spacing: YuNianTheme.Space.tight) {
                                HStack(spacing: YuNianTheme.Space.half) {
                                    Image(systemName: "bubble.left.and.bubble.right")
                                        .foregroundStyle(colors.primary)
                                    Text("进入对话")
                                        .font(.body)
                                        .foregroundStyle(colors.textPrimary)
                                    Spacer(minLength: 0)
                                    Image(systemName: "chevron.right")
                                        .font(.system(size: 13, weight: .semibold))
                                        .foregroundStyle(colors.textSecondary)
                                }
                                Text("回合经 Rust 决策，SSE 流式输出。")
                                    .font(YuNianTheme.TextStyle.settingsRowSubtitle)
                                    .foregroundStyle(colors.textSecondary)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .disabled(environment.runtime == nil)

                    YuNianSectionTitle(title: "模型渠道")
                    NavigationLink {
                        ChannelConfigView(provider: providerDraft)
                    } label: {
                        YuNianGlassCard {
                            VStack(alignment: .leading, spacing: YuNianTheme.Space.tight) {
                                HStack(spacing: YuNianTheme.Space.half) {
                                    Image(systemName: channelReady
                                          ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                                        .foregroundStyle(channelReady ? colors.success : colors.warning)
                                    Text(channelTitle)
                                        .font(YuNianTheme.TextStyle.settingsRowTitle)
                                        .foregroundStyle(colors.textPrimary)
                                    Spacer(minLength: 0)
                                    Text("配置")
                                        .font(.footnote)
                                        .foregroundStyle(colors.primary)
                                }
                                Text(channelSubtitle)
                                    .font(YuNianTheme.TextStyle.settingsRowSubtitle)
                                    .foregroundStyle(colors.textSecondary)
                                    .lineLimit(1)
                            }
                        }
                    }
                    .buttonStyle(.plain)

                    YuNianSectionTitle(title: "数据")

                    NavigationLink {
                        MemoryListView()
                    } label: {
                        YuNianGlyphRow("记忆管理", icon: "brain",
                                       disabled: environment.memoryRepo == nil)
                    }
                    .buttonStyle(.plain)
                    .disabled(environment.memoryRepo == nil)

                    NavigationLink {
                        SkillLibraryView()
                    } label: {
                        YuNianGlyphRow("技能库", icon: "books.vertical",
                                       disabled: environment.stores == nil)
                    }
                    .buttonStyle(.plain)
                    .disabled(environment.stores == nil)

                    NavigationLink {
                        StickerLibraryView()
                    } label: {
                        YuNianGlyphRow("表情库", icon: "face.smiling",
                                       disabled: environment.database == nil)
                    }
                    .buttonStyle(.plain)
                    .disabled(environment.database == nil)

                    NavigationLink {
                        BackupImportView()
                    } label: {
                        YuNianGlyphRow("备份导入", icon: "square.and.arrow.down",
                                       disabled: environment.database == nil)
                    }
                    .buttonStyle(.plain)
                    .disabled(environment.database == nil)

                    NavigationLink {
                        MessageSearchView()
                    } label: {
                        YuNianGlyphRow("搜索消息", icon: "magnifyingglass",
                                       disabled: environment.database == nil)
                    }
                    .buttonStyle(.plain)
                    .disabled(environment.database == nil)

                    YuNianSectionTitle(title: "关于")
                    YuNianGlassCard {
                        VStack(alignment: .leading, spacing: YuNianTheme.Space.tight) {
                            Text("予念 · iOS")
                                .font(YuNianTheme.TextStyle.settingsRowTitle)
                                .foregroundStyle(colors.textPrimary)
                            Text("Rust Agent 运行时 + SwiftUI 原生壳。")
                                .font(YuNianTheme.TextStyle.settingsRowSubtitle)
                                .foregroundStyle(colors.textSecondary)
                        }
                    }

                    // 首页底部让位 —— HomeScreen.kt:237 的 80dp
                    Spacer(minLength: YuNianTheme.Space.bottomInset)
                }
                .padding(.horizontal, YuNianTheme.Space.page)
                .padding(.top, YuNianTheme.Space.standard)
            }
        }
        .background(colors.background.ignoresSafeArea())
    }

    // MARK: - 零件

    /// 36dp 玻璃动作键 —— HomeScreen.kt:128-157
    private func glassAction(
        systemImage: String,
        colors: YuNianTheme.Colors,
        isDark: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 20))
                .foregroundStyle(colors.textPrimary)
                .frame(width: 36, height: 36)
                .yuNianGlass(colors, radius: 18,
                             surfaceColor: colors.card.opacity(0.85), isDark: isDark)
        }
        .buttonStyle(.plain)
    }

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
