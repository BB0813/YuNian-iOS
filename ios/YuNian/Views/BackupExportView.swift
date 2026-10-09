import SwiftUI

/// 备份导出 —— 与 `BackupImportView` 对称的另一半。
///
/// ## 为什么要有它
/// 在此之前 iOS **只能导入不能导出**：用户能把 Android 备份导进来，
/// 却导不回去。V8「跨端数据迁移」缺的就是这一半。
/// 引擎（9 分区序列化 + `.lybk` 加密）已在 `BackupExporter` 完成并有往返测试；
/// **本页是它唯一的 UI 入口** —— 在它之前 `BackupExporter` 无用户可达路径。
///
/// ## ⚠️ 密码是这份备份唯一的钥匙
/// 容器是 AES-GCM + PBKDF2 派生，密码**不落盘、不上传、无法找回**。
/// 所以本页做了三件事，都是为了不让用户以为"备份好了"却发现打不开：
/// 1. 要求**输两遍**（打错一个字符 = 备份永久不可解）
/// 2. 醒目位置写明"忘了就解不开"（用 danger 色，不是普通提示）
/// 3. 生成后**必须显式分享出去** —— 只存在沙盒里等于没备份
struct BackupExportView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    @State private var password = ""
    @State private var confirmPassword = ""
    /// 生成成功的文件 URL。非 nil 时才显示分享按钮。
    @State private var exportedURL: URL?
    @State private var outcome: BackupExporter.Outcome?
    @State private var errorText: String?
    @State private var working = false

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }

    var body: some View {
        YuNianGlassPage(title: "备份导出", onBack: { dismiss() }) {
            VStack(alignment: .leading, spacing: YuNianTheme.Space.standard) {

                YuNianSectionTitle(title: "加密密码")

                YuNianGlassCard {
                    VStack(alignment: .leading, spacing: YuNianTheme.Space.cardPadding) {
                        // danger 色是刻意的：这不是普通说明，是"数据可能永久丢失"的警告
                        Text("密码不保存在任何地方，也不会上传。忘记密码 = 这份备份永远打不开。")
                            .font(.system(size: 11))
                            .foregroundStyle(colors.danger)

                        YuNianField("密码", text: $password, isSecure: true)
                        YuNianField("再输一次", text: $confirmPassword, isSecure: true)
                    }
                    .padding(YuNianTheme.Space.cardPadding)
                }

                YuNianSectionTitle(title: "生成")

                YuNianGlassCard {
                    VStack(alignment: .leading, spacing: YuNianTheme.Space.cardPadding) {
                        YuNianGlassButton(
                            onClick: { generate() },
                            height: 44,
                            enabled: !working
                        ) {
                            if working {
                                ProgressView()
                            } else {
                                Image(systemName: "tray.and.arrow.up")
                                Text("生成 .lybk 备份文件")
                                    .font(YuNianTheme.TextStyle.cardAction)
                            }
                        }

                        if let msg = errorText {
                            Text(msg)
                                .font(.caption)
                                .foregroundStyle(colors.danger)
                                .textSelection(.enabled)
                        }

                        // 生成成功：显示摘要 + 分享。
                        // ⚠️ 分享是**必须**的一步 —— 文件只在 app 沙盒的临时目录里，
                        // 用户不分享出去就等于没备份（临时目录还会被系统回收）。
                        if let url = exportedURL {
                            if let o = outcome {
                                Text(o.summary)
                                    .font(.system(size: 11))
                                    .foregroundStyle(colors.textSecondary)
                                    .textSelection(.enabled)
                            }

                            Text("下一步：把它存到「文件」或发给自己。只留在 App 里不算备份。")
                                .font(.system(size: 11))
                                .foregroundStyle(colors.textTertiary)

                            ShareLink(item: url) {
                                HStack(spacing: YuNianTheme.Space.half) {
                                    Image(systemName: "square.and.arrow.up")
                                    Text("分享 / 存到「文件」")
                                        .font(YuNianTheme.TextStyle.cardAction)
                                }
                                .foregroundStyle(colors.primary)
                            }

                            Text(url.lastPathComponent)
                                .font(.system(size: 10).monospaced())
                                .foregroundStyle(colors.textTertiary)
                                .lineLimit(1)
                        }
                    }
                    .padding(YuNianTheme.Space.cardPadding)
                }

                YuNianSectionTitle(title: "备份内容")

                YuNianGlassCard {
                    VStack(alignment: .leading, spacing: YuNianTheme.Space.tight) {
                        Text("伴侣、群聊、单聊与群聊消息、记忆、临时记忆、Token 用量、统一记忆、日记 —— 共 9 个分区。")
                            .font(.system(size: 11))
                            .foregroundStyle(colors.textSecondary)
                        Text("格式与 Android「设置 → 数据备份」相同，因此 Android 端可以直接导入。")
                            .font(.system(size: 11))
                            .foregroundStyle(colors.textTertiary)
                    }
                    .padding(YuNianTheme.Space.cardPadding)
                }

                Spacer(minLength: YuNianTheme.Space.pageTop)
            }
            .padding(.horizontal, YuNianTheme.Space.page)
            .padding(.top, YuNianTheme.Space.standard)
        }
    }

    /// 生成并落盘到临时目录。
    private func generate() {
        errorText = nil
        // 每次重新生成都清掉上一个 URL —— 否则用户看到的是旧文件的分享按钮
        exportedURL = nil
        outcome = nil

        guard let database = environment.database else {
            errorText = "数据库尚未就绪"
            return
        }
        guard password.count >= 6 else {
            errorText = "密码至少 6 位"
            return
        }
        guard password == confirmPassword else {
            errorText = "两次输入的密码不一致"
            return
        }

        working = true
        defer { working = false }

        do {
            let (data, result) = try BackupExporter.exportFile(
                database: database, password: password
            )
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("YuNian-\(Self.stamp()).lybk")
            try data.write(to: url, options: .atomic)
            exportedURL = url
            outcome = result
        } catch {
            errorText = "导出失败：\(error)"
        }
    }

    /// 文件名里的时间戳（本地时区，便于用户区分多次备份）。
    private static func stamp() -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd-HHmmss"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f.string(from: Date())
    }
}
