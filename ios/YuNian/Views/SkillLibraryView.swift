import SwiftUI

/// 技能库界面（M3）。
///
/// ## 数据流已在别处验证
/// `SkillChainTests` 验证过「种子 → discover → loadContent」全链路，
/// 所以这里显示的内容确实是模型能通过 `load_skill` 加载的那批。
///
/// ## 与 Rust 的关系
/// 列表来自 `SkillStore.list_skills`，正文来自 `get_skill_content`
/// （带 SHA-256 校验，失败视为不存在）。删除走 `delete_skill`。
struct SkillLibraryView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @State private var skills: [SkillRow] = []
    @State private var selected: SkillRow?
    @State private var errorMessage: String?

    /// 列表行（从 `listSkills` 的 JSON 提取）。
    struct SkillRow: Identifiable, Equatable {
        var id: String { skillId }
        let skillId: String
        let name: String
        let description: String
        let category: String
        let enabled: Bool
        let tools: [String]
    }

    var body: some View {
        List {
            if let errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }

            Section {
                if skills.isEmpty {
                    Text("还没有技能。内置的「聊天工具协议」技能应已在启动时播种。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(skills) { skill in
                        Button { selected = skill } label: { row(skill) }
                            .foregroundStyle(.primary)
                    }
                }
            } footer: {
                Text("模型通过 load_skill 工具按需加载正文；这里只影响可见性与增删。")
                    .font(.caption2)
            }
        }
        .navigationTitle("技能库")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $selected) { skill in
            NavigationStack {
                SkillDetailView(skill: skill)
                    .environmentObject(environment)
            }
        }
        .task { reload() }
    }

    // MARK: - 子视图

    private func row(_ skill: SkillRow) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(skill.name).font(.headline)
                Spacer()
                if !skill.enabled {
                    Text("已禁用")
                        .font(.caption2)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.secondary.opacity(0.18))
                        .clipShape(Capsule())
                }
            }
            Text(skill.description)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
            if !skill.tools.isEmpty {
                Text(skill.tools.joined(separator: " · "))
                    .font(.caption2.monospaced())
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 2)
    }

    // MARK: - 数据

    private func reload() {
        guard let stores = environment.stores else {
            errorMessage = "数据库未就绪"
            return
        }
        // companionId 为 nil = 只列全局技能（与 Rust 的 list_skills(nil) 语义一致）
        let json = stores.listSkills(companionId: nil)
        guard let data = json.data(using: .utf8),
              let arr = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else {
            errorMessage = "技能列表解析失败"
            skills = []
            return
        }
        skills = arr.compactMap { item in
            guard let skillId = item["skill_id"] as? String else { return nil }
            return SkillRow(
                skillId: skillId,
                name: item["name"] as? String ?? skillId,
                description: item["description"] as? String ?? "",
                category: item["category"] as? String ?? "",
                enabled: (item["enabled"] as? Bool) ?? false,
                tools: (item["tools"] as? [String]) ?? []
            )
        }
        .sorted { $0.name.localizedCompare($1.name) == .orderedAscending }
        errorMessage = nil
    }
}

/// 技能详情：显示正文（模型加载到的就是这份）。
private struct SkillDetailView: View {

    @EnvironmentObject private var environment: AppEnvironment
    let skill: SkillLibraryView.SkillRow

    @State private var content: String = "（加载中…）"
    @State private var loadFailed = false

    var body: some View {
        List {
            Section("元信息") {
                LabeledContent("ID", value: skill.skillId)
                LabeledContent("分类", value: skill.category.isEmpty ? "—" : skill.category)
                LabeledContent("依赖工具", value: skill.tools.isEmpty ? "无" : skill.tools.joined(separator: ", "))
                LabeledContent("状态", value: skill.enabled ? "启用" : "禁用")
            }
            Section("正文（load_skill 加载到的内容）") {
                if loadFailed {
                    Label("正文校验失败或不存在（Rust 会视为「技能不存在」）",
                          systemImage: "exclamationmark.triangle")
                        .font(.caption)
                        .foregroundStyle(.red)
                } else {
                    Text(content)
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                }
            }
        }
        .navigationTitle(skill.name)
        .navigationBarTitleDisplayMode(.inline)
        .task { load() }
    }

    private func load() {
        guard let stores = environment.stores else { return }
        if let text = stores.getSkillContent(skillId: skill.skillId), !text.isEmpty {
            content = text
            loadFailed = false
        } else {
            content = ""
            loadFailed = true
        }
    }
}
