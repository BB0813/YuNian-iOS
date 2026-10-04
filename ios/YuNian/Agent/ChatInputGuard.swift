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
    /// 3. 绑定的配置 `apiKey` 非空白 **或** provider 为 PARTNER → true
    static func hasCompanionBoundConfig(
        database: YuNianDatabase, companionId: Int64
    ) -> Bool {
        do {
            return try database.pool.read { db in
                // 列名直接用真实列（不用别名）—— 否则 SQL 检查器会报「不是 schema 列」
                let row = try Row.fetchOne(
                    db,
                    sql: """
                        SELECT c.apiConfigId, a.apiKey, a.provider
                        FROM companions c
                        LEFT JOIN api_configs a ON a.id = c.apiConfigId
                        WHERE c.id = ?
                        """,
                    arguments: [companionId]
                )
                guard let row else { return false }
                guard let boundId = row["apiConfigId"] as Int64?, boundId > 0 else { return false }
                // LEFT JOIN 未命中 → apiKey / provider 为 nil → 视为配置不存在
                guard (row["apiKey"] as String?) != nil || (row["provider"] as String?) != nil else {
                    return false
                }
                let apiKeyBlank = (row["apiKey"] as String?)?.isEmpty ?? true
                let isPartner = (row["provider"] as String?) == "PARTNER"
                return !apiKeyBlank || isPartner
            }
        } catch {
            return false   // 与 Android 的 catch → false 一致
        }
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
