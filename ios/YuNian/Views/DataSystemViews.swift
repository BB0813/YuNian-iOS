import SwiftUI
import UniformTypeIdentifiers
// UIApplication.openSettingsURLString（跳系统设置）需要 UIKit。
import UIKit
// 恢复引擎直接走 pool.write 写库 —— GRDB 的 write / db.execute 需要本文件可见
// （仓内先例：ImageGenSettingsView 同样 import GRDB 直接查 pool）。
import GRDB
import Foundation

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

    // ── 恢复（覆盖式）─────────────────────────────────────────
    // 与「导入」分开的口令框：恢复是破坏性操作，让它有自己独立、显式的输入，
    // 不与合并导入共用状态。
    @State private var restorePassword = ""
    @State private var showRestoreImporter = false
    @State private var showRestoreConfirm = false
    /// 已解密、等待用户确认的恢复任务。确认弹窗与真正执行共用同一份明文 ——
    /// 既有"确认框要显示概要"的原因（加密容器看不见内容），也省掉确认后
    /// 再跑一次 PBKDF2（10 万次迭代）+ 解密的开销。
    @State private var pendingRestore: PendingRestore?

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
            restoreSection
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
        // 恢复：同一容器格式。`.lybk` 是自定义二进制（LYBK magic），UTType
        // 没有对应类型，`.data` 是诚实的选择 —— 与上面的导入一致。
        .fileImporter(
            isPresented: $showRestoreImporter,
            allowedContentTypes: [.data]
        ) { result in
            handleRestorePick(result)
        }
        // 恢复前二次确认：这一步**不可撤销**。概要（体积/分区条数）来自
        // 选文件时的解密结果，让用户在知情的前提下按下破坏性按钮。
        .confirmationDialog(
            "用这份备份覆盖当前数据？",
            isPresented: $showRestoreConfirm,
            titleVisibility: .visible
        ) {
            Button("覆盖当前数据并恢复", role: .destructive) { runRestore() }
            Button("取消", role: .cancel) { pendingRestore = nil }
        } message: {
            Text(pendingRestore?.summary.confirmMessage ?? "")
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

    // MARK: - 恢复（覆盖式）

    /// 「恢复」与「导入」的分工：导入是**合并**（同名条目以导入为准，不清空），
    /// 恢复是把库**还原成备份那一刻的快照** —— 必须先清空再写入。
    private var restoreSection: some View {
        Section {
            SecureField("该备份的口令", text: $restorePassword)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            Button {
                showRestoreImporter = true
            } label: {
                HStack {
                    if busy { ProgressView().controlSize(.small) }
                    Label("从备份恢复（覆盖当前数据）", systemImage: "arrow.triangle.2.circlepath")
                    Spacer()
                }
            }
            .disabled(busy || restorePassword.isEmpty)
        } header: {
            Text("恢复")
        } footer: {
            Text("恢复与导入不同：会**先清空**本机的伴侣、聊天与记忆，再写入备份里的内容。当前数据会被覆盖且**无法撤销** —— 动手前建议先导出一份新备份。")
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

    // MARK: - 恢复

    /// 选定文件：先解密 + 概要化，再交给确认弹窗。
    ///
    /// 为什么不直接恢复：确认框必须给出"这份备份有多大、都有什么"，
    /// 而容器是加密的 —— 只有先解密才能数出分区条数。解密失败（密码错 /
    /// 非备份文件）在这里就地报告，不进入确认流程。
    private func handleRestorePick(_ result: Result<URL, Error>) {
        switch result {
        case let .failure(error):
            report = BackupReport.failure(Self.friendly(error))
        case let .success(url):
            guard environment.database != nil else {
                report = BackupReport.failure("数据库尚未就绪。")
                return
            }
            let password = restorePassword
            busy = true
            report = nil
            stage = "正在解密备份…"

            // 与导入同一条铁律：安全作用域的取/放必须**同线程配对**，整段进后台。
            DispatchQueue.global(qos: .userInitiated).async {
                let accessing = url.startAccessingSecurityScopedResource()
                defer { if accessing { url.stopAccessingSecurityScopedResource() } }

                let failureText: String?
                var picked: PendingRestore?
                if let fileData = try? Data(contentsOf: url) {
                    do {
                        let plain = try BackupCrypto.decrypt(fileData, password: password)
                        if let summary = BackupRestorer.summarize(plaintext: plain, fileBytes: fileData.count) {
                            picked = PendingRestore(plaintext: plain, summary: summary)
                            failureText = nil
                        } else {
                            failureText = "解密成功，但内容不是予念备份（缺少已知分区）"
                        }
                    } catch BackupCrypto.CryptoError.badMagic {
                        failureText = "这不是 .lybk 备份文件"
                    } catch BackupCrypto.CryptoError.tooShort {
                        failureText = "备份文件过短或不完整"
                    } catch BackupCrypto.CryptoError.decryptionFailed {
                        failureText = "解密失败：密码错误或文件已损坏"
                    } catch BackupCrypto.CryptoError.keyDerivationFailed {
                        failureText = "密钥派生失败（PBKDF2）"
                    } catch {
                        failureText = "读取失败，请重试"
                    }
                } else {
                    failureText = "读不到这个文件（可能是 iCloud 未下载或权限不足）"
                }

                DispatchQueue.main.async {
                    busy = false
                    stage = nil
                    if let picked {
                        pendingRestore = picked
                        showRestoreConfirm = true
                    } else {
                        report = BackupReport.failure(failureText ?? "操作失败，请重试")
                    }
                }
            }
        }
    }

    /// 执行恢复（后台线程）。`BackupRestorer` 在单事务里先删后插，
    /// 任何一步失败整体回滚 —— 用户看到的是"当前数据保持原样"，不是半个快照。
    private func runRestore() {
        guard let database = environment.database, let pending = pendingRestore else { return }
        busy = true
        report = nil
        stage = "正在覆盖恢复…（失败会整体回滚）"
        let plaintext = pending.plaintext
        pendingRestore = nil

        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let outcome = try BackupRestorer.restore(plaintext: plaintext, database: database)
                DispatchQueue.main.async {
                    busy = false
                    stage = nil
                    restorePassword = ""
                    report = BackupReport.Report(
                        ok: true,
                        title: "恢复完成：\(outcome.totalRestored) 条",
                        lines: BackupReport.sectionLines(outcome.restoredSections)
                            + [BackupReport.Line(label: "清除旧数据", value: "\(outcome.wipedRows) 行")],
                        hint: "本机数据已按备份覆盖。不在备份范围内的数据（渠道、世界书、技能、表情、归档等）保持原样。"
                    )
                }
            } catch {
                DispatchQueue.main.async {
                    busy = false
                    stage = nil
                    report = BackupReport.failure(
                        "恢复未完成：\(Self.restoreFailureText(error)) —— 当前数据保持原样（失败已整体回滚）"
                    )
                }
            }
        }
    }

    /// 恢复失败的用户话术：不暴露 SQL / 路径 / 堆栈（与 friendly 同一原则）。
    private static func restoreFailureText(_ error: Error) -> String {
        if let restoreError = error as? BackupRestorer.RestoreError {
            return restoreError.description
        }
        return "数据库写入失败"
    }

    /// 不把底层错误文本直接抛给用户（UI 不暴露路径 / 加密细节 / 堆栈）。
    private static func friendly(_ error: Error) -> String {
        "操作失败，请确认口令正确、文件完整后重试。"
    }
}

// MARK: - 恢复引擎（第 255 轮补完）

/// 待确认的一次恢复（解密成功、用户还没点确认）。
struct PendingRestore {
    /// 解密后的明文 JSON（`BackupCrypto.decrypt` 产出就是 Data，保持同型免转码）。
    let plaintext: Data
    let summary: BackupRestorer.Summary
}

/// `.lybk` 覆盖式恢复引擎。
///
/// ## 策略（冻结自第 255 轮方案）
/// **单事务先删后插 + 保留备份原始 ID**：
/// - 先按"子表在前"的顺序 DELETE（messages → message_bodies → FTS → … → companions），
///   全部与插入包在 `pool.write` 的**同一个事务**里 —— GRDB 的 write 闭包自带事务，
///   任何一步抛错整体回滚，用户看到"当前数据保持原样"而不是半个快照。
/// - 保留原始 ID：表已清空无主键冲突，messages.companionId / anchorMessageId /
///   unified_memories.sourceId 等跨表引用**原样有效** —— 合并导入那套 name→id
///   重映射完全不需要。
///
/// ## 列名的健壮性
/// 插入列 = 行 JSON 的键 ∩ **PRAGMA table_info 实际存在的列**（按表过滤）。
/// 导出器的 JSON 键本来就是列名，但按 schema 漂移过滤后，未来加列/删列
/// 不会让旧备份恢复直接炸掉。
///
/// ## 特殊处理
/// - `apiConfigId` → nil、`lorebookIdsJson` → `"[]"`：渠道与世界书不在备份契约里，
///   恢复后引用必悬空（渠道悬空会在发消息时被路由层兜住）。
/// - `deviceId` → 本机（`DeviceIdentity.deviceId`）：召回与用量统计按设备过滤，
///   旧设备的 id 在这台机器上永远查不到。
/// - `unified_memories` 三个时间戳 0 → now：0 是"从未见过"的哨兵值，
///   恢复后会被立刻判过期。
/// - FTS **逐条重建**（`MessageRepository.upsertSearchIndex`，已改 internal）：
///   不重建的话恢复出的消息搜不到，且 FTS 里的旧 rowid 全悬空。
enum BackupRestorer {

    struct Summary {
        let sectionCounts: [String: Int]
        let fileBytes: Int
        var confirmMessage: String
    }

    struct Outcome {
        let totalRestored: Int
        let restoredSections: [String: Int]
        let wipedRows: Int
    }

    enum RestoreError: Error, CustomStringConvertible {
        case badSections
        case writeFailed(String)

        var description: String {
            switch self {
            case .badSections: return "备份内容不完整或无法解析"
            case .writeFailed(let reason): return reason
            }
        }
    }

    /// JSON 分区 → 目标表。chatMessages/groupMessages 共用 messages 表
    /// （靠 conversationType 区分，导出侧就没带这一列）。
    private static let sectionTables: [(key: String, table: String, conversationType: String?)] = [
        ("companions", "companions", nil),
        ("chatGroups", "chat_groups", nil),
        ("chatMessages", "messages", "chat"),
        ("groupMessages", "messages", "group"),
        ("memoryEntries", "memory_entries", nil),
        ("tempMemories", "temp_memory", nil),
        ("tokenUsages", "token_usage", nil),
        ("unifiedMemories", "unified_memories", nil),
        ("diaries", "diary_entries", nil),
    ]

    /// 清空顺序：引用别的表的（messages 依赖 companions.id）放后面。
    private static let wipeOrder = [
        "message_search_index", "message_bodies", "messages",
        "conversation_summary", "diary_entries", "token_usage",
        "temp_memory", "unified_memories", "memory_entries",
        "chat_groups", "companions",
    ]

    /// 解密后的明文里有没有"至少一条已知分区"—— 区分予念备份与碰巧同后缀的文件。
    static func summarize(plaintext: Data, fileBytes: Int) -> Summary? {
        guard let root = decode(plaintext) else { return nil }
        var counts: [String: Int] = [:]
        var total = 0
        for (key, _, _) in sectionTables {
            let n = (root[key] as? [[String: Any]])?.count ?? 0
            counts[key] = n
            total += n
        }
        // 空备份（全是 0 条）也接受 —— 用户可能故意导出空库再恢复。
        // 但**一个已知分区都没有**（比如根级没有 companions 键）就不是予念备份。
        guard root.keys.contains(where: { knownKeys.contains($0) }) else { return nil }

        let size = BackupReport.bytes(fileBytes)
        let detail = counts
            .filter { $0.value > 0 }
            .sorted { $0.value > $1.value }
            .map { "\(BackupReport.sectionTitle($0.key)) \(abs($0.value)) 条" }
            .prefix(3)
            .joined(separator: " · ")
        return Summary(
            sectionCounts: counts,
            fileBytes: fileBytes,
            confirmMessage: "文件 \(size)"
                + (detail.isEmpty ? "" : " · \(detail)")
                + "。当前数据会被覆盖，且无法撤销。"
        )
    }

    private static let knownKeys = Set(sectionTables.map(\.key))

    private static func decode(_ plaintext: Data) -> [String: Any]? {
        guard let root = try? JSONSerialization.jsonObject(with: plaintext) as? [String: Any]
        else { return nil }
        return root
    }

    // MARK: 恢复执行

    static func restore(plaintext: Data, database: YuNianDatabase) throws -> Outcome {
        guard let root = decode(plaintext), !root.isEmpty else {
            throw RestoreError.badSections
        }
        let localDeviceId = DeviceIdentity.deviceId
        let now = Int64(Date().timeIntervalSince1970 * 1000)

        var wiped = 0
        var restored: [String: Int] = [:]
        var total = 0

        try database.pool.write { db in
            // ① 清空（子表在前）
            for table in wipeOrder {
                try db.execute(sql: "DELETE FROM \(table)")
                wiped += db.changesCount
            }

            // ② 插入（保留原 ID）
            for (key, table, conversationType) in sectionTables {
                guard let rows = root[key] as? [[String: Any]], !rows.isEmpty else {
                    restored[key] = 0
                    continue
                }
                let allowed = try columnNames(of: table, db: db)
                for row in rows {
                    var columns: [String] = []
                    var values: [DatabaseValueConvertible] = []
                    for (rawKey, rawValue) in row {
                        guard allowed.contains(rawKey) else { continue }
                        columns.append(rawKey)
                        values.append(restoreValue(key: rawKey, value: rawValue,
                                                   table: table,
                                                   localDeviceId: localDeviceId, now: now))
                    }
                    // messages 表的 conversationType 导出侧没带，按分区补
                    if table == "messages", conversationType != nil,
                       !columns.contains("conversationType") {
                        columns.append("conversationType")
                        values.append(conversationType)
                    }
                    guard !columns.isEmpty else { continue }
                    let placeholders = values.map { _ in "?" }.joined(separator: ", ")
                    try db.execute(
                        sql: "INSERT INTO \(table) (\(columns.joined(separator: ", "))) VALUES (\(placeholders))",
                        arguments: StatementArguments(values))
                    total += 1
                }
                restored[key] = rows.count

                // ③ messages 的 bodies + FTS 逐条重建
                if table == "messages" {
                    for row in rows {
                        let id = (row["id"] as? NSNumber)?.int64Value ?? 0
                        guard id > 0 else { continue }
                        let content = row["content"] as? String ?? ""
                        let searchContent = row["searchContent"] as? String ?? ""
                        try db.execute(sql: """
                            INSERT OR REPLACE INTO message_bodies (messageId, content, searchContent, linkString)
                            VALUES (?, ?, ?, ?)
                            """, arguments: [id, content, searchContent,
                                             row["linkString"] as? String ?? ""])
                        try MessageRepository(database: database)
                            .upsertSearchIndex(db, messageId: id, searchContent: searchContent)
                    }
                }
            }
        }
        return Outcome(totalRestored: total, restoredSections: restored, wipedRows: wiped)
    }

    /// 单列值的恢复变换。NSNull → `DatabaseValue.null`（显式写 NULL，
    /// 与"该列不在行里 → 用表默认值"语义不同）。
    private static func restoreValue(key: String, value: Any, table: String,
                                     localDeviceId: String, now: Int64) -> DatabaseValueConvertible {
        if value is NSNull { return DatabaseValue.null }
        switch key {
        case "apiConfigId":
            return DatabaseValue.null   // 渠道不在备份契约里，引用必悬空 → 置空（路由层会兜）
        case "lorebookIdsJson":
            return "[]"  // 同上
        case "deviceId":
            return localDeviceId
        case "createdAt", "updatedAt", "observedAt":
            // unified_memories 的三个时间戳 0 = "从未见过"哨兵 → 恢复后立即判过期
            if table == "unified_memories",
               let n = value as? NSNumber, n.int64Value == 0 {
                return now
            }
            return wrap(value)
        default:
            return wrap(value)
        }
    }

    /// JSON 值 → GRDB 参数（NSNumber 覆盖 Bool/Int/Double；
    /// Bool 在 JSONSerialization 里就是 NSNumber，无须单独分支）。
    private static func wrap(_ value: Any) -> DatabaseValueConvertible {
        switch value {
        case let n as NSNumber: return n
        case let s as String: return s
        default: return String(describing: value)
        }
    }

    /// 目标表**实际存在**的列名集合（schema 漂移防护）。
    private static func columnNames(of table: String, db: Database) throws -> Set<String> {
        let rows = try Row.fetchAll(db, sql: "PRAGMA table_info(\(table))")
        return Set(rows.compactMap { $0["name"] as String? })
    }
}
