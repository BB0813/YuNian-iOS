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
    /// ⚠️ 第 128 轮：语义色跟随系统明暗。
    @Environment(\.colorScheme) private var scheme
    /// 第 133 轮：玻璃顶栏的返回按钮用。
    /// 这些页面由 RootView 的 NavigationLink push 进来，
    /// 系统不自动给可见返回钮，故自绘顶栏需要它。
    @Environment(\.dismiss) private var dismiss
    @State private var skills: [SkillRow] = []
    @State private var selected: SkillRow?
    @State private var errorMessage: String?

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }

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

    /// 第 128 轮：套上设计系统。
    ///
    /// 对照 `feature/skills/.../SkillsCenterScreen.kt`：
    /// - 页面标题 **"AI 能力中心"**（SkillsCenterScreen.kt:106）——
    ///   我上一版叫"技能库"，与 Android 不一致，本版对齐
    /// - `CapabilityCard`：**16dp 圆角** + drawGlass + padding(16)，
    ///   头部有 8dp 状态圆点（SkillsCenterScreen.kt:220-225）
    /// - `SkillRow`：**14dp 圆角** + drawGlass + Row padding(14)，
    ///   名称 Medium、外部技能加 11sp "AI 安装" `primary`、
    ///   描述 13sp/lineHeight 17sp（SkillsCenterScreen.kt:281-285）
    /// - `CapabilitySectionLabel`：14sp Medium `onSurfaceVariant`
    ///   （SkillsCenterScreen.kt:201-206）
    var body: some View {
        // 第 133 轮：改用 YuNianGlassPage（对照 Android GlassTopBar），
        // 不再用系统 NavigationBar。
        YuNianGlassPage(title: "AI 能力中心", onBack: { dismiss() }) {
            VStack(alignment: .leading, spacing: YuNianTheme.Space.standard) {

                if let errorMessage {
                    Text(errorMessage)
                        .font(.caption)
                        .foregroundStyle(colors.danger)
                        .textSelection(.enabled)
                        .padding(.horizontal, YuNianTheme.Space.minUnit)
                }

                YuNianSectionTitle(title: "技能库（AI 可自主加载）")

                if skills.isEmpty {
                    YuNianGlassCard {
                        Text("还没有技能。内置的「聊天工具协议」技能应已在启动时播种。")
                            .font(.system(size: 12))
                            .foregroundStyle(colors.textSecondary)
                    }
                } else {
                    ForEach(skills) { skill in
                        YuNianGlassCard {
                            Button { selected = skill } label: { row(skill) }
                                .buttonStyle(.plain)
                        }
                    }
                }

                Text("模型通过 load_skill 工具按需加载正文；这里只影响可见性与增删。")
                    .font(.system(size: 11))
                    .foregroundStyle(colors.textTertiary)
                    .padding(.horizontal, YuNianTheme.Space.minUnit)

                Spacer(minLength: YuNianTheme.Space.pageTop)
            }
            .padding(.horizontal, YuNianTheme.Space.page)
            .padding(.top, YuNianTheme.Space.standard)
        }
        .sheet(item: $selected) { skill in
            NavigationStack {
                SkillDetailView(skill: skill)
                    .environmentObject(environment)
            }
        }
        .task { reload() }
    }

    // MARK: - 子视图

    /// 单条技能 —— 对应 `SkillRow`（SkillsCenterScreen.kt:264-285）。
    private func row(_ skill: SkillRow) -> some View {
        VStack(alignment: .leading, spacing: YuNianTheme.Space.half) {
            HStack(spacing: YuNianTheme.Space.half) {
                Text(skill.name)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(colors.textPrimary)
                Spacer()
                if !skill.enabled {
                    Text("已禁用")
                        .font(.system(size: 11))
                        .foregroundStyle(colors.warning)
                        .padding(.horizontal, YuNianTheme.Space.standard)
                        .padding(.vertical, YuNianTheme.Space.tight)
                        .background(colors.warning.opacity(0.15))
                        .clipShape(Capsule())
                }
            }

            Text(skill.description)
                .font(.system(size: 13))
                .foregroundStyle(colors.textSecondary)
                .lineLimit(2)

            if !skill.tools.isEmpty {
                Text(skill.tools.joined(separator: " · "))
                    .font(.system(size: 11).monospaced())
                    .foregroundStyle(colors.textTertiary)
            }
        }
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
