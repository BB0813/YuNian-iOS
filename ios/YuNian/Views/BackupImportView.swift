import SwiftUI
import UniformTypeIdentifiers

/// 备份导入 —— 第 129 轮套上设计系统。
///
/// ## 与 Android 的关系
/// 备份文件由 Android 端「设置 -> 数据备份」导出（BackupExportService），
/// 经文件 App / AirDrop 传到本机后在这里解密导入。
/// 解密与落库逻辑在 BackupCrypto + BackupImporter，均逐字对齐 Android。
///
/// ## 幂等性
/// 同一文件重复导入是安全的：伴侣按名称匹配复用，消息按时间戳与内容去重。
struct BackupImportView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme
    @State private var password = ""
    @State private var pickedFile: BackupFile?
    @State private var showPicker = false
    @State private var outcome: BackupImportService.Outcome?
    @State private var importing = false

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }

    struct BackupFile: Equatable {
        var name: String
        var data: Data
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: YuNianTheme.Space.standard) {

                YuNianSectionTitle(title: "备份文件")

                YuNianGlassCard {
                    VStack(alignment: .leading, spacing: YuNianTheme.Space.cardPadding) {
                        YuNianGlassButton(onClick: { showPicker = true }, height: 44) {
                            Image(systemName: "doc")
                            Text("选择 .lybk 备份文件")
                                .font(YuNianTheme.TextStyle.cardAction)
                        }

                        if let f = pickedFile {
                            LabeledContent {
                                Text(f.name).font(.system(size: 13))
                            } label: {
                                Text("已选择")
                                    .font(YuNianTheme.TextStyle.settingsRowSubtitle)
                            }
                            Text("\(f.data.count) 字节")
                                .font(.system(size: 11))
                                .foregroundStyle(colors.textTertiary)
                        } else {
                            Text("从 Android 端「设置 -> 数据备份」导出后，通过文件 App / AirDrop 传到本机。")
                                .font(.system(size: 12))
                                .foregroundStyle(colors.textSecondary)
                        }
                    }
                }

                YuNianSectionTitle(title: "密码")

                YuNianGlassCard {
                    YuNianField("导出时设置的密码", text: $password, isSecure: true)
                }

                YuNianGlassButton(
                    onClick: { Task { await runImport() } },
                    height: 48
                ) {
                    Text("导入").bold()
                    if importing { ProgressView() }
                }
                .disabled(pickedFile == nil || password.isEmpty || importing)

                if let outcome {
                    YuNianGlassCard {
                        VStack(alignment: .leading, spacing: YuNianTheme.Space.half) {
                            Label(outcome.ok ? "导入成功" : "导入失败",
                                  systemImage: outcome.ok ? "checkmark.circle.fill"
                                                        : "xmark.octagon.fill")
                                .font(YuNianTheme.TextStyle.settingsRowTitle)
                                .foregroundStyle(outcome.ok ? colors.success : colors.danger)
                            Text(outcome.summary)
                                .font(.system(size: 11))
                                .foregroundStyle(colors.textSecondary)
                                .textSelection(.enabled)
                        }
                    }
                }

                Text("同一备份可重复导入：伴侣按名称匹配复用，消息按时间戳与内容去重。")
                    .font(.system(size: 11))
                    .foregroundStyle(colors.textTertiary)
                    .padding(.horizontal, YuNianTheme.Space.minUnit)

                Spacer(minLength: YuNianTheme.Space.pageTop)
            }
            .padding(.horizontal, YuNianTheme.Space.page)
            .padding(.top, YuNianTheme.Space.standard)
        }
        .background(colors.background.ignoresSafeArea())
        .navigationTitle("备份导入")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showPicker) {
            DocumentPicker(onPick: { url in
                pickedFile = loadFile(url)
                outcome = nil
                showPicker = false
            })
        }
    }

    // MARK: - 导入

    private func runImport() async {
        guard let file = pickedFile, let database = environment.database else { return }
        importing = true
        defer { importing = false }

        let service = BackupImportService(database: database)
        let result = await Task.detached(priority: .userInitiated) {
            service.import(data: file.data, password: password)
        }.value

        outcome = result
        if result.ok {
            environment.syncRuntimeConfig()
        }
    }

    private func loadFile(_ url: URL) -> BackupFile? {
        guard url.startAccessingSecurityScopedResource() else { return nil }
        defer { url.stopAccessingSecurityScopedResource() }
        guard let data = try? Data(contentsOf: url) else { return nil }
        return BackupFile(name: url.lastPathComponent, data: data)
    }
}
