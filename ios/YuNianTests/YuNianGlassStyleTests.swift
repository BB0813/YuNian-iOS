import XCTest

/// 毛玻璃风格分叉的解析逻辑。
///
/// 用户的原始要求：**iOS 17 与 26 分水岭** ——
/// iOS 17 默认旧原生 UI，iOS 26+ 默认新毛玻璃原生 UI。
@testable import YuNian
final class YuNianGlassStyleTests: XCTestCase {

    private let key = YuNianGlassStyle.overrideKey

    override func setUp() {
        super.setUp()
        UserDefaults.standard.removeObject(forKey: key)
    }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: key)
        super.tearDown()
    }

    // MARK: - 覆盖优先于 OS

    /// 用户显式选了 legacy → 即使在 iOS 26 上也听用户的。
    /// 这就是"**默认**走某档"的含义：默认可被覆盖。
    func testExplicitOverrideWinsOverOS() {
        XCTAssertEqual(YuNianGlassStyle.resolved(raw: "legacy"), .legacy)
        XCTAssertEqual(YuNianGlassStyle.resolved(raw: "liquidGlass"), .liquidGlass)
    }

    /// 垃圾值 / 空串都回落自动判定，不当机也不卡在坏状态。
    func testGarbageOverrideFallsBackToAuto() {
        let auto = YuNianGlassStyle.autoDetected()
        XCTAssertEqual(YuNianGlassStyle.resolved(raw: "not-a-style"), auto)
        XCTAssertEqual(YuNianGlassStyle.resolved(raw: ""), auto)
    }

    /// nil（从未设置）→ 自动判定。
    func testNilOverrideUsesAuto() {
        XCTAssertEqual(YuNianGlassStyle.resolved(raw: nil),
                       YuNianGlassStyle.autoDetected())
    }

    // MARK: - 分水岭

    /// `autoDetected()` 在 iOS 26+ 必须是 `.liquidGlass`，
    /// 在 26 以下必须是 `.legacy` —— 这是用户要求的核心。
    ///
    /// ⚠️ 测试本身跑在 CI 的 iOS 26 SDK + 模拟器上，
    /// 所以断言的是"跑在哪个 OS 就返回哪档"。真实覆盖由
    /// `testExplicitOverrideWinsOverOS` 保证。
    func testAutoDetectedMatchesRunningOS() {
        let detected = YuNianGlassStyle.autoDetected()
        if #available(iOS 26.0, *) {
            XCTAssertEqual(detected, .liquidGlass,
                           "iOS 26+ 默认必须是新毛玻璃")
        } else {
            XCTAssertEqual(detected, .legacy,
                           "iOS 26 以下默认必须是旧原生")
        }
    }

    /// rawValue 往返一致（UserDefaults 存的是它）。
    func testRawValueRoundTrip() {
        for s in YuNianGlassStyle.allCases {
            XCTAssertEqual(YuNianGlassStyle(rawValue: s.rawValue), s)
        }
        XCTAssertEqual(YuNianGlassStyle.allCases.count, 2, "只有两档")
    }

    // MARK: - current() 读 UserDefaults

    /// 存了什么就读回什么。
    func testCurrentReadsUserDefaults() {
        UserDefaults.standard.set("legacy", forKey: key)
        XCTAssertEqual(YuNianGlassStyle.current(), .legacy)

        UserDefaults.standard.set("liquidGlass", forKey: key)
        XCTAssertEqual(YuNianGlassStyle.current(), .liquidGlass)
    }

    /// 清空后回到自动判定。
    func testCurrentAfterRemoveIsAuto() {
        UserDefaults.standard.set("legacy", forKey: key)
        UserDefaults.standard.removeObject(forKey: key)
        XCTAssertEqual(YuNianGlassStyle.current(), YuNianGlassStyle.autoDetected())
    }

    // MARK: - 展示文案

    /// 两档的描述必须都能说清"这是什么"与"适用谁"，
    /// 且都要提到原生（用户要的是原生，不是近似）。
    func testDisplayNameAndDetailMentionNative() {
        for s in YuNianGlassStyle.allCases {
            XCTAssertFalse(s.displayName.isEmpty)
            XCTAssertFalse(s.detail.isEmpty)
            XCTAssertTrue(s.detail.contains("原生") || s.displayName.contains("原生"),
                          "\(s.rawValue) 的说明应点明是原生实现")
        }
        // 档位名要带 OS 范围，用户才知道自己为什么在某档
        XCTAssertTrue(YuNianGlassStyle.legacy.displayName.contains("iOS 17"))
        XCTAssertTrue(YuNianGlassStyle.liquidGlass.displayName.contains("iOS 26"))
    }
}
