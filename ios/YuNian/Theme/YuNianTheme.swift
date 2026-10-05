import SwiftUI

/// 予念设计系统 —— 逐条复刻自 Android `core:ui-common`。
///
/// ## 权威来源（本文件所有数值都能在下列位置找到，未自行发明）
/// - 色彩常量：`core/ui-common/.../theme/Color.kt`（97 个常量）
/// - 明暗方案：`core/ui-common/.../theme/Theme.kt:18-72`
/// - 语义角色：`core/ui-common/.../AppTheme.kt`（43 字段）+ `AppColors.kt`
/// - 玻璃专用色：`GlassSurface.kt:21-23,49-59`
///
/// ## ⚠️ 命名陷阱（来自规格勘察，务必知道）
/// Kotlin 的 `PinkPrimary = 0xFF4A9EFF` **是蓝色，不是粉色**。
/// SwiftUI 侧直接叫 `brandBlue`，不要照抄误导性名字。
///
/// ## 暗色优先
/// Android 多处用 `MaterialTheme.colorScheme.surface.luminance() < 0.5f`
/// 判断暗色而非布尔 flag。这里提供 `isDark` 计算属性做同样的事。
enum YuNianTheme {

    // MARK: - 原始色板（Color.kt 逐条对应）

    enum Palette {
        // 品牌辅色（ pastel 系 ）
        static let pastelMint = Color(hex: 0xDEFCF9)
        static let pastelSky = Color(hex: 0xCADEFC)
        static let pastelLavender = Color(hex: 0xC3BEF0)
        static let pastelLilac = Color(hex: 0xCCA8E9)

        // 品牌主色（注意：是蓝色）
        static let brandBlue = Color(hex: 0x4A9EFF)      // Kotlin PinkPrimary
        static let brandBlueLight = Color(hex: 0xA8CFFF) // Kotlin PinkLight
        static let brandBlueDark = Color(hex: 0x2E6FD8)  // Kotlin PinkDark
        static let brandBlueMuted = Color(hex: 0x2A4A7A) // Kotlin PinkMuted
        static let brandBlueContainerDark = Color(hex: 0x1E3A5F)
        static let brandOnBlueContainerDark = Color(hex: 0xD6E8FF)

        // 暗色背景 / 表面
        static let darkBackground = Color(hex: 0x16131F)
        static let darkSurface = Color(hex: 0x1F1A2C)
        static let darkCard = Color(hex: 0x2A2438)
        static let darkElevated = Color(hex: 0x342E44)
        static let darkDivider = Color(hex: 0x463E58)

        // 暗色文字
        static let darkTextPrimary = Color(hex: 0xF2EEF8)
        static let darkTextSecondary = Color(hex: 0xB8B0C8)
        static let darkTextTertiary = Color(hex: 0x8A829A)

        // 亮色背景 / 表面
        static let lightBackground = Color(hex: 0xDEFCF9)  // = pastelMint
        static let lightSurface = Color(hex: 0xE8F6FC)
        static let lightCard = Color(hex: 0xFFFFFF)
        static let lightDivider = Color(hex: 0xC8D8F0)

        // 亮色文字
        static let lightTextPrimary = Color(hex: 0x2A2440)
        static let lightTextSecondary = Color(hex: 0x5E5678)
        static let lightTextTertiary = Color(hex: 0x8A829A)

        // 气泡
        static let bubbleOnBlue = Color(hex: 0x2A2440)     // Kotlin BubbleOnPink
        static let selfBubbleLight = Color(hex: 0xCCA8E9)  // = pastelLilac
        static let selfBubbleDark = Color(hex: 0x2E6FD8)   // = brandBlueDark
        static let aiBubble = Color(hex: 0xCADEFC)         // = pastelSky，亮暗同值
        static let aiBubbleBorderDark = Color(hex: 0xC3BEF0).opacity(0.40)
        static let aiBubbleBorderLight = Color(hex: 0xC3BEF0).opacity(0.28)

        // 导航
        static let navBackgroundLight = Color(hex: 0xF0FAFC)
        static let navSelected = Color(hex: 0x4A9EFF)      // = brandBlue

        // 语义
        static let success = Color(hex: 0x07C160)
        static let successContainerDark = Color(hex: 0x16351F)
        static let successContainerLight = Color(hex: 0xE8F5E9)
        static let warning = Color(hex: 0xFF9800)
        static let warningContainerDark = Color(hex: 0x3A2A12)
        static let warningContainerLight = Color(hex: 0xFFF3E0)
        static let danger = Color(hex: 0xFA5151)           // = error
        static let dangerContainerDark = Color(hex: 0x3A1518)
        static let dangerContainerLight = Color(hex: 0xFFEBEE)
        static let onlineStatus = Color(hex: 0x4A9EFF)     // = brandBlue

        // 玻璃
        static let glassDarkBg = Color(hex: 0x2A2438).opacity(0.75)
        static let glassDarkBorder = Color(hex: 0xCCA8E9).opacity(0.14)
        static let glassLightBorder = Color(hex: 0xCCA8E9).opacity(0.22)
        static let glassLightBg = Color(hex: 0xFFFFFF).opacity(0.75)
        static let glassWhite30 = Color(hex: 0xFFFFFF).opacity(0.30)
        static let glassWhite50 = Color(hex: 0xFFFFFF).opacity(0.50)
        /// `GlassSurface.kt:23` —— drawGlass 的默认玻璃底色
        static let glassSurfaceDark = Color(hex: 0x20242B)
        static let glassSurfaceLight = Color(hex: 0xF7FFFFFF)

        // Petal（设置页强调）
        static let petalPrimary = Color(hex: 0x5A4A80)
        static let petalPrimaryContainer = Color(hex: 0xCCA8E9)
        static let petalOnPrimaryContainer = Color(hex: 0x3A2E52)
        static let petalSurface = Color(hex: 0xFFFFFF)
        static let petalSurfaceContainer = Color(hex: 0xE8F0F8)
        static let petalOnSurfaceVariant = Color(hex: 0x524A66)
        static let petalGreen = Color(hex: 0x10A37F)
        static let petalError = Color(hex: 0xBA1A1A)
        static let petalOrange = Color(hex: 0xFFA726)

        // 其他
        static let deepWarm = Color(hex: 0x3A3450)
        static let warmGray = Color(hex: 0x7A7490)
    }

    // MARK: - 语义角色（对应 AppTheme.kt 的 AppThemeColors）

    /// 语义色集合。用法：` YuNianTheme.colors(isDark).textPrimary `。
    ///
    /// 每一行后面的注释是 Kotlin 侧的来源，便于逐条核对。
    struct Colors {
        // 文字三级
        let textPrimary: Color       // AppTheme.kt:134  onSurface
        let textSecondary: Color     // AppTheme.kt:135  onSurfaceVariant
        let textTertiary: Color      // AppTheme.kt:140  outlineVariant 位置
        let metadataContent: Color   // AppColors.kt:21  = onSurfaceVariant
        let captionContent: Color    // AppColors.kt:37  onSurfaceVariant @ 0.78

        // 容器
        let background: Color        // AppTheme.kt:126
        let surface: Color           // AppTheme.kt:127
        let card: Color              // AppTheme.kt:128  surfaceVariant
        let divider: Color           // AppTheme.kt:139  outline
        let dividerBackground: Color // AppColors.kt:36  surfaceVariant @ 0.7

        // 主色
        let primary: Color           // AppTheme.kt:129  亮暗同值 0x4A9EFF
        let onPrimary: Color         // AppTheme.kt:136  亮暗同值 0x2A2440
        let primaryContainer: Color
        let onPrimaryContainer: Color

        // 气泡
        let selfBubbleBackground: Color  // AppColors.kt:9-10
        let selfBubbleContent: Color     // AppColors.kt:11  BubbleOnPink
        let aiBubbleBackground: Color    // AppColors.kt:12-13
        let aiBubbleContent: Color       // AppColors.kt:14
        let aiBubbleBorder: Color        // AppColors.kt:15-16

        // 语义
        let success: Color
        let successContainer: Color
        let warning: Color
        let warningContainer: Color
        let danger: Color
        let dangerContainer: Color
        let inverseContent: Color     // AppTheme.kt:159  White
        let scrim: Color              // AppTheme.kt:162  Black

        // 玻璃
        /// `GlassSurface.kt:23`
        let glassSurface: Color
        let glassBorder: Color
    }

    /// 暗色语义（`Theme.kt:43-72`）
    static let dark = Colors(
        textPrimary: Palette.darkTextPrimary,
        textSecondary: Palette.darkTextSecondary,
        textTertiary: Palette.darkElevated,
        metadataContent: Palette.darkTextSecondary,
        captionContent: Palette.darkTextSecondary.opacity(0.78),

        background: Palette.darkBackground,
        surface: Palette.darkSurface,
        card: Palette.darkCard,
        divider: Palette.darkDivider,
        dividerBackground: Palette.darkCard.opacity(0.7),

        primary: Palette.brandBlue,
        onPrimary: Palette.bubbleOnBlue,
        primaryContainer: Palette.brandBlueContainerDark,
        onPrimaryContainer: Palette.brandOnBlueContainerDark,

        selfBubbleBackground: Palette.selfBubbleDark,
        selfBubbleContent: Palette.bubbleOnBlue,
        aiBubbleBackground: Palette.aiBubble,
        aiBubbleContent: Palette.bubbleOnBlue,
        aiBubbleBorder: Palette.aiBubbleBorderDark,

        success: Palette.success,
        successContainer: Palette.successContainerDark,
        warning: Palette.warning,
        warningContainer: Palette.warningContainerDark,
        danger: Palette.danger,
        dangerContainer: Palette.dangerContainerDark,
        inverseContent: .white,
        scrim: .black,

        glassSurface: Palette.glassSurfaceDark,
        glassBorder: Palette.glassWhite30
    )

    /// 亮色语义（`Theme.kt:18-41`）
    static let light = Colors(
        textPrimary: Palette.lightTextPrimary,
        textSecondary: Palette.lightTextSecondary,
        // Kotlin 亮色三级文字直接用 outlineVariant = PastelSky@0.55
        textTertiary: Palette.pastelSky.opacity(0.55),
        metadataContent: Palette.lightTextSecondary,
        captionContent: Palette.lightTextSecondary.opacity(0.78),

        background: Palette.lightBackground,
        surface: Palette.lightSurface,
        card: Palette.lightCard,
        divider: Palette.lightDivider,
        dividerBackground: Palette.lightCard.opacity(0.7),

        primary: Palette.brandBlue,
        onPrimary: Palette.bubbleOnBlue,
        primaryContainer: Palette.pastelLavender,
        onPrimaryContainer: Palette.bubbleOnBlue,

        selfBubbleBackground: Palette.selfBubbleLight,
        selfBubbleContent: Palette.bubbleOnBlue,
        aiBubbleBackground: Palette.aiBubble,
        aiBubbleContent: Palette.bubbleOnBlue,
        aiBubbleBorder: Palette.aiBubbleBorderLight,

        success: Palette.success,
        successContainer: Palette.successContainerLight,
        warning: Palette.warning,
        warningContainer: Palette.warningContainerLight,
        danger: Palette.danger,
        dangerContainer: Palette.dangerContainerLight,
        inverseContent: .white,
        scrim: .black,

        glassSurface: Palette.glassSurfaceLight,
        glassBorder: .black.opacity(0.06)
    )

    /// 按当前 ColorScheme 取语义色。
    ///
    /// ⚠️ 判暗不靠 `colorScheme == .dark` —— Android 那边是靠
    /// `surface.luminance() < 0.5f`。两者在系统跟随下等价，但 luminance 判法
    /// 在自定义 surface 时仍然正确，故这里保留同源思路。
    static func colors(_ scheme: ColorScheme) -> Colors {
        scheme == .dark ? dark : light
    }

    // MARK: - 圆角（规格 §3.1）

    enum Radius {
        /// `GlassCard.kt:60`
        static let glassCard: CGFloat = 24
        /// `GlassTopBar.kt:58,61` / 聊天顶栏 / 输入栏外框 / 拉黑提示
        static let topBarCapsule: CGFloat = 28
        /// `WeChatChatInputBar.kt:122,126`
        static let chatInput: CGFloat = 21
        /// `LiquidGlass.kt:51` 磨砂玻璃导航
        static let frostedNav: CGFloat = 26
        /// `LiquidGlass.kt:61`
        static let glassPill: CGFloat = 32
        /// `LiquidGlass.kt:22` glass 默认
        static let glassDefault: CGFloat = 16
        /// `GlassEditDialog.kt:32`
        static let dialog: CGFloat = 28
        /// `PetalApiCards.kt:669,931,1179` / `AboutScreen.kt:402` /
        /// `BackupScreen.kt:383` / `SkillsCenterScreen.kt:220`
        static let card: CGFloat = 16
        /// `SettingsScreen.kt:228,293,358` 设置页功能大卡
        static let settingsBigCard: CGFloat = 20
        /// `PetalApiCards.kt:247` 等全文件统一的输入框
        static let field: CGFloat = 12
        /// `MemoryScreen.kt:611,731,832`
        static let memoryCard: CGFloat = 12
        /// `SkillsCenterScreen.kt:281-285`
        static let skillRow: CGFloat = 14
        /// `BackupScreen.kt:410`
        static let button: CGFloat = 10
        /// `MemoryScreen.kt:231,580` 同伴 chip / 过滤 chip
        static let chip: CGFloat = 20
        /// `MemoryScreen.kt:1069` 心情 chip
        static let moodChip: CGFloat = 14
        /// `PetalApiCards.kt:98-123` 状态 chip
        static let statChip: CGFloat = 6
        /// `AppDimens.kt:13` 引用块 / `:22` 图片 / `:37` 时间分割线
        static let quote: CGFloat = 12
        static let image: CGFloat = 14
        static let timeDivider: CGFloat = 8
    }

    // MARK: - 间距（规格 §3.2）

    enum Space {
        static let hairline: CGFloat = 1
        static let micro: CGFloat = 2
        static let tight: CGFloat = 3
        static let minUnit: CGFloat = 4
        static let half: CGFloat = 6
        /// 标准单元：顶栏动作 gap、卡片列表 spacedBy、图标-文字 gap
        static let standard: CGFloat = 8
        /// 顶栏左右内边距、卡片图标-文字 gap
        static let topBar: CGFloat = 10
        /// 主要内边距：卡片内边距、section 上下、chip 水平内边距
        static let cardPadding: CGFloat = 12
        /// 次级内边距：列表项水平内边距
        static let listItem: CGFloat = 14
        /// **页面水平安全边距（最常用）**
        static let page: CGFloat = 16
        /// 备份卡内边距、设置弹窗竖向内边距
        static let sheet: CGFloat = 18
        /// 卡片内边距（设置大卡、关于卡、备份卡）
        static let cardInner: CGFloat = 20
        /// `AppDimens.kt:24` 系统提示水平内边距
        static let systemTip: CGFloat = 24
        /// 页面顶部留白、加载圈尺寸
        static let pageTop: CGFloat = 32
        /// 首页底部让位（导航高度）
        static let bottomInset: CGFloat = 80
    }

    // MARK: - 字号（规格 §2，实际项目里用的覆盖值）

    /// ⚠️ 第 124 轮：原名 `Font`，但它**遮蔽了 `SwiftUI.Font`**，
    /// 导致本文件内所有 `Font.system(size:weight:)` 都解析到我自己定义的枚举上
    /// （CI 报 "type 'YuNianTheme.Font' has no member 'system'" ×12）。
    /// 改名为 `TextStyle`，与 SwiftUI 的 Font 区分开。
    enum TextStyle {
        /// `HomeScreen.kt:119-122` 首页标题
        static func homeTitle(_ c: Colors) -> Font { .system(size: 22, weight: .bold) }
        /// `GlassTopBar.kt:72-75`
        static let topBarTitle = Font.system(size: 17, weight: .semibold)
        /// `MainBottomBar.kt:127-130`
        static let bottomTab = Font.system(size: 12, weight: .semibold)
        /// `HomeScreen.kt:338-341`
        static let homeTab = Font.system(size: 14, weight: .semibold)
        /// `ChatTopBarRegion.kt:216-219`
        static let chatTopTitle = Font.system(size: 15, weight: .semibold)
        /// `ChatTopBarRegion.kt:192-196` 「对方正在输入...」
        static let chatTyping = Font.system(size: 14, weight: .regular)
        /// `SettingsWidgets.kt:92,98` SettingsRow 标题/副标题
        static let settingsRowTitle = Font.system(size: 15)
        static let settingsRowSubtitle = Font.system(size: 13)
        /// `SettingsWidgets.kt:59,62` SettingItem
        static let settingsItemTitle = Font.system(size: 14)
        static let settingsItemSubtitle = Font.system(size: 12)
        /// `BackupScreen.kt:401,403`
        static let backupTitle = Font.system(size: 17, weight: .semibold)
        static let backupDescription = Font.system(size: 13)
        /// `AboutScreen.kt:166-170`
        static let aboutAppName = Font.system(size: 28, weight: .bold)
        /// `SkillsCenterScreen.kt:238,243`
        static let skillStatus = Font.system(size: 13)
        static let skillSubtitle = Font.system(size: 13)
        /// `SkillsCenterScreen.kt:201` / `HomeScreen.kt:277` 区块标签
        static let sectionLabel = Font.system(size: 13, weight: .medium)
        /// `PetalApiCards.kt:832-863` 卡片动作按钮文字
        static let cardAction = Font.system(size: 13)
        /// `PetalApiCards.kt:98-123` 状态 chip
        static let statChip = Font.system(size: 12, weight: .medium)
        /// `SettingsScreen.kt:501` 预设列表副标题（baseUrl）
        static let presetUrl = Font.system(size: 11)
        /// `TimeDividerItem.kt:54`
        static let timeDivider = Font.system(size: 11)
        /// `MemoryScreen.kt` 卡片内
        static let memoryCategory = Font.system(size: 11, weight: .medium)
        static let memoryImportance = Font.system(size: 9)
        static let memoryBody = Font.system(size: 14)
        static let memorySummary = Font.system(size: 11)
        static let memoryTime = Font.system(size: 10)
    }
}

// MARK: - Color(hex:)

extension Color {
    /// 由 `0xRRGGBB` 构造（不带 alpha；需要透明处用 `.opacity`）。
    ///
    /// Kotlin 侧是 `0xAARRGGBB`，本文件在调用处已把 alpha 拆成 `.opacity(...)`，
    /// 这样颜色与透明度分离，和 `Colors` 结构里的字段一一对应。
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0
        )
    }
}
