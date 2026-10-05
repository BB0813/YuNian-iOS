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
    }

    private func statusRow(_ title: String, ok: Bool, detail: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: ok ? "checkmark.circle.fill" : "xmark.circle.fill")
                .foregroundStyle(ok ? .green : .red)
                .font(.caption)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
            Spacer(minLength: 0)
        }
    }

    private func abbreviated(_ value: String) -> String {
        value.count <= 16 ? value : String(value.prefix(16)) + "…"
    }
}
