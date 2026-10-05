import SwiftUI

/// 根视图 —— 用户视角的首页。
///
/// ## 第 109 轮重构:把自检面板搬走
/// 上一版这里就是一张 `List`,Rust Agent 状态、deviceId、keyId、中文分词索引串、
/// settings JSON 全堆在首屏,「进入对话」被埋在最下面。
/// 那是**开发者视角**的界面,不是用户的。
///
/// 现在的首屏只回答三个问题:
///   1. 能不能聊   —— 渠道配置（服务商 / 模型 / API Key）
///   2. 去哪聊      —— 对话 / 记忆 / 技能 三个入口
///   3. 出问题看哪 —— 「诊断」页（原先首屏那些内容原样搬过去了）
///
/// 自检内容本身没删也没改,只是换了位置:见 `DiagnosticsView`。
struct RootView: View {

    @EnvironmentObject private var environment: AppEnvironment
    /// 昵称（owner_name）。⚠️ 不属于渠道配置，故留在首屏而不进 ChannelConfigView。
    @State private var ownerNameDraft = ""
    /// 预选的服务商（进配置页时带上；根视图默认 OPENAI）。
    ///
    /// ⚠️ 为什么必须有这个选择器而不是默认一个：选错 provider 的后果是
    /// 请求打到**错误的 baseUrl**（静默失败或 404）—— 正是最难看懂的那类故障。
    /// provider 由用户显式决定，代码不替他猜。
    @State private var providerDraft = "OPENAI"

    var body: some View {
        NavigationStack {
            Group {
                if let error = environment.startupError {
                    failureView(error)
                } else if environment.runtime != nil {
                    homeView
                } else {
                    ProgressView("正在启动…")
                }
            }
            .navigationTitle("予念")
        }
    }

    // MARK: - 首页

    private var homeView: some View {
        List {
            // ── 主要入口：用户来这里就是为了这三个 ──
            Section {
                NavigationLink("进入对话") { ChatView() }
                    .disabled(environment.runtime == nil)
                NavigationLink("记忆管理") { MemoryListView() }
                    .disabled(environment.memoryRepo == nil)
                NavigationLink("技能库") { SkillLibraryView() }
                    .disabled(environment.stores == nil)
            } footer: {
                Text("回合经 Rust 决策，SSE 流式输出。")
                    .font(.caption2)
            }

            // ── 渠道配置：决定能不能真的聊起来 ──
            Section {
                // 改为「当前渠道卡片 + 去配置」，对齐 Android 的 PetalApiCard 入口。
                // ⚠️ 第 122 轮：原先这里把 provider/model/key/昵称全塞在首屏，
                // 自定义 API 甚至没有填 Base URL 的地方（用户报的缺口）。
                // Android 的做法是「选服务商 → 打开配置表单」（SettingsScreen.kt:469），
                // 表单里才有 Base URL / API 格式 / API 名称。
                // 现在 iOS 照这个结构来：首屏只显示状态 + 入口。
                NavigationLink {
                    ChannelConfigView(provider: providerDraft)
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: channelReady
                              ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                            .foregroundStyle(channelReady ? .green : .orange)
                            .font(.title3)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(channelTitle).font(.body)
                            Text(channelSubtitle)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 4)
                        Text("配置")
                            .font(.footnote)
                            .foregroundStyle(Color.accentColor)
                    }
                }
            } header: {
                Text("模型渠道")
            } footer: {
                Text("未配置或填错时，症状是「发消息没反应」且不报错 —— 所以配完请先「测试连接」。")
                    .font(.caption2)
            }

            // ── 次要功能 ──
            Section {
                NavigationLink("备份导入") { BackupImportView() }
                    .disabled(environment.database == nil)
                NavigationLink("搜索消息") { MessageSearchView() }
                    .disabled(environment.database == nil)
                // ⚠️ 第 113 轮：补表情库入口。
                // sticker_entries / sticker_tags 从 v45 就在 schema 里，
                // StickerTagProvider 也读后者给 Rust 兜底，但用户侧一直无界面 ——
                // 全新安装下表是空的，用户无从判断"没有"还是"没显示"。
                NavigationLink("表情库") { StickerLibraryView() }
                    .disabled(environment.database == nil)
            }

            // ── 开发者入口：默认收起，不打扰用户 ──
            Section {
                NavigationLink("诊断") { DiagnosticsView() }
            } footer: {
                Text("Rust 运行时、数据库签名、设备密钥等自检信息。")
                    .font(.caption2)
            }
        }
        .task {
            ownerNameDraft = KeychainStore.string(for: KeychainStore.Key.ownerName) ?? ""
            // 用当前生效的配置决定「配置」按钮要把哪个服务商带进表单
            if let active = environment.apiConfigs?.tryActiveConfig(),
               YuNianSeed.apiProviderPresets.contains(where: { $0.provider == active.provider }) {
                providerDraft = active.provider
            }
        }
    }

    // MARK: - 渠道状态展示

    private var channelReady: Bool {
        // ⚠️ 第 122 轮：apiKeyDraft 已随首屏表单一起移除（改由 ChannelConfigView 管）。
        // 只依据「库里有没有一条启用配置」判断 —— 那正是 Rust 每回合要用的东西。
        environment.apiConfigs?.tryActiveConfig() != nil
    }

    private var channelTitle: String {
        if let active = environment.apiConfigs?.tryActiveConfig(),
           YuNianSeed.apiProviderPresets.contains(where: { $0.provider == active.provider }) {
            // 与 Android 列表项一致：显示「名称」而非枚举名（SettingsScreen.kt:495）
            return active.name.isEmpty ? presetName(active.provider) : active.name
        }
        return "未配置渠道"
    }

    private var channelSubtitle: String {
        guard let active = environment.apiConfigs?.tryActiveConfig() else {
            return "点此选择服务商并填入 API Key"
        }
        // Android 在预设列表副标题显示 baseUrl（SettingsScreen.kt:501），保持同一信息密度
        let url = active.baseUrl.isEmpty ? "（未填地址）" : active.baseUrl
        return "\(active.model.isEmpty ? "未填模型" : active.model) · \(url)"
    }

    private func presetName(_ provider: String) -> String {
        YuNianSeed.apiProviderPresets.first { $0.provider == provider }?.displayName ?? provider
    }

    // MARK: - 启动失败

    private func failureView(_ error: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.largeTitle)
                .foregroundStyle(.red)
            Text("启动失败").font(.headline)
            Text(error)
                .font(.caption)
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
            NavigationLink("查看诊断") { DiagnosticsView() }
                .buttonStyle(.bordered)
        }
    }
}
