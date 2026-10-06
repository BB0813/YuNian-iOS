import Foundation
import GRDB
import os

/// 备份导入的**端到端**编排：文件 → 解密 → 解析 → 写入 → 结果。
///
/// ## 为什么单独一层
/// `BackupCrypto`（容器解密）与 `BackupImporter`（9 分区写入）在第 71–86 轮就完成并各有单测，
/// 但第 120 轮发现它们**没有任何 UI 入口** —— 用户拿不到备份导入能力。
/// 本类型是把两者接起来的那一层，并给 UI 一个可观测的结果结构。
///
/// ## 导入是幂等的
/// 伴侣按 name 匹配复用，消息按 `(timestamp, isFromUser, content)` 去重，
/// 因此同一文件重复导入不会产生重复数据（`BackupImporterTests` 已覆盖）。
struct BackupImportService {

    private let log = Logger(subsystem: "com.yunian.ai", category: "backup.import")

    struct Outcome: Equatable {
        var ok: Bool
        var message: String
        var detail: BackupImporter.Result?

        var summary: String {
            guard ok, let d = detail else { return message }
            return message + "：" + d.summary
        }
    }

    /// 从备份文件字节导入。
    ///
    /// ⚠️ 第 131 轮：加 `static`。
    /// 调用方（BackupImportView）拿不到可用的实例 init —— 结构体唯一的
    /// stored property 是 `log`，隐式 memberwise init 是 `(log:)`，不实用。
    /// 本方法也不使用任何实例状态，故改 static，调用点写
    /// `BackupImportService.importFile(...)`，与 `BackupCrypto.decrypt(...)`
    /// 的静态风格一致。
    ///
    /// - Parameters:
    ///   - data: `.lybk` 文件内容（二进制，含 LYBK magic + salt + IV + 密文）
    ///   - password: 用户在 Android 侧导出时设的密码
    ///   - database: 目标库
    static func importFile(data: Data, password: String, database: YuNianDatabase) -> Outcome {
        // ① 容器解密
        let plain: Data
        do {
            plain = try BackupCrypto.decrypt(data, password: password)
        } catch BackupCrypto.CryptoError.badMagic {
            return Outcome(ok: false, message: "这不是 .lybk 备份文件", detail: nil)
        } catch BackupCrypto.CryptoError.tooShort {
            return Outcome(ok: false, message: "备份文件过短或不完整", detail: nil)
        } catch BackupCrypto.CryptoError.decryptionFailed {
            return Outcome(ok: false, message: "解密失败：密码错误或文件已损坏", detail: nil)
        } catch BackupCrypto.CryptoError.keyDerivationFailed {
            return Outcome(ok: false, message: "密钥派生失败（PBKDF2）", detail: nil)
        } catch {
            return Outcome(ok: false,
                          message: "读取失败：\(error.localizedDescription)", detail: nil)
        }

        // ② 解析为 JSON 文本
        guard let json = String(data: plain, encoding: .utf8) else {
            return Outcome(ok: false, message: "解密后的内容不是 UTF-8 文本", detail: nil)
        }

        // ③ 写入 9 个分区
        do {
            let result = try BackupImporter(database: database).importBackup(json)
            log.info("备份导入成功：\(result.summary, privacy: .public)")
            return Outcome(ok: true, message: "导入成功", detail: result)
        } catch BackupImporter.ImportError.malformedJSON {
            return Outcome(ok: false, message: "备份内容不是合法 JSON", detail: nil)
        } catch {
            return Outcome(ok: false,
                          message: "写入失败：\(error.localizedDescription)", detail: nil)
        }
    }
}
