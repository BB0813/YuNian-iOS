import Foundation
import GRDB

/// 输入安全检查门 —— 精确对应 Android `ChatGenerationManager.startSendMessage` 里的这一段：
///
/// ```kotlin
/// if (apiConfigRepository.getActiveEnabledConfig() == null && !hasCompanionBoundConfig()) {
///     for (msg in contentBatch) {
///         val inputCheck = runCatching { ContentFilter.checkInput(msg) }.getOrNull()
///         if (inputCheck == null) { Error("安全检查异常"); return null }
///         if (inputCheck.isViolating) {
///             BanManager.recordViolation(application, inputCheck.level)
///             _events.tryEmit(ChatUiEvent.ContentBlocked("内容违规: ${inputCheck.reason}"))
///             return null
///         }
///     }
///     Error("请先配置API：我 → API设置 → 添加密钥"); return null
/// }
/// ```
///
/// ## ⚠️ 为什么这个门是**有条件**的
/// 第一直觉是「输入安全检查应该每回合都做」。但 Android 的实际语义是：
/// **只有在完全没有 API 配置（全局无启用配置、且当前伴侣没有绑定可用配置）时才检查**；
/// 有配置时直接进入生成，不做 `checkInput`。
///
/// 若按直觉改成每回合检查，会拦住 Android 会放行的输入 —— 那是**真实的跨端不一致**，
/// 且症状是「iOS 上某些话发不出去」这种无法归因的问题。
/// 本项目的原则是「等价优先于更好」，故此处的正确做法是复刻条件，不是加强防护。
enum ChatInputGuard {

    enum Outcome: Equatable {
        /// 不需要检查（有可用配置）—— 直接进入回合。
        case notChecked
        /// 需要检查，且**未命中违规**—— Android 随后会报「请先配置API」。
        case needsCheck
        /// 命中违规，应当拦截（对应 `ContentBlocked`）。
        case blocked(reason: String)
        /// 安全检查本身失败（过滤器未装载 / 抛错）。
        case checkFailed
    }

    /// 纯逻辑：是否应当执行输入检查。
    /// - Parameters:
    ///   - activeEnabled: `apiConfigRepository.getActiveEnabledConfig() != null`
    ///   - companionBound: `hasCompanionBoundConfig()`
    static func shouldCheckInput(activeEnabled: Bool, companionBound: Bool) -> Bool {
        // Android: `getActiveEnabledConfig() == null && !hasCompanionBoundConfig()`
        !activeEnabled && !companionBound
    }

    /// 对应 Android 的 `hasCompanionBoundConfig()`：
    /// 1. 伴侣的 `apiConfigId` 为 null 或 <= 0 → false
    /// 2. 该 id 指向的配置不存在 → false
    /// 3. 绑定的配置有 Key **或** provider 为 PARTNER → true
    ///
    /// ## ⚠️ 第 188 轮：第 3 条的"有 Key"必须查 Keychain，不能查行内 `apiKey`
    ///
    /// Android 的原文是 `bound.apiKey.isNotBlank()`（`AiService.kt:190`），
    /// 因为 Android 把 Tink 密文写进 `api_configs.apiKey`（非空）。
    ///
    /// 但 **iOS 刻意把行内 `apiKey` 写成空串** ——
    /// 见 `ApiConfigRepository.upsertActiveConfig` 的安全决策：
    /// iOS 没有 Android 的列加密，明文落库等于把密钥写进未加密 SQLite。
    /// iOS 的真源是 **Keychain**。
    ///
    /// 所以照抄 Android 的写法在 iOS 上**恒为 false**（非 PARTNER 时）：
    ///   `apiKeyBlank = true`，`isPartner = false` → `!true || false = false`
    ///
    /// 后果：`shouldCheckInput(activeEnabled:companionBound:)` 里的
    /// `companionBound` 永远拿不到 true → 只要全局没有启用配置，
    /// 即使伴侣**明明绑定了可用配置**，也照样走内容过滤并报「请先配置API」。
    /// 症状是「我明明给这个角色配了专属渠道，它还说我没配」。
    ///
    /// 这与第 121 轮 `activeConfig()` 那个 bug 是**同一根因的第二个实例**：
    /// 照抄了一个"依赖行内 apiKey 非空"的谓词，而 iOS 那一列永远是空的。
    static func hasCompanionBoundConfig(
        database: YuNianDatabase, companionId: Int64
    ) -> Bool {
        do {
            return try database.pool.read { db in
                // 列名直接用真实列（不用别名）—— 否则 SQL 检查器会报「不是 schema 列」。
                // ⚠️ 不再 SELECT `a.apiKey` —— 那一列在 iOS 上恒为空串，
                //    读它只会把 bug 再引回来。
                let row = try Row.fetchOne(
                    db,
                    sql: """
                        SELECT c.apiConfigId, a.provider
                        FROM companions c
                        LEFT JOIN api_configs a ON a.id = c.apiConfigId
                        WHERE c.id = ?
                        """,
                    arguments: [companionId]
                )
                guard let row else { return false }
                guard let boundId = row["apiConfigId"] as Int64?, boundId > 0 else { return false }

                let provider = row["provider"] as String?
                // ✅ iOS 的真源：Keychain 里那条配置自己的槽
                let key = KeychainStore.resolvedAPIKey(configId: boundId)
                return isBoundConfigUsable(provider: provider, key: key)
            }
        } catch {
            return false   // 与 Android 的 catch → false 一致
        }
    }

    /// 纯逻辑：绑定的配置是否"可用" —— **可直接单测，不碰 DB / Keychain**。
    ///
    /// 规则逐条对应 Android `AiService.kt:189-190` 的
    /// `bound != null && (bound.apiKey.isNotBlank() || provider == PARTNER)`，
    /// 只是把"有没有 key"的来源从行内列换成了 Keychain。
    ///
    /// - Parameters:
    ///   - provider: 绑定配置的 provider；**nil 表示那条配置不存在**
    ///     （LEFT JOIN 未命中），此时一律不可用。
    ///   - key: 已从 Keychain 解析出来的 key（空串表示没有）。
    static func isBoundConfigUsable(provider: String?, key: String) -> Bool {
        guard let provider else { return false }
        return !key.isEmpty || provider == "PARTNER"
    }

    /// 组合判定 + 执行检查。返回 iOS 侧应当采取的动作。
    static func evaluate(
        text: String,
        database: YuNianDatabase,
        companionId: Int64,
        activeEnabled: Bool,
        filter: ContentFilter = .shared
    ) -> Outcome {
        let companionBound = hasCompanionBoundConfig(database: database, companionId: companionId)

        guard shouldCheckInput(activeEnabled: activeEnabled, companionBound: companionBound) else {
            return .notChecked
        }

        // 未装载 / 抛错 → Android 报「安全检查异常」
        guard let result = filter.checkInputOrNil(text) else { return .checkFailed }
        guard !result.isViolating else { return .blocked(reason: result.reason) }
        return .needsCheck
    }
}
