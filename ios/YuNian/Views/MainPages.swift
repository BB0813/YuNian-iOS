import SwiftUI

// 三个一级页面。
//
// ⚠️ **当前是骨架（占位），不是成品。**
// 视觉层已整体废弃，这些页面将按 `APPLE-DESIGN-CONSTRAINTS.md`
// 与 Android 端的功能对照**逐屏实现**。
//
// 现阶段刻意保持最小：保证工程可编译、可真机运行，
// 以便每完成一屏就能立刻装机验收（而不是攒一大坨再上机）。

/// 予念 —— 会话列表。
///
/// 使用系统 `List` / `NavigationLink`，不把每行包成圆角卡片，也不使用 Liquid Glass。
struct ConversationsView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var rows: [ConversationRepository.ConvRow] = []
    @State private var loadError: String?

    var body: some View {
        let c = YNTheme.palette(scheme)
        NavigationStack {
            ZStack {
                YNCanvas()

                if rows.isEmpty, loadError == nil {
                    ContentUnavailableView {
                        Label("还没有对话", systemImage: "bubble.left.and.bubble.right")
                    } description: {
                        // 空状态是第一次打开时唯一的引导面：这里必须回答"下一步做什么"。
                        // 指路到通讯录（选角色）+ 使用指南（怎么配 Key）。
                        Text("到「通讯录」选择一位角色开始聊天。\n第一次用？「我 → 关于 → 使用指南」有三步配置说明。")
                    }
                } else {
                    List(rows) { row in
                        NavigationLink {
                            ChatView(companionId: row.companionId)
                        } label: {
                            ConversationRow(row: row)
                        }
                        .listRowBackground(Color.clear)
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .refreshable { reload() }
                }
            }
            .navigationTitle("予念")
            .alert("无法载入会话", isPresented: errorPresented) {
                Button("重试") { reload() }
                Button("取消", role: .cancel) { loadError = nil }
            } message: {
                Text(loadError ?? "发生未知错误")
            }
            .task { reload() }
            .onAppear { reload() }
        }
        .tint(c.accent)
    }

    private var errorPresented: Binding<Bool> {
        Binding(
            get: { loadError != nil },
            set: { if !$0 { loadError = nil } }
        )
    }

    private func reload() {
        guard let database = environment.database else { return }
        do {
            let repo = ConversationRepository(database: database)
            rows = try repo.rows()
            rows = try rows.map { item in
                var copy = item
                copy.hasUnread = try repo.unreadCount(companionId: item.companionId) > 0
                return copy
            }
            loadError = nil
        } catch {
            // 不把 GRDB SQL / schema / 路径暴露到 UI。完整错误留给系统日志与诊断页。
            loadError = "会话列表暂时无法读取，请稍后重试。"
        }
    }
}

private struct ConversationRow: View {
    let row: ConversationRepository.ConvRow

    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let c = YNTheme.palette(scheme)
        HStack(spacing: YNTheme.Space.md) {
            CompanionAvatar(avatarURL: row.avatarUrl, name: row.name, size: 46)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: YNTheme.Space.sm) {
                    Text(row.name)
                        .font(.body.weight(row.hasUnread ? .semibold : .regular))
                        .foregroundStyle(c.textPrimary)
                        .lineLimit(1)

                    Spacer(minLength: YNTheme.Space.sm)

                    if let timestamp = row.lastMessageAt {
                        Text(Self.timeString(timestamp))
                            .font(.caption)
                            .foregroundStyle(c.textTertiary)
                    }
                }

                HStack(spacing: 6) {
                    if row.hasUnread {
                        Circle()
                            .fill(c.accent)
                            .frame(width: 7, height: 7)
                            .accessibilityLabel("有未读消息")
                    }
                    Text(row.lastMessageFromUser ? "我：\(row.preview)" : row.preview)
                        .font(.subheadline)
                        .foregroundStyle(row.hasUnread ? c.textSecondary : c.textTertiary)
                        .lineLimit(1)
                }
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private static func timeString(_ milliseconds: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
        let formatter = DateFormatter()
        formatter.doesRelativeDateFormatting = true
        formatter.dateStyle = Calendar.current.isDateInToday(date) ? .none : .short
        formatter.timeStyle = Calendar.current.isDateInToday(date) ? .short : .none
        return formatter.string(from: date)
    }
}

/// 通讯录 —— 角色列表。
struct ContactsView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.colorScheme) private var scheme

    @State private var companions: [CompanionRepository.Companion] = []
    @State private var loadError: String?
    @State private var showCreator = false
    @State private var editTarget: CompanionRepository.Companion?
    /// 待删除的角色 + 是否连聊天记录一起删（两选项，见删除对话框）。
    @State private var deleteTarget: CompanionRepository.Companion?

    var body: some View {
        let c = YNTheme.palette(scheme)
        NavigationStack {
            ZStack {
                YNCanvas()

                if companions.isEmpty, loadError == nil {
                    ContentUnavailableView {
                        Label("还没有联系人", systemImage: "person.2")
                    } description: {
                        Text("创建角色后，ta 会出现在这里")
                    } actions: {
                        Button("新建角色") { showCreator = true }
                    }
                } else {
                    List(companions) { companion in
                        NavigationLink {
                            ChatView(companionId: companion.id)
                        } label: {
                            HStack(spacing: YNTheme.Space.md) {
                                CompanionAvatar(
                                    avatarURL: companion.avatarUrl,
                                    name: companion.name,
                                    size: 46
                                )
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(companion.name)
                                        .font(.body)
                                        .foregroundStyle(c.textPrimary)
                                    if !companion.personality.isEmpty {
                                        Text(companion.personality)
                                            .font(.subheadline)
                                            .foregroundStyle(c.textSecondary)
                                            .lineLimit(1)
                                    }
                                }
                            }
                            .padding(.vertical, 4)
                            .contentShape(Rectangle())
                        }
                        // 第 212 轮：编辑与删除。此前通讯录是**只读**的 ——
                        // 仓储早有 create/update/delete，但没有任何界面调用过。
                        .swipeActions(edge: .leading) {
                            Button {
                                editTarget = companion
                            } label: {
                                Label("编辑", systemImage: "pencil")
                            }
                            .tint(c.accent)
                        }
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) {
                                deleteTarget = companion
                            } label: {
                                Label("删除", systemImage: "trash")
                            }
                        }
                        .listRowBackground(Color.clear)
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .refreshable { reload() }
                }
            }
            .navigationTitle("通讯录")
            // 点行 = 去聊天（主操作），改人设走左滑 —— 与记忆/技能页的手势语言一致
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { showCreator = true } label: {
                        Label("新建角色", systemImage: "plus")
                    }
                }
            }
            .sheet(isPresented: $showCreator) {
                NavigationStack {
                    CompanionEditView(companion: nil, onSaved: reload)
                }
            }
            .sheet(item: $editTarget) { target in
                NavigationStack {
                    CompanionEditView(companion: target, onSaved: reload)
                }
            }
            .confirmationDialog("删除角色「\(deleteTarget?.name ?? "")」？",
                                isPresented: Binding(get: { deleteTarget != nil },
                                                     set: { if !$0 { deleteTarget = nil } }),
                                titleVisibility: .visible) {
                // 两个选项都是破坏性的，但后果差别很大 —— 必须让用户自己选，
                // 不能替他决定（删角色保留记录 / 连记录一起删）。
                Button("只删角色，保留聊天记录", role: .destructive) {
                    if let target = deleteTarget { performDelete(target, alsoMessages: false) }
                    deleteTarget = nil
                }
                Button("连聊天记录一起删", role: .destructive) {
                    if let target = deleteTarget { performDelete(target, alsoMessages: true) }
                    deleteTarget = nil
                }
                Button("取消", role: .cancel) { deleteTarget = nil }
            } message: {
                Text(deleteHint)
            }
            .alert("无法载入通讯录", isPresented: errorPresented) {
                Button("重试") { reload() }
                Button("取消", role: .cancel) { loadError = nil }
            } message: {
                Text(loadError ?? "发生未知错误")
            }
            .task { reload() }
            .onAppear { reload() }
        }
        .tint(c.accent)
    }

    /// 删除说明随"是不是内置默认角色"变化 —— 内置角色删掉后不会再自动重建。
    private var deleteHint: String {
        guard let target = deleteTarget else { return "" }
        var text = "「只删角色」会保留 ta 的聊天记录（记录仍可被备份带走）；「连聊天记录一起删」不可恢复。"
        if DefaultCompanionSeeder.isDefaultExperienceCompanion(target) {
            text += "\n\n⚠️ 这是内置的默认角色：删掉后不会再自动重建。"
        }
        return text
    }

    /// ⚠️ 顺序必须是**先清消息、再删角色**（`messages` 表对 companions 没有外键，
    /// 反过来做会留下孤儿会话）。
    private func performDelete(_ target: CompanionRepository.Companion, alsoMessages: Bool) {
        do {
            if alsoMessages, let messages = environment.messages {
                try messages.deleteForConversation(conversationId: target.id, type: .chat)
            }
            try environment.companions?.delete(id: target.id)
            reload()
        } catch {
            loadError = "删除失败，请稍后重试。"
        }
    }

    private var errorPresented: Binding<Bool> {
        Binding(
            get: { loadError != nil },
            set: { if !$0 { loadError = nil } }
        )
    }

    private func reload() {
        do {
            companions = try environment.companions?.fetchAll() ?? []
            loadError = nil
        } catch {
            loadError = "通讯录暂时无法读取，请稍后重试。"
        }
    }
}

// 「我」页已移到 `MeView.swift`（第 202 轮：它不再是占位，
// 而是承载模型渠道配置 / 个人信息的真实设置页）。
