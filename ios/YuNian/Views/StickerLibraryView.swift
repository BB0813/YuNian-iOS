import SwiftUI
import PhotosUI
import UIKit

/// 表情库。
///
/// ## 为什么"名称"必须让用户填
/// `StickerImportRepository` 用 `description` 派生 **tags**，而 tags 正是
/// Rust 侧 `send_sticker` 挑表情的依据（模型按标签选，不是按文件名）。
/// 所以名称不是装饰 —— **填错了模型就永远挑不中它**。
/// 也因此这里一次只导一张并要求填名，而不是批量导入一串无名文件。
///
/// ## 为什么这一页很重要
/// 在此之前 `StickerImportRepository` **没有任何调用方** ——
/// 也就是说数据库里 `sticker_entries` 恒为空，
/// 模型看得见 `send_sticker` 工具却**无表情可选**，
/// 聊天页那条"表情气泡"路径**永远走不到**。
struct StickerLibraryView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var entries: [StickerLibraryRepository.Entry] = []
    @State private var pickerItem: PhotosPickerItem?
    @State private var draftName = ""
    @State private var draftTags = ""
    @State private var status: Status?
    @State private var busy = false

    private enum Status: Equatable {
        case ok(String)
        case failure(String)
    }

    var body: some View {
        let c = YNTheme.palette(scheme)

        List {
            importSection
            librarySection
            if let status { statusSection(status, colors: c) }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("表情库")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { reload() }
        .onChange(of: pickerItem) { _, item in
            guard let item else { return }
            Task { await importPicked(item) }
        }
    }

    // MARK: - 导入

    private var importSection: some View {
        Section {
            LabeledContent("名称") {
                TextField("例如：开心", text: $draftName)
                    .multilineTextAlignment(.trailing)
                    .autocorrectionDisabled()
            }
            LabeledContent("标签") {
                TextField("可选，逗号分隔", text: $draftTags)
                    .multilineTextAlignment(.trailing)
                    .autocorrectionDisabled()
            }

            PhotosPicker(selection: $pickerItem, matching: .images) {
                HStack {
                    if busy {
                        ProgressView().controlSize(.small)
                    }
                    Text("选择图片导入")
                    Spacer()
                }
            }
            .disabled(busy || draftName.trimmingCharacters(in: .whitespaces).isEmpty)

            // 一张张选太慢，而用户手上的表情包资料是「名称 + 直链」清单。
            NavigationLink {
                StickerBulkImportView()
            } label: {
                Label("从清单批量导入", systemImage: "doc.text")
            }
        } header: {
            Text("导入")
        } footer: {
            Text("名称决定标签，而模型是**按标签**挑表情的 —— 名称填得越具体，它越容易挑中。留空标签时用名称自动拆分。")
        }
    }

    // MARK: - 已有

    private var librarySection: some View {
        let c = YNTheme.palette(scheme)
        return Section {
            if entries.isEmpty {
                Text("还没有表情。导入之后，模型才能在对话里发表情。")
                    .foregroundStyle(c.textTertiary)
            } else {
                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: 72), spacing: YNTheme.Space.sm)],
                    spacing: YNTheme.Space.sm
                ) {
                    ForEach(entries) { entry in
                        VStack(spacing: 4) {
                            stickerImage(entry)
                                .frame(width: 64, height: 64)
                                .background(c.surface,
                                            in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                            Text(entry.displayName)
                                .font(.caption2)
                                .foregroundStyle(c.textTertiary)
                                .lineLimit(1)
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityLabel("表情 \(entry.displayName)")
                    }
                }
                .padding(.vertical, YNTheme.Space.xs)
            }
        } header: {
            Text("已有表情（\(entries.count)）")
        }
    }

    /// 表情文件在 `AppPaths.stickersDirectory()/fileName`。
    ///
    /// 读不到就显示占位 —— 消息仍可见，与 Android「资源缺失时显示描述」一致。
    @ViewBuilder
    private func stickerImage(_ entry: StickerLibraryRepository.Entry) -> some View {
        if let url = try? AppPaths.stickersDirectory().appendingPathComponent(entry.fileName),
           let image = UIImage(contentsOfFile: url.path) {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
        } else {
            Image(systemName: "photo")
                .foregroundStyle(YNTheme.palette(scheme).textTertiary)
        }
    }

    @ViewBuilder
    private func statusSection(_ status: Status, colors c: YNTheme.Palette) -> some View {
        Section {
            // T6：图标 + 文字，不只靠颜色
            switch status {
            case let .ok(text):
                Label(text, systemImage: "checkmark.circle.fill")
                    .foregroundStyle(c.success)
                    .font(.subheadline)
            case let .failure(text):
                Label(text, systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(c.warning)
                    .font(.subheadline)
            }
        }
    }

    // MARK: - 逻辑

    private func reload() {
        guard let database = environment.database else { entries = []; return }
        entries = (try? StickerLibraryRepository(database: database).entries()) ?? []
    }

    private func importPicked(_ item: PhotosPickerItem) async {
        guard let database = environment.database else {
            status = .failure("数据库尚未就绪。")
            return
        }
        busy = true
        defer { busy = false; pickerItem = nil }

        guard let data = try? await item.loadTransferable(type: Data.self) else {
            status = .failure("读不到所选图片，请换一张再试。")
            return
        }

        // 文件名格式与 Android 一致：`custom_<ms>_<0..999>.<ext>`
        let ext = Self.fileExtension(for: data)
        let fileName = "custom_\(Int(Date().timeIntervalSince1970 * 1000))_\(Int.random(in: 0..<1000)).\(ext)"

        do {
            _ = try StickerImportRepository(database: database).importSticker(
                data: data,
                fileName: fileName,
                description: draftName,
                userTags: draftTags
                    .split(whereSeparator: { $0 == "," || $0 == "，" })
                    .map { $0.trimmingCharacters(in: .whitespaces) }
                    .filter { !$0.isEmpty }
            )
            let name = draftName
            draftName = ""
            draftTags = ""
            reload()
            status = .ok("已导入「\(name)」。回聊天页发条消息，模型就有表情可挑了。")
        } catch let error as StickerImportRepository.ImportError {
            status = .failure(error.description)
        } catch {
            status = .failure("导入失败，请重试。")
        }
    }

    /// 按魔数判扩展名，不信任 PhotosPicker 给的 UTType 字符串。
    private static func fileExtension(for data: Data) -> String {
        if data.starts(with: [0x89, 0x50, 0x4E, 0x47]) { return "png" }
        if data.starts(with: [0x47, 0x49, 0x46]) { return "gif" }
        if data.starts(with: [0x52, 0x49, 0x46, 0x46]) { return "webp" }
        return "jpg"
    }
}
