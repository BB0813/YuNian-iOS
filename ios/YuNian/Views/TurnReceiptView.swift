import SwiftUI

/// 本轮收据 —— 「这一轮模型到底收到了什么」。
///
/// ## 为什么放在聊天页而不是设置页
/// 它回答的是"**这一句**为什么这么答"，天然属于那一轮回复；放进设置页就
/// 失去了与具体回合的绑定（Aru 也是把它挂在消息菜单里）。
///
/// ## 两种口径**分开列、绝不相加**
/// 「本机估算」（按材料字符数）在请求前就能算；「供应商回执」（真实 token）
/// 只有服务端知道，且含缓存命中与新算输入。加成一个"总数"是错的。
///
/// ## 入口
/// 1. 聊天页长按某条助手回复 → 「本轮收据」（带那一轮的清单）
/// 2. 我 → AI 能力 → 「上下文与收据」（不带收据，只看选项与说明）
///
/// 第二个入口让这个页面同时承担"上下文选项"的家 ——
/// 选项摆在**能看到它效果**的地方旁边（改完这里就能看到材料条数与字数变化）。
struct TurnReceiptView: View {

    /// nil = 只显示选项与说明（从设置进的）。
    let receipt: TurnReceipt?
    /// 由聊天页注入：拿最新一轮的收据 / 读上下文选项。
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss

    @State private var contextSettings = ContextSettings()

    var body: some View {
        let c = YNTheme.palette(scheme)

        List {
            if let receipt {
                summarySection(receipt, colors: c)
                materialSection(receipt, colors: c)
                tokenSection(receipt, colors: c)
                toolSection(receipt, colors: c)
                skippedSection(receipt, colors: c)
                technicalSection(receipt, colors: c)
            } else {
                explainSection(colors: c)
            }
            contextOptionsSection(colors: c)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle(receipt == nil ? "上下文" : "本轮收据")
        .navigationBarTitleDisplayMode(.inline)
        .tint(c.accent)
        .onAppear {
            if let repo = environment.apiConfigs {
                contextSettings = ContextSettings.load(from: repo)
            }
        }
    }

    // MARK: - 概览

    private func summarySection(_ receipt: TurnReceipt, colors c: YNTheme.Palette) -> some View {
        Section {
            LabeledContent("时间", value: Self.stampFormatter.string(
                from: Date(timeIntervalSince1970: TimeInterval(receipt.timestamp) / 1000)))
            if !receipt.model.isEmpty {
                LabeledContent("线路模型", value: receipt.model)
            }
            if !receipt.provider.isEmpty {
                LabeledContent("供应商", value: receipt.provider)
            }
            LabeledContent("轮数", value: "\(receipt.roundsUsed)")
            if !receipt.finishReason.isEmpty {
                LabeledContent("结束原因", value: Self.finishReasonTitle(receipt.finishReason))
            }
        } header: {
            Text("这一轮")
        }
    }

    // MARK: - 材料（本机估算口径）

    private func materialSection(_ receipt: TurnReceipt, colors c: YNTheme.Palette) -> some View {
        Section {
            if receipt.segments.isEmpty {
                Text("没有采集到材料片段（编排器未就绪时会这样）。")
                    .font(.subheadline)
                    .foregroundStyle(c.textTertiary)
            } else {
                ForEach(receipt.segments) { segment in
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text(segment.sourceTitle)
                                .font(.subheadline)
                            Spacer()
                            Text("\(segment.chars) 字")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(c.textSecondary)
                        }
                        Text("\(segment.layerTitle) · \(segment.lifetime) · \(segment.id)")
                            .font(.caption2.monospaced())
                            .foregroundStyle(c.textTertiary)
                            .lineLimit(1)
                    }
                }
            }
        } header: {
            Text("本轮带入的材料（共 \(receipt.segments.count) 段 / \(receipt.estimatedChars) 字）")
        } footer: {
            Text("这是**本机估算**：按片段字符数统计，在请求发出前就能算出来。它不是 token 数。")
        }
    }

    // MARK: - Token（供应商回执口径）

    private func tokenSection(_ receipt: TurnReceipt, colors c: YNTheme.Palette) -> some View {
        Section {
            if let usage = receipt.usage {
                LabeledContent("输入", value: "\(usage.promptTokens)")
                LabeledContent("输出", value: "\(usage.completionTokens)")
                LabeledContent("合计", value: "\(usage.totalTokens)")
                LabeledContent("请求次数", value: "\(usage.requests)")
            } else {
                Text(receipt.timestamp == 0 || receipt.roundsUsed == 0
                     ? "本轮未留存供应商回执。"
                     : "供应商没有回传用量（部分中转/降级路径不给）。")
                    .font(.subheadline)
                    .foregroundStyle(c.textTertiary)
            }
        } header: {
            Text("供应商回执")
        } footer: {
            Text("""
            输入/输出是**供应商回执**的真实 token；多轮工具调用会把上下文重发，故按各轮累加。
            ⚠️ 它和上面的「本机估算」是**两种口径，不能相加** —— 前者含缓存命中与新算输入之分，只有服务端清楚。
            重启后打开的收据没有这一节：`prompt_audit` 只存材料清单，用量按日聚合在 `token_usage` 里。
            """)
        }
    }

    // MARK: - 工具

    private func toolSection(_ receipt: TurnReceipt, colors c: YNTheme.Palette) -> some View {
        Section {
            if receipt.toolNames.isEmpty {
                Text("本回合没有可下发的工具。")
                    .font(.subheadline)
                    .foregroundStyle(c.textTertiary)
            } else {
                ForEach(receipt.toolNames, id: \.self) { name in
                    Text(name).font(.caption.monospaced())
                }
            }
        } header: {
            Text("下发给模型的工具（\(receipt.toolNames.count) 个）")
        } footer: {
            Text("内置的回复/表情工具（send_sticker / emit_bubble / emit_segmented）由引擎恒注入，不在此列表内，也不受可见性开关控制。")
        }
    }

    // MARK: - 跳过与说明

    private func skippedSection(_ receipt: TurnReceipt, colors c: YNTheme.Palette) -> some View {
        Section {
            if receipt.skipped.isEmpty {
                Text("没有跳过任何部分。")
                    .font(.subheadline)
                    .foregroundStyle(c.textTertiary)
            } else {
                ForEach(receipt.skipped, id: \.self) { reason in
                    Label(reason, systemImage: "info.circle")
                        .font(.subheadline)
                        .foregroundStyle(c.textSecondary)
                }
            }
        } header: {
            Text("跳过与说明")
        } footer: {
            Text("「它怎么忘了」的答案通常在这一节：不是模型记性差，而是某一段根本没送进去。")
        }
    }

    // MARK: - 技术细节

    private func technicalSection(_ receipt: TurnReceipt, colors c: YNTheme.Palette) -> some View {
        Section {
            LabeledContent("系统提示词指纹", value: receipt.systemPromptHash.isEmpty ? "（未采集）" : receipt.systemPromptHash)
            LabeledContent("turnId", value: receipt.turnId)
        } header: {
            Text("技术细节")
        } footer: {
            Text("指纹变了 = 拼给模型的系统提示词变了（内容或顺序）。同一次会话里它应当稳定；抖动说明有东西在每轮变化。")
        }
    }

    // MARK: - 无收据时的说明

    private func explainSection(colors c: YNTheme.Palette) -> some View {
        Section {
            Text("""
            收据记录的是**一轮请求的装箱清单**：模型实际收到什么、没收到什么、各占多少。
            它不是日志，也不是设置备份 —— 它是"这一句为什么这么答"的取证面。
            """)
            .font(.subheadline)
            .foregroundStyle(c.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
            Label("在聊天里长按一条回复 → 「本轮收据」", systemImage: "hand.tap")
                .font(.subheadline)
                .foregroundStyle(c.textPrimary)
        } header: {
            Text("怎么看收据")
        }
    }

    // MARK: - 上下文选项（**只放真能生效的**）

    private func contextOptionsSection(colors c: YNTheme.Palette) -> some View {
        Section {
            Stepper(value: $contextSettings.historyLimit,
                    in: ContextSettings.historyLimitRange,
                    step: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("历史消息上限")
                    Text("当前 \(contextSettings.historyLimit) 条")
                        .font(.caption)
                        .foregroundStyle(c.textSecondary)
                }
            }
            Stepper(value: $contextSettings.workingMemoryLimit,
                    in: ContextSettings.workingMemoryLimitRange,
                    step: 20) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("短期记忆注入上限")
                    Text("当前 \(contextSettings.workingMemoryLimit) 条")
                        .font(.caption)
                        .foregroundStyle(c.textSecondary)
                }
            }
        } header: {
            Text("上下文选项")
        } footer: {
            Text("""
            下一回合生效。
            「历史消息上限」由本机截断（引擎收到多少就送多少）；「短期记忆注入上限」写在 settings 里，引擎读它。
            其它上下文开关（记忆召回条数、L2 安全说明、L4 记忆技能说明）目前**写死在引擎侧、宿主改不了**，
            所以这里不放 —— 放上去就是一个拨了没反应的开关。
            """)
        }
        .onChange(of: contextSettings) { _, newValue in
            guard let repo = environment.apiConfigs else { return }
            try? newValue.save(to: repo)
        }
    }

    // MARK: - 文案

    private static func finishReasonTitle(_ reason: String) -> String {
        switch reason {
        case "stop": return "正常结束"
        case "length": return "达到输出上限"
        case "tool_calls": return "工具调用"
        case "confirm_pending": return "等待用户确认"
        default: return reason
        }
    }

    private static let stampFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd HH:mm:ss"
        return formatter
    }()
}
