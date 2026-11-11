import SwiftUI

/// 上下文账本 —— 「这一轮模型到底收到了什么」。
///
/// # 为什么是"先结论后明细"这个形状（第 232 轮改版）
///
/// 第一版我做成了审计工具的排布：8 行「名称 ×N ≈ N Token」，一行行往下排。
/// 用户的原话是「感觉很丑」。剖析下来问题**不在渲染，在结构**：
///
/// 1. 打开先看到 8 行长相一样的行，眼睛没有落点；
/// 2. 想知道"大头在哪"要自己做加法和除法 —— 而这恰恰是这页存在的**唯一理由**；
/// 3. 右侧一列「≈ N Token」重复 7 次，看着像账单不像界面。
///
/// 现在：**一个主数字 + 一条占比条 + 一句人话结论**，明细降级为三行折叠。
/// 「≈ Token」只在展开明细时出现。
///
/// # 两个清单的分工（保留）
/// - 材料（送入的）
/// - 没带上（降级，单独一块，**与送入的视觉分开**）
/// - 本实现没有（别家有的能力）
///
/// # 六条硬规则（来自 Aru 实测，逐条落进代码）
/// 1. 无供应商回执 → **差异行整行不渲染**（不是 `0%`，也不是 `—%`）
/// 2. 供应商口径每字段独立 `—`
/// 3. 本机估算照常显示
/// 4. 降级项按 `severity` 三分类引导（用户可改 / 配置缺失 / 正常状态）
/// 5. `skipped_no_trigger` 写「无需处理」，**不警示、不给修复入口**
/// 6. 降级项带结构化证据（可复现）
struct ContextLedgerView: View {

    let ledger: ContextLedger
    /// 对话标题（让用户知道这是"哪个对话"的账本）
    var conversationTitle: String = ""
    /// 供应商回执（有则显示差异；无则整行不渲染）
    var providerInputTokens: Int?
    /// 回合状态（四态，与"有没有回执"分开）
    var turnStatus: TurnStatus = .complete

    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    @State private var expanded: Set<String> = []

    /// 回合完成度。
    ///
    /// ⚠️ **不照抄 Aru 的 `status`**：它的用户中断轮次仍显示 `complete`（实测确认的缺陷）。
    /// 这里四态分开，"有没有 usage 回执"是另一个字段（`providerInputTokens == nil`）。
    enum TurnStatus: String {
        case complete, cancelled, failed, partial

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
        ScrollView {
            VStack(spacing: YNTheme.Space.md) {
                heroCard(c)
                usageCard(c)
                detailSection(c)
                if !actionable.isEmpty { actionableCard(c) }
                footnote(c)
            }
            .padding(.horizontal, YNTheme.Space.lg)
            .padding(.vertical, YNTheme.Space.md)
        }
        .background(YNCanvas())
        .navigationTitle("上下文账本")
        .navigationBarTitleDisplayMode(.inline)
        .tint(c.accent)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("完成") { dismiss() }
            }
        }
    }

    // MARK: - 主卡：一个数字 + 一条占比条 + 一句人话

    private func heroCard(_ c: YNTheme.Palette) -> some View {
        VStack(alignment: .leading, spacing: YNTheme.Space.sm) {
            Text(conversationTitle.isEmpty ? "这一轮" : "这一轮 · \(conversationTitle)")
                .font(.caption)
                .foregroundStyle(c.textTertiary)

            // 主数字：本机估算。它是这一页唯一该被"大字"强调的数字。
            HStack(alignment: .firstTextBaseline, spacing: YNTheme.Space.sm) {
                Text(Self.format(ledger.estimate.tokens))
                    .font(YNTheme.Display.hero)
                    .tracking(YNTheme.Display.heroTracking)
                    .foregroundStyle(c.textPrimary)
                    .monospacedDigit()
                Text("Token（本机估算）")
                    .font(.caption)
                    .foregroundStyle(c.textTertiary)
            }

            compositionBar(c)

            // 一句人话结论 —— 整页最重要的字，不让用户自己推
            Text(verdict)
                .font(.subheadline)
                .foregroundStyle(c.textPrimary)
                .fixedSize(horizontal: false, vertical: true)

            if turnStatus != .complete || ledger.isLegacy {
                HStack(spacing: 6) {
                    if turnStatus != .complete {
                        Label(turnStatus.text, systemImage: "exclamationmark.circle.fill")
                    }
                    if ledger.isLegacy {
                        Text("旧记录：未计量工具与世界书")
                    }
                }
                .font(.caption2)
                .foregroundStyle(c.warning)
            }
        }
        .padding(YNTheme.Space.lg)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(c.surface,
                    in: RoundedRectangle(cornerRadius: YNTheme.Radius.container, style: .continuous))
    }

    /// 占比条：**同一色相的透明度阶梯**。
    ///
    /// 为什么不用多色：`DESIGN.md` 规定一个控件里不同时出现两个强调色；
    /// 而"多通道拼色"必然要两三个色相。改用单色渐变既守住规则，
    /// 也更能表达"这是同一个总量的切分"。
    private func compositionBar(_ c: YNTheme.Palette) -> some View {
        let parts = ledger.admitted.sorted { $0.tokens > $1.tokens }
        let total = max(parts.reduce(0) { $0 + $1.tokens }, 1)
        return VStack(alignment: .leading, spacing: 8) {
            GeometryReader { proxy in
                HStack(spacing: 1) {
                    ForEach(Array(parts.enumerated()), id: \.element.id) { index, part in
                        let width = proxy.size.width * CGFloat(part.tokens) / CGFloat(total)
                        RoundedRectangle(cornerRadius: 2, style: .continuous)
                            .fill(c.accent.opacity(Self.ramp(index)))
                            .frame(width: max(width, 1.5))
                    }
                }
            }
            .frame(height: 10)
            .background(c.textTertiary.opacity(0.15),
                        in: RoundedRectangle(cornerRadius: 5, style: .continuous))

            // 图例只列前 3 —— 列全了又变回数字墙
            HStack(spacing: YNTheme.Space.md) {
                ForEach(Array(parts.prefix(3).enumerated()), id: \.element.id) { index, part in
                    HStack(spacing: 5) {
                        RoundedRectangle(cornerRadius: 2)
                            .fill(c.accent.opacity(Self.ramp(index)))
                            .frame(width: 8, height: 8)
                        Text("\(part.cn) \(Self.percent(part.tokens, of: total))")
                            .font(.caption2)
                            .foregroundStyle(c.textSecondary)
                    }
                }
                Spacer(minLength: 0)
            }
        }
    }

    // MARK: - 本轮用量

    private func usageCard(_ c: YNTheme.Palette) -> some View {
        let diff = ContextLedger.Diff.compute(provider: providerInputTokens,
                                              local: ledger.estimate.tokens)
        return VStack(alignment: .leading, spacing: 0) {
            sectionLabel("本轮用量", colors: c)
            VStack(spacing: 0) {
                kv("本机文本估算", Self.format(ledger.estimate.tokens), colors: c)
                divider(c)
                kv("估算算法", ledger.estimate.method + (ledger.estimate.isRoughEstimate ? "（粗估）" : ""),
                   colors: c, valueColor: c.textTertiary)
                divider(c)
                // 规则 2：供应商口径没值就给 `—`，不是 0
                kv("供应商输入",
                   diff.providerInputTokens.map { Self.format($0) } ?? "—",
                   colors: c,
                   valueColor: diff.providerInputTokens == nil ? c.textTertiary : c.textPrimary)
                // 规则 1：**没有回执就整行不渲染**（不是 0%、不是 —%）
                if let percentText = diff.percentText {
                    divider(c)
                    kv("差异", percentText, colors: c)
                }
            }
            .background(c.surface,
                        in: RoundedRectangle(cornerRadius: YNTheme.Radius.container, style: .continuous))

            Text("没有供应商回执就不给对比：不显示 0%，也不显示「—%」。")
                .font(.caption2)
                .foregroundStyle(c.textTertiary)
                .padding(.top, 6)
                .padding(.horizontal, 4)
        }
    }

    // MARK: - 明细（三行折叠）

    private func detailSection(_ c: YNTheme.Palette) -> some View {
        let groups: [(id: String, title: String, caption: String, items: [ContextLedger.Channel], color: Color)] = [
            ("material", "材料", "全部送进请求",
             ledger.admitted.sorted { $0.tokens > $1.tokens }, c.accent),
            ("degraded", "没带上", degradedCaption,
             ledger.degraded, c.warm),
            ("na", "本实现没有", "别家有的能力",
             ledger.notApplicable, c.textTertiary),
        ]
        return VStack(alignment: .leading, spacing: 0) {
            HStack {
                sectionLabel("明细", colors: c)
                Spacer()
                Text("\(ledger.admitted.count) 项材料 · \(ledger.degraded.count) 项未带入")
                    .font(.caption2)
                    .foregroundStyle(c.textTertiary)
                    .monospacedDigit()
            }
            VStack(spacing: 0) {
                ForEach(Array(groups.enumerated()), id: \.element.id) { index, group in
                    if index > 0 { divider(c) }
                    disclosureRow(group.id, group.title, group.caption,
                                  group.items, group.color, colors: c)
                }
            }
            .background(c.surface,
                        in: RoundedRectangle(cornerRadius: YNTheme.Radius.container, style: .continuous))
        }
    }

    private func disclosureRow(_ id: String, _ title: String, _ caption: String,
                              _ items: [ContextLedger.Channel], _ dotColor: Color,
                              colors c: YNTheme.Palette) -> some View {
        let isOpen = expanded.contains(id)
        return VStack(spacing: 0) {
            Button {
                withAnimation(YNTheme.Motion.quick) {
                    if isOpen { expanded.remove(id) } else { expanded.insert(id) }
                }
            } label: {
                HStack(alignment: .center, spacing: YNTheme.Space.sm) {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Text(title)
                                .font(.body)
                                .foregroundStyle(c.textPrimary)
                            Text("\(items.count) 项")
                                .font(.caption)
                                .foregroundStyle(c.textTertiary)
                                .monospacedDigit()
                        }
                        Text(caption)
                            .font(.caption2)
                            .foregroundStyle(c.textTertiary)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.down")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(c.textTertiary)
                        .rotationEffect(.degrees(isOpen ? 180 : 0))
                }
                .padding(.horizontal, YNTheme.Space.md)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(items.isEmpty)

            if isOpen && !items.isEmpty {
                VStack(spacing: 0) {
                    ForEach(items) { channel in
                        channelRow(channel, colors: c)
                    }
                }
                .padding(.bottom, 8)
            }
        }
    }

    /// 明细里的一行：名称 + ×N + Token（**只在展开时才有数字**）。
    private func channelRow(_ channel: ContextLedger.Channel, colors c: YNTheme.Palette) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Circle()
                    .fill(channel.sent ? c.accent : dotColorFor(channel, colors: c))
                    .frame(width: 6, height: 6)
                Text(channel.cn)
                    .font(.subheadline)
                    .foregroundStyle(channel.sent ? c.textPrimary : c.textSecondary)
                if let label = channel.itemsLabel ?? (channel.items > 1 ? "×\(channel.items)" : nil) {
                    Text(label)
                        .font(.caption2)
                        .foregroundStyle(c.textTertiary)
                }
                Spacer(minLength: 0)
                Text(channel.sent ? "≈ \(channel.tokens)" : "未送入")
                    .font(.caption2)
                    .foregroundStyle(c.textTertiary)
                    .monospacedDigit()
            }
            if !channel.sent, let hint = channel.reasonHint {
                Text(hint)
                    .font(.caption2)
                    .foregroundStyle(channel.severity == .normal ? c.textTertiary : c.warm)
                    .padding(.leading, 12)
            }
            if let fix = channel.fixPath {
                Text(fix)
                    .font(.caption2)
                    .foregroundStyle(c.accent)
                    .padding(.leading, 12)
            }
            if !channel.evidence.isEmpty {
                Text(channel.evidence.sorted { $0.key < $1.key }
                        .map { "\($0.key)=\($0.value)" }
                        .joined(separator: "; "))
                    .font(.caption2.monospaced())
                    .foregroundStyle(c.textTertiary)
                    .padding(.leading, 12)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.horizontal, YNTheme.Space.md)
        .padding(.vertical, 6)
        .accessibilityElement(children: .combine)
    }

    // MARK: - 可操作项（把"要你动手的"单独提出来）

    /// 只有**用户能动手改**的项才进这张卡。
    ///
    /// `skipped_no_trigger`（本轮没命中）**永远不进** —— 它不是故障，
    /// 放进来就是在催用户去改一个没问题的设置。
    private var actionable: [ContextLedger.Channel] {
        (ledger.degraded + ledger.channels.filter { !$0.sent && $0.severity == .config })
            .filter { $0.severity == .actionable || $0.severity == .config }
            .reduce(into: [ContextLedger.Channel]()) { acc, ch in
                if !acc.contains(where: { $0.fixPath == ch.fixPath && $0.cn == ch.cn }) {
                    acc.append(ch)
                }
            }
    }

    private func actionableCard(_ c: YNTheme.Palette) -> some View {
        VStack(spacing: 0) {
            ForEach(Array(actionable.prefix(3).enumerated()), id: \.element.id) { index, channel in
                if index > 0 { divider(c) }
                HStack(alignment: .top, spacing: YNTheme.Space.sm) {
                    Circle()
                        .fill(c.warm)
                        .frame(width: 6, height: 6)
                        .padding(.top, 6)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(channel.reasonHint ?? channel.cn)
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(c.textPrimary)
                        if let fix = channel.fixPath {
                            Text(fix)
                                .font(.caption2)
                                .foregroundStyle(c.textTertiary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, YNTheme.Space.md)
                .padding(.vertical, 12)
            }
        }
        .background(c.warmSoft,
                    in: RoundedRectangle(cornerRadius: YNTheme.Radius.container, style: .continuous))
    }

    private func footnote(_ c: YNTheme.Palette) -> some View {
        Text("两种口径不能相加：本机估算按文本体积算，供应商回执来自服务端。")
            .font(.caption2)
            .foregroundStyle(c.textTertiary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 4)
    }

    // MARK: - 小工具

    private func sectionLabel(_ text: String, colors c: YNTheme.Palette) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(c.textTertiary)
            .padding(.horizontal, 4)
            .padding(.bottom, 6)
    }

    private func kv(_ label: String, _ value: String, colors c: YNTheme.Palette,
                    valueColor: Color? = nil) -> some View {
        HStack {
            Text(label)
                .font(.subheadline)
                .foregroundStyle(c.textSecondary)
            Spacer(minLength: YNTheme.Space.sm)
            Text(value)
                .font(.subheadline)
                .foregroundStyle(valueColor ?? c.textPrimary)
                .monospacedDigit()
        }
        .padding(.horizontal, YNTheme.Space.md)
        .padding(.vertical, 11)
    }

    private func divider(_ c: YNTheme.Palette) -> some View {
        Rectangle()
            .fill(c.textTertiary.opacity(0.15))
            .frame(height: 0.5)
            .padding(.leading, YNTheme.Space.md)
    }

    private func dotColorFor(_ channel: ContextLedger.Channel, colors c: YNTheme.Palette) -> Color {
        switch channel.severity {
        case .actionable, .config: return c.warm
        case .normal, .notApplicable, .none: return c.textTertiary
        }
    }

    /// 把回合结束原因映射成四态。
    ///
    /// ⚠️ **不照抄 Aru**：它把"完成度"和"有没有回执"揉进一个 `status`，
    /// 结果用户点「停止」之后状态仍显示 `complete`（实测确认的缺陷）。
    /// 这里用引擎给的 `finishReason` 判真实完成度，回执缺失另算。
    ///
    /// 放在这里（而不是 `ChatView` 里）是因为**调用方有两处**：
    /// 聊天页的账本弹层，以及将来的其它入口 —— 复制两份迟早会漂。
    static func status(of receipt: TurnReceipt) -> TurnStatus {
        let reason = receipt.finishReason.lowercased()
        if reason.contains("cancel") || reason.contains("interrupt") { return .cancelled }
        if reason.contains("error") || reason.contains("fail") { return .failed }
        if receipt.usage == nil { return .partial }
        return .complete
    }

    private var degradedCaption: String {        guard !ledger.degraded.isEmpty else { return "全部按完整形态带入" }
        // 按原因三分类给出不同的副标题 —— 用户据此判断"要不要动手"
        if ledger.degraded.contains(where: { $0.severity == .actionable || $0.severity == .config }) {
            return "有需要你去配的项"
        }
        return "都是正常状态，无需处理"
    }

    /// 一句人话结论 —— **由数据算出**，不是写死的文案。
    private var verdict: String {
        guard !ledger.isLegacy else {
            return "旧记录没有计量工具成本，只能看到材料与字数。"
        }
        guard ledger.admittedTokens > 0 else {
            return "这一轮还没有可统计的材料。"
        }
        let share = ledger.toolSchemaShare
        let count = ledger.toolLedger.visible.count
        if share >= 0.6 {
            return String(format: "工具 Schema 占 %.0f%% —— 这是最大的一块，减少工具数量能明显省下输入。",
                          share * 100)
        }
        if share >= 0.3 {
            return String(format: "工具 Schema 占 %.0f%%（%d 个工具）—— 有一定占比，但不是主要成本。",
                          share * 100, count)
        }
        return String(format: "工具 Schema 占 %.0f%% —— 只装了 %d 个工具，少塞工具对这里几乎没用。",
                      share * 100, count)
    }

    /// 单色透明度阶梯（前几项最浓）。
    private static func ramp(_ index: Int) -> Double {
        let steps: [Double] = [0.92, 0.62, 0.46, 0.36, 0.28, 0.22, 0.16, 0.11]
        return index < steps.count ? steps[index] : 0.08
    }

    private static func percent(_ part: Int, of total: Int) -> String {
        guard total > 0 else { return "0%" }
        return String(format: "%.0f%%", Double(part) / Double(total) * 100)
    }

    private static func format(_ value: Int) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        return formatter.string(from: NSNumber(value: value)) ?? "\(value)"
    }
}

// MARK: - 从既有收据构造（Rust v2 到位前的桥）

extension ContextLedger {

    /// 用「本轮收据」的片段构造账本（v1 语义：全部 admitted）。
    ///
    /// Rust 侧 `ledger_build_snapshot_json` 要等 CI 产出新框架才能被 Swift 调用，
    /// 而**界面不该等**：先用现有片段拼出同一套结构，账本页今天就能看。
    /// v2 接通后换成解码 JSON，界面代码一行不用改。
    ///
    /// 局限（界面顶部如实标注 `isLegacy`）：没有工具账、没有降级区。
    static func from(receipt: TurnReceipt) -> ContextLedger {
        let legacyShape = receipt.segments.map { seg -> [String: Any] in
            ["id": seg.id, "source": seg.source, "layer": Int(seg.layer),
             "lifetime": seg.lifetime, "chars": seg.chars]
        }
        if let data = try? JSONSerialization.data(withJSONObject: legacyShape),
           let json = String(data: data, encoding: .utf8),
           let decoded = ContextLedger.decode(fragmentsJson: json) {
            return decoded
        }
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
