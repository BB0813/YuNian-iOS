import SwiftUI

/// 用量页 —— 「这个月烧了多少 token」。
///
/// ## 为什么现在才有
/// `token_usage` 表一直存在，但 iOS 侧此前**只有备份恢复会写它**，
/// 运行时无人写 → 查出来恒为 0。摆一个恒为 0 的数字比没有更糟，
/// 所以这一页一直没做。第 207 轮的「本轮收据」开始每回合写它
/// （伴侣行 + 全局行，按日累加），这里才有了可看的东西。
///
/// ## 两个必须写在界面上的事实
/// 1. **数据从"本轮收据"那个版本开始** —— 更早的用量不存在，无法追溯。
///    （`firstDate` 会如实显示从哪天开始有记录。）
/// 2. **这里的数字是供应商回执**（真实 token），不是本机按字数估的。
///    两者口径不同，不能互相换算。
struct UsageStatsView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var stats = UsageStatsRepository.Stats()
    @State private var confirmClearOld = false
    @State private var confirmClearAll = false
    @State private var notice: String?

    var body: some View {
        let c = YNTheme.palette(scheme)

        List {
            if stats.hasAnyData {
                overviewSection(c)
                dailySection(c)
                companionSection(c)
            } else {
                emptySection(c)
            }
            maintenanceSection(c)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("用量")
        .navigationBarTitleDisplayMode(.inline)
        .tint(c.accent)
        .onAppear(perform: reload)
        .confirmationDialog("清理 90 天前的用量记录？",
                            isPresented: $confirmClearOld, titleVisibility: .visible) {
            Button("清理", role: .destructive) {
                let removed = UsageStatsRepository.clearOlderThan(
                    days: 90, database: environment.database, deviceId: DeviceIdentity.deviceId)
                notice = "已清理 \(removed) 条记录"
                reload()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("只删本机（deviceId 匹配）且早于 90 天的行，不影响对话与记忆。")
        }
        .confirmationDialog("清空本机全部用量记录？",
                            isPresented: $confirmClearAll, titleVisibility: .visible) {
            Button("清空", role: .destructive) {
                let removed = UsageStatsRepository.clearAll(
                    database: environment.database, deviceId: DeviceIdentity.deviceId)
                notice = "已清空 \(removed) 条记录"
                reload()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("只删用量统计，不影响对话与记忆。清空后不可恢复。")
        }
    }

    // MARK: - 概览

    private func overviewSection(_ c: YNTheme.Palette) -> some View {
        Section {
            LabeledContent("今日合计", value: Self.number(todayTotal))
            LabeledContent("今日请求", value: "\(todayRequests) 次")
            LabeledContent("累计输入", value: Self.number(stats.totalInput))
            LabeledContent("累计输出", value: Self.number(stats.totalOutput))
            LabeledContent("累计合计", value: Self.number(stats.totalTokens))
            LabeledContent("累计请求", value: "\(stats.totalRequests) 次")
        } header: {
            Text("总计")
        } footer: {
            Text(footerText)
        }
    }

    private func dailySection(_ c: YNTheme.Palette) -> some View {
        let peak = max(1, stats.days.map(\.totalTokens).max() ?? 1)
        return Section {
            ForEach(stats.days) { day in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(Self.dayTitle(day.date))
                            .font(.subheadline)
                        Spacer()
                        Text("\(Self.number(day.totalTokens)) · \(day.requests) 次")
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(c.textSecondary)
                    }
                    // 比例条：一眼看出哪几天重。宽度按当日占峰值的比例。
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(c.surface)
                            Capsule()
                                .fill(c.accent.opacity(0.55))
                                .frame(width: max(2, geo.size.width * CGFloat(day.totalTokens) / CGFloat(peak)))
                        }
                    }
                    .frame(height: 4)
                }
                .padding(.vertical, 2)
            }
        } header: {
            Text("按日（最近 \(stats.days.count) 天有记录）")
        }
    }

    private func companionSection(_ c: YNTheme.Palette) -> some View {
        Section {
            ForEach(stats.companions) { item in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.name.isEmpty ? "伴侣 #\(item.companionId)" : item.name)
                            .font(.subheadline)
                        Text("输入 \(Self.number(item.inputTokens)) · 输出 \(Self.number(item.outputTokens))")
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(c.textTertiary)
                    }
                    Spacer()
                    Text("\(Self.number(item.totalTokens))")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(c.textSecondary)
                }
            }
        } header: {
            Text("按伴侣")
        } footer: {
            Text("""
            全局合计与这里的各项**不是相加关系**：库里 `companionId = -1` 的那一行就是全局汇总，
            它天然等于各伴侣之和。所以两处分别显示、不重复累加。
            """)
        }
    }

    private func emptySection(_ c: YNTheme.Palette) -> some View {
        Section {
            Text("还没有用量记录。")
                .font(.subheadline)
                .foregroundStyle(c.textSecondary)
            Text("聊过一轮之后，这里会出现输入/输出 token 与请求次数 —— 数字来自**供应商回执**，不是本机估算。")
                .font(.caption)
                .foregroundStyle(c.textTertiary)
        } header: {
            Text("暂无数据")
        }
    }

    // MARK: - 维护（选项都放在这里，留好扩展位）

    private func maintenanceSection(_ c: YNTheme.Palette) -> some View {
        Section {
            Button(role: .destructive) {
                confirmClearOld = true
            } label: {
                Label("清理 90 天前的记录", systemImage: "clock.arrow.circlepath")
            }
            Button(role: .destructive) {
                confirmClearAll = true
            } label: {
                Label("清空本机用量", systemImage: "trash")
            }
            if let notice {
                Text(notice)
                    .font(.caption)
                    .foregroundStyle(c.textSecondary)
            }
        } header: {
            Text("维护")
        } footer: {
            Text("""
            目前是**手动**清理。要保持长期不留堆积，将来可以在这里接一个「自动保留 N 天」
            （Android 侧走的是 `TokenUsageDao.deleteOldRecords`，同一套语义）。
            删除一律限定 `deviceId = 本机`，不会碰别的设备的数据。
            """)
        }
    }

    // MARK: - 数据

    private var todayTotal: Int {
        stats.days.first { $0.date == UsageStatsRepository.todayString() }?.totalTokens ?? 0
    }

    private var todayRequests: Int {
        stats.days.first { $0.date == UsageStatsRepository.todayString() }?.requests ?? 0
    }

    private var footerText: String {
        var text = "数据来自**供应商回执**的真实 token（多轮工具调用会把上下文重发，故按各轮累加）。"
        if let first = stats.firstDate {
            text += "\n从 \(first) 开始有记录 —— 更早的用量在这一版之前没有被写入，无法追溯。"
        }
        return text
    }

    private func reload() {
        stats = UsageStatsRepository.load(database: environment.database,
                                          deviceId: DeviceIdentity.deviceId)
    }

    // MARK: - 文案

    private static func number(_ value: Int) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        return formatter.string(from: NSNumber(value: value)) ?? "\(value)"
    }

    /// `2026-10-11` → `10-11 周六`。
    private static func dayTitle(_ date: String) -> String {
        guard let parsed = dayFormatter.date(from: date) else { return date }
        return shortFormatter.string(from: parsed)
    }

    private static let dayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()

    private static let shortFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd EEE"
        return formatter
    }()
}
