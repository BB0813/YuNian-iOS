import SwiftUI

/// 表情导入页 —— 对应 Android `StickerManager.importStickerFile` (L318-407)。
///
/// ## 与 Android 对齐的校验（同一来源：`StickerManager` + `StickerPreferenceFacade`）
/// - 文件名格式 `custom_<ms>_<0..999>.<ext>`
/// - 按内容 SHA-256 去重（重复内容拒绝）
/// - 上限 500（Android `MAX_IMPORTED_COUNT`）
/// - 标签：填了走 normalizeTags，没填走 deriveTags
/// - 来源标记 `imported`
///
/// ## iOS 侧差异（已记录）
/// Android 还有「系统保留名」校验（保留名会被发送侧无条件跳过）。
/// iOS 的发送侧（Rust `builtin_send_sticker`）不认保留名概念，
/// 故该校验未移植 —— 不是遗漏，是无对应物。
struct StickerImportView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    @State private var pickedData: [Data] = []
    @State private var pickedExts: [String] = []
    @State private var showPicker = false

    @State private var nameDraft = ""
    @State private var tagsDraft = ""
    @State private var statusMessage: String?
    @State private var isImporting = false
    @State private var importedCount = 0

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button {
                        showPicker = true
                    } label: {
                        HStack {
                            Image(systemName: "photo.on.rectangle.angled")
                            Text(pickedData.isEmpty ? "从相册选择图片" : "重新选择（已选 \(pickedData.count) 张）")
                        }
                    }
                    .disabled(isImporting)

                    if !pickedData.isEmpty {
                        // 本地预览：让用户在导入前确认选对了图
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(Array(pickedData.enumerated()), id: \.offset) { idx, data in
                                    if let ui = UIImage(data: data) {
                                        Image(uiImage: ui)
                                            .resizable()
                                            .scaledToFill()
                                            .frame(width: 72, height: 72)
                                            .clipped()
                                            .cornerRadius(8)
                                    }
                                }
                            }
                        }
                        .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                    }
                } header: {
                    Text("图片")
                } footer: {
                    Text("支持 png / jpg / gif / webp。内容相同（SHA-256）的表情会被去重。")
                        .font(.caption2)
                }

                Section {
                    TextField("表情名", text: $nameDraft)
                        .disabled(isImporting)
                    TextField("标签（用逗号分隔，可留空）", text: $tagsDraft)
                        .disabled(isImporting)
                } header: {
                    Text("元数据")
                } footer: {
                    Text("标签留空时由表情名自动拆分生成（最多 3 个）。标签是模型挑选表情的依据，填得准更容易被用到。")
                        .font(.caption2)
                }

                if let statusMessage {
                    Section {
                        Text(statusMessage)
                            .font(.caption)
                            .foregroundStyle(importedCount > 0 ? .green : .red)
                    }
                }

                Section {
                    Button {
                        Task { await runImport() }
                    } label: {
                        HStack {
                            Text("导入")
                            if isImporting {
                                Spacer()
                                ProgressView()
                            }
                        }
                    }
                    .disabled(pickedData.isEmpty || isImporting)
                }
            }
            .navigationTitle("导入表情")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
            }
            .sheet(isPresented: $showPicker) {
                PhotoPicker(maxSelection: 20) { data, exts in
                    pickedData = data
                    pickedExts = exts
                    statusMessage = nil
                } onCancel: {
                    showPicker = false
                }
            }
        }
    }

    // MARK: - 导入

    private func runImport() async {
        guard let database = environment.database else {
            statusMessage = "数据库未就绪"
            return
        }
        isImporting = true
        importedCount = 0
        defer { isImporting = false }

        let repo = StickerImportRepository(database: database)
        let userTags = tagsDraft
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        var ok = 0
        var failures: [String] = []

        for (idx, data) in pickedData.enumerated() {
            // Android 的文件名格式：custom_<ms>_<0..999>.<ext>
            let ext = idx < pickedExts.count ? pickedExts[idx] : "png"
            let ms = Int64(Date().timeIntervalSince1970 * 1000)
            let rand = Int.random(in: 0...999)
            let fileName = "custom_\(ms)_\(rand).\(ext)"

            do {
                _ = try repo.importSticker(
                    data: data,
                    fileName: fileName,
                    // 多张时名字加序号，避免"已有同名"之外还能看出区别
                    description: pickedData.count > 1
                        ? "\(baseName) \(idx + 1)" : baseName,
                    userTags: userTags
                )
                ok += 1
            } catch let error as StickerImportRepository.ImportError {
                failures.append(error.description)
            } catch {
                failures.append(String(describing: error))
            }
        }

        importedCount = ok
        if ok > 0 {
            // 刷新首屏的表情标签展示，并重新下发给 Rust
            // （settings.stickers 是 builtin_send_sticker 的匹配依据）
            // ⚠️ 用 syncRuntimeConfig() 而非自以为存在的 refreshStickerTags()
            // —— 前者才真实存在，且做的事正是"重建 stickers 并推送"。
            environment.syncRuntimeConfig()
            statusMessage = "已导入 \(ok) 个表情" + (failures.isEmpty ? "" : "；\(failures.count) 个失败：\(failures.first!)")
        } else {
            statusMessage = failures.first ?? "导入失败"
        }
        if ok > 0 {
            pickedData = []
            nameDraft = ""
            tagsDraft = ""
        }
    }

    private var baseName: String {
        let t = nameDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? "自定义表情" : t
    }
}
