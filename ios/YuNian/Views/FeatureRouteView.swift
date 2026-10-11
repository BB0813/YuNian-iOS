import SwiftUI

/// 用途线路 —— 四条线路各自选渠道与模型（P0 ②）。
///
/// ## 这一页在回答什么
/// 「对话用哪家、生图用哪家、朗读用哪家、向量检索用哪家」。
/// 在此之前只有一条「当前启用渠道」，四个用途被迫共用同一个渠道与模型 ——
/// 拿对话模型去调 `/audio/speech` 或 `/embeddings`，服务端只会报错。
///
/// ## 两条硬要求（第 202 轮的教训换来的）
/// 1. **每条线路都标出"谁在读它"**：对话由推理引擎读，其余三条由本机读。
///    不写清楚，用户会以为"配了就是本机在用"。
/// 2. **界面显示的就是实际生效的** —— 一律走 `ApiConfigRepository.resolve`，
///    界面不自己另算一套，否则必然出现「UI 说没配、引擎却照样跑」这类分歧。
struct FeatureRouteView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var configs: [ApiConfigRepository.ApiConfig] = []
    @State private var resolutions: [FeaturePurpose: FeatureRouteResolution] = [:]

    var body: some View {
        let c = YNTheme.palette(scheme)
        List {
            Section {
                ForEach(FeaturePurpose.allCases) { purpose in
                    NavigationLink {
                        PurposeRouteDetailView(purpose: purpose)
                    } label: {
                        row(purpose, colors: c)
                    }
                }
            } header: {
                Text("用途线路")
            } footer: {
                Text("每条线路各自指定渠道与模型。没绑定过的线路跟随「当前启用渠道」，行为与只有一条渠道时完全一致。")
            }

            Section {
                // 把这四条线路各自的**读方**摊开写，而不是藏在注释里 ——
                // 否则「我明明配了朗读，怎么没反应」这种问题无从自查。
                ForEach(FeaturePurpose.allCases) { purpose in
                    LabeledContent(purpose.title) {
                        Text(purpose.reader)
                            .foregroundStyle(c.textSecondary)
                    }
                    .font(.subheadline)
                }
            } header: {
                Text("谁读这条线路")
            } footer: {
                Text("「推理引擎」= Rust Agent 在建库后自己读；「本机」= iOS 的 HTTP 客户端直连。两者都读同一批配置。")
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("用途线路")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { reload() }
    }

    // MARK: - 行

    private func row(_ purpose: FeaturePurpose, colors c: YNTheme.Palette) -> some View {
        let resolved = resolutions[purpose]
        let displayName = resolved.map { environment.displayName(forProvider: $0.provider) }
        let hasKey = resolved.map { !environment.resolvedAPIKey(configId: $0.configId).isEmpty }

        return VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Text(purpose.title)
                    .foregroundStyle(c.textPrimary)
                Text(resolved?.isBound == true ? "已指定" : "跟随当前")
                    .font(.caption2)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 1)
                    .background(
                        (resolved?.isBound == true ? c.accent : c.textTertiary).opacity(0.15),
                        in: Capsule()
                    )
                    .foregroundStyle(resolved?.isBound == true ? c.accent : c.textTertiary)
            }
            if let resolved, let displayName {
                HStack(spacing: 6) {
                    Text(displayName)
                    if !resolved.model.isEmpty {
                        Text("· \(resolved.model)")
                    }
                    if resolved.isModelOverridden {
                        Text("（本线路模型）")
                    }
                    if hasKey == false {
                        Text("· 缺密钥").foregroundStyle(c.warning)
                    }
                }
                .font(.caption)
                .foregroundStyle(c.textSecondary)
                .lineLimit(1)
            } else {
                Text("尚未配置任何渠道")
                    .font(.caption)
                    .foregroundStyle(c.textTertiary)
            }
        }
        .accessibilityElement(children: .combine)
    }

    // MARK: - 数据

    private func reload() {
        guard let repo = environment.apiConfigs else { return }
        configs = (try? repo.allConfigs()) ?? []
        // ⚠️ `try?` 自己就拍平成一层可选（Swift 5+），不要再写第二个 `let`。
        var map: [FeaturePurpose: FeatureRouteResolution] = [:]
        for purpose in FeaturePurpose.allCases {
            if let resolved = try? repo.resolve(purpose: purpose) {
                map[purpose] = resolved
            }
        }
        resolutions = map
    }
}

// MARK: - 单条线路

/// 一条线路的详情：选渠道 + 改模型（+ 朗读参数）。
///
/// ## 对话那条为什么选了渠道就等于切了「当前启用渠道」
/// 引擎读 `feature_route.chat`，旧版引擎读 `isEnabled = 1`。
/// 两者必须指向同一行，否则同一台机器上会出现两个"当前渠道"。
/// 所以对话线路的绑定就是 `activate` —— 界面上也照实说。
private struct PurposeRouteDetailView: View {

    let purpose: FeaturePurpose

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var configs: [ApiConfigRepository.ApiConfig] = []
    @State private var resolved: FeatureRouteResolution?
    @State private var modelDraft = ""
    @State private var tts = TtsSettings()
    @State private var ttsLoaded = false
    @State private var status: String?
    /// 试听（第 208 轮）：必须由被持有的对象拿住播放器（见 `VoicePreviewer`）。
    @StateObject private var previewer = VoicePreviewer()

    var body: some View {
        let c = YNTheme.palette(scheme)
        List {
            Section {
                // 第一项：不绑定，跟随「当前启用渠道」
                choice(
                    title: "跟随当前启用渠道",
                    subtitle: activeSubtitle,
                    selected: resolved?.isBound != true,
                    colors: c
                ) { apply(nil) }

                ForEach(configs, id: \.id) { config in
                    choice(
                        title: environment.displayName(forProvider: config.provider),
                        subtitle: subtitle(for: config),
                        selected: resolved?.isBound == true && resolved?.configId == config.id,
                        colors: c
                    ) { apply(config.id) }
                }
            } header: {
                Text("渠道")
            } footer: {
                // 第一句是这条线路的用途说明（`FeaturePurpose.detail`，与枚举同源，
                // 避免这里再抄一遍措辞导致两处不一致）。
                Text(purpose.detail + "\n" + (purpose == .chat
                     ? "对话线路与「当前启用渠道」是同一条：选定后它会成为当前渠道，聊天随即走它。"
                     : "选定后只影响「\(purpose.title)」，不会动到对话正在用的渠道。"))
            }

            Section {
                LabeledContent("模型") {
                    TextField(modelPlaceholder, text: $modelDraft)
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                }
                Button("应用模型") { applyModel() }
                    .disabled(modelDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            } header: {
                Text("模型")
            } footer: {
                Text(purpose == .chat
                     // 引擎从渠道行读模型，所以这条线路改的就是渠道本身。
                     ? "对话由推理引擎读渠道行里的模型，因此这里改的就是该渠道的模型（渠道页看到的是同一个值）。"
                     : "留空表示用该渠道的模型。填了就只对「\(purpose.title)」生效，不动渠道本身。")
            }

            if purpose == .tts, resolved?.isBound == true {
                ttsSection
                autoReadSection
                voiceProfileSection
            }

            if let status {
                Section {
                    Label(status, systemImage: "info.circle")
                        .font(.subheadline)
                        .foregroundStyle(c.textSecondary)
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle(purpose.title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { reload() }
        .onDisappear {
            // 离开这一页就停试听：声音继续响而屏幕上没有任何指示，
            // 用户只能靠"声音从哪来"猜。
            previewer.reset()
            applyModel(silentlyWhenUnchanged: true)
        }
        .onChange(of: tts) { _, value in saveTTS(value) }
    }

    // MARK: - 朗读参数

    @ViewBuilder
    private var ttsSection: some View {
        // ⚠️ `body` 里的 `c` 在这些 `some View` 计算属性里**不在作用域**，
        // 每个都要自己取一份（与 DiagnosticsView 的分节写法一致）。
        let c = YNTheme.palette(scheme)
        Section {
            LabeledContent("音色") {
                TextField(tts.proto == .mimo ? "mimo_default" : "alloy", text: $tts.voice)
                    .multilineTextAlignment(.trailing)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
            }

            Picker("接口协议", selection: $tts.proto) {
                ForEach(TtsClient.SpeechProtocol.allCases) { item in
                    Text(item.title).tag(item)
                }
            }

            Picker("音频格式", selection: $tts.format) {
                ForEach(TtsClient.Format.allCases) { item in
                    Text(item.title).tag(item)
                }
            }

            Toggle("跳过括号内容", isOn: $tts.skipParentheses)

            // 试听（第 208 轮）：改完音色/协议/格式**当场听**。
            // 没有它就只能回聊天页长按一条回复去试，改一次音色要跑一趟。
            Button {
                previewVoice(voice: tts.voice)
            } label: {
                Label(previewer.state.isBusy ? "停止试听" : "试听这一条", systemImage: "play.circle")
            }
            if let title = previewer.state.title {
                Text(title)
                    .font(.caption)
                    .foregroundStyle(previewer.state.isFailed ? c.warning : c.textSecondary)
            }
        } header: {
            Text("朗读参数")
        } footer: {
            Text("""
            自动：渠道是小米 MiMo 时走对话式语音接口（/chat/completions），其余走 OpenAI 兼容的 /audio/speech。
            音色留空则用默认（OpenAI alloy / MiMo mimo_default）；MiMo 可选 冰糖、茉莉、苏打、白桦、Mia、Chloe、Milo、Dean。
            只列 mp3 与 wav —— iOS 用系统播放器朗读，裸 PCM 播不了。
            跳过括号内容 = Android 的同名开关，关闭时括号里的动作描写也会被念出来。
            """)
        }
    }

    // MARK: - 自动朗读（第 208 轮）

    /// **自动朗读新回复**是全局开关（不是按角色），因为它回答的是
    /// "这台设备要不要出声"，而音色回答的是"这个角色什么声音"。
    @ViewBuilder
    private var autoReadSection: some View {
        Section {
            Toggle("自动朗读新回复", isOn: $tts.autoRead)
        } header: {
            Text("自动朗读")
        } footer: {
            Text("""
            默认关 —— 默认开等于"刚装上就突然出声"。
            它用播放类别（`.playback`），**不受静音开关影响**：尊重静音会变成
            "看起来在念、其实没声音"，那比出声更让人困惑。不想听就关掉这个开关。
            只念**助手的新文本回复**：表情、图片、历史消息都不会被念。
            """)
        }
    }

    // MARK: - 按角色音色（第 208 轮）

    /// 每个角色一套音色（留空 = 用上面的全局音色）。
    ///
    /// ## 为什么放在这里而不是角色编辑页
    /// 音色是"这条语音线路怎么念"，与线路设置强相关；
    /// 而且用户配音色时正看着这条线路的模型/协议，不该被拆到两个页面。
    @ViewBuilder
    private var voiceProfileSection: some View {
        let c = YNTheme.palette(scheme)
        // ⚠️ 取列表放在**普通计算属性**里（`companionList`），不在这里 `if let`：
        // ViewBuilder 里的 `if` 会被当成"产出某个 View"，只做赋值会报
        // `type '()' cannot conform to 'View'`。
        let companions = companionList
        Section {
            if companions.isEmpty {
                Text("还没有角色。")
                    .font(.subheadline)
                    .foregroundStyle(c.textTertiary)
            } else {
                ForEach(companions, id: \.id) { companion in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(companion.name)
                                .font(.subheadline)
                            Spacer()
                            TextField(tts.voice.isEmpty ? "用全局音色" : "用全局（\(tts.voice)）",
                                      text: bindingForVoice(companion.id))
                                .multilineTextAlignment(.trailing)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                                .font(.subheadline)
                        }
                        Button {
                            previewVoice(voice: VoiceProfileStore.effectiveVoice(
                                companionId: companion.id,
                                global: tts.voice,
                                repository: environment.apiConfigs))
                        } label: {
                            Label("试听这个角色", systemImage: "play.circle")
                                .font(.caption)
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(c.accent)
                    }
                    .padding(.vertical, 2)
                }
            }
        } header: {
            Text("按角色音色")
        } footer: {
            Text("""
            留空 = 用上面的全局音色。填了则**该角色优先**（这条语音线路不变，只换声音）。
            存 `app_meta`（`voice.voice.<角色 id>`），不新增表。
            试听按**这个角色最终会用的音色**发一次请求 —— 听到什么，聊天里就是什么。
            """)
        }
        .onAppear { resetPreviewer() }
    }

    /// 角色列表（供「按角色音色」一节）。
    ///
    /// 放在普通计算属性里而不是 ViewBuilder 内联：
    /// ① ViewBuilder 里的 `if` 只做赋值会报 `type '()' cannot conform to 'View'`；
    /// ② `(try? x?.f()) ?? []` 这种「可选链 + 自动闭包」的组合在 macOS 的 Swift 上
    ///    会撞编译器崩溃（`failed to produce diagnostic for expression`）——
    ///    本地 WSL 的 Swift 6.4 能过而 CI 不能。拆开后两边都过。
    private var companionList: [CompanionRepository.Companion] {
        guard let repo = environment.companions else { return [] }
        return (try? repo.fetchAll()) ?? []
    }

    /// 某个角色的音色覆盖绑定（空串 = 清除覆盖）。
    private func bindingForVoice(_ companionId: Int64) -> Binding<String> {
        Binding(
            get: { VoiceProfileStore.override(companionId: companionId,
                                              repository: environment.apiConfigs) ?? "" },
            set: { newValue in
                try? VoiceProfileStore.setOverride(newValue, companionId: companionId,
                                                   repository: environment.apiConfigs)
            }
        )
    }

    /// 试听当前设置（先停掉上一次；缺 Key 等前置条件直接给出可读原因）。
    private func previewVoice(voice: String) {
        guard let repo = environment.apiConfigs else { return }
        if previewer.state.isBusy {
            previewer.stop()
            return
        }
        guard let route = resolved else { return }
        let apiKey = environment.resolvedAPIKey(configId: route.configId)
        guard !apiKey.isEmpty else {
            status = "「\(environment.displayName(forProvider: route.provider))」缺少 API Key"
            return
        }
        previewer.preview(baseUrl: route.baseUrl,
                          apiKey: apiKey,
                          model: route.model,
                          provider: route.provider,
                          settings: tts,
                          voice: voice)
    }

    private func resetPreviewer() {
        previewer.reset()
    }

    // MARK: - 组件

    private func choice(
        title: String,
        subtitle: String?,
        selected: Bool,
        colors c: YNTheme.Palette,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: YNTheme.Space.md) {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(selected ? c.accent : c.textTertiary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(c.textPrimary)
                    if let subtitle, !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(c.textSecondary)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
        }
        .buttonStyle(.plain)
    }

    private var activeSubtitle: String? {
        // `try?` + 可选链已经拍平成一层可选，只解一次（Swift 5+）。
        guard let active = try? environment.apiConfigs?.activeConfig() else { return nil }
        let name = environment.displayName(forProvider: active.provider)
        return active.model.isEmpty ? name : "\(name) · \(active.model)"
    }

    private func subtitle(for config: ApiConfigRepository.ApiConfig) -> String {
        var parts: [String] = []
        if !config.model.isEmpty { parts.append(config.model) }
        if config.name != config.provider, !config.name.isEmpty { parts.append("名称：\(config.name)") }
        if environment.resolvedAPIKey(configId: config.id).isEmpty { parts.append("缺密钥") }
        return parts.joined(separator: " · ")
    }

    private var modelPlaceholder: String {
        guard let resolved else { return "模型名" }
        if resolved.purpose == .embedding, resolved.model.isEmpty {
            return EmbeddingClient.recommendedModel(provider: resolved.provider)
        }
        return resolved.model.isEmpty ? "模型名" : resolved.model
    }

    // MARK: - 数据读写

    private func reload() {
        guard let repo = environment.apiConfigs else { return }
        configs = (try? repo.allConfigs()) ?? []
        resolved = (try? repo.resolve(purpose: purpose)) ?? nil
        modelDraft = resolved?.isModelOverridden == true ? (resolved?.model ?? "") : ""
        if purpose == .tts, !ttsLoaded {
            tts = TtsSettings.load(from: repo)
            ttsLoaded = true
        }
    }

    private func apply(_ configId: Int64?) {
        guard let repo = environment.apiConfigs else { return }
        do {
            if let configId {
                try repo.bind(purpose: purpose, configId: configId)
                status = purpose == .chat ? "已切换当前渠道。" : "已指定「\(purpose.title)」使用的渠道。"
            } else {
                try repo.unbind(purpose: purpose)
                status = "已改为跟随「当前启用渠道」。"
            }
            environment.syncRuntimeConfig()
        } catch {
            status = "保存失败，请重试。"
        }
        reload()
    }

    /// 把模型写到它该去的地方。
    ///
    /// - 对话：引擎从**渠道行**读模型 → 写行（`updateModel`）。
    /// - 其余三条：本机读 → 写线路覆盖键；留空即清除覆盖。
    private func applyModel(silentlyWhenUnchanged: Bool = false) {
        guard let repo = environment.apiConfigs, let resolved else { return }
        let draft = modelDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            if purpose == .chat {
                guard draft != resolved.model else { return }
                guard !draft.isEmpty else {
                    if !silentlyWhenUnchanged { status = "对话渠道的模型不能留空。" }
                    return
                }
                _ = try repo.updateModel(id: resolved.configId, model: draft)
                if !silentlyWhenUnchanged { status = "已更新渠道模型。" }
            } else {
                let currentOverride = resolved.isModelOverridden ? resolved.model : ""
                guard draft != currentOverride else { return }
                try repo.setMetaValue(draft, forKey: purpose.modelKey)
                if !silentlyWhenUnchanged {
                    status = draft.isEmpty ? "已改为使用渠道自身的模型。" : "已设为「\(purpose.title)」专用模型。"
                }
            }
        } catch {
            if !silentlyWhenUnchanged { status = "保存失败，请重试。" }
        }
        reload()
    }

    private func saveTTS(_ value: TtsSettings) {
        guard purpose == .tts, ttsLoaded, let repo = environment.apiConfigs else { return }
        try? value.save(to: repo)
    }
}
