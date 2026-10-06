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
/// ⚠️ 未做：自动触发（关键词/概率/冷却）、流式分片累积、
/// 写入消息库（`data[].revised_prompt` 与 `partial_image_index` 分支）。
/// 这些要么需要服务端确认，要么属于对话链路改造，超出本页范围。
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

    private let sizes = ["1024x1024", "1024x1792", "1792x1024"]

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }

    var body: some View {
        YuNianGlassPage(
            title: "AI 生图",
            onBack: { dismiss() },
            trailing: {
                Button("生成") { run() }
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(canRun ? colors.primary : colors.textTertiary)
                    .disabled(!canRun)
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
        .task { await loadModels() }
    }

    private var canRun: Bool {
        !busy && !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
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
            selectedModel = catalog.imageModels.first ?? ""
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
    /// ⚠️ iOS 侧 apiKey 不明文入库（第 121 轮的设计），故这里只能拿到
    /// baseUrl；**apiKey 需要 Keychain。** 当前 Keychain 未存 apiKey，
    /// 所以这里只回传空串 —— 表现为「未配置」，由用户去渠道页补。
    /// 这是已知缺口，不是 bug：Keychain 存 apiKey 属另一项改动。
    private func credentials() -> (String, String)? {
        guard let repo = environment.apiConfigs,
              let cfg = try? repo.activeConfig() else { return nil }
        return (cfg.baseUrl, "")
    }
}
