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
    /// 角色 id → 名字（归属列要显示名字而不是 id）。
    @State private var companionNames: [Int64: String] = [:]
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
                Text(ownerLabel(book))
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

    /// 归属文案。
    ///
    /// ⚠️ 第 219 轮：此前写的是 `角色 #7`（**id**）。用户从角色页点进来时
    /// 根本对不上号 —— 而这一列存在的全部意义就是"这本书是给谁的"。
    /// 名字读不到时才退回 id（宁可难看也不要显示成"全局"）。
    private func ownerLabel(_ book: WorldbookRepository.Book) -> String {
        guard let id = book.companionId else { return "全局" }
        return companionNames[id].map { "角色：\($0)" } ?? "角色 #\(id)"
    }

    // MARK: - 数据

    private var repo: WorldbookRepository? {
        guard let database = environment.database else { return nil }
        return WorldbookRepository(database: database)
    }

    private func reload() {
        books = repo?.list() ?? []
        activeBook = repo?.activeGlobalBook()
        // 归属要显示**角色名字**（不是 id）：这一列存在的意义就是"这本书给谁的"
        companionNames = (try? environment.companions?.fetchAll())?
            .reduce(into: [Int64: String]()) { map, companion in
                map[companion.id] = companion.name
            } ?? [:]
    }
}

// MARK: - 详情

/// 单本世界书：条目摘要 + 原始 JSON。
struct WorldbookDetailView: View {

    /// 当前这份书的最新状态（保存 / 开关后从库里重取）。
    ///
    /// ## 为什么不能拿构造时的 `book` 用到底
    /// 编辑词条改的是 `json` 列：保存后界面必须基于**新 JSON** 解码与定位，
    /// 否则数组形态的定位器（下标）会和库里错位 —— 第二次编辑就会改错行。
    @State private var currentBook: WorldbookRepository.Book
    /// 旧引用保持原样（标题 / 信息区 / 删除都读它）。
    var book: WorldbookRepository.Book { currentBook }
    let activeId: Int64?
    var onChanged: () -> Void

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    @State private var enabled: Bool
    @State private var entries: [WorldbookEntryStore.EditableEntry] = []
    /// 打开的词条 sheet（编辑 = 带定位器；新增 = .create）。
    @State private var entrySheet: EntrySheet?
    /// 待删除的词条（confirmationDialog 的锚）。
    @State private var pendingDeleteEntry: WorldbookEntryStore.EditableEntry?
    @State private var showRaw = false
    @State private var confirmDelete = false
    @State private var notice: String?

    /// 打开哪个词条表单。`.sheet(item:)` 要求 Identifiable。
    enum EntrySheet: Identifiable {
        case edit(WorldbookEntryStore.EditableEntry)
        case create

        var id: String {
            switch self {
            case .edit(let entry): return "edit-\(entry.locatorId)"
            case .create: return "create"
            }
        }

        /// 传给表单的既有条目（create 时 nil）。
        var existing: WorldbookEntryStore.EditableEntry? {
            switch self {
            case .edit(let entry): return entry
            case .create: return nil
            }
        }
    }

    /// 归属文案：显示**角色名字**（读不到才退回 id，与列表页同一条规则）。
    private var ownerText: String {
        guard let id = book.companionId else { return "全局" }
        if let companion = try? environment.companions?.fetch(id: id) {
            return "角色：\(companion.name)"
        }
        return "角色 #\(id)"
    }

    init(book: WorldbookRepository.Book, activeId: Int64?, onChanged: @escaping () -> Void) {
        self.activeId = activeId
        self.onChanged = onChanged
        _currentBook = State(initialValue: book)
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
                LabeledContent("归属", value: ownerText)
                LabeledContent("更新时间", value: Self.stamp(book.updatedAt))
                LabeledContent("JSON 大小", value: "\(book.json.utf8.count) 字节")
            } header: {
                Text("信息")
            }

            Section {
                if entries.isEmpty {
                    Text("没有解析出条目（`entries` 缺失、为空或解析失败）。这样的书启用后也不会注入任何内容 —— 可以在下面先加一条。")
                        .font(.subheadline)
                        .foregroundStyle(c.textTertiary)
                } else {
                    ForEach(entries) { entry in
                        entryRow(entry, colors: c)
                            // 点按 = 编辑（预填同一个表单）；长按菜单给"只想删"的路径
                            .contentShape(Rectangle())
                            .onTapGesture { entrySheet = .edit(entry) }
                            .contextMenu {
                                Button { entrySheet = .edit(entry) } label: {
                                    Label("编辑", systemImage: "pencil")
                                }
                                Button(role: .destructive) { pendingDeleteEntry = entry } label: {
                                    Label("删除", systemImage: "trash")
                                }
                            }
                    }
                }
                Button { entrySheet = .create } label: {
                    Label("新增词条", systemImage: "plus.circle")
                }
            } header: {
                Text("条目（按注入顺序）")
            } footer: {
                Text("顺序 = `insertion_order`。点一条可编辑；新增/编辑/删除都直接改这本书的 JSON（下一回合生效），`position` / `role` 等表单外的字段原样保留。")
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
            entries = WorldbookEntryStore.decode(json: book.json) ?? []
        }
        .onChange(of: enabled) { _, value in setEnabled(value) }
        .confirmationDialog("删除世界书「\(book.name)」？",
                            isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("删除", role: .destructive) { delete() }
            Button("取消", role: .cancel) {}
        }
        // 词条编辑/新增（第 255 轮补完）：表单见 `EntryEditView`。
        // 保存直接改这本书的 `json` 列 —— 引擎每回合覆盖式注入的就是这一列，
        // 所以改完下一回合就生效，无需额外同步。
        .sheet(item: $entrySheet) { sheet in
            EntryEditView(existing: sheet.existing) { updated in
                if sheet.existing == nil {
                    addEntry(updated)
                } else {
                    saveEntry(updated)
                }
            }
        }
        .confirmationDialog("删除这条词条？",
                            isPresented: Binding(
                                get: { pendingDeleteEntry != nil },
                                set: { if !$0 { pendingDeleteEntry = nil } }),
                            titleVisibility: .visible) {
            Button("删除", role: .destructive) { deleteEntry() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("只改这本世界书的 JSON，不影响其他书与备份。")
        }
    }

    private func entryRow(_ entry: WorldbookEntryStore.EditableEntry,
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

    // MARK: - 词条持久化（第 255 轮补完）

    /// 把新 JSON 写回这本书，并刷新本页状态。
    ///
    /// ⚠️ 必须刷新 `currentBook`（不能拿构造时的 `book` 用到底）：
    /// 编辑改的是 `json` 列，保存后定位器要基于**新 JSON** 重新解码，
    /// 否则数组形态的下标会和库里错位 —— 第二次编辑就会改错行
    /// （`currentBook` 的注释里记录过这个坑）。
    private func persist(json newJSON: String) {
        guard let database = environment.database else {
            notice = "数据库未就绪"
            return
        }
        do {
            try database.pool.write { db in
                try db.execute(sql: """
                    UPDATE worldbooks SET json = ?, updatedAt = ? WHERE id = ?
                    """, arguments: [newJSON,
                                     Int64(Date().timeIntervalSince1970 * 1000),
                                     book.id])
            }
            currentBook = WorldbookRepository(database: database)
                .list().first { $0.id == book.id } ?? currentBook
            entries = WorldbookEntryStore.decode(json: currentBook.json) ?? []
            onChanged()
            notice = "已保存（下一回合生效）"
        } catch {
            notice = "保存失败：\(error.localizedDescription)"
        }
    }

    private func saveEntry(_ entry: WorldbookEntryStore.EditableEntry) {
        guard let newJSON = WorldbookEntryStore.apply(entry, to: currentBook.json) else {
            notice = "写回失败：条目定位失效（JSON 可能被外部改过），请重新打开"
            return
        }
        persist(json: newJSON)
    }

    private func addEntry(_ entry: WorldbookEntryStore.EditableEntry) {
        guard let newJSON = WorldbookEntryStore.add(entry, to: currentBook.json) else {
            notice = "新增失败：JSON 形态不支持"
            return
        }
        persist(json: newJSON)
    }

    private func deleteEntry() {
        guard let target = pendingDeleteEntry else { return }
        pendingDeleteEntry = nil
        guard let newJSON = WorldbookEntryStore.remove(target, from: currentBook.json) else {
            notice = "删除失败：条目定位失效，请重新打开"
            return
        }
        persist(json: newJSON)
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

// MARK: - 词条编辑存储（第 255 轮补完）

/// 世界书条目的**定位 + 编辑**。
///
/// ## 为什么自己解析而不是复用 `WorldbookRuntimeSync`
/// 编辑需要**定位器**（map 形态的键 / array 形态的下标）与**写回**，
/// 而 `entrySummaries` 只产出展示用的摘要（它的 `id` 是 `"\(offset)-\(entry.id)"`，
/// 定位不了原条目）。写回还有一条硬约束：**表单外的字段原样保留**
/// （position / role / depth / scan_depth / 使用方自定义字段都不能丢）——
/// 所以这里用 `JSONSerialization` 拿到原始条目字典，只改表单管的五个字段。
///
/// ## 两种 `entries` 形态都要支持
/// ST 导出的书两种都有：map（`"0": {...}`）与 array（`[...]`）。
/// 定位器分别是 map 键与数组下标，混用会改错行。
enum WorldbookEntryStore {

    /// 可编辑的条目视图。`id` 用定位器字符串 —— 同一次解码内稳定。
    struct EditableEntry: Identifiable, Equatable {
        var id: String { locatorId }
        let locatorId: String
        /// 显示序号（解码时按 `insertion_order` 排好后回填）。
        var order: Int
        var keys: [String]
        var content: String
        var enabled: Bool
        var insertionOrder: Int
        var constant: Bool
        /// 表单外的展示信息（编辑表单不提供它们，写回时原样保留）。
        let priority: Int
        let position: String
        let depth: Int?
        let scanDepth: Int
        let caseSensitive: Bool
        let useRegex: Bool

        // 定位器（二选一非空）
        let mapKey: String?
        let arrayIndex: Int?
    }

    /// 新条目的占位（走"新增"路径）。
    static func draftEntry() -> EditableEntry {
        EditableEntry(locatorId: "new", order: 0, keys: [], content: "",
                      enabled: true, insertionOrder: 0, constant: false,
                      priority: 100, position: "before_char",
                      depth: nil, scanDepth: 10,
                      caseSensitive: false, useRegex: false,
                      mapKey: nil, arrayIndex: nil)
    }

    // MARK: 解码

    static func decode(json: String) -> [EditableEntry]? {
        guard let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rawEntries = root["entries"] else { return nil }

        var result: [EditableEntry] = []
        // map 形态：值逐个判断（整表强转 [String: [String: Any]] 会在
        // 任一值不是字典时整体失败 —— ST 导出的书里混过非字典条目）
        if let map = rawEntries as? [String: Any] {
            for (key, value) in map {
                if let dict = value as? [String: Any],
                   let entry = entry(from: dict, mapKey: key, arrayIndex: nil) {
                    result.append(entry)
                }
            }
        } else if let array = rawEntries as? [[String: Any]] {
            for (index, dict) in array.enumerated() {
                if let entry = entry(from: dict, mapKey: nil, arrayIndex: index) {
                    result.append(entry)
                }
            }
        } else {
            return nil
        }
        // 与展示层同一排序口径：insertion_order 升序，order 回填显示序号
        result.sort { $0.insertionOrder < $1.insertionOrder }
        for index in result.indices {
            result[index].order = index + 1
        }
        return result
    }

    private static func entry(from dict: [String: Any], mapKey: String?,
                              arrayIndex: Int?) -> EditableEntry? {
        let keys = (dict["keys"] as? [Any])?.compactMap { $0 as? String } ?? []
        let content = dict["content"] as? String ?? ""
        let enabled = dict["enabled"] as? Bool ?? true
        let order = (dict["insertion_order"] as? NSNumber)?.intValue ?? 0
        let constant = dict["constant"] as? Bool ?? false
        let priority = (dict["priority"] as? NSNumber)?.intValue ?? 100
        let position = dict["position"] as? String ?? ""
        let depth = (dict["depth"] as? NSNumber)?.intValue
        let scanDepth = (dict["scan_depth"] as? NSNumber)?.intValue ?? 10
        let caseSensitive = dict["case_sensitive"] as? Bool ?? false
        let useRegex = dict["use_regex"] as? Bool ?? false
        let locator = mapKey.map { "map:\($0)" } ?? "arr:\(arrayIndex ?? -1)"
        return EditableEntry(locatorId: locator, order: 0, keys: keys, content: content,
                             enabled: enabled, insertionOrder: order, constant: constant,
                             priority: priority, position: position,
                             depth: depth, scanDepth: scanDepth,
                             caseSensitive: caseSensitive, useRegex: useRegex,
                             mapKey: mapKey, arrayIndex: arrayIndex)
    }

    // MARK: 定位

    private static func locate(_ entry: EditableEntry,
                               in rawEntries: Any) -> [String: Any]? {
        if let mapKey = entry.mapKey, let map = rawEntries as? [String: Any] {
            return map[mapKey] as? [String: Any]
        }
        if let index = entry.arrayIndex, let array = rawEntries as? [[String: Any]],
           index >= 0, index < array.count {
            return array[index]
        }
        return nil
    }

    /// 把编辑后的五个字段写回条目字典（其余字段原样保留），再整体重序列化。
    private static func writing(_ entry: EditableEntry, into json: String,
                                transform: (inout [String: Any]) -> Void) -> String? {
        guard let data = json.data(using: .utf8),
              var root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              var rawEntries = root["entries"] else { return nil }

        guard var dict = locate(entry, in: rawEntries) else { return nil }
        transform(&dict)

        if entry.mapKey != nil, var map = rawEntries as? [String: Any] {
            map[entry.mapKey!] = dict
            rawEntries = map
        } else if let index = entry.arrayIndex, var array = rawEntries as? [[String: Any]],
                  index >= 0, index < array.count {
            array[index] = dict
            rawEntries = array
        } else {
            return nil
        }

        root["entries"] = rawEntries
        guard let out = try? JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted]),
              let text = String(data: out, encoding: .utf8) else { return nil }
        return text
    }

    // MARK: 写回

    /// 更新一条（五个表单字段；其余原样保留）。
    static func apply(_ entry: EditableEntry, to json: String) -> String? {
        writing(entry, into: json) { dict in
            dict["keys"] = entry.keys
            dict["content"] = entry.content
            dict["enabled"] = entry.enabled
            dict["insertion_order"] = entry.insertionOrder
            dict["constant"] = entry.constant
        }
    }

    /// 新增一条：map 形态找下一个空闲数字键，array 形态追加到尾部。
    /// `insertion_order` 取现存最大值 + 1（排在最后，用户可在编辑里改）。
    static func add(_ entry: EditableEntry, to json: String) -> String? {
        guard let data = json.data(using: .utf8),
              var root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              var rawEntries = root["entries"] else { return nil }

        var dict = entryDictionary(for: entry, existing: rawEntries)

        if var map = rawEntries as? [String: Any] {
            // 找空闲数字键（ST 惯例 "0","1",…）
            var next = 0
            while map[String(next)] != nil { next += 1 }
            map[String(next)] = dict
            rawEntries = map
        } else if var array = rawEntries as? [[String: Any]] {
            array.append(dict)
            rawEntries = array
        } else {
            return nil
        }

        root["entries"] = rawEntries
        guard let out = try? JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted]),
              let text = String(data: out, encoding: .utf8) else { return nil }
        return text
    }

    /// 删除一条（按定位器）。
    static func remove(_ target: EditableEntry, from json: String) -> String? {
        guard let data = json.data(using: .utf8),
              var root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              var rawEntries = root["entries"] else { return nil }

        if let mapKey = target.mapKey, var map = rawEntries as? [String: Any] {
            guard map[mapKey] != nil else { return nil }
            map.removeValue(forKey: mapKey)
            rawEntries = map
        } else if let index = target.arrayIndex, var array = rawEntries as? [[String: Any]],
                  index >= 0, index < array.count {
            array.remove(at: index)
            rawEntries = array
        } else {
            return nil
        }

        root["entries"] = rawEntries
        guard let out = try? JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted]),
              let text = String(data: out, encoding: .utf8) else { return nil }
        return text
    }

    private static func entryDictionary(for entry: EditableEntry,
                                        existing rawEntries: Any) -> [String: Any] {
        // 新增的 insertion_order = 现存最大值 + 1
        var maxOrder = -1
        if let map = rawEntries as? [String: Any] {
            for case let dict as [String: Any] in map.values {
                let v = (dict["insertion_order"] as? NSNumber)?.intValue ?? 0
                if v > maxOrder { maxOrder = v }
            }
        } else if let array = rawEntries as? [[String: Any]] {
            for dict in array {
                let v = (dict["insertion_order"] as? NSNumber)?.intValue ?? 0
                if v > maxOrder { maxOrder = v }
            }
        }

        return [
            // ST 惯例：条目带数字 id（map 形态里它只是冗余标识，array 形态常缺省）
            "id": maxOrder + 1,
            "keys": entry.keys,
            "content": entry.content,
            "enabled": entry.enabled,
            "insertion_order": entry.insertionOrder > maxOrder
                ? entry.insertionOrder : maxOrder + 1,
            "constant": entry.constant,
            // 新增条目给 ST 兼容的默认值；已有书的其余字段不经过这里
            "position": entry.position.isEmpty ? "before_char" : entry.position,
            "scan_depth": 10,
            "priority": entry.priority,
        ]
    }
}

// MARK: - 词条编辑表单

/// 新增 / 编辑一个词条。
///
/// ## 只开放五个字段
/// `keys` / `content` / `enabled` / `insertion_order` / `constant` 是用户会想改的；
/// `position` / `role` / `depth` / `scan_depth` 等字段背着引擎注入语义，
/// 改错会让条目静默不注入或注入位置错乱 —— 表单外字段**原样保留**。
///
/// ## 为什么 keys 用逗号分隔而不是多行
/// 与 ST 的编辑习惯一致（关键词通常两三个）；中文逗号也接受。
private struct EntryEditView: View {

    /// nil = 新增。
    let existing: WorldbookEntryStore.EditableEntry?
    /// 保存回调（新增与编辑共用：调用方按 `existing == nil` 区分走 add 还是 apply）。
    var onSaved: (WorldbookEntryStore.EditableEntry) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var keysText: String
    @State private var content: String
    @State private var enabled: Bool
    @State private var insertionOrder: Int
    @State private var constant: Bool

    init(existing: WorldbookEntryStore.EditableEntry?,
         onSaved: @escaping (WorldbookEntryStore.EditableEntry) -> Void) {
        self.existing = existing
        self.onSaved = onSaved
        let base = existing ?? WorldbookEntryStore.draftEntry()
        _keysText = State(initialValue: base.keys.joined(separator: ", "))
        _content = State(initialValue: base.content)
        _enabled = State(initialValue: base.enabled)
        _insertionOrder = State(initialValue: base.insertionOrder)
        _constant = State(initialValue: base.constant)
    }

    /// 常驻条目不靠关键词触发（footer 里说明了），所以 keys 允许为空当 constant 开着时。
    private var contentIsValid: Bool {
        !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let c = YNTheme.palette(scheme)
        return NavigationStack {
            Form {
                Section {
                    TextField("咖啡, 拿铁", text: $keysText)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("关键词（逗号分隔）")
                } footer: {
                    Text(constant
                         ? "常驻条目不扫描关键词 —— 每回合都会注入。"
                         : "消息里命中任一关键词，这条内容才会注入。留空且非常驻 = 永远不触发。")
                }

                Section {
                    TextEditor(text: $content)
                        .frame(minHeight: 120)
                } header: {
                    Text("内容")
                } footer: {
                    Text("命中的话，这段文字进入提示词。")
                }

                Section {
                    Toggle("常驻（每回合注入）", isOn: $constant)
                    Stepper(value: $insertionOrder, in: 0...9999) {
                        LabeledContent("插入顺序", value: "\(insertionOrder)")
                    }
                    Toggle("启用", isOn: $enabled)
                } header: {
                    Text("注入行为")
                } footer: {
                    Text("顺序小的先注入。禁用的条目不注入但保留在 JSON 里。")
                }
            }
            .scrollContentBackground(.hidden)
            .background(YNCanvas())
            .navigationTitle(existing == nil ? "新增词条" : "编辑词条")
            .navigationBarTitleDisplayMode(.inline)
            .tint(c.accent)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                        .disabled(!contentIsValid)
                }
            }
        }
    }

    private func save() {
        let base = existing ?? WorldbookEntryStore.draftEntry()
        // 中英文逗号都接受分隔
        let keys = keysText
            .split(whereSeparator: { $0 == "," || $0 == "，" })
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        let updated = WorldbookEntryStore.EditableEntry(
            locatorId: base.locatorId, order: base.order,
            keys: keys, content: content, enabled: enabled,
            insertionOrder: insertionOrder, constant: constant,
            priority: base.priority, position: base.position,
            depth: base.depth, scanDepth: base.scanDepth,
            caseSensitive: base.caseSensitive, useRegex: base.useRegex,
            mapKey: base.mapKey, arrayIndex: base.arrayIndex)
        onSaved(updated)
        dismiss()
    }
}
