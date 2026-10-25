import SwiftUI
import UIKit

/// iOS 原生单聊页（V1 文本闭环）。
///
/// 规格：`ios/CHAT-SPEC.md`
/// Apple 约束：`ios/APPLE-DESIGN-CONSTRAINTS.md`
struct ChatView: View {
    let companionId: Int64

    @EnvironmentObject private var environment: AppEnvironment

    var body: some View {
        ChatViewContent(companionId: companionId, environment: environment)
    }
}

/// 单独一层承载 `StateObject`，防止 `AppEnvironment` 发布更新时重建会话状态。
private struct ChatViewContent: View {
    @StateObject private var model: ChatViewModel

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    @State private var isNearBottom = true
    @State private var unseenCount = 0
    /// 要展示收据的那条消息（nil = 不弹）。用 item 形式，避免"先置 true 再取消息"
    /// 那种两步状态（中间会有一帧拿到 nil）。
    @State private var receiptTarget: ChatSession.Message?
    /// 待删除的那一条（第 221 轮）：先确认再删。
    @State private var deleteTarget: ChatSession.Message?
    /// 角色页（第 219 轮）：点顶栏标题打开，进人设/记忆/世界书/音色/会话。
    @State private var showCompanionHub = false
    /// 历史是否已完成首次灌入。用于把「首次加载」与「新消息到达」区分开 ——
    /// 二者都会让消息数增加，但只有后者才应该计数/滚动。
    @State private var didInitialScroll = false
    @State private var reasoningExpanded = false
    /// 已落地思考过程的展开状态（按消息 id 记 ——
    /// 用一个 Bool 会让"展开一条"把所有条都撑开）。
    @State private var expandedReasoning: Set<UUID> = []

    init(companionId: Int64, environment: AppEnvironment) {
        _model = StateObject(wrappedValue: ChatViewModel(
            companionId: companionId,
            environment: environment
        ))
    }

    var body: some View {
        let c = YNTheme.palette(scheme)

        GeometryReader { viewport in
            ScrollViewReader { reader in
                messageScroll(colors: c, viewportHeight: viewport.size.height)
                    .onChange(of: model.displayMessages.count) { old, new in
                        handleMessageCountChange(old: old, new: new, reader: reader)
                    }
                    .onChange(of: model.displayStreaming) { _, _ in
                        if isNearBottom { scrollToBottom(reader) }
                    }
                    .onChange(of: model.displayPhase) { _, phase in
                        if phase == .typing { scrollToBottom(reader) }
                    }
                    .overlay(alignment: .bottom) {
                        if unseenCount > 0 && !isNearBottom {
                            newMessagesButton(reader: reader, colors: c)
                                .padding(.bottom, 10)
                        }
                    }
            }
        }
        .modifier(ChatComposerInset(
            model: model,
            colors: c,
            reduceTransparency: reduceTransparency
        ))
        .navigationBarTitleDisplayMode(.inline)
        // 进到聊天页就**收起底部标签栏**：已经在这一层了，
        // 底栏继续戳在那儿既是多余的视觉噪音，也白占一截纵向空间
        // （聊天页最缺的就是纵向空间）。
        // `toolbar(_:for:)` 是 iOS 16+ 的系统做法，返回上一层会自动恢复。
        .toolbar(.hidden, for: .tabBar)
        .toolbar { toolbarContent(colors: c) }
        // 「本轮收据」（P1）：从消息菜单打开，展示**那一轮**的装箱清单。
        .sheet(item: $receiptTarget) { message in
            NavigationStack {
                TurnReceiptView(receipt: model.receipt(for: message))
            }
        }
        // 角色页（第 219 轮）：点顶栏标题打开。
        // ⚠️ 用 `model.companionId` 而不是 `companionId`：这一层是 `ChatViewContent`，
        // 它不存 `companionId`（那个属性在 `ChatView` 上），companionId 由 ViewModel 持有。
        .sheet(isPresented: $showCompanionHub) {
            CompanionHubView(companionId: model.companionId, embeddedInSheet: true)
        }
        // 删除单条消息（第 221 轮）：破坏性且不可逆 → 先确认。
        // 直接把要删的那句话开头放进标题里，避免删错（长按菜单很容易点错行）。
        .confirmationDialog("删除这一条消息？",
                            isPresented: Binding(get: { deleteTarget != nil },
                                                 set: { if !$0 { deleteTarget = nil } }),
                            titleVisibility: .visible) {
            Button("删除", role: .destructive) {
                if let target = deleteTarget { _ = model.deleteMessage(target) }
                deleteTarget = nil
            }
            Button("取消", role: .cancel) { deleteTarget = nil }
        } message: {
            Text(deletePreview)
        }
        // 一行会自己消失的提示（删除结果 / 被中断的角色）。
        .overlay(alignment: .top) {
            if let notice = model.notice ?? model.interruptedCompanionName.map({ "\($0) 的回复已中断（同时只支持一位角色生成）" }) {
                Text(notice)
                    .font(.footnote)
                    .foregroundStyle(c.textPrimary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(c.surface, in: Capsule())
                    .ynElevation(YNTheme.Elevation.card)
                    .padding(.top, YNTheme.Space.sm)
                    .transition(.opacity)
                    .id(notice)
                    .task {
                        try? await Task.sleep(nanoseconds: 3_000_000_000)
                        model.notice = nil
                    }
            }
        }
        .task { await model.appear() }
        .onDisappear { model.disappear() }
    }

    /// 确认框里回显要删的那句话（截断，够辨认就行）。
    private var deletePreview: String {
        guard let target = deleteTarget else { return "" }
        let text = target.text.trimmingCharacters(in: .whitespacesAndNewlines)
        let who = target.role == .user ? "你" : (model.companion?.name ?? "对方")
        guard !text.isEmpty else { return "这条是\(who)发的（内容为空）。删除后不可恢复。" }
        let head = text.count > 24 ? String(text.prefix(24)) + "…" : text
        return "\(who)：\(head)\n删除后不可恢复。它会同时从发给模型的历史里移除。"
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private func toolbarContent(colors c: YNTheme.Palette) -> some ToolbarContent {
        ToolbarItem(placement: .principal) {
            // 第 219 轮：标题变**可点**，进"角色页"（人设/记忆/世界书/音色/会话）。
            // 借鉴 Aru：聊天页顶栏那个头像点下去就是这位协作者的设置中枢。
            // 用 Button 而不是 NavigationLink：工具栏里的 NavigationLink 行为不稳，
            // 这里用受控 sheet，弹层自带导航栈（`embeddedInSheet`）。
            Button {
                showCompanionHub = true
            } label: {
                HStack(spacing: YNTheme.Space.sm) {
                    CompanionAvatar(
                        avatarURL: model.companion?.avatarUrl,
                        name: model.companion?.name ?? "对话",
                        size: 34
                    )

                    VStack(alignment: .leading, spacing: 1) {
                        Text(model.companion?.name ?? "对话")
                            .font(.headline)
                            .foregroundStyle(c.textPrimary)
                            .lineLimit(1)

                        if let subtitle = model.subtitle {
                            Text(subtitle)
                                .font(.caption)
                                .foregroundStyle(c.textSecondary)
                                .transition(.opacity)
                        }
                    }

                    Image(systemName: "chevron.right")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(c.textTertiary)
                }
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(toolbarAccessibilityLabel + "，打开角色页")
        }

        #if DEBUG
        // 仅 DEBUG：无 API Key 时也能验收完整视觉状态（AI 气泡 / typing /
        // 流式 / 思考过程 / 停止按钮 / 消息分组）。
        // release 构建里这段不存在，因此不会变成对用户的伪能力。
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                ForEach(ChatViewModel.PreviewMode.allCases) { mode in
                    Button(mode.rawValue) { model.startPreview(mode) }
                }
                if model.previewMode != nil {
                    Button("退出预览", role: .destructive) { model.stopPreview() }
                }
            } label: {
                Image(systemName: model.previewMode == nil ? "eye" : "eye.fill")
            }
            .accessibilityLabel("预览界面（示例数据）")
        }
        #endif
    }

    private var toolbarAccessibilityLabel: String {
        let name = model.companion?.name ?? "对话"
        if let subtitle = model.subtitle { return "\(name)，\(subtitle)" }
        return name
    }

    // MARK: - Messages

    /// 新对话的开场状态（第 220 轮）。
    ///
    /// ## 为什么从"刻意留白"改掉
    /// 旧实现是 `Color.clear.frame(minHeight: ...)`，注释写着"不放大插画或 CTA，
    /// 让对话自然开始"。但实测观感反馈正是**「没有使用欲望」** ——
    /// 而第一屏（刚进聊天、什么都没有）就是决定使用欲望的地方：
    /// 一片空白 + 一个输入框，用户不知道该说什么。
    ///
    /// ## 现在的形状
    /// 头像光晕（这是"人"）+ 一句邀请 + **三个可点的开场语**。
    /// 开场语点了**直接发出去**（不是填进输入框让用户再点一次发送）。
    ///
    /// ⚠️ 开场语是**通用**的，不带任何角色专属设定：
    /// 我们不知道这个角色的世界观，编造"符合人设"的开场语只会出戏。
    @ViewBuilder
    private func openingState(colors c: YNTheme.Palette) -> some View {
        VStack(spacing: YNTheme.Space.lg) {
            YNAvatarHalo(avatarURL: model.companion?.avatarUrl,
                         name: model.companion?.name ?? "对话",
                         size: 84)

            VStack(spacing: YNTheme.Space.xs) {
                Text(model.companion?.name ?? "对方")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(c.textPrimary)
                Text("还没聊过。说点什么开始吧 —— 或者从下面挑一句。")
                    .font(.subheadline)
                    .foregroundStyle(c.textSecondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 300)
            }

            VStack(spacing: YNTheme.Space.sm) {
                ForEach(Self.openingPrompts, id: \.self) { prompt in
                    Button {
                        // 直接发送：点了还要再点一次发送键，等于把两步做成一步半
                        model.draft = prompt
                        model.sendText()
                    } label: {
                        Text(prompt)
                            .font(.subheadline)
                            .foregroundStyle(c.textPrimary)
                            .padding(.horizontal, YNTheme.Space.lg)
                            .padding(.vertical, YNTheme.Space.sm)
                            .background(c.surface, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.top, YNTheme.Space.xs)
        }
        .padding(.horizontal, YNTheme.Space.xl)
        .frame(maxWidth: .infinity)
    }

    /// 开场语：**通用**、口语、不预设角色设定（见 `openingState` 的说明）。
    private static let openingPrompts = [
        "在吗？",
        "今天过得怎么样？",
        "给我讲件你今天遇到的小事",
    ]

    private func messageScroll(colors c: YNTheme.Palette, viewportHeight: CGFloat) -> some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                if model.displayPhase == .booting && model.displayMessages.isEmpty {
                    loadingRows(colors: c)
                } else if model.displayMessages.isEmpty && model.displayStreaming.isEmpty {
                    // 第 220 轮：从"一片空白"换成**开场状态**。
                    //
                    // 旧实现刻意留白（注释说 Android 没有欢迎空态）——
                    // 但实测观感反馈正是「没有使用欲望」，而第一屏就是它。
                    // 现在给：角色头像光晕 + 一句人话 + 三个可点的开场语
                    //（点了直接发出去，用户不用想第一句说什么）。
                    openingState(colors: c)
                        .padding(.top, max(viewportHeight * 0.18, 80))
                }

                ForEach(Array(model.displayMessages.enumerated()), id: \.element.id) { index, message in
                    let previous = index > 0 ? model.displayMessages[index - 1] : nil
                    let next = index + 1 < model.displayMessages.count ? model.displayMessages[index + 1] : nil
                    let divider = showsDivider(previous: previous, current: message)

                    if divider {
                        timeDivider(message.timestamp, colors: c)
                    }

                    // 已落地的思考过程：回复完成后仍可展开回看（Android 语义）
                    if let reasoning = message.reasoning, !reasoning.isEmpty {
                        persistedReasoning(reasoning, messageID: message.id, colors: c)
                            .padding(.top, YNTheme.Space.md)
                    }

                    // ⚠️ 第 205 轮：**正文为空但有思考过程**的消息不画气泡。
                    //
                    // 这种情况真实存在：思考型模型把 max_tokens 预算全花在推理上
                    // （Rust 会以 `finish_reason=length` 明确报 error）。此前那种回合
                    // 连思考过程都不留；现在会留一条"只有思考过程"的消息，
                    // 而它的正文是空串 —— 画出来就是一个空气泡。
                    // 上面那段已经把思考过程显示了，所以这里直接跳过气泡。
                    if !(message.text.isEmpty && message.reasoning?.isEmpty == false) {
                        MessageBubble(
                            message: message,
                            companionName: model.companion?.name ?? "对方",
                            colors: c,
                            // 第 220 轮：助手一组的第一条显示**头像 + 名字**（借 Aru 的做法）。
                            // 之前整屏只有气泡，没有任何"说话人"的视觉身份 ——
                            // 这是聊天页观感单薄的主要原因之一。
                            showsIdentity: previous?.role != .assistant && message.role == .assistant,
                            isFirstOfGroup: previous?.role != message.role,
                            stickerImage: model.stickerImages[message.id],
                            // 诊断必须是**完备**的：有图 / 三种失败 / 未解析，五选一。
                            // 真机上曾出现"气泡在、图不在、且一行原因都没有" ——
                            // 那说明解析根本没跑到这条消息，而当时我的诊断
                            // 只有三种"解析过但失败"，漏掉了这一种。
                            stickerFailure: model.stickerImages[message.id] == nil
                                ? (model.stickerFailures[message.id] ?? "未解析")
                                : nil,
                            // 同角色连续消息收紧间距；只有一组的**最后一条**才带小尾巴，
                            // 否则连续气泡会各自带尾，像一串互不相干的方块。
                            isLastOfGroup: next?.role != message.role,
                            onSpeak: { target in model.speak(target) },
                            // 「本轮收据」：只有助手消息、且那一轮真的留了 turnId
                            // 才有得看（`nil` = 菜单里不出现，而不是弹一个空页面）。
                            onReceipt: { target in receiptTarget = target },
                            // 「删除这一条」（第 221 轮）：只有已落库的消息才给入口。
                            // 先弹确认再删 —— 这是聊天页里唯一不可逆的操作。
                            onDelete: model.canDelete(message)
                                ? { target in deleteTarget = target } : nil
                        )
                        .padding(.top, divider ? YNTheme.Space.xs : groupSpacing(index: index, previous: previous, current: message))
                        .id(message.id)
                    }

                    // 朗读状态只挂在该条消息下方（合成中 / 朗读中 / 失败原因）。
                    // 与表情失败原因同一条原则：拿不到设备日志时，界面就是取证面。
                    if let tts = model.ttsStates[message.id] {
                        ttsNotice(tts, colors: c)
                            .padding(.top, YNTheme.Space.xs)
                    }
                }

                if !model.displayReasoning.isEmpty {
                    // ⚠️ 这些"非消息行"原先没有任何间距：为做消息分组把
                    // LazyVStack 改成 spacing 0 之后，只有 ForEach 里的消息
                    // 自己带上边距，思考过程/typing/流式/错误条被漏掉了，
                    // 于是"思考过程"和回复正文几乎贴在一起（真机截图确认）。
                    reasoningItem(colors: c)
                        .padding(.top, YNTheme.Space.md)
                        .id("reasoning")
                }

                if model.displayPhase == .typing {
                    typingItem(colors: c)
                        .padding(.top, YNTheme.Space.md)
                        .id("typing")
                }

                if !model.displayStreaming.isEmpty {
                    streamingBubble(colors: c)
                        .padding(.top, YNTheme.Space.md)
                        .id("streaming")
                }

                if let message = model.errorMessage {
                    errorNotice(message, colors: c)
                        .padding(.top, YNTheme.Space.md)
                        .id("error")
                }

                Color.clear
                    .frame(height: 1)
                    .id("bottom")
                    .background {
                        GeometryReader { proxy in
                            Color.clear.preference(
                                key: ChatBottomPositionKey.self,
                                value: proxy.frame(in: .named("chat-scroll")).maxY
                            )
                        }
                    }
            }
            .padding(.horizontal, YNTheme.Space.lg)
            .padding(.vertical, YNTheme.Space.md)
        }
        .coordinateSpace(name: "chat-scroll")
        // 原生「聊天式初始锚点」：首次布局即停在底部（iOS 17+；16 上由
        // `.onChange(of: displayMessages.count)` 的 scrollToBottom 兜底，
        // 见 YuNian/Support/Compatibility.swift）。
        //
        // 不能靠手写 scrollTo 解决：历史是**一次性灌入**的（0 → N），
        // 那一刻测量到的「是否在底部」必然是 false，于是既不滚动、
        // 还会把 N 条历史误记成「N 条新消息」。
        .ynDefaultScrollAnchorBottom()
        .scrollDismissesKeyboard(.interactively)
        .background(Color.clear)
        .onTapGesture { dismissKeyboard() }
        .onPreferenceChange(ChatBottomPositionKey.self) { bottomY in
            let wasNearBottom = isNearBottom
            isNearBottom = bottomY <= viewportHeight + 100
            if isNearBottom && !wasNearBottom { unseenCount = 0 }
        }
        .accessibilityLabel("与\(model.companion?.name ?? "对方")的聊天记录")
    }

    /// 是否在这一条之前插入时间分隔线。
    ///
    /// 依据 Android 规格 §5：`TimeDivider（UI 派生）| 相邻可见消息间隔 >=5 分钟时插入`。
    /// 首条消息之前始终显示（与 Apple Messages 一致）。
    /// 注意这与**分组间距**是两件事：时间分隔线管"跨时段"，分组管"同一段里换人"。
    private func showsDivider(previous: ChatSession.Message?, current: ChatSession.Message) -> Bool {
        guard let previous else { return true }
        return current.timestamp.timeIntervalSince(previous.timestamp) >= 5 * 60
    }

    private func timeDivider(_ date: Date, colors c: YNTheme.Palette) -> some View {
        Text(Self.dividerFormatter.string(from: date))
            .font(.caption2)
            .foregroundStyle(c.textTertiary)
            .frame(maxWidth: .infinity)
            .padding(.top, YNTheme.Space.md)
            .accessibilityLabel("时间：\(Self.dividerFormatter.string(from: date))")
    }

    /// `doesRelativeDateFormatting` 让今天显示「今天 14:30」、昨天显示「昨天 …」，
    /// 更早显示日期 —— 与系统邮件/信息的时间文案一致。
    private static let dividerFormatter: DateFormatter = {
        let f = DateFormatter()
        f.doesRelativeDateFormatting = true
        f.dateStyle = .medium
        f.timeStyle = .short
        return f
    }()

    /// 一屏内 LazyVStack 用 0 间距，改由每条自己带上边距 ——
    /// 这样才能按「是否同一组」给出不同的呼吸感。
    private func groupSpacing(
        index: Int,
        previous: ChatSession.Message?,
        current: ChatSession.Message
    ) -> CGFloat {
        guard let previous else { return 0 }
        if previous.role == current.role { return 3 }   // 同组：紧凑
        return 12                                        // 换人：明显断开
    }

    /// 内联错误提示。
    ///
    /// 刻意**不用 modal alert**：iMessage 的发送失败也是内联的（"Not Delivered"），
    /// 弹窗会打断整个对话，而这条错误往往只是一句「还没配 API」。
    /// 设计规范明确把「首选用模态」列为反模式，要求先考虑内联方案。
    ///
    /// 因此这里：不遮内容、不抢焦点、可关闭、位置就在对话末尾
    /// —— 用户看到"卡在哪一步"，而不是被拦在一个对话框前。
    /// 朗读状态提示（合成中 / 朗读中 / 失败原因）。
    ///
    /// 贴在对应气泡下方、靠助手一侧；与表情的失败原因同样是"界面自报原因"，
    /// 因为拿不到设备日志时这是唯一的取证面。
    private func ttsNotice(_ state: ChatViewModel.TtsState, colors c: YNTheme.Palette) -> some View {
        HStack(spacing: 6) {
            Image(systemName: state.symbol)
                .accessibilityHidden(true)
            Text(state.label)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .font(.caption2)
        .foregroundStyle(state.isFailure ? c.warning : c.textSecondary)
        .padding(.leading, YNTheme.Space.lg)
        .accessibilityElement(children: .combine)
    }

    private func errorNotice(_ text: String, colors c: YNTheme.Palette) -> some View {
        HStack(alignment: .top, spacing: YNTheme.Space.sm) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.footnote)
                .foregroundStyle(c.warning)
                .accessibilityHidden(true)

            Text(text)
                .font(.footnote)
                .foregroundStyle(c.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)

            Button {
                model.clearError()
            } label: {
                Image(systemName: "xmark")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(c.textSecondary)
                    .frame(width: 44, height: 44)   // 命中区下限，见约束文档 C4
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("关闭提示")
        }
        .padding(.leading, YNTheme.Space.md)
        .padding(.vertical, YNTheme.Space.xs)
        .background(c.surfaceElevated, in: RoundedRectangle(cornerRadius: YNTheme.Radius.notice, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("提示：\(text)")
    }

    private func loadingRows(colors c: YNTheme.Palette) -> some View {
        VStack(spacing: YNTheme.Space.sm) {
            ForEach(0..<3, id: \.self) { index in
                HStack {
                    if index == 1 { Spacer(minLength: 72) }
                    RoundedRectangle(cornerRadius: YNTheme.Radius.bubbleImage, style: .continuous)
                        .fill(c.surfaceElevated)
                        .frame(width: index == 0 ? 210 : 150, height: index == 2 ? 64 : 48)
                    if index != 1 { Spacer(minLength: 72) }
                }
            }
        }
        .redacted(reason: .placeholder)
        .accessibilityHidden(true)
    }

    private func typingItem(colors c: YNTheme.Palette) -> some View {
        HStack {
            HStack(spacing: YNTheme.Space.sm) {
                ProgressView()
                    .controlSize(.small)
                Text("正在输入…")
                    .font(.subheadline)
            }
            .foregroundStyle(c.textSecondary)
            .padding(.horizontal, YNTheme.Space.md)
            .padding(.vertical, 10)
            .background(c.surface, in: RoundedRectangle(cornerRadius: YNTheme.Radius.container, style: .continuous))

            Spacer(minLength: 72)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(model.companion?.name ?? "对方")正在输入")
    }

    private func streamingBubble(colors c: YNTheme.Palette) -> some View {
        HStack(alignment: .bottom) {
            Text(model.displayStreaming)
                .font(.body)
                .foregroundStyle(c.textPrimary)
                .textSelection(.enabled)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(c.surface, in: assistantBubbleShape)
                .accessibilityLabel("\(model.companion?.name ?? "对方")：\(model.displayStreaming)")

            Spacer(minLength: 72)
        }
    }

    /// 思考过程（过程态）。
    ///
    /// ## 层级原则：过程必须比内容**安静**
    /// 上一版用 `surfaceElevated` —— 而它比助手气泡的 `surface` **更亮**，
    /// 于是"过程"成了整屏最响的元素（真机截图确认）。层级是反的。
    ///
    /// 现在：底色改用 `surface` 的 **40% 半透明**（向画布方向退半步，
    /// 比任何气泡都弱），字号降到 `.caption`，图标与箭头统一 tertiary。
    /// 目标是让它读起来像**附注**，而不是一条消息。
    private func reasoningItem(colors c: YNTheme.Palette) -> some View {
        DisclosureGroup(isExpanded: $reasoningExpanded) {
            Text(model.displayReasoning)
                .font(.caption)
                .foregroundStyle(c.textTertiary)
                .textSelection(.enabled)
                .padding(.top, YNTheme.Space.sm)
        } label: {
            Label("思考过程", systemImage: "brain")
                .font(.caption.weight(.medium))
                .foregroundStyle(c.textTertiary)
        }
        .tint(c.textTertiary)
        .padding(.horizontal, YNTheme.Space.md)
        .padding(.vertical, YNTheme.Space.sm)
        .background(
            RoundedRectangle(cornerRadius: YNTheme.Radius.notice, style: .continuous)
                .fill(c.surface.opacity(0.40))
        )
        .accessibilityLabel(reasoningExpanded ? "收起思考过程" : "展开思考过程")
    }

    /// 已落地的思考过程。
    ///
    /// Android 规格 §5：`REASONING` 是**与该轮助手消息关联的正式消息**
    /// （含 duration），只有负 ID 那条是临时流态 —— 即**持久保留**。
    /// 因此这里不再"回复一完就消失"，而是跟着那轮消息一起回看。
    private func persistedReasoning(_ text: String, messageID: UUID, colors c: YNTheme.Palette) -> some View {
        let isExpanded = expandedReasoning.contains(messageID)
        return DisclosureGroup(
            isExpanded: Binding(
                get: { expandedReasoning.contains(messageID) },
                set: { expanded in
                    if expanded { expandedReasoning.insert(messageID) }
                    else { expandedReasoning.remove(messageID) }
                }
            )
        ) {
            Text(text)
                .font(.caption)
                .foregroundStyle(c.textTertiary)
                .textSelection(.enabled)
                .padding(.top, YNTheme.Space.sm)
        } label: {
            Label("思考过程", systemImage: "brain")
                .font(.caption.weight(.medium))
                .foregroundStyle(c.textTertiary)
        }
        .tint(c.textTertiary)
        .padding(.horizontal, YNTheme.Space.md)
        .padding(.vertical, YNTheme.Space.sm)
        .background(
            RoundedRectangle(cornerRadius: YNTheme.Radius.notice, style: .continuous)
                .fill(c.surface.opacity(0.40))
        )
        .accessibilityLabel(isExpanded ? "收起思考过程" : "展开思考过程")
    }

    private var assistantBubbleShape: some Shape {
        UnevenRoundedRectangle(
            topLeadingRadius: YNTheme.Radius.bubble,
            bottomLeadingRadius: YNTheme.Radius.bubbleTail,
            bottomTrailingRadius: YNTheme.Radius.bubble,
            topTrailingRadius: YNTheme.Radius.bubble,
            style: .continuous
        )
    }

    // MARK: - Scroll behavior

    private func handleMessageCountChange(
        old: Int,
        new: Int,
        reader: ScrollViewProxy
    ) {
        guard new > old else { return }

        // 首次灌入历史：不是「新消息」，不计数、不动画。
        // 位置已由 `.defaultScrollAnchor(.bottom)` 保证。
        if !didInitialScroll {
            didInitialScroll = true
            unseenCount = 0
            return
        }

        let newestIsUser = model.displayMessages.last?.role == .user
        if isNearBottom || newestIsUser {
            scrollToBottom(reader)
            unseenCount = 0
        } else {
            unseenCount += new - old
        }
    }

    private func scrollToBottom(_ reader: ScrollViewProxy) {
        if reduceMotion {
            reader.scrollTo("bottom", anchor: .bottom)
        } else {
            withAnimation(.easeOut(duration: 0.2)) {
                reader.scrollTo("bottom", anchor: .bottom)
            }
        }
    }

    private func newMessagesButton(reader: ScrollViewProxy, colors c: YNTheme.Palette) -> some View {
        Button {
            scrollToBottom(reader)
            unseenCount = 0
        } label: {
            Label("\(unseenCount) 条新消息", systemImage: "arrow.down")
                .font(.footnote.weight(.semibold))
        }
        .buttonStyle(.borderedProminent)
        .tint(c.accent)
        .controlSize(.small)
        .accessibilityHint("回到最新消息")
    }

    private func dismissKeyboard() {
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
    }
}

// MARK: - Bubble

private struct MessageBubble: View {
    let message: ChatSession.Message
    let companionName: String
    let colors: YNTheme.Palette
    /// 是否在这一条上显示**说话人身份**（助手一组的第一条：头像 + 名字）。
    var showsIdentity: Bool = false
    /// 是否是这一组的第一条（决定头像/名字的间距）。
    var isFirstOfGroup: Bool = false
    /// 已在 ViewModel 解析好的表情图（查不到为 nil → 退化为占位）。
    let stickerImage: UIImage?
    /// 解析失败的原因（显示在占位符下方）。
    /// 界面上如实说明原因，比一个沉默的占位符有用得多 —— 见 ChatViewModel 的注释。
    let stickerFailure: String?
    /// 是否是一组同角色消息的最后一条 —— 只有它带「小尾巴」。
    let isLastOfGroup: Bool
    /// 长按菜单里的「朗读」（走 `feature_route.tts` 的语音渠道）。
    /// `nil` = 不提供入口（例如预览模式或将来把朗读收进别处）。
    var onSpeak: ((ChatSession.Message) -> Void)?

    /// 长按菜单里的「本轮收据」（P1）。
    ///
    /// 与朗读同理：`nil` 或该轮没有 `turnId` 时**不出现这一项**。
    /// 菜单里出现一个点了没内容的入口，比没有入口更糟。
    var onReceipt: ((ChatSession.Message) -> Void)?

    /// 长按菜单里的「删除」（第 221 轮，用户反馈）。
    ///
    /// `nil` = 不提供（还没有库行的流式草稿）—— 见 `ChatViewModel.canDelete(_:)`。
    var onDelete: ((ChatSession.Message) -> Void)?

    private var isUser: Bool { message.role == .user }

    /// 气泡内容是图片（含表情图）时收紧内边距 —— 图文混排时
    /// 14pt 的文本内边距会让图片四周出现一圈多余的白边。
    private var hasVisualContent: Bool {
        message.imageData != nil || stickerImage != nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            // 说话人身份：助手一组的第一条显示头像 + 名字。
            // 借 Aru 的做法 —— 整屏只有气泡时，聊天页读起来"没有人在说话"。
            if showsIdentity, !isUser {
                HStack(spacing: YNTheme.Space.sm) {
                    CompanionAvatar(avatarURL: nil, name: companionName, size: 24)
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    Text(companionName)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(colors.textSecondary)
                }
                .padding(.leading, 2)
                .accessibilityHidden(true)
            }

            HStack(alignment: .bottom) {
                if isUser { Spacer(minLength: 72) }

                bubbleContent
                    .padding(.horizontal, hasVisualContent ? 4 : 14)
                    .padding(.vertical, hasVisualContent ? 4 : 10)
                    .background(bubbleColor, in: bubbleShape)
                    // 用户气泡带一层**品牌色淡光**：它是"我"说的话，
                    // 在深色画布上需要比助手气泡更"跳"一点（tinted shadow，
                    // 不是黑色阴影 —— 见 DESIGN.md §6.4 Elevation.glow）。
                    .ynElevation(isUser ? YNTheme.Elevation.glow(colors.accent, radius: 10)
                                        : YNTheme.Elevation.Spec(color: .clear, radius: 0, y: 0))
                    .contextMenu {
                    if !message.text.isEmpty, message.imageData == nil, !message.isSticker {
                        Button {
                            UIPasteboard.general.string = message.text
                        } label: {
                            Label("复制", systemImage: "doc.on.doc")
                        }
                        // 朗读只给**助手**的文本消息：用户自己的消息没有朗读的意义，
                        // 表情与图片也没有可念的文字（菜单里那两项本就不该出现）。
                        if !isUser, let onSpeak {
                            Button {
                                onSpeak(message)
                            } label: {
                                Label("朗读", systemImage: "speaker.wave.2")
                            }
                        }
                    }
                    // 收据不看正文是否为空：正文为空的"只有思考过程"那种回合
                    // 恰恰最需要看收据（模型把预算花在推理上了）。
                    if !isUser, message.turnId != nil, let onReceipt {
                        Button {
                            onReceipt(message)
                        } label: {
                            Label("本轮收据", systemImage: "list.bullet.rectangle")
                        }
                    }
                    // 「删除」（第 221 轮）：只给**已落库**的消息（`onDelete` 为 nil
                    // 就整项不出现）—— 流式草稿还没有库行，删它没有意义。
                    // 放在菜单**最后**并以 destructive 呈现：它是这里唯一不可逆的操作。
                    if let onDelete {
                        Divider()
                        Button(role: .destructive) {
                            onDelete(message)
                        } label: {
                            Label("删除这一条", systemImage: "trash")
                        }
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(accessibilityLabel)

                if !isUser { Spacer(minLength: 72) }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var bubbleContent: some View {
        if let data = message.imageData, let image = UIImage(data: data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .frame(maxHeight: 320)
                .clipShape(RoundedRectangle(cornerRadius: YNTheme.Radius.bubbleImage, style: .continuous))
        } else if message.isSticker {
            // 🔴 第 202 轮：**这里此前一直是原始占位符**。
            //
            // `stickerImage` / `stickerFailure` 在结构体里声明了、调用点也传了，
            // 但这个分支**从来没读过它们** —— 所以表情**永远**渲染成占位符，
            // 与 id 空间、文件名、文件是否读得到**全都无关**。
            //
            // 前几轮我在 id 映射、`file_name` 兜底、异步解析上绕了三圈，
            // 每次都是"改完就装机"，却始终没先确认这个分支到底读了什么。
            // 这是本会话最贵的一次绕路。
            if let data = message.stickerImageData, let image = UIImage(data: data) {
                stickerImageView(image)
            } else if let stickerImage {
                stickerImageView(stickerImage)
            } else {
                VStack(alignment: .leading, spacing: 2) {
                    Label("表情", systemImage: "face.smiling")
                        .font(.body)
                        .foregroundStyle(isUser ? colors.onAccent : colors.textPrimary)
                    // 把**具体原因**写出来：一个沉默的占位符会把
                    // "没导入 / 文件丢了 / id 对不上"三种情况混成一件事。
                    if let stickerFailure {
                        Text(stickerFailure)
                            .font(.caption2)
                            .foregroundStyle(colors.warning)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        } else {
            Text(message.text)
                .font(.body)
                .foregroundStyle(isUser ? colors.onAccent : colors.textPrimary)
                // 行距 2：中文正文挤在一起读起来费劲；多行回复尤其明显。
                .lineSpacing(2)
                .textSelection(.enabled)
        }
    }

    /// 表情图的统一渲染。表情**不套气泡底** ——
    /// 贴纸自带背景与轮廓，再包一层圆角矩形就成了"卡片套卡片"。
    private func stickerImageView(_ image: UIImage) -> some View {
        Image(uiImage: image)
            .resizable()
            .scaledToFit()
            .frame(maxWidth: 132, maxHeight: 132)
            .accessibilityHidden(true)
    }

    private var bubbleColor: Color {
        isUser ? colors.accent : colors.surface
    }

    private var bubbleShape: some Shape {
        let normal = YNTheme.Radius.bubble
        let tail = YNTheme.Radius.bubbleTail
        // 同组中间的气泡四角统一；只有最后一条朝说话人一侧收出尾巴。
        let bottomLeading = isUser ? normal : (isLastOfGroup ? tail : normal)
        let bottomTrailing = isUser ? (isLastOfGroup ? tail : normal) : normal
        return UnevenRoundedRectangle(
            topLeadingRadius: normal,
            bottomLeadingRadius: bottomLeading,
            bottomTrailingRadius: bottomTrailing,
            topTrailingRadius: normal,
            style: .continuous
        )
    }

    private var accessibilityLabel: String {
        let sender = isUser ? "我" : companionName
        if message.imageData != nil { return "\(sender)发送了一张图片" }
        if message.isSticker { return "\(sender)发送了一个表情" }
        return "\(sender)：\(message.text)"
    }
}

// MARK: - Avatar

struct CompanionAvatar: View {
    let avatarURL: String?
    let name: String
    var size: CGFloat = 40

    var body: some View {
        Group {
            if let asset = AvatarResolver.assetName(for: avatarURL) {
                Image(asset)
                    .resizable()
                    .scaledToFill()
            } else if let remote = AvatarResolver.remoteURL(for: avatarURL) {
                AsyncImage(url: remote) { phase in
                    if let image = phase.image {
                        image.resizable().scaledToFill()
                    } else {
                        placeholder
                    }
                }
            } else {
                placeholder
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }

    private var placeholder: some View {
        Circle()
            .fill(.secondary.opacity(0.16))
            .overlay {
                Text(String(name.prefix(1)))
                    .font(.headline)
                    .foregroundStyle(.secondary)
            }
    }
}

// MARK: - Composer

private struct ChatComposerInset: ViewModifier {
    @ObservedObject var model: ChatViewModel
    let colors: YNTheme.Palette
    let reduceTransparency: Bool

    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content.safeAreaBar(edge: .bottom, spacing: 0) {
                ChatComposer(model: model, colors: colors, reduceTransparency: reduceTransparency)
            }
        } else {
            content.safeAreaInset(edge: .bottom, spacing: 0) {
                ChatComposer(model: model, colors: colors, reduceTransparency: reduceTransparency)
            }
        }
    }
}

private struct ChatComposer: View {
    @ObservedObject var model: ChatViewModel
    let colors: YNTheme.Palette
    let reduceTransparency: Bool

    @FocusState private var focused: Bool

    /// 「画一张」弹层（第 228 轮）。
    @State private var showImageSheet = false

    /// 写入确认卡（P0 ③）。
    ///
    /// ## 这条链路的完整语义（照 Rust 的实现写，不要想当然）
    /// Rust 对需要确认的工具**停下回合**（`finished_reason = "confirm_pending"`），
    /// 并交出一个 `confirm_request` 事件；宿主 `approveTool` / `rejectTool`
    /// 之后必须**重跑同一个回合** —— 审批是"名称+参数"完全匹配的**一次性**授权，
    /// 重跑时匹配的调用才执行（拒绝则把「用户拒绝」回灌给模型）。
    ///
    /// 所以这张卡的两个按钮都不是"关掉弹窗"：它们会真的重跑那一轮。
    private func confirmationCard(_ pending: ChatSession.ToolConfirmation) -> some View {
        VStack(alignment: .leading, spacing: YNTheme.Space.sm) {
            Label("需要你确认", systemImage: "hand.raised.fill")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(colors.textPrimary)
            Text(pending.summary)
                .font(.caption)
                .foregroundStyle(colors.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: YNTheme.Space.sm) {
                Button("拒绝") { model.resolveConfirmation(approve: false) }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                Button("允许一次") { model.resolveConfirmation(approve: true) }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            colors.surface,
            in: RoundedRectangle(cornerRadius: YNTheme.Radius.composer, style: .continuous)
        )
        .accessibilityElement(children: .contain)
    }

    var body: some View {
        VStack(spacing: YNTheme.Space.xs) {
            // ⚠️ 写入确认卡（P0 ③）摆在输入框**上方**：它属于"这一轮还没结束"，
            // 不是一个可以忽略的提示。有待确认时输入框同时被禁用（见下）。
            if let pending = model.pendingConfirmation {
                confirmationCard(pending)
            }
            HStack(alignment: .bottom, spacing: YNTheme.Space.sm) {
                // 第 228 轮：主动要一张图。放在输入框左边而不是藏进菜单 ——
                // 这是"我明确想让它画"的动作，藏起来等于没有。
                Button {
                    showImageSheet = true
                } label: {
                    Image(systemName: "photo.badge.plus")
                        .font(.body.weight(.medium))
                        .frame(width: 28, height: 28)
                }
                .buttonStyle(.plain)
                .foregroundStyle(colors.accent)
                .frame(minWidth: 44, minHeight: 44)
                .disabled(model.pendingConfirmation != nil)
                .accessibilityLabel("画一张")

                TextField("发消息", text: $model.draft, axis: .vertical)
                    .font(.body)
                    .lineLimit(1...5)
                    .textFieldStyle(.plain)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)
                    .background(
                        reduceTransparency ? colors.surface : colors.surface.opacity(0.92),
                        in: RoundedRectangle(cornerRadius: YNTheme.Radius.composer, style: .continuous)
                    )
                    .focused($focused)
                    .submitLabel(.send)
                    .onSubmit {
                        if model.canSend { model.sendText() }
                    }
                    // 有待确认的工具调用时先处理那张卡：批准要**重跑同一个请求**，
                    // 而请求只保存了最后一份 —— 此时再发新消息会把它覆盖掉。
                    .disabled(model.pendingConfirmation != nil)
                    .accessibilityLabel("消息")

                if model.isGenerating {
                    stopButton
                } else {
                    sendButton
                }
            }

            // 当前模型：把"正在用的是哪个"直接摊出来，不用去设置里翻。
            //
            // ⚠️ 这里**只放模型名**，不放 token 数字：输入框上方是"现在用什么"，
            // 而"这一轮花了多少"属于那一轮 —— 它在**「本轮收据」**里
            // （供应商回执口径，第 207 轮起每回合写入 `token_usage`
            //  + `prompt_audit`）。
            //
            // 这段注释此前写着"iOS 拿不到 per-turn 用量 / `token_usage` 运行时无人写"，
            // 那是第 207 轮之前的情况 —— 已按现状更正（同一类"注释撒谎"）。
            if let modelName = model.activeModelName {
                Text(modelName)
                    .font(.caption2)
                    .foregroundStyle(colors.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .lineLimit(1)
                    .accessibilityLabel("当前模型：\(modelName)")
            }
        }
        .padding(.horizontal, YNTheme.Space.lg)
        .padding(.vertical, YNTheme.Space.sm)
        .sheet(isPresented: $showImageSheet) {
            ImageRequestSheet(model: model)
        }
    }

    @ViewBuilder
    private var sendButton: some View {
        if #available(iOS 26.0, *) {
            Button(action: model.sendText) {
                Image(systemName: "arrow.up")
                    .font(.body.weight(.bold))
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.glassProminent)
            .tint(colors.accent)
            .disabled(!model.canSend)
            .frame(minWidth: 44, minHeight: 44)
            .accessibilityLabel("发送")
        } else {
            Button(action: model.sendText) {
                Image(systemName: "arrow.up")
                    .font(.body.weight(.bold))
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.borderedProminent)
            .tint(colors.accent)
            .disabled(!model.canSend)
            .frame(minWidth: 44, minHeight: 44)
            .accessibilityLabel("发送")
        }
    }

    @ViewBuilder
    private var stopButton: some View {
        if #available(iOS 26.0, *) {
            Button(action: model.stopGenerating) {
                Image(systemName: "stop.fill")
                    .font(.subheadline.weight(.semibold))
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.glass)
            .tint(colors.danger)
            .frame(minWidth: 44, minHeight: 44)
            .accessibilityLabel("停止生成")
        } else {
            Button(action: model.stopGenerating) {
                Image(systemName: "stop.fill")
                    .font(.subheadline.weight(.semibold))
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.bordered)
            .tint(colors.danger)
            .frame(minWidth: 44, minHeight: 44)
            .accessibilityLabel("停止生成")
        }
    }
}

private struct ChatBottomPositionKey: PreferenceKey {
    static var defaultValue: CGFloat = .greatestFiniteMagnitude
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}
