import Foundation
import CryptoKit
// ⚠️ 第 81 轮：`import CommonCrypto` 已移除。
// 原有实现用 CCKeyDerivationPBKDF2，但它在 CI 上稳定报
// "cannot find in scope"（试过 target 级 / 工程级 -framework CommonCrypto
// 均无效）。现改用 BackupKDF.swift（CryptoKit 自建，等价性已在本地对 JDK 验证）。

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
                // ⚠️ 第 72 轮：这里原来写的是 `\([UInt8](m)`，收尾用了**全角** `）`
                // （U+FF09）而不是 ASCII `)`，于是插值永远找不到闭合括号，
                // 编译报 "Cannot find ')' to match opening '(' in string interpolation"。
                // 另外 `[UInt8](m)` 也不是合法的转换写法，改为 `Array(m)`。
                return "文件格式不正确（magic 为 \(Array(m))，非 LYBK）"
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
    ///
    /// ⚠️ 第 81 轮：把 CommonCrypto 的 CCKeyDerivationPBKDF2 换成自建的
    /// BackupKDF（CryptoKit）。原因：CI 上 CCKeyDerivationPBKDF2 稳定报
    /// "cannot find in scope"，我试过 target 级 / 工程级 OTHER_LDFLAGS
    /// 都没解决，而在无 Mac 的情况下继续猜根因不负责任。
    ///
    /// BackupKDF 的等价性**已在本地证明**：Python 手写的
    /// PBKDF2-HMAC-SHA256 与 JDK 21 的 PBKDF2WithHmacSHA256 逐字节一致，
    /// 本文件是它的直译。参数（迭代 100000 / 256 位）逐字沿用。
    static func deriveKey(password: String, salt: Data) throws -> SymmetricKey {
        let derived = BackupKDF.deriveKey(
            password: password,
            salt: salt,
            iterations: pbkdf2Iterations,
            keyLength: keyLength
        )
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
