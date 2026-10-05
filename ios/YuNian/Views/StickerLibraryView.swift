import SwiftUI

/// 表情库 —— 让用户看得见表情与标签的现状。
///
/// ## 为什么需要（第 113 轮）
/// `sticker_entries` / `sticker_tags` 两张表从 v45 起就在 schema 里，
/// `StickerTagProvider` 也读 `sticker_tags` 给 Rust 做兜底，
/// 但**用户侧没有任何界面**。全新安装下这两张表是空的 ——
/// 用户既不知道表情从哪来，也无法判断"是没有"还是"没显示"。
///
/// 本页只做读：列出表情 + 标签统计，并说明空态的原因。
struct StickerLibraryView: View {

    @EnvironmentObject private var environment: AppEnvironment

    @State private var entries: [StickerLibraryRepository.Entry] = []
    @State private var tags: [StickerLibraryRepository.TagStat] = []
    @State private var loadError: String?

    var body: some View {
        Group {
            if let loadError {
                errorState(loadError)
            } else if entries.isEmpty {
                emptyState
            } else {
                list
            }
        }
        .navigationTitle("表情库")
        .navigationBarTitleDisplayMode(.inline)
        .task { reload() }
    }

    // MARK: - 列表

    private var list: some View {
        List {
            Section {
                ForEach(entries) { entry in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(entry.displayName)
                            .font(.body)
                            .lineLimit(2)

                        if !entry.tags.isEmpty {
                            // 标签是模型选表情的依据，直接展示便于核对
                            Text(entry.tags.joined(separator: "、"))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        HStack(spacing: 12) {
                            Label("用户 \(entry.userUsageCount)", systemImage: "person")
                            Label("模型 \(entry.modelUsageCount)", systemImage: "cpu")
                            if !entry.source.isEmpty {
                                Label(entry.source, systemImage: "tray")
                            }
                        }
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                    }
                    .padding(.vertical, 2)
                }
            } header: {
                Text("已导入 \(entries.count) 个")
            }

            if !tags.isEmpty {
                Section {
                    ForEach(tags) { stat in
                        HStack {
                            Text(stat.tag)
                            Spacer()
                            Text("\(stat.stickerCount)")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                    }
                } header: {
                    Text("标签统计（按图片数降序）")
                } footer: {
                    Text("这些标签经 settings.stickers 下发给 Rust，供 builtin_send_sticker 做精确匹配。")
                        .font(.caption2)
                }
            }
        }
    }

    // MARK: - 空态 / 错误态

    private var emptyState: some View {
        VStack(spacing: 14) {
            Image(systemName: "face.smiling")
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text("还没有表情")
                .font(.headline)
            Text("全新安装下为空是正常的：目前只能通过备份导入获得表情，导入后这里会列出。")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            if environment.database != nil {
                NavigationLink("去备份导入") { BackupImportView() }
                    .buttonStyle(.bordered)
            }
        }
    }

    private func errorState(_ message: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.largeTitle)
                .foregroundStyle(.red)
            Text("读取失败")
                .font(.headline)
            Text(message)
                .font(.caption)
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
            Button("重试") { reload() }
        }
    }

    // MARK: - 数据

    private func reload() {
        guard let database = environment.database else {
            loadError = "数据库未就绪"
            return
        }
        do {
            let snapshot = try StickerLibraryRepository(database: database).snapshot()
            entries = snapshot.entries
            tags = snapshot.tags
            loadError = nil
        } catch {
            entries = []
            tags = []
            loadError = String(describing: error)
        }
    }
}
