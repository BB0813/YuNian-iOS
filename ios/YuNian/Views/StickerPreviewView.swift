import SwiftUI
import UIKit

/// 单张表情的预览 —— 点开能看大图，并且**能改名字 / 改标签 / 删除**。
///
/// ## 为什么预览页必须有"改名"入口
/// 名称派生 tags，而 tags 是引擎 `builtin_send_sticker` **精确匹配的唯一依据** ——
/// 名字不对，模型永远挑不中这张图。所以"能看见"还不够，
/// 得能当场把名字改对（导入时贴错、或想调语气，都在这条路上）。
///
/// ## 为什么信息这么"多"
/// 这一页同时是**取证面**：这张表情多大、从哪来、被用过几次、
/// 最后什么时候用过 —— 判断"它到底工作不工作"全靠这几行。
struct StickerPreviewView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    /// 传入的那一条（只读快照；改完直接关闭，列表会重读）。
    let entry: StickerLibraryRepository.Entry
    /// 保存 / 删除成功后通知列表刷新。
    var onChanged: () -> Void

    @State private var name: String
    @State private var tags: String
    @State private var confirmDelete = false
    @State private var error: String?

    init(entry: StickerLibraryRepository.Entry, onChanged: @escaping () -> Void) {
        self.entry = entry
        self.onChanged = onChanged
        _name = State(initialValue: entry.name)
        _tags = State(initialValue: entry.tags.joined(separator: ","))
    }

    var body: some View {
        let c = YNTheme.palette(scheme)
        NavigationStack {
            List {
                imageSection(c)
                nameSection
                factsSection(c)
                dangerSection(c)
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(YNCanvas())
            .navigationTitle("预览")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .alert("删除这张表情？", isPresented: $confirmDelete) {
                Button("删除", role: .destructive) { delete() }
                Button("取消", role: .cancel) {}
            } message: {
                Text("文件会一起删掉。模型之后不能再挑中它。")
            }
        }
    }

    // MARK: - 各段

    private func imageSection(_ c: YNTheme.Palette) -> some View {
        Section {
            HStack {
                Spacer()
                image
                    .frame(maxWidth: 220, maxHeight: 220)
                    .padding(YNTheme.Space.sm)
                    .background(c.surface,
                                in: RoundedRectangle(cornerRadius: YNTheme.Radius.container,
                                                     style: .continuous))
                Spacer()
            }
            .listRowBackground(Color.clear)
        }
    }

    @ViewBuilder
    private var image: some View {
        if let url = try? AppPaths.stickersDirectory().appendingPathComponent(entry.fileName),
           let image = UIImage(contentsOfFile: url.path) {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .accessibilityLabel("表情 \(entry.displayName)")
        } else {
            // 与列表里同一套兜底：读不到就显示占位，不假装有图
            Label("文件读不到（可能被系统清理过）", systemImage: "photo")
                .font(.footnote)
                .foregroundStyle(YNTheme.palette(scheme).warning)
        }
    }

    private var nameSection: some View {
        Section {
            TextField("表情名字", text: $name)
                .textInputAutocapitalization(.never)
            TextField("标签（可选，逗号分隔）", text: $tags)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        } header: {
            Text("名字与标签")
        } footer: {
            Text("名字决定标签，模型按标签挑表情 —— 写得越贴近你会用的语境，越容易被挑中。"
                 + "留空标签时按名字自动拆分。")
        }
    }

    private func factsSection(_ c: YNTheme.Palette) -> some View {
        Section {
            LabeledContent("文件名", value: entry.fileName)
                .font(.footnote)
            LabeledContent("来源", value: entry.source == "imported" ? "导入" : entry.source)
            LabeledContent("模型用过", value: "\(entry.modelUsageCount) 次")
            LabeledContent("我发过", value: "\(entry.userUsageCount) 次")
            if let at = entry.lastUsedAt {
                LabeledContent("最近使用", value: Self.dateText(at))
            }
            if !entry.tags.isEmpty {
                LabeledContent("当前标签", value: entry.tags.joined(separator: "、"))
            }
        } header: {
            Text("这个库怎么看它")
        } footer: {
            Text("「模型用过 / 我发过」都是 0，说明它还没被挑中过 —— "
                 + "这时候先看名字和标签是不是太泛。")
        }
    }

    private func dangerSection(_ c: YNTheme.Palette) -> some View {
        Section {
            Button(role: .destructive) {
                confirmDelete = true
            } label: {
                Label("删除这张表情", systemImage: "trash")
            }
            if let error {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(c.warning)
            }
        }
    }

    // MARK: - 动作

    private func save() {
        guard let database = environment.database else {
            error = "数据库尚未就绪。"
            return
        }
        let repo = StickerImportRepository(database: database)
        do {
            try repo.renameSticker(
                id: entry.id,
                name: name,
                userTags: tags.split(whereSeparator: { $0 == "," || $0 == "，" })
                    .map { $0.trimmingCharacters(in: .whitespaces) }
                    .filter { !$0.isEmpty }
            )
            onChanged()
            environment.refreshStickerCount()
            dismiss()
        } catch {
            self.error = String(describing: error)
        }
    }

    private func delete() {
        guard let database = environment.database else {
            error = "数据库尚未就绪。"
            return
        }
        do {
            try StickerImportRepository(database: database).deleteSticker(id: entry.id)
            onChanged()
            environment.refreshStickerCount()
            dismiss()
        } catch {
            self.error = String(describing: error)
        }
    }

    private static func dateText(_ millis: Int64) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .short
        formatter.timeStyle = .short
        return formatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
    }
}
