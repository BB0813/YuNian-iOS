import SwiftUI
import UIKit

/// 备份导入界面（V8）。
///
/// ## 背景
/// 第 120 轮发现：`BackupCrypto` 与 `BackupImporter` 完成并各有单测，
/// 但**没有任何 UI 入口** —— 用户拿不到这个能力。本视图补上那一环。
///
/// ## 流程
/// 选择 `.lybk` 文件 → 输入导出时设的密码 → 导入 → 显示结果。
/// 解密与写入由 `BackupImportService` 编排（容器解密 + 9 分区写入），
/// 各步骤的失败都有明确文案，不让用户看到裸异常。
struct BackupImportView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @State private var password = ""
    @State private var pickedFile: BackupFile?
    @State private var showPicker = false
    @State private var outcome: BackupImportService.Outcome?
    @State private var importing = false

    /// 选中的文件（`UIViewControllerRepresentable` 传来的 URL 需立即读取，
    /// 因为安全作用域的 URL 在回调外不可用）。
    struct BackupFile: Equatable {
        var name: String
        var data: Data
    }

    var body: some View {
        Form {
            Section("备份文件") {
                Button("选择 .lybk 备份文件") { showPicker = true }
                if let f = pickedFile {
                    LabeledContent("已选择", value: f.name)
                    Text("\(f.data.count) 字节")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    Text("从 Android 端「设置 → 数据备份」导出后，通过文件 App / AirDrop / 隔空投送传到本机。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Section("密码") {
                SecureField("导出时设置的密码", text: $password)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }

            Section {
                Button {
                    Task { await runImport() }
                } label: {
                    HStack {
                        Text("导入")
                        if importing {
                            Spacer()
                            ProgressView()
                        }
                    }
                }
                .disabled(pickedFile == nil || password.isEmpty || importing)
            }

            if let outcome {
                Section("结果") {
                    Label(outcome.ok ? "导入成功" : "导入失败",
                          systemImage: outcome.ok ? "checkmark.circle.fill" : "xmark.octagon.fill")
                        .foregroundStyle(outcome.ok ? .green : .red)
                    Text(outcome.summary)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
            }

            Section {
                // 幂等性说明：同一文件重复导入是安全的
                Text("同一备份可重复导入：伴侣按名称匹配复用，消息按时间戳与内容去重。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("备份导入")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showPicker) {
            DocumentPicker(onPick: { url in
                pickedFile = loadFile(url)
                outcome = nil
            })
        }
    }

    private func runImport() async {
        guard let file = pickedFile, let database = environment.database else { return }
        importing = true
        defer { importing = false }
        // 解密是 CPU 密集（PBKDF2 10 万次），放到后台避免卡界面
        let result = await Task.detached(priority: .userInitiated) {
            BackupImportService().importFile(
                data: file.data, password: password, database: database)
        }.value
        outcome = result
    }

    /// 读取选中的文件。必须在 picker 回调的**同一轮**里读完并 startAccessing。
    private func loadFile(_ url: URL) -> BackupFile? {
        let needsScope = url.startAccessingSecurityScopedResource()
        defer { if needsScope { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { return nil }
        return BackupFile(name: url.lastPathComponent, data: data)
    }
}

/// `.lybk` 文件选择器。
private struct DocumentPicker: UIViewControllerRepresentable {

    var onPick: (URL) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.data])
        picker.allowsMultipleSelection = false
        picker.shouldShowFileExtensions = true
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ v: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL) -> Void
        init(onPick: @escaping (URL) -> Void) { self.onPick = onPick }

        func documentPicker(_ controller: UIDocumentPickerViewController,
                            didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else { return }
            onPick(url)
        }
    }
}
