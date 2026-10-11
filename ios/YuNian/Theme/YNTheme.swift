import SwiftUI

/// 予念 iOS · 设计 token
///
/// 实现 `ios/APPLE-DESIGN-CONSTRAINTS.md`。**与该文档冲突时以文档为准。**
///
/// ## 三条硬约束的落点（依据见约束文档）
///
/// - **T4**：即使用户只开一种外观，也**必须同时提供浅色与深色两套** ——
///   否则 Liquid Glass 的自适应失效。所以下面 `.light` / `.dark` 是对等的，
///   没有"暗色优先"。
/// - **T5**：深色模式自定义色对比度 **≥ 7:1**（不是 4.5:1）。
/// - **T6**：不得只靠颜色传达状态 —— 调用方必须同时给出图形/文字线索。
///
/// ## 为什么没有 `Radius` 枚举里的一堆魔法数值
/// **S1** 要求圆角用 `ConcentricRectangle` 表达嵌套关系。这里只保留
/// **两个**语义档位（容器 / 容器内元素），嵌套关系交给
/// `ConcentricRectangle` 计算，不硬编码。
enum YNTheme {

    // MARK: - 语义色

    struct Palette {
        // 背景
        let background: Color
        let backgroundElevated: Color

        // 内容面（**不是** Liquid Glass —— M1 禁止内容层用玻璃）
        let surface: Color
        let surfaceElevated: Color
        let separator: Color

        // 文字
        let textPrimary: Color
        let textSecondary: Color
        let textTertiary: Color

        // 强调
        let accent: Color
        let onAccent: Color
        let accentSoft: Color

        // 语义状态（T6：必须与图形/文字线索并用，不得单独承担含义）
        let success: Color
        let warning: Color
        let danger: Color
    }

    /// 深色。
    ///
    /// ⚠️ 对比度按 **T5 的 ≥7:1** 选取，不是 4.5:1。
    /// `textTertiary` 是**下限**，不是"可以再淡一点"的起点 ——
    /// 旧代码在这里栽过（曾把背景色当成文字色，实测 1.41:1）。
    static let dark = Palette(
        // 画布压暗、去饱和：让内容面能"浮"起来，而不是一起糊成紫灰泥。
        // （PRODUCT.md 反模式：低对比暗面板 + 满屏紫色渐变）
        //
        // ⚠️ 第 202 轮修正：上一版 #131019 / #0B0910 太暗（亮度 0.0058 / 0.0030），
        // 在真机截图上呈现为**纯黑**，渐变完全不可感知 —— 等于没做画布。
        // 现在抬到约 2 倍亮度：仍属深色，但紫罗兰底色可被看出来，
        // 暗色不至于冷硬。文字对比度与分层经实测仍全部达标。
        background:        Color(hex: 0x14111D),
        backgroundElevated: Color(hex: 0x1E1930),

        // 内容面必须与画布**明确分层**：这是气泡、输入框可读性的前提。
        //
        // ⚠️ 第 202 轮修正（真机截图驱动）：上一版 surface=#2E2740、
        // surfaceElevated=#3A3150 都是**紫色**（色相 257°，与画布 255° 几乎相同）。
        // 结果整屏从背景到气泡全在一个色相里，只靠亮度区分 ——
        // 这正是 PRODUCT.md 反模式第 1 条「满屏紫色 / 低对比暗面板」。
        //
        // 现在内容层改**中性灰**（饱和度从 39% 降到 18%，肉眼即为灰），
        // 品牌紫只保留在：用户气泡、选中态、主操作。
        // 与 Apple Messages 同一逻辑：**对方中性，自己染色**。
        // 好处是助手气泡靠"色相差"而不是"亮度差"从画布里站出来，不再糊成一片。
        surface:           Color(hex: 0x2B2932),
        surfaceElevated:   Color(hex: 0x37343F),
        separator:         Color.white.opacity(0.10),

        textPrimary:       Color(hex: 0xF5F3F9),
        textSecondary:     Color(hex: 0xC9C3D6),
        textTertiary:      Color(hex: 0xA29CB4),

        accent:            Color(hex: 0xB9A2FF),
        onAccent:          Color(hex: 0x1A1030),
        accentSoft:        Color(hex: 0xB9A2FF).opacity(0.18),

        success:           Color(hex: 0x4ADE9B),
        warning:           Color(hex: 0xFBBF24),
        danger:            Color(hex: 0xFF8093)
    )

    /// 浅色。与深色**对等**，不是附赠品（T4）。
    static let light = Palette(
        background:        Color(hex: 0xF7F6FA),
        backgroundElevated: Color(hex: 0xFFFFFF),

        surface:           Color(hex: 0xFFFFFF),
        surfaceElevated:   Color(hex: 0xF2F1F4),
        separator:         Color.black.opacity(0.10),

        textPrimary:       Color(hex: 0x1A1626),
        textSecondary:     Color(hex: 0x4A4560),
        textTertiary:      Color(hex: 0x6B6580),

        accent:            Color(hex: 0x6D3FE0),
        onAccent:          Color(hex: 0xFFFFFF),
        accentSoft:        Color(hex: 0x6D3FE0).opacity(0.12),

        success:           Color(hex: 0x0B7A55),
        warning:           Color(hex: 0x8A5300),
        danger:            Color(hex: 0xC11B3E)
    )

    /// 按 SwiftUI 的 `ColorScheme` 取语义色。
    ///
    /// **不再用 `luminance()` 判暗** —— 那是旧代码从 Kotlin 搬来的做法，
    /// 在 SwiftUI 里 `ColorScheme` 就是权威。
    static func palette(_ scheme: ColorScheme) -> Palette {
        scheme == .dark ? dark : light
    }

    // MARK: - 间距（4pt 基网）

    /// S1/S2：不要在调用点硬编码数值。
    enum Space {
        static let xs: CGFloat = 4
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        /// 页面左右边距
        static let lg: CGFloat = 16
        static let xl: CGFloat = 20
        static let xxl: CGFloat = 28
        static let section: CGFloat = 40
    }

    // MARK: - 圆角
    //
    // ⚠️ 刻意**只保留两档**（见类型说明）。嵌套同心关系由
    // `ConcentricRectangle` 计算，不在这里堆数值。

    enum Radius {
        /// 最外层容器（卡片、弹层）
        static let container: CGFloat = 16
        /// 容器内元素。用 `ConcentricRectangle` 时会自动保持同心；
        /// 仍需手算时用这个函数，不要写死。
        static func inner(container: CGFloat = container, inset: CGFloat) -> CGFloat {
            max(container - inset, 6)
        }

        // MARK: 聊天页专用
        //
        // 第 202 轮自查发现：这些数值原先**散在 ChatView 里**（18×3、20、16、
        // 14×2、12×2、5），违反 S1「圆角不硬编码」的意图 —— 同一种"气泡"
        // 在三处各写一遍，改一处就会不一致。
        // 集中到这里之后，"气泡长什么样"只有一个定义。

        /// 气泡主体圆角
        static let bubble: CGFloat = 18
        /// 气泡"尾巴"那一角的圆角（同组只有最后一条收窄）
        static let bubbleTail: CGFloat = 5
        /// 气泡内的图片圆角
        static let bubbleImage: CGFloat = 14
        /// 内联提示 / 思考过程块
        static let notice: CGFloat = 12
        /// 输入框圆角
        static let composer: CGFloat = 20
    }

    // MARK: - 字体
    //
    // T1：**禁止**固定字号排正文（用语义字号以获得动态字体支持）。
    // T2：**禁止** Light / Thin / UltraLight 字重。

    enum TextStyle {
        static let largeTitle = Font.largeTitle.weight(.bold)
        static let title = Font.title2.weight(.semibold)
        static let headline = Font.headline
        static let body = Font.body
        static let callout = Font.callout
        static let subheadline = Font.subheadline
        static let footnote = Font.footnote
        static let caption = Font.caption
        /// Tab 标签 —— 用系统语义字号，不写死 10pt
        static let tabLabel = Font.caption2.weight(.medium)
        /// 等宽数字（时间、计数）—— 避免数字跳动
        static func monoNumber(_ base: Font) -> Font { base.monospacedDigit() }
    }

    // MARK: - 无障碍

    /// **A1**：`Reduce Transparency` 为真时，半透明背景必须变不透明。
    ///
    /// 用法：任何自己画的半透明层都要过这个函数；
    /// 用系统组件（`.glassEffect` / `.ultraThinMaterial`）时系统自动处理。
    static func adaptiveAlpha(_ alpha: Double, reduceTransparency: Bool) -> Double {
        reduceTransparency ? 1.0 : alpha
    }
}

// MARK: - Color(hex:)

extension Color {
    /// 由 `0xRRGGBB` 构造（不含 alpha；需要透明处用 `.opacity`）。
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0
        )
    }
}
