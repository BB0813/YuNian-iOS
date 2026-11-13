import SwiftUI
import GRDB

/// 生图设置页（第 251 轮）。
///
/// ## 为什么必须有这一页
///
/// 「画一张」的入口早就有了（顶栏 `⋯`），但它**永远画不出图**：
///
/// 1. 生图走 `feature_route.image` 指向的渠道，而 iOS **没有任何界面能把这条路由绑上**
///    —— `ApiConfigRepository.bind(purpose:configId:)` 存在，但没人调用 `.image`。
/// 2. `ImageGenCoordinator.Deps.imageModel` 取自 `route.model`，路由为空时它是空串，
///    请求体就是 `{"model": "", …}`，任何服务端都会拒。
///
/// 换句话说：**功能代码是完整的，缺的是"让用户告诉它用哪家画"这一步。**
///
/// ## 设计
///
/// 三块，从上到下就是用户的思考顺序：
///
/// 1. **用哪家画** —— 从已配置的渠道里选一个（或"跟随聊天"）
/// 2. **用什么模型** —— 常见生图模型一键选，也能手填
/// 3. **画多大** —— 尺寸与张数（这些存 `app_meta`，不进 schema）
struct ImageGenSettingsView: View {

    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme

    /// 所有已配置渠道（供选择）。
    @State private var channels: [Channel] = []
    /// 当前绑定到 `.image` 的渠道 id（nil = 跟随聊天）。
    @State private var boundConfigId: Int64?
    /// 生图模型名（空 = 用渠道默认）。
    @State private var modelName = ""
    @State private var size = ImageGenSettingsView.defaultSize
    @State private var count = 1
    @State private var loadError: String?
    @State private var savedToast: String?

    /// 由调用方传入：`ApiConfigRepository` 的 init 需要 database，
    /// 而这个页面是独立 sheet，拿不到上层注入的 environment。
    let environment: AppEnvironment

    struct Channel: Identifiable, Equatable {
        let id: Int64
        let name: String
        let provider: String
        let model: String
        let baseUrl: String
    }

    static let defaultSize = "1024x1024"
    static let sizeOptions = ["1024x1024", "1024x1792", "1792x1024", "512x512"]

    /// 常见生图模型（点一下填进去，省得手打错）。
    /// 不追求穷举 —— 生图模型更新太快，列表迟早过时，所以同时保留手填。
    static let commonModels = [
        "dall-e-3",
        "gpt-image-1",
        "flux-schnell",
        "flux-dev",
        "stable-diffusion-3",
        "seedream-3.0",
    ]

    private static let boundKey = "image_gen.config_id"
    private static let modelKey = "image_gen.model"
    private static let sizeKey = "image_gen.size"
    private static let countKey = "image_gen.count"

    var body: some View {
        let c = YNTheme.palette(scheme)
        NavigationStack {
            Form {
                channelSection(c)
                modelSection(c)
                sizeSection(c)
                statusSection(c)
            }
            .scrollContentBackground(.hidden)
            .background(c.background)
            .navigationTitle("生图设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { save() }
                }
            }
            .task { load() }
            .overlay(alignment: .bottom) {
                if let savedToast {
                    Text(savedToast)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 10)
                        .background(.black.opacity(0.72), in: Capsule())
                        .padding(.bottom, 24)
                }
            }
        }
    }

    // MARK: - 用哪家画

    private func channelSection(_ c: YNTheme.Palette) -> some View {
        Section {
            // 「跟随聊天」是默认：多数用户的聊天渠道也能生图（OpenAI 兼容）。
            // 把它放在第一项，用户不动就是最省事的配置。
            Button {
                boundConfigId = nil
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("跟随聊天渠道")
                            .foregroundStyle(c.textPrimary)
                        Text("用当前启用的聊天渠道画")
                            .font(.caption)
                            .foregroundStyle(c.textTertiary)
                    }
                    Spacer()
                    if boundConfigId == nil {
                        Image(systemName: "checkmark")
                            .foregroundStyle(c.accent)
                    }
                }
            }
            .buttonStyle(.plain)

            ForEach(channels) { channel in
                Button {
                    boundConfigId = channel.id
                    // 换渠道时把模型名清掉：上一家的模型名在这一家通常不存在，
                    // 留着会让请求发一个无效 model（比空串更难排查）。
                    if modelName.isEmpty == false { modelName = "" }
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(channel.name)
                                .foregroundStyle(c.textPrimary)
                            Text("\(channel.provider) · \(channel.model)")
                                .font(.caption)
                                .foregroundStyle(c.textTertiary)
                                .lineLimit(1)
                        }
                        Spacer()
                        if boundConfigId == channel.id {
                            Image(systemName: "checkmark")
                                .foregroundStyle(c.accent)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
        } header: {
            Text("用哪家画")
        } footer: {
            if channels.isEmpty {
                Text("还没有配置任何渠道。先到「我 → 渠道」添加一个，再回来选。")
                    .foregroundStyle(c.warning)
            } else {
                Text("生图会走这里选的渠道。选「跟随聊天渠道」时用当前启用的那条。")
            }
        }
    }

    // MARK: - 用什么模型

    private func modelSection(_ c: YNTheme.Palette) -> some View {
        Section {
            TextField("模型名，例如 dall-e-3", text: $modelName)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.body.monospaced())

            // 一键填入常见模型
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: YNTheme.Space.sm) {
                    ForEach(Self.commonModels, id: \.self) { name in
                        Button {
                            modelName = name
                        } label: {
                            Text(name)
                                .font(.caption.monospaced())
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .background(
                                    (modelName == name ? c.accent.opacity(0.22) : c.surface),
                                    in: Capsule()
                                )
                                .foregroundStyle(modelName == name ? c.accent : c.textSecondary)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.vertical, 2)
            }
        } header: {
            Text("用什么模型")
        } footer: {
            Text("留空则用所选渠道的默认模型。名字必须和服务商文档一致 —— 写错会直接返回错误。")
        }
    }

    // MARK: - 画多大

    private func sizeSection(_ c: YNTheme.Palette) -> some View {
        Section("画多大") {
            Picker("尺寸", selection: $size) {
                ForEach(Self.sizeOptions, id: \.self) { option in
                    Text(option).tag(option)
                }
            }
            .pickerStyle(.menu)

            Stepper(value: $count, in: 1...4) {
                HStack {
                    Text("张数")
                    Spacer()
                    Text("\(count)")
                        .foregroundStyle(c.textSecondary)
                        .monospacedDigit()
                }
            }
        }
    }

    // MARK: - 状态

    private func statusSection(_ c: YNTheme.Palette) -> some View {
        Section {
            if let loadError {
                Label(loadError, systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(c.warning)
                    .font(.footnote)
            }
            HStack {
                Text("当前生效")
                Spacer()
                Text(effectiveSummary)
                    .foregroundStyle(c.textSecondary)
                    .font(.footnote)
                    .lineLimit(2)
                    .multilineTextAlignment(.trailing)
            }
        } header: {
            Text("状态")
        } footer: {
            Text("改完点「完成」保存。生图时会按这里的设置发请求。")
        }
    }

    private var effectiveSummary: String {
        let channelName: String
        if let boundConfigId, let channel = channels.first(where: { $0.id == boundConfigId }) {
            channelName = channel.name
        } else if let firstName = channels.first?.name {
            channelName = "跟随聊天（\(firstName)）"
        } else {
            channelName = "跟随聊天"
        }
        let model = modelName.isEmpty ? "默认模型" : modelName
        return "\(channelName) · \(model)"
    }

    // MARK: - 读写

    private func load() {
        guard let db = AppEnvironment.shared.database else {
            loadError = "数据库未就绪"
            return
        }
        do {
            // 渠道列表
            let rows = try db.pool.read { db in
                try Row.fetchAll(db, sql: """
                    SELECT id, name, provider, model, baseUrl
                    FROM api_configs ORDER BY id
                    """)
            }
            channels = rows.compactMap { row in
                guard let id = row["id"] as Int64? else { return nil }
                return Channel(
                    id: id,
                    name: (row["name"] as String?) ?? "未命名",
                    provider: (row["provider"] as String?) ?? "",
                    model: (row["model"] as String?) ?? "",
                    baseUrl: (row["baseUrl"] as String?) ?? ""
                )
            }

            // 已保存的设置
            guard let repo = environment.apiConfigs else { return }
            if let raw = try? repo.metaValue(forKey: Self.boundKey), let id = Int64(raw) {
                boundConfigId = id
            }
            modelName = (try? repo.metaValue(forKey: Self.modelKey)) ?? ""
            if let s = try? repo.metaValue(forKey: Self.sizeKey), !s.isEmpty { size = s }
            if let cRaw = try? repo.metaValue(forKey: Self.countKey), let n = Int(cRaw), n > 0 {
                count = min(n, 4)
            }
        } catch {
            loadError = "读取渠道失败：\(error.localizedDescription)"
        }
    }

    private func save() {
        guard let repo = environment.apiConfigs else {
            loadError = "数据库未就绪"
            return
        }
        do {
            // `setMetaValue(nil)` 是**删键**（= 回到"跟随聊天"），
            // 与写空串语义不同 —— 所以这里显式区分。
            try repo.setMetaValue(boundConfigId.map(String.init), forKey: Self.boundKey)
            try repo.setMetaValue(modelName.isEmpty ? nil : modelName, forKey: Self.modelKey)
            try repo.setMetaValue(size, forKey: Self.sizeKey)
            try repo.setMetaValue(String(count), forKey: Self.countKey)

            // ⚠️ 同时写 `feature_route.image`：`ImageGenCoordinator` 走的是**那个**键，
            // 不是上面这些 `image_gen.*`。两处都写，避免"设置页显示已配好但生图仍报没模型"。
            if let boundConfigId {
                try repo.bind(purpose: .image, configId: boundConfigId)
            }

            withAnimation { savedToast = "已保存" }
            Task {
                try? await Task.sleep(for: .seconds(1.4))
                withAnimation { savedToast = nil }
            }
        } catch {
            loadError = "保存失败：\(error.localizedDescription)"
        }
    }
}
