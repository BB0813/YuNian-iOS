import SwiftUI

/// 生图页 —— OpenAI 兼容协议（`POST {base}/images/generations`）。
///
/// ## 权威来源（第 150 轮）
/// `core/network/.../ImageGenerationService.kt`：
/// - 协议：`GET {base}/models` + `POST {base}/images/generations`
/// - 取图 `data[].b64_json` 优先、`.url` 回退（:27、:208）
/// - 4 级参数回退（:219-234）
///
/// ## 入口设计
/// 从「我」页的菜单进入。iOS 侧此前没有生图入口：
/// Kotlin 侧是 `ImageGenCoordinator`（关键词→概率→冷却）自动触发的，
/// iOS 侧那套判定逻辑未移植 —— **这是手动触发页，不是自动触发**。
///
/// ## ⚠️ 未做：自动触发（关键词/概率/冷却）、流式分片累积、
/// 写入消息库（`data[].revised_prompt` 与 `partial_image_index` 分支）。
/// 这些要么需要服务端确认，要么属于对话链路改造，超出本页范围。
///
/// ## 凭证来源（第 154 轮更正）
/// baseUrl 取 `ApiConfigRepository.activeConfig()`，apiKey 取
/// `KeychainStore.Key.apiKey`（`AppEnvironment.setAPIKey` 写入）。
/// 两者都在本页读取，无需额外配置。
struct ImageGenView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    @State private var prompt = ""
    @State private var size = "1024x1024"
    @State private var models: [String] = []
    @State private var selectedModel = ""
    @State private var images: [UIImage] = []
    @State private var busy = false
    @State private var message: String?
    @State private var loaded = false

    // MARK: - 自动生图配置（第 164 轮）
    //
    // 上一轮把 ImageGenCoordinator + ImageGenStore 接进了对话链路，
    // 但**没有任何 UI 写 ImageGenStore** —— 于是 `load()` 恒返回 nil，
    // 自动触发恒不生效，整条链路是"通了但没人打开"。
    //
    // 这个区就是那个开关。⚠️ 没有放一个不落盘的假开关：
    // 下面每个字段都直接进 ImageGenStore，保存即生效。
    @State private var autoEnabled = false
    @State private var probability = 0
    @State private var cooldownMinutes = 3
    @State private var keywordText = ""
    /// 已保存的生图模型名（第 164 轮）。
    /// 用户手填过但接口没返回它时也要记住。
    @State private var savedModel = ""

    private let probabilities = [0, 10, 30, 60, 100]
    private let cooldowns = [0, 1, 3, 5, 10, 30]

    private let sizes = ["1024x1024", "1024x1792", "1792x1024"]

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }

    var body: some View {
        YuNianGlassPage(
            title: "AI 生图",
            onBack: { dismiss() },
            trailing: {
                // 第 164 轮：两个动作 —— 保存配置 + 生成。
                // Kotlin 的 actions 插槽支持多个（GlassTopBar.kt:97-102）。
                HStack(spacing: YuNianTheme.Space.standard) {
                    Button("保存") { saveAutoConfig() }
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(colors.primary)
                    Button("生成") { run() }
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(canRun ? colors.primary : colors.textTertiary)
                        .disabled(!canRun)
                }
            }
        ) {
            YuNianSectionTitle(title: "描述")

            YuNianGlassCard {
                TextEditor(text: $prompt)
                    .font(.system(size: 15))
                    .foregroundStyle(colors.textPrimary)
                    .frame(minHeight: 96)
                    .scrollContentBackground(.hidden)
                    .padding(YuNianTheme.Space.cardPadding)
            }

            YuNianSectionTitle(title: "尺寸")

            HStack(spacing: YuNianTheme.Space.standard) {
                ForEach(sizes, id: \.self) { s in
                    Button {
                        size = s
                    } label: {
                        Text(s)
                            .font(.system(size: 13, weight: size == s ? .semibold : .regular))
                            .foregroundStyle(size == s ? colors.primary : colors.textSecondary)
                            .padding(.horizontal, YuNianTheme.Space.standard)
                            .padding(.vertical, YuNianTheme.Space.tight)
                            .background(size == s ? colors.primary.opacity(0.15) : colors.card)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
                Spacer(minLength: 0)
            }

            // MARK: - 自动生图（第 164 轮）
            //
            // Kotlin 对应 `ImageGenSettingsViewModel` +
            // `ChatDetailSettingsStore` 那套（AppSettingsStore.kt:83-125）。
            YuNianSectionTitle(title: "自动生图")

            YuNianGlassCard {
                VStack(alignment: .leading, spacing: YuNianTheme.Space.cardPadding) {
                    // 总开关
                    Toggle(isOn: $autoEnabled) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("对话中自动生图")
                                .font(.system(size: 15))
                                .foregroundStyle(colors.textPrimary)
                            Text(autoEnabled
                                 ? "命中关键词或按概率在本回合结束后出图"
                                 : "关闭时只在手动生成生效，不影响对话")
                                .font(.system(size: 11))
                                .foregroundStyle(colors.textSecondary)
                        }
                    }
                    .tint(colors.primary)

                    if autoEnabled {
                        Divider().overlay(colors.divider)

                        // 触发概率
                        VStack(alignment: .leading, spacing: YuNianTheme.Space.half) {
                            Text("无关键词时的触发概率")
                                .font(.system(size: 13))
                                .foregroundStyle(colors.textSecondary)
                            HStack(spacing: YuNianTheme.Space.half) {
                                ForEach(probabilities, id: \.self) { p in
                                    Button {
                                        probability = p
                                    } label: {
                                        Text("\(p)%")
                                            .font(.system(size: 12, weight: probability == p ? .semibold : .regular))
                                            .foregroundStyle(probability == p ? colors.primary : colors.textSecondary)
                                            .padding(.horizontal, YuNianTheme.Space.standard)
                                            .padding(.vertical, YuNianTheme.Space.tight)
                                            .background(probability == p ? colors.primary.opacity(0.15) : colors.card)
                                            .clipShape(Capsule())
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            Text(probability == 0
                                 ? "概率 0 表示只靠关键词触发"
                                 : "每回合结束时掷一次 0–99，小于 \(probability) 才出图")
                                .font(.system(size: 11))
                                .foregroundStyle(colors.textTertiary)
                        }

                        Divider().overlay(colors.divider)

                        // 冷却
                        VStack(alignment: .leading, spacing: YuNianTheme.Space.half) {
                            Text("冷却时长")
                                .font(.system(size: 13))
                                .foregroundStyle(colors.textSecondary)
                            HStack(spacing: YuNianTheme.Space.half) {
                                ForEach(cooldowns, id: \.self) { m in
                                    Button {
                                        cooldownMinutes = m
                                    } label: {
                                        Text(m == 0 ? "无" : "\(m) 分")
                                            .font(.system(size: 12, weight: cooldownMinutes == m ? .semibold : .regular))
                                            .foregroundStyle(cooldownMinutes == m ? colors.primary : colors.textSecondary)
                                            .padding(.horizontal, YuNianTheme.Space.tight)
                                            .padding(.vertical, YuNianTheme.Space.tight)
                                            .background(cooldownMinutes == m ? colors.primary.opacity(0.15) : colors.card)
                                            .clipShape(Capsule())
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            // ⚠️ 与 Kotlin 对齐：冷却是「先扣后画」，失败也计入
                            Text("出图后开始冷却；失败也计入（与 Android 一致）")
                                .font(.system(size: 11))
                                .foregroundStyle(colors.textTertiary)
                        }

                        Divider().overlay(colors.divider)

                        // 关键词
                        VStack(alignment: .leading, spacing: YuNianTheme.Space.half) {
                            Text("触发关键词（每行一个，或用逗号分隔）")
                                .font(.system(size: 13))
                                .foregroundStyle(colors.textSecondary)
                            TextEditor(text: $keywordText)
                                .font(.system(size: 13))
                                .foregroundStyle(colors.textPrimary)
                                .frame(minHeight: 72)
                                .scrollContentBackground(.hidden)
                                .padding(YuNianTheme.Space.tight)
                                .background(colors.card)
                                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                            Button(keywordIsDefault ? "恢复默认关键词" : "使用默认关键词") {
                                keywordText = ImageGenTrigger.defaultKeywords.joined(separator: "\n")
                            }
                            .font(.system(size: 12))
                            .foregroundStyle(colors.primary)
                        }
                    }
                }
                .padding(YuNianTheme.Space.cardPadding)
            }

            if !models.isEmpty {
                YuNianSectionTitle(title: "模型")
                YuNianGlassCard {
                    VStack(alignment: .leading, spacing: YuNianTheme.Space.tight) {
                        ForEach(models, id: \.self) { m in
                            Button {
                                selectedModel = m
                            } label: {
                                HStack {
                                    Text(m).font(.system(size: 13))
                                    Spacer(minLength: 0)
                                    if m == selectedModel {
                                        Image(systemName: "checkmark")
                                            .font(.system(size: 12))
                                            .foregroundStyle(colors.primary)
                                    }
                                }
                                .foregroundStyle(colors.textPrimary)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(YuNianTheme.Space.cardPadding)
                }
            }

            if busy {
                HStack(spacing: YuNianTheme.Space.standard) {
                    ProgressView()
                    Text("生成中…").font(.system(size: 13))
                        .foregroundStyle(colors.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, YuNianTheme.Space.standard)
            }

            if let message {
                Text(message)
                    .font(.system(size: 12))
                    .foregroundStyle(colors.danger)
            }

            if !images.isEmpty {
                YuNianSectionTitle(title: "结果")
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 8),
                                    GridItem(.flexible(), spacing: 8)], spacing: 8) {
                    ForEach(Array(images.indices), id: \.self) { i in
                        Image(uiImage: images[i])
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: .infinity)
                            .background(colors.card)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                }
            }
        }
        .task {
            // 第 164 轮：先载入既有配置，再拉模型列表
            loadAutoConfig()
            await loadModels()
        }
    }

    private var canRun: Bool {
        !busy && !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// 关键词是否就是默认集（按钮文案据此切换）。
    private var keywordIsDefault: Bool {
        let current = ImageGenTrigger.parseKeywords(keywordText)
        return current == ImageGenTrigger.defaultKeywords
    }

    /// 载入已保存的自动生图配置（第 164 轮）。
    ///
    /// `ImageGenStore.load()` 在 enabled=false 时返回 nil，
    /// 所以**关着**的时候读不回 model/size 等 —— 那是设计：
    /// 没开过就没有配置。此时用默认值铺界面，用户一开就能存新的。
    private func loadAutoConfig() {
        if let prefs = ImageGenStore.load() {
            autoEnabled = true
            savedModel = prefs.global.model
            probability = prefs.global.probability
            cooldownMinutes = prefs.global.cooldownMinutes
            keywordText = prefs.global.keywords.joined(separator: "\n")
            size = prefs.global.size
            if !savedModel.isEmpty { selectedModel = savedModel }
        } else {
            // 没开过：铺 Kotlin 的默认值（AppSettingsStore.kt:83-125）
            keywordText = ImageGenTrigger.defaultKeywords.joined(separator: "\n")
        }
    }

    /// 保存自动生图配置（第 164 轮）。
    ///
    /// ⚠️ 这是 `ImageGenStore.enable()` 的**第一个真实调用方**。
    /// 在此之前那个方法没有调用方，`load()` 恒返回 nil，
    /// 对话链路里的自动生图是"通了但没人打开"。
    private func saveAutoConfig() {
        guard !selectedModel.isEmpty else {
            message = ImageGenClient.ImageGenError.noModel.message
            return
        }
        if autoEnabled {
            ImageGenStore.enable(
                model: selectedModel,
                size: size,
                count: 1,
                probability: probability,
                cooldownMinutes: cooldownMinutes,
                template: "{content}",
                keywordText: keywordText
            )
        } else {
            ImageGenStore.disable()
        }
        message = autoEnabled ? "已开启自动生图" : "已关闭自动生图"
    }

    /// 拉模型列表。
    ///
    /// Kotlin `fetchImageModels`（:58-71）：空列表视为错误。
    /// 拉不到不阻断使用 —— 用户仍可手填（Kotlin :81-83 的提示语就是这个意思）。
    private func loadModels() async {
        guard !loaded, let (base, key) = credentials() else { return }
        loaded = true
        do {
            let catalog = try await ImageGenClient()
                .fetchModels(baseUrl: base, apiKey: key)
            models = catalog.imageModels
            // ⚠️ 第 164 轮：已保存过模型名时优先用它（可能不在候选列表里）
            if !savedModel.isEmpty, catalog.imageModels.contains(savedModel) {
                selectedModel = savedModel
            } else if selectedModel.isEmpty {
                selectedModel = catalog.imageModels.first ?? savedModel
            }
            if catalog.imageModels.isEmpty {
                message = "接口未识别到生图模型，可手动在渠道页确认地址支持 /images/generations。"
            }
        } catch {
            message = error.localizedDescription
        }
    }

    private func run() {
        let p = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !p.isEmpty, let (base, key) = credentials() else { return }
        guard !selectedModel.isEmpty else {
            message = ImageGenClient.ImageGenError.noModel.message
            return
        }
        busy = true; message = nil; images = []
        Task {
            defer { busy = false }
            do {
                let datas = try await ImageGenClient().generate(
                    baseUrl: base, apiKey: key, prompt: p,
                    size: size, count: 1)
                images = datas.compactMap { UIImage(data: $0) }
                if images.isEmpty { message = ImageGenClient.ImageGenError.noImageData.message }
            } catch {
                message = error.localizedDescription
            }
        }
    }

    /// 取当前启用渠道的 baseUrl + apiKey。
    ///
    /// ⚠️ 第 154 轮：上一版这里写"apiKey 需要 Keychain，当前拿不到"——
    /// **那个判断是错的**。apiKey 一直都在 Keychain 里
    /// （`KeychainStore.Key.apiKey`，`AppEnvironment.setAPIKey` 第 252-260 行
    ///  写入），我只是没去读。
    ///
    /// 我据一个错误结论在提交信息和 Release 里都写了"已知缺口"，
    /// 而实际只是漏读一行 API。这比真缺口更糟：
    /// 假缺口会让下一个人（或下一轮的我）以为必须做一项大改动。
    private func credentials() -> (String, String)? {
        guard let repo = environment.apiConfigs,
              let cfg = try? repo.activeConfig() else { return nil }
        // 第 186 轮：按该条配置解析 key
        let key = KeychainStore.resolvedAPIKey(configId: cfg.id)
        return (cfg.baseUrl, key)
    }
}
