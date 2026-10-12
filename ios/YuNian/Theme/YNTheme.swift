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
        /// **暖色第二强调**（第 220 轮「暖夜」方向）。
        ///
        /// ## 为什么要打破"只有一个强调色"
        /// 设计系统原本只留一个强调色（避免花）。但实测观感反馈是
        /// 「单调 / 无聊 / 没有使用欲望」—— 因为**整屏全是冷色**：
        /// 紫罗兰画布 + 中性灰内容面 + 紫罗兰强调色，没有任何暖色。
        /// 一个陪伴类 App 缺的正是"体温"。
        ///
        /// 所以：`accent`（紫罗兰）继续承担**品牌与交互**（选中、主操作、可点文字），
        /// `warm`（蜜琥珀）只承担**情感与注意**（未读、亲密、头像光晕、提醒）。
        /// 两者**不同时**出现在同一个控件上，代码里有这条约定。
        let warm: Color
        /// 暖色淡底（与 `accentSoft` 同规格）。
        let warmSoft: Color

        // 语义状态（T6：必须与图形/文字线索并用，不得单独承担含义）
        let success: Color
        let warning: Color
        let danger: Color
        /// 画布辉光（品牌紫，右上光源）
        let canvasGlow: Color
        /// 画布第二光源（暖色，左下）—— 「暖夜」的关键：
        /// 双光源让平铺的深色底产生"黄昏"体积感，而不是一块死黑。
        let canvasGlowWarm: Color
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
        // ⚠️ 第 220 轮「暖夜」：从**中性灰**改成**暖灰**。
        // 上一版为了避免"满屏紫"把内容面去饱和成中性灰（饱和 18%），
        // 结果整屏是"深灰底 + 灰卡片"—— 干净但**没有温度**，实测反馈
        // 就是「单调、无聊、没有使用欲望」。现在把内容面**往暖侧偏**
        //（色相 ~330 玫瑰紫，而不是 255 蓝紫），饱和度小幅回到 ~14%：
        // 既不会糊回"满屏紫"，也不再是冷灰。
        surface:           Color(hex: 0x2A2229),
        surfaceElevated:   Color(hex: 0x362C34),
        separator:         Color.white.opacity(0.10),

        textPrimary:       Color(hex: 0xF7F2F5),
        textSecondary:     Color(hex: 0xCFC4CC),
        textTertiary:      Color(hex: 0xA79DA6),

        accent:            Color(hex: 0xB9A2FF),
        onAccent:          Color(hex: 0x1A1030),
        accentSoft:        Color(hex: 0xB9A2FF).opacity(0.18),
        warm:              Color(hex: 0xFFB27A),
        warmSoft:          Color(hex: 0xFFB27A).opacity(0.18),

        success:           Color(hex: 0x4ADE9B),
        warning:           Color(hex: 0xFBBF24),
        danger:            Color(hex: 0xFF8093),
        canvasGlow:        Color(hex: 0x7C5CE6),
        canvasGlowWarm:    Color(hex: 0xE08A5F)
    )

    /// 浅色。与深色**对等**，不是附赠品（T4）。
    ///
    /// 第 220 轮「暖夜」：画布从冷白改成**暖米**（对应深色的暖夜），
    /// 强调色不变（品牌），新增暖色第二强调。浅色下 `warm` 用更深的琥珀，
    /// 否则压在米色底上对比度不足。
    static let light = Palette(
        background:        Color(hex: 0xF6F1EE),
        backgroundElevated: Color(hex: 0xFCF8F5),

        surface:           Color(hex: 0xFFFFFF),
        surfaceElevated:   Color(hex: 0xF4EDE9),
        separator:         Color.black.opacity(0.10),

        textPrimary:       Color(hex: 0x221A20),
        textSecondary:     Color(hex: 0x574B53),
        textTertiary:      Color(hex: 0x776A72),

        accent:            Color(hex: 0x6D3FE0),
        onAccent:          Color(hex: 0xFFFFFF),
        accentSoft:        Color(hex: 0x6D3FE0).opacity(0.12),
        warm:              Color(hex: 0xA8450F),
        warmSoft:          Color(hex: 0xA8450F).opacity(0.12),

        success:           Color(hex: 0x0B7A55),
        warning:           Color(hex: 0x8A5300),
        danger:            Color(hex: 0xC11B3E),
        canvasGlow:        Color(hex: 0x9F7BFF),
        canvasGlowWarm:    Color(hex: 0xF0A878)
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

// MARK: - 显示级字号（第 220 轮）

/// 旧字号表最大只到 `.largeTitle`，而页面标题实际走系统导航栏（17pt）——
/// 于是**整屏最大的字就是 17pt**，扫过去没有重点，观感自然"平"。
/// 这一档补的是"页面级存在感"：门面页（会话列表 / 我 / 通讯录）用真正大的标题，
/// 二级页仍走导航栏（`.inline`），保持层级差异。
extension YNTheme {
    enum Display {
        static let hero = Font.system(size: 34, weight: .bold)
        static let heroSmall = Font.system(size: 28, weight: .bold)
        static let section = Font.system(size: 20, weight: .semibold)
        /// 大字号必须**收紧**字距，否则中文会散开、显得松散。
        static let heroTracking: CGFloat = -0.6
        static let sectionTracking: CGFloat = -0.2
    }
}

// MARK: - 阴影（第 220 轮）

/// 旧实现几乎没有任何高度线索：画布 0.11、内容面 0.21、次级面 0.26 ——
/// 全靠极小的亮度差分，层次读不出来，整屏像"一整块"。
///
/// ⚠️ 仍然遵守 `DESIGN.md` 反模式 7：**描边与投影不同时出现**。
/// 用了 `ynElevation` 就不要再加 1px 描边。
extension YNTheme {
    enum Elevation {
        struct Spec {
            let color: Color
            let radius: CGFloat
            let y: CGFloat
        }

        /// 内容面：极轻，只把面从画布上"抬起来"。
        static let card = Spec(color: .black.opacity(0.28), radius: 14, y: 6)
        /// 悬浮 / 强调块：稍强（弹层、主卡）。
        static let raised = Spec(color: .black.opacity(0.36), radius: 22, y: 10)
        /// 暖色光晕（头像/未读）：用暖色而不是黑色，避免"脏边"。
        static func glow(_ color: Color, radius: CGFloat = 12) -> Spec {
            Spec(color: color.opacity(0.35), radius: radius, y: 2)
        }
    }
}

// MARK: - 动效（第 220 轮集中）

extension YNTheme {
    enum Motion {
        static let spring = Animation.spring(response: 0.34, dampingFraction: 0.86)
        static let quick = Animation.easeOut(duration: 0.2)

        /// 列表逐个入场的延迟。
        ///
        /// ⚠️ 只给**首屏第一组**用：`DESIGN.md` 明写"每个区块都套同一个入场动画是反模式"，
        /// 所以这里连上限都写死（最多 0.24s），避免一不小心把整页元素都拖慢。
        static func entryDelay(_ index: Int, step: Double = 0.03, cap: Double = 0.24) -> Double {
            min(Double(index) * step, cap)
        }
    }
}

extension View {
    /// 套一层高度（见 `YNTheme.Elevation` 的说明：与描边互斥）。
    func ynElevation(_ spec: YNTheme.Elevation.Spec) -> some View {
        shadow(color: spec.color, radius: spec.radius, y: spec.y)
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
