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

    // MARK: - 已保存的渠道（第 182 轮）
    //
    // `upsertActiveConfig` 按 provider 找行，所以实际形态是"每家一行"。
    // 用户切过几家后数据都留着，但仓库层原来只有 `activeConfig()`
    // （读当前启用那一条）—— **没有任何方法能列出全部**，
    // 于是想切回之前那家只能重新填一遍。
    @State private var savedConfigs: [ApiConfigRepository.ApiConfig] = []

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
        // 第 133 轮：改用 YuNianGlassPage（对照 Android GlassTopBar/GlassPageScaffold），
        // 不再用系统 NavigationBar + ScrollView。
        // ⚠️ 第 145 轮：trailing 槽接上「保存」。
        // 在此之前 YuNianGlassPage 没有动作槽，save() 定义了却无人调用 ——
        // 用户填完渠道配置**无法保存**。find_dead_swift 关卡抓到的。
        YuNianGlassPage(
            title: "模型渠道",
            onBack: { dismiss() },
            trailing: {
                Button("保存") { save() }
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(colors.primary)
            }
        ) {
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

                                // ⚠️ 第 181 轮：不支持模型列表时给出下一步。
                                // 之前只显示"XX 不支持模型列表查询"就停了 ——
                                // 用户看到这句话并不知道该干什么。
                                // Kotlin 同类提示见 ImageGenerationService.kt:81-83：
                                // "请确认该地址支持 /images/generations，或手动填写模型名"。
                                if environment.modelsNotSupported {
                                    Text("该服务商不提供模型列表，请手动在上方 Model 框填写模型名。")
                                        .font(.system(size: 11))
                                        .foregroundStyle(colors.textTertiary)
                                        .padding(.top, YuNianTheme.Space.tight)
                                }
                            }
                            if !environment.serverModels.isEmpty {
                                // ⚠️ 第 181 轮：改成**可点选**列表。
                                //
                                // 之前是
                                //     Text(environment.serverModels.joined(separator: "、"))
                                //         .lineLimit(8)
                                // 两个问题：
                                // 1. 模型一多就被截断，看不到全部
                                // 2. **点不了** —— 用户得手动回上方 Model 框抄名字
                                //
                                // 而 `loadServerModels` 存在的全部意义
                                // （见 AppEnvironment.swift:381-384 的注释）就是
                                // "让用户不必手填模型名"。展示成纯文本，
                                // 等于把这个意义抵消掉了。
                                VStack(alignment: .leading, spacing: YuNianTheme.Space.half) {
                                    Text("共 \(environment.serverModels.count) 个 · 点选填入上方 Model")
                                        .font(.system(size: 11))
                                        .foregroundStyle(colors.textTertiary)

                                    ScrollView {
                                        LazyVStack(alignment: .leading, spacing: 0) {
                                            ForEach(environment.serverModels, id: \.self) { m in
                                                        Button {
                                                            model = m
                                                        } label: {
                                                            HStack(spacing: YuNianTheme.Space.half) {
                                                                Text(m)
                                                                    .font(.system(size: 12).monospaced())
                                                                    .lineLimit(1)
                                                                Spacer(minLength: 0)
                                                                if m == model {
                                                                    Image(systemName: "checkmark")
                                                                        .font(.system(size: 11, weight: .semibold))
                                                                }
                                                            }
                                                            .foregroundStyle(
                                                                m == model ? colors.primary : colors.textSecondary
                                                            )
                                                            .padding(.vertical, YuNianTheme.Space.tight)
                                                        }
                                                        .buttonStyle(.plain)
                                                    }
                                                }
                                    }
                                    .frame(maxHeight: 180)
                                }
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

                    // ── 已保存的渠道（第 182 轮）────────────────────
                    if !savedConfigs.isEmpty {
                        YuNianSectionTitle(title: "已保存的渠道")

                        YuNianGlassCard {
                            VStack(alignment: .leading, spacing: YuNianTheme.Space.tight) {
                                Text("点选切换。同一时刻只有一条生效 —— Rust 读的就是那一条。")
                                    .font(.system(size: 11))
                                    .foregroundStyle(colors.textTertiary)

                                // ⚠️ 第 183 轮：分隔线只画在**行与行之间**。
                                // 上一版我写了
                                //     if cfg.id != savedConfigs.first?.id || true
                                // `|| true` 恒真 → 第一行上面也多一条。
                                // 那种"带条件但恒真"的写法比无条件更糟：
                                // 它看起来像有逻辑，其实没有。
                                ForEach(Array(savedConfigs.enumerated()), id: \.element.id) { idx, cfg in
                                    if idx > 0 {
                                        Divider().overlay(colors.divider.opacity(0.5))
                                    }
                                    HStack(spacing: YuNianTheme.Space.half) {
                                        Button {
                                            switchTo(cfg)
                                        } label: {
                                            HStack(spacing: YuNianTheme.Space.half) {
                                                VStack(alignment: .leading, spacing: 2) {
                                                    Text(cfg.name.isEmpty ? cfg.provider : cfg.name)
                                                        .font(YuNianTheme.TextStyle.settingsRowTitle)
                                                        .foregroundStyle(colors.textPrimary)
                                                        .lineLimit(1)
                                                    Text("\(cfg.provider) · \(cfg.model)")
                                                        .font(.system(size: 11).monospaced())
                                                        .foregroundStyle(colors.textSecondary)
                                                        .lineLimit(1)
                                                }
                                                Spacer(minLength: 0)
                                                if cfg.isEnabled {
                                                    Text("使用中")
                                                        .font(.system(size: 11, weight: .semibold))
                                                        .foregroundStyle(colors.primary)
                                                } else {
                                                    Image(systemName: "arrow.left.arrow.right")
                                                        .font(.system(size: 12))
                                                        .foregroundStyle(colors.textTertiary)
                                                }
                                            }
                                        }
                                        .buttonStyle(.plain)

                                        // 删除（启用中的那条不给删 —— 删了就没了生效渠道）
                                        if !cfg.isEnabled {
                                            Button {
                                                deleteConfig(cfg)
                                            } label: {
                                                Image(systemName: "trash")
                                                    .font(.system(size: 12))
                                                    .foregroundStyle(colors.danger)
                                            }
                                            .buttonStyle(.plain)
                                            .padding(.leading, YuNianTheme.Space.half)
                                        }
                                    }
                                }
                            }
                            .padding(YuNianTheme.Space.cardPadding)
                        }
                    }

                    Spacer(minLength: YuNianTheme.Space.pageTop)
                }
                .padding(.horizontal, YuNianTheme.Space.page)
                .padding(.top, YuNianTheme.Space.standard)
            }
            .task { prefill() }

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
        reloadSavedConfigs()
    }

    // MARK: - 已保存渠道的切换与删除（第 182 轮）

    private func reloadSavedConfigs() {
        savedConfigs = (try? environment.apiConfigs?.allConfigs()) ?? []
    }

    /// 切到另一条已保存配置。
    ///
    /// ⚠️ 切完必须 `syncRuntimeConfig()` —— 否则 Rust 侧还拿着旧渠道，
    /// 表现为"UI 显示切了，发消息还是打到原来那家"。
    /// 第 107 轮 `setAPIKey` 里已经踩过同类的坑（写库不同步运行时）。
    private func switchTo(_ cfg: ApiConfigRepository.ApiConfig) {
        guard !cfg.isEnabled else { return }        // 已是当前，别重复操作
        do {
            _ = try environment.apiConfigs?.activate(id: cfg.id)
            environment.syncRuntimeConfig()
            // 表单跟着切过去，让用户看到"现在编辑的就是这一条"
            provider = cfg.provider
            name = cfg.name
            baseUrl = cfg.baseUrl
            model = cfg.model
            formatHint = cfg.formatHint
            reloadSavedConfigs()
        } catch {
            errorText = String(describing: error)
        }
    }

    /// 删除一条配置。启用中的那条不给删（UI 上也不显示删除钮）。
    private func deleteConfig(_ cfg: ApiConfigRepository.ApiConfig) {
        guard !cfg.isEnabled else { return }
        do {
            _ = try environment.apiConfigs?.delete(id: cfg.id)
            reloadSavedConfigs()
        } catch {
            errorText = String(describing: error)
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
            // 第 182 轮：刷新已保存列表 —— 可能是新建了一行
            reloadSavedConfigs()
            dismiss()
        } catch {
            errorText = String(describing: error)
        }
    }
}
