import SwiftUI

/// 视觉原语 —— 第 220 轮「暖夜」。
///
/// 这一批解决的是**观感单调**的三个具体成因：
/// 1. 页面没有大标题（全屏最大的字是导航栏 17pt）→ `YNPageHeader`
/// 2. 内容面没有高度线索（全靠 0.05 的亮度差）→ `YNCard` / `ynElevation`
/// 3. 头像是唯一图像，但它是平贴的 → `YNAvatarHalo`
///
/// ⚠️ 三者都必须遵守 `DESIGN.md` 的反模式清单：
/// 描边与投影互斥、卡片圆角 ≤ 16、不用侧边色条、不做渐变文字。

// MARK: - 页头（大标题）

/// 门面页的页头：大标题 + 右侧计数 +（可选）副标题。
///
/// ## 为什么不用 `.navigationTitle`
/// 系统导航栏标题是 17pt —— **整屏最大的字就是它**，所以任何一页都没有重点。
/// 这里借用 Aru 的做法：标题行里的字真正大（34pt / 粗），
/// 右侧放一个**轻量计数**（`2 段` 那种），副标题再补一句状态。
///
/// 用法：放进 `List` 的第一个 `Section`，并把 `.navigationBarTitleDisplayMode(.inline)`
/// 且 `navigationTitle("")`，否则会出现两个标题。
struct YNPageHeader<Trailing: View>: View {
    let title: String
    var subtitle: String?
    @ViewBuilder var trailing: () -> Trailing

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let c = YNTheme.palette(scheme)
        VStack(alignment: .leading, spacing: YNTheme.Space.xs) {
            HStack(alignment: .firstTextBaseline, spacing: YNTheme.Space.sm) {
                Text(title)
                    .font(YNTheme.Display.hero)
                    .tracking(YNTheme.Display.heroTracking)
                    .foregroundStyle(c.textPrimary)
                    // ⚠️ 第 222 轮（真机截图驱动）：大标题原来会**被切掉第一个字**。
                    // 成因是整行需求宽度超过屏幕、SwiftUI 把内容居中并两侧裁掉。
                    // 这里做两件事兜底：允许略微缩放 + 不撑破可用宽度。
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
                Spacer(minLength: 0)
                trailing()
                    // 计数是短文本，不该被压缩（配合中间列的 maxWidth: .infinity）
                    .fixedSize(horizontal: true, vertical: false)
            }
            if let subtitle {
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundStyle(c.textSecondary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, YNTheme.Space.sm)
        .padding(.bottom, YNTheme.Space.xs)
        .accessibilityElement(children: .combine)
    }
}

extension YNPageHeader where Trailing == EmptyView {
    init(title: String, subtitle: String? = nil) {
        self.init(title: title, subtitle: subtitle) { EmptyView() }
    }
}

// MARK: - 卡片

/// 内容面容器。
///
/// ⚠️ **只加投影，不加描边** —— `DESIGN.md` 反模式 7 明写
/// "1px 描边 + 大范围软投影同时出现"是幽灵卡片。
/// 需要更强的分层时就换 `surfaceElevated`，不要加第二层阴影。
struct YNCard<Content: View>: View {
    var padding: CGFloat = YNTheme.Space.xl
    var elevated: Bool = false
    var radius: CGFloat = YNTheme.Radius.container
    @ViewBuilder var content: () -> Content

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let c = YNTheme.palette(scheme)
        content()
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(c.surface, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .ynElevation(elevated ? YNTheme.Elevation.raised : YNTheme.Elevation.card)
    }
}

// MARK: - 头像光晕

/// 带光晕的圆角头像（**不是**纯圆 —— 见 `DESIGN.md` 对"千篇一律圆形头像"的取舍）。
///
/// 光晕颜色：有未读用 `warm`（暖色＝需要注意），否则用 `accent`（品牌色＝身份）。
/// 这是"暖色只承担情感与注意"这条约定的具体落点。
struct YNAvatarHalo: View {
    let avatarURL: String?
    let name: String
    var size: CGFloat = 52
    var unread: Bool = false

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let c = YNTheme.palette(scheme)
        let halo = unread ? c.warm : c.accent
        ZStack {
            Circle()
                .fill(
                    RadialGradient(
                        colors: [halo.opacity(0.42), halo.opacity(0.10), .clear],
                        center: .center, startRadius: size * 0.42, endRadius: size * 0.92
                    )
                )
                .frame(width: size * 1.62, height: size * 1.62)

            CompanionAvatar(avatarURL: avatarURL, name: name, size: size)
                .clipShape(RoundedRectangle(cornerRadius: size * 0.34, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: size * 0.34, style: .continuous)
                        .strokeBorder(unread ? halo.opacity(0.55) : c.separator, lineWidth: 1)
                )
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

// MARK: - 空态

/// 空态：**必须有出路**（`DESIGN.md` 反模式 12：无出路的空态是反模式）。
///
/// `actions` 为空时它仍然可用，但调用点应当问自己"用户在这一屏想干什么"。
struct YNEmptyState<Actions: View>: View {
    let icon: String
    let title: String
    let message: String
    @ViewBuilder var actions: () -> Actions

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let c = YNTheme.palette(scheme)
        VStack(spacing: YNTheme.Space.md) {
            ZStack {
                Circle()
                    .fill(
                        RadialGradient(colors: [c.accent.opacity(0.26), c.accent.opacity(0.06)],
                                       center: .center, startRadius: 2, endRadius: 44)
                    )
                    .frame(width: 88, height: 88)
                Image(systemName: icon)
                    .font(.system(size: 32, weight: .medium))
                    .foregroundStyle(c.accent)
                    .symbolRenderingMode(.hierarchical)
            }

            VStack(spacing: YNTheme.Space.xs) {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(c.textPrimary)
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(c.textSecondary)
                    .multilineTextAlignment(.center)
                    // 一行中文 ≤ 42 字（DESIGN.md §5）
                    .frame(maxWidth: 300)
            }

            actions()
                .padding(.top, YNTheme.Space.xs)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, YNTheme.Space.section)
    }
}

extension YNEmptyState where Actions == EmptyView {
    init(icon: String, title: String, message: String) {
        self.init(icon: icon, title: title, message: message) { EmptyView() }
    }
}
