import SwiftUI

/// 二级页面统一的「椭圆胶囊」玻璃返回栏。
///
/// ## 权威来源（第 133 轮，全部来自源码，无自行发明）
/// `core/ui-common/.../component/glass/GlassTopBar.kt:29-105`：
///
/// | 项 | Kotlin 值 | 行号 |
/// |---|---|---|
/// | 胶囊圆角 | `RoundedCornerShape(28.dp)` | :58,61,64 |
/// | 胶囊高度 | `48.dp` | :64 |
/// | 外层水平/竖向内边距 | `16.dp` / `8.dp` | :48-53 |
/// | statusBars | 默认自行处理 `windowInsetsPadding(statusBars)` | :44,51 |
/// | 标题水平内边距 | `56.dp` | :70 |
/// | 标题对齐 | `Alignment.Center` + `TextAlign.Center` | :69,71 |
/// | 标题字重/字号 | `SemiBold` / `17.sp`(叠在 titleMedium 上) | :73-74 |
/// | 标题颜色 | `titleColor`(默认 `onSurface`) | :76 |
/// | 返回按钮容器 | `IconButton` + `size(36.dp)` + `padding(4.dp)` | :81-87 |
/// | 返回图标 | `AppIcons.ArrowLeft`(`Iconsax.Linear.ArrowLeft`) | :90 |
/// | 返回图标尺寸 | `22.dp`,tint = `titleColor` | :92-93 |
/// | 返回按钮触发 | `onBack != null` 才绘制 | :80 |
/// | actions | 胶囊内 `Alignment.CenterEnd`,wrap content,无固定宽度 | :97-102 |
///
/// 玻璃层由 `Modifier.drawGlass` 提供(`vibrancy() + blur(24.dp) + lens(16.dp,16.dp)`)。
///
/// ## ⚠️ 与 Kotlin 的实现差异（如实记录）
/// Kotlin 用 `drawBackdrop` 真采样背景层做折射。SwiftUI 无跨层采样 API,
/// 这里沿用 `yuNianGlass` 三层叠加逼近观感 —— **视觉近似,非实现等同**。
struct YuNianGlassTopBar<Trailing: View>: View {

    let title: String
    let onBack: (() -> Void)?
    @ViewBuilder var trailing: Trailing

    @Environment(\.colorScheme) private var scheme

    init(title: String,
         onBack: (() -> Void)? = nil,
         @ViewBuilder trailing: () -> Trailing = { EmptyView() }) {
        self.title = title
        self.onBack = onBack
        self.trailing = trailing()
    }

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }
    private var isDark: Bool { scheme == .dark }

    var body: some View {
        HStack(spacing: 0) {
            // 返回按钮（Kotlin: onBack != null 才绘制）
            Group {
                if let onBack {
                    Button(action: onBack) {
                        Image(systemName: "chevron.backward")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(colors.textPrimary)
                            .frame(width: 28, height: 28)
                    }
                    .buttonStyle(.plain)
                }
            }
            .frame(width: 36, height: 36)          // :81-87 size(36) + padding(4)

            // 标题：Kotlin 靠 padding(horizontal = 56dp) 让出左右空间以保证绝对居中。
            // SwiftUI 里左右各占 36dp，中间 Text 用 maxWidth .infinity 即可等价居中。
            Text(title)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(colors.textPrimary)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.horizontal, YuNianTheme.Space.topBar)

            // actions 插槽：CenterEnd，wrap content。
            // 默认 EmptyView 时占位宽度仍保留 36dp，保证标题居中（与 Kotlin
            // 左右各让位的做法等价），只是不显示内容。
            trailing
                .frame(minWidth: 36, minHeight: 36)
        }
        .padding(.horizontal, YuNianTheme.Space.page)      // :52 horizontal=16
        .padding(.vertical, YuNianTheme.Space.standard)    // :53 vertical=8
        .frame(height: 48)                                  // :64
        .frame(maxWidth: .infinity)
        .yuNianGlass(colors, radius: YuNianTheme.Radius.topBarCapsule,
                     surfaceColor: colors.card, isDark: isDark)
    }
}

/// 页面骨架：把 `YuNianGlassTopBar` 与内容、背景色组装起来。
///
/// 对应 Kotlin 的 `GlassPageScaffold`(`GlassPageScaffold.kt:32-93`)，
/// 但**只取其观感层面**：Kotlin 那套 `layerBackdrop` + `Scaffold` +
/// `contentWindowInsets = WindowInsets(0,0,0,0)` 的机制在 SwiftUI 无对应物。
///
/// statusBars inset 用 SwiftUI 的 `.safeAreaInset` 处理(Kotlin 是 topBar 自己
/// `windowInsetsPadding(statusBars)`，见 GlassTopBar.kt:51)。
struct YuNianGlassPage<Content: View, Trailing: View>: View {
    let title: String
    let onBack: (() -> Void)?
    @ViewBuilder var content: Content
    /// 顶栏右侧动作槽。对应 Kotlin `GlassTopBar.kt:97-102` 的 actions 插槽
    /// （Capsule 内 Alignment.CenterEnd、wrap content、无固定宽度）。
    ///
    /// ⚠️ 第 145 轮加。此前没有这个槽，导致 `ChannelConfigView.save()`
    /// 定义了却**没有任何调用方** —— 用户填完渠道配置无法保存。
    /// find_dead_swift 关卡抓到的。
    @ViewBuilder var trailing: Trailing

    @Environment(\.colorScheme) private var scheme

    init(title: String,
         onBack: (() -> Void)? = nil,
         @ViewBuilder trailing: () -> Trailing = { EmptyView() },
         @ViewBuilder content: () -> Content) {
        self.title = title
        self.onBack = onBack
        self.trailing = trailing()
        self.content = content()
    }

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }

    var body: some View {
        ZStack(alignment: .top) {
            colors.background.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: YuNianTheme.Space.standard) {
                    content
                }
                .padding(.horizontal, YuNianTheme.Space.page)
                .padding(.top, YuNianTheme.Space.standard)
            }
        }
        .safeAreaInset(edge: .top) {
            YuNianGlassTopBar(title: title, onBack: onBack, trailing: { trailing })
                .background(colors.background.opacity(0.001))   // 让点击穿透到空白处
        }
    }
}
