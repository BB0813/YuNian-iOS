import SwiftUI

/// 液态玻璃底部 Tab —— 逐条复刻 Android `LiquidBottomTabs.kt`。
///
/// ## 权威来源（第 134 轮，全部来自源码）
/// `core/ui-common/.../component/glass/LiquidBottomTabs.kt` +
/// `MainBottomBar.kt:63-137`（FloatingGlassBottomNav）：
///
/// | 项 | Kotlin 值 | 行号 |
/// |---|---|---|
/// | 容器高度 | `containerHeight = 64.dp` | :158 |
/// | 内容内边距 | `contentPadding = 4.dp` | :159 |
/// | 外层胶囊 lens 半径 | `outerLensRadius = 24.dp` | :182 |
/// | 选中块 lens 半径 | `selectionLensRadius = 10.dp` | :183 |
/// | 选中块色散半径 | `selectionChromaticRadius = 14.dp` | :184 |
/// | 容器基色(暗) | `#121212` @ 0.14 | :172-174 |
/// | 容器基色(亮) | `#FAFAFA` @ 0.14 | :172-174 |
/// | 边框 | 0.8dp 暗 White@0.12 / 亮 Black@0.08 | :310,318 |
/// | 边框形状 | `ContinuousCapsule` | :309,317 |
/// | accent | `PinkPrimary = #4A9EFF` | :167 |
/// | tab 图标尺寸 | `28.dp` | MainBottomBar.kt:122 |
/// | tab 文字 | `labelSmall.copy(fontSize=12.sp)` | MainBottomBar.kt:127-130 |
/// | 图文字间距 | `Arrangement.spacedBy(2.dp)` | :144 |
/// | 按下缩放 | `pressedScale = 78/56f` ≈ 1.3929 | :203,104 |
/// | 回弹动画 | `spring(1f, 300f, 0.5f)` | :214 |
///
/// ## 选中态（Kotlin 是一个**移动的 lens 药丸**，:333-415）
/// 位置随 `dampedDragAnimation` 在 tab 间移动；尺寸
/// `.height(selectionHeight).fillMaxWidth(1f/tabsCount)`；形状 ContinuousCapsule。
///
/// ## ⚠️ 与 Kotlin 的实现差异（如实记录，不假装等同）
/// Kotlin 的药丸用 `rememberCombinedBackdrop` + `lens(10dp, 14dp, chromaticAberration=true)`
/// 做**真折射**，且有一层 `alpha(0f)` 的隐藏捕获层把 tab 内容染成 accent 蓝
/// 供折射（:470-515）。SwiftUI 无跨层采样 API，这里用：
/// - 一块会滑动的 accent 半透明胶囊（近似"选中块被点亮"）
/// - 弹簧动画近似 `spring(1f,300f,0.5f)`
/// - 按下时 tab 内容轻微放大（近似 pressedScale 的观感，实测量级降到 1.06 避免夸张）
///
/// **没有做的**：色散折射、隐藏捕获层的 accent 染色、面板视差。
/// 这三项要 Metal 自绘或 UIVisualEffectView，本版明确不做。
struct YuNianLiquidTabs: View {

    /// 一个 tab。
    struct Tab: Identifiable, Equatable {
        let id = UUID()
        let title: String
        let icon: String          // SF Symbols 名
        let route: String         // 仅用于调用方识别，不参与渲染

        init(title: String, icon: String, route: String) {
            self.title = title
            self.icon = icon
            self.route = route
        }
    }

    let tabs: [Tab]
    @Binding var selectedIndex: Int

    @Environment(\.colorScheme) private var scheme
    /// 拖动中的索引（Kotlin 的 dampedDragAnimation.value，浮点以支持平滑插值）
    @State private var visualIndex: CGFloat = 0
    @State private var pressedIndex: Int?

    init(tabs: [Tab], selectedIndex: Binding<Int>) {
        self.tabs = tabs
        _selectedIndex = selectedIndex
    }

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }
    private var isDark: Bool { scheme == .dark }

    /// Kotlin: contentColor = 暗 White / 亮 Black（MainBottomBar.kt:71-72）
    private var contentColor: Color { isDark ? .white : .black }

    /// Kotlin: containerColor（LiquidBottomTabs.kt:170-175）
    private var containerColor: Color {
        let base = isDark ? Color(hex: 0x121212) : Color(hex: 0xFAFAFA)
        return base.opacity(0.14)
    }

    /// Kotlin: 边框色（LiquidBottomTabs.kt:310 / :318）
    private var borderColor: Color {
        isDark ? Color.white.opacity(0.12) : Color.black.opacity(0.08)
    }

    var body: some View {
        // ⚠️ 第 136 轮：tabWidth 抽成私有计算属性。
        // 原写法把 `(geo.size.width - minUnit*2) / count` 内联在 GeometryReader
        // 闭包里，与外层 ZStack/HStack/overlay 一起超出编译器推导预算，
        // CI 报 "unable to type-check this expression in reasonable time"
        // （同一类错误第 131 轮在 StickerImportView 也犯过）。
        GeometryReader { geo in
            let tabWidth = tabWidth(for: geo.size.width)

            ZStack(alignment: .leading) {
                // ── 选中的滑动药丸（近似 Kotlin selectionModifier）──
                RoundedRectangle(cornerRadius: .infty, style: .continuous)
                    .fill(colors.primary.opacity(0.18))
                    .frame(
                        width: tabWidth,
                        height: YuNianTheme.LiquidMetric.barHeight
                            - YuNianTheme.Space.minUnit * 2
                    )
                    .offset(x: YuNianTheme.Space.minUnit + visualIndex * tabWidth)
                    .animation(.spring(response: 0.42, dampingFraction: 0.82),
                               value: visualIndex)

                // ── tab 项 ──
                HStack(spacing: 0) {
                    ForEach(Array(tabs.enumerated()), id: \.element.id) { idx, tab in
                        tabButton(tab, index: idx)
                    }
                }
            }
            .padding(YuNianTheme.Space.minUnit)      // contentPadding = 4dp
            .frame(height: YuNianTheme.LiquidMetric.barHeight)  // containerHeight = 64dp
            .frame(maxWidth: .infinity)
            .background(
                Capsule().fill(containerColor)
            )
            .overlay(
                Capsule().strokeBorder(borderColor, lineWidth: 0.8)
            )
            .onAppear { visualIndex = CGFloat(selectedIndex) }
            .onChange(of: selectedIndex) { _, new in
                visualIndex = CGFloat(new)
            }
        }
        .frame(height: YuNianTheme.LiquidMetric.barHeight)
    }

    /// Kotlin `LiquidBottomTabs.kt:253-255`：
    /// `tabWidthPx = (maxWidthPx - contentPadding * 2) / tabsCount`
    private func tabWidth(for containerWidth: CGFloat) -> CGFloat {
        let padding = YuNianTheme.Space.minUnit
        return (containerWidth - padding * 2) / CGFloat(max(tabs.count, 1))
    }

    /// 单个 tab。
    ///
    /// Kotlin 的 `LiquidBottomTab`（:127-148）：`clip(ContinuousCapsule)
    /// .clickable(indication=null)` + `Column(spacedBy(2dp))`。
    private func tabButton(_ tab: Tab, index: Int) -> some View {
        let isSelected = index == selectedIndex
        let isPressed = pressedIndex == index

        return Button {
            // Kotlin 的两段式提交：先动视觉再等一帧翻页（MainBottomBar.kt:79-91）。
            // SwiftUI 里 Binding 驱动 visualIndex，一帧差异不足以感知，
            // 故直接更新；若将来要做"点了立刻有反馈"可拆成两步。
            selectedIndex = index
        } label: {
            VStack(spacing: YuNianTheme.Space.micro) {          // spacedBy(2dp)
                Image(systemName: tab.icon)
                    .font(.system(size: 28))                    // MainBottomBar.kt:122
                Text(tab.title)
                    .font(.system(size: 12, weight: isSelected ? .semibold : .regular))
                    .foregroundStyle(contentColor)
            }
            .foregroundStyle(contentColor)
            .frame(maxWidth: .infinity)
            .frame(height: YuNianTheme.LiquidMetric.barHeight
                   - YuNianTheme.Space.minUnit * 2)
            .scaleEffect(isPressed ? 1.06 : 1.0)
            .animation(.spring(response: 0.3, dampingFraction: 0.6), value: isPressed)
        }
        .buttonStyle(.plain)
        .simultaneousGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in pressedIndex = index }
                .onEnded { _ in pressedIndex = nil }
        )
    }
}


extension YuNianTheme {
    /// 液态 Tab 尺寸 token。
    ///
    /// ⚠️ 第 136 轮：原本写成顶层 `enum YuNianLiquidMetric`，
    /// 调用处写 `YuNianTheme.LiquidMetric` 就解析不到
    /// （CI 报 "type 'YuNianTheme' has no member 'LiquidMetric'"）。
    /// 改成嵌套，与 Radius / Space / TextStyle 的组织方式一致。
    ///
    /// 来源：`LiquidBottomTabs.kt`
    enum LiquidMetric {
        /// `containerHeight = 64.dp`（:158）
        static let barHeight: CGFloat = 64
        /// `outerLensRadius = 24.dp`（:182）
        static let outerLensRadius: CGFloat = 24
        /// `selectionLensRadius = 10.dp`（:183）
        static let selectionLensRadius: CGFloat = 10
        /// `selectionChromaticRadius = 14.dp`（:184）
        static let selectionChromaticRadius: CGFloat = 14
    }
}
