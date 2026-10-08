import XCTest

/// 按配置存放 API Key（第 186 轮）。
///
/// ## 修的是什么
/// 第 182 轮做了"多渠道切换"，但 key 存在**一个全局槽**里 ——
/// 切 provider/baseUrl/model 都生效，**key 换不了**（谁最后保存是谁的）。
/// 于是"多配置"名存实亡，角色级 API 隔离（Android `AiService.kt:177-186`）
/// 也无从谈起。
///
/// ## 为什么只测纯逻辑
/// 仓内此前**没有任何测试碰过 Keychain** —— 说明测试宿主里它不一定可用
/// （缺 entitlement 会返回 errSecMissingEntitlement）。
/// 所以把"选哪个"（`pickAPIKey`，纯函数，可测）与"从哪读"
/// （`resolvedAPIKey`，碰 Keychain，不测）分开。
/// 这样规则本身有测试，而测试不会因为环境的 Keychain 差异而假失败。
@testable import YuNian
final class PerConfigAPIKeyTests: XCTestCase {

    // MARK: - 槽名

    /// 不同配置必须是不同槽 —— 这是"按配置存放"的全部意义。
    func testSlotNamesAreDistinctPerConfig() {
        XCTAssertNotEqual(KeychainStore.Key.apiKeyFor(1),
                          KeychainStore.Key.apiKeyFor(2))
        XCTAssertEqual(KeychainStore.Key.apiKeyFor(7), "api_key_7")
        XCTAssertEqual(KeychainStore.Key.apiKeyFor(123), "api_key_123")
    }

    /// 按配置槽名不能撞上旧单槽 —— 否则迁移回退会自我覆盖。
    func testPerConfigSlotDoesNotCollideWithLegacy() {
        XCTAssertNotEqual(KeychainStore.Key.apiKeyFor(0), KeychainStore.Key.apiKey)
        // 前缀存在且旧槽名不是它的成员
        XCTAssertTrue(KeychainStore.Key.apiKeyFor(1)
            .hasPrefix(KeychainStore.Key.apiKeyPrefix))
        XCTAssertFalse(KeychainStore.Key.apiKey
            .hasPrefix(KeychainStore.Key.apiKeyPrefix))
    }

    // MARK: - 选择规则

    /// 按配置槽有值 → 用它（即使旧槽也有值）。
    /// 这是"切换配置能换 key"的核心断言。
    func testPerConfigWins() {
        XCTAssertEqual(
            KeychainStore.pickAPIKey(perConfig: "sk-new", legacy: "sk-old"),
            "sk-new"
        )
    }

    /// 按配置槽缺失 → 回退旧槽（第 186 轮前装的用户只有旧槽）。
    func testFallsBackToLegacyWhenPerConfigMissing() {
        XCTAssertEqual(
            KeychainStore.pickAPIKey(perConfig: nil, legacy: "sk-old"),
            "sk-old"
        )
    }

    /// 按配置槽是**空串**也算缺失，回退旧槽。
    /// （边界：空串与 nil 语义必须一致，否则"清空某配置的 key"
    ///   会把它误当成有效 key 下发。）
    func testEmptyPerConfigFallsBackToLegacy() {
        XCTAssertEqual(
            KeychainStore.pickAPIKey(perConfig: "", legacy: "sk-old"),
            "sk-old"
        )
    }

    /// 两者都没有 → 空串（调用方据此判"未配置"）。
    func testBothMissingYieldsEmpty() {
        XCTAssertEqual(KeychainStore.pickAPIKey(perConfig: nil, legacy: nil), "")
        XCTAssertEqual(KeychainStore.pickAPIKey(perConfig: "", legacy: ""), "")
    }

    /// 两个都有但按配置是空白、旧槽有值 → 用旧槽。
    func testWhitespaceLegacyStillReturned() {
        // 注意：这里**不做 trim** —— 与 `resolvedAPIKey` 的既有语义一致
        // （调用方自己判 isEmpty）。写死这条是为了防止将来有人"顺手 trim"
        // 而改变行为。
        XCTAssertEqual(
            KeychainStore.pickAPIKey(perConfig: nil, legacy: "  "),
            "  "
        )
    }
}
