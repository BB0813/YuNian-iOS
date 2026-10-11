import SwiftUI

/// 记忆页 —— 「它到底记住了我什么」。
///
/// ## 这一页显示的就是**会进提示词的那批**
/// 已核实过（见 `MemoryRepository` 的注释）：`build_user_context` 调
/// `memory.select_working(...)`，**只看编排器持有的 MemorySelector**，
/// 完全不看 `request.tools`。所以这里不是"数据库里有什么"的展示，
/// 而是"模型每回合真的会看到什么"的一部分。
///
/// ## 三层分桶（分桶规则与引擎一致，不是我们自己编的）
/// | 桶 | 条件 | 引擎行为 |
/// |---|---|---|
/// | 短期（`WORKING`） | 未删、未过期 | `select_working` → 每回合注入 |
/// | 长期（`EPISODIC` / `SEMANTIC` …） | 未删、未过期 | 由 `recall_memory` / 每回合召回按相关度挑选 |
/// | 不再注入 | 已软删除**或已过期** | 既不注入也不召回 |
///
/// 第三桶是"它怎么忘了"的常见答案 —— 与聊天页「本轮收据」的
/// 「跳过与说明」正好互补：那里说这一轮带了什么，这里说库里还有什么。
struct MemoryView: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var memories: [MemoryRepository.Memory] = []
    @State private var query = ""
    @State private var showInactive = false
    @State private var sort: MemorySort = .importance
    @State private var notice: String?

    /// 排序方式（**本机排序**，不动引擎召回顺序 —— 那只由 importance/observedAt 决定）。
    enum MemorySort: String, CaseIterable, Identifiable {
        case importance
        case recent

        var id: String { rawValue }

        var title: String {
            switch self {
            case .importance: return "重要度"
            case .recent: return "最近"
            }
        }
    }

    /// 分桶（与引擎行为一致的判定）。
    enum MemoryBucket: String, CaseIterable {
        case working
        case longTerm
        case inactive

        var title: String {
            switch self {
            case .working: return "短期记忆（每回合都带给模型）"
            case .longTerm: return "长期记忆（按相关度召回）"
            case .inactive: return "不再注入（已删除或已过期）"
            }
        }

        var footer: String? {
            switch self {
            case .working:
                return "这些是 WORKING 层：只要未过期，每回合都会被注入，不依赖召回。"
            case .longTerm:
                return "这些不是每回合都送：引擎按相关度挑选（召回条数上限写死在引擎侧，宿主改不了）。"
            case .inactive:
                return "软删除的记录仍在库里（可被备份带走），但既不注入也不再被召回 —— 这就是「它怎么忘了」最常见的原因。"
            }
        }
    }

    /// 一组（供界面直接渲染）。
    struct Group: Identifiable {
        var bucket: MemoryBucket
        var items: [MemoryRepository.Memory]
        var id: String { bucket.rawValue }
    }

    var body: some View {
        let c = YNTheme.palette(scheme)
        let groups = Self.groups(memories, query: query, showInactive: showInactive,
                                 sort: sort, now: Self.nowMs())

        return List {
            overviewSection(c)
            ForEach(groups) { group in
                Section {
                    ForEach(group.items) { memory in
                        row(memory, colors: c)
                    }
                } header: {
                    Text("\(group.bucket.title)（\(group.items.count)）")
                } footer: {
                    if let footer = group.bucket.footer {
                        Text(footer)
                    }
                }
            }
            optionsSection(c)
            if let notice {
                Section { Text(notice).font(.caption).foregroundStyle(c.textSecondary) }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(YNCanvas())
        .navigationTitle("记忆")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, prompt: "搜记忆内容")
        .tint(c.accent)
        .onAppear(perform: reload)
    }

    // MARK: - 概览

    private func overviewSection(_ c: YNTheme.Palette) -> some View {
        let counts = Self.counts(memories, now: Self.nowMs())
        return Section {
            LabeledContent("短期", value: "\(counts.working) 条")
            LabeledContent("长期", value: "\(counts.longTerm) 条")
            LabeledContent("不再注入", value: "\(counts.inactive) 条")
        } header: {
            Text("总共 \(memories.count) 条")
        } footer: {
            Text("""
            只有"短期"与"长期"里的记录会被模型看到。想确认**最近一轮**到底带了哪些记忆，
            去聊天里长按那条回复 → 「本轮收据」—— 那里有逐条材料与字数。
            """)
        }
    }

    // MARK: - 一行记忆

    private func row(_ memory: MemoryRepository.Memory, colors c: YNTheme.Palette) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(memory.content)
                .font(.subheadline)
                .foregroundStyle(memory.isDeleted ? c.textTertiary : c.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: YNTheme.Space.sm) {
                Text(Self.typeTitle(memory.memoryType))
                Text(memory.scope == "GROUP" ? "群组" : "伴侣")
                if let expires = memory.expiresAt {
                    Text(Self.expiryTitle(expires, now: Self.nowMs()))
                }
                if memory.accessCount > 0 {
                    Text("取用 \(memory.accessCount) 次")
                }
                Spacer()
                Text(Self.importanceTitle(memory.importance))
            }
            .font(.caption2)
            .foregroundStyle(c.textTertiary)
        }
        .padding(.vertical, 2)
        .swipeActions(edge: .trailing) {
            if !memory.isDeleted {
                Button(role: .destructive) {
                    softDelete(memory)
                } label: {
                    Label("删除", systemImage: "trash")
                }
            }
        }
    }

    // MARK: - 选项

    private func optionsSection(_ c: YNTheme.Palette) -> some View {
        Section {
            Picker("排序", selection: $sort) {
                ForEach(MemorySort.allCases) { option in
                    Text(option.title).tag(option)
                }
            }
            .pickerStyle(.segmented)
            Toggle("显示已删除 / 已过期", isOn: $showInactive)
                .tint(c.accent)
        } header: {
            Text("显示")
        } footer: {
            Text("""
            排序只影响**这一页**的显示，不影响引擎的召回顺序（那是按重要度与时间算的）。
            删除是**软删除**：记录留在库里，可被备份带走，但不再注入、不再召回。
            """)
        }
    }

    // MARK: - 数据

    private func reload() {
        guard let repo = environment.memoryRepo else {
            memories = []
            return
        }
        memories = repo.listAll()
    }

    private func softDelete(_ memory: MemoryRepository.Memory) {
        guard let repo = environment.memoryRepo else { return }
        let ok = repo.softDelete(id: memory.id)
        notice = ok ? "已删除一条记忆（软删除，可从备份恢复）"
                    : "删除失败：这条记录可能已经不在了"
        reload()
    }

    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    // MARK: - 纯逻辑（可单测）

    /// 分桶判定。
    ///
    /// ⚠️ 判定必须与引擎**逐条对齐**（`memory_selector.rs:135/169`）：
    /// `WORKING` 走短期注入、其余走召回；已删除或已过期的两边都不进。
    /// 这里写错的表现是"界面说会注入、实际没注入" —— 比不显示更误导。
    nonisolated static func bucket(of memory: MemoryRepository.Memory,
                                   now: Int64) -> MemoryBucket {
        if memory.isDeleted { return .inactive }
        if let expires = memory.expiresAt, expires <= now { return .inactive }
        return memory.memoryType == "WORKING" ? .working : .longTerm
    }

    nonisolated static func counts(_ memories: [MemoryRepository.Memory],
                                   now: Int64) -> (working: Int, longTerm: Int, inactive: Int) {
        var working = 0, longTerm = 0, inactive = 0
        for memory in memories {
            switch bucket(of: memory, now: now) {
            case .working: working += 1
            case .longTerm: longTerm += 1
            case .inactive: inactive += 1
            }
        }
        return (working, longTerm, inactive)
    }

    /// 过滤 + 分桶 + 排序（纯函数，便于单测；界面直接渲染它）。
    nonisolated static func groups(_ memories: [MemoryRepository.Memory],
                                   query: String,
                                   showInactive: Bool,
                                   sort: MemorySort,
                                   now: Int64) -> [Group] {
        let keyword = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        var buckets: [MemoryBucket: [MemoryRepository.Memory]] = [:]
        for memory in memories {
            let target = bucket(of: memory, now: now)
            if target == .inactive, !showInactive { continue }
            if !keyword.isEmpty, !memory.content.lowercased().contains(keyword) { continue }
            buckets[target, default: []].append(memory)
        }
        // 桶的顺序固定：短期 → 长期 → 不再注入（"会注入的"永远在上面）
        return MemoryBucket.allCases.compactMap { bucket in
            guard let items = buckets[bucket], !items.isEmpty else { return nil }
            let sorted = items.sorted { lhs, rhs in
                switch sort {
                case .importance:
                    if lhs.importance != rhs.importance { return lhs.importance > rhs.importance }
                    return lhs.observedAt > rhs.observedAt
                case .recent:
                    if lhs.observedAt != rhs.observedAt { return lhs.observedAt > rhs.observedAt }
                    return lhs.importance > rhs.importance
                }
            }
            return Group(bucket: bucket, items: sorted)
        }
    }

    // MARK: - 文案

    /// 记忆类型的中文名（未知类型回退成原名 —— 引擎加了新类型也不会显示空白）。
    static func typeTitle(_ type: String) -> String {
        switch type {
        case "WORKING": return "短期"
        case "EPISODIC": return "情景"
        case "SEMANTIC": return "语义"
        case "PROCEDURAL": return "程序"
        default: return type.isEmpty ? "未标注" : type
        }
    }

    static func importanceTitle(_ importance: Double) -> String {
        String(format: "重要度 %.2f", importance)
    }

    static func expiryTitle(_ expiresAt: Int64, now: Int64) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd HH:mm"
        let date = Date(timeIntervalSince1970: TimeInterval(expiresAt) / 1000)
        if expiresAt <= now { return "已过期 \(formatter.string(from: date))" }
        return "有效至 \(formatter.string(from: date))"
    }
}
