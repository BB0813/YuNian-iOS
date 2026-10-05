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
    @State private var apiKeyDraft = ""
    @State private var ownerNameDraft = ""
    @State private var credentialError: String?
    /// 选中的 provider（枚举名，如 "OPENAI"）。
    ///
    /// ⚠️ 为什么必须有这个选择器而不是默认一个：选错 provider 的后果是
    /// 请求打到**错误的 baseUrl**（静默失败或 404）—— 正是最难看懂的那类故障。
    /// provider 由用户显式决定，代码不替他猜。
    @State private var providerDraft = "OPENAI"
    /// 模型名。**可为空**：空 = 用该 provider 的预设默认值。
    ///
    /// ⚠️ 第 106 轮加。此前没有这个输入框 —— 模型名只能来自预设常量，
    /// 而预设是从 `YuNianSeed` 生成的，用户改不了。
    @State private var modelDraft = ""

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
                // 当前状态一行说明：省得用户猜"我配好了没"
                HStack {
                    Text("当前渠道")
                    Spacer()
                    Text(channelStatusText)
                        .foregroundStyle(channelReady ? .green : .secondary)
                        .font(.caption)
                }

                Picker("服务商", selection: $providerDraft) {
                    ForEach(YuNianSeed.apiProviderPresets, id: \.provider) { preset in
                        Text(preset.displayName).tag(preset.provider)
                    }
                }
                .onChange(of: providerDraft) { _, newProvider in
                    // 换服务商时带出该家的默认模型名，用户在 placeholder 里看得到
                    if let preset = YuNianSeed.apiProviderPresets
                        .first(where: { $0.provider == newProvider }) {
                        modelDraft = preset.model
                    }
                }

                TextField("模型（留空用默认）", text: $modelDraft)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .font(.body.monospaced())
                    .overlay(alignment: .trailing) {
                        if !modelDraft.isEmpty {
                            Button {
                                modelDraft = ""
                            } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .foregroundStyle(.secondary)
                            }
                            .buttonStyle(.plain)
                            .padding(.trailing, 6)
                        }
                    }

                SecureField("API Key", text: $apiKeyDraft)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                TextField("昵称（owner_name）", text: $ownerNameDraft)

                if let credentialError {
                    Text(credentialError).font(.caption).foregroundStyle(.red)
                }

                HStack(spacing: 12) {
                    Button("保存并下发") {
                        do {
                            let model = modelDraft
                                .trimmingCharacters(in: .whitespaces)
                            try environment.setAPIKey(
                                apiKeyDraft,
                                provider: providerDraft,
                                model: model.isEmpty ? nil : model
                            )
                            environment.setOwnerName(ownerNameDraft)
                            apiKeyDraft = ""
                            credentialError = nil
                        } catch {
                            credentialError = String(describing: error)
                        }
                    }
                    .buttonStyle(.borderedProminent)

                    // 拉取模型列表 + 测试连接：配完 key 后的两个验证动作。
                    // 没有它们，填错 key 的症状是"发消息没反应"（第 40 轮的「上线即故障」）。
                    if environment.modelsLoading {
                        ProgressView()
                    } else {
                        Button("拉取模型列表") { Task { await environment.loadServerModels() } }
                        Button("测试连接") { Task { await environment.testConnection() } }
                    }
                }
                .font(.footnote)

                if let msg = environment.modelsMessage {
                    Text(msg)
                        .font(.caption)
                        .foregroundStyle(
                            environment.serverModels.isEmpty
                                && !environment.modelsNotSupported ? .red : .secondary
                        )
                    if environment.modelsNotSupported {
                        Text("可直接在上方「模型」处手动填写模型名，再用「测试连接」验证是否可用。")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    if !environment.serverModels.isEmpty {
                        Text(environment.serverModels.joined(separator: "、"))
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                            .lineLimit(6)
                    }
                }
            } header: {
                Text("模型渠道")
            } footer: {
                Text("API Key 存在系统钥匙串，不写入应用数据库。")
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
            if let key = KeychainStore.string(for: KeychainStore.Key.apiKey), !key.isEmpty {
                apiKeyDraft = key
            }
            // 用「当前生效的配置」回填，避免用户误以为选的是别家
            if let active = environment.apiConfigs?.tryActiveConfig(),
               YuNianSeed.apiProviderPresets.contains(where: { $0.provider == active.provider }) {
                providerDraft = active.provider
                if !active.model.isEmpty {
                    modelDraft = active.model
                }
            }
        }
    }

    // MARK: - 渠道状态

    private var channelReady: Bool {
        let hasKey = !apiKeyDraft.trimmingCharacters(in: .whitespaces).isEmpty
        let configured = environment.credentialsSummary.contains("已配置")
        return hasKey || configured
    }

    private var channelStatusText: String {
        if channelReady {
            if let active = environment.apiConfigs?.tryActiveConfig() {
                return "\(active.provider) · \(active.model)"
            }
            return "已配置"
        }
        return "未配置 API Key"
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
