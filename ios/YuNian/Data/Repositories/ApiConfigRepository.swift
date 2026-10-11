import Foundation
import GRDB
import os

/// 四条**用途线路** —— 同一条用途由自己的渠道与模型承担。
///
/// ## 为什么需要（第 203 轮，P0 ②）
/// 原先只有「读唯一一条 `isEnabled = 1`」这一种取法，于是对话、生图、朗读、
/// 向量嵌入**只能共用同一个渠道与模型**。而朗读（小米 MiMo / MiniMax 等语音模型）
/// 与向量嵌入（text-embedding 系）通常需要各自的渠道 ——
/// 拿对话渠道的模型去调 `/audio/speech` 或 `/embeddings`，服务端只会报错。
///
/// ## 存哪里：`app_meta`，不动 schema
/// `api_configs` 与 Android Room 共用、且有闸门逐字比对，**不能加列**。
/// 库里已有现成的 KV 表 `app_meta`（`key` 主键 / `value` / `updatedAt`），
/// 用它存「用途 → 渠道 id」。
///
/// ## ⚠️ 键名是跨端契约
/// Rust `agent-native/src/native_gateway.rs` 的 `route_config_id` 读的是
/// `format!("feature_route.{purpose}")`，其 `PURPOSE_*` 常量的字面值
/// 与本枚举的 `rawValue` **逐字相同**。改一边必须改另一边。
///
/// ## 取法（与 Rust `load_api_config_for` 同一套，顺序即语义）
/// 1. 有绑定 → 按绑定 id 读那一行（**不要求 `isEnabled`**：绑定是显式指定）
/// 2. 无绑定、或绑定的行已被删 → **回退到 `isEnabled = 1`**
enum FeaturePurpose: String, CaseIterable, Sendable, Identifiable {
    case chat
    case image
    case tts
    case embedding

    var id: String { rawValue }

    /// `app_meta` 里「用途 → `api_configs.id`」的键。
    var metaKey: String { "feature_route.\(rawValue)" }

    /// 该线路**模型覆盖**的键（留空 = 用渠道自身的模型）。
    ///
    /// 只有**宿主侧消费**的三条线路会读它：生图 / 朗读 / 向量嵌入都是本机的
    /// HTTP 客户端，覆盖哪个模型由本机决定。对话线路的模型由引擎从渠道行读，
    /// 所以对话改模型写的是**渠道行本身**（见 `FeatureRouteView`）。
    var modelKey: String { "feature_route.\(rawValue).model" }

    var title: String {
        switch self {
        case .chat: "对话"
        case .image: "生图"
        case .tts: "朗读"
        case .embedding: "向量嵌入"
        }
    }

    var detail: String {
        switch self {
        case .chat: "角色回复用哪个渠道。与「当前启用渠道」始终是同一条。"
        case .image: "聊天里触发生图时用的渠道与模型。"
        case .tts: "朗读消息时用的语音渠道与模型。"
        case .embedding: "记忆语义检索用的向量模型渠道。"
        }
    }

    /// 谁读这条线路 —— 决定「配了之后到底谁生效」。
    ///
    /// 对话由引擎（Rust）读，其余三条由本机读。这四个字直接印在界面上，
    /// 免得用户以为「配了就是本机在用」。
    var reader: String {
        switch self {
        case .chat: "推理引擎"
        case .image, .tts, .embedding: "本机"
        }
    }
}

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

            return Self.mapConfigRow(row)
        }
    }

    /// 一行配置的列映射 —— 三处查询（当前启用 / 全部 / 按 id）共用一份，
    /// 不再各写一遍（「同一份契约两处各自维护」是本仓记录过的教训）。
    private static func mapConfigRow(_ row: Row) -> ApiConfig {
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
            return rows.map { row in Self.mapConfigRow(row) }
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

            // ③ ⚠️ 第 203 轮：对话线路与「当前启用渠道」是**同一件事**，必须同步写。
            //    引擎读 `app_meta.feature_route.chat`；旧版引擎读 `isEnabled = 1`。
            //    两者指向同一行时，换不换引擎行为都一样 —— 这是刻意维持的不变式：
            //    **存在启用行时，`feature_route.chat` 必须指向它。**
            //    不写的话会出现最糟的一类分歧：界面按线路显示 A，引擎按 isEnabled 用 B。
            try Self.writeRouteMeta(db, purpose: .chat, configId: id)
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
            let deleted = db.changesCount > 0
            // 第 203 轮：被删的行若还是某条线路的绑定，绑定必须一并清掉。
            // 留着的话界面会显示「线路指向一条已经不存在的渠道」，
            // 而引擎那边早已回退到 `isEnabled = 1` —— 又是一次界面与行为分歧。
            if deleted {
                try Self.clearRouteMeta(db, configId: id)
            }
            return deleted
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
        // 第 203 轮：删掉这里一行的 `let now = …`（原先就没被使用，
        // 编译器一直在报 "initialization of immutable value 'now' was never used"）。
        // `api_configs` 表没有 createdAt/updatedAt 列，所以它本来也无处可写。
        return try database.pool.write { db -> Int64 in
            // 与 Android 语义一致：同一时刻只有一条启用配置（ORDER BY id DESC LIMIT 1 取最新）
            try db.execute(sql: "UPDATE api_configs SET isEnabled = 0 WHERE isEnabled = 1")

            let rowId: Int64
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
                rowId = existingId
            } else {
                try db.execute(sql: """
                    INSERT INTO api_configs
                      (provider, name, apiKey, extraApiKeys, baseUrl, model, temperature,
                       maxTokens, isEnabled, connectionTested, connectionTestedAt, latencyMs, formatHint)
                    VALUES (?, ?, '', '', ?, ?, ?, ?, 1, 0, 0, 0, ?)
                    """, arguments: [
                        provider, name ?? provider, baseUrl, model, temperature, maxTokens, formatHint,
                    ])
                rowId = db.lastInsertedRowID
            }

            // 保存一条渠道 = 启用它 = 对话线路指向它（同一个不变式，见 `activate`）。
            try Self.writeRouteMeta(db, purpose: .chat, configId: rowId)
            return rowId
        }
    }

    // MARK: - 服务商预设（读库）

    /// 库里**可见**的服务商预设，按 `sortOrder` 升序。
    ///
    /// ## 为什么需要（第 202 轮）
    /// 界面一直用硬编码的 `YuNianSeed.apiProviderPresets`，
    /// 于是 `api_provider_presets.isVisible` 这一列**从来没有被读过** ——
    /// 也就是说"某个服务商在界面上不该出现"这件事**无法表达**。
    ///
    /// 两者关系：种子只是**建库时的初始内容**（`seedApiProviderPresets`），
    /// 建完之后库才是运行时真源。读种子等于冻结在初始状态。
    func visiblePresets() throws -> [YuNianSeed.ApiProviderPreset] {
        try database.pool.read { db in
            let rows = try Row.fetchAll(db, sql: """
                SELECT provider, displayName, baseUrl, model, formatHint, sortOrder
                FROM api_provider_presets
                WHERE isVisible = 1
                ORDER BY sortOrder ASC
                """)
            return rows.map { row in
                YuNianSeed.ApiProviderPreset(
                    provider: row["provider"] as String? ?? "",
                    displayName: row["displayName"] as String? ?? "",
                    baseUrl: row["baseUrl"] as String? ?? "",
                    model: row["model"] as String? ?? "",
                    formatHint: row["formatHint"] as String? ?? "",
                    sortOrder: row["sortOrder"] as Int? ?? 0
                )
            }
        }
    }

    // MARK: - 用途线路（多线路，P0 ②）

    /// 写入「用途 → 渠道 id」。**调用方必须已经在写事务里**。
    private static func writeRouteMeta(
        _ db: Database, purpose: FeaturePurpose, configId: Int64
    ) throws {
        try db.execute(sql: """
            INSERT OR REPLACE INTO app_meta (key, value, updatedAt) VALUES (?, ?, ?)
            """, arguments: [
                purpose.metaKey, String(configId),
                Int64(Date().timeIntervalSince1970 * 1000),
            ])
    }

    /// 清掉指向某条渠道的全部线路绑定。
    ///
    /// 用 `GLOB` 精确锁定「用途键」这一层，把 `feature_route.tts.model`
    /// 这类**覆盖键**排除在外 —— 它的值是模型名而不是渠道 id，
    /// 若某个模型名恰好等于被删的 id，按值匹配就会把它一起删掉。
    private static func clearRouteMeta(_ db: Database, configId: Int64) throws {
        try db.execute(sql: """
            DELETE FROM app_meta
            WHERE value = ?
              AND key GLOB 'feature_route.*'
              AND key NOT GLOB 'feature_route.*.*'
            """, arguments: [String(configId)])
    }

    /// 读 `app_meta` 的一个键（不存在 → nil）。
    func metaValue(forKey key: String) throws -> String? {
        try database.pool.read { db in
            try String.fetchOne(
                db,
                sql: "SELECT value FROM app_meta WHERE key = ?",
                arguments: [key]
            )
        }
    }

    /// 写/删 `app_meta` 的一个键。`nil` 或空串 → **删除**该键。
    ///
    /// 删除而不是写空串：留着空值的话，库里看不出"到底配没配过"，
    /// 而引擎那边空串也会解析失败、当成没绑定 —— 两边对不上时排查会很贵。
    func setMetaValue(_ value: String?, forKey key: String) throws {
        let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines)
        try database.pool.write { db in
            guard let trimmed, !trimmed.isEmpty else {
                try db.execute(sql: "DELETE FROM app_meta WHERE key = ?", arguments: [key])
                return
            }
            try db.execute(sql: """
                INSERT OR REPLACE INTO app_meta (key, value, updatedAt) VALUES (?, ?, ?)
                """, arguments: [
                    key, trimmed, Int64(Date().timeIntervalSince1970 * 1000),
                ])
        }
    }

    /// 读某用途绑定的渠道 id（无绑定 / 值不是数字 → nil）。
    ///
    /// 与 Rust `route_config_id` 同构：**取不到、值非法、读库失败都算"没绑定"**，
    /// 交给调用方回退到「当前启用渠道」。把"没配过"当成错误，
    /// 会让每一条没配过的线路都报一次错。
    func routeConfigId(purpose: FeaturePurpose) throws -> Int64? {
        guard let raw = try metaValue(forKey: purpose.metaKey) else { return nil }
        return Int64(raw.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    /// 按 id 读一行（**不要求 `isEnabled`**）。
    ///
    /// 用途绑定是显式指定：一条只为朗读而建的渠道不该被迫先成为
    /// 「当前启用渠道」——那会把对话一起换过去。
    func config(id: Int64) throws -> ApiConfig? {
        try database.pool.read { db in
            guard let row = try Row.fetchOne(db, sql: """
                SELECT id, provider, name, baseUrl, model, formatHint, isEnabled
                FROM api_configs WHERE id = ?
                """, arguments: [id]) else { return nil }
            return Self.mapConfigRow(row)
        }
    }

    /// 把某用途绑定到一条渠道。
    ///
    /// - 对话：同时把它设为**当前启用**。这不是多余动作 —— 引擎读
    ///   `feature_route.chat`，而旧版引擎读 `isEnabled = 1`，
    ///   两者必须指向同一行（见 `activate` 里的不变式）。
    /// - 其余三条：只写 `app_meta`。它们由本机的 HTTP 客户端消费，
    ///   不该抢占「当前启用渠道」。
    ///
    /// - Throws: `RouteError.configNotFound` —— id 不存在时**什么都不写**。
    func bind(purpose: FeaturePurpose, configId: Int64) throws {
        let existing = try config(id: configId)
        guard existing != nil else { throw RouteError.configNotFound }
        if purpose == .chat {
            // activate 内部已经写了 feature_route.chat，不要重复写
            guard try activate(id: configId) else { throw RouteError.configNotFound }
            return
        }
        try setMetaValue(String(configId), forKey: purpose.metaKey)
    }

    /// 解除绑定：该用途回退到「当前启用渠道」。
    ///
    /// 模型覆盖一并清掉 —— 留着的话，下次绑到别的渠道会"莫名用了旧模型"。
    func unbind(purpose: FeaturePurpose) throws {
        try setMetaValue(nil, forKey: purpose.metaKey)
        try setMetaValue(nil, forKey: purpose.modelKey)
    }

    /// 改某条渠道的模型名。
    ///
    /// 对话线路的模型存在**渠道行**里（引擎从行里读），所以界面上改
    /// 对话模型就是改这一行；其余三条线路的模型覆盖存在 `app_meta`，
    /// 由本机的 HTTP 客户端消费。
    ///
    /// - Returns: 是否真的改到了行（id 不存在时 false）。
    @discardableResult
    func updateModel(id: Int64, model: String) throws -> Bool {
        try database.pool.write { db in
            try db.execute(
                sql: "UPDATE api_configs SET model = ? WHERE id = ?",
                arguments: [model, id]
            )
            return db.changesCount > 0
        }
    }

    /// 解析某用途**实际生效**的渠道与模型。
    ///
    /// 这是界面**唯一**该用来回答「这条线路现在是什么」的方法。
    /// 界面自己另算一套的话，就会出现最难查的一类分歧：
    /// 「UI 说没配，引擎却照样在跑」。
    func resolve(purpose: FeaturePurpose) throws -> FeatureRouteResolution? {
        let boundId = try routeConfigId(purpose: purpose)
        var row: ApiConfig?
        if let boundId {
            row = try config(id: boundId)
        }
        let isBound = row != nil
        if row == nil { row = try activeConfig() }
        guard let row else { return nil }

        let override = try metaValue(forKey: purpose.modelKey)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let hasOverride = (override?.isEmpty == false)

        return FeatureRouteResolution(
            purpose: purpose,
            configId: row.id,
            provider: row.provider,
            channelName: row.name,
            baseUrl: row.baseUrl,
            model: hasOverride ? (override ?? "") : row.model,
            formatHint: row.formatHint,
            isBound: isBound,
            isModelOverridden: hasOverride
        )
    }
}

/// 线路绑定的失败原因。
enum RouteError: Error, Equatable {
    /// 要绑定的渠道 id 在库里不存在 —— 此时**什么都不写**。
    case configNotFound
}

/// 一条线路的解析结果：渠道 + **实际生效**的模型。
struct FeatureRouteResolution: Sendable, Equatable {
    var purpose: FeaturePurpose
    var configId: Int64
    var provider: String
    /// `api_configs.name` —— 用户可自定义（用于区分「号1 / 号2」）。
    var channelName: String
    var baseUrl: String
    /// 实际生效的模型：线路覆盖优先，其次渠道自身的模型。
    var model: String
    var formatHint: String
    /// true = 用户显式绑定了渠道；false = 回退到「当前启用渠道」。
    var isBound: Bool
    /// 模型是否来自该线路的覆盖值。
    var isModelOverridden: Bool
}
