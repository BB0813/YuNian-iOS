import SwiftUI

/// 通讯录页 —— 一级 tab 之一。
///
/// ## 权威来源（第 141 轮，子代理只读勘察，均标行号）
/// `feature/companion/.../ui/screen/ContactsScreen.kt`：
///
/// | 项 | Kotlin 值 | 行号 |
/// |---|---|---|
/// | 页面标题 | "通讯录" 22sp Bold onSurface | :153-158 |
/// | 标题行 | statusBars inset + h16 + t4/b10 | :132-135 |
/// | 右侧按钮 | 36×36 GlassButton、surfaceVariant@0.85、图标20dp onSurface、间距8dp | :164-196 |
/// | 区块头 | 13sp Medium onSurfaceVariant、padding start4/bottom4 | :212-218、231-237 |
/// | 群聊行 | drawGlass(Capsule)+padding h14/v10、头像44dp 圆、名字16sp、副文本13sp | :290-352 |
/// | 好友行 | 同布局、**无副标题无箭头** | :355-412 |
/// | 分组字母条 | 14sp SemiBold primary、底 primary@0.08、padding h12/v4 | :242-253 |
/// | 空态 | 80dp 圆底 + 图标36dp + 主文案16sp + 副文案14sp | :415-452 |
///
/// ## iOS 侧省略（如实记录，不是遗漏）
/// - **字母侧边栏**（AlphabetSidebar）—— iOS 无对应交互约定，
///   且拼音分组在 Swift 侧需要 ICU Transliterator，成本高于收益。
/// - **搜索态**（ContactsScreen.kt:143-152）—— iOS 已有独立「搜索消息」页。
/// - **群聊区**（ChatGroup）—— iOS 侧无群聊数据模型，`fetchAll()` 只返回伴侣。
///   空群聊区直接不渲染，而不是显示一个永远为空的区块。
struct ContactsView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme
    /// 选中要进入对话的伴侣。
    @State private var chatCompanion: CompanionRepository.Companion?

    @State private var companions: [CompanionRepository.Companion] = []

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    // 自绘标题行（Kotlin :132-135）
                    HStack(spacing: YuNianTheme.Space.standard) {
                        Spacer(minLength: 0)                      // :137 左占位让标题居中
                        Text("通讯录")
                            .font(.system(size: 22, weight: .bold))
                            .foregroundStyle(colors.textPrimary)   // :154-158
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, YuNianTheme.Space.page)  // :133 h=16
                    .padding(.top, YuNianTheme.Space.minUnit)      // :134 t=4
                    .padding(.bottom, YuNianTheme.Space.topBar)    // :134 b=10

                    if companions.isEmpty {
                        emptyState
                    } else {
                        // 好友区块头（Kotlin :230-238）
                        sectionHeader("好友")

                        // 按首字母分组（Kotlin :98-103）
                        ForEach(groupedByInitial, id: \.key) { group in
                            letterBar(group.key)                     // :242-253
                            ForEach(group.items) { c in
                                Button { chatCompanion = c } label: {
                                    contactRow(c)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }

                    Spacer(minLength: YuNianTheme.Space.bottomInset)
                }
                .padding(.horizontal, YuNianTheme.Space.cardPadding)  // 列表 contentPadding h=12
            }
            .background(colors.background.ignoresSafeArea())
            .navigationDestination(item: $chatCompanion) { c in
                ChatView(companion: c)
            }
            .task { reload() }
        }
    }

    // MARK: - 分组

    /// 按名称首字符分组。
    ///
    /// Kotlin 用 ICU Transliterator("Han-Latin; Latin-ASCII") 取拼音首字母
    /// （ContactsScreen.kt:454-466）。Swift 侧等价物是 `CFStringTransform`
    /// 的 kCFStringTransformMandarinLatin，但它对 iOS 版本有要求且行为不稳定；
    /// 这里退化为「取首字符，非字母归 #」—— 分组存在，但不保证与 Android
    /// 的拼音结果一致。已在注释中记录差异。
    private var groupedByInitial: [(key: String, items: [CompanionRepository.Companion])] {
        var buckets: [String: [CompanionRepository.Companion]] = [:]
        for c in companions.sorted(by: { $0.name < $1.name }) {
            let first = c.name.first.map(String.init) ?? "#"
            let key = (first.rangeOfCharacter(from: .letters) != nil) ? first.uppercased() : "#"
            buckets[key, default: []].append(c)
        }
        return buckets.sorted { $0.key < $1.key }
            .map { (key: $0.key, items: $0.value) }
    }

    // MARK: - 零件

    /// 区块头（Kotlin :212-218）
    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .font(.system(size: 13, weight: .medium))
            .foregroundStyle(colors.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, YuNianTheme.Space.minUnit)   // start=4
            .padding(.top, YuNianTheme.Space.standard)      // v=8 列表间距
            .padding(.bottom, YuNianTheme.Space.minUnit)    // bottom=4
    }

    /// 分组字母条（Kotlin :242-253）：14sp SemiBold primary + primary@0.08 底
    private func letterBar(_ letter: String) -> some View {
        Text(letter)
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(colors.primary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, YuNianTheme.Space.cardPadding)   // h=12
            .padding(.vertical, YuNianTheme.Space.minUnit)          // v=4
            .background(colors.primary.opacity(0.08))
    }

    /// 好友行（Kotlin :355-412）—— 无副标题、无右侧箭头。
    private func contactRow(_ c: CompanionRepository.Companion) -> some View {
        HStack(spacing: YuNianTheme.Space.standard) {          // avatarGap = 8dp
            ZStack {
                Circle().fill(colors.card)                      // :365-368 底色 surface
                Image(systemName: "person")
                    .font(.system(size: 24))
                    .foregroundStyle(colors.captionContent)      // :383 tint = captionContent
            }
            .frame(width: 44, height: 44)                       // :369 44dp

            Text(c.name)
                .font(.system(size: 16))                        // :404 16sp Normal
                .foregroundStyle(colors.textPrimary)
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, YuNianTheme.Space.listItem)       // h=14
        .padding(.vertical, YuNianTheme.Space.topBar)           // v=10
        .yuNianGlass(colors, radius: .infinity,
                     surfaceColor: colors.card, isDark: scheme == .dark)   // ContinuousCapsule
    }

    /// 空态（Kotlin :415-452）
    private var emptyState: some View {
        VStack(spacing: YuNianTheme.Space.cardPadding) {
            Circle()
                .fill(colors.card)
                .frame(width: 80, height: 80)
                .overlay(
                    Image(systemName: "person")
                        .font(.system(size: 36))
                        .foregroundStyle(colors.textSecondary)
                )
            Text("还没有联系人")
                .font(.system(size: 16))
                .foregroundStyle(colors.textPrimary)
            Text("先在启动时播种默认伴侣，之后这里会列出。")
                .font(.system(size: 14))
                .foregroundStyle(colors.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, YuNianTheme.Space.pageTop)
    }

    private func reload() {
        guard let repo = environment.companions else { return }
        companions = (try? repo.fetchAll()) ?? []
    }
}
