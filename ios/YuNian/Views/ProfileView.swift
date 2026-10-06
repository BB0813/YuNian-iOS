import SwiftUI

/// 「我」页 —— 一级 tab 之一。
///
/// ## 权威来源（第 141 轮，子代理只读勘察，均标行号）
/// `feature/profile/.../ProfileScreen.kt`：
///
/// | 项 | Kotlin 值 | 行号 |
/// |---|---|---|
/// | 页面框架 | 无顶栏、`padding(top = 48.dp)` | :98 |
/// | 资料卡 | 16dp 圆角 + surface@0.85 玻璃、行 padding h20/v14 | :101-110 |
/// | 头像 | 56dp Box、圆角 12dp、底 surfaceVariant、兜底图标 28dp | :113-133 |
/// | 用户名 | 18sp Bold onSurface | :142-143 |
/// | 签名 | 13sp onSurfaceVariant（空白时 ×0.5，显示"点击编辑个人资料"） | :148-154 |
/// | 右箭头 | ChevronRight 20dp onSurfaceVariant | :157-162 |
/// | 统计行 | 16dp 圆角 + surface@0.85、padding h20/v14、SpaceEvenly | :168-191 |
/// | 统计值/标签 | 18sp Bold / 12sp | :240、242 |
/// | 统计分隔条 | 1dp × 36dp、outline@0.3 | :248 |
/// | 菜单卡 | 16dp 圆角 + surfaceVariant@0.9 玻璃、padding h16/v8 | :259-265 |
/// | 菜单行 | 图标24dp + 间距12dp + 标题16sp Medium + 副标题12sp + 箭头20dp | :275-282 |
/// | 菜单分隔线 | 0.5dp outline、左缩进 36dp | :284 |
/// | 区块间距 | 12 / 16 / 12 / 12 / 80dp | :166、193、203、210、215 |
///
/// ## iOS 侧省略（如实记录）
/// Android 的 8 个菜单项目里，`role_manager` / `plugin_settings` / `theme` /
/// `background_settings` / `general_settings` / `about` / `profile_settings`
/// **在 iOS 侧没有对应页面**。本页只放真有目的地的项
/// （记忆管理 / 模型渠道 / 表情库 / 备份导入 / 搜索消息 / 诊断），
/// 不放点了没反应的占位行。
struct ProfileView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var memoryCount = 0
    @State private var stickerCount = 0
    @State private var schemaVersion = "—"

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }

    /// 用户昵称（iOS 侧即 owner_name，存 Keychain）。
    private var nickname: String {
        KeychainStore.string(for: KeychainStore.Key.ownerName) ?? ""
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // Kotlin :98 固定 top padding
                Color.clear.frame(height: 48)

                // ① 资料卡（:101-110）
                profileCard
                    .padding(.horizontal, YuNianTheme.Space.page)
                Spacer(minLength: 12)                              // :166

                // ② 统计行（:168-191）
                statsRow
                    .padding(.horizontal, YuNianTheme.Space.page)
                Spacer(minLength: 16)                              // :193

                // ③ 菜单组 1（:199-202）
                menuGroup([
                    .init(icon: "brain", title: "记忆管理", subtitle: "查看记住的关于你的事"),
                    .init(icon: "cpu", title: "模型渠道", subtitle: "配置 API Key 与服务商"),
                ])
                Spacer(minLength: 12)

                // ④ 菜单组 2（:204-209 的 iOS 可用子集）
                menuGroup([
                    .init(icon: "face.smiling", title: "表情库", subtitle: "导入与管理表情"),
                    .init(icon: "tray.and.arrow.down", title: "备份导入", subtitle: "从 Android 备份恢复"),
                    .init(icon: "magnifyingglass", title: "搜索消息", subtitle: "全文检索聊天记录"),
                    // ⚠️ 第 150 轮：生图入口。
                    // Kotlin 侧是 ImageGenCoordinator 自动触发（关键词→概率→冷却），
                    // iOS 侧那套判定未移植，故这是**手动触发页**。
                    .init(icon: "photo.badge.sparkles", title: "AI 生图",
                          subtitle: "OpenAI 兼容协议，手动生成"),
                    .init(icon: "stethoscope", title: "诊断", subtitle: "运行时与契约自检"),
                ])
                Spacer(minLength: 12)

                // ⑤ 关于本端（Android 的 about 在 iOS 尚无独立页，用一行说明代替）
                menuGroup([
                    .init(icon: "info.circle", title: "关于予念 iOS",
                          subtitle: "Rust Agent 运行时 + SwiftUI 原生壳"),
                ])

                Spacer(minLength: YuNianTheme.Space.bottomInset)   // :215
            }
        }
        .background(colors.background.ignoresSafeArea())
        .task { reload() }
    }

    // MARK: - 资料卡

    private var profileCard: some View {
        HStack(spacing: 14) {                                     // :137 Spacer(width=14)
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous)   // :115-116
                    .fill(colors.card)                                  // 底 surfaceVariant
                Image(systemName: "person")
                    .font(.system(size: 28))                            // :132 28dp
                    .foregroundStyle(colors.textSecondary)
            }
            .frame(width: 56, height: 56)                               // :113 56dp

            VStack(alignment: .leading, spacing: 4) {                   // :147 名↔签名 4dp
                Text(nickname.isEmpty ? "我" : nickname)
                    .font(.system(size: 18, weight: .bold))             // :142-143
                    .foregroundStyle(colors.textPrimary)
                    .lineLimit(1)
                Text("点击编辑个人资料")
                    .font(.system(size: 13))                            // :150 13sp
                    .foregroundStyle(colors.textSecondary.opacity(0.5)) // 空白时 ×0.5
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(colors.textSecondary)                   // :157-162
        }
        .padding(.horizontal, 20)                                        // :41 h=20
        .padding(.vertical, 14)                                          // :61 v=14
        .yuNianGlass(colors, radius: YuNianTheme.Radius.card,
                     surfaceColor: colors.surface.opacity(0.85), isDark: scheme == .dark)
    }

    // MARK: - 统计行

    private var statsRow: some View {
        HStack(spacing: 0) {
            statItem(value: "\(memoryCount)", label: "AI 伴侣")          // :181
            statDivider                                                  // :182
            statItem(value: stickerCount == 0 ? "—" : "\(stickerCount)", label: "表情")
            statDivider                                                  // :189
            statItem(value: schemaVersion, label: "Schema")
        }
        .padding(.horizontal, 20)                                         // :178 h=20
        .padding(.vertical, 14)                                           // v=14
        .yuNianGlass(colors, radius: YuNianTheme.Radius.card,
                     surfaceColor: colors.surface.opacity(0.85), isDark: scheme == .dark)
    }

    private func statItem(value: String, label: String) -> some View {
        VStack(spacing: 2) {                                              // :241 值↔标签 2dp
            Text(value)
                .font(.system(size: 18, weight: .bold))                   // :240 18sp Bold
                .foregroundStyle(colors.textPrimary)
                .lineLimit(1)
            Text(label)
                .font(.system(size: 12))                                  // :242 12sp
                .foregroundStyle(colors.textSecondary)
        }
        .frame(maxWidth: .infinity)
    }

    private var statDivider: some View {
        Rectangle()
            .fill(colors.divider.opacity(0.3))                            // :248 outline@0.3
            .frame(width: 1, height: 36)                                  // :248 1x36dp
    }

    // MARK: - 菜单

    struct MenuItem: Identifiable {
        let id = UUID()
        let icon: String
        let title: String
        let subtitle: String
    }

    /// 菜单卡（Kotlin SolidMenuGroup :259-265）
    private func menuGroup(_ items: [MenuItem]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.element.id) { idx, item in
                if idx > 0 {
                    // 分隔线：0.5dp outline、左缩进 36dp（:284）
                    Rectangle()
                        .fill(colors.divider)
                        .frame(height: 0.5)
                        .padding(.leading, 36)
                }
                NavigationLink {
                    destination(for: item)
                } label: {
                    menuRow(item)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, YuNianTheme.Space.page)                     // :258 h=16
        .padding(.vertical, YuNianTheme.Space.standard)                   // :265 v=8
        .yuNianGlass(colors, radius: YuNianTheme.Radius.card,
                     surfaceColor: colors.card.opacity(0.9), isDark: scheme == .dark)
    }

    /// 菜单行（Kotlin SolidMenuItem :275-282）
    private func menuRow(_ item: MenuItem) -> some View {
        HStack(spacing: 12) {                                             // :277 图标↔文字 12dp
            Image(systemName: item.icon)
                .font(.system(size: 24))                                  // :276 24dp
                .foregroundStyle(colors.textPrimary)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {                      // :280 主↔副 2dp
                Text(item.title)
                    .font(.system(size: 16, weight: .medium))             // :279
                    .foregroundStyle(colors.textPrimary)
                Text(item.subtitle)
                    .font(.system(size: 12))                              // :280
                    .foregroundStyle(colors.textSecondary)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.system(size: 15, weight: .semibold))               // :282 20dp
                .foregroundStyle(colors.textSecondary)
        }
        .padding(.vertical, 12)                                           // :275 v=12
    }

    @ViewBuilder
    private func destination(for item: MenuItem) -> some View {
        switch item.title {
        case "记忆管理": MemoryListView()
        case "模型渠道": ChannelConfigView(provider: "OPENAI")
        case "表情库": StickerLibraryView()
        case "备份导入": BackupImportView()
        case "搜索消息": MessageSearchView()
        case "AI 生图": ImageGenView()
        default: DiagnosticsView()
        }
    }

    private func reload() {
        memoryCount = (try? environment.memoryRepo?.listAll().count) ?? 0
        stickerCount = (try? environment.stickerTags?.tagsWithFallback(topN: 30).count) ?? 0
        schemaVersion = "v\(YuNianSchema.version)"
    }
}
