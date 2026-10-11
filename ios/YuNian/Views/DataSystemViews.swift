import SwiftUI
import UniformTypeIdentifiers
// UIApplication.openSettingsURLString（跳系统设置）需要 UIKit。
import UIKit

/// 备份文件容器（`.lybk`）。
///
/// 用 `FileDocument` 是为了走系统 `.fileExporter` ——
/// 让用户自己选存到"文件"App 的哪里，而不是我们替他决定路径。
struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }

    var data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

/// 数据与备份。
///
/// 后端的 `BackupExporter` / `BackupImportService` 早就存在
/// （导出是 `BackupCrypto.encrypt` 出的加密容器，导入按分区逐表映射），
/// **但一直没有任何界面调用它们** —— 用户没有任何办法导出自己的数据。
///
/// ## 一条产品原则
/// 「数据是你的」这件事必须**有出口**：加密导出 + 可恢复导入，
/// 而不是把数据锁在 App 沙盒里、换机就全丢。
struct BackupView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var password = ""
    @State private var confirmPassword = ""
    @State private var importingPassword = ""
    @State private var status: Status?
    @State private var exportDocument: BackupDocument?
    @State private var showExporter = false
    @State private var showImporter = false
    @State private var busy = false

    private enum Status: Equatable {
        case ok(String)
        case failure(String)
    }

    var body: some View {
        let c = YNTheme.palette(scheme)

        List {
            exportSection
            importSection
            statusSection
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("数据与备份")
        .navigationBarTitleDisplayMode(.inline)
        // 导出到「文件」
        .fileExporter(
            isPresented: $showExporter,
            document: exportDocument,
            contentType: .data,
            defaultFilename: "yinian-backup"
        ) { result in
            if case let .failure(error) = result {
                status = .failure(Self.friendly(error))
            } else {
                status = .ok("已导出。请妥善保管文件与口令 —— 口令丢失将无法恢复。")
            }
        }
        // 从「文件」导入
        .fileImporter(
            isPresented: $showImporter,
            allowedContentTypes: [.data]
        ) { result in
            handleImport(result)
        }
    }

    // MARK: - 导出

    private var exportSection: some View {
        Section {
            SecureField("设置一个备份口令", text: $password)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            SecureField("再输一次", text: $confirmPassword)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            Button {
                export()
            } label: {
                HStack {
                    if busy { ProgressView().controlSize(.small) }
                    Text("导出加密备份")
                    Spacer()
                }
            }
            .disabled(busy || !canExport)
        } header: {
            Text("导出")
        } footer: {
            Text(canExport || password.isEmpty
                 ? "备份以你设置的口令加密。**口令不保存在本机**，忘记即无法恢复。"
                 : "两次输入的口令不一致。")
        }
    }

    private var canExport: Bool {
        !password.isEmpty && password == confirmPassword
    }

    // MARK: - 导入

    private var importSection: some View {
        Section {
            SecureField("该备份的口令", text: $importingPassword)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            Button {
                showImporter = true
            } label: {
                HStack {
                    if busy { ProgressView().controlSize(.small) }
                    Text("选择备份文件导入")
                    Spacer()
                }
            }
            .disabled(busy || importingPassword.isEmpty)
        } header: {
            Text("导入")
        } footer: {
            Text("导入会按分区写入本地库。现有数据不会被清空，同名条目以导入内容为准。")
        }
    }

    // MARK: - 状态

    @ViewBuilder
    private var statusSection: some View {
        let c = YNTheme.palette(scheme)
        if let status {
            Section {
                switch status {
                case let .ok(text):
                    Label(text, systemImage: "checkmark.circle.fill")
                        .foregroundStyle(c.success)
                        .font(.subheadline)
                case let .failure(text):
                    // T6：图标 + 文字，不只靠颜色
                    Label(text, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(c.warning)
                        .font(.subheadline)
                }
            }
        }
    }

    // MARK: - 逻辑

    private func export() {
        guard let database = environment.database else {
            status = .failure("数据库尚未就绪。")
            return
        }
        busy = true
        defer { busy = false }

        do {
            let result = try BackupExporter.exportFile(database: database, password: password)
            exportDocument = BackupDocument(data: result.data)
            password = ""
            confirmPassword = ""
            showExporter = true
        } catch {
            status = .failure(Self.friendly(error))
        }
    }

    private func handleImport(_ result: Result<URL, Error>) {
        guard let database = environment.database else {
            status = .failure("数据库尚未就绪。")
            return
        }
        switch result {
        case let .failure(error):
            status = .failure(Self.friendly(error))
        case let .success(url):
            // 安全作用域资源：从「文件」拿到的 URL 必须先取访问权才能读
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }

            do {
                let data = try Data(contentsOf: url)
                let outcome = BackupImportService.importFile(
                    data: data, password: importingPassword, database: database)
                importingPassword = ""
                status = .ok(outcome.summary)
            } catch {
                status = .failure(Self.friendly(error))
            }
        }
    }

    /// 不把底层错误文本直接抛给用户（UI 不暴露路径 / 加密细节 / 堆栈）。
    private static func friendly(_ error: Error) -> String {
        "操作失败，请确认口令正确、文件完整后重试。"
    }
}
