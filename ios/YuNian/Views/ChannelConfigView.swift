import SwiftUI

/// 单条模型渠道的编辑页。
///
/// `config == nil` 表示新增；否则编辑已有那条。
///
/// ## 两个必须遵守的语义
///
/// **① 保存 = 设为当前。**
/// `AppEnvironment.setAPIKey` 走 `ensureActiveApiConfig` → `upsertActiveConfig`，
/// 后者会把该 provider 的行置为 `isEnabled = 1` 并停用其它行；
/// 随后密钥写入的是**当前启用行**对应的钥匙串槽。
/// 因此保存一条渠道，就等于把它切成当前渠道 —— UI 必须明说，
/// 否则用户会以为只是"改了参数"，实际请求已经换到别家了。
///
/// **② 自定义 baseUrl 必须在 `setAPIKey` 之后再写。**
/// `ensureActiveApiConfig` 会用预设的 baseUrl 覆盖行，
/// 顺序反了用户填的地址就被冲掉。`upsertActiveConfig` 只改行、不碰钥匙串，
/// 所以①写进去的密钥不会受影响。
///
/// ## ③ 测试/拉模型都读"当前启用配置"
/// 所以必须**先保存再测试**。反过来测的是上一次的配置，
/// 症状是"明明填对了却连不上"。
struct ChannelConfigView: View {

    let config: ApiConfigRepository.ApiConfig?

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var provider = "OPENAI"
    @State private var model = ""
    @State private var baseUrl = ""
    @State private var apiKey = ""
    @State private var temperature = 0.7
    @State private var maxTokensText = ""
    @State private var configName = ""
    @State private var extraKeys = ""
    @State private var formatHint = ""

    @State private var status: Status?
    @State private var didLoad = false

    private enum Status: Equatable {
        case ok(String)
        case failure(String)
    }

    private var isEditing: Bool { config != nil }

    private var preset: YuNianSeed.ApiProviderPreset? {
        environment.visibleApiPresets().first { $0.provider == provider }
    }

    /// 当前启用行是不是正在编辑的这条 —— 决定「测试连接」有没有意义。
    private var isActiveRow: Bool {
        guard let active = try? environment.apiConfigs?.activeConfig() else { return false }
        guard let config else { return true }   // 新增：保存后即成当前
        return active.id == config.id
    }

    /// 已保存的额外密钥数量（只给数量，不回显内容）。
    private var savedExtraKeyCount: Int {
        guard let config else { return 0 }
        return environment.extraAPIKeyCount(configId: config.id)
    }

    private var hasSavedKey: Bool {
        guard let config else { return false }
        return !environment.resolvedAPIKey(configId: config.id).isEmpty
    }

    var body: some View {
        List {
            providerSection
            keySection
            connectionSection
            generationSection
            actionSection
            balanceSection
            statusSection
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle(isEditing ? "编辑渠道" : "添加渠道")
        .navigationBarTitleDisplayMode(.inline)
        .task { loadOnce() }
        .onChange(of: provider) { _, newValue in
            // 只在新增时跟随预设，避免编辑已有条目时被悄悄改掉参数
            if !isEditing { applyPreset(newValue) }
        }
    }

    // MARK: - 服务商与模型

    private var providerSection: some View {
        Section {
            if isEditing {
                // 编辑时锁定服务商：`upsertActiveConfig` 按 provider 找行，
                // 改了 provider 会写到**另一行**，看起来像"改了没生效"。
                LabeledContent("服务商", value: preset?.displayName ?? provider)
            } else {
                Picker("服务商", selection: $provider) {
                    ForEach(environment.visibleApiPresets(), id: \.provider) { item in
                        Text(item.displayName).tag(item.provider)
                    }
                }
                .pickerStyle(.menu)
            }

            // 自定义名称：多配置时用于区分（例如"号1 / 号2"）。
            // `api_configs.name` 列与 `upsertActiveConfig(name:)` 早就支持，
            // 之前一直没用上。
            LabeledContent("名称") {
                TextField(provider, text: $configName)
                    .multilineTextAlignment(.trailing)
                    .autocorrectionDisabled()
            }

            LabeledContent("模型") {
                TextField("模型名", text: $model)
                    .multilineTextAlignment(.trailing)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            }

            Button {
                Task { await loadModels() }
            } label: {
                HStack {
                    if environment.modelsLoading {
                        ProgressView().controlSize(.small)
                        Text("拉取中…")
                    } else {
                        Text("从服务端拉取模型列表")
                    }
                    Spacer()
                }
                .font(.subheadline)
            }
            .disabled(environment.modelsLoading || !isActiveRow || !hasSavedKey)

            if !environment.serverModels.isEmpty {
                ForEach(environment.serverModels, id: \.self) { name in
                    Button {
                        model = name
                    } label: {
                        HStack {
                            Text(name).font(.subheadline)
                            Spacer()
                            if name == model {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(YNTheme.palette(scheme).accent)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        } header: {
            Text("服务商与模型")
        } footer: {
            if !isActiveRow {
                Text("这条不是当前渠道。保存后会切换为当前渠道，之后才能测试或拉取模型。")
            } else if !hasSavedKey {
                Text("先填密钥并保存，才能拉取模型列表或测试连接。")
            }
        }
    }

    // MARK: - 密钥

    private var keySection: some View {
        Section {
            SecureField(hasSavedKey ? "已保存（重新输入可覆盖）" : "API Key", text: $apiKey)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .accessibilityLabel("API Key")

            // 多密钥轮换：Rust 的 all_api_keys() 会把 extra_api_keys
            // 按逗号 split 后一起用。上一版 Swift 只下发主 key，这个字段恒为 nil。
            VStack(alignment: .leading, spacing: YNTheme.Space.xs) {
                TextField("额外密钥（可选，多个用逗号或换行分隔）", text: $extraKeys, axis: .vertical)
                    .lineLimit(2...5)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .accessibilityLabel("额外密钥")
                if savedExtraKeyCount > 0, extraKeys.isEmpty {
                    Text("已保存 \(savedExtraKeyCount) 个额外密钥")
                        .font(.caption)
                        .foregroundStyle(YNTheme.palette(scheme).textSecondary)
                }
            }
        } header: {
            Text("密钥")
        } footer: {
            Text("密钥存放在系统钥匙串（Keychain），不写入数据库明文。填多个时按顺序轮换，单个失败会自动换下一个。")
        }
    }

    // MARK: - 接口

    /// 接口地址。
    ///
    /// ## 为什么不收进「高级」（第 202 轮，用户要求 + 竞品印证）
    /// 用户明确要求：**不要在详细页里再套一层二级菜单，所有可配项一眼看全**。
    /// 竞品 Aru 的「自定义服务」页也是这个做法 ——
    /// 供应商名称 / 接口格式 / Base URL / API Key / 当前模型 全部平铺，
    /// 一个 DisclosureGroup 都没有。
    ///
    /// 收进折叠区的真实代价不是"少看几个字段"，而是
    /// **用户不知道那里还有东西可调**：他只会照着表面填，
    /// 于是"怎么调都不对"却找不到原因。
    private var connectionSection: some View {
        Section {
            LabeledContent("接口地址") {
                TextField(preset?.baseUrl ?? "https://…", text: $baseUrl)
                    .multilineTextAlignment(.trailing)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .font(.footnote)
            }
            if let preset, !baseUrl.isEmpty, baseUrl != preset.baseUrl {
                Button("恢复 \(preset.displayName) 默认地址") {
                    baseUrl = preset.baseUrl
                }
                .font(.footnote)
                .foregroundStyle(YNTheme.palette(scheme).accent)
            }
        } header: {
            Text("接口")
        } footer: {
            Text("自建网关或代理才需要改；留空则用服务商默认地址。")
        }
    }

    // MARK: - 生成参数

    /// 协议格式 / 温度 / 最大回复长度 —— 同样全部平铺。
    ///
    /// 这三项都是 Rust `load_api_config` **真正读取**的字段，
    /// 不给出入口用户只能吃默认值，且无从判断"为什么回复风格不对"。
    private var generationSection: some View {
        Section {
            // 多数兼容网关自称 OpenAI 协议，少数只认 Anthropic。
            // `apiUsesAnthropicProtocol(provider:formatHint:)` 优先看 formatHint。
            Picker("协议格式", selection: $formatHint) {
                Text("跟随服务商").tag("")
                Text("OpenAI 兼容").tag("openai")
                Text("Anthropic").tag("anthropic")
            }

            VStack(alignment: .leading, spacing: YNTheme.Space.xs) {
                HStack {
                    Text("温度")
                    Spacer()
                    Text(String(format: "%.1f", temperature))
                        .foregroundStyle(YNTheme.palette(scheme).textSecondary)
                        .monospacedDigit()
                }
                Slider(value: $temperature, in: 0...2, step: 0.1) {
                    Text("温度")
                } minimumValueLabel: {
                    Text("0").font(.caption2)
                } maximumValueLabel: {
                    Text("2").font(.caption2)
                }
                .accessibilityValue(String(format: "%.1f", temperature))
            }

            LabeledContent("最大回复长度") {
                TextField("不限", text: $maxTokensText)
                    .multilineTextAlignment(.trailing)
                    .keyboardType(.numberPad)
            }
        } header: {
            Text("生成参数")
        } footer: {
            Text("协议格式通常不用改，只有服务商自称 OpenAI 兼容却只认 Anthropic 时才需要。温度越高回复越发散，默认 0.7；最大回复长度留空表示由服务端决定。")
        }
    }

    // MARK: - 动作

    private var actionSection: some View {
        let c = YNTheme.palette(scheme)
        return Section {
            Button {
                save()
            } label: {
                Text(isEditing ? "保存并设为当前" : "保存")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(c.accent)
            .disabled(!canSave)

            Button {
                Task { await test() }
            } label: {
                HStack {
                    if environment.modelsLoading {
                        ProgressView().controlSize(.small)
                    }
                    Text("测试连接")
                    Spacer()
                }
            }
            .buttonStyle(.bordered)
            .disabled(environment.modelsLoading || !isActiveRow || !hasSavedKey)
        }
    }

    /// 余额。
    ///
    /// 与「测试连接」同一条线程约束：`queryBalance` 也是阻塞调用，
    /// 已下沉到 `AppEnvironment` 的后台队列（不要再在主线程直接调）。
    ///
    /// 服务端**大多不返回结构化余额**，所以有值才显示对应行 ——
    /// 宁可少几行，也不要摆一排 "—" 让人以为坏了。
    @ViewBuilder
    private var balanceSection: some View {
        let c = YNTheme.palette(scheme)
        Section {
            Button {
                Task { await environment.queryBalance() }
            } label: {
                HStack {
                    if environment.balanceLoading {
                        ProgressView().controlSize(.small)
                    }
                    Text("查询余额")
                    Spacer()
                }
            }
            .disabled(environment.balanceLoading || !isActiveRow || !hasSavedKey)

            if let balance = environment.balance {
                if let remaining = balance.remainingBalance {
                    LabeledContent("剩余", value: String(format: "%.2f", remaining))
                }
                if let used = balance.totalUsed {
                    LabeledContent("已用", value: String(format: "%.2f", used))
                }
                if let limit = balance.totalLimit {
                    LabeledContent("总额度", value: String(format: "%.2f", limit))
                }
                if let available = balance.totalAvailable {
                    LabeledContent("可用", value: String(format: "%.2f", available))
                }
                if let raw = balance.rawSubscription, !raw.isEmpty {
                    Text(raw).font(.caption).foregroundStyle(c.textSecondary)
                }
            } else if let message = environment.balanceMessage {
                Text(message).font(.caption).foregroundStyle(c.textSecondary)
            }
        } header: {
            Text("余额")
        } footer: {
            Text("多数服务商不返回结构化余额，此时只显示服务端返回的原文。")
        }
    }

    @ViewBuilder
    private var statusSection: some View {
        let c = YNTheme.palette(scheme)
        if let status {
            Section {
                switch status {
                case let .ok(text):
                    Label(text, systemImage: "checkmark.circle.fill")
                        .foregroundStyle(c.success)
                        .font(.subheadline)
                case let .failure(text):
                    Label(text, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(c.warning)
                        .font(.subheadline)
                }
            }
        } else if let message = environment.modelsMessage {
            Section {
                Text(message).font(.subheadline).foregroundStyle(c.textSecondary)
            }
        }
    }

    // MARK: - 逻辑

    private var canSave: Bool {
        isEditing || !model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func loadOnce() {
        guard !didLoad else { return }
        didLoad = true

        if let config {
            provider = config.provider
            model = config.model
            baseUrl = config.baseUrl
            // name 默认等于 provider，此时留空让占位符显示 provider，避免噪声
            configName = config.name == config.provider ? "" : config.name
            formatHint = config.formatHint
        } else if let active = try? environment.apiConfigs?.activeConfig() {
            // 新增时以当前渠道为起点，减少重复输入
            provider = active.provider
            model = active.model
            baseUrl = active.baseUrl
            temperature = 0.7
        } else {
            applyPreset(provider)
        }
        if baseUrl.isEmpty { applyPreset(provider) }
    }

    private func applyPreset(_ provider: String) {
        guard let preset = environment.visibleApiPresets().first(where: { $0.provider == provider })
        else { return }
        baseUrl = preset.baseUrl
        model = preset.model
    }

    private func save() {
        let key = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let modelName = model.trimmingCharacters(in: .whitespacesAndNewlines)
        let url = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let maxTokens = Int(maxTokensText.trimmingCharacters(in: .whitespacesAndNewlines))

        do {
            // ① 建/更新启用行 + 写钥匙串（内部走 ensureActiveApiConfig）
            try environment.setAPIKey(
                key,
                provider: provider,
                model: modelName.isEmpty ? nil : modelName
            )

            // ② 其余字段统一用 upsertActiveConfig 落库。
            //    必须晚于①：ensureActiveApiConfig 会用预设 baseUrl 覆盖，
            //    顺序反了自定义地址就被冲掉。它只改行、不碰钥匙串。
            if let preset {
                let trimmedName = configName.trimmingCharacters(in: .whitespacesAndNewlines)
                try environment.apiConfigs?.upsertActiveConfig(
                    provider: provider,
                    model: modelName,
                    baseUrl: url.isEmpty ? preset.baseUrl : url,
                    name: trimmedName.isEmpty ? nil : trimmedName,
                    temperature: temperature,
                    maxTokens: maxTokens,
                    // 空表示跟随服务商预设
                    formatHint: formatHint.isEmpty ? preset.formatHint : formatHint
                )
            }

            // ③ 额外密钥写进"刚变成当前的"那条配置的钥匙串槽。
            //    必须在①②之后 —— 槽名按 configId 定，而 configId 要等
            //    upsertActiveConfig 建好行才拿得到。
            let extras = extraKeys.trimmingCharacters(in: .whitespacesAndNewlines)
            if !extras.isEmpty, let active = try? environment.apiConfigs?.activeConfig() {
                environment.setExtraAPIKeys(extras, configId: active.id)
            }

            apiKey = ""
            extraKeys = ""
            maxTokensText = maxTokens.map(String.init) ?? ""
            status = .ok("已保存并设为当前渠道。可以测试连接，或直接回聊天页发消息。")
        } catch {
            // 不把底层错误文本抛给用户（UI 不暴露 SQL / 路径 / 技术细节）
            status = .failure("保存失败，请重试。")
        }
    }

    private func test() async {
        status = nil
        await environment.testConnection()
        if let message = environment.modelsMessage {
            status = message.hasPrefix("连接成功") ? .ok(message) : .failure(message)
        }
    }

    private func loadModels() async {
        status = nil
        await environment.loadServerModels()
        if environment.modelsNotSupported {
            status = .failure("该服务商不支持模型列表查询，请手动填写模型名。")
        } else if !environment.serverModels.isEmpty {
            status = .ok("拉到 \(environment.serverModels.count) 个模型，点选即可。")
        } else if let message = environment.modelsMessage {
            status = .failure(message)
        }
    }
}
