import SwiftUI

/// 根视图：M0/M1 阶段的「启动自检」面板 + 对话入口。
///
/// 它存在的意义是让开工前必验项（文档 §9）可见、可截图：
///   - V1/V2：Rust Agent 与 Swift 绑定是否可用
///   - V4/V5：数据库是否建成 v45 基线、FTS 落到哪个版本、Rust 直读的 2 张表是否在
///   - V9 前置：设备签名是否用上了 Secure Enclave、keyId 是否可算
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
    /// ⚠️ 第 106 轮加。此前没有这个输入框 —— 模型名只能来自预设，
    /// 而预设是从 `YuNianSeed` 生成的常量，用户改不了。
    /// 后果：服务商上架了新模型（或换了模型命名），iOS 侧无法跟进，
    /// 「拉取模型列表」列出来的名字也只能"可选中复制"、没有回填处。
    @State private var modelDraft = ""

    var body: some View {
        NavigationStack {
            Group {
                if let error = environment.startupError {
                    failureView(error)
                } else if environment.runtime != nil {
                    selfCheckView
                } else {
                    ProgressView("正在启动…")
                }
            }
            .navigationTitle("予念")
        }
    }

    // MARK: - 自检

    private var selfCheckView: some View {
        List {
            Section {
                NavigationLink("进入对话") { ChatView() }
                    .disabled(environment.runtime == nil)
                NavigationLink("记忆管理") { MemoryListView() }
                    .disabled(environment.memoryRepo == nil)
                NavigationLink("技能库") { SkillLibraryView() }
                    .disabled(environment.stores == nil)
            } footer: {
                Text("M2 最小可用对话：文本回合经 Rust 决策 + SSE 流式输出。")
            }

            Section("Rust Agent") {
                checkRow("AgentRuntime", passed: environment.runtime != nil, detail: "已构造")
                checkRow("Swift 绑定", passed: environment.runtime != nil,
                         detail: "UniFFI 生成物可调用")
                checkRow("工具宿主", passed: true,
                         detail: environment.toolHost.registeredToolNames.isEmpty
                            ? "未注册工具（M3 接入）"
                            : environment.toolHost.registeredToolNames.joined(separator: ", "))
            }

            Section("数据层") {
                checkRow("schema 版本", passed: true,
                         detail: "v\(YuNianSchema.version)（Rust 契约 \(YuNianSchema.rustSupportedVersionRange.lowerBound)–\(YuNianSchema.rustSupportedVersionRange.upperBound)）")
                checkRow("FTS 实现", passed: environment.resolvedFTSVersion != "unknown",
                         detail: environment.resolvedFTSVersion)
                checkRow("表 / 索引 / 外键", passed: true,
                         detail: "\(YuNianSchema.tableCount) / \(YuNianSchema.indexCount) / \(YuNianSchema.foreignKeyCount)")
                if let path = try? AppPaths.databaseURL().path {
                    Text(path)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                }
            }

            Section("伴侣（Rust 人设来源）") {
                if let companion = environment.defaultCompanion {
                    checkRow("默认伴侣", passed: true,
                             detail: "#\(companion.id) \(companion.name) · 亲密度 \(companion.intimacy)")
                    Text(companion.personality)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    checkRow("Rust 直读的 7 列", passed: true,
                             detail: CompanionRepository.rustReadColumns.joined(separator: " / "))
                } else {
                    checkRow("默认伴侣", passed: false,
                             detail: "缺失 —— Rust 的 load_companion 会失败，人设不会注入")
                }
            }

            Section("表情标签（builtin_send_sticker 校验用）") {
                checkRow("可用标签", passed: !environment.stickerTagsSummary.isEmpty,
                         detail: environment.stickerTagsSummary)
                // 直接把下发给 Rust 的标签列出来。
                //
                // 为什么值得占版面：标签表为空时 send_sticker 会静默匹配不到，
                // 而「为什么模型发不出表情」这类问题没有这里是查不出来的。
                // 注意：全新安装下为空是**正常**的（Android 同理 ——
                // 只有用户导入的表情才进 sticker_tags，内置表情被排除）。
                if !environment.stickerTagList.isEmpty {
                    Text(environment.stickerTagList.joined(separator: "、"))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                } else {
                    Text("（空）全新安装下为空是正常的：只有导入的表情才会进表。")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Text("经 settings.stickers 下发给 Rust；内置工具不受 tools 门控，故这是活需求。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            Section("设备签名（V9 前置）") {
                checkRow("Secure Enclave", passed: SecureEnclaveSigner.isHardwareBacked,
                         detail: SecureEnclaveSigner.isHardwareBacked ? "硬件密钥" : "软件回退（模拟器正常）")
                checkRow("deviceId", passed: true,
                         detail: abbreviate(DeviceIdentity.deviceId))
                checkRow("keyId（sha256(SPKI)[:32]）",
                         passed: (try? SecureEnclaveSigner.keyId()) != nil,
                         detail: (try? SecureEnclaveSigner.keyId()).map(abbreviate) ?? "不可用")
            }

            Section("凭证") {
                LabeledContent("settings", value: environment.settingsSummary)
                    .font(.caption)
                LabeledContent("credentials", value: environment.credentialsSummary)
                    .font(.caption)

                // 拉取模型列表：填完 key 后先验证可用性，再决定模型名。
                // 没有它，填错 key 的症状是"发消息没反应"（第 40 轮列为上线即故障）。
                Button {
                    Task { await environment.loadServerModels() }
                } label: {
                    HStack {
                        Text("拉取模型列表")
                        if environment.modelsLoading {
                            Spacer()
                            ProgressView()
                        } else if !environment.serverModels.isEmpty {
                            Spacer()
                            Text("\(environment.serverModels.count) 个")
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .disabled(environment.modelsLoading)

                if let msg = environment.modelsMessage {
                    Text(msg)
                        .font(.caption)
                        // ⚠️ 第 108 轮：「该服务商不支持模型列表」是正常分支，
                        // 不是故障。此前一律标红，用户以为出错了。
                        .foregroundStyle(
                            environment.serverModels.isEmpty
                                && !environment.modelsNotSupported ? .red : .secondary
                        )
                    if environment.modelsNotSupported {
                        Text("可直接在上方「模型」处手动填写模型名，再用「测试连接」验证是否可用。")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }

                // 测试连接：解决「fetchModels 报"该API不支持模型列表查询"时
                // 怎么验证 key」的问题 —— 它走的是真对话端点。
                Button("测试连接") {
                    Task { await environment.testConnection() }
                }
                .disabled(environment.modelsLoading)
                .font(.caption)

                if !environment.serverModels.isEmpty {
                    // 可选中复制：用户要把模型名填进 provider 配置
                    Text(environment.serverModels.joined(separator: "、"))
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                        .lineLimit(6)
                }

                SecureField("API Key", text: $apiKeyDraft)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                // provider 选择：决定 baseUrl / model / formatHint。
                // 选错会静默打到错误 baseUrl，所以必须显式选而不是默认。
                Picker("服务商", selection: $providerDraft) {
                    ForEach(YuNianSeed.apiProviderPresets, id: \.provider) { preset in
                        Text(preset.displayName).tag(preset.provider)
                    }
                }
                .onChange(of: providerDraft) { _, newProvider in
                    // 换服务商时，把模型输入框重置为该服务商的预设默认值，
                    // 并在 placeholder 里显示它 —— 用户看得到"不填就用这个"。
                    if let preset = YuNianSeed.apiProviderPresets.first(where: { $0.provider == newProvider }) {
                        modelDraft = preset.model
                    }
                }

                // ⚠️ 第 106 轮：模型名改为用户可填。
                // 此前只能"可选中复制"模型列表里的名字，没有回填入口；
                // 真正生效的 model 只有预设常量一个来源，用户改不了。
                // 现在：留空 = 用预设默认值；填写 = 覆盖。
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

                TextField("昵称（owner_name）", text: $ownerNameDraft)

                if let credentialError {
                    Text(credentialError).font(.caption).foregroundStyle(.red)
                }

                Button("保存并下发") {
                    do {
                        // ⚠️ modelDraft 空串按"用预设"处理，不要传空串覆盖
                        let model = modelDraft.trimmingCharacters(in: .whitespaces)
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
            }

            // 备份导入（V8）：第 120 轮发现 BackupImporter/BackupCrypto 无 UI 入口，
            // 用户完全触达不到 —— 此处补上导航入口。
            NavigationLink("备份导入") { BackupImportView() }
                .disabled(environment.database == nil)

            // 消息搜索（第 142 轮）：FTS 写入/维护侧早已实现并验证，
            // 但 searchMessageIds 无调用方 —— 索引只写不读。此处补上查询侧消费方。
            NavigationLink("搜索消息") { MessageSearchView() }
                .disabled(environment.database == nil)

            Section("中文分词（逐字复刻自 Android）") {
                let sample = "今天天气不错"
                Text("索引：\(MessageSearchTokenizer.indexTokens(sample))")
                    .font(.caption.monospaced())
                Text("查询：\(MessageSearchTokenizer.matchQuery(sample) ?? "nil")")
                    .font(.caption.monospaced())
                Text("若与 Android 侧不一致会导致「搜不到」且不报错；回归测试见 YuNianTests。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .task {
            ownerNameDraft = KeychainStore.string(for: KeychainStore.Key.ownerName) ?? ""
            if let key = KeychainStore.string(for: KeychainStore.Key.apiKey), !key.isEmpty {
                apiKeyDraft = key
            }
            // 用「当前生效的配置」回填选择器，避免用户误以为选的是别家
            if let active = environment.apiConfigs?.tryActiveConfig(),
               YuNianSeed.apiProviderPresets.contains(where: { $0.provider == active.provider }) {
                providerDraft = active.provider
                // ⚠️ 第 106 轮：同时回填已生效的模型名。
                // 不回填的话，用户想"只改 model"时会看见空框，
                // 一旦留空保存就把 model 覆盖成预设值 —— 静默丢配置。
                if !active.model.isEmpty {
                    modelDraft = active.model
                }
            }
        }
    }

    private func abbreviate(_ value: String) -> String {
        value.count <= 16 ? value : String(value.prefix(16)) + "…"
    }

    private func checkRow(_ title: String, passed: Bool, detail: String) -> some View {
        HStack(alignment: .top) {
            Image(systemName: passed ? "checkmark.circle.fill" : "xmark.circle.fill")
                .foregroundStyle(passed ? .green : .red)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func failureView(_ message: String) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Label("启动失败", systemImage: "exclamationmark.triangle.fill")
                    .font(.headline)
                    .foregroundStyle(.red)
                Text(message).font(.callout)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        }
    }
}
