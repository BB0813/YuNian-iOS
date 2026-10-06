import SwiftUI

/// 记忆管理界面（M3）。
///
/// ## 它管理的是「会进提示词的那批」
/// `MemoryRepository.listActive()` 的过滤条件与 `AgentStores.listMemories`
/// 逐字一致（deviceId + isDeleted + expiry），因此这里看到的正是
/// `PromptOrchestrator::build_user_context` 会注入 `[近期记忆]` 的那批。
///
/// ## 删除语义
/// 软删除（`isDeleted = 1`）而不是物理删除 —— 与 Android `softDelete` 一致。
/// 删除是**即时生效**的：下一次回合的 `syncRuntimeConfig` → `listMemories`
/// 就会看不到它。
struct MemoryListView: View {

    @EnvironmentObject private var environment: AppEnvironment
    /// ⚠️ 第 128 轮：语义色跟随系统明暗。
    @Environment(\.colorScheme) private var scheme
    @State private var memories: [MemoryRepository.Memory] = []
    @State private var counts: (active: Int, total: Int, deleted: Int) = (0, 0, 0)
    @State private var showDeleted = false
    @State private var errorMessage: String?

    private var colors: YuNianTheme.Colors { YuNianTheme.colors(scheme) }

    /// 第 128 轮：套上设计系统。
    ///
    /// 对照 `feature/memory/.../MemoryScreen.kt`：
    /// - 统计区对应同伴 chip 行 + TabRow（104-193），iOS 简化为一行 metric 卡
    /// - 记忆卡 `MemoryItemCard`：**12dp 圆角** + drawGlass + padding(14)
    ///   （MemoryScreen.kt:603-716）
    /// - 分类徽标：`primary@0.7` 图标 + 分类名 11sp Medium `primary@0.8`
    ///   （MemoryScreen.kt:611-616）
    /// - 正文 14sp / lineHeight 20sp `onSurface@0.9`；摘要 11sp；时间 10sp
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: YuNianTheme.Space.standard) {

                // 统计行 —— GlassCard 的 Metric 三态（GlassCard.kt:38-47）
                YuNianGlassCard(style: .metric) {
                    HStack {
                        stat("有效", counts.active)
                        Spacer()
                        stat("已删除", counts.deleted)
                        Spacer()
                        stat("合计", counts.total)
                    }
                }

                if let errorMessage {
                    Text(errorMessage)
                        .font(.caption)
                        .foregroundStyle(colors.danger)
                        .textSelection(.enabled)
                        .padding(.horizontal, YuNianTheme.Space.minUnit)
                }

                YuNianSectionTitle(title: "有效记忆")

                if activeMemories.isEmpty {
                    Text("还没有记忆。对话中模型会通过记忆工具积累，或由整理任务生成。")
                        .font(.system(size: 12))
                        .foregroundStyle(colors.textSecondary)
                        .padding(.horizontal, YuNianTheme.Space.minUnit)
                } else {
                    ForEach(activeMemories) { memory in
                        YuNianGlassCard { row(memory) }
                            .swipeActions {
                                Button(role: .destructive) {
                                    deleteMemory(memory)
                                } label: {
                                    Label("删除", systemImage: "trash")
                                }
                            }
                    }
                }

                if showDeleted, !deletedMemories.isEmpty {
                    YuNianSectionTitle(title: "已删除")
                    ForEach(deletedMemories) { memory in
                        YuNianGlassCard { row(memory) }
                    }
                }

                Spacer(minLength: YuNianTheme.Space.pageTop)
            }
            .padding(.horizontal, YuNianTheme.Space.page)
            .padding(.top, YuNianTheme.Space.standard)
        }
        .background(colors.background.ignoresSafeArea())
        .navigationTitle("记忆")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(showDeleted ? "隐藏已删除" : "显示已删除") {
                    showDeleted.toggle()
                }
                .font(.caption)
                .foregroundStyle(colors.primary)
            }
        }
        .task { reload() }
    }

    // MARK: - 子视图

    private func stat(_ label: String, _ value: Int) -> some View {
        VStack(spacing: YuNianTheme.Space.micro) {
            Text("\(value)")
                .font(.system(size: 22, weight: .semibold).monospacedDigit())
                .foregroundStyle(colors.textPrimary)
            Text(label)
                .font(.system(size: 11))
                .foregroundStyle(colors.textSecondary)
        }
    }

    /// 单条记忆 —— 对应 `MemoryItemCard`（MemoryScreen.kt:603-716）。
    private func row(_ memory: MemoryRepository.Memory) -> some View {
        VStack(alignment: .leading, spacing: YuNianTheme.Space.half) {
            HStack(spacing: YuNianTheme.Space.half) {
                // 分类徽标：11sp Medium + primary@0.8（MemoryScreen.kt:611-616）
                Text(memory.memoryType)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(colors.primary.opacity(0.8))
                    .padding(.horizontal, YuNianTheme.Space.standard)
                    .padding(.vertical, YuNianTheme.Space.tight)
                    .background(colors.primary.opacity(0.12))
                    .clipShape(Capsule())

                Text(memory.scope)
                    .font(.system(size: 11))
                    .foregroundStyle(colors.textSecondary)

                Spacer()

                Text(String(format: "%.2f", memory.importance))
                    .font(.system(size: 10).monospacedDigit())
                    .foregroundStyle(colors.textSecondary)
            }

            // 正文：14sp，lineHeight 20sp，onSurface@0.9
            Text(memory.content)
                .font(.system(size: 14))
                .foregroundStyle(colors.textPrimary.opacity(0.9))

            if let expiresAt = memory.expiresAt {
                Text("过期于 \(format(expiresAt))")
                    .font(.system(size: 10))
                    .foregroundStyle(colors.warning)
            }
        }
    }

    // MARK: - 数据

    private var activeMemories: [MemoryRepository.Memory] {
        memories.filter { !$0.isDeleted }
    }

    private var deletedMemories: [MemoryRepository.Memory] {
        memories.filter(\.isDeleted)
    }

    private func reload() {
        guard let repo = environment.memoryRepo else {
            errorMessage = "数据库未就绪"
            return
        }
        memories = repo.listAll()
        counts = repo.counts()
        errorMessage = nil
    }

    /// 单条软删除。第 128 轮由 `.onDelete`（List 专用）改为
    /// `.swipeActions`（玻璃卡列表用），故形参从 IndexSet 变成具体条目。
    private func deleteMemory(_ memory: MemoryRepository.Memory) {
        guard let repo = environment.memoryRepo else { return }
        if !repo.softDelete(id: memory.id) {
            errorMessage = "「\(memory.content.prefix(20))…」删除失败"
        }
        reload()
    }

    private func activeMemories(at offsets: IndexSet) -> [MemoryRepository.Memory] {
        let active = activeMemories
        return offsets.compactMap { $0 < active.count ? active[$0] : nil }
    }

    private func format(_ ms: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(ms) / 1000)
        return date.formatted(date: .abbreviated, time: .omitted)
    }
}
