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

    var body: some View {
        let c = YNTheme.palette(scheme)

        List {
            buildSection
            runtimeSection
            dataSection
            securitySection
            channelSection
            personaSection
            if environment.startupError != nil { startupSection }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("诊断")
        .navigationBarTitleDisplayMode(.inline)
        .tint(c.accent)
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
        }
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
