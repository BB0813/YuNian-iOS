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
        static let apiKey = "api_key"
        /// PARTNER 会话令牌（Rust 注入为 `X-LianYu-Session` 头）
        static let partnerToken = "partner_auth_token"
        /// PARTNER 客户端 id（Rust 注入为 `X-LianYu-Client-Id` 头，并作为签名回调入参）
        static let partnerClientId = "partner_client_id"
        /// PARTNER 会话密钥（Android 侧 `PartnerSession.sessionKey`，本地留存）
        static let partnerSessionKey = "partner_session_key"
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
