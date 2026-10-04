import Foundation
import CryptoKit
import CommonCrypto

/// `.lybk` 备份容器解密 —— 对应 Android `BackupViewModel.Crypto`。
///
/// ## 容器布局（`BackupViewModel.kt:131-169`）
/// ```
/// 偏移  长度  内容
/// 0     4     MAGIC = "LYBK"（UTF-8）
/// 4     16     salt（SecureRandom）
/// 20    12     AES-GCM IV
/// 32    rest   ciphertext || tag(16)     ← Java doFinal 把 tag 接在后面
/// ```
/// 密钥派生：`PBKDF2WithHmacSHA256`，100,000 次迭代，256 位。
///
/// ## 密码编码（已实测确认，无歧义）
/// Android 用 `PBEKeySpec(password.toCharArray(), ...)`。
/// 曾担心非 ASCII 密码下字节化方式不明，**第 77 轮用本机 JDK 21 实测**：
/// `PBKDF2WithHmacSHA256` 按 **UTF-8** 编码密码，
/// 与 `密码pass1😀` 等含中日文与 emoji 的密码均验证一致
/// （Java 派生密钥 `28b63441...` == `pbkdf2_hmac("sha256", pw.utf8, ...)`）。
/// 因此本实现直接用 `password.utf8` 是正确的。
enum BackupCrypto {

    private static let magic = Array("LYBK".utf8)
    private static let saltLength = 16
    private static let ivLength = 12
    private static let tagLength = 16
    private static let pbkdf2Iterations: UInt32 = 100_000
    private static let keyLength = 32          // 256 位

    struct Container {
        let salt: Data
        let iv: Data
        let ciphertext: Data
        let tag: Data

        /// Java `doFinal` 的输入是 ciphertext 与 tag 已拼接的形式。
        var sealedBox: Data { ciphertext + tag }
    }

    enum CryptoError: Error, CustomStringConvertible {
        case tooShort(Int)
        case badMagic([UInt8])
        case keyDerivationFailed
        case decryptionFailed

        var description: String {
            switch self {
            case let .tooShort(n):
                return "备份文件过短（\(n) 字节，至少需要 \(4 + 16 + 12 + 16)）"
            case let .badMagic(m):
                return "文件格式不正确（magic 为 \([UInt8](m)，非 LYBK）"
            case .keyDerivationFailed:
                return "PBKDF2 密钥派生失败"
            case .decryptionFailed:
                return "解密失败：密码错误或文件已损坏"
            }
        }
    }

    /// 解析容器。对应 Android 侧 salt / iv / ciphertext 的切分。
    static func parse(_ data: Data) throws -> Container {
        let header = magic.count + saltLength + ivLength
        guard data.count >= header + tagLength else {
            throw CryptoError.tooShort(data.count)
        }
        let bytes = [UInt8](data)
        guard Array(bytes[0..<magic.count]) == magic else {
            throw CryptoError.badMagic(Array(bytes[0..<magic.count]))
        }

        var offset = magic.count
        let salt = Data(bytes[offset..<offset + saltLength]); offset += saltLength
        let iv = Data(bytes[offset..<offset + ivLength]); offset += ivLength
        let rest = bytes[offset...]
        let body = Array(rest)
        guard body.count >= tagLength else { throw CryptoError.tooShort(data.count) }

        let ciphertext = Data(body[0..<(body.count - tagLength)])
        let tag = Data(body[(body.count - tagLength)...])
        return Container(salt: salt, iv: iv, ciphertext: ciphertext, tag: tag)
    }

    /// PBKDF2-HMAC-SHA256。
    ///
    /// ⚠️ 走了 `CommonCrypto` 而非 CryptoKit —— 后者未暴露 PBKDF2。
    /// 本函数**无法在本会话中编译验证**（需 Xcode），
    /// 但有确定性夹具断言其输出与 Android 侧参数一致。
    static func deriveKey(password: String, salt: Data) throws -> SymmetricKey {
        let passwordBytes = Array(password.utf8)
        var derived = [UInt8](repeating: 0, count: keyLength)

        let status = CCKeyDerivationPBKDF2(
            CCPBKDFAlgorithm(kCCPBKDF2),
            String(decoding: passwordBytes, as: UTF8.self),   // C 字符串
            passwordBytes.count,
            Array(salt),
            salt.count,
            CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256),
            UInt(pbkdf2Iterations),
            &derived,
            derived.count
        )

        guard status == kCCSuccess else {
            throw CryptoError.keyDerivationFailed
        }
        return SymmetricKey(data: derived)
    }

    /// 解密整个容器，返回 `BackupData` 明文 JSON。
    static func decrypt(_ data: Data, password: String) throws -> Data {
        let container = try parse(data)
        let key = try deriveKey(password: password, salt: container.salt)

        let sealed: AES.GCM.SealedBox
        do {
            sealed = try AES.GCM.SealedBox(
                nonce: try AES.GCM.Nonce(data: container.iv),
                ciphertext: container.ciphertext,
                tag: container.tag
            )
            return try AES.GCM.open(sealed, using: key)
        } catch {
            throw CryptoError.decryptionFailed
        }
    }

    /// 便捷入口：直接得到 JSON 字符串。
    static func decryptToString(_ data: Data, password: String) throws -> String {
        let plain = try decrypt(data, password: password)
        guard let s = String(data: plain, encoding: .utf8) else {
            throw CryptoError.decryptionFailed
        }
        return s
    }
}
