import XCTest
import CryptoKit

/// `.lybk` 容器**加密**侧（第 195 轮）。
///
/// ## 为什么单独一个文件
/// `BackupCryptoTests` 覆盖解密；这里覆盖新增的 `encrypt`。
/// 两者对称性由本文件的核心断言守住。
///
/// ## ⚠️ 本文件最重要的一条：`testEncryptOutputPassesParse`
/// 只测「encrypt 出来的东西 decrypt 能还原」是**不够的** ——
/// 若把 salt 与 IV 的顺序写反，自己加自己解照样通过，
/// 而 Android 端会解不开。所以必须让产物经过与导入路径**同一个**
/// `parse`，逐字段核对切分位置。
@testable import YuNian
final class BackupCryptoEncryptTests: XCTestCase {

    /// 固定 salt，让断言可复现（生产路径不传，由 CSPRNG 生成）。
    private let fixedSalt = Data(repeating: 0xAB, count: 16)
    private let password = "密码pass1😀"   // 含中日文与 emoji —— 文件头声明按 UTF-8

    private func plaintext(_ s: String) -> Data { Data(s.utf8) }

    // MARK: - 往返

    func testRoundTripASCII() throws {
        let plain = plaintext("{\"hello\":\"world\"}")
        let blob = try BackupCrypto.encrypt(plain, password: "pw123", salt: fixedSalt)
        XCTAssertEqual(try BackupCrypto.decrypt(blob, password: "pw123"), plain)
    }

    /// 非 ASCII 密码 —— 文件头记录了「Android 侧按 UTF-8 编码密码」且已用
    /// JDK 实测过。这里锁住 iOS 侧同口径。
    func testRoundTripNonASCIIPassword() throws {
        let plain = plaintext("{\"k\":1}")
        let blob = try BackupCrypto.encrypt(plain, password: password, salt: fixedSalt)
        XCTAssertEqual(try BackupCrypto.decrypt(blob, password: password), plain)
    }

    func testRoundTripEmptyPlaintext() throws {
        let blob = try BackupCrypto.encrypt(Data(), password: "pw", salt: fixedSalt)
        XCTAssertEqual(try BackupCrypto.decrypt(blob, password: "pw"), Data())
    }

    /// 大一点的内容（多块，跨 GCM 分块边界）。
    func testRoundTripLargePlaintext() throws {
        let plain = Data((0..<10_000).map { UInt8($0 % 251) })
        let blob = try BackupCrypto.encrypt(plain, password: "pw", salt: fixedSalt)
        XCTAssertEqual(try BackupCrypto.decrypt(blob, password: "pw"), plain)
    }

    // MARK: - 布局（核心）

    /// ⚠️ **本文件最重要的一条**：产物必须能被 `parse` 正确切分。
    func testEncryptOutputPassesParse() throws {
        let plain = plaintext("{\"a\":1}")
        let blob = try BackupCrypto.encrypt(plain, password: "pw", salt: fixedSalt)
        let c = try BackupCrypto.parse(blob)

        // salt 在偏移 4，且就是我们传进去的那 16 字节
        XCTAssertEqual(c.salt, fixedSalt, "salt 必须落在偏移 4..<20")
        // IV 是 12 字节（不核对内容 —— 它是随机生成的）
        XCTAssertEqual(c.iv.count, 12)
        // tag 恒为 16 字节，且是从**尾部**切出来的
        XCTAssertEqual(c.tag.count, 16)
        // ciphertext 长度 == 明文长度（GCM 是流式加密，不填充）
        XCTAssertEqual(c.ciphertext.count, plain.count,
                       "GCM 不填充，密文长度应等于明文长度")
        // 总长 == 4 + 16 + 12 + 明文 + 16
        XCTAssertEqual(blob.count, 4 + 16 + 12 + plain.count + 16)
    }

    /// 前 4 字节必须是 `LYBK` —— 否则导入侧会报「这不是 .lybk 备份文件」。
    func testOutputStartsWithLYBKMagic() throws {
        let blob = try BackupCrypto.encrypt(plaintext("x"), password: "pw", salt: fixedSalt)
        XCTAssertEqual(Array(blob.prefix(4)), Array("LYBK".utf8))
    }

    /// 空明文时容器仍是**最小合法长度**（4+16+12+0+16 = 48），
    /// 不能被 `parse` 判为 tooShort。
    func testEmptyPlaintextContainerIsNotTooShort() throws {
        let blob = try BackupCrypto.encrypt(Data(), password: "pw", salt: fixedSalt)
        XCTAssertEqual(blob.count, 48)
        XCTAssertNoThrow(try BackupCrypto.parse(blob))
    }

    // MARK: - salt / IV 的随机性

    /// 同明文同密码两次加密 → 字节不同（salt 与 IV 各自随机）。
    /// 若相同，说明某处用了固定值 —— 那是严重的安全退化。
    func testTwoEncryptionsDifferWhenSaltRandom() throws {
        let plain = plaintext("same")
        let a = try BackupCrypto.encrypt(plain, password: "pw")
        let b = try BackupCrypto.encrypt(plain, password: "pw")
        XCTAssertNotEqual(a, b, "随机 salt/IV 未生效 —— 两次产物不应相同")
        // 但两者都能解回同一明文
        XCTAssertEqual(try BackupCrypto.decrypt(a, password: "pw"), plain)
        XCTAssertEqual(try BackupCrypto.decrypt(b, password: "pw"), plain)
    }

    /// 固定 salt 时，salt 段仍是固定值（证明 salt 确实被写进偏移 4）。
    func testFixedSaltIsWrittenIntoContainer() throws {
        let blob = try BackupCrypto.encrypt(plaintext("x"), password: "pw", salt: fixedSalt)
        XCTAssertEqual(Data(blob[4..<20]), fixedSalt)
    }

    // MARK: - 错误路径

    /// 密码错 → `decryptionFailed`（而不是别的错，也不是崩溃）。
    func testWrongPasswordFails() throws {
        let blob = try BackupCrypto.encrypt(plaintext("secret"), password: "right", salt: fixedSalt)
        XCTAssertThrowsError(try BackupCrypto.decrypt(blob, password: "wrong")) { err in
            guard case BackupCrypto.CryptoError.decryptionFailed = err else {
                return XCTFail("期望 decryptionFailed，实际 \(err)")
            }
        }
    }

    /// salt 长度不对 → 明确报错，而不是产出一个 `parse` 不了的容器。
    func testBadSaltLengthThrows() throws {
        XCTAssertThrowsError(
            try BackupCrypto.encrypt(plaintext("x"), password: "pw", salt: Data(repeating: 1, count: 8))
        ) { err in
            guard case let BackupCrypto.CryptoError.badSaltLength(n) = err else {
                return XCTFail("期望 badSaltLength，实际 \(err)")
            }
            XCTAssertEqual(n, 8)
        }
    }

    /// 篡改密文 → 解密失败（GCM 的完整性保证）。
    func testTamperedCiphertextFails() throws {
        var blob = try BackupCrypto.encrypt(plaintext("secret"), password: "pw", salt: fixedSalt)
        // 翻掉最后一个密文/标签字节之前的一位
        blob[blob.count - 20] ^= 0x01
        XCTAssertThrowsError(try BackupCrypto.decrypt(blob, password: "pw"))
    }

    /// 篡改 tag → 解密失败。
    func testTamperedTagFails() throws {
        var blob = try BackupCrypto.encrypt(plaintext("secret"), password: "pw", salt: fixedSalt)
        blob[blob.count - 1] ^= 0x01
        XCTAssertThrowsError(try BackupCrypto.decrypt(blob, password: "pw"))
    }
}
