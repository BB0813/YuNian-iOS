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
    @State private var report: BackupReport.Report?
    @State private var exportDocument: BackupDocument?
    @State private var showExporter = false
    @State private var showImporter = false
    @State private var busy = false
    /// 进行中的阶段文字（**只报阶段，不报百分比** —— 后端不提供进度，
    /// 编一个百分比出来就是假数据）。
    @State private var stage: String?
    /// 上一次选的文件：失败后可以直接重试，不用再挑一次文件。
    @State private var lastImportURL: URL?

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
                report = BackupReport.failure(Self.friendly(error))
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

            // 失败后不必重新挑文件 —— 上一次选的那个还在（安全作用域会重新取一次）。
            if lastImportURL != nil {
                Button {
                    if let url = lastImportURL { runImport(from: url) }
                } label: {
                    Label("重试上一次选的文件", systemImage: "arrow.clockwise")
                }
                .disabled(busy || importingPassword.isEmpty)
            }
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
        if let stage {
            Section {
                HStack(spacing: YNTheme.Space.sm) {
                    ProgressView().controlSize(.small)
                    Text(stage).font(.subheadline).foregroundStyle(c.textSecondary)
                }
            } footer: {
                Text("这一步在后台线程跑（PBKDF2 + AES + 全表读写都是 CPU 活），界面不会卡住。")
            }
        }
        if let report {
            Section {
                // T6：图标 + 文字，不只靠颜色
                Label(report.title,
                      systemImage: report.ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                    .foregroundStyle(report.ok ? c.success : c.warning)
                    .font(.subheadline)
                ForEach(report.lines) { line in
                    LabeledContent(line.label, value: line.value)
                }
                if let hint = report.hint {
                    Text(hint)
                        .font(.caption)
                        .foregroundStyle(c.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } header: {
                Text(report.ok ? "结果" : "未能完成")
            }
        }
    }

    // MARK: - 逻辑

    /// 导出。
    ///
    /// ⚠️ 第 215 轮：**移到后台线程**。此前整个导出（全表序列化 + PBKDF2 + AES）
    /// 在主线程同步跑 —— 数据一多界面就冻住，而且冻住期间用户看不到任何反馈。
    /// 现在：主线程只切状态，重活丢给全局队列，回来再切结果。
    private func export() {
        guard let database = environment.database else {
            report = BackupReport.failure("数据库尚未就绪。")
            return
        }
        let password = self.password
        busy = true
        report = nil
        stage = "正在收集数据并加密…"

        DispatchQueue.global(qos: .userInitiated).async {
            let result = try? BackupExporter.exportFile(database: database, password: password)
            DispatchQueue.main.async {
                busy = false
                stage = nil
                guard let result else {
                    report = BackupReport.failure("导出失败：序列化或加密出错（口令不变，可重试）")
                    return
                }
                exportDocument = BackupDocument(data: result.data)
                self.password = ""
                confirmPassword = ""
                report = BackupReport.export(result.outcome)
                showExporter = true
            }
        }
    }

    private func handleImport(_ result: Result<URL, Error>) {
        switch result {
        case let .failure(error):
            report = BackupReport.failure(Self.friendly(error))
        case let .success(url):
            lastImportURL = url
            runImport(from: url)
        }
    }

    /// 跑一次导入（**后台线程**）。
    ///
    /// ⚠️ 第 215 轮修掉一处**会撒谎的 UI**：此前结束后写的是
    /// `status = .ok(outcome.summary)` —— 从不看 `outcome.ok`，
    /// 于是"解密失败：密码错误"会带**绿色对勾**显示。
    /// 现在成功与失败都过 `BackupReport.imported(_:)`，并且有测试锁住这条映射。
    private func runImport(from url: URL) {
        guard let database = environment.database else {
            report = BackupReport.failure("数据库尚未就绪。")
            return
        }
        let password = importingPassword
        busy = true
        report = nil
        stage = "正在读取并解密备份…"

        // 安全作用域资源：从「文件」拿到的 URL 必须先取访问权才能读。
        // ⚠️ 取/放必须在**同一个线程**上配对，所以整段放进后台任务里。
        DispatchQueue.global(qos: .userInitiated).async {
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }

            let data = try? Data(contentsOf: url)
            let outcome = data.map {
                BackupImportService.importFile(data: $0, password: password, database: database)
            }
            DispatchQueue.main.async {
                busy = false
                stage = nil
                guard let outcome else {
                    report = BackupReport.failure("读不到这个文件（可能是 iCloud 未下载或权限不足）")
                    return
                }
                if outcome.ok { importingPassword = "" }
                report = BackupReport.imported(outcome)
            }
        }
    }

    /// 不把底层错误文本直接抛给用户（UI 不暴露路径 / 加密细节 / 堆栈）。
    private static func friendly(_ error: Error) -> String {
        "操作失败，请确认口令正确、文件完整后重试。"
    }
}
