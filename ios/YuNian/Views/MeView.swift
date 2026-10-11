import SwiftUI

/// 设置行：图标 + 标题 + **一句人话解释** + 右侧状态徽标。
///
/// ## 为什么要有副标题和徽标
/// 竞品（Aru）的设置页是同一结构：每一项都有一句
/// "这是什么"的说明，右侧徽标告诉你"现在什么状态"。
///
/// 只有标题的列表，用户必须**一项项点进去才知道是什么** ——
/// 而设置页恰恰是他最不想乱点的地方（怕改坏）。
struct YNSettingsRow: View {

    let icon: String
    let title: String
    let subtitle: String
    var badge: String?
    var badgeTint: Color?

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let c = YNTheme.palette(scheme)
        HStack(spacing: YNTheme.Space.md) {
            Image(systemName: icon)
                .font(.body)
                .foregroundStyle(c.accent)
                .frame(width: 34, height: 34)
                .background(c.accent.opacity(0.14),
                            in: RoundedRectangle(cornerRadius: 9, style: .continuous))
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .foregroundStyle(c.textPrimary)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(c.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: YNTheme.Space.sm)

            if let badge {
                Text(badge)
                    .font(.caption)
                    .foregroundStyle(badgeTint ?? c.textSecondary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background((badgeTint ?? c.textSecondary).opacity(0.12),
                                in: Capsule())
            }
        }
        .padding(.vertical, 2)
        // 合并成一个可点元素：否则 VoiceOver 会读成三个互不相关的片段
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(title)。\(subtitle)\(badge.map { "，\($0)" } ?? "")")
    }
}

/// 我 —— 设置与个人信息。
///
/// ## 为什么它排在聊天页之后、却是第一个被填实的页面
/// 它本身观感不复杂，但它是**其余能力的宿主**：
/// 没有它，用户连 API Key 都配不了 —— 聊天页永远停在
/// 「没有可用的 API Key」，流式 / typing / 思考过程这些
/// **最该被验收的状态一个都看不到**。
///
/// ## 形态选择：系统 `List` + `Section`
/// 不自己搭卡片。iOS 设置类页面的**原生答案**就是 inset grouped List，
/// 它自带分组、分隔线、可点区域、动态字体与 VoiceOver 支持。
struct MeView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var ownerName = ""
    @State private var didLoad = false

    var body: some View {
        let c = YNTheme.palette(scheme)
        NavigationStack {
            List {
                ownerSection
                aiSection
                systemSection
                dataSection
                diagnosticSection
                aboutSection
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(YNCanvas())
            .navigationTitle("我")
            .task { loadOnce() }
        }
        .tint(c.accent)
    }

    // MARK: - 个人信息

    private var ownerSection: some View {
        Section {
            // 内联编辑而不是跳页：只有一个字段，开一屏是多余的。
            HStack(spacing: YNTheme.Space.md) {
                Image(systemName: "person.text.rectangle")
                    .foregroundStyle(YNTheme.palette(scheme).accent)
                    .frame(width: 26)
                TextField("你的称呼", text: $ownerName)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onSubmit { saveOwnerName() }
                    .accessibilityLabel("你的称呼")
            }
        } header: {
            Text("个人信息")
        } footer: {
            Text("角色会用它称呼你。留空则不下发。")
        }
    }

    private func saveOwnerName() {
        environment.setOwnerName(ownerName.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    // MARK: - AI 能力

    private var aiSection: some View {
        let c = YNTheme.palette(scheme)
        return Section {
            NavigationLink {
                ChannelListView()
            } label: {
                YNSettingsRow(
                    icon: "cpu",
                    title: "模型渠道",
                    subtitle: "对话用哪个服务商与密钥；可配多条并随时切换。",
                    badge: channelBadge,
                    badgeTint: channelReady ? nil : c.warning
                )
            }

            // 第 203 轮（P0 ②）：四条用途线路。
            // 单独一层入口而不是塞进渠道页 —— 渠道页回答"我配了哪些渠道"，
            // 这里回答"哪个用途用哪条"，是两件事。
            NavigationLink {
                FeatureRouteView()
            } label: {
                YNSettingsRow(
                    icon: "arrow.triangle.branch",
                    title: "用途线路",
                    subtitle: "对话 / 生图 / 朗读 / 向量嵌入各自用哪条渠道与模型。",
                    badge: routesBadge,
                    badgeTint: routesBoundCount == 0 ? nil : c.accent
                )
            }

            NavigationLink {
                StickerLibraryView()
            } label: {
                YNSettingsRow(
                    icon: "face.smiling",
                    title: "表情库",
                    subtitle: "导入表情并命名 —— 名称决定标签，模型按标签挑表情。",
                    badge: environment.stickerTagsSummary
                )
            }

            // 说明：表情库此前没有入口，是因为导入页没做。
            // 现在 `StickerImportRepository` 有了调用方。

        } header: {
            Text("AI 能力")
        } footer: {
            if !channelReady {
                // 明确告诉用户"不配就用不了"，而不是等他发消息才弹错。
                Text("未配置可用的 API Key，聊天无法获得回复。")
            }
        }
    }

    private var channelReady: Bool {
        (try? environment.apiConfigs?.activeConfig()) != nil
    }

    /// 用户**显式指定**过渠道的线路条数。
    ///
    /// ⚠️ 刻意**不数「对话」**：对话线路由 `upsertActiveConfig` / `activate`
    /// 自动与「当前启用渠道」保持同步（见 `ApiConfigRepository.activate` 的不变式），
    /// 所以它几乎恒为已绑定 —— 数进去的话，一个刚装好、从没进过线路页的用户
    /// 也会看到「已指定 1 条」，那是在描述实现细节，不是在描述用户做过什么。
    private var routesBoundCount: Int {
        guard let repo = environment.apiConfigs else { return 0 }
        return FeaturePurpose.allCases
            .filter { $0 != .chat }
            .filter { purpose in
                let bound = (try? repo.routeConfigId(purpose: purpose)) ?? nil
                return bound != nil
            }
            .count
    }

    /// 徽标回答两个问题：另外三条线路有没有单独指定；没有就是全部跟随当前。
    private var routesBadge: String {
        let count = routesBoundCount
        return count == 0 ? "跟随当前渠道" : "已单独指定 \(count) 条"
    }

    /// 徽标只回答一个问题：「现在能不能用」。
    private var channelBadge: String {
        guard let active = try? environment.apiConfigs?.activeConfig() else {
            return "未配置"
        }
        let name = environment.visibleApiPresets()
            .first { $0.provider == active.provider }?.displayName ?? active.provider
        let hasKey = !environment.resolvedAPIKey(configId: active.id).isEmpty
        return hasKey ? name : "\(name) · 缺密钥"
    }

    // MARK: - 系统连接

    /// 系统能力授权。
    ///
    /// 完整形态（每类三态 + 写入确认 + 访问日志）需要真正接入各系统框架，
    /// 见 `SystemConnectionsView` 的说明。这里先把**入口**立起来，
    /// 并把当前实际状态如实写进徽标（"只读"），不假装能开关。
    private var systemSection: some View {
        Section {
            NavigationLink {
                SystemConnectionsView()
            } label: {
                YNSettingsRow(
                    icon: "link",
                    title: "系统连接",
                    subtitle: "本 App 声明了哪些系统能力，以及去哪里管理授权。",
                    badge: "只读"
                )
            }
        } header: {
            Text("系统连接")
        }
    }

    // MARK: - 数据

    private var dataSection: some View {
        Section {
            NavigationLink {
                BackupView()
            } label: {
                YNSettingsRow(
                    icon: "externaldrive",
                    title: "数据与备份",
                    subtitle: "导出加密备份、从备份恢复；数据始终能带走。"
                )
            }
        } header: {
            Text("数据")
        } footer: {
            Text("没有导出入口的数据等于不属于你。备份以自设口令加密，口令不存本机。")
        }
    }

    // MARK: - 诊断

    private var diagnosticSection: some View {
        let c = YNTheme.palette(scheme)
        return Section {
            NavigationLink {
                DiagnosticsView()
            } label: {
                YNSettingsRow(
                    icon: "stethoscope",
                    title: "诊断",
                    subtitle: "构建标记、数据状态、安全基线、渠道状态；只读自查。",
                    badge: environment.startupError == nil ? "正常" : "有错误",
                    badgeTint: environment.startupError == nil ? nil : c.danger
                )
            }
        } header: {
            Text("诊断")
        } footer: {
            Text("构建 SDK 与设计兼容模式决定系统是否渲染新外观 —— 排查外观问题时先看这两项。")
        }
    }

    /// 诊断摘要（已移入 `DiagnosticsView`，此处保留供「我」页快速一瞥）。
    private var diagnosticSummarySection: some View {
        let c = YNTheme.palette(scheme)
        return Section("诊断") {
            // 只读汇总。完整诊断页（含契约自检）是后续项 ——
            // 这里先把"当前能不能用"如实摆出来，避免用户靠猜。
            LabeledContent("数据库", value: environment.resolvedFTSVersion)
            LabeledContent("内容过滤", value: environment.contentFilterReady ? "已就绪" : "未就绪")
            LabeledContent("安全种子", value: environment.securitySeedSummary)
            LabeledContent("渠道数量", value: "\(channelCount) 条")
            // 表情状态放在这里：目前还没有导入页，所以只如实汇报"有没有"，
            // 不做一个点不动的入口。
            LabeledContent("表情标签", value: environment.stickerTagsSummary)

            if let error = environment.startupError {
                // T6：不只靠颜色 —— 图标 + 文字共同传达
                Label {
                    Text(error).font(.caption)
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(c.danger)
                }
            }
        }
    }

    private var channelCount: Int {
        ((try? environment.apiConfigs?.allConfigs()) ?? []).count
    }

    // MARK: - 关于

    private var aboutSection: some View {
        Section("关于") {
            LabeledContent("版本", value: Self.appVersion)
            LabeledContent("引擎", value: "Rust Agent + SwiftUI")
        }
    }

    private static var appVersion: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "—"
        return "\(v) (\(b))"
    }

    // MARK: - 载入

    private func loadOnce() {
        guard !didLoad else { return }
        didLoad = true
        ownerName = KeychainStore.string(for: KeychainStore.Key.ownerName) ?? ""
    }
}
