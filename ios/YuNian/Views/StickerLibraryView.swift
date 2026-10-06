import SwiftUI

/// 表情库 —— 让用户看得见表情与标签的现状。
///
/// ## 为什么需要（第 115 轮）
/// `sticker_entries` / `sticker_tags` 两张表从 v45 起就在 schema 里，
/// `StickerTagProvider` 也读后者给 Rust 兜底，但用户侧一直没有界面。
/// 全新安装下这两张表是空的 —— 用户无从判断"没有"还是"没显示"。
///
/// ## 第 129 轮：套上设计系统
/// 上一版是 `List` + 系统样式。改用 `YuNianGlassCard` 列表 +
/// `StickerThumbnail` 缩略图网格，走语义色。
struct StickerLibraryView: View {

    @EnvironmentObject private var environment: AppEnvironment
    /// ⚠️ 第 129 轮：语义色跟随系统明暗。
    @Environment(\.colorScheme) private var scheme
    /// 第 133 轮：玻璃顶栏的返回按钮用。
    /// 这些页面由 RootView 的 NavigationLink push 进来，
    /// 系统不自动给可见返回钮，故自绘顶栏需要它。
    @Environment(\.dismiss) private var dismiss

    @State private var entries: [StickerLibraryRepository.Entry] = []
    @State private var tags: [StickerLibraryRepository.TagStat] = []
    @State private var loadError: String?
    /// 导入面板。⚠️ 第 115 轮加 —— 此前空态只能引导去备份导入。
    @State private var showImport = false

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }

    var body: some View {
        // 第 133 轮：改用 YuNianGlassPage（对照 Android GlassTopBar），
        // 不再用系统 NavigationBar。
        YuNianGlassPage(title: "表情库", onBack: { dismiss() }) {
            VStack(alignment: .leading, spacing: YuNianTheme.Space.standard) {

                if let loadError {
                    Text(loadError)
                        .font(.caption)
                        .foregroundStyle(colors.danger)
                        .textSelection(.enabled)
                        .padding(.horizontal, YuNianTheme.Space.minUnit)
                } else if entries.isEmpty {
                    emptyState
                } else {
                    YuNianSectionTitle(title: "已导入 \(entries.count) 个")

                    // 缩略图网格：一眼看全，点一下直接发
                    LazyVGrid(columns: [
                        GridItem(.adaptive(minimum: 88), spacing: YuNianTheme.Space.cardPadding)
                    ], spacing: YuNianTheme.Space.cardPadding) {
                        ForEach(entries) { entry in
                            YuNianGlassCard {
                                VStack(spacing: YuNianTheme.Space.half) {
                                    StickerThumbnail(fileName: entry.fileName)
                                        .frame(width: 64, height: 64)
                                        .cornerRadius(8)
                                    Text(entry.displayName)
                                        .font(.system(size: 11))
                                        .foregroundStyle(colors.textPrimary)
                                        .lineLimit(1)
                                    HStack(spacing: YuNianTheme.Space.standard) {
                                        Label("\(entry.userUsageCount)",
                                              systemImage: "person")
                                        Label("\(entry.modelUsageCount)",
                                              systemImage: "cpu")
                                    }
                                    .font(.system(size: 9))
                                    .foregroundStyle(colors.textTertiary)
                                }
                            }
                        }
                    }

                    // 标签统计：与 Android 排序一致（stickerCount DESC, tag ASC）
                    if !tags.isEmpty {
                        YuNianSectionTitle(title: "标签统计（按图片数降序）")
                        YuNianGlassCard {
                            VStack(alignment: .leading, spacing: YuNianTheme.Space.half) {
                                ForEach(tags) { stat in
                                    HStack {
                                        Text(stat.tag)
                                            .font(.system(size: 13))
                                            .foregroundStyle(colors.textPrimary)
                                        Spacer()
                                        Text("\(stat.stickerCount)")
                                            .font(.system(size: 12).monospacedDigit())
                                            .foregroundStyle(colors.textSecondary)
                                    }
                                    .padding(.vertical, YuNianTheme.Space.tight)
                                }
                            }
                        }
                        Text("这些标签经 settings.stickers 下发给 Rust，供 builtin_send_sticker 做精确匹配。")
                            .font(.system(size: 11))
                            .foregroundStyle(colors.textTertiary)
                            .padding(.horizontal, YuNianTheme.Space.minUnit)
                    }
                }

                Spacer(minLength: YuNianTheme.Space.pageTop)
            }
            .padding(.horizontal, YuNianTheme.Space.page)
            .padding(.top, YuNianTheme.Space.standard)
        }
        .toolbar {
            // 第 115 轮：列表顶部也放一个导入按钮。
            // 只在空态放的话，导入了第一批之后用户就找不到入口了。
            if environment.database != nil {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        showImport = true
                    } label: {
                        Image(systemName: "plus")
                    }
                    .foregroundStyle(colors.primary)
                }
            }
        }
        .task { reload() }
        .sheet(isPresented: $showImport) {
            StickerImportView()
        }
        // 导入完成后重新读取（sheet dismiss 时刷新）
        .onChange(of: showImport) { _, shown in
            if !shown { reload() }
        }
    }

    // MARK: - 空态

    private var emptyState: some View {
        VStack(spacing: YuNianTheme.Space.cardPadding) {
            Image(systemName: "face.smiling")
                .font(.system(size: 40))
                .foregroundStyle(colors.textTertiary)
            Text("还没有表情")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(colors.textPrimary)
            Text("全新安装下为空是正常的：目前只能通过相册导入或备份导入获得，导入后这里会列出。")
                .font(.system(size: 12))
                .foregroundStyle(colors.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, YuNianTheme.Space.page)

            YuNianGlassButton(onClick: { showImport = true }, height: 44) {
                Label("从相册导入表情", systemImage: "plus.circle")
            }
            .padding(.horizontal, YuNianTheme.Space.page)

            if environment.database != nil {
                NavigationLink("或从备份导入") { BackupImportView() }
                    .font(YuNianTheme.TextStyle.settingsRowSubtitle)
                    .foregroundStyle(colors.primary)
            }
        }
        .padding(.top, YuNianTheme.Space.pageTop)
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
