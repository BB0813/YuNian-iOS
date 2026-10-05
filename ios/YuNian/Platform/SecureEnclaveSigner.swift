import Foundation
import CryptoKit
import Security

/// P-256 设备签名密钥 —— 替代 Android 侧的 AndroidKeyStore 实现
/// （`core/common/.../security/DeviceRequestSigner.kt`）。
///
/// ## 为什么能对得上
/// Android 侧用 `Signature.getInstance("SHA256withECDSA")`，输出 DER 编码的
/// ECDSA 签名再 Base64（NO_WRAP）。iOS 的
/// `SecKeyCreateSignature(_, .ecdsaSignatureMessageX962SHA256, _)` 产出**同样的
/// DER(X9.62) 编码**，因此签名本身逐字节等价。
///
/// ## keyId 的坑（最容易做错的一处）
/// Android 侧 `keyId = sha256Hex(publicKey.encoded).take(32)`，其中
/// `PublicKey.getEncoded()` 返回的是 **X.509 SubjectPublicKeyInfo (SPKI) DER**，
/// 不是裸公钥点。服务端按此校验（`_server_update/server.mjs`：
/// `sha256Hex(der).slice(0,32) !== keyId`）。
///
/// 而 iOS 的 `SecKeyCopyExternalRepresentation` 对 EC 公钥返回的是
/// **裸点** `0x04 || X(32) || Y(32)`（65 字节）。所以必须手工包成 SPKI DER，
/// 否则 keyId 与服务端不一致 —— 表现为「签名校验失败」且极难定位。
/// 详见本文件 `spkiDER(from:)`。
///
/// ## 密钥存储
/// 优先 Secure Enclave（硬件，私钥不可导出），与 Android 的 StrongBox/TEE 对应；
/// 模拟器无 SE，回退到 Keychain 的软件 P-256 密钥，以便 CI 能跑到这条代码路径。
enum SecureEnclaveSigner {

    enum SignerError: Error {
        case keyUnavailable
        case publicKeyUnavailable
        case representationUnavailable
        case signFailed(String)
    }

    /// Android 侧别名 `lianyu_device_signing_p256_v1`；iOS 用同一语义名便于排查。
    private static let keyTag = "com.yunian.ai.deviceSigningP256.v1"
    private static let keySize = 256

    /// P-256 SPKI DER 的固定前缀（26 字节），其后接 65 字节裸点。
    ///
    /// 30 59                                     SEQUENCE, len 89
    ///   30 13                                   SEQUENCE, len 19
    ///     06 07 2A 86 48 CE 3D 02 01            OID 1.2.840.10045.2.1  (ecPublicKey)
    ///     06 08 2A 86 48 CE 3D 03 01 07        OID 1.2.840.10045.3.1.7 (prime256v1)
    ///   03 42                                   BIT STRING, len 66
    ///     00                                    unused bits
    ///     04 || X(32) || Y(32)                  未压缩点
    private static let spkiPrefix: [UInt8] = [
        0x30, 0x59,
        0x30, 0x13,
        0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01,
        0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07,
        0x03, 0x42, 0x00,
    ]

    // MARK: - 对外

    /// `sha256(SPKI DER).hex[:32]`，与 Android 侧及服务端校验一致。
    static func keyId() throws -> String {
        let spki = try spkiDER()
        return String(DeviceIdentity.sha256Hex(spki).prefix(32))
    }

    /// Base64(SPKI DER)，对应 Android 的 `publicKeyBase64()`
    /// （更新通道的 `X-LianYu-Pub` 头用这个）。
    static func publicKeyBase64() throws -> String {
        try spkiDER().base64EncodedString()
    }

    /// 签名结果，字段与 Android 的 `SignedRequest` 对齐。
    struct SignedRequest {
        let signature: String   // Base64(DER)，NO_WRAP
        let keyId: String
        let deviceId: String
    }

    /// 对 payload 做 SHA256withECDSA，返回 Base64(DER) 与 keyId/deviceId。
    static func sign(_ payload: Data) throws -> SignedRequest {
        let key = try privateKey()
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(
            key,
            .ecdsaSignatureMessageX962SHA256,
            payload as CFData,
            &error
        ) as Data? else {
            let message = (error?.takeRetainedValue() as Error?)?.localizedDescription ?? "unknown"
            throw SignerError.signFailed(message)
        }

        return SignedRequest(
            signature: signature.base64EncodedString(),
            keyId: try keyId(),
            deviceId: DeviceIdentity.deviceId
        )
    }

    // MARK: - SPKI 构造

    /// 取公钥并包成 X.509 SPKI DER。
    static func spkiDER() throws -> Data {
        let publicKey = try publicKey()
        guard let raw = SecKeyCopyExternalRepresentation(publicKey, nil) as Data? else {
            throw SignerError.representationUnavailable
        }
        return spkiDER(fromRawPoint: raw)
    }

    /// 裸点（65 字节，0x04 开头）→ SPKI DER（91 字节）。
    /// 已经带 SPKI 结构时原样返回，避免重复包装。
    static func spkiDER(fromRawPoint raw: Data) -> Data {
        if raw.count == spkiPrefix.count + 65, raw.starts(with: spkiPrefix) {
            return raw
        }
        precondition(
            raw.count == 65 && raw.first == 0x04,
            "P-256 裸公钥点应为 65 字节且以 0x04 开头，实际 \(raw.count) 字节"
        )
        var out = Data(spkiPrefix)
        out.append(raw)
        return out
    }

    // MARK: - 密钥生命周期

    private static let cachedKey: NSLock = NSLock()
    nonisolated(unsafe) private static var keyCache: SecKey?

    static func privateKey() throws -> SecKey {
        cachedKey.lock()
        defer { cachedKey.unlock() }
        if let keyCache { return keyCache }

        if let existing = try? loadKey() {
            keyCache = existing
            return existing
        }
        let created = try createKey()
        keyCache = created
        return created
    }

    static func publicKey() throws -> SecKey {
        let key = try privateKey()
        // ⚠️ 第 88 轮：`SecKeyCopyPublicKey` 在这个 SDK 上返回**非可选** SecKey，
        // 所以 `guard let public = ... else` 报
        //   expected pattern / unwrap condition requires a valid identifier /
        //   expected 'else' after 'guard' condition ...
        // 一条 guard let 引出一串语法级报错。
        return SecKeyCopyPublicKey(key)
    }

    /// 测试与「重置设备身份」用。
    static func reset() throws {
        cachedKey.lock()
        defer { cachedKey.unlock() }
        keyCache = nil
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: Data(keyTag.utf8),
        ]
        SecItemDelete(query as CFDictionary)
    }

    // MARK: - 内部

    private static func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: Data(keyTag.utf8),
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
        ]
    }

    private static func loadKey() throws -> SecKey {
        var query = baseQuery()
        query[kSecReturnRef as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let key = item else {
            throw SignerError.keyUnavailable
        }
        // swiftlint:disable:next force_cast
        return key as! SecKey
    }

    private static func createKey() throws -> SecKey {
        // 与 Android 侧 setUserAuthenticationRequired(false) 对应：
        // 后台主动消息需要在设备锁定时也能签名，因此用 AfterFirstUnlock。
        let access = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            [.privateKeyUsage],
            nil
        )

        var attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: keySize,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: Data(keyTag.utf8),
            ],
        ]
        if let access {
            attributes[kSecAttrTokenID as String] = kSecAttrTokenIDSecureEnclave
            var privateAttrs = attributes[kSecPrivateKeyAttrs as String] as! [String: Any]
            privateAttrs[kSecAttrAccessControl as String] = access
            attributes[kSecPrivateKeyAttrs as String] = privateAttrs
        }

        var error: Unmanaged<CFError>?
        if let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) {
            return key
        }

        // 模拟器 / 无 SE 设备：退回纯软件 P-256（同样不可导出）
        if attributes[kSecAttrTokenID as String] != nil {
            attributes.removeValue(forKey: kSecAttrTokenID as String)
            error = nil
            if let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) {
                return key
            }
        }

        let message = (error?.takeRetainedValue() as Error?)?.localizedDescription ?? "unknown"
        throw SignerError.signFailed("SecKeyCreateRandomKey 失败：\(message)")
    }
}

extension SecureEnclaveSigner {
    /// 本机是否真的用上了 Secure Enclave（供诊断面板显示）。
    static var isHardwareBacked: Bool {
        guard let key = try? privateKey(),
              let attributes = SecKeyCopyAttributes(key) as? [String: Any],
              let tokenID = attributes[kSecAttrTokenID as String] as? String
        else { return false }
        return tokenID == (kSecAttrTokenIDSecureEnclave as String)
    }
}
