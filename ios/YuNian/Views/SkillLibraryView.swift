import SwiftUI

/// 技能页 —— 「模型能按需加载哪些操作说明」。
///
/// ## 为什么之前没有这一页（以及为什么它必须有）
/// 表与存储层都在（`agent_skills` + `AgentStores.saveSkill/deleteSkill`），
/// 但**没有任何界面调用**，也没有导入路径 → 技能表恒为空，
/// `load_skill` 永远返回「未找到技能」，提示词里的技能目录菜单也永远是空的。
/// **模型看到 `load_skill` 这个工具，却永远加载不到东西** —— 这是死链，不是缺页面。
///
/// ## 这一页做四件事
/// 1. **看**：名称/描述/分类/标签/正文长度/版本/归属
/// 2. **开关**：`enabled` 是真开关（Rust 三处 `filter(|m| m.enabled)`）
/// 3. **导入**：粘贴一份 SKILL.md（frontmatter + 正文）→ 走 `saveSkill` 落库落文件
/// 4. **删**：走 `deleteSkill`（它会同时删索引与文件）
///
/// ## 留好的接入位
/// 「从文件导入」与「技能市场」都**没有**做（前者要处理安全作用域 URL，
/// 后者要一套远端协议与鉴权）。页脚写明了它们将来接在哪，**不放假入口**。
struct SkillLibraryView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var skills: [SkillRepository.Skill] = []
    @State private var showImporter = false
    @State private var pendingDelete: SkillRepository.Skill?

    var body: some View {
        let c = YNTheme.palette(scheme)
        return List {
            if skills.isEmpty {
                emptySection(c)
            } else {
                overviewSection(c)
                ForEach(skills) { skill in
                    NavigationLink {
                        SkillDetailView(skill: skill, onChanged: reload)
                    } label: {
                        row(skill, colors: c)
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            pendingDelete = skill
                        } label: {
                            Label("删除", systemImage: "trash")
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("技能")
        .navigationBarTitleDisplayMode(.inline)
        .tint(c.accent)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showImporter = true
                } label: {
                    Label("导入", systemImage: "plus")
                }
            }
        }
        .onAppear(perform: reload)
        .sheet(isPresented: $showImporter) {
            NavigationStack {
                SkillImportView(onSaved: reload)
            }
        }
        .confirmationDialog("删除技能「\(pendingDelete?.name ?? "")」？",
                            isPresented: Binding(get: { pendingDelete != nil },
                                                 set: { if !$0 { pendingDelete = nil } }),
                            titleVisibility: .visible) {
            Button("删除", role: .destructive) {
                if let target = pendingDelete { delete(target) }
                pendingDelete = nil
            }
            Button("取消", role: .cancel) { pendingDelete = nil }
        } message: {
            Text("会同时删除索引与正文文件，不可恢复。")
        }
    }

    // MARK: - 分节

    private func overviewSection(_ c: YNTheme.Palette) -> some View {
        let counts = skillRepo?.counts() ?? (all: 0, enabled: 0, disabled: 0)
        return Section {
            LabeledContent("技能总数", value: "\(counts.all)")
            LabeledContent("已启用", value: "\(counts.enabled)")
            LabeledContent("已禁用", value: "\(counts.disabled)")
        } header: {
            Text("概览")
        } footer: {
            Text("""
            技能 = 一段操作说明。模型在需要时用 `load_skill` **按需加载**，
            所以它平时不占上下文，只在用到的回合进入提示词。
            被禁用的技能：既不进技能目录、也不回应 `load_skill`，但正文仍留在本机。
            """)
        }
    }

    private func emptySection(_ c: YNTheme.Palette) -> some View {
        Section {
            Text("还没有技能。")
                .font(.subheadline)
                .foregroundStyle(c.textSecondary)
            Text("""
            导入一份 SKILL.md（frontmatter 写 name / description，正文写操作说明）之后：
            它会出现在模型的[可用技能目录]里，模型需要时用 `load_skill` 加载。
            """)
            .font(.caption)
            .foregroundStyle(c.textTertiary)
            .fixedSize(horizontal: false, vertical: true)
            Button {
                showImporter = true
            } label: {
                Label("导入技能", systemImage: "square.and.arrow.down")
            }
        } header: {
            Text("空")
        } footer: {
            Text("将来可以在这里接「从文件导入」与「技能市场」—— 现在只有粘贴导入，所以不放那两行入口。")
        }
    }

    private func row(_ skill: SkillRepository.Skill, colors c: YNTheme.Palette) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: YNTheme.Space.xs) {
                Text(skill.name)
                    .font(.subheadline)
                    .foregroundStyle(skill.enabled ? c.textPrimary : c.textTertiary)
                if !skill.enabled {
                    Text("已禁用")
                        .font(.caption2)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(c.surface, in: Capsule())
                        .foregroundStyle(c.textTertiary)
                }
            }
            if !skill.description.isEmpty {
                Text(skill.description)
                    .font(.caption)
                    .foregroundStyle(c.textSecondary)
                    .lineLimit(2)
            }
            HStack(spacing: YNTheme.Space.sm) {
                Text(skill.category)
                if skill.companionId != nil { Text("角色专属") }
                ForEach(skill.tagList.prefix(3), id: \.self) { tag in
                    Text("#\(tag)")
                }
                Spacer()
                Text("v\(skill.version) · \(skill.contentLength) 字节")
            }
            .font(.caption2)
            .foregroundStyle(c.textTertiary)
        }
        .padding(.vertical, 2)
    }

    // MARK: - 数据

    private func reload() {
        skills = skillRepo?.listAll() ?? []
    }

    /// 仓储（数据库未就绪时为 nil —— 启动早期界面照常渲染空态，不崩）。
    private var skillRepo: SkillRepository? {
        guard let database = environment.database else { return nil }
        return SkillRepository(database: database)
    }

    /// 删除走 `AgentStores.deleteSkill` —— 它负责**索引与文件一起删**，
    /// 自己写 SQL 会留下孤儿正文文件（下次同 id 导入时哈希对不上）。
    private func delete(_ skill: SkillRepository.Skill) {
        guard let stores = environment.stores else { return }
        _ = stores.deleteSkill(skillId: skill.skillId)
        reload()
    }
}

// MARK: - 技能详情

/// 单个技能的正文与元信息。
///
/// ## 为什么把「校验」单列出来
/// 引擎读正文时会**无条件比对 SHA-256**，不一致就返回 nil ——
/// 表现是模型说「未找到技能」。不把这一项显出来，
/// 用户只会看到"技能明明在列表里，模型却说没有"。
struct SkillDetailView: View {

    let skill: SkillRepository.Skill
    /// 改动（开关/删除）后通知列表刷新。
    var onChanged: () -> Void

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    @State private var content: SkillRepository.Content?
    @State private var enabled: Bool
    @State private var confirmDelete = false
    @State private var notice: String?

    init(skill: SkillRepository.Skill, onChanged: @escaping () -> Void) {
        self.skill = skill
        self.onChanged = onChanged
        _enabled = State(initialValue: skill.enabled)
    }

    var body: some View {
        let c = YNTheme.palette(scheme)
        return List {
            Section {
                Toggle("启用", isOn: $enabled)
                if let notice {
                    Text(notice).font(.caption).foregroundStyle(c.textSecondary)
                }
            } header: {
                Text("状态")
            } footer: {
                Text(enabled
                     ? "启用中：会出现在模型的技能目录里，`load_skill` 可加载。"
                     : "已禁用：不进技能目录、`load_skill` 也不回应。正文仍留在本机。")
            }

            Section {
                LabeledContent("技能 ID", value: skill.skillId)
                LabeledContent("分类", value: skill.category)
                LabeledContent("版本", value: "v\(skill.version)")
                LabeledContent("正文长度", value: "\(skill.contentLength) 字节")
                LabeledContent("归属", value: skill.companionId.map { "角色 #\($0)" } ?? "全局")
                if !skill.tagList.isEmpty {
                    LabeledContent("标签", value: skill.tagList.joined(separator: "、"))
                }
                if !skill.toolList.isEmpty {
                    LabeledContent("关联工具", value: skill.toolList.joined(separator: "、"))
                }
                LabeledContent("完整性校验") {
                    if let content {
                        Label(content.hashMatches ? "一致" : "不一致",
                              systemImage: content.hashMatches ? "checkmark.seal" : "exclamationmark.triangle")
                            .foregroundStyle(content.hashMatches ? c.textSecondary : c.warning)
                    } else {
                        Text("正文文件读不到").foregroundStyle(c.warning)
                    }
                }
            } header: {
                Text("信息")
            } footer: {
                Text("引擎读取时会比对 SHA-256；「不一致」或「读不到」时 `load_skill` 会返回未找到 —— 重新导入同一份正文即可修好。")
            }

            Section {
                if let content {
                    Text(content.text)
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                        .foregroundStyle(c.textPrimary)
                } else {
                    Text("读不到正文文件（可能已被删除，或路径不可解析）。")
                        .font(.subheadline)
                        .foregroundStyle(c.textTertiary)
                }
            } header: {
                Text("正文（模型加载后看到的内容）")
            }

            Section {
                Button(role: .destructive) {
                    confirmDelete = true
                } label: {
                    Label("删除这个技能", systemImage: "trash")
                }
            } footer: {
                Text("会同时删除索引与正文文件，不可恢复。")
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle(skill.name)
        .navigationBarTitleDisplayMode(.inline)
        .tint(c.accent)
        .onAppear(perform: load)
        .onChange(of: enabled) { _, value in setEnabled(value) }
        .confirmationDialog("删除技能「\(skill.name)」？",
                            isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("删除", role: .destructive) { delete() }
            Button("取消", role: .cancel) {}
        }
    }

    private func load() {
        guard let database = environment.database else { return }
        // ⚠️ 读正文**不看 enabled**：已禁用的技能也要能预览
        content = SkillRepository(database: database).content(for: skill)
    }

    private func setEnabled(_ value: Bool) {
        guard let database = environment.database else { return }
        let ok = SkillRepository(database: database).setEnabled(value, skillId: skill.skillId)
        notice = ok ? (value ? "已启用（下一回合生效）" : "已禁用（下一回合生效）")
                    : "改动没写进库：这条技能可能已经不在了"
        onChanged()
    }

    private func delete() {
        guard let stores = environment.stores else { return }
        _ = stores.deleteSkill(skillId: skill.skillId)
        onChanged()
        dismiss()
    }
}

// MARK: - 导入

/// 粘贴一份 SKILL.md 导入技能。
///
/// ## 为什么是"粘贴"而不是"选文件"
/// 文件导入要处理 iOS 的安全作用域 URL（`startAccessingSecurityScopedResource`）
/// 与 iCloud 下载状态，那是另一件事；先把**入库这条路**打通并做对。
/// 「从文件导入」的入口留给后续（页脚写明）。
struct SkillImportView: View {

    var onSaved: () -> Void

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    @State private var skillId = ""
    @State private var category = "CUSTOM"
    @State private var tags = ""
    @State private var draft = ""
    @State private var notice: String?

    /// 可选的分类（与 `AgentStores.parseSkillCategory` 认的取值**逐字一致**；
    /// 列表外的值会被回落成 CUSTOM，所以这里不能自己编）。
    private static let categories = [
        "GENERAL", "WRITING", "CODING", "ANALYSIS", "CREATIVE",
        "PRODUCTIVITY", "LANGUAGE", "CUSTOM",
    ]

    var body: some View {
        let c = YNTheme.palette(scheme)
        let parsed = SkillContentParser.parse(draft)
        // ⚠️ 先把回退值算成常量：在字符串插值里写 `?? "（未声明）"` 会让 Swift
        // 的字符串解析炸掉（`cannot find ')' to match opening '('`）。
        let parsedName = parsed.name ?? "（未声明，将回退到技能 ID）"
        let parsedDescription = parsed.description ?? "（未声明）"
        return List {
            Section {
                LabeledContent("技能 ID") {
                    TextField("留空自动生成", text: $skillId)
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }
                Picker("分类", selection: $category) {
                    ForEach(Self.categories, id: \.self) { Text($0).tag($0) }
                }
                LabeledContent("标签") {
                    TextField("逗号分隔", text: $tags)
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }
            } header: {
                Text("元信息")
            } footer: {
                Text("名称与描述优先取自正文的 frontmatter（`name:` / `description:`）；这里只补 frontmatter 不带的字段。")
            }

            Section {
                TextEditor(text: $draft)
                    .font(.caption.monospaced())
                    .frame(minHeight: 220)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button {
                    draft = Self.template
                } label: {
                    Label("填入示例模板", systemImage: "doc.text")
                }
            } header: {
                Text("SKILL.md")
            } footer: {
                Text("解析结果：名称「\(parsedName)」，描述「\(parsedDescription)」")
            }

            if let notice {
                Section { Text(notice).font(.caption).foregroundStyle(c.textSecondary) }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("导入技能")
        .navigationBarTitleDisplayMode(.inline)
        .tint(c.accent)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("取消") { dismiss() }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("保存") { save() }
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
    }

    /// 保存：**走 `AgentStores.saveSkill`**，不要自己写 SQL/文件 ——
    /// 它负责"先写正文再写索引"的次序、相对路径约定与 version 递增。
    private func save() {
        guard let stores = environment.stores else {
            notice = "运行环境未就绪"
            return
        }
        var meta: [String: Any] = ["enabled": true, "category": category]
        let trimmedId = skillId.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedId.isEmpty { meta["skill_id"] = trimmedId }
        let trimmedTags = tags.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedTags.isEmpty { meta["tags"] = trimmedTags }

        let json = (try? JSONSerialization.data(withJSONObject: meta))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        let version = stores.saveSkill(metaJson: json, content: draft)
        if version < 0 {
            notice = "保存失败：存储层返回 -1（见 App 日志 repo.skill）"
            return
        }
        onSaved()
        dismiss()
    }

    /// 示例模板（自己写的，不是从别处抄的）。
    private static let template = """
    ---
    name: 每日复盘
    description: 用户说「复盘今天」时，按这个结构带他把一天过一遍。
    ---

    # 每日复盘

    1. 先问「今天最想记住的一件事是什么」。
    2. 再问「哪件事花的时间比预期多」。
    3. 用三行总结：做成了什么 / 卡在哪 / 明天第一件事。
    4. 不要一次问完，逐条来。
    """
}
