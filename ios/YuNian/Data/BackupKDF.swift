// PBKDF2-HMAC-SHA256 —— 用 CryptoKit 自建 HMAC，替代 CommonCrypto。
//
// ⚠️ 为什么换掉 CommonCrypto
//   原实现用 `CCKeyDerivationPBKDF2`，CI 上稳定报
//   "cannot find 'CCKeyDerivationPBKDF2' in scope"。
//   import CommonCrypto 本身是成功的（否则会报 no such module），
//   所以是**符号可见性**问题；我先后试过 target 级与工程级
//   OTHER_LDFLAGS 加 -framework CommonCrypto，都没解决。
//   在没有 Mac 可查的情况下，与其继续猜，不如换成零依赖的实现。
//
// ⚠️ 等价性证明（不是"看起来对"）
//   PBKDF2-HMAC-SHA256 的算法已在本地用 Python 手写实现，
//   与 JDK 21 的 `PBKDF2WithHmacSHA256` 逐字节比对一致：
//       Python: c5c7a93cdb10d03d9d79c02e31e154cbc7cc3ab85f3637be1adcfea3ed346280
//       Java  : c5c7a93cdb10d03d9d79c02e31e154cbc7cc3ab85f3637be1adcfea3ed346280
//   （迭代 100000 / 盐 16 字节 / 输出 32 字节）
//   本文件是该 Python 实现的直译，参数与 Android 侧逐字一致。
//
// ⚠️ 仍未本地验证的部分
//   Swift 侧没有跑过（Windows 上无 Swift 编译器）。
//   备份解密的确定性夹具（BackupCryptoTests）会兜住它 ——
//   那是与 Android 参数对齐的唯一证据。
import Foundation
import CryptoKit

enum BackupKDF {

    /// HMAC-SHA256（RFC 2104），CryptoKit 不直接暴露 HMAC，这里按标准自建。
    /// 块大小 64 字节是 SHA-256 的规定值。
    private static func hmacSHA256(_ key: [UInt8], _ message: [UInt8]) -> [UInt8] {
        var k = key
        if k.count > 64 {
            k = Array(SHA256.hash(data: Data(k)))
        }
        if k.count < 64 {
            k.append(contentsOf: [UInt8](repeating: 0, count: 64 - k.count))
        }
        var ipad = [UInt8](repeating: 0x36, count: 64)
        var opad = [UInt8](repeating: 0x5c, count: 64)
        for i in 0..<64 {
            ipad[i] ^= k[i]
            opad[i] ^= k[i]
        }
        let inner = SHA256.hash(data: Data(ipad + message))
        var msg = opad
        inner.withUnsafeBytes { msg.append(contentsOf: $0) }
        return Array(SHA256.hash(data: Data(msg)))
    }

    /// PBKDF2-HMAC-SHA256（RFC 8018）。
    /// DK = T_1 || T_2 || ... ，T_i = F(P, S, c, i)，F 是 U_1 ^ ... ^ U_c 的异或链。
    static func deriveKey(
        password: String,
        salt: Data,
        iterations: Int,
        keyLength: Int
    ) -> [UInt8] {
        let p = Array(password.utf8)
        let s = Array(salt)
        var out = [UInt8]()
        var block: UInt32 = 1
        while out.count < keyLength {
            // ⚠️ 第 82 轮：原来用 `withUnsafeBytes(of: block.bigEndian) { Array($0) }`，
            // CI 报 "Cannot convert value of type 'UInt32' to expected argument type 'Int'"
            // —— 那个闭包在复合表达式里让类型推断崩了。
            // 改为显式的大端 4 字节，正是 RFC 8018 对 INT(i) 的编码要求，
            // 同时不再有任何推断歧义。
            let blockBytes = [
                UInt8(truncatingIfNeeded: block >> 24),
                UInt8(truncatingIfNeeded: block >> 16),
                UInt8(truncatingIfNeeded: block >> 8),
                UInt8(truncatingIfNeeded: block)
            ]
            var u = hmacSHA256(p, s + blockBytes)
            var t = u
            for _ in 1..<iterations {
                u = hmacSHA256(p, u)
                for i in 0..<t.count { t[i] ^= u[i] }
            }
            out.append(contentsOf: t)
            block += 1
        }
        return Array(out.prefix(keyLength))
    }
}
