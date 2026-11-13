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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var rows: [ConversationRepository.ConvRow] = []
    @State private var loadError: String?
    @State private var query = ""
    /// 待清空记录的那个会话（危险操作要二次确认，并显示会删掉多少条）。
    @State private var clearTarget: ConversationRepository.ConvRow?
    @State private var notice: String?
    /// 首屏入场动画是否已触发（只跑一次，滚动回来不再重放）。
    @State private var appeared = false

    /// 置顶 / 免打扰的会话 id，存 `app_meta`（读写集中在 `ConversationFlagsStore`）。
    /// 置顶数组**最新的排最前** —— 列表的置顶区就按这个顺序排。
    @State private var pinnedIds: [Int64] = []
    @State private var mutedIds: [Int64] = []

    /// 首屏入场的延迟（错峰）。`reduceMotion` 时调用点会跳过动画。
    private func entryDelay(_ index: Int) -> Double {
        YNTheme.Motion.entryDelay(index)
    }

    var body: some View {
        let c = YNTheme.palette(scheme)
        let filtered = ConversationRepository.filter(rows, query: query)
        // 置顶区排最前（在搜索过滤之后排 —— 搜索结果里置顶的同样靠前）
        let ordered = sortedWithPinnedFirst(filtered, pinnedIds: pinnedIds)
        let unreadTotal = rows.filter(\.hasUnread).count

        return NavigationStack {
            ZStack {
                YNCanvas()

                if rows.isEmpty, loadError == nil {
                    // 空态给**出路**（不是"去别处找"那种死胡同文案）：
                    // 一条通向使用指南（三步配置），一条说明去哪选角色。
                    ScrollView {
                        YNEmptyState(
                            icon: "bubble.left.and.bubble.right.fill",
                            title: "还没有对话",
                            message: "去「通讯录」选一位角色就能开始。第一次用，先看使用指南里的三步配置。"
                        ) {
                            NavigationLink {
                                GuideView()
                            } label: {
                                Label("打开使用指南", systemImage: "book")
                                    .font(.subheadline.weight(.medium))
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(c.accent)
                        }
                    }
                } else {
                    List {
                        // ⚠️ 第 222 轮第二次修正（真机截图驱动）：
                        // **页头交回系统的 `.largeTitle`**，不再自绘。
                        //
                        // 第一版我自绘了 `YNPageHeader`（大标题 + 右侧计数 + 副标题），
                        // 真机上标题被**切掉第一个字**、右侧计数被切到屏幕外 ——
                        // 表现是"整行比屏幕宽、被居中后两侧裁掉"。
                        // 我按这个假设改过一轮（中间列 maxWidth + 去掉 fixedSize），
                        // **没有修好**。既然自绘几何在这台设备上不可靠，
                        // 就用系统的大标题：量级一样（34pt 粗体），几何由系统保证。
                        //
                        // 计数不再与标题同行 —— 放到工具栏尾部（见下面的 toolbar），
                        // 那里是系统管理的位置，不会参与内容宽度计算。
                        Section {
                            ForEach(Array(ordered.enumerated()), id: \.element.id) { index, row in
                                let isPinned = pinnedIds.contains(row.companionId)
                                let isMuted = mutedIds.contains(row.companionId)
                                NavigationLink {
                                    ChatView(companionId: row.companionId)
                                } label: {
                                    ConversationRow(row: row, isPinned: isPinned, isMuted: isMuted)
                                }
                                // 置顶 / 免打扰走长按菜单：低频的「整理」操作不占滑动手势
                                //（滑动留给清空记录 / 已读这些高频动作）。
                                .contextMenu {
                                    Button {
                                        togglePinned(row)
                                    } label: {
                                        Label(isPinned ? "取消置顶" : "置顶",
                                              systemImage: isPinned ? "pin.slash" : "pin.fill")
                                    }
                                    Button {
                                        toggleMuted(row)
                                    } label: {
                                        Label(isMuted ? "关闭免打扰" : "免打扰",
                                              systemImage: isMuted ? "bell" : "bell.slash")
                                    }
                                }
                                // 首屏逐个入场（只给第一组、只跑一次；减弱动态效果时不播）
                                .opacity(appeared ? 1 : 0)
                                .offset(y: appeared ? 0 : 6)
                                .animation(reduceMotion ? nil
                                           : YNTheme.Motion.quick.delay(entryDelay(index)),
                                           value: appeared)
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
                                .listRowInsets(EdgeInsets(top: YNTheme.Space.sm,
                                                          leading: YNTheme.Space.lg,
                                                          bottom: YNTheme.Space.sm,
                                                          trailing: YNTheme.Space.lg))

                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                    .scrollContentBackground(.hidden)
                    .refreshable { reload() }
                    // 入场动画触发点：整页出现后置 true（只跑一次）
                    .onAppear {
                        guard !appeared else { return }
                        withAnimation(reduceMotion ? nil : YNTheme.Motion.quick.delay(0.02)) {
                            appeared = true
                        }
                    }
                    // 搜索：名字或最后一条消息（纯函数过滤，有测试）
                    .searchable(text: $query, prompt: "搜索名字或消息")
                    .overlay {
                        if filtered.isEmpty, !query.isEmpty {
                            ContentUnavailableView.search(text: query)
                        }
                    }
                }
            }
            // 大标题交回系统（`.largeTitle` = 34pt 粗体，与设计要的量级一致），
            // 计数放工具栏尾部 —— 都不参与内容行宽计算。
            .navigationTitle("对话")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if !rows.isEmpty {
                        Text(unreadTotal > 0 ? "\(rows.count) 个 · \(unreadTotal) 未读"
                                             : "\(rows.count) 个")
                            .font(.footnote)
                            .foregroundStyle(c.textTertiary)
                            .monospacedDigit()
                    }
                }
            }
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

    /// `app_meta` 的读写口（置顶 / 免打扰挂在那张 KV 表上 —— companions 表与
    /// Android Room 共用 schema 且有闸门逐字比对，不能加列）。
    private var metaRepo: ApiConfigRepository? {
        if let apiConfigs = environment.apiConfigs { return apiConfigs }
        // 兜底：apiConfigs 与 database 在 boot() 里同时赋值，正常不会走到这里，
        // 但设置操作不该被启动时序卡死。
        return environment.database.map { ApiConfigRepository(database: $0) }
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
            // 置顶 / 免打扰跟消息数据一起刷：长按改完、从聊天页回来都要是最新状态。
            // 读失败在 helper 里已当空数组处理，不在这里报错 —— 偏好坏了不该连累列表。
            if let metaRepo {
                pinnedIds = ConversationFlagsStore.loadPinned(from: metaRepo)
                mutedIds = ConversationFlagsStore.loadMuted(from: metaRepo)
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

    /// 置顶 / 取消置顶。置顶 id 数组**最新的插最前**，列表的置顶区按它排。
    private func togglePinned(_ row: ConversationRepository.ConvRow) {
        guard let metaRepo else { return }
        var ids = pinnedIds
        let pinning = !ids.contains(row.companionId)
        do {
            if pinning {
                ids.insert(row.companionId, at: 0)
            } else {
                ids.removeAll { $0 == row.companionId }
            }
            try ConversationFlagsStore.setPinned(ids, in: metaRepo)
            pinnedIds = ids
            notice = pinning ? "已置顶「\(row.name)」" : "已取消置顶「\(row.name)」"
        } catch {
            notice = "置顶设置保存失败，请稍后重试"
        }
    }

    /// 免打扰 / 关闭免打扰。只改列表上的未读显示，不动通知设置（那是另一套）。
    private func toggleMuted(_ row: ConversationRepository.ConvRow) {
        guard let metaRepo else { return }
        var ids = mutedIds
        let muting = !ids.contains(row.companionId)
        do {
            if muting {
                ids.append(row.companionId)   // 免打扰不参与排序，顺序无所谓
            } else {
                ids.removeAll { $0 == row.companionId }
            }
            try ConversationFlagsStore.setMuted(ids, in: metaRepo)
            mutedIds = ids
            notice = muting ? "已对「\(row.name)」开启免打扰" : "已关闭「\(row.name)」的免打扰"
        } catch {
            notice = "免打扰设置保存失败，请稍后重试"
        }
    }

    /// 置顶排序：置顶的排最前（按存储顺序 = 最近置顶在前），其余保持原顺序。
    ///
    /// 不用 `sorted(by:)` —— Swift 的排序不保证稳定，两次 filter 再拼接
    /// 才能保住未置顶行的相对顺序。空列表 / 单条 / 全部置顶在这个实现下
    /// 都自然退化（compactMap 跳过、rest 可为空），不存在死循环路径；
    /// 已删角色的残留置顶 id 由 compactMap 顺手丢弃。
    private func sortedWithPinnedFirst(_ rows: [ConversationRepository.ConvRow],
                                       pinnedIds: [Int64]) -> [ConversationRepository.ConvRow] {
        let pinned = pinnedIds.compactMap { id in rows.first { $0.companionId == id } }
        let rest = rows.filter { !pinnedIds.contains($0.companionId) }
        return pinned + rest
    }
}

private struct ConversationRow: View {
    let row: ConversationRepository.ConvRow
    /// 置顶 / 免打扰由列表层算好传进来（行内不查库 —— 渲染保持纯函数）。
    let isPinned: Bool
    let isMuted: Bool

    @Environment(\.colorScheme) private var scheme

    /// 这一行要不要画未读标记。免打扰**不改数据只改显示**：
    /// 未读本身还在（角标、"已读"滑动都不受影响），只是这一行不提示。
    private var showsUnread: Bool { row.hasUnread && !isMuted }

    var body: some View {
        let c = YNTheme.palette(scheme)
        HStack(spacing: YNTheme.Space.md) {
            YNAvatarHalo(avatarURL: row.avatarUrl, name: row.name,
                         size: 52, unread: showsUnread)

            VStack(alignment: .leading, spacing: 3) {
                // 未读：**加粗 + 暖色圆点**（不只靠颜色 —— T6）。免打扰的行不画
                HStack(spacing: 6) {
                    if showsUnread {
                        Circle()
                            .fill(c.warm)
                            .frame(width: 7, height: 7)
                            .accessibilityHidden(true)
                    }
                    Text(row.name)
                        .font(.body.weight(showsUnread ? .semibold : .regular))
                        .foregroundStyle(c.textPrimary)
                        .lineLimit(1)
                }

                Text(row.lastMessageFromUser ? "我：\(row.preview)" : row.preview)
                    .font(.subheadline)
                    .foregroundStyle(showsUnread ? c.textSecondary : c.textTertiary)
                    .lineLimit(1)
            }
            // ⚠️ 第 222 轮（真机截图驱动）：
            // 左列必须**主动吸收剩余宽度**。之前这里用的是 `Spacer(minLength:)`，
            // 而右列又写了 `.fixedSize(horizontal: true)` —— 两者叠加会让整行
            // 需求宽度**超过屏幕**，SwiftUI 于是把内容居中并**两侧一起裁掉**：
            // 标题被切掉第一个字（「对话」→「舌」）、右列时间与条数也被切。
            // 正确形状：中间可伸缩列 maxWidth: .infinity，右列靠 layoutPriority 保尺寸。
            .frame(maxWidth: .infinity, alignment: .leading)

            // 右列：时间 + 条数（**竖排**）。
            //
            // ⚠️ 第 220 轮把这个数字**放回来了，但换了位置**：苏晚瑄反馈过
            // 「条数跟在预览文字后面很怪」—— 根因是它和预览挤同一行、被挤成竖排。
            // 借 Aru 的做法放到右侧固定列（那里宽度不受预览影响）。
            VStack(alignment: .trailing, spacing: 4) {
                // 置顶 / 免打扰的静态标记（操作在长按菜单，图标只负责"能看出来"；
                // 放右列而不挤名字区 —— 那是第 222 轮踩过宽度坑的地方）
                if isPinned {
                    Image(systemName: "pin.fill")
                        .font(.caption2)
                        .foregroundStyle(c.textTertiary)
                        .accessibilityHidden(true)
                }
                if isMuted {
                    Image(systemName: "bell.slash")
                        .font(.caption2)
                        .foregroundStyle(c.textTertiary)
                        .accessibilityHidden(true)
                }
                if let timestamp = row.lastMessageAt {
                    Text(Self.timeString(timestamp))
                        .font(.caption)
                        .foregroundStyle(c.textTertiary)
                        .monospacedDigit()
                }
                if row.messageCount > 0 {
                    Text("\(row.messageCount) 条")
                        .font(.caption2)
                        .foregroundStyle(c.textTertiary)
                        .monospacedDigit()
                }
            }
            // 优先满足右列（它很短且不该被压缩），中间列再吃掉剩下的
            .layoutPriority(1)
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Self.accessibilityLabel(row, isPinned: isPinned, isMuted: isMuted))
    }

    /// 读屏文案：把"置顶/免打扰/未读/条数"说清楚（视觉上它们是颜色与位置，读屏读不到）。
    private static func accessibilityLabel(_ row: ConversationRepository.ConvRow,
                                           isPinned: Bool, isMuted: Bool) -> String {
        var parts = [row.name]
        if isPinned { parts.append("已置顶") }
        if isMuted { parts.append("免打扰") }
        if row.hasUnread && !isMuted { parts.append("有未读") }
        parts.append(row.lastMessageFromUser ? "我：\(row.preview)" : row.preview)
        if row.messageCount > 0 { parts.append("共 \(row.messageCount) 条") }
        return parts.joined(separator: "，")
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

/// 会话置顶 / 免打扰的存取 —— 集中键名与解析，UI 层不碰原始字符串。
///
/// ## 为什么存 `app_meta` 而不是 companions 表
/// 那张表与 Android Room **共用 schema 且有闸门逐字比对**，不能加列；
/// 这类纯界面偏好只能挂 KV。先例：`tool_gate.*` 也是 JSON 数组进 `app_meta`。
///
/// ## 值的格式
/// JSON 数组字符串，元素为 conversationId（iOS 侧 == companionId，
/// `ConvRow.companionId`）：
///   · `conversation.pinned.ids` —— **有序**，最新的排最前，列表的置顶区按它排；
///   · `conversation.muted.ids` —— 顺序无意义，只用来做 contains 判断。
/// 空 = 没有任何置顶/免打扰，此时**直接删键**（`setMetaValue(nil)` 的语义），
/// 不写 `"[]"` —— 库里不留"配过又全删"的痕迹。键不存在 / 值解析失败
/// 一律当空数组：偏好数据坏了不该崩列表。
private enum ConversationFlagsStore {

    static let pinnedKey = "conversation.pinned.ids"
    static let mutedKey = "conversation.muted.ids"

    // MARK: - 读

    /// 置顶 id，**最近置顶的排最前**（写入时维护这个顺序）。
    static func loadPinned(from repo: ApiConfigRepository) -> [Int64] {
        load(repo, key: pinnedKey)
    }

    /// 免打扰 id（顺序无意义）。
    static func loadMuted(from repo: ApiConfigRepository) -> [Int64] {
        load(repo, key: mutedKey)
    }

    // MARK: - 写

    static func setPinned(_ ids: [Int64], in repo: ApiConfigRepository) throws {
        try save(ids, key: pinnedKey, repo: repo)
    }

    static func setMuted(_ ids: [Int64], in repo: ApiConfigRepository) throws {
        try save(ids, key: mutedKey, repo: repo)
    }

    // MARK: - 实现

    private static func load(_ repo: ApiConfigRepository, key: String) -> [Int64] {
        guard let raw = try? repo.metaValue(forKey: key),
              let data = raw.data(using: .utf8) else { return [] }
        return (try? JSONDecoder().decode([Int64].self, from: data)) ?? []
    }

    private static func save(_ ids: [Int64], key: String, repo: ApiConfigRepository) throws {
        guard !ids.isEmpty else {
            // 空集合 = 回到默认态 → 删键（`setMetaValue(nil)` 就是删）
            try repo.setMetaValue(nil, forKey: key)
            return
        }
        let data = try JSONEncoder().encode(ids)
        try repo.setMetaValue(String(data: data, encoding: .utf8), forKey: key)
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
    /// 角色页（第 219 轮）：左滑「详情」用 Button 触发（见下方注释为什么不放 NavigationLink）。
    @State private var hubCompanionId: Int64?
    @State private var showHub = false
    /// 待删除的角色 + 是否连聊天记录一起删（两选项，见删除对话框）。
    @State private var deleteTarget: CompanionRepository.Companion?

    var body: some View {
        let c = YNTheme.palette(scheme)
        NavigationStack {
            ZStack {
                YNCanvas()

                if companions.isEmpty, loadError == nil {
                    // 空态给**出路**：直接新建（这是这一屏唯一要做的事）
                    ScrollView {
                        YNEmptyState(
                            icon: "person.2.fill",
                            title: "还没有联系人",
                            message: "创建一位角色，或导入一张别人做好的角色卡。"
                        ) {
                            VStack(spacing: YNTheme.Space.sm) {
                                Button {
                                    showCreator = true
                                } label: {
                                    Label("新建角色", systemImage: "person.badge.plus")
                                        .font(.subheadline.weight(.medium))
                                }
                                .buttonStyle(.borderedProminent)
                                .tint(c.accent)

                                Button {
                                    showCardImport = true
                                } label: {
                                    Label("导入角色卡", systemImage: "square.and.arrow.down")
                                        .font(.subheadline.weight(.medium))
                                }
                                .buttonStyle(.bordered)
                                .tint(c.accent)
                            }
                        }
                    }
                } else {
                    List {
                        // 页头同样交回系统大标题（第 222 轮第二次修正，原因见会话列表处）
                        Section {
                            ForEach(companions) { companion in
                                NavigationLink {
                                    ChatView(companionId: companion.id)
                                } label: {
                                    HStack(spacing: YNTheme.Space.md) {
                                        // 头像（第 222 轮第二次修正：**列表行里不再用自绘光晕**）。
                                        // 自绘光晕是 1.62× 的溢出绘制，是"内容被裁"的重点嫌疑；
                                        // 光晕保留在角色页/个人卡这种独立卡片上（那里有空间、也不会挤压别的内容）。
                                        CompanionAvatar(avatarURL: companion.avatarUrl,
                                                        name: companion.name, size: 52)
                                        VStack(alignment: .leading, spacing: 3) {
                                            Text(companion.name)
                                                .font(.body.weight(.medium))
                                                .foregroundStyle(c.textPrimary)
                                                .lineLimit(1)
                                            // ⚠️ 第 222 轮（真机截图驱动）：副标题从**原样铺人设**
                                            // 改成摘要。人设常写成整段提示词（截图里是
                                            // "你是小鱼，一个温柔体贴、有点粘人的AI女友。你喜欢分…"）
                                            // —— 列表里像一堵墙，还把名字挤没了。
                                            // 摘要只取**用户真正填过的字段**，且与角色页同用一个函数。
                                            Text(CompanionHubStats.personaSummary(for: companion))
                                                .font(.subheadline)
                                                .foregroundStyle(c.textSecondary)
                                                .lineLimit(1)
                                        }
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    }
                                    .padding(.vertical, 4)
                                    .contentShape(Rectangle())
                                }
                        // 第 212 轮：编辑与删除。第 219 轮加「详情」（角色页）——
                        // 点行仍然是**去聊天**（主操作不变），改人设/看状态走左滑。
                        //
                        // ⚠️ 这里必须是 Button + navigationDestination，**不能**直接在
                        // swipeActions 里放 NavigationLink —— 滑动菜单里它的转场行为不可靠
                        //（菜单会先收起，push 常被吞掉）。用 isPresented 形式还能避开
                        // `Companion` 是否 Hashable 的问题。
                        .swipeActions(edge: .leading) {
                            Button {
                                editTarget = companion
                            } label: {
                                Label("编辑", systemImage: "pencil")
                            }
                            .tint(c.accent)

                            Button {
                                hubCompanionId = companion.id
                                showHub = true
                            } label: {
                                Label("详情", systemImage: "info.circle")
                            }
                            .tint(c.textSecondary)
                        }
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) {
                                deleteTarget = companion
                            } label: {
                                Label("删除", systemImage: "trash")
                            }
                        }
                        .listRowBackground(Color.clear)
                                .listRowInsets(EdgeInsets(top: YNTheme.Space.sm,
                                                          leading: YNTheme.Space.lg,
                                                          bottom: YNTheme.Space.sm,
                                                          trailing: YNTheme.Space.lg))

                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                    .scrollContentBackground(.hidden)
                    .refreshable { reload() }
                }
            }
            // 大标题交回系统（同会话列表）；人数放工具栏尾部
            .navigationTitle("通讯录")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if !companions.isEmpty {
                        Text("\(companions.count) 位")
                            .font(.footnote)
                            .foregroundStyle(c.textTertiary)
                            .monospacedDigit()
                    }
                }
            }
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
            // 角色页（第 219 轮）：由左滑「详情」触发（Button → 这里 push）。
            .navigationDestination(isPresented: $showHub) {
                if let hubCompanionId {
                    CompanionHubView(companionId: hubCompanionId)
                }
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
