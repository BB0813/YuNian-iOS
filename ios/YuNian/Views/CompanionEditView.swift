import SwiftUI

/// 角色编辑（新建 / 修改人设）。
///
/// ## 为什么必须有
/// `CompanionRepository` 早就有 `create` / `update` / `delete`，
/// 但 iOS **没有任何界面调用它们** —— 通讯录是只读的，
/// 用户既不能建角色、也不能改人设（连内置角色的性格都动不了）。
/// 这与技能页 / 世界书页是同一种病：**存储层在、入口没有**。
///
/// ## 这个表单只做"引擎真的会读"的字段
/// Rust 侧 `native_gateway.rs::to_profile` 逐字段读这 7 列：
/// `name / age / personality / speakingStyle / backstory / rawPrompt / systemPrompt`。
/// 界面把它们按**引擎拼提示词的顺序**摆出来，并在页脚写清每条会变成什么 ——
/// 否则用户改了一个引擎不读的字段，会以为"改了没用"。
/// （`tags` / `avatarUrl` 引擎不读：前者只用于识别"默认角色"，后者只影响显示。）
///
/// ## 保存**不动** `updatedAt`
/// 与 Android 的 `@Update` 一致：编辑人设不该让列表顺序跳变
/// （需要刷新时间戳的路径是对话流程里的 `touch(id:)`）。
struct CompanionEditView: View {

    /// nil = 新建。
    let companion: CompanionRepository.Companion?
    /// 保存成功后回调（列表刷新）。
    var onSaved: () -> Void

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var avatarUrl = ""
    @State private var ageText = ""
    @State private var personality = ""
    @State private var speakingStyle = ""
    @State private var backstory = ""
    @State private var rawPrompt = ""
    @State private var systemPrompt = ""
    @State private var tags = ""
    @State private var notice: String?

    private var isNew: Bool { companion == nil }

    var body: some View {
        let c = YNTheme.palette(scheme)
        return List {
            Section {
                LabeledContent("名字") {
                    TextField("必填", text: $name)
                        .multilineTextAlignment(.trailing)
                }
                LabeledContent("头像 URL") {
                    TextField("可留空", text: $avatarUrl)
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }
                LabeledContent("年龄") {
                    TextField("可留空", text: $ageText)
                        .multilineTextAlignment(.trailing)
                        .keyboardType(.numberPad)
                }
            } header: {
                Text("基本信息")
            } footer: {
                Text("年龄留空就不注入「年龄：N岁」这一行；名字会进入提示词的「你是{名字}」一句。")
            }

            Section {
                field("人设", text: $personality,
                      hint: "会拼成「人设：…」。这是最主要的性格设定。")
                field("说话风格", text: $speakingStyle,
                      hint: "会拼成「说话风格：…」。留空则不注入这一行。")
                field("背景", text: $backstory,
                      hint: "会拼成「背景：…」。留空则不注入这一行。")
            } header: {
                Text("人设")
            } footer: {
                Text("这三项按上面顺序拼进【角色设定】，顺序与引擎一致（不要以为顺序无所谓 —— 它决定模型先看到什么）。")
            }

            Section {
                field("原始提示词", text: $rawPrompt,
                      hint: "ST 角色卡的 raw prompt。⚠️ 与「人设」重复（互相包含）时**会被引擎自动省略**，所以别把它抄一遍到人设里。")
                field("自定义角色指令", text: $systemPrompt,
                      hint: "会作为**单独一段**【自定义角色指令】追加在人设之后，优先级观感上最高。")
            } header: {
                Text("进阶")
            } footer: {
                Text("引擎实际读的是这 7 列：名字 / 年龄 / 人设 / 说话风格 / 背景 / 原始提示词 / 自定义角色指令。写在这里之外的内容不会进提示词。")
            }

            Section {
                LabeledContent("标签") {
                    TextField("逗号分隔", text: $tags)
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }
            } header: {
                Text("高级")
            } footer: {
                Text("标签**不进提示词**。它用于识别「默认角色」（内置角色的标签被标记过）—— 清掉内置角色的标签后，删掉它就不会再被记住「用户删过默认角色」。不确定就别改。")
            }

            if let notice {
                Section { Text(notice).font(.caption).foregroundStyle(c.textSecondary) }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle(isNew ? "新建角色" : "编辑角色")
        .navigationBarTitleDisplayMode(.inline)
        .tint(c.accent)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("保存") { save() }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .onAppear(perform: load)
    }

    // MARK: - 字段

    private func field(_ title: String, text: Binding<String>, hint: String) -> some View {
        let c = YNTheme.palette(scheme)
        return VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.subheadline)
            TextField("可留空", text: text, axis: .vertical)
                .lineLimit(2...6)
                .textFieldStyle(.plain)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background(c.surface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            Text(hint)
                .font(.caption2)
                .foregroundStyle(c.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 2)
    }

    // MARK: - 数据

    private func load() {
        guard let companion else { return }
        name = companion.name
        avatarUrl = companion.avatarUrl ?? ""
        ageText = companion.age.map(String.init) ?? ""
        personality = companion.personality
        speakingStyle = companion.speakingStyle ?? ""
        backstory = companion.backstory ?? ""
        rawPrompt = companion.rawPrompt ?? ""
        systemPrompt = companion.systemPrompt ?? ""
        tags = companion.tags ?? ""
    }

    private func save() {
        guard let repo = environment.companions else {
            notice = "运行环境未就绪"
            return
        }
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty else { return }
        let age = Int(ageText.trimmingCharacters(in: .whitespaces))

        func optional(_ value: String) -> String? {
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }

        do {
            if var existing = companion {
                // ⚠️ 保留原 `updatedAt`：编辑人设不该让列表顺序跳变（与 Android 的 @Update 一致）
                existing.name = trimmedName
                existing.avatarUrl = optional(avatarUrl)
                existing.age = age
                existing.personality = personality.trimmingCharacters(in: .whitespacesAndNewlines)
                existing.speakingStyle = optional(speakingStyle)
                existing.backstory = optional(backstory)
                existing.rawPrompt = optional(rawPrompt)
                existing.systemPrompt = optional(systemPrompt)
                existing.tags = optional(tags)
                try repo.update(existing)
            } else {
                _ = try repo.create(
                    name: trimmedName,
                    personality: personality.trimmingCharacters(in: .whitespacesAndNewlines),
                    age: age,
                    backstory: optional(backstory),
                    speakingStyle: optional(speakingStyle),
                    avatarUrl: optional(avatarUrl),
                    tags: optional(tags),
                    rawPrompt: optional(rawPrompt),
                    systemPrompt: optional(systemPrompt)
                )
            }
            onSaved()
            dismiss()
        } catch {
            notice = "保存失败：写库出错（见 App 日志 repo.companion）"
        }
    }
}
