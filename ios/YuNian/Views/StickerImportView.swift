import SwiftUI

/// 表情导入页 —— 对应 Android `StickerManager.importStickerFile` (L318-407)。
///
/// ## 与 Android 对齐的校验（同一来源：`StickerManager` + `StickerPreferenceFacade`）
/// - 文件名格式 `custom_<ms>_<0..999>.<ext>`
/// - 按内容 SHA-256 去重（唯一索引 idx_sticker_entries_hash）
/// - 上限 500（Android `MAX_IMPORTED_COUNT`）
/// - 标签：填了走 normalizeTags，没填走 deriveTags
/// - 来源标记 `imported`
///
/// ## 第 129 轮：套上设计系统
/// 上一版是裸 `Form` + 系统 `TextField`，与 Android 观感无关。
/// 改用 `YuNianGlassCard` + `YuNianField` + `YuNianGlassButton`。
///
/// ## iOS 侧差异（已记录）
/// Android 还有「系统保留名」校验（保留名会被发送侧无条件跳过）。
/// iOS 的发送侧（Rust `builtin_send_sticker`）不认保留名概念，
/// 故该校验未移植 —— 不是遗漏，是无对应物。
struct StickerImportView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss
    /// ⚠️ 第 129 轮：语义色跟随系统明暗。
    @Environment(\.colorScheme) private var scheme

    @State private var pickedData: [Data] = []
    @State private var pickedExts: [String] = []
    @State private var showPicker = false

    @State private var nameDraft = ""
    @State private var tagsDraft = ""
    @State private var statusMessage: String?
    @State private var isImporting = false
    @State private var importedCount = 0

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: YuNianTheme.Space.standard) {

                    YuNianSectionTitle(title: "图片")

                    YuNianGlassCard {
                        VStack(alignment: .leading, spacing: YuNianTheme.Space.cardPadding) {
                            YuNianGlassButton(
                                onClick: { showPicker = true },
                                height: 44, horizontalPadding: 12
                            ) {
                                Image(systemName: "photo.on.rectangle.angled")
                                Text(pickedData.isEmpty
                                     ? "从相册选择图片" : "重新选择（已选 \\(pickedData.count) 张）")
                                    .font(YuNianTheme.TextStyle.cardAction)
                            }
                            .disabled(isImporting)

                            if !pickedData.isEmpty {
                                // 本地预览：让用户在导入前确认选对了图
                                ScrollView(.horizontal, showsIndicators: false) {
                                    HStack(spacing: YuNianTheme.Space.standard) {
                                        ForEach(Array(pickedData.enumerated()), id: \\.offset) { _, data in
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
                            }

                            Text("支持 png / jpg / gif / webp。内容相同（SHA-256）的表情会被去重。")
                                .font(.system(size: 11))
                                .foregroundStyle(colors.textTertiary)
                        }
                    }

                    YuNianSectionTitle(title: "元数据")

                    YuNianGlassCard {
                        VStack(alignment: .leading, spacing: YuNianTheme.Space.cardPadding) {
                            YuNianField("表情名", text: $nameDraft)
                                .disabled(isImporting)
                            YuNianField("标签（用逗号分隔，可留空）", text: $tagsDraft)
                                .disabled(isImporting)
                            Text("标签留空时由表情名自动拆分生成（最多 3 个）。标签是模型挑选表情的依据，填得准更容易被用到。")
                                .font(.system(size: 11))
                                .foregroundStyle(colors.textTertiary)
                        }
                    }

                    if let statusMessage {
                        Text(statusMessage)
                            .font(.caption)
                            .foregroundStyle(importedCount > 0 ? colors.success : colors.danger)
                            .textSelection(.enabled)
                            .padding(.horizontal, YuNianTheme.Space.minUnit)
                    }

                    YuNianGlassButton(
                        onClick: { Task { await runImport() } },
                        height: 48
                    ) {
                        Text("导入").bold()
                        if isImporting { ProgressView() }
                    }
                    .disabled(pickedData.isEmpty || isImporting)

                    Spacer(minLength: YuNianTheme.Space.pageTop)
                }
                .padding(.horizontal, YuNianTheme.Space.page)
                .padding(.top, YuNianTheme.Space.standard)
            }
            .background(colors.background.ignoresSafeArea())
            .navigationTitle("导入表情")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                        .foregroundStyle(colors.textSecondary)
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
            let fileName = "custom_\\(ms)_\\(rand).\\(ext)"

            do {
                _ = try repo.importSticker(
                    data: data,
                    fileName: fileName,
                    // 多张时名字加序号，避免"已有同名"之外还能看出区别
                    description: pickedData.count > 1 ? "\\(baseName) \\(idx + 1)" : baseName,
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
            // ⚠️ 用 syncRuntimeConfig() 而非自以为存在的 refreshStickerTags()
            environment.syncRuntimeConfig()
            statusMessage = "已导入 \\(ok) 个表情"
                + (failures.isEmpty ? "" : "；\\(failures.count) 个失败：\\(failures.first!)")
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
