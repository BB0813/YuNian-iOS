import SwiftUI

/// 诊断页 —— 把原先堆在首屏的开发者自检项集中到这里。
///
/// ## 为什么拆出来（第 109 轮）
/// 上一版 `RootView` 首屏就是一张 `List`,把 Rust Agent 状态、deviceId、keyId、
/// 中文分词的索引串、settings/credentials JSON 全摆在用户眼前。
/// 用户要找的「进入对话」被埋在八个 Section 之下。
///
/// 自检本身是有价值的(V1–V9 的可见证据、可截图),但它属于**开发者视角**,不该占首屏。
/// 这里原样保留那些内容,只换位置。
///
/// ⚠️ 第 109 轮第一版我引用了 `environment.ftsImplementation` /
/// `objectCountSummary` / `databasePath` / `schemaSummary` —— **全都不存在**,
/// 这正是第 87、93 轮栽过的坑(引用自己没写的符号)。现在改用面板原有的
/// 真实字段:`resolvedFTSVersion` / `YuNianSchema.*` / `AppPaths.databaseURL()`。
struct DiagnosticsView: View {

    @EnvironmentObject private var environment: AppEnvironment
    /// 第 129 轮：语义色。诊断页是给开发者看的，仍保留 List/Section 的
    /// 高信息密度结构，但配色/字号接进设计系统，不再用系统默认灰。
    @Environment(\.colorScheme) private var scheme

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }

    // MARK: - 渠道自检（第 189 轮）
    //
    // 为什么放在诊断页：第 186/188 轮修的两个 bug 都是**看不见**的 ——
    //   · key 存在一个全局槽 → 多配置切换时切不动 key
    //   · 绑定可用性读了恒空的行内 apiKey → 非 PARTNER 恒判不可用
    // 两者都只在"用户发现行为不对"时才暴露。
    // 这一节把它们的状态摊开：当前生效的是哪条、key 从哪来、每条配置有没有 key。
    @State private var diagActiveConfig: ApiConfigRepository.ApiConfig?
    @State private var diagSavedConfigs: [ApiConfigRepository.ApiConfig] = []

    var body: some View {
        List {
            if let error = environment.startupError {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                        .font(.caption)
                        .textSelection(.enabled)
                }
            }

            Section("Rust Agent") {
                statusRow("运行时", ok: environment.runtime != nil,
                          detail: environment.runtime != nil ? "已构造" : "未就绪")
                statusRow("Swift 绑定", ok: environment.runtime != nil,
                          detail: environment.runtime != nil ? "UniFFI 生成物可调用" : "不可用")
                statusRow("工具宿主", ok: !environment.toolHost.registeredToolNames.isEmpty,
                          detail: environment.toolHost.registeredToolNames.isEmpty
                             ? "未注册工具（M3 接入）"
                             : "\(environment.toolHost.registeredToolNames.count) 个："
                              + environment.toolHost.registeredToolNames.joined(separator: ", "))
            }

            Section("数据层") {
                statusRow(
                    "schema 版本",
                    ok: true,
                    detail: "v\(YuNianSchema.version)（Rust 契约 "
                          + "\(YuNianSchema.rustSupportedVersionRange.lowerBound)–"
                          + "\(YuNianSchema.rustSupportedVersionRange.upperBound)）")
                statusRow("FTS 实现", ok: environment.resolvedFTSVersion != "unknown",
                          detail: environment.resolvedFTSVersion)
                statusRow("表 / 索引 / 外键", ok: true,
                          detail: "\(YuNianSchema.tableCount) / \(YuNianSchema.indexCount)"
                                + " / \(YuNianSchema.foreignKeyCount)")

                if let path = try? AppPaths.databaseURL().path {
                    Text(path)
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                        .lineLimit(2)
                }
            }

            Section("伴侣（Rust 人设来源）") {
                if let companion = environment.defaultCompanion {
                    statusRow("默认伴侣", ok: true,
                              detail: "#\(companion.id) \(companion.name) · 亲密度 \(companion.intimacy)")
                    if !companion.personality.isEmpty {
                        Text(companion.personality)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                    }
                    statusRow("Rust 直读列", ok: true,
                              detail: CompanionRepository.rustReadColumns.joined(separator: " / "))
                } else {
                    statusRow("默认伴侣", ok: false,
                              detail: "缺失 —— Rust 的 load_companion 会失败，人设不会注入")
                }
            }

            Section("表情标签（builtin_send_sticker 校验用）") {
                if !environment.stickerTagsSummary.isEmpty {
                    statusRow("可用标签", ok: !environment.stickerTagList.isEmpty,
                              detail: environment.stickerTagsSummary)
                } else {
                    Text("（空）全新安装下为空是正常的：只有导入的表情才会进表。")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Text("经 settings.stickers 下发给 Rust；内置工具不受 tools 门控，故这是活需求。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            Section("设备签名（PARTNER 需要）") {
                statusRow("Secure Enclave", ok: SecureEnclaveSigner.isHardwareBacked,
                          detail: SecureEnclaveSigner.isHardwareBacked
                                 ? "硬件密钥" : "软件回退（模拟器正常）")
                statusRow("deviceId", ok: true, detail: abbreviated(DeviceIdentity.deviceId))
                statusRow("keyId（sha256(SPKI)[:32]）", ok: (try? SecureEnclaveSigner.keyId()) != nil,
                          detail: ((try? SecureEnclaveSigner.keyId()).map(abbreviated)) ?? "不可用")
            }

            Section("渠道（生效配置与 Key 来源）") {
                // ⚠️ 这里显示的"生效配置"就是 **Rust 每回合读的那一行**：
                //   native_gateway.rs:281-286
                //   `SELECT ... FROM api_configs WHERE isEnabled = 1
                //    ORDER BY id DESC LIMIT 1`
                //
                // 它**不看** `companions.apiConfigId` —— Rust 的
                // `AgentTurnRequest`（agent.rs:88-106）没有配置覆盖字段，
                // 所以「角色级 API 隔离」在 Agent 路径上不生效。
                // （Android 的 `AiService.resolveConfig`（:183-204）是它
                //   **Kotlin HTTP 路径**的逻辑，那条路 iOS 不用。）
                // 因此渠道页没有放"绑定到伴侣"的入口 —— 放了也不生效。
                if let cfg = diagActiveConfig {
                    statusRow("生效配置", ok: true,
                              detail: "#\(cfg.id) \(cfg.provider) · \(cfg.model)")
                    let perConfig = KeychainStore.string(
                        for: KeychainStore.Key.apiKeyFor(cfg.id)) ?? ""
                    let legacy = KeychainStore.string(for: KeychainStore.Key.apiKey) ?? ""
                    let source = !perConfig.isEmpty
                        ? "按配置槽（api_key_\(cfg.id)）"
                        : (!legacy.isEmpty ? "回退旧单槽（迁移态）" : "无")
                    statusRow("Key 来源",
                              ok: !perConfig.isEmpty || !legacy.isEmpty || cfg.provider == "PARTNER",
                              detail: cfg.provider == "PARTNER" ? "PARTNER（免 Key）" : source)
                } else {
                    statusRow("生效配置", ok: false,
                              detail: "没有任何 isEnabled = 1 的行（Rust 会报「无可用 API 配置」）")
                }

                if diagSavedConfigs.isEmpty {
                    statusRow("已保存渠道", ok: false, detail: "无")
                } else {
                    ForEach(diagSavedConfigs) { c in
                        let hasKey = !(KeychainStore.string(
                            for: KeychainStore.Key.apiKeyFor(c.id)) ?? "").isEmpty
                        let isPartner = c.provider == "PARTNER"
                        statusRow(
                            "#\(c.id) \(c.provider)\(c.isEnabled ? "（使用中）" : "")",
                            ok: hasKey || isPartner,
                            detail: hasKey ? "有 Key"
                                  : (isPartner ? "PARTNER 免 Key" : "无 Key → 切到它会 401")
                        )
                    }
                }
            }

            Section("下发给 Rust 的配置") {
                LabeledContent("settings", value: environment.settingsSummary)
                    .font(.caption)
                LabeledContent("credentials", value: environment.credentialsSummary)
                    .font(.caption)
            }

            Section("中文分词（逐字复刻自 Android）") {
                let sample = "今天天气不错"
                Text("索引：\(MessageSearchTokenizer.indexTokens(sample))")
                    .font(.caption.monospaced())
                    .textSelection(.enabled)
                Text("查询：\(MessageSearchTokenizer.matchQuery(sample) ?? "nil")")
                    .font(.caption.monospaced())
                    .textSelection(.enabled)
                Text("与 Android 侧不一致会导致「搜不到」且不报错，故有回归测试兜住。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .navigationTitle("诊断")
        .navigationBarTitleDisplayMode(.inline)
        .task { reloadChannelState() }
    }

    /// 读渠道自检所需的数据（第 189 轮）。
    private func reloadChannelState() {
        diagActiveConfig = try? environment.apiConfigs?.activeConfig()
        diagSavedConfigs = (try? environment.apiConfigs?.allConfigs()) ?? []
    }

    private func statusRow(_ title: String, ok: Bool, detail: String) -> some View {
        HStack(alignment: .top, spacing: YuNianTheme.Space.standard) {
            Image(systemName: ok ? "checkmark.circle.fill" : "xmark.circle.fill")
                .foregroundStyle(ok ? colors.success : colors.danger)
                .font(.caption)
            VStack(alignment: .leading, spacing: YuNianTheme.Space.micro) {
                Text(title)
                    .font(YuNianTheme.TextStyle.settingsRowTitle)
                    .foregroundStyle(colors.textPrimary)
                Text(detail)
                    .font(.system(size: 11))
                    .foregroundStyle(colors.textSecondary)
                    .textSelection(.enabled)
            }
            Spacer(minLength: 0)
        }
    }

    private func abbreviated(_ value: String) -> String {
        value.count <= 16 ? value : String(value.prefix(16)) + "…"
    }
}
