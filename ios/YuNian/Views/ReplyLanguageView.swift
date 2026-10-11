import SwiftUI

/// 回复语言（第 218 轮）。
///
/// ## 这一页回答的问题
/// 「为什么它有时用英文回我？」—— 因为**引擎里原本没有任何语言指令**，
/// 用什么语言回答全靠模型自己从人设与历史里猜。
/// 这一页把这个要求变成**显式下发的一条指令**。
///
/// ## 为什么不做得更"智能"（自动检测输入语言）
/// 自动检测要引入一套语言识别 + 切换策略，而且**失败时用户完全无从纠正**；
/// 一个能看见、能改、能说清"它是指令不是保证"的选项，比自动猜测更可靠。
struct ReplyLanguageView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var selection: ReplyLanguage = .followModel
    @State private var notice: String?

    var body: some View {
        let c = YNTheme.palette(scheme)
        return List {
            Section {
                ForEach(ReplyLanguage.allCases) { option in
                    Button {
                        select(option)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(option.title)
                                    .foregroundStyle(c.textPrimary)
                                Text(subtitle(option))
                                    .font(.caption)
                                    .foregroundStyle(c.textTertiary)
                            }
                            Spacer()
                            if selection == option {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(c.accent)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                }
            } header: {
                Text("回复语言")
            } footer: {
                Text("""
                选中的语言会作为一条**指令**下发到提示词（在【角色设定】之后注入【回复语言】），\
                要求模型始终用它回复 —— 包括思考过程与工具调用里的自然语言。
                ⚠️ 它是指令，**不是保证**：长上下文、强人设或模型自身偏好都可能压过它。\
                真遇到它仍换语言，可以把「跟随模型」换成明确的一种，或在角色人设里再强调一次。
                """)
            }

            Section {
                LabeledContent("当前下发",
                               value: selection.languageName ?? "不下发（跟随模型）")
            } footer: {
                Text("""
                「跟随模型」= **不下发这个键**（与 Android 端行为一致，不改变任何既有表现）。
                其余选项下发的是**语言名**（如「简体中文」），不是 `zh-Hans` 这类代码 —— \
                少一层映射，就少一处"码对了但文案不对"的可能。
                """)
            }

            if let notice {
                Section { Text(notice).font(.caption).foregroundStyle(c.textSecondary) }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("回复语言")
        .navigationBarTitleDisplayMode(.inline)
        .tint(c.accent)
        .onAppear(perform: load)
    }

    private func subtitle(_ option: ReplyLanguage) -> String {
        switch option {
        case .followModel: return "不下发语言指令，保持与 Android 一致"
        case .simplifiedChinese: return "最常见的默认选择"
        case .traditionalChinese: return "繁體中文用字"
        case .english: return "Always reply in English"
        case .japanese: return "常に日本語で返信"
        }
    }

    private func load() {
        guard let repo = environment.apiConfigs else { return }
        selection = ReplyLanguage.load(from: repo)
    }

    private func select(_ option: ReplyLanguage) {
        guard let repo = environment.apiConfigs else {
            notice = "运行环境未就绪"
            return
        }
        do {
            try option.save(to: repo)
            selection = option
            notice = option.languageName == nil
                ? "已改为跟随模型，语言指令不再下发"
                : "已改为 \(option.title)，下一轮对话起生效"
        } catch {
            notice = "保存失败，请稍后重试"
        }
    }
}
