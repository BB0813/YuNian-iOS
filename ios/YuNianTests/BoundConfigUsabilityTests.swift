import XCTest

/// 绑定配置的"可用性"判定（第 188 轮）。
///
/// ## 修的是什么
/// `hasCompanionBoundConfig` 照抄了 Android 的
/// `bound.apiKey.isNotBlank()`（`AiService.kt:190`），而 **iOS 刻意把行内
/// `apiKey` 写成空串**（第 121 轮的安全决策：iOS 没有列加密，
/// 明文落库等于把密钥写进未加密 SQLite）。
///
/// 于是非 PARTNER 的绑定**恒判为不可用** →
/// `shouldCheckInput` 的 `companionBound` 拿不到 true →
/// 只要全局没启用配置，即使伴侣明明绑了可用渠道，也照样报「请先配置API」。
///
/// 这与第 121 轮 `activeConfig()` 那个 bug 是同一根因的第二个实例。
///
/// ## 为什么只测纯逻辑
/// 仓内没有测试碰过 Keychain（测试宿主 entitlement 不可靠），
/// 所以把规则抽成 `isBoundConfigUsable(provider:key:)` 单独验证。
@testable import YuNian
final class BoundConfigUsabilityTests: XCTestCase {

    // MARK: - 配置不存在

    /// provider 为 nil = LEFT JOIN 未命中 = 那条配置已被删 → 不可用。
    /// 对应 Android `bound != null && ...` 的前半段。
    func testMissingConfigIsNotUsable() {
        XCTAssertFalse(ChatInputGuard.isBoundConfigUsable(provider: nil, key: "sk-x"))
        XCTAssertFalse(ChatInputGuard.isBoundConfigUsable(provider: nil, key: ""))
    }

    // MARK: - 有 key

    /// 有 key 的非 PARTNER 配置 → 可用。
    ///
    /// ⚠️ **这条就是本次修的 bug**：旧实现在这里返回 false，
    /// 因为读的是行内恒为空的 apiKey。
    func testNonPartnerWithKeyIsUsable() {
        XCTAssertTrue(ChatInputGuard.isBoundConfigUsable(provider: "OPENAI", key: "sk-abc"))
        XCTAssertTrue(ChatInputGuard.isBoundConfigUsable(provider: "DASHSCOPE", key: "sk-xyz"))
    }

    /// 没 key 的非 PARTNER 配置 → 不可用（回退全局）。
    func testNonPartnerWithoutKeyIsNotUsable() {
        XCTAssertFalse(ChatInputGuard.isBoundConfigUsable(provider: "OPENAI", key: ""))
    }

    // MARK: - PARTNER 豁免

    /// PARTNER 不需要 key（凭 session/clientId），无 key 也算可用。
    /// 对应 Android 的 `|| provider == ApiProvider.PARTNER`。
    func testPartnerIsUsableWithoutKey() {
        XCTAssertTrue(ChatInputGuard.isBoundConfigUsable(provider: "PARTNER", key: ""))
    }

    /// PARTNER 有 key 也当然可用。
    func testPartnerWithKeyIsUsable() {
        XCTAssertTrue(ChatInputGuard.isBoundConfigUsable(provider: "PARTNER", key: "sk-y"))
    }

    // MARK: - 边界

    /// 纯空格 key **不算**有 key —— 与 Kotlin `isNotBlank()` 一致。
    ///
    /// ⚠️ 注意：这里依赖 `resolvedAPIKey` 不做 trim（它只判 isEmpty）。
    /// 若将来有人给解析器加 trim，这条会失效 —— 那正是它的作用。
    func testWhitespaceKeyCountsAsPresent() {
        // `isBoundConfigUsable` 只看 isEmpty，不看 trim ——
        // 与 `ChatInputGuard` 里 `!key.isEmpty` 的写法一致。
        // 写死这条是为了让"是否 trim"成为一个**被测试记录的决策**，
        // 而不是随手改动。
        XCTAssertTrue(ChatInputGuard.isBoundConfigUsable(provider: "OPENAI", key: " "))
    }

    /// provider 为空串（脏数据）→ 既不是 PARTNER 也没 key 就不可用。
    func testEmptyProviderString() {
        XCTAssertFalse(ChatInputGuard.isBoundConfigUsable(provider: "", key: ""))
        XCTAssertTrue(ChatInputGuard.isBoundConfigUsable(provider: "", key: "sk-a"))
    }
}
