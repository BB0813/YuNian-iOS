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
    @State private var query = ""
    /// 待清空记录的那个会话（危险操作要二次确认，并显示会删掉多少条）。
    @State private var clearTarget: ConversationRepository.ConvRow?
    @State private var notice: String?

    var body: some View {
        let c = YNTheme.palette(scheme)
        let filtered = ConversationRepository.filter(rows, query: query)

        return NavigationStack {
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
                    List(filtered) { row in
                        NavigationLink {
                            ChatView(companionId: row.companionId)
                        } label: {
                            ConversationRow(row: row)
                        }
                        // 只清理**这个会话**的记录，不动角色 ——
                        // 与「通讯录」里删角色是两件事，菜单文案要分清。
                        .swipeActions(edge: .trailing) {
                            if row.messageCount > 0 {
                                Button(role: .destructive) {
                                    clearTarget = row
                                } label: {
                                    Label("清空记录", systemImage: "eraser")
                                }
                            }
                        }
                        .swipeActions(edge: .leading) {
                            if row.hasUnread {
                                Button {
                                    markRead(row)
                                } label: {
                                    Label("已读", systemImage: "checkmark.circle")
                                }
                                .tint(c.accent)
                            }
                        }
                        .listRowBackground(Color.clear)
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .refreshable { reload() }
                    // 搜索：名字或最后一条消息（纯函数过滤，有测试）
                    .searchable(text: $query, prompt: "搜索名字或消息")
                    .overlay {
                        if filtered.isEmpty, !query.isEmpty {
                            ContentUnavailableView.search(text: query)
                        }
                    }
                }
            }
            .navigationTitle("予念")
            .alert("无法载入会话", isPresented: errorPresented) {
                Button("重试") { reload() }
                Button("取消", role: .cancel) { loadError = nil }
            } message: {
                Text(loadError ?? "发生未知错误")
            }
            .confirmationDialog("清空与「\(clearTarget?.name ?? "")」的聊天记录？",
                                isPresented: Binding(get: { clearTarget != nil },
                                                     set: { if !$0 { clearTarget = nil } }),
                                titleVisibility: .visible) {
                Button("清空 \(clearTarget?.messageCount ?? 0) 条消息", role: .destructive) {
                    if let target = clearTarget { clear(target) }
                    clearTarget = nil
                }
                Button("取消", role: .cancel) { clearTarget = nil }
            } message: {
                Text("只清空这一个会话的聊天记录，**角色本身保留**（人设、记忆、世界书都不动）。不可恢复。")
            }
            .task { reload() }
            .onAppear { reload() }
            // 操作结果（清空/已读）用一条会自己消失的横幅 ——
            // 不弹窗打断，也不静默（"点了没反应"与"点了但没生效"看起来是一样的）。
            .overlay(alignment: .bottom) {
                if let notice {
                    Text(notice)
                        .font(.footnote)
                        .foregroundStyle(c.textPrimary)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 9)
                        .background(c.surface, in: Capsule())
                        .padding(.bottom, 24)
                        .transition(.opacity)
                        .id(notice)
                        .task {
                            try? await Task.sleep(nanoseconds: 3_000_000_000)
                            self.notice = nil
                        }
                }
            }
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

    /// 标为已读（把已读游标推到最新一条）。
    private func markRead(_ row: ConversationRepository.ConvRow) {
        guard let database = environment.database else { return }
        _ = try? ConversationRepository(database: database)
            .markReadThroughLatest(companionId: row.companionId)
        reload()
    }

    /// 清空这一个会话的聊天记录（**保留角色**）。
    ///
    /// ⚠️ 顺序由仓储保证：先删消息、再删 `conversation_summary` ——
    /// 否则记录清空了、未读标记还留在列表上。
    private func clear(_ row: ConversationRepository.ConvRow) {
        guard let database = environment.database else { return }
        do {
            let removed = try ConversationRepository(database: database)
                .clearConversation(companionId: row.companionId,
                                   messages: environment.messages)
            notice = "已清空 \(row.name) 的 \(removed) 条记录行（角色保留）"
            reload()
        } catch {
            notice = "清空失败，请稍后重试"
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

                    // 共 N 条：清空记录前用户能看到"会删掉多少"，
                    // 也让"这个会话到底聊过没有"一眼可判（0 条时不显示，不加噪音）。
                    if row.messageCount > 0 {
                        Text("共 \(row.messageCount) 条")
                            .font(.caption2)
                            .foregroundStyle(c.textTertiary)
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                            .layoutPriority(1)
                            .accessibilityLabel("共 \(row.messageCount) 条消息")
                    }
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
    @State private var showCardImport = false
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
                    // 一个「+」菜单放两种建角色的方式：新建（自己写人设）/
                    // 导入角色卡（PNG 或 JSON —— 引擎早就能解析，之前两端都没有入口）
                    Menu {
                        Button { showCreator = true } label: {
                            Label("新建角色", systemImage: "person.badge.plus")
                        }
                        Button { showCardImport = true } label: {
                            Label("导入角色卡", systemImage: "square.and.arrow.down")
                        }
                    } label: {
                        Label("添加", systemImage: "plus")
                    }
                }
            }
            .sheet(isPresented: $showCreator) {
                NavigationStack {
                    CompanionEditView(companion: nil, onSaved: reload)
                }
            }
            .sheet(isPresented: $showCardImport) {
                NavigationStack {
                    CharacterCardImportView(onImported: reload)
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
