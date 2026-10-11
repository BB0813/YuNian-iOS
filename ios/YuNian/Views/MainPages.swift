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
                        Text("到通讯录选择一位角色开始聊天")
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
                        .listRowBackground(Color.clear)
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .refreshable { reload() }
                }
            }
            .navigationTitle("通讯录")
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
