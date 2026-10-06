import SwiftUI

/// 渠道配置页 —— 对齐 Android `PetalApiConfigEditDialog`
/// (feature/settings/.../ui/screen/PetalApiCards.kt:126-540)。
///
/// ## 第 128 轮：套上设计系统
/// 上一版是裸 `Form` + 系统 `TextField`，与 Android 观感完全无关。
/// 本版逐条对照 Kotlin 的表单规格：
/// - 输入框 `OutlinedTextField` 统一 `shape = RoundedCornerShape(12.dp)`、
///   容器 `surfaceVariant@0.62`、聚焦边框 `PetalPrimary`、未聚焦 `outline`
///   （PetalApiCards.kt:248-255）→ 改用 `YuNianField`
/// - 卡片 16dp 圆角玻璃（PetalApiCards.kt:669）→ `YuNianGlassCard`
/// - 保存/取消用 `GlassButton` 的全胶囊 + 高度 48dp
///
/// ## 显示条件（逐字对应 Kotlin，不自己发明）
/// | 字段 | Kotlin | 本实现 |
/// |---|---|---|
/// | API 名称 | isCustom | CUSTOM |
/// | API Key | 总是（密码样式） | 同 |
/// | Base URL | 总是 | 同 |
/// | API 格式 | isCustom（openai/anthropic） | 同 |
/// | Model | isCustomAnthropic | 同 |
struct ChannelConfigView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

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

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }
    private var isDark: Bool { scheme == .dark }

    /// 是否为自定义 API —— 决定 API 名称 / API 格式 两个字段是否显示。
    private var isCustom: Bool { provider == "CUSTOM" }

    /// Model 字段是否必填。
    ///
    /// Kotlin 的 `isCustomAnthropic`：自定义且选 Anthropic 格式时才强制填模型名。
    /// 这与 Rust 侧"OpenAI 兼容可从 baseUrl 推断默认模型"的行为对应。
    private var isCustomAnthropic: Bool { isCustom && formatHint == "anthropic" }

    private var preset: YuNianSeed.ApiProviderPreset? {
        YuNianSeed.apiProviderPresets.first { $0.provider == provider }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: YuNianTheme.Space.standard) {

                    YuNianSectionTitle(title: "服务商")

                    // 服务商选择卡。Android 是「添加 API」后弹预设列表
                    // （SettingsScreen.kt:440-514），每项 ProviderLogo + 两行文字；
                    // 这里是已选定后的单卡展示 + Picker。
                    YuNianGlassCard {
                        VStack(alignment: .leading, spacing: YuNianTheme.Space.cardPadding) {
                            Picker("服务商", selection: $provider) {
                                ForEach(YuNianSeed.apiProviderPresets, id: \.provider) { p in
                                    Text(p.displayName).tag(p.provider)
                                }
                            }
                            .onChange(of: provider) { _, newValue in
                                applyPreset(newValue)
                            }

                            if let preset, !preset.baseUrl.isEmpty {
                                Text("默认地址：\(preset.baseUrl)")
                                    .font(.system(size: 11))
                                    .foregroundStyle(colors.textTertiary)
                                    .textSelection(.enabled)
                            }
                        }
                    }

                    YuNianSectionTitle(title: "接入参数")

                    YuNianGlassCard {
                        VStack(alignment: .leading, spacing: YuNianTheme.Space.cardPadding) {
                            if isCustom {
                                // 顺序与 Kotlin 一致：API 名称在最前
                                YuNianField("API 名称", text: $name)
                            }

                            YuNianField("API Key", text: $apiKey, isSecure: true)

                            // Base URL 此前完全没暴露，这是用户报的缺口
                            YuNianField("Base URL", text: $baseUrl,
                                        monospaced: true, keyboard: .URL)

                            if isCustom {
                                VStack(alignment: .leading, spacing: YuNianTheme.Space.half) {
                                    Text("API 格式")
                                        .font(YuNianTheme.TextStyle.settingsRowSubtitle)
                                        .foregroundStyle(colors.textSecondary)
                                    Picker("API 格式", selection: $formatHint) {
                                        Text("OpenAI 兼容").tag("openai")
                                        Text("Anthropic 兼容").tag("anthropic")
                                    }
                                    .pickerStyle(.segmented)
                                }
                            }

                            YuNianField("Model", text: $model, monospaced: true)
                        }
                    }

                    // Kotlin 的 placeholder 原文（PetalApiCards.kt:302-308）
                    Text("Base URL 填到能拼 /chat/completions 的那一层，如 https://api.openai.com/v1")
                        .font(.system(size: 11))
                        .foregroundStyle(colors.textTertiary)
                        .padding(.horizontal, YuNianTheme.Space.minUnit)

                    YuNianSectionTitle(title: "验证")

                    YuNianGlassCard {
                        VStack(alignment: .leading, spacing: YuNianTheme.Space.cardPadding) {
                            if environment.modelsLoading {
                                HStack(spacing: YuNianTheme.Space.standard) {
                                    ProgressView()
                                    Text("正在请求…")
                                        .font(YuNianTheme.TextStyle.settingsRowSubtitle)
                                        .foregroundStyle(colors.textSecondary)
                                }
                            } else {
                                HStack(spacing: YuNianTheme.Space.standard) {
                                    YuNianGlassButton(
                                        onClick: { Task { await environment.loadServerModels() } },
                                        height: 34, horizontalPadding: 12
                                    ) {
                                        Text("拉取模型列表")
                                            .font(YuNianTheme.TextStyle.cardAction)
                                            .foregroundStyle(colors.textPrimary)
                                    }
                                    YuNianGlassButton(
                                        onClick: { Task { await environment.testConnection() } },
                                        height: 34, horizontalPadding: 12
                                    ) {
                                        Text("测试连接")
                                            .font(YuNianTheme.TextStyle.cardAction)
                                            .foregroundStyle(colors.textPrimary)
                                    }
                                }
                            }

                            if let msg = environment.modelsMessage {
                                Text(msg)
                                    .font(.caption)
                                    .foregroundStyle(
                                        environment.serverModels.isEmpty
                                            && !environment.modelsNotSupported
                                            ? colors.danger : colors.textSecondary
                                    )
                                    .textSelection(.enabled)
                            }
                            if !environment.serverModels.isEmpty {
                                Text(environment.serverModels.joined(separator: "、"))
                                    .font(.caption.monospaced())
                                    .foregroundStyle(colors.textSecondary)
                                    .textSelection(.enabled)
                                    .lineLimit(8)
                            }
                        }
                    }

                    if let errorText {
                        Text(errorText)
                            .font(.caption)
                            .foregroundStyle(colors.danger)
                            .textSelection(.enabled)
                            .padding(.horizontal, YuNianTheme.Space.minUnit)
                    }

                    Spacer(minLength: YuNianTheme.Space.pageTop)
                }
                .padding(.horizontal, YuNianTheme.Space.page)
                .padding(.top, YuNianTheme.Space.standard)
            }
            .background(colors.background.ignoresSafeArea())
            .navigationTitle("模型渠道")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                        .foregroundStyle(colors.textSecondary)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                        .bold()
                        .foregroundStyle(colors.primary)
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
        guard let p = YuNianSeed.apiProviderPresets
            .first(where: { $0.provider == value }) else { return }
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
            _ = try environment.apiConfigs?.upsertActiveConfig(
                provider: provider,
                model: trimmedModel,
                baseUrl: trimmedBase,
                name: name.isEmpty ? (preset?.displayName ?? provider) : name,
                formatHint: formatHint
            )
            try environment.setAPIKey(trimmedKey, provider: provider, model: trimmedModel)
            environment.syncRuntimeConfig()
            saved = true
            errorText = nil
            dismiss()
        } catch {
            errorText = String(describing: error)
        }
    }
}
