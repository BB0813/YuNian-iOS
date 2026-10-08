import Foundation
import GRDB
import os

/// API 配置仓储（M3 最小实现）。
///
/// 存在的直接理由：Android 的 `syncRuntimeConfig` 需要知道**当前启用的配置**
/// 才能决定是否发送 PARTNER 会话头：
/// ```kotlin
/// val isPartner = activeApi?.provider == ApiProvider.PARTNER
/// buildCredentialsJson(
///     sessionToken = if (isPartner) partnerSession?.token else null, ...)
/// ```
/// 没有这个读取，iOS 只能无条件发送 session/client_id，与 Android 的 JSON 不同。
///
/// ## 已知简化
/// 只读**不写**。iOS 的 M2 凭证入口写 Keychain（`AppEnvironment.setAPIKey`），
/// 完整的多配置管理界面仍是 M3 后续项。
final class ApiConfigRepository {

    private let database: YuNianDatabase
    private let log = Logger(subsystem: "com.yunian.ai", category: "repo.apiconfig")

    init(database: YuNianDatabase) {
        self.database = database
    }

    /// 一行配置（只取 PARTNER 门控需要的列 + 便于诊断的几列）。
    struct ApiConfig: Sendable, Equatable {
        var id: Int64
        var provider: String
        var name: String
        var baseUrl: String
        var model: String
        var formatHint: String
        var isEnabled: Bool
    }

    /// 当前启用的配置。
    ///
    /// ⚠️ SQL 逐字对应 Android `ApiConfigDao.getActiveEnabledConfig()`：
    /// ```sql
    /// SELECT * FROM api_configs
    /// WHERE (apiKey IS NOT NULL AND apiKey != '' OR provider = 'PARTNER')
    ///   AND isEnabled = 1
    /// ORDER BY id DESC LIMIT 1
    /// ```
    ///
    /// **运算符优先级**：SQL 里 `AND` 比 `OR` 结合更紧，所以第一行实际是
    /// `((apiKey IS NOT NULL AND apiKey != '') OR provider = 'PARTNER')`，
    /// 再 `AND isEnabled = 1`。写成
    /// ⚠️ 第 121 轮：谓词从 Android 的
    ///   `(apiKey IS NOT NULL AND apiKey != '' OR provider = 'PARTNER') AND isEnabled = 1`
    ///   改成 `isEnabled = 1`。**这是修一个真 bug，不是简化。**
    ///
    /// ## 为什么原来的写法在 iOS 上恒不成立
    /// Android 的 `api_configs.apiKey` 存的是 Tink 密文 `enc:v4:...`（非空），
    /// 所以那个条件成立。而 iOS **刻意把行里的 apiKey 写成空串**
    /// （见本文下方 `upsertActiveConfig` 的安全决策：iOS 没有 Android 的
    ///   列加密，明文落库等于把密钥写进未加密 SQLite）。
    /// 于是保存成功后 `isEnabled=1` 但 `apiKey=''`，除 PARTNER 外**全部**
    /// 被谓词过滤掉 → `activeConfig()` 恒返回 nil。
    ///
    /// 用户可见症状：**渠道保存后仍显示「未配置 API Key」**，
    /// 看起来像"添加渠道失败"，反复保存反复失败。（用户三次反馈同一现象。）
    ///
    /// ## 为什么改成 isEnabled = 1 是安全的
    /// 1. 这正是 Rust `load_api_config` 的读法（native_gateway.rs，
    ///    本文件上方注释已记录：`... WHERE isEnabled = 1 ORDER BY id DESC LIMIT 1`）。
    ///    宿主观测与引擎读法必须一致，否则会出现"UI 说没配、回合却照样跑"。
    /// 2. iOS 上"有没有 key"由 **Keychain** 判定，不由行判定 ——
    ///    用行里的空串去推断是本末倒置。
    /// 3. PARTNER 豁免不再需要：PARTNER 也没有行内 key，旧写法对它是特例，
    ///    现在统一按 isEnabled 走，行为反而更一致。
    func activeConfig() throws -> ApiConfig? {
        try database.pool.read { db in
            guard let row = try Row.fetchOne(db, sql: """
            SELECT id, provider, name, baseUrl, model, formatHint, isEnabled
            FROM api_configs
            WHERE isEnabled = 1
            ORDER BY id DESC LIMIT 1
            """) else { return nil }

            return ApiConfig(
                id: row["id"] as Int64? ?? 0,
                provider: row["provider"] as String? ?? "",
                name: row["name"] as String? ?? "",
                baseUrl: row["baseUrl"] as String? ?? "",
                model: row["model"] as String? ?? "",
                formatHint: row["formatHint"] as String? ?? "",
                isEnabled: (row["isEnabled"] as Int? ?? 0) != 0
            )
        }
    }

    /// 全部已保存配置（按 id 升序）。
    ///
    /// ## 为什么需要
    /// `upsertActiveConfig` 是按 provider 找行的，所以实际形态是
    /// "每家服务商一行"。用户切过几家之后，行里都留着数据 ——
    /// 但仓库层只有 `activeConfig()`（读当前启用那一条），
    /// **没有任何方法能把全部列出来**，于是：
    ///   · 用户不知道自己配过哪些
    ///   · 想切回之前那家，只能重新填一遍 baseUrl/model/key
    ///
    /// ## 排序
    /// `ORDER BY id ASC` —— 配置的创建顺序。启用态由 `isEnabled` 表达，
    /// 不参与排序（同时只有一条启用，见 `activate`）。
    func allConfigs() throws -> [ApiConfig] {
        try database.pool.read { db in
            let rows = try Row.fetchAll(db, sql: """
                SELECT id, provider, name, baseUrl, model, formatHint, isEnabled
                FROM api_configs
                ORDER BY id ASC
                """)
            return rows.map { row in
                ApiConfig(
                    id: row["id"] as Int64? ?? 0,
                    provider: row["provider"] as String? ?? "",
                    name: row["name"] as String? ?? "",
                    baseUrl: row["baseUrl"] as String? ?? "",
                    model: row["model"] as String? ?? "",
                    formatHint: row["formatHint"] as String? ?? "",
                    isEnabled: (row["isEnabled"] as Int? ?? 0) != 0
                )
            }
        }
    }

    /// 启用指定配置，其余全部置停用。
    ///
    /// ## 单活语义
    /// 同一时刻只有一条启用。Rust 侧读的是
    /// `WHERE isEnabled = 1 ORDER BY id DESC LIMIT 1`，
    /// 多条启用会让它取 id 最大的那条 —— 与 UI 显示的不一定一致。
    ///
    /// ⚠️ 第 184 轮：**顺序很关键** —— 必须先启用目标，再停用其它。
    /// 我第一版写的是"先停用全部，再启用目标"：
    /// ```swift
    /// try db.execute("UPDATE api_configs SET isEnabled = 0 WHERE isEnabled = 1")
    /// try db.execute("UPDATE api_configs SET isEnabled = 1 WHERE id = ?", ...)
    /// ```
    /// 目标 id 不存在时，第一句**已经把当前生效那条停掉了**，
    /// 第二句影响 0 行 → 结果是没有一条启用，
    /// 用户下一次发消息直接失败。
    ///
    /// CI（05375fc）的 `testActivateUnknownIdLeavesCurrentIntact`
    /// 抓到的：`XCTAssertEqual failed: ("nil")` —— activeConfig() 变 nil。
    ///
    /// - Returns: 是否真的启用到了行（id 不存在时 false，且不影响当前启用态）。
    @discardableResult
    func activate(id: Int64) throws -> Bool {
        try database.pool.write { db in
            // ① 先启用目标。影响 0 行说明 id 不存在 —— 直接返回，
            //    当前启用态一个字都没动。
            try db.execute(
                sql: "UPDATE api_configs SET isEnabled = 1 WHERE id = ?",
                arguments: [id]
            )
            guard db.changesCount > 0 else { return false }

            // ② 目标已启用，再停用其它（`id != ?` 保证不会把自己也停掉）
            try db.execute(
                sql: "UPDATE api_configs SET isEnabled = 0 WHERE isEnabled = 1 AND id != ?",
                arguments: [id]
            )
            return true
        }
    }

    /// 删除一条配置。
    ///
    /// 启用中的那条被删时，剩下里 id 最大的一条**不会**自动顶上 ——
    /// 与 Android 的 `deleteConfig` 行为一致（删完就没有启用的，
    /// 由用户去挑一个）。顶上来的话，用户没点过它就换了渠道，
    /// 请求会悄悄打到别家去。
    @discardableResult
    func delete(id: Int64) throws -> Bool {
        try database.pool.write { db in
            try db.execute(sql: "DELETE FROM api_configs WHERE id = ?", arguments: [id])
            return db.changesCount > 0
        }
    }

    /// 当前启用配置是否为 PARTNER（内置 Clove 云通道）。
    ///
    /// 无任何启用配置时返回 **false**（与 Android 一致：
    /// `activeApi?.provider == PARTNER` 在 null 时为 false）。
    func isActiveProviderPARTNER() throws -> Bool {
        try activeConfig()?.provider == "PARTNER"
    }

    /// 非抛出的 `activeConfig()`（视图层用，避免 `try?` 把错误吞掉后难查）。
    func tryActiveConfig() -> ApiConfig? {
        try? activeConfig()
    }

    /// 诊断用：配置总数 / 启用数。
    func counts() throws -> (total: Int, enabled: Int) {
        try database.pool.read { db in
            let total = try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM api_configs") ?? 0
            let enabled = try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM api_configs WHERE isEnabled = 1") ?? 0
            return (total, enabled)
        }
    }

    // MARK: - 写（M2 最小凭证入口的 DB 侧）

    /// 写入（或更新）**唯一一条启用配置**，使 Rust 的 `load_api_config` 有行可取。
    ///
    /// ## 为什么必须有这一步（第 40 轮发现的缺口）
    /// Rust 在 LLM 请求的关键路径上：
    /// ```rust
    /// let cfg = self.load_api_config()?;                       // native_gateway.rs:765
    /// if cfg.model.trim().is_empty() { return Err("模型名未配置…") }
    /// ```
    /// SQL 是 `... WHERE isEnabled = 1 ORDER BY id DESC LIMIT 1`，无行时报
    /// 「无可用 API 配置」。它提供的是 **provider / baseUrl / model / temperature /
    /// maxTokens / formatHint**；而 `api_key` 由 `credentials_json` 覆盖提供
    /// （`all_api_keys` 优先取宿主传入的明文）。
    ///
    /// 因此：**只有 Keychain 里的 key 是不够的**，必须同时有一条启用的行，
    /// 否则每个回合都会失败。
    ///
    /// ## ⚠️ 为什么把行里的 `apiKey` 写成空串（安全决策，勿改）
    /// `all_api_keys` 的兜底链是：credentials 的 `api_key` → 行里的 `api_key`。
    /// 因此把明文 key 写进行里**也能让 iOS 跑通**，但那等于把明文密钥落进
    /// **未加密的 SQLite 文件**（iOS 侧没有 Android 的 Tink 列加密）。
    /// Android 的行里存的是 `enc:v4:...` 密文，Rust 读到了也无法使用。
    ///
    /// 所以这里保持空串，让密钥只走 `credentials_json`（内存中的明文，
    /// 由 Keychain 取出的那一份）：
    ///   - 与 Android 的设计意图一致（Kotlin 解密后经 credentials 传入）
    ///   - 不把明文落盘
    ///   - 若 Keychain 里没有 key，`resolve_keys` 会明确报「没有可用的 API Key」，
    ///     而不是拿着密文/空串去打一个注定失败的请求
    ///
    /// ## 为什么不默认猜一个 provider
    /// 选错 provider 的后果是请求打到错误的 baseUrl（静默失败或 404）。
    /// 由调用方显式传入 —— UI 上让用户选，而不是我们替他决定。
    @discardableResult
    func upsertActiveConfig(
        provider: String,
        model: String,
        baseUrl: String,
        name: String? = nil,
        temperature: Double = 0.7,
        maxTokens: Int? = nil,
        formatHint: String = "openai"
    ) throws -> Int64 {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return try database.pool.write { db -> Int64 in
            // 与 Android 语义一致：同一时刻只有一条启用配置（ORDER BY id DESC LIMIT 1 取最新）
            try db.execute(sql: "UPDATE api_configs SET isEnabled = 0 WHERE isEnabled = 1")

            if let existingId = try Int64.fetchOne(
                db,
                sql: "SELECT id FROM api_configs WHERE provider = ? LIMIT 1",
                arguments: [provider]
            ) {
                try db.execute(sql: """
                    UPDATE api_configs SET
                      name = ?, apiKey = '', extraApiKeys = '', baseUrl = ?, model = ?,
                      temperature = ?, maxTokens = ?, isEnabled = 1,
                      connectionTested = 0, connectionTestedAt = 0, latencyMs = 0,
                      formatHint = ?
                    WHERE id = ?
                    """, arguments: [
                        name ?? provider, baseUrl, model, temperature, maxTokens,
                        formatHint, existingId,
                    ])
                return existingId
            }

            try db.execute(sql: """
                INSERT INTO api_configs
                  (provider, name, apiKey, extraApiKeys, baseUrl, model, temperature,
                   maxTokens, isEnabled, connectionTested, connectionTestedAt, latencyMs, formatHint)
                VALUES (?, ?, '', '', ?, ?, ?, ?, 1, 0, 0, 0, ?)
                """, arguments: [
                    provider, name ?? provider, baseUrl, model, temperature, maxTokens, formatHint,
                ])
            return db.lastInsertedRowID
        }
    }
}
