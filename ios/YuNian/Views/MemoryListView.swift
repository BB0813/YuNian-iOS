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
    @State private var memories: [MemoryRepository.Memory] = []
    @State private var counts: (active: Int, total: Int, deleted: Int) = (0, 0, 0)
    @State private var showDeleted = false
    @State private var errorMessage: String?

    var body: some View {
        List {
            Section {
                HStack {
                    stat("有效", counts.active)
                    Spacer()
                    stat("已删除", counts.deleted)
                    Spacer()
                    stat("合计", counts.total)
                }
                .font(.caption)
            }

            if let errorMessage {
                Section {
                    Label(errorMessage, systemImage: "exclamationmark.triangle")
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }

            Section("有效记忆") {
                if activeMemories.isEmpty {
                    Text("还没有记忆。对话中模型会通过记忆工具积累，或由整理任务生成。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(activeMemories) { memory in
                        row(memory)
                    }
                    .onDelete(perform: delete)
                }
            }

            if showDeleted, !deletedMemories.isEmpty {
                Section("已删除") {
                    ForEach(deletedMemories) { row($0) }
                }
            }
        }
        .navigationTitle("记忆")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(showDeleted ? "隐藏已删除" : "显示已删除") {
                    showDeleted.toggle()
                }
                .font(.caption)
            }
        }
        .task { reload() }
    }

    // MARK: - 子视图

    private func stat(_ label: String, _ value: Int) -> some View {
        VStack(spacing: 2) {
            Text("\(value)").font(.headline.monospacedDigit())
            Text(label).foregroundStyle(.secondary)
        }
    }

    private func row(_ memory: MemoryRepository.Memory) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(memory.memoryType)
                    .font(.caption2)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color.accentColor.opacity(0.15))
                    .clipShape(Capsule())
                Text(memory.scope)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Spacer()
                Text(String(format: "%.2f", memory.importance))
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            Text(memory.content)
                .font(.callout)
            if let expiresAt = memory.expiresAt {
                Text("过期于 \(format(expiresAt))")
                    .font(.caption2)
                    .foregroundStyle(.orange)
            }
        }
        .padding(.vertical, 2)
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

    private func delete(_ offsets: IndexSet) {
        guard let repo = environment.memoryRepo else { return }
        let targets = activeMemories(at: offsets)
        var failed = 0
        for memory in targets where !repo.softDelete(id: memory.id) {
            failed += 1
        }
        reload()
        if failed > 0 {
            errorMessage = "\(failed) 条记忆删除失败"
        }
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
