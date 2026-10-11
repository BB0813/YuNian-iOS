import Foundation
import Security

/// Keychain 封装 —— 替代 Android 侧的 `EncryptedSharedPreferences`
/// （`core/security/.../DatabaseKeyProvider.getSecurePrefs`、
/// `core/common/.../RemoteKeyProvider` 的 `suflow_session_store_encrypted`）。
///
/// ⚠️ **密文不可跨端迁移**：AndroidX Security 的存储格式是私有的，
/// 其 AES 主密钥位于 AndroidKeyStore 且不可导出。因此 iOS 读不到 Android 的
/// 会话令牌与 API Key，反之亦然。跨端迁移必须走逻辑导出（见 §4.4 / §9 V8）。
///
/// 可访问性统一用 `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`：
///   - AfterFirstUnlock：后台主动消息（APNs 唤醒 / BGTask）在锁屏时也能读；
///   - ThisDeviceOnly：不随 iCloud 钥匙串同步到其他设备，与 Android 的
///     `setUserAuthenticationRequired(false)` + 设备绑定语义对齐。
enum KeychainStore {

    enum KeychainError: Error {
        case unexpectedStatus(OSStatus)
        case dataCorrupted
    }

    private static let service = "com.yunian.ai"

    /// 读取字符串；不存在返回 nil。
    static func string(for key: String) -> String? {
        guard let data = data(for: key) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// 按配置解析 API Key —— **全仓唯一实现**（第 186 轮）。
    ///
    /// ## 解析顺序
    /// 1. `api_key_<configId>` —— 按配置存放（第 186 轮起）
    /// 2. `api_key` —— 旧单槽，**迁移回退**（第 186 轮前装的用户只有它）
    ///
    /// ## 为什么放在存储层而不是 AppEnvironment
    /// 读取方横跨 App 层（`AppEnvironment`）与 Agent 层（`ApiProbeService`），
    /// 后者不该依赖前者。规则写在存储层，两边都用同一份 ——
    /// 否则就是第 8 轮记录的教训："同一个契约两处各自维护就会互相掩盖"。
    ///
    /// ⚠️ 决策部分抽到 `pickAPIKey` 是为了**可测**：
    /// 仓内此前没有任何测试碰过 Keychain，说明测试宿主里它不一定可用
    /// （entitlement 缺失会返回 errSecMissingEntitlement）。
    /// 把"选哪个"与"从哪读"分开，规则就能在无 Keychain 的环境下验证。
    static func resolvedAPIKey(configId: Int64?) -> String {
        let perConfig = configId.flatMap { string(for: Key.apiKeyFor($0)) }
        return pickAPIKey(perConfig: perConfig,
                          legacy: string(for: Key.apiKey))
    }

    /// 纯逻辑：两个候选里选哪个。**不碰 Keychain，可直接单测。**
    ///
    /// 规则：按配置的槽优先；它为空（没有或空串）时回退旧单槽。
    /// 两者都没有则返回空串（调用方据此判"未配置"）。
    static func pickAPIKey(perConfig: String?, legacy: String?) -> String {
        if let perConfig, !perConfig.isEmpty { return perConfig }
        return legacy ?? ""
    }

    /// 写入字符串（存在则覆盖）。
    static func set(_ value: String, for key: String) throws {
        try set(Data(value.utf8), for: key)
    }

    static func data(for key: String) -> Data? {
        var query = baseQuery(for: key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return data
    }

    static func set(_ data: Data, for key: String) throws {
        let query = baseQuery(for: key)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]

        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            throw KeychainError.unexpectedStatus(updateStatus)
        }

        var insert = query
        insert.merge(attributes) { _, new in new }
        let addStatus = SecItemAdd(insert as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw KeychainError.unexpectedStatus(addStatus)
        }
    }

    /// 删除；不存在视为成功（幂等）。
    @discardableResult
    static func remove(_ key: String) -> Bool {
        let status = SecItemDelete(baseQuery(for: key) as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }

    /// 列举本 service 下的全部键（诊断面板用，不返回值内容）。
    static func allKeys() -> [String] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
        ]
        var items: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &items) == errSecSuccess,
              let list = items as? [[String: Any]] else { return [] }
        return list.compactMap { $0[kSecAttrAccount as String] as? String }.sorted()
    }

    // MARK: - 已知键名（与 Android 侧语义对应）

    enum Key {
        /// API Key（Android 侧为 `api_configs.apiKey`，native VMP/Tink 加密）
        ///
        /// ⚠️ 第 186 轮：这已**不是**主存放位，改为「按配置一行一个槽」。
        ///
        /// 原因：这个单槽让「多配置」名存实亡 ——
        /// 第 182 轮做的多渠道切换能换 provider/baseUrl/model，
        /// 但 **key 换不了**（所有配置共用一个槽，谁最后保存是谁的）。
        /// 角色级 API 隔离（Android `AiService.kt:177-186`）也就无从谈起。
        ///
        /// 现在：新写入走 `apiKeyFor(configId)`；
        /// 这个旧槽只在**按配置读不到时**作迁移回退，
        /// 保证升级上来的用户不丢已有 key。
        static let apiKey = "api_key"

        /// 按配置存放 API Key 的键名前缀。
        static let apiKeyPrefix = "api_key_"

        /// 某条 `api_configs` 行的 API Key 槽名。
        ///
        /// 用 `id` 而不是 `provider` 作后缀：同一个 provider 可以有多行
        /// （不同 baseUrl / 不同 key），用 provider 会互相覆盖。
        static func apiKeyFor(_ configId: Int64) -> String {
            "\(apiKeyPrefix)\(configId)"
        }

        /// 某条配置的**额外** API Key（多密钥轮换）槽名。
        ///
        /// ## 为什么需要（第 202 轮）
        /// Rust 的 `all_api_keys()` 会把凭证里的 `extra_api_keys`
        /// **按逗号 split 并 trim** 后一起纳入轮换/重试 ——
        /// `AgentCredentials.extraApiKeys` 这个字段也一直在契约里。
        ///
        /// 但 Swift 侧 `pushCredentials()` 只下发了主 key，
        /// 这个字段**永远是 nil**，于是「多密钥」在 iOS 上名存实亡：
        /// 契约有、Rust 支持、宿主不发。
        ///
        /// 槽位与 `apiKeyFor` 同一套规则（按 configId，不按 provider），
        /// 理由相同：同一个 provider 可以有多行。
        static func extraApiKeysFor(_ configId: Int64) -> String {
            "extra_api_keys_\(configId)"
        }
        // ── PARTNER 会话：iOS 不接入，但这两槽保留（第 193 轮）──────────
        //
        // 它们的**写入路径已删**（原 `AppEnvironment.setPartnerSession`），
        // 所以 iOS 上永远是 nil。保留是因为它们仍被读：
        //   · `AgentCredentials.fromKeychain` —— 装配给 Rust 的凭证 JSON
        //   · `ApiProbeService.authHeaders`   —— 探针的 PARTNER 分支
        // 删掉就要改 Swift↔Rust 的凭证契约，而那份契约与 Android 共用
        // （`agent-native/src/` 是两端共享源码，Android 的
        //  `liblianyu_agent.so` 也从它编出）。
        //
        // ⚠️ 原有一个 `partnerSessionKey = "partner_session_key"`
        // （注释写「Android 侧 `PartnerSession.sessionKey`，本地留存」），
        // **全仓声明后从未被读写**，本轮删除。Android 有那个字段这条信息
        // 记在这里，不随常量一起丢。

        /// PARTNER 会话令牌（Rust 注入为 `X-LianYu-Session` 头）
        static let partnerToken = "partner_auth_token"
        /// PARTNER 客户端 id（Rust 注入为 `X-LianYu-Client-Id` 头，并作为签名回调入参）
        static let partnerClientId = "partner_client_id"
        /// 数据库口令（Android 侧 `lianyu_db_secure_prefs`）
        static let databasePassphrase = "db_passphrase"
        /// 审计链 nonce（Android 侧 `lianyu_audit_chain_prefs`，用于防回滚）
        static let auditChainHead = "audit_chain_head"
        /// 用户昵称，供编排器渲染 `owner_name`（对应 settings 的 owner_name 键）
        static let ownerName = "owner_name"
    }

    private static func baseQuery(for key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
    }
}
