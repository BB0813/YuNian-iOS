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

// MARK: - 页头（已废弃 → 用系统大标题）

// ⚠️ 第 222 轮第二次修正：原先这里有一个自绘的 `YNPageHeader`
// （34pt 大标题 + 右侧计数 + 副标题）。真机上它**把标题的第一个字裁掉了**，
// 右侧计数也被切到屏幕外 —— 我按"整行比屏幕宽"的假设改过一轮（中间列 maxWidth +
// 去掉 fixedSize），**没有修好**。
//
// 既然自绘几何在这台设备上不可靠，三个页签统一改用**系统 `.largeTitle`**：
// 量级一样（34pt 粗体），几何由系统保证；计数改放工具栏尾部。
// 这里刻意**不保留**这个原语 —— 留着一个已被证明会出错的组件，
// 下一个人还会再踩一次（"删掉而不是注释掉"）。

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
