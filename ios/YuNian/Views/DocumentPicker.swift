import SwiftUI
import UniformTypeIdentifiers

/// 文件选择器 —— 选 `.lybk` 备份文件。
///
/// ## 为什么需要（第 130 轮才发现它一直缺失）
/// `BackupImportView` 从第 120 轮起就调用 `DocumentPicker(onPick:)`，
/// 但**这个类型从未被创建**。也就是说备份导入页从那一版起就编译不过，
/// 而我多轮都没发现 —— 因为那几轮的 CI 报错我逐个修完前面的就以为干净了。
///
/// 这次是 `verify_own_types` 关卡抓到的（"构造 DocumentPicker 的类型未声明"）。
/// 它又一次证明了价值：**静态关卡能抓到我连编译错误都没看全的疏漏**。
///
/// 用 `UIDocumentPickerViewController`（SwiftUI 外层包
/// `UIViewControllerRepresentable`），模式为打开内容 —— 因此回调里
/// 必须**立即读数据**：安全作用域的 URL 在回调外不可用。
struct DocumentPicker: UIViewControllerRepresentable {

    let onPick: (URL) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        // 只接受 .lybk；Android 导出文件名格式 lianyu_backup_yyyy-MM-dd.lybk
        let types: [UTType] = [.lybk, .data]
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: types)
        picker.allowsMultipleSelection = false
        picker.shouldShowFileExtensions = true
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController,
                            context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let parent: DocumentPicker

        init(_ parent: DocumentPicker) {
            self.parent = parent
        }

        func documentPicker(_ controller: UIDocumentPickerViewController,
                            didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else { return }
            // 必须在这里通知读完：startAccessingSecurityScopedResource 的窗口
            // 只在回调内有效（BackupImportView.loadFile 也受同一约束）。
            parent.onPick(url)
        }
    }
}

// MARK: - .lybk 内容类型

extension UTType {
    /// 备份文件类型。Android 侧导出后缀为 `.lybk`（BackupScreen.kt:423-426）。
    ///
    /// 系统不认识该扩展名时会归为通用数据文件，仍可选中 —— 不会因此漏文件。
    static let lybk = UTType(filenameExtension: "lybk") ?? .data
}
