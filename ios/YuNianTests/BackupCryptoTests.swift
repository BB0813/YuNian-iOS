import XCTest
// ⚠️ 第 95 轮：`SymmetricKey` 属 CryptoKit，本文件第 66 行用了它。
// 我第 72 轮加过这条 import，后来某次同步把它覆盖丢了 ——
// 与第 94 轮 BackupImporterTests 丢 import GRDB 完全同源（两份副本互相覆盖）。
// CI 报 "cannot find type 'SymmetricKey' in scope"。
import CryptoKit
@testable import YuNian

/// `.lybk` 容器解密测试。
///
/// 夹具 `Fixtures/LybkFixture.swift` 是**确定性**的（固定 salt/IV/ASCII 密码），
/// 由 .NET 的 `AesGcm` + `Rfc2898DeriveBytes::Pbkdf2` 生成，
/// 参数与 Android `BackupViewModel.Crypto` 逐字一致：
/// PBKDF2-HMAC-SHA256 / 100,000 次 / 256 位，AES-256-GCM / 12 字节 IV / 16 字节 tag。
final class BackupCryptoTests: XCTestCase {

    private var blob: Data { Data(fixtureHex: fixtureHex) }

    // MARK: - 容器解析

    func testParseSplitsContainerCorrectly() throws {
        let c = try BackupCrypto.parse(blob)
        XCTAssertEqual(blob.count, 4 + 16 + 12 + 602 + 16, "夹具长度应为 650")
        XCTAssertEqual(c.salt.count, 16)
        XCTAssertEqual(c.iv.count, 12)
        XCTAssertEqual(c.tag.count, 16)
        XCTAssertEqual(c.ciphertext.count, 602, "密文应等于明文长度（GCM 是流式）")
        XCTAssertEqual(c.sealedBox.count, 602 + 16)
    }

    func testParseRejectsBadMagic() {
        var bad = Array(blob.prefix(4))
        bad[0] ^= 0xFF
        let mutated = Data(bad) + blob.dropFirst(4)
        do {
            _ = try BackupCrypto.parse(mutated)
            XCTFail("magic 错误时应抛错")
        } catch {
            guard case BackupCrypto.CryptoError.badMagic = error else {
                return XCTFail("抛的应是 badMagic，实际 \(error)")
            }
        }
    }

    func testParseRejectsTruncatedFile() {
        XCTAssertThrowsError(try BackupCrypto.parse(Data(blob.prefix(20)))) { error in
            guard case BackupCrypto.CryptoError.tooShort = error else {
                return XCTFail("抛的应是 tooShort，实际 \(error)")
            }
        }
    }

    // MARK: - PBKDF2

    /// 派生密钥必须与夹具记录的一致 —— 这是 PBKDF2 三参数
    /// （SHA-256 / 10 万次 / 256 位）与 Android 对齐的唯一证据。
    ///
    /// ⚠️ CI 里**没有 Xcode**，这一段若要真正运行需在 macOS 上；
    /// 本地只能保证：夹具密钥由 .NET 标准库算出、且参数与 Kotlin 逐字相同。
    func testDeriveKeyMatchesFixture() throws {
        let c = try BackupCrypto.parse(blob)
        let key = try BackupCrypto.deriveKey(password: password, salt: c.salt)
        let got = key.withUnsafeBytes { Data($0).map { String(format: "%02x", $0) }.joined() }
        XCTAssertEqual(got, keyHex, "派生密钥与夹具不一致 → PBKDF2 参数或密码编码有偏差")
    }

    func testWrongPasswordYieldsDifferentKey() throws {
        let c = try BackupCrypto.parse(blob)
        let right = try BackupCrypto.deriveKey(password: password, salt: c.salt)
        let wrong = try BackupCrypto.deriveKey(password: password + "x", salt: c.salt)
        let hex = { (k: SymmetricKey) in
            k.withUnsafeBytes { Data($0).map { String(format: "%02x", $0) }.joined() }
        }
        XCTAssertNotEqual(hex(right), hex(wrong))
    }

    // MARK: - 端到端

    func testDecryptRoundTrip() throws {
        let plain = try BackupCrypto.decryptToString(blob, password: password)
        XCTAssertEqual(plain, plaintext, "解密出的明文应与夹具一致")
        // 且应能被 BackupImporter 直接消费
        XCTAssertNoThrow(try BackupImporter(database: try makeDB()).importBackup(plain))
    }

    func testDecryptWithWrongPasswordFails() {
        XCTAssertThrowsError(try BackupCrypto.decryptToString(blob, password: "wrong-password")) { error in
            guard case BackupCrypto.CryptoError.decryptionFailed = error else {
                return XCTFail("抛的应是 decryptionFailed，实际 \(error)")
            }
        }
    }

    // MARK: - 辅助

    private func makeDB() throws -> YuNianDatabase {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("crypto-test-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return try YuNianDatabase(databaseURL: dir.appendingPathComponent("yunian_database"))
    }
}

private extension Data {
    init(fixtureHex: String) {
        var bytes = [UInt8]()
        var it = fixtureHex.makeIterator()
        while let hi = it.next(), let lo = it.next() {
            // ⚠️ 第 93 轮：`hi + lo` 是 Character 相加，结果虽能拼成字符串，
            // 但 CI 报 "cannot convert value of type 'Character' to expected
            // argument type 'String'"。显式插值成 String 再交给 UInt8(_:radix:)。
            // 另外扩展名原先叫 hexString，而调用处写的是 fixtureHex（标签不匹配）。
            bytes.append(UInt8("\(hi)\(lo)", radix: 16) ?? 0)
        }
        self = Data(bytes)
    }
}
