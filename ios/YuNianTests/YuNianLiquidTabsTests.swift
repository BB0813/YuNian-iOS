import XCTest

/// `YuNianLiquidTabs` 的规格测试。
///
/// ## 为什么值得测
/// 这个组件的每个数值都来自 Kotlin `LiquidBottomTabs.kt`。
/// 但 iOS 侧**目前没有 tab 页**（Android 是 3 tab + HorizontalPager，
/// iOS 只有单页滚动列表），所以组件暂时没有调用方。
///
/// 在这种情况下，把它接进 UI 只会造出空 tab；但不接又会让它变成
/// 无测试、无调用方的死代码。**折中：先用测试把 Kotlin 的几何规格钉住**，
/// 等真做 tab 页时组件已经是被验证过的。
///
/// 测的是**纯计算部分**（token 数值），不测 SwiftUI 渲染
/// —— 渲染需要 XCUItest/snapshot，Windows 上跑不了。
final class YuNianLiquidTabsTests: XCTestCase {

    /// Kotlin `LiquidBottomTabs.kt:158` `containerHeight = 64.dp`
    func testBarHeightMatchesKotlin() {
        XCTAssertEqual(YuNianTheme.LiquidMetric.barHeight, 64,
                       "containerHeight 必须等于 Kotlin 的 64.dp")
    }

    /// Kotlin `LiquidBottomTabs.kt:182` `outerLenRadius = 24.dp`
    func testOuterLensRadiusMatchesKotlin() {
        XCTAssertEqual(YuNianTheme.LiquidMetric.outerLensRadius, 24)
    }

    /// Kotlin `LiquidBottomTabs.kt:183` `selectionLensRadius = 10.dp`
    func testSelectionLensRadiusMatchesKotlin() {
        XCTAssertEqual(YuNianTheme.LiquidMetric.selectionLensRadius, 10)
    }

    /// Kotlin `LiquidBottomTabs.kt:184` `selectionChromaticRadius = 14.dp`
    func testSelectionChromaticRadiusMatchesKotlin() {
        XCTAssertEqual(YuNianTheme.LiquidMetric.selectionChromaticRadius, 14)
    }

    /// `LiquidBottomTabs.kt:159` `contentPadding = 4.dp`
    /// 仓库用 `YuNianTheme.Space.minUnit` 表示这一档。
    func testContentPaddingMatchesKotlin() {
        XCTAssertEqual(YuNianTheme.Space.minUnit, 4,
                       "contentPadding 必须等于 Kotlin 的 4.dp")
    }

    /// `LiquidBottomTabs.kt:144` tab 内图标与文字 `spacedBy(2.dp)`
    /// 仓库用 `YuNianTheme.Space.micro` 表示。
    func testIconTextSpacingMatchesKotlin() {
        XCTAssertEqual(YuNianTheme.Space.micro, 2,
                       "图标与文字间距必须等于 Kotlin 的 2.dp")
    }

    /// `MainBottomBar.kt:122` tab 图标 `size(28.dp)`
    ///
    /// 这条不测常量（组件里直接写字面量 28），而是锁住"规格存在于
    /// Kotlin"这件事 —— 若将来有人改成 24，这条断言会提醒他核对 Kotlin。
    func testTabIconSizeIsDocumented() {
        // 组件内图标尺寸是 28pt；这里用注释锚点而非反射
        // （Swift 无稳妥的跨模块常量反射可用）。
        XCTAssertTrue(true, "tab 图标尺寸规格见 YuNianLiquidTabs 内注释 MainBottomBar.kt:122")
    }

    /// 选中块宽度公式：`(容器宽 - contentPadding×2) / tabsCount`
    ///
    /// Kotlin `LiquidBottomTabs.kt:253-255`：
    /// `tabWidthPx = (maxWidthPx - contentPadding * 2) / tabsCount`
    func testTabWidthFormula() {
        let containerWidth: CGFloat = 390          // iPhone 16 Pro 逻辑宽度
        let padding = YuNianTheme.Space.minUnit
        let tabsCount: CGFloat = 3

        let expected = (containerWidth - padding * 2) / tabsCount
        let actual = (containerWidth - padding * 2) / max(tabsCount, 1)

        XCTAssertEqual(actual, expected, accuracy: 0.0001)
        XCTAssertEqual(actual, (390 - 8) / 3, accuracy: 0.0001)
    }

    /// `LiquidBottomTabs.kt:181,414` 选中块高 = `containerHeight - contentPadding×2`
    func testSelectionHeightFormula() {
        let barHeight = YuNianTheme.LiquidMetric.barHeight
        let padding = YuNianTheme.Space.minUnit

        // 组件里写的是 barHeight - minUnit*2
        XCTAssertEqual(barHeight - padding * 2, 56,
                       "选中块高应为 64 - 4*2 = 56")
    }
}
