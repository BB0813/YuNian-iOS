import SwiftUI

/// 玻璃组件族 —— 逐条复刻 Android `core:ui-common/.../component/glass/`。
///
/// ## 权威来源
/// - `GlassCard.kt`（Standard/Emphasis/Metric 三态 × 明暗）
/// - `GlassButton.kt`（全胶囊 + 禁用 0.45 alpha）
/// - `GlassSurface.kt:25-64`（`Modifier.drawGlass` 基元）
///
/// ## SwiftUI 与 Compose 的等价取舍（如实记录）
/// Android 的液态玻璃用 `com.kyant.backdrop` **真采样背景层**（vibrancy → blur
/// → lens）。SwiftUI 没有等价的跨层采样 API，这里用
/// `Color.glassSurface` + 高光渐变 + 描边三层叠加来逼近其**观感**，
/// 并保留 Android 的回退逻辑（无 backdrop 时用纯色）。
///
/// ⚠️ 这是「视觉近似」而非「实现等同」。要 1:1 的采样需要用 Metal 自绘
/// 或用 `UIVisualEffectView` 包一层 backdrop —— 属后续可选项，本轮不假装已做到。
struct YuNianGlass {

    /// `GlassCardStyle` —— `GlassCard.kt:22-26`
    enum Style {
        case standard
        case emphasis
        case metric
    }
}

// MARK: - 玻璃修饰符

extension View {
    /// 对应 `Modifier.drawGlass(shape:surfaceColor:)` —— `GlassSurface.kt:25-64`
    ///
    /// 三层叠加，与 Kotlin `onDrawSurface` 的两步一致：
    /// 1. `glassColor @ 0.50` 半透明底（`GlassSurface.kt:51`）
    /// 2. 竖直渐变高光：0→白@0.14、0.35→白@0.04、1→白@0（`GlassSurface.kt:52-58`）
    ///
    /// 暗色额外加 0.5dp 白@0.07 描边（`GlassCard.kt:49`），亮色不加（`GlassCard.kt:48-52`）。
    func yuNianGlass(
        _ colors: YuNianTheme.Colors,
        radius: CGFloat = YuNianTheme.Radius.glassDefault,
        surfaceColor: Color? = nil,
        isDark: Bool
    ) -> some View {
        let base = surfaceColor ?? colors.glassSurface
        return self
            .background(
                ZStack {
                    base.opacity(0.50)
                    LinearGradient(
                        stops: [
                            .init(color: .white.opacity(0.14), location: 0),
                            .init(color: .white.opacity(0.04), location: 0.35),
                            .init(color: .white.opacity(0), location: 1),
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                }
            )
            .cornerRadius(radius)
            .overlay(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .strokeBorder(
                        isDark ? Color.white.opacity(0.07) : Color.black.opacity(0.06),
                        lineWidth: 0.5
                    )
            )
    }
}

// MARK: - GlassCard

/// 对应 `GlassCard` —— `GlassCard.kt`
struct YuNianGlassCard<Content: View>: View {
    let style: YuNianGlass.Style
    let onClick: (() -> Void)?
    let content: Content

    @Environment(\.colorScheme) private var scheme
    @State private var pressed = false

    init(
        style: YuNianGlass.Style = .standard,
        onClick: (() -> Void)? = nil,
        @ViewBuilder content: () -> Content
    ) {
        self.style = style
        self.onClick = onClick
        self.content = content()
    }

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }
    private var isDark: Bool { scheme == .dark }

    /// 三态容器色 —— `GlassCard.kt:38-47`
    private var containerColor: Color {
        switch style {
        case .standard:
            // Kotlin：暗 0xFF20242B / 亮 0xF7FFFFFF
            return isDark ? YuNianTheme.Palette.glassSurfaceDark
                          : YuNianTheme.Palette.glassSurfaceLight
        case .emphasis:
            return colors.primary.opacity(isDark ? 0.16 : 0.10)
        case .metric:
            // Kotlin：暗 White@0.055 / 亮 0xFFF4F7FB
            return isDark ? Color.white.opacity(0.055) : Color(hex: 0xF4F7FB)
        }
    }

    var body: some View {
        Group {
            if let onClick {
                Button(action: onClick) { cardBody }
                    .buttonStyle(.plain)
            } else {
                cardBody
            }
        }
        .scaleEffect(pressed ? 0.985 : 1.0)          // GlassCard.kt:55-59
        .animation(.spring(dampingRatio: 0.78, stiffness: 520), value: pressed)
        .simultaneousGesture(
            // ⚠️ 第 124 轮：`DragGesture` 的形参是 **minimumDistance**，
            // 我写成了 `minLength`，CI 报 "extra argument 'minLength' in call"。
            DragGesture(minimumDistance: 0)
                .onChanged { _ in pressed = true }
                .onEnded { _ in pressed = false }
        )
        .disabled(onClick == nil)
    }

    /// ⚠️ 第 124 轮：原名 `body`，与 `View` 协议要求的 `body` 重名，
    /// CI 报 "invalid redeclaration of 'body'" 且 View conformance 失败。
    private var cardBody: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .padding(YuNianTheme.Space.cardPadding)
            .yuNianGlass(colors, radius: YuNianTheme.Radius.glassCard,
                         surfaceColor: containerColor, isDark: isDark)
    }
}

// MARK: - GlassButton

/// 对应 `GlassButton` —— `GlassButton.kt`
struct YuNianGlassButton<Content: View>: View {
    let onClick: () -> Void
    let height: CGFloat
    let horizontalPadding: CGFloat
    let tint: Color?
    let surfaceColor: Color?
    let enabled: Bool
    let content: Content

    @Environment(\.colorScheme) private var scheme

    init(
        onClick: @escaping () -> Void,
        height: CGFloat = 48,                                  // GlassButton.kt 默认
        horizontalPadding: CGFloat = 16,
        tint: Color? = nil,
        surfaceColor: Color? = nil,
        enabled: Bool = true,
        @ViewBuilder content: () -> Content
    ) {
        self.onClick = onClick
        self.height = height
        self.horizontalPadding = horizontalPadding
        self.tint = tint
        self.surfaceColor = surfaceColor
        self.enabled = enabled
        self.content = content()
    }

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }
    private var isDark: Bool { scheme == .dark }

    /// 无 backdrop 回退色 —— `GlassButton.kt:112-118`
    private var fallback: Color? {
        if let tint, let surfaceColor { return surfaceColor }
        if let surfaceColor { return surfaceColor }
        if let tint { return tint.opacity(0.15) }
        return nil
    }

    var body: some View {
        Button(action: onClick) {
            HStack(spacing: YuNianTheme.Space.standard) { content }   // spacedBy(8)
                .padding(.horizontal, horizontalPadding)
                .frame(maxWidth: .infinity)
                .frame(height: height)
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .background(Capsule().fill(fallback ?? .clear))
        .opacity(enabled ? 1.0 : 0.45)                    // DisabledAlpha GlassButton.kt:45
        .disabled(!enabled)
    }
}

// MARK: - 自绘顶栏

/// 对应 `WeChatTopBar` —— `MainBottomBar.kt:140-177`
///
/// 高度 44dp + `windowInsetsPadding(statusBars)`；
/// 标题 `titleMedium.copy(SemiBold, 17sp)`、`padding(start=16)`、`TextAlign.Start`；
/// 动作区 `padding(end=16).spacedBy(8)`。
struct YuNianTopBar<Actions: View>: View {
    let title: String
    let isVisible: Bool
    @ViewBuilder var actions: Actions

    @Environment(\.colorScheme) private var scheme

    init(title: String, isVisible: Bool = true,
         @ViewBuilder actions: () -> Actions = { EmptyView() }) {
        self.title = title
        self.isVisible = isVisible
        self.actions = actions()
    }

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }

    var body: some View {
        HStack(spacing: YuNianTheme.Space.standard) {
            Text(title)
                .font(YuNianTheme.TextStyle.topBarTitle)
                .foregroundStyle(colors.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: YuNianTheme.Space.standard) { actions }
        }
        .padding(.horizontal, YuNianTheme.Space.page)
        .padding(.vertical, YuNianTheme.Space.topBar)
        .frame(height: 44)
        .frame(maxWidth: .infinity)
        .background(isVisible ? colors.background : .clear)
    }
}

// MARK: - 胶囊输入框

/// 对应 Android 的 `OutlinedTextField` 玻璃变体 —— `PetalApiCards.kt:242-309`
///
/// 圆角 12dp、容器 `surfaceVariant@0.62`、聚焦边框 `PetalPrimary`、
/// 未聚焦 `outline`（`PetalApiCards.kt:248-255`）。
struct YuNianField: View {
    let title: String
    @Binding var text: String
    var isSecure = false
    var monospaced = false
    var keyboard: UIKeyboardType = .default

    @Environment(\.colorScheme) private var scheme
    @FocusState private var focused: Bool

    init(_ title: String, text: Binding<String>, isSecure: Bool = false,
         monospaced: Bool = false, keyboard: UIKeyboardType = .default) {
        self.title = title
        _text = text
        self.isSecure = isSecure
        self.monospaced = monospaced
        self.keyboard = keyboard
    }

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }

    var body: some View {
        Group {
            if isSecure {
                SecureField(title, text: $text)
            } else {
                TextField(title, text: $text)
            }
        }
        .font(monospaced ? .body.monospaced() : .body)
        .keyboardType(keyboard)
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        .focused($focused)
        .padding(.horizontal, YuNianTheme.Space.cardPadding)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: YuNianTheme.Radius.field, style: .continuous)
                .fill(colors.card.opacity(0.62))
        )
        .overlay(
            RoundedRectangle(cornerRadius: YuNianTheme.Radius.field, style: .continuous)
                .strokeBorder(
                    focused ? YuNianTheme.Palette.petalPrimary : colors.divider,
                    lineWidth: focused ? 1.5 : 1.0
                )
        )
    }
}

// MARK: - 状态 chip

/// 对应 `PetalStatChip` —— `PetalApiCards.kt:98-123`
///
/// 圆角 6dp、底 `isDark ? color@0.12 : 0xFFF0F6FC`、
/// `padding(horizontal=8,vertical=3)`、图标 12dp + `Spacer(4)` + 12sp Medium。
struct YuNianStatChip: View {
    let text: String
    let color: Color
    var systemImage: String? = nil

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        HStack(spacing: YuNianTheme.Space.minUnit) {
            if let systemImage {
                Image(systemName: systemImage).font(.system(size: 12))
            }
            Text(text).font(YuNianTheme.TextStyle.statChip)
        }
        .padding(.horizontal, YuNianTheme.Space.standard)
        .padding(.vertical, YuNianTheme.Space.tight)
        .background(
            RoundedRectangle(cornerRadius: YuNianTheme.Radius.statChip, style: .continuous)
                .fill(scheme == .dark ? color.opacity(0.12) : Color(hex: 0xF0F6FC))
        )
        .foregroundStyle(color)
    }
}

// MARK: - 区块标题

/// 对应 `SectionTitle` —— `HomeScreen.kt:267-281`
/// 13sp Medium，`padding(start=4,bottom=4,top=8)`
struct YuNianSectionTitle: View {
    let title: String
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Text(title)
            .font(YuNianTheme.TextStyle.sectionLabel)
            .foregroundStyle(YuNianTheme.colors(scheme).textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, YuNianTheme.Space.minUnit)
            .padding(.top, YuNianTheme.Space.standard)
            .padding(.bottom, YuNianTheme.Space.minUnit)
    }
}

// MARK: - 列表行

/// 对应 Android `SettingsRow` —— `SettingsWidgets.kt:92-103`
///
/// 标题 15sp、尾部 "›" 13sp 色 `textSecondary`。
/// **不带自己的玻璃底** —— Android 的 GlassCard 是外层容器，
/// 这里同样留给调用方包 `YuNianGlassCard`，避免卡片套卡片。
struct YuNianGlyphRow: View {
    let title: String
    let icon: String
    var subtitle: String? = nil
    var disabled = false

    @Environment(\.colorScheme) private var scheme

    init(_ title: String, icon: String, subtitle: String? = nil, disabled: Bool = false) {
        self.title = title
        self.icon = icon
        self.subtitle = subtitle
        self.disabled = disabled
    }

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }

    var body: some View {
        HStack(spacing: YuNianTheme.Space.half) {
            Image(systemName: icon)
                .foregroundStyle(colors.primary)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: YuNianTheme.Space.micro) {
                Text(title)
                    .font(YuNianTheme.TextStyle.settingsRowTitle)
                    .foregroundStyle(colors.textPrimary)
                if let subtitle {
                    Text(subtitle)
                        .font(YuNianTheme.TextStyle.settingsRowSubtitle)
                        .foregroundStyle(colors.textSecondary)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(colors.textSecondary)
        }
        .padding(.vertical, YuNianTheme.Space.half)
        .opacity(disabled ? 0.45 : 1.0)
    }
}
