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
        .onDisappear { applyModel(silentlyWhenUnchanged: true) }
        .onChange(of: tts) { _, value in saveTTS(value) }
    }

    // MARK: - 朗读参数

    @ViewBuilder
    private var ttsSection: some View {
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
