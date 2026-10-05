import SwiftUI

/// 渠道配置页 —— 对齐 Android `PetalApiConfigEditDialog`
/// (feature/settings/.../ui/screen/PetalApiCards.kt:126-540)。
///
/// ## 为什么之前"自定义 API 没地方填 URL"
/// `ApiConfigRepository.upsertActiveConfig` **一直支持** `name` / `baseUrl` /
/// `formatHint` 三个形参，但 iOS 的 UI 只暴露了 provider / model / apiKey。
/// 于是在 CUSTOM provider 下 baseUrl 永远是预设里的空串 ——
/// 用户选了「自定义 API」却发现无处可填，而这正是它唯一必须填的一项。
///
/// ## 字段与显示条件（逐条对照 Kotlin，不自己发明）
/// | 字段 | Kotlin | 本实现 |
/// |---|---|---|
/// | API 名称 | 仅 `isCustom` | 仅 CUSTOM |
/// | API Key | 总是，密码样式 | 同 |
/// | Base URL | 总是，placeholder「填到能拼 /chat/completions 的那一层」 | 同 |
/// | API 格式 | 仅 `isCustom`，下拉 openai/anthropic | 同 |
/// | Model | `isCustomAnthropic` 时必填 | 同 |
///
/// ⚠️ 未移植：`temperature` / `maxTokens` 滑杆。Android 有，
/// 但 `upsertActiveConfig` 已有默认值(0.7 / nil)，且它们是调参项不是接入项 ——
/// 先补"能不能接上"，再谈"调得多好"。
struct ChannelConfigView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    /// 预选的服务商（从首屏带进来；根视图默认 OPENAI）。
    let initialProvider: String

    @State private var provider: String
    @State private var name: String = ""
    @State private var apiKey: String = ""
    @State private var baseUrl: String = ""
    @State private var formatHint: String = "openai"
    @State private var model: String = ""
    @State private var errorText: String?
    @State private var saved = false

    init(provider: String) {
        self.initialProvider = provider
        _provider = State(initialValue: provider)
    }

    /// 是否为自定义 API —— 决定 API 名称 / API 格式 两个字段是否显示。
    private var isCustom: Bool { provider == "CUSTOM" }

    /// Model 字段是否必填。
    ///
    /// Kotlin 的 `isCustomAnthropic`：自定义且选 Anthropic 格式时才强制填模型名。
    /// 这与 Rust 侧"OpenAI 兼容可从 baseUrl 推断默认模型"的行为对应。
    private var isCustomAnthropic: Bool { isCustom && formatHint == "anthropic" }

    private var preset: ApiProviderPreset? {
        YuNianSeed.apiProviderPresets.first { $0.provider == provider }
    }

    var body: some View {
        NavigationStack {
            Form {
                // ── 服务商选择 ──
                Section {
                    Picker("服务商", selection: $provider) {
                        ForEach(YuNianSeed.apiProviderPresets, id: \.provider) { p in
                            Text(p.displayName).tag(p.provider)
                        }
                    }
                    .onChange(of: provider) { _, newValue in
                        applyPreset(newValue)
                    }
                } footer: {
                    // Android 在预设列表里直接把 baseUrl 显示在副标题
                    // （SettingsScreen.kt:501），这里保持同一信息密度。
                    if let preset, !preset.baseUrl.isEmpty {
                        Text("默认地址：\(preset.baseUrl)")
                            .font(.caption2)
                    }
                }

                Section {
                    if isCustom {
                        // ⚠️ 顺序与 Kotlin 一致：API 名称在最前（PetalApiCards.kt:241）
                        TextField("API 名称", text: $name)
                            .autocorrectionDisabled()
                    }

                    SecureField("API Key", text: $apiKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    // ⚠️ Base URL 此前**完全没暴露**，这是用户报的缺口
                    TextField("Base URL", text: $baseUrl)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.body.monospaced())
                        .keyboardType(.URL)

                    if isCustom {
                        Picker("API 格式", selection: $formatHint) {
                            Text("OpenAI 兼容").tag("openai")
                            Text("Anthropic 兼容").tag("anthropic")
                        }
                    }

                    TextField("Model", text: $model)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.body.monospaced())
                } header: {
                    Text("接入参数")
                } footer: {
                    // Kotlin 的 placeholder 原文（PetalApiCards.kt:302-308）
                    Text("Base URL 填到能拼 /chat/completions 的那一层，如 https://api.openai.com/v1")
                        .font(.caption2)
                }

                // ── 校验动作（复用既有能力，不重写一遍探测逻辑）──
                Section {
                    if environment.modelsLoading {
                        ProgressView()
                    } else {
                        Button("拉取模型列表") { Task { await environment.loadServerModels() } }
                        Button("测试连接") { Task { await environment.testConnection() } }
                    }

                    if let msg = environment.modelsMessage {
                        Text(msg)
                            .font(.caption)
                            .foregroundStyle(
                                environment.serverModels.isEmpty
                                    && !environment.modelsNotSupported ? .red : .secondary
                            )
                    }
                    if !environment.serverModels.isEmpty {
                        Text(environment.serverModels.joined(separator: "、"))
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                            .lineLimit(8)
                    }
                }

                if let errorText {
                    Section {
                        Text(errorText)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("模型渠道")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                        .bold()
                }
            }
            .task { prefill() }
        }
    }

    // MARK: - 预填 / 切换预设

    /// 用「当前生效配置」预填，与 Android 打开编辑框时的行为一致。
    private func prefill() {
        if let active = environment.apiConfigs?.tryActiveConfig() {
            provider = active.provider
            name = active.name
            baseUrl = active.baseUrl
            model = active.model
            formatHint = active.formatHint
            if let key = KeychainStore.string(for: KeychainStore.Key.apiKey), !key.isEmpty {
                apiKey = key
            }
        } else {
            applyPreset(initialProvider)
        }
    }

    /// 切换服务商时用该家预设重填 baseUrl/model/formatHint。
    ///
    /// 与 Android 从预设新建配置时同源（SettingsScreen.kt:469-476）。
    private func applyPreset(_ value: String) {
        guard let p = YuNianSeed.apiProviderPresets.first(where: { $0.provider == value }) else {
            return
        }
        baseUrl = p.baseUrl
        model = p.model
        formatHint = p.formatHint
        if name.isEmpty || YuNianSeed.apiProviderPresets
            .contains(where: { $0.displayName == name }) {
            name = p.displayName
        }
    }

    // MARK: - 保存

    private func save() {
        // ── 校验（对齐 Rust load_api_config 的硬性要求，见仓库注释 L128）──
        let trimmedBase = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedModel = model.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)

        if isCustom, trimmedBase.isEmpty {
            errorText = "自定义 API 必须填写 Base URL"
            return
        }
        if trimmedBase.isEmpty {
            errorText = "Base URL 不能为空"
            return
        }
        if isCustomAnthropic, trimmedModel.isEmpty {
            errorText = "Anthropic 兼容格式必须填写模型名"
            return
        }
        if trimmedModel.isEmpty {
            errorText = "模型名不能为空（Rust 每个回合都会校验）"
            return
        }
        if trimmedKey.isEmpty && provider != "PARTNER" {
            errorText = "请填写 API Key"
            return
        }

        do {
            // 先落库（Rust 的关键路径依赖这条启用行），再写 Keychain
            _ = try environment.apiConfigs?.upsertActiveConfig(
                provider: provider,
                model: trimmedModel,
                baseUrl: trimmedBase,
                name: name.isEmpty ? (preset?.displayName ?? provider) : name,
                formatHint: formatHint
            )
            try environment.setAPIKey(
                trimmedKey,
                provider: provider,
                model: trimmedModel
            )
            environment.syncRuntimeConfig()
            saved = true
            errorText = nil
            dismiss()
        } catch {
            errorText = String(describing: error)
        }
    }
}
