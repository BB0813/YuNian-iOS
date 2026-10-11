import SwiftUI

/// 诊断聚合页（只读）。
///
/// ## 为什么要页面化，而不是"出问题让用户截图发开发者"
/// 用户能自查的越多，来回问的成本越低。这一页把**本机当前的事实**摊开：
/// 构建标记、数据状态、安全基线、渠道状态、启动错误。
///
/// ## 一条硬约束：**只读**
/// 这里不做任何"清理/修复/重置"动作。诊断页上的写操作是危险动作，
/// 应该由用户明确知道后果时再单独触发（如备份页）。
/// 一个看起来像"一键修复"的按钮，代价往往是不可逆的数据损失。
struct DiagnosticsView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    /// `buildUserContext` 的产物（模型每回合看到的用户上下文前缀）。
    @State private var modelContextPreview: String?

    var body: some View {
        let c = YNTheme.palette(scheme)

        List {
            buildSection
            runtimeSection
            dataSection
            securitySection
            channelSection
            personaSection
            modelContextSection
            if environment.startupError != nil { startupSection }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("诊断")
        .navigationBarTitleDisplayMode(.inline)
        .tint(c.accent)
        // 组装一次用户上下文预览（含一次记忆召回，故放在 task 里而不是 body 里）
        .task { loadModelContextPreview() }
    }

    // MARK: - 构建标记

    /// 这些是**判断"系统新外观到底有没有生效"的关键**。
    ///
    /// 之前排查「Liquid Glass 不见了」花了很久，根因就是 Mach-O 被标成
    /// `sdk 17.0`：编译、链接、安装全部成功，**只有外观是旧的**，完全静默。
    /// 把这几项摆在界面上，下次一眼就能排除这一层。
    private var buildSection: some View {
        Section {
            LabeledContent("构建 SDK", value: Self.string(info["DTSDKName"]))
            LabeledContent("平台版本", value: Self.string(info["DTPlatformVersion"]))
            LabeledContent("最低系统", value: Self.string(info["MinimumOSVersion"]))
            LabeledContent("设计兼容模式",
                           value: (info["UIDesignRequiresCompatibility"] as? Bool) == true
                                  ? "已开启（不启用新外观）" : "关闭（启用新外观）")
            LabeledContent("版本", value: "\(Self.string(info["CFBundleShortVersionString"])) (\(Self.string(info["CFBundleVersion"])))")
        } header: {
            Text("构建标记")
        } footer: {
            Text("「构建 SDK」必须等于本机系统所在的大版本，否则系统会按旧 SDK 渲染界面（新外观不生效，且不会有任何报错）。")
        }
    }

    /// 运行时的外部依赖状态。
    private var runtimeSection: some View {
        let c = YNTheme.palette(scheme)
        return Section("运行环境") {
            LabeledContent("全文检索", value: environment.resolvedFTSVersion)
            LabeledContent("内容过滤", value: environment.contentFilterReady ? "已就绪" : "未就绪")
            LabeledContent("安全种子", value: environment.securitySeedSummary)
        }
    }

    private var dataSection: some View {
        Section("数据") {
            LabeledContent("渠道数量", value: "\(channelCount) 条")
            LabeledContent("启用渠道", value: activeChannelSummary)
            LabeledContent("表情标签", value: environment.stickerTagsSummary)
            // ⚠️ 这一项是"模型可见性"开关的**验收面**：
            // 关掉某个工具集之后，这里必须跟着少掉那几个工具名。
            // 拿不到设备日志时，界面就是取证面。
            LabeledContent("发给模型的工具", value: effectiveToolSummary)
        }
    }

    /// 本回合实际下发给模型的工具名（已含可见性过滤与确认策略的类别改写）。
    private var effectiveToolSummary: String {
        let names = effectiveToolNames()
        return names.isEmpty ? "无" : names.joined(separator: ", ")
    }

    /// 过滤后的工具名（`buildUserContext` 的 `availableTools` 也用它）。
    private func effectiveToolNames() -> [String] {
        let settings = environment.apiConfigs.map { ToolSettings.load(from: $0) } ?? ToolSettings()
        return AgentToolCatalog.gatedDefinitions(settings).map(\.name)
    }

    private var securitySection: some View {
        Section("安全") {
            LabeledContent("密钥存放", value: "系统钥匙串（不落库明文）")
            LabeledContent("数据库位置", value: "App 沙盒内")
        }
    }

    private var channelSection: some View {
        Section("渠道") {
            if let active = try? environment.apiConfigs?.activeConfig() {
                LabeledContent("服务商", value: active.provider)
                LabeledContent("模型", value: active.model.isEmpty ? "未填" : active.model)
                LabeledContent("接口地址", value: active.baseUrl)
                 LabeledContent("协议格式", value: active.formatHint)
                LabeledContent("主密钥",
                               value: environment.resolvedAPIKey(configId: active.id).isEmpty ? "缺失" : "已保存")
                LabeledContent("额外密钥", value: "\(environment.extraAPIKeyCount(configId: active.id)) 个")
            } else {
                Text("没有启用的渠道。")
                    .foregroundStyle(YNTheme.palette(scheme).textTertiary)
            }
        }
    }

    /// 角色人设 —— **引擎读的同一行**。
    ///
    /// ## 为什么摆在界面上（第 204 轮）
    /// 用户问「回复为什么是英文不是中文」。这类问题的可能原因有三类：
    ///   1. 渠道/模型自身的倾向（换一家就知道）
    ///   2. **人设是空的或是英文的** —— 引擎组装 system prompt 时，
    ///      persona 片段就取自这一行的 `personality / speakingStyle /
    ///      backstory / rawPrompt / systemPrompt`
    ///   3. 历史惯性（模型会模仿自己上文里的语言）
    ///
    /// 第 2 类在这里一眼可见：`load_companion` 的 SELECT 列
    /// 与 `CompanionRepository.rustReadColumns` 逐字相同，
    /// 所以这几行就是引擎看到的东西。
    ///
    /// ## ⚠️ 这里显示的是**输入**，不是"模拟出来的提示词"
    /// 模拟提示词要复刻 Rust 的编排逻辑（L0–L5 片段、工具集合、时区…），
    /// 一旦有一处对不上，就会给出**看起来精确的假证据** ——
    /// 那比没有证据更糟（本会话记录过多次"观测手段骗人"）。
    /// 所以只摊输入，把结论留给判断。
    @ViewBuilder
    private var personaSection: some View {
        let c = YNTheme.palette(scheme)
        return Section {
            if let companion = environment.defaultCompanion {
                LabeledContent("名称", value: companion.name)
                LabeledContent("年龄", value: companion.age.map(String.init) ?? "未填")
                personaRow("人设", companion.personality, colors: c)
                personaRow("说话风格", companion.speakingStyle, colors: c)
                personaRow("背景", companion.backstory, colors: c)
                personaRow("补充设定", companion.rawPrompt, colors: c)
                personaRow("角色指令", companion.systemPrompt, colors: c)
            } else {
                Text("没有伴侣行 —— 引擎读不到人设，会以空人设组装提示词。")
                    .font(.subheadline)
                    .foregroundStyle(c.warning)
            }
        } header: {
            Text("角色人设（引擎读的那一行）")
        } footer: {
            Text("这几段是模型看到的「人设」来源。全为「（空）」时，模型没有任何人物设定与语言倾向，回复风格会完全取决于所选模型。")
        }
    }

    /// 人设字段一行：空值明确写「（空）」，不让空白看起来像"没数据"。
    private func personaRow(_ title: String, _ value: String?,
                            colors c: YNTheme.Palette) -> some View {
        let text = (value ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption).foregroundStyle(c.textSecondary)
            Text(text.isEmpty ? "（空）" : text)
                .font(.subheadline)
                .foregroundStyle(text.isEmpty ? c.warning : c.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// **模型看到的**时间与环境上下文（第 205 轮）。
    ///
    /// ## 为什么值得摆在界面上
    /// 用户报过「模型说凌晨三点半，而状态栏 05:09」。要判断这是模型的幻觉
    /// 还是我们注入错了，唯一的办法是**看注入的那一行到底是什么** ——
    /// 拿不到设备日志时，界面就是取证面。
    ///
    /// 这里直接向编排器要 `buildUserContext` 的产物：就是每回合拼给模型的
    /// 那一段（`[当前时间]` / `[对话轮数]` / `[近期记忆]`）。
    ///
    /// 两个实现细节可以当场核对：
    ///   · 时间来自 **`chrono::Local`（进程本地时间）**，而**不是**下发下去的
    ///     `timezone` 设置 —— 后者只作为文本标签出现在 system prompt 里
    ///     （`prompt_orchestrator.rs:482`）。两者不一致时，"标签"与"钟"就会打架。
    ///   · **时段是按小时粗分的**（`5..=7 → 清晨`，`3 点 → 深夜/凌晨`），
    ///     模型很容易照着它**编一个具体钟点**出来。
    ///
    /// ⚠️ 这是**预览**：`history_rounds` 传 0（真实回合传历史条数），
    /// 所以只看时间 / 星期 / 时段这三项。
    @ViewBuilder
    private var modelContextSection: some View {
        let c = YNTheme.palette(scheme)
        return Section {
            LabeledContent("下发时区", value: TimeZone.current.identifier)
            LabeledContent("系统当前时间", value: Self.clockFormatter.string(from: Date()))
            if let preview = modelContextPreview {
                Text(preview)
                    .font(.caption.monospaced())
                    .foregroundStyle(preview.isEmpty ? c.warning : c.textPrimary)
                    .textSelection(.enabled)
            } else {
                Text("正在组装…")
                    .font(.caption)
                    .foregroundStyle(c.textTertiary)
            }
        } header: {
            Text("模型看到的时间与环境")
        } footer: {
            Text("""
            第一行是每回合拼给模型的用户上下文（预览：对话轮数按 0 计）。
            把它和上面的「系统当前时间」对一下就能判断：一致 → 模型自己编的钟点；不一致 → 是注入的问题。
            注意时段是粗分的（3 点与 7 点都可能是「凌晨/清晨」），模型常在时段上补一个具体时间。
            """)
        }
    }

    /// 组装一次用户上下文预览。
    ///
    /// 用与回合相同的编排器构造方式（memory / skill 两个选择器都基于 stores），
    /// 选项也按 `agent.rs::orchestration_options` 的取法填 ——
    /// **不另造一套**，否则预览与真实回合会悄悄分叉，那就成了假证据。
    private func loadModelContextPreview() {
        guard let stores = environment.stores else {
            modelContextPreview = "（运行环境未就绪）"
            return
        }
        let orchestrator = PromptOrchestrator(
            memory: MemorySelector(store: stores),
            skill: SkillSelector(store: stores)
        )
        let options = PromptOrchestratorOptions(
            memoryLimit: 5,
            skillLimit: 3,
            includeMemorySkill: true,
            includeSafetyNote: true,
            // 与回合一致：请求侧工具名（**用同一套可见性过滤**，
            // 否则预览里会出现用户已经关掉的工具）。
            availableTools: effectiveToolNames(),
            deviceId: DeviceIdentity.deviceId,
            timezone: TimeZone.current.identifier,
            sessionId: nil,
            ownerName: KeychainStore.string(for: KeychainStore.Key.ownerName),
            companionNameMapJson: nil,
            workingMemoryLimit: 200
        )
        modelContextPreview = orchestrator.buildUserContext(
            companionId: environment.defaultCompanion?.id,
            groupId: nil,
            historyRounds: 0,
            options: options
        )
    }

    private static let clockFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return formatter
    }()

    private var startupSection: some View {
        let c = YNTheme.palette(scheme)
        return Section("启动") {
            // T6：图标 + 文字共同传达，不只靠颜色
            Label {
                Text(environment.startupError ?? "")
                    .font(.subheadline)
            } icon: {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(c.danger)
            }
        }
    }

    // MARK: - 数据源

    private var info: [String: Any] {
        Bundle.main.infoDictionary ?? [:]
    }

    /// `infoDictionary` 是 `[String: Any]`，值不能直接当 `String?` 用。
    private static func string(_ value: Any?) -> String {
        (value as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "—"
    }

    private var channelCount: Int {
        ((try? environment.apiConfigs?.allConfigs()) ?? []).count
    }

    private var activeChannelSummary: String {
        guard let active = try? environment.apiConfigs?.activeConfig() else { return "无" }
        let hasKey = !environment.resolvedAPIKey(configId: active.id).isEmpty
        return hasKey ? "\(active.provider)（密钥已保存）" : "\(active.provider)（缺密钥）"
    }
}
