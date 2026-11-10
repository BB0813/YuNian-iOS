import SwiftUI

/// 上下文账本 —— 「这一轮模型到底收到了什么」，按 Aru 的实测层级实现。
///
/// # 与「本轮收据」的区别
///
/// 「本轮收据」（第 207 轮）只到「片段名 + 字符数」。账本把同一件事做成**可审计结构**：
/// 按通道分组、三态（送进/只记录/本实现没有）、四种深度、
/// **未准入原因是枚举**、双口径 token 并列且不相加、逐工具成本。
///
/// # 信息架构：两个清单并立（本页最重要的设计）
///
/// ```
/// ⑤ 本轮带入的材料   ← 只列 sent == true    「先看真送进去了什么」
/// ⑥ 降级区块         ← 只列 sent == false   「再看为什么没送」
/// ⑦ 本实现没有的通道 ← not_applicable       「我们没有这个能力，不是丢了」
/// ```
///
/// 三者**视觉上分开**，绝不混流。
///
/// # 六条硬规则（全部来自 Aru 实测，代码里逐条注释）
///
/// 1. 无供应商回执 → **差异行整行不渲染**（不是 `0%`，也不是 `—%`）
/// 2. 供应商口径的每个字段独立显示 `—`（不是 `0`）
/// 3. 本机估算**照常显示**（它不依赖回执）
/// 4. 降级项按 `severity` 三分类呈现，**引导语不同**
/// 5. `skipped_no_trigger` 写「无需处理」，**不用警示色、不给修复入口**
/// 6. 降级项带结构化证据（可复现）
struct ContextLedgerView: View {

    let ledger: ContextLedger
    /// 对话标题（小字，标在标题上方 —— 让用户知道这是"哪个对话"的账本）
    var conversationTitle: String = ""
    /// 供应商回执（有则显示差异行；无则整行不渲染）
    var providerInputTokens: Int?
    /// 回合状态（四态，与「有没有回执」分开）
    var turnStatus: TurnStatus = .complete

    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    /// 展开状态（默认全部折叠 —— 层级多，一上来全展开会吓人）
    @State private var showTechnical = false
    @State private var showToolLedger = false
    @State private var showNotApplicable = false

    /// 回合完成度。
    ///
    /// ⚠️ **不照抄 Aru 的 `status`**：它的用户中断轮次仍然显示 `complete`
    ///（实测确认的缺陷）。这里四态分开，并且"有没有 usage 回执"是**另一个字段**。
    enum TurnStatus: String {
        case complete
        case cancelled
        case failed
        case partial

        var text: String {
            switch self {
            case .complete: return "完成"
            case .cancelled: return "已停止"
            case .failed: return "失败"
            case .partial: return "未完整"
            }
        }
    }

    var body: some View {
        let c = YNTheme.palette(scheme)

        List {
            headerSection(c)
            usageSection(c)
            messageWindowSection(c)
            admittedSection(c)
            degradedSection(c)
            notApplicableSection(c)
            technicalSection(c)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("上下文账本")
        .navigationBarTitleDisplayMode(.inline)
        .tint(c.accent)
    }

    // MARK: - ① 头部

    private func headerSection(_ c: YNTheme.Palette) -> some View {
        Section {
            VStack(alignment: .leading, spacing: YNTheme.Space.xs) {
                if !conversationTitle.isEmpty {
                    Text(conversationTitle)
                        .font(.caption)
                        .foregroundStyle(c.textTertiary)
                }
                Text("这一轮请求的装箱清单：模型实际收到什么、没收到什么，各占多少。")
                    .font(.subheadline)
                    .foregroundStyle(c.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                // 状态与「有没有回执」分开显示 —— 见 TurnStatus 的注释
                Label(turnStatus.text,
                      systemImage: turnStatus == .complete
                          ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                    .font(.caption)
                    .foregroundStyle(turnStatus == .complete ? c.textTertiary : c.warning)
                if ledger.isLegacy {
                    // 旧记录必须标注：当时只记了字符数，且没记工具/世界书
                    Text("旧记录：只有字符数，Token 为按字符估算；"
                         + "当时未记录工具 Schema 与世界书注入。")
                        .font(.caption2)
                        .foregroundStyle(c.warning)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .listRowBackground(Color.clear)
        }
    }

    // MARK: - ② 本轮用量（双口径）

    private func usageSection(_ c: YNTheme.Palette) -> some View {
        // 差异行只在**供应商给了回执**时出现（规则 1）
        let diff = ContextLedger.Diff.compute(provider: providerInputTokens,
                                              local: ledger.estimate.tokens)
        return Section {
            // 本机估算照常显示（规则 3：它不依赖回执）
            LabeledContent("本机文本估算") {
                Text("\(ledger.estimate.tokens)")
                    .monospacedDigit()
            }
            LabeledContent("估算算法") {
                Text(ledger.estimate.isRoughEstimate ? "\(ledger.estimate.method)（粗估）"
                                                     : ledger.estimate.method)
                    .font(.caption)
                    .foregroundStyle(c.textTertiary)
            }
            // 供应商口径：有值给值，没值给 `—`（规则 2：不是 0）
            LabeledContent("供应商输入") {
                if let provider = diff.providerInputTokens {
                    Text("\(provider)").monospacedDigit()
                } else {
                    Text("—").foregroundStyle(c.textTertiary)
                }
            }
            if let percentText = diff.percentText {
                LabeledContent("差异") {
                    Text(percentText).monospacedDigit()
                }
            }
            // 原文口径（规则 1 的可读版）：一行说清"哪些是两种口径"
            Text("分项 Token 是发出请求前的本机估算；供应商回执来自服务端。"
                 + "两种口径用于对照，不能相加。")
                .font(.caption2)
                .foregroundStyle(c.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        } header: {
            Text("本轮用量")
        }
    }

    // MARK: - ④ 消息窗口

    @ViewBuilder
    private func messageWindowSection(_ c: YNTheme.Palette) -> some View {
        if let window = ledger.channels.first(where: { $0.id == "conversation_window" }) {
            Section {
                materialRow(window, colors: c)
            } header: {
                Text("消息窗口")
            } footer: {
                Text("窗口内的历史消息。条数与体积随对话增长 —— 它不在下面的材料列表里，"
                     + "但同样是真实输入的一部分。")
            }
        }
    }

    // MARK: - ⑤ 本轮带入的材料

    @ViewBuilder
    private func admittedSection(_ c: YNTheme.Palette) -> some View {
        // 排除独立的「消息窗口」—— 它已经单独成块
        let items = ledger.admitted.filter { $0.id != "conversation_window" }
        if !items.isEmpty {
            Section {
                ForEach(items) { channel in
                    materialRow(channel, colors: c)
                }
            } header: {
                HStack {
                    Text("本轮带入的材料")
                    Spacer()
                    Text("\(items.count) 项 · ≈ \(ledger.admittedTokens) Token")
                        .font(.caption2)
                        .foregroundStyle(c.textTertiary)
                        .monospacedDigit()
                }
            } footer: {
                Text("只有真正送进请求的才会出现在这里。没送进去的在下面「降级」一块。")
            }
        }
    }

    /// 一条材料/降级行 —— 两个清单共用同一个渲染器，**但色点与第二行不同**。
    ///
    /// Aru 实测用的是绿/灰点；我们按 `PRODUCT.md` 不用原色，
    /// 改用**品牌紫（已送入）/ 中性灰（未送入）/ 暖色（有配置问题）**，
    /// 并且**文字始终同时给出**（不靠颜色单独承载语义 —— `DESIGN.md` 的既有规则）。
    private func materialRow(_ channel: ContextLedger.Channel, colors c: YNTheme.Palette) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Circle()
                    .fill(dotColor(channel, colors: c))
                    .frame(width: 7, height: 7)
                Text(channel.cn)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(c.textPrimary)
                // 复合计数（记忆召回）优先于 `×N`
                Text(channel.itemsLabel ?? (channel.items > 0 ? "×\(channel.items)" : ""))
                    .font(.caption)
                    .foregroundStyle(c.textTertiary)
                Spacer(minLength: 0)
                Text("≈ \(channel.tokens) Token")
                    .font(.caption)
                    .foregroundStyle(c.textTertiary)
                    .monospacedDigit()
            }
            // 第二行：跨轮/状态标记（降级固定「本次未发送」）。空则不渲染。
            if !channel.statusLine.isEmpty {
                Label(channel.statusLine,
                      systemImage: channel.sent ? "arrow.triangle.2.circlepath" : "arrow.uturn.backward")
                    .font(.caption2)
                    .foregroundStyle(channel.sent ? c.textTertiary : c.textSecondary)
            }
            // 原因 + 三分类引导（只在降级时）
            if let hint = channel.reasonHint {
                Text(hint)
                    .font(.caption2)
                    .foregroundStyle(c.textSecondary)
            }
            if let fix = channel.fixPath {
                Text(fix)
                    .font(.caption2)
                    .foregroundStyle(c.accent)
            }
            // 结构化证据（可复现）
            if !channel.evidence.isEmpty {
                Text(channel.evidence.sorted { $0.key < $1.key }
                        .map { "\($0.key)=\($0.value)" }
                        .joined(separator: "; "))
                    .font(.caption2.monospaced())
                    .foregroundStyle(c.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityText(channel))
    }

    private func dotColor(_ channel: ContextLedger.Channel, colors c: YNTheme.Palette) -> Color {
        if channel.sent { return c.accent }
        switch channel.severity {
        case .config, .actionable: return c.warm   // 有东西要改 → 暖色
        case .normal: return c.textTertiary        // 正常状态 → 中性，不警示
        case .notApplicable, .none: return c.textTertiary.opacity(0.5)
        }
    }

    private func accessibilityText(_ channel: ContextLedger.Channel) -> String {
        var parts = ["\(channel.cn)"]
        parts.append(channel.sent ? "已送入 \(channel.tokens) Token" : "未送入")
        if let hint = channel.reasonHint { parts.append(hint) }
        return parts.joined(separator: "，")
    }

    // MARK: - ⑥ 降级区块

    @ViewBuilder
    private func degradedSection(_ c: YNTheme.Palette) -> some View {
        let items = ledger.degraded
        if !items.isEmpty {
            Section {
                ForEach(items) { channel in
                    materialRow(channel, colors: c)
                }
            } header: {
                Text("\(items.count) 项没有按完整形态带入")
            } footer: {
                // 这句话是 Aru 实测里最有用的一个提示：告诉用户这块是"附注"
                Text("这里记录降级、跳过和失败事实；收起时不影响上面的实际装箱清单。"
                     + "「本轮没有命中」是正常状态，不需要处理。")
            }
        }
    }

    // MARK: - ⑦ 本实现没有的通道

    @ViewBuilder
    private func notApplicableSection(_ c: YNTheme.Palette) -> some View {
        let items = ledger.notApplicable
        if !items.isEmpty {
            Section {
                DisclosureGroup(isExpanded: $showNotApplicable) {
                    ForEach(items) { channel in
                        HStack {
                            Text(channel.cn)
                                .font(.caption)
                                .foregroundStyle(c.textTertiary)
                            Spacer()
                            Text("本实现没有")
                                .font(.caption2)
                                .foregroundStyle(c.textTertiary)
                        }
                    }
                } label: {
                    Text("\(items.count) 条通道本实现没有")
                        .font(.subheadline)
                        .foregroundStyle(c.textSecondary)
                }
            } footer: {
                Text("列出来而不是隐藏：这些是别的实现有的能力，我们没有 —— "
                     + "不是「这一轮没带上」。")
            }
        }
    }

    // MARK: - 工具账 + 技术收据（折叠）

    private func technicalSection(_ c: YNTheme.Palette) -> some View {
        Section {
            DisclosureGroup(isExpanded: $showToolLedger) {
                toolLedgerRows(c)
            } label: {
                HStack {
                    Text("工具账")
                    Spacer()
                    Text("\(ledger.toolLedger.visible.count) 可见 · "
                         + "\(ledger.toolLedger.hidden.count) 隐藏 · "
                         + "≈ \(ledger.toolLedger.schemaTokens) Token")
                        .font(.caption2)
                        .foregroundStyle(c.textTertiary)
                        .monospacedDigit()
                }
            }
            DisclosureGroup(isExpanded: $showTechnical) {
                technicalRows(c)
            } label: {
                Text("技术收据")
            }
        } footer: {
            Text("技术收据是排查用的原始字段，平时不用展开。")
        }
    }

    @ViewBuilder
    private func toolLedgerRows(_ c: YNTheme.Palette) -> some View {
        if ledger.toolLedger.perTool.isEmpty {
            Text(ledger.isLegacy
                 ? "旧记录没有工具账（当时未计量）。"
                 : "本轮没有下发工具。")
                .font(.caption2)
                .foregroundStyle(c.textTertiary)
        } else {
            // 逐工具成本 —— **按 token 降序**（最贵的排第一，不让成本藏在折叠里）
            ForEach(ledger.toolLedger.perTool) { tool in
                HStack {
                    Text(tool.name)
                        .font(.caption.monospaced())
                        .foregroundStyle(c.textSecondary)
                    Spacer()
                    Text("≈ \(tool.tokens) Token")
                        .font(.caption2)
                        .foregroundStyle(c.textTertiary)
                        .monospacedDigit()
                }
            }
        }
        ForEach(ledger.toolLedger.hidden) { hidden in
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(hidden.name)
                        .font(.caption.monospaced())
                        .foregroundStyle(c.textTertiary)
                    Spacer()
                    Text(hidden.reason?.rawValue ?? "未下发")
                        .font(.caption2)
                        .foregroundStyle(c.textTertiary)
                }
                // 三分类引导：用户关的 vs 没配线路，去处不同
                if let severity = hidden.severity {
                    Text(severityText(severity))
                        .font(.caption2)
                        .foregroundStyle(severity == .normal ? c.textTertiary : c.warm)
                }
            }
        }
    }

    private func severityText(_ severity: ContextLedger.Severity) -> String {
        switch severity {
        case .actionable: return "你关掉了这个组 —— 去工具箱开启"
        case .config: return "能力还没就绪 —— 去用途线路配置"
        case .normal: return "正常状态，无需处理"
        case .notApplicable: return "本实现没有"
        }
    }

    @ViewBuilder
    private func technicalRows(_ c: YNTheme.Palette) -> some View {
        LabeledContent("结构版本", value: "v\(ledger.version)")
        LabeledContent("本机估算字节", value: "\(ledger.estimate.bytes)")
        LabeledContent("本机估算字符", value: "\(ledger.estimate.chars)")
        LabeledContent("工具 Schema 占比",
                       value: String(format: "%.1f%%", ledger.toolSchemaShare * 100))
        LabeledContent("差异公式", value: ledger.diff?.formula ?? "(provider−local)/local")
        ForEach(ledger.channels) { channel in
            VStack(alignment: .leading, spacing: 2) {
                Text(channel.cn)
                    .font(.caption)
                    .foregroundStyle(c.textSecondary)
                Text("id=\(channel.id) · status=\(channel.status.rawValue) · "
                     + "depth=\(channel.depth.rawValue) · owner=\(channel.owner) · "
                     + "fragments=\(channel.fragmentCount)")
                    .font(.caption2.monospaced())
                    .foregroundStyle(c.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

// MARK: - 从既有收据构造（Rust v2 到位前的桥）

extension ContextLedger {

    /// 用「本轮收据」的片段构造账本（v1 语义：全部 admitted）。
    ///
    /// ## 为什么需要有这个桥
    /// Rust 侧 `ledger_build_snapshot_json` 要等 CI 产出新框架才能被 Swift 调用，
    /// 而**界面不该等**：先用现有片段拼出同一套结构，账本页今天就能看。
    /// 等 v2 接通后，这里换成解码 JSON 即可，界面代码一行不用改。
    ///
    /// ## 已知局限（界面会如实标注 `isLegacy = true`）
    /// - 没有工具账（当时未计量）
    /// - 没有降级区（当时未记原因）
    static func from(receipt: TurnReceipt) -> ContextLedger {
        let encoder = JSONEncoder()
        let legacyShape = receipt.segments.map { seg -> [String: Any] in
            ["id": seg.id, "source": seg.source, "layer": Int(seg.layer),
             "lifetime": seg.lifetime, "chars": seg.chars]
        }
        if let data = try? JSONSerialization.data(withJSONObject: legacyShape),
           let json = String(data: data, encoding: .utf8),
           let decoded = ContextLedger.decode(fragmentsJson: json) {
            return decoded
        }
        // 兜底：空账本（宁可空，也不要假数据）
        _ = encoder
        return ContextLedger(
            version: 1, channels: [],
            toolLedger: ToolLedger(visible: [], hidden: [], perTool: [],
                                   schemaTokens: 0, schemaBytes: 0),
            estimate: Estimate(tokens: 0, chars: 0, bytes: 0,
                               method: "legacy:chars*3/3.5", isRoughEstimate: true),
            diff: Diff.compute(provider: nil, local: 0),
            isLegacy: true
        )
    }
}
