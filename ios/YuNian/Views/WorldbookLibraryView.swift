import SwiftUI

/// 世界书页 —— 「模型知道哪些设定」。
///
/// ## 为什么之前没有这一页（与技能页同一种病，但更隐蔽）
/// 表在、`WorldbookRuntimeSync` 每回合也会把它推给引擎 ——
/// 但 **iOS 没有任何界面，也没有任何东西写这张表**，
/// 于是世界书恒为空：引擎每回合拿到的都是 `nil`（不注入），
/// 而界面上**看不出任何异常**（不像技能至少有个失败的工具调用）。
///
/// ## ⚠️ 引擎只取"第一本启用的"
/// 全局路径的查询是 `SELECT json FROM worldbooks WHERE enabled = 1 LIMIT 1`
/// （见 `WorldbookRuntimeSync.activeGlobalBookJson`），
/// 所以同时启用多本时**只有一本生效**，而且哪一本取决于行序 ——
/// 这是跨端一致的既有语义（Android 的 `WorldbookDao.active()` 同样是 `LIMIT 1`）。
/// 本页不偷偷改用户的开关，而是**把当前生效的那本显出来 + 多本时给一键处置**。
struct WorldbookLibraryView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var books: [WorldbookRepository.Book] = []
    @State private var activeBook: WorldbookRepository.Book?
    @State private var showImporter = false
    @State private var pendingDelete: WorldbookRepository.Book?

    var body: some View {
        let c = YNTheme.palette(scheme)
        let enabledCount = books.filter(\.enabled).count
        return List {
            if books.isEmpty {
                emptySection(c)
            } else {
                activeSection(c)
                if enabledCount > 1 { conflictSection(c, enabledCount: enabledCount) }
                Section {
                    ForEach(books) { book in
                        NavigationLink {
                            WorldbookDetailView(book: book, activeId: activeBook?.id,
                                                onChanged: reload)
                        } label: {
                            row(book, colors: c)
                        }
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) {
                                pendingDelete = book
                            } label: {
                                Label("删除", systemImage: "trash")
                            }
                        }
                    }
                } header: {
                    Text("全部世界书（\(books.count)）")
                } footer: {
                    Text("""
                    条目来自 `worldbooks.json` 这一列，**不是** `lorebook_entries` 表 ——
                    后者只是结构化编辑器用的视图，不参与合成。
                    条目按 `priority` 降序注入；同一段内容只保留优先级最高的一条。
                    ⚠️ 全局路径的查询**不按归属过滤**（与引擎、Android 一致）：
                    没有绑定角色时，一本启用的「角色专属」书也会被注入。
                    """)
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("世界书")
        .navigationBarTitleDisplayMode(.inline)
        .tint(c.accent)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showImporter = true } label: {
                    Label("导入", systemImage: "plus")
                }
            }
        }
        .onAppear(perform: reload)
        .sheet(isPresented: $showImporter) {
            NavigationStack {
                WorldbookImportView(onSaved: reload)
            }
        }
        .confirmationDialog("删除世界书「\(pendingDelete?.name ?? "")」？",
                            isPresented: Binding(get: { pendingDelete != nil },
                                                 set: { if !$0 { pendingDelete = nil } }),
                            titleVisibility: .visible) {
            Button("删除", role: .destructive) {
                if let target = pendingDelete {
                    _ = repo?.delete(id: target.id)
                    reload()
                }
                pendingDelete = nil
            }
            Button("取消", role: .cancel) { pendingDelete = nil }
        } message: {
            Text("只删本机的这一份，不影响已导入的备份。")
        }
    }

    // MARK: - 分节

    private func activeSection(_ c: YNTheme.Palette) -> some View {
        Section {
            if let active = activeBook {
                LabeledContent("当前生效", value: "\(active.name)（\(active.entryCount) 条）")
            } else {
                Text("没有任何世界书启用 —— 引擎这一回合不注入世界书（这是正常状态）。")
                    .font(.subheadline)
                    .foregroundStyle(c.textSecondary)
            }
        } header: {
            Text("注入状态")
        } footer: {
            Text("每回合前把「当前生效」的那一本覆盖式推给引擎。想确认某一轮到底带了哪些片段，去聊天页长按回复看「本轮收据」（来源为「世界书」的那些）。")
        }
    }

    private func conflictSection(_ c: YNTheme.Palette, enabledCount: Int) -> some View {
        Section {
            Label("有 \(enabledCount) 本同时启用，但引擎只取**第一本**。", systemImage: "exclamationmark.triangle")
                .font(.subheadline)
                .foregroundStyle(c.warning)
            if let active = activeBook {
                Button("只保留「\(active.name)」启用") {
                    _ = repo?.enableOnly(id: active.id)
                    reload()
                }
            }
        } header: {
            Text("多本冲突")
        } footer: {
            Text("这是引擎与 Android 一致的既有语义（`LIMIT 1`），不是 bug。要么手动关掉多余的，要么用上面的按钮一键只留生效的那本。")
        }
    }

    private func emptySection(_ c: YNTheme.Palette) -> some View {
        Section {
            Text("还没有世界书。")
                .font(.subheadline)
                .foregroundStyle(c.textSecondary)
            Text("""
            导入一份世界书 JSON（含 `entries`；ST 的 map 与 array 两种形态都支持）之后，
            启用的那一本会在**每回合前**注入：命中关键词的条目进入提示词。
            """)
            .font(.caption)
            .foregroundStyle(c.textTertiary)
            .fixedSize(horizontal: false, vertical: true)
            Button { showImporter = true } label: {
                Label("导入世界书", systemImage: "square.and.arrow.down")
            }
        } header: {
            Text("空")
        } footer: {
            Text("「从角色卡导入」（读 `companions.worldJson` 的 `character_book`）与逐条结构化编辑都还没做 —— 页脚写在这里，将来接在这儿，先不放点不动的入口。")
        }
    }

    private func row(_ book: WorldbookRepository.Book, colors c: YNTheme.Palette) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: YNTheme.Space.xs) {
                Text(book.name)
                    .font(.subheadline)
                    .foregroundStyle(book.enabled ? c.textPrimary : c.textTertiary)
                if book.id == activeBook?.id {
                    Text("生效中")
                        .font(.caption2)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(c.surface, in: Capsule())
                        .foregroundStyle(c.accent)
                }
            }
            HStack(spacing: YNTheme.Space.sm) {
                Text("\(book.entryCount) 条")
                Text(book.companionId == nil ? "全局" : "角色 #\(book.companionId ?? 0)")
                if !book.isParsable { Text("JSON 解析失败").foregroundStyle(c.warning) }
                Spacer()
                Toggle("", isOn: Binding(
                    get: { book.enabled },
                    set: { value in
                        _ = repo?.setEnabled(value, id: book.id)
                        reload()
                    }
                ))
                .labelsHidden()
            }
            .font(.caption2)
            .foregroundStyle(c.textTertiary)
        }
        .padding(.vertical, 2)
    }

    // MARK: - 数据

    private var repo: WorldbookRepository? {
        guard let database = environment.database else { return nil }
        return WorldbookRepository(database: database)
    }

    private func reload() {
        books = repo?.list() ?? []
        activeBook = repo?.activeGlobalBook()
    }
}

// MARK: - 详情

/// 单本世界书：条目摘要 + 原始 JSON。
struct WorldbookDetailView: View {

    let book: WorldbookRepository.Book
    let activeId: Int64?
    var onChanged: () -> Void

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    @State private var enabled: Bool
    @State private var entries: [WorldbookRuntimeSync.EntrySummary] = []
    @State private var showRaw = false
    @State private var confirmDelete = false
    @State private var notice: String?

    init(book: WorldbookRepository.Book, activeId: Int64?, onChanged: @escaping () -> Void) {
        self.book = book
        self.activeId = activeId
        self.onChanged = onChanged
        _enabled = State(initialValue: book.enabled)
    }

    var body: some View {
        let c = YNTheme.palette(scheme)
        return List {
            Section {
                Toggle("启用", isOn: $enabled)
                LabeledContent("引擎状态") {
                    if book.id == activeId && enabled {
                        Label("正在注入", systemImage: "checkmark.circle")
                            .foregroundStyle(c.accent)
                    } else if enabled {
                        Text("已启用，但不是引擎取的那一本")
                            .foregroundStyle(c.warning)
                    } else {
                        Text("不注入")
                    }
                }
                if let notice { Text(notice).font(.caption).foregroundStyle(c.textSecondary) }
            } header: {
                Text("状态")
            } footer: {
                Text("引擎全局路径取「第一本启用的」（`LIMIT 1`）。多本同时启用时只有一本生效，所以「已启用」不等于「正在注入」。")
            }

            Section {
                LabeledContent("条目数", value: "\(entries.count)")
                LabeledContent("归属", value: book.companionId.map { "角色 #\($0)" } ?? "全局")
                LabeledContent("更新时间", value: Self.stamp(book.updatedAt))
                LabeledContent("JSON 大小", value: "\(book.json.utf8.count) 字节")
            } header: {
                Text("信息")
            }

            Section {
                if entries.isEmpty {
                    Text("没有解析出条目（`entries` 缺失或为空）。这样的书启用后也不会注入任何内容。")
                        .font(.subheadline)
                        .foregroundStyle(c.textTertiary)
                } else {
                    ForEach(entries) { entry in
                        entryRow(entry, colors: c)
                    }
                }
            } header: {
                Text("条目（按注入顺序）")
            } footer: {
                Text("顺序 = `insertion_order`（引擎合成时会按 priority 重排并全局重编号）。界面按**源 JSON 的顺序**列出。")
            }

            Section {
                DisclosureGroup("原始 JSON", isExpanded: $showRaw) {
                    Text(book.json)
                        .font(.caption2.monospaced())
                        .textSelection(.enabled)
                }
            } footer: {
                Text("这一列就是推给引擎的内容（`setWorldbook` 覆盖式注入）。")
            }

            Section {
                Button(role: .destructive) { confirmDelete = true } label: {
                    Label("删除这本世界书", systemImage: "trash")
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle(book.name)
        .navigationBarTitleDisplayMode(.inline)
        .tint(c.accent)
        .onAppear {
            entries = WorldbookRuntimeSync.entrySummaries(book.json)
        }
        .onChange(of: enabled) { _, value in setEnabled(value) }
        .confirmationDialog("删除世界书「\(book.name)」？",
                            isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("删除", role: .destructive) { delete() }
            Button("取消", role: .cancel) {}
        }
    }

    private func entryRow(_ entry: WorldbookRuntimeSync.EntrySummary,
                          colors c: YNTheme.Palette) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: YNTheme.Space.xs) {
                Text("#\(entry.order)").font(.caption.monospacedDigit()).foregroundStyle(c.textTertiary)
                if entry.constant {
                    Text("常驻").font(.caption2)
                        .padding(.horizontal, 5).padding(.vertical, 1)
                        .background(c.surface, in: Capsule())
                        .foregroundStyle(c.textSecondary)
                } else if entry.keys.isEmpty {
                    Text("无关键词").font(.caption2).foregroundStyle(c.warning)
                } else {
                    Text(entry.keys.joined(separator: " / ")).font(.caption2).foregroundStyle(c.textSecondary)
                }
                if !entry.enabled {
                    Text("已禁用").font(.caption2).foregroundStyle(c.textTertiary)
                }
                Spacer()
                Text("P\(entry.priority)").font(.caption2.monospacedDigit()).foregroundStyle(c.textTertiary)
            }
            Text(entry.content.isEmpty ? "（空内容）" : entry.content)
                .font(.caption)
                .foregroundStyle(entry.content.isEmpty ? c.textTertiary : c.textPrimary)
                .lineLimit(3)
            HStack(spacing: YNTheme.Space.sm) {
                if !entry.position.isEmpty { Text("位置 \(entry.position)") }
                if let depth = entry.depth { Text("深度 \(depth)") }
                Text("扫描 \(entry.scanDepth)")
                if entry.caseSensitive { Text("区分大小写") }
                if entry.useRegex { Text("正则") }
            }
            .font(.caption2)
            .foregroundStyle(c.textTertiary)
        }
        .padding(.vertical, 2)
    }

    private func setEnabled(_ value: Bool) {
        guard let database = environment.database else { return }
        let ok = WorldbookRepository(database: database).setEnabled(value, id: book.id)
        notice = ok ? (value ? "已启用（下一回合生效）" : "已停用（下一回合生效）")
                    : "改动没写进库：这本可能已经不在了"
        onChanged()
    }

    private func delete() {
        guard let database = environment.database else { return }
        _ = WorldbookRepository(database: database).delete(id: book.id)
        onChanged()
        dismiss()
    }

    private static func stamp(_ ms: Int64) -> String {
        guard ms > 0 else { return "（未知）" }
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(ms) / 1000))
    }
}

// MARK: - 导入

/// 粘贴一份世界书 JSON 导入。
///
/// ## 为什么先做"粘贴"
/// 文件导入要处理安全作用域 URL；结构化逐条编辑要先把 `entryToJson` 的
/// 13 个字段写回契约完整移植（否则会把用户的条目写坏）。两者都留给后续，
/// 这一版只保证**入库这条路是对的**：校验 → 落库 → 下回合生效。
struct WorldbookImportView: View {

    var onSaved: () -> Void

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var scope: Scope = .global
    @State private var companionId: Int64?
    @State private var draft = ""
    @State private var notice: String?

    enum Scope: String, CaseIterable, Identifiable {
        case global, companion
        var id: String { rawValue }
        var title: String { self == .global ? "全局" : "角色专属" }
    }

    var body: some View {
        let c = YNTheme.palette(scheme)
        let entries = WorldbookRuntimeSync.entrySummaries(draft)
        let parsable = WorldbookRuntimeSync.looksLikeWorldbook(draft)
        let suggested = WorldbookRuntimeSync.bookName(draft)
        return List {
            Section {
                LabeledContent("名称") {
                    TextField(suggested.isEmpty ? "世界书" : suggested, text: $name)
                        .multilineTextAlignment(.trailing)
                }
                Picker("归属", selection: $scope) {
                    ForEach(Scope.allCases) { Text($0.title).tag($0) }
                }
                if scope == .companion {
                    Picker("角色", selection: $companionId) {
                        Text("请选择").tag(Int64?.none)
                        ForEach(companionList, id: \.id) { companion in
                            Text(companion.name).tag(Int64?.some(companion.id))
                        }
                    }
                }
            } header: {
                Text("基本信息")
            } footer: {
                Text("全局书在「每回合前」都会被推给引擎（取第一本启用的）；角色专属书只在按伴侣合成时参与。")
            }

            Section {
                TextEditor(text: $draft)
                    .font(.caption.monospaced())
                    .frame(minHeight: 240)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button { draft = Self.template } label: {
                    Label("填入示例", systemImage: "doc.text")
                }
            } header: {
                Text("世界书 JSON")
            } footer: {
                if draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Text("粘贴含 `entries` 的 JSON。")
                } else if parsable {
                    Text("解析成功：\(entries.count) 条条目，其中启用 \(entries.filter(\.enabled).count) 条。")
                } else {
                    Text("解析失败：顶层没有 `entries` —— 这不是一本世界书（ST 的 map 与 array 两种 `entries` 形态都支持）。")
                        .foregroundStyle(c.warning)
                }
            }

            if let notice {
                Section { Text(notice).font(.caption).foregroundStyle(c.textSecondary) }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("导入世界书")
        .navigationBarTitleDisplayMode(.inline)
        .tint(c.accent)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("保存") { save() }
                    .disabled(!WorldbookRuntimeSync.looksLikeWorldbook(draft)
                              || (scope == .companion && companionId == nil))
            }
        }
    }

    private var companionList: [CompanionRepository.Companion] {
        guard let repo = environment.companions else { return [] }
        return (try? repo.fetchAll()) ?? []
    }

    private func save() {
        guard let database = environment.database else {
            notice = "运行环境未就绪"
            return
        }
        let finalName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let id = WorldbookRepository(database: database).save(
            name: finalName.isEmpty ? WorldbookRuntimeSync.bookName(draft) : finalName,
            json: draft,
            companionId: scope == .companion ? companionId : nil
        )
        guard id > 0 else {
            notice = "保存失败（见 App 日志 repo.worldbook）"
            return
        }
        onSaved()
        dismiss()
    }

    /// 示例（自己写的）。含一个关键词条目 + 一个常驻条目，便于看出两者差别。
    private static let template = """
    {
      "name": "示例世界书",
      "entries": {
        "0": {
          "id": 0,
          "keys": ["咖啡", "拿铁"],
          "content": "她只知道用户爱喝美式，别的咖啡一律记不住。",
          "enabled": true,
          "insertion_order": 0,
          "constant": false,
          "position": "before_char",
          "scan_depth": 10
        },
        "1": {
          "id": 1,
          "keys": [],
          "content": "无论聊什么，她都不会主动提起「搬家」这件事。",
          "enabled": true,
          "insertion_order": 1,
          "constant": true,
          "position": "after_char",
          "scan_depth": 10
        }
      },
      "scan_depth": 10,
      "token_budget": 10000
    }
    """
}
