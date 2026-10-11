import SwiftUI

/// 渠道列表 —— 已保存的全部 API 配置。
///
/// ## 为什么需要这一层
/// `ApiConfigRepository` 早就提供了 `allConfigs()` / `activate(id:)` / `delete(id:)`，
/// 但**之前没有任何 UI 用它们**。仓储层注释自己写明了后果：
///
/// > 用户不知道自己配过哪些；想切回之前那家，只能重新填一遍 baseUrl/model/key
///
/// 单配置的编辑页撑不起"多渠道"，所以这里补上列表层。
///
/// ## 一条与 Android 一致的语义（不要"优化"掉）
/// 删除**启用中**的那条时，剩下的**不会自动顶上** ——
/// 用户没点过就换了渠道，请求会悄悄打到别家去。
/// 因此删掉启用项后会出现"无启用渠道"，那是**预期行为**，UI 要如实说明。
struct ChannelListView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var configs: [ApiConfigRepository.ApiConfig] = []
    @State private var status: String?

    var body: some View {
        let c = YNTheme.palette(scheme)

        List {
            Section {
                if configs.isEmpty {
                    Text("还没有配置任何渠道")
                        .foregroundStyle(c.textTertiary)
                } else {
                    ForEach(configs, id: \.id) { config in
                        NavigationLink {
                            ChannelConfigView(config: config)
                        } label: {
                            row(config, colors: c)
                        }
                    }
                    .onDelete(perform: delete)
                }
            } header: {
                Text("已配置渠道")
            } footer: {
                // 第 203 轮：把「当前」与「对话线路」的关系说清楚 ——
                // 引擎读的是 app_meta.feature_route.chat，而它就是这里这条
                // 「当前启用渠道」（不变式见 ApiConfigRepository.activate）。
                Text("同一时刻只有一条渠道生效，它就是「对话」线路使用的渠道（见「用途线路」）。左滑可删除；保存某条即把它设为当前。")
            }

            Section {
                NavigationLink {
                    ChannelConfigView(config: nil)
                } label: {
                    Label("添加渠道", systemImage: "plus")
                }
            }

            if let status {
                Section {
                    // T6：图标 + 文字，不只靠颜色
                    Label(status, systemImage: "info.circle")
                        .font(.subheadline)
                        .foregroundStyle(c.textSecondary)
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("模型渠道")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { reload() }
    }

    // MARK: - 行

    private func row(_ config: ApiConfigRepository.ApiConfig, colors c: YNTheme.Palette) -> some View {
        let displayName = environment.visibleApiPresets()
            .first { $0.provider == config.provider }?.displayName ?? config.provider
        let hasKey = !environment.resolvedAPIKey(configId: config.id).isEmpty

        return HStack(spacing: YNTheme.Space.md) {
            Image(systemName: config.isEnabled ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(config.isEnabled ? c.accent : c.textTertiary)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(displayName)
                    .foregroundStyle(c.textPrimary)
                HStack(spacing: 6) {
                    if !config.model.isEmpty {
                        Text(config.model)
                    }
                    if !hasKey {
                        // 明确标出"这条没密钥"，否则用户会以为它能用
                        Text("· 缺密钥").foregroundStyle(c.warning)
                    }
                }
                .font(.caption)
                .foregroundStyle(c.textSecondary)
                .lineLimit(1)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            "\(displayName)\(config.isEnabled ? "，当前使用" : "")\(hasKey ? "" : "，缺少密钥")"
        )
    }

    // MARK: - 数据

    private func reload() {
        configs = (try? environment.apiConfigs?.allConfigs()) ?? []
    }

    private func delete(at offsets: IndexSet) {
        guard let repo = environment.apiConfigs else { return }
        let targets = offsets.map { configs[$0] }
        var removedActive = false
        for target in targets {
            if target.isEnabled { removedActive = true }
            _ = try? repo.delete(id: target.id)
        }
        reload()

        // 如实说明后果，而不是让用户自己发现"怎么没渠道在跑了"
        if removedActive {
            status = configs.contains(where: \.isEnabled)
                ? "已删除。"
                : "已删除当前渠道 —— 现在没有生效的渠道，聊天将无法获得回复，请选一条或新建。"
            environment.syncRuntimeConfig()
        } else {
            status = "已删除。"
        }
    }
}
