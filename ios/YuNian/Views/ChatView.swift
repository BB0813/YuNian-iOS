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
        .task { await model.appear() }
        .onDisappear { model.disappear() }
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private func toolbarContent(colors c: YNTheme.Palette) -> some ToolbarContent {
        ToolbarItem(placement: .principal) {
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
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(toolbarAccessibilityLabel)
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

    private func messageScroll(colors c: YNTheme.Palette, viewportHeight: CGFloat) -> some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                if model.displayPhase == .booting && model.displayMessages.isEmpty {
                    loadingRows(colors: c)
                } else if model.displayMessages.isEmpty && model.displayStreaming.isEmpty {
                    // Android 没有欢迎空态；这里同样不放大插画或 CTA，
                    // 只保留输入区，让角色和对话自然开始。
                    Color.clear.frame(minHeight: max(viewportHeight * 0.55, 240))
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

                    MessageBubble(
                        message: message,
                        companionName: model.companion?.name ?? "对方",
                        colors: c,
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
                        onSpeak: { target in model.speak(target) }
                    )
                    .padding(.top, divider ? YNTheme.Space.xs : groupSpacing(index: index, previous: previous, current: message))
                    .id(message.id)

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
        // iOS 17+ 原生「聊天式初始锚点」：首次布局即停在底部。
        //
        // 不能靠手写 scrollTo 解决：历史是**一次性灌入**的（0 → N），
        // 那一刻测量到的「是否在底部」必然是 false，于是既不滚动、
        // 还会把 N 条历史误记成「N 条新消息」。
        .defaultScrollAnchor(.bottom)
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

    private var isUser: Bool { message.role == .user }

    /// 气泡内容是图片（含表情图）时收紧内边距 —— 图文混排时
    /// 14pt 的文本内边距会让图片四周出现一圈多余的白边。
    private var hasVisualContent: Bool {
        message.imageData != nil || stickerImage != nil
    }

    var body: some View {
        HStack(alignment: .bottom) {
            if isUser { Spacer(minLength: 72) }

            bubbleContent
                .padding(.horizontal, hasVisualContent ? 4 : 14)
                .padding(.vertical, hasVisualContent ? 4 : 10)
                .background(bubbleColor, in: bubbleShape)
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
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(accessibilityLabel)

            if !isUser { Spacer(minLength: 72) }
        }
        .frame(maxWidth: .infinity)
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

    var body: some View {
        VStack(spacing: YNTheme.Space.xs) {
            HStack(alignment: .bottom, spacing: YNTheme.Space.sm) {
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
                    .accessibilityLabel("消息")

                if model.isGenerating {
                    stopButton
                } else {
                    sendButton
                }
            }

            // 当前模型：把"正在用的是哪个"直接摊出来，不用去设置里翻。
            //
            // ⚠️ 这里**只有模型名，没有 token 用量** ——
            // iOS 这条链路上拿不到 per-turn 用量：生成绑定里
            // `AgentTurnResult` 没有用量字段，`token_usage` 表也**只有备份恢复会写**，
            // 运行时无人写。所以不做那个数字，而不是显示一个恒为 0 的假值。
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
