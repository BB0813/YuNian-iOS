import Foundation
import os
import Combine
// 表情图片要持有 UIImage —— 解析在 ViewModel 做（见 stickerImages 的注释），
// 而不是在 View 的 body 里每次重绘查一次库。
import UIKit

/// 单聊页的 UI 适配层。
///
/// ## 边界
/// - 页面只观察这个对象，不散取 `AppEnvironment` 的十几个成员；
/// - 现有 `ChatSession` 仍负责请求组装、流消费与落库；
/// - 不在这里重做 ContentFilter、工具装配或消息持久化，避免双重执行；
/// - 不暴露逻辑层尚未实现的图片输入、语音、重试、引用与精确分页。
@MainActor
final class ChatViewModel: ObservableObject {

    enum Phase: Equatable {
        case booting
        case idle
        case typing
        case streaming
        case stopping
    }

    struct Capabilities: Equatable {
        let text = true
        let cancel = true
        let generatedImage = true
        let stickerDisplay = true

        // 明确关闭：逻辑层补齐前 UI 不展示入口。
        let userImageVision = false
        let voiceMessage = false
        let voiceCall = false
        let retry = false
        let quote = false
        let pagination = false
    }

    @Published private(set) var companion: CompanionRepository.Companion?
    @Published private(set) var messages: [ChatSession.Message] = []
    private static let stickerLog = Logger(subsystem: "com.yunian.ai", category: "chat.sticker")

    /// 表情消息 → 真实图片。
    ///
    /// ## 为什么放在 ViewModel 而不是 View
    /// `StickerBubbleModel.init?` 会**同步读一次 SQLite**，而它的注释明确要求
    /// 「避免每次重绘都去读一次 SQLite」。若在 `MessageBubble` 里构造它，
    /// 列表每次重绘就会对每个表情各查一次库。
    /// 因此在这里**按消息变化解析一次**，视图只做字典查找。
    ///
    /// 解析不到的（表情被删、备份导入后 id 变了、文件丢失）不进字典，
    /// 由视图退化为占位 —— 与 Android「资源缺失时显示描述」一致。
    @Published private(set) var stickerImages: [UUID: UIImage] = [:]

    /// 表情渲染失败的原因（按消息 id）。
    ///
    /// ## 为什么要显示到界面上，而不是只写日志
    /// 真机反复出现"气泡在、图不在"。**拿不到设备日志时，界面就是唯一的取证面。**
    /// 一个沉默的占位符会把三种完全不同的原因混成一件事：
    ///   · 事件里没有文件名，且 entry_id 在本地不存在
    ///   · 条目在，但 fileName 指向的文件不在磁盘上
    ///   · 事件里连 entry_id 都没有
    /// 三者要修的地方完全不同，所以必须在界面上分开。
    @Published private(set) var stickerFailures: [UUID: String] = [:]
    @Published private(set) var streamingText = ""
    @Published private(set) var reasoningText = ""
    @Published private(set) var phase: Phase = .booting
    @Published private(set) var errorMessage: String?

    /// 朗读状态（按消息 id）。
    ///
    /// ## 为什么要显示到界面上，而不是只写日志
    /// 与表情失败原因同一条原则：**拿不到设备日志时，界面就是唯一的取证面**。
    /// 一个沉默的"点了没反应"会把三种完全不同的原因混成一件事：
    ///   · 这条消息没有可朗读的文字（表情 / 图片 / 只剩括号内容）
    ///   · 朗读线路没配，或渠道没密钥
    ///   · 接口返回的不是音频（这时要给出状态码与正文开头）
    enum TtsState: Equatable {
        case synthesizing
        case speaking
        case failed(String)

        var label: String {
            switch self {
            case .synthesizing: return "正在合成语音…"
            case .speaking: return "朗读中…"
            case .failed(let reason): return "朗读失败：\(reason)"
            }
        }

        var symbol: String {
            switch self {
            case .synthesizing: return "waveform"
            case .speaking: return "speaker.wave.2.fill"
            case .failed: return "exclamationmark.triangle.fill"
            }
        }

        var isFailure: Bool {
            if case .failed = self { return true }
            return false
        }
    }

    @Published private(set) var ttsStates: [UUID: TtsState] = [:]

    /// 待确认的工具调用（P0 ③ 的"写入确认"）。
    ///
    /// Rust 对需要确认的工具会**停下回合**并把这次调用交出来
    /// （`finished_reason == "confirm_pending"` + `confirm_request` 事件）。
    /// 界面据此弹一张卡；用户点允许/拒绝后重跑那一回合。
    @Published private(set) var pendingConfirmation: ChatSession.ToolConfirmation?
    /// 回合是否停在"等确认"（用于把状态显示成"等你确认"而不是"正在输入"）。
    @Published private(set) var isAwaitingConfirmation = false
    private var confirmTask: Task<Void, Never>?
    @Published var draft: String {
        didSet { UserDefaults.standard.set(draft, forKey: draftKey) }
    }

    let capabilities = Capabilities()
    let companionId: Int64

    /// 界面上的一行提示（删除成功、删除失败等）。
    /// 用**会自己消失**的方式显示（见 ChatView），不弹窗打断。
    @Published var notice: String?

    /// 会话是不是从 App 级拿到的**已存在**那一份（第 221 轮）。
    /// true = 用户返回时回合可能还在跑，`appear()` 不能再灌历史（会把流式那条挤掉）。
    private let reusedSession: Bool
    /// 若因为切到别的角色而中断了上一个角色的回复，这里是被中断者的名字（可空）。
    /// 界面用一行提示如实说明 —— 静默中断比中断更糟。
    let interruptedCompanionName: String?

    private let environment: AppEnvironment
    private let session: ChatSession
    private var cancellables = Set<AnyCancellable>()
    private var sendTask: Task<Void, Never>?
    private var didAppear = false

    /// 朗读播放器（持有 `AVAudioPlayer` —— 见 TtsSpeaker 的注释：
    /// 播放器一旦被释放声音立刻停）。
    private let speaker = TtsSpeaker()

    /// 历史是否已灌完（自动朗读的前置条件之一，见 `autoReadIfNeeded`）。
    private var didFinishInitialLoad = false
    /// 上一次看到的助手消息数（用来识别"刚刚新增了一条"）。
    private var lastSeenMessageCount = 0
    private var ttsTask: Task<Void, Never>?

    private var draftKey: String { "chat.draft.\(companionId)" }

    /// 当前生效的模型名（输入栏下方显示）。
    ///
    /// ## 为什么这里只有模型名
    /// 输入栏下方回答的是"现在用什么"；"这一轮花了多少"属于**那一轮** ——
    /// 它在「本轮收据」里按供应商回执口径显示。
    ///
    /// ⚠️ 这段注释此前写着"`AgentTurnResult` 没有用量字段 / `token_usage`
    /// 运行时无人写 → 所以不做这个数字"。第 207 轮起：用量**一直在事件流里**
    /// （`AgentEvent.kind == "usage"`，Rust agent.rs:1294），
    /// 收据会读它，并每回合写 `token_usage` + `prompt_audit`。
    /// 刻意不在这里放数字，是**位置**的选择，不是能力的限制（已按现状更正）。
    var activeModelName: String? {
        guard let active = try? environment.apiConfigs?.activeConfig(),
              !active.model.isEmpty else { return nil }
        return active.model
    }

    /// 取某条助手消息对应的「本轮收据」。
    ///
    /// 先查内存（刚跑完的那几轮，带供应商回执），再回查 `prompt_audit`
    /// （重启后仍在，但没有 token —— 由 `TurnReceiptView` 如实说明）。
    func receipt(for message: ChatSession.Message) -> TurnReceipt? {
        session.receipt(for: message, environment: environment)
    }

    var canSend: Bool {
        !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && phase == .idle
            // 有待确认的工具调用时先处理它：批准要重跑"最后那份请求"，
            // 此时发新消息会把请求覆盖掉（见 ChatComposer 的确认卡注释）。
            && pendingConfirmation == nil
    }

    // MARK: - 离线预览（仅 DEBUG）
    //
    // 目的：**在没有 API Key 的情况下也能看到聊天页的完整视觉状态**。
    // 没有它，AI 气泡 / typing / 流式 / 思考过程 / 停止按钮 / 消息分组
    // 全部无法被验收 —— 而那正是这个页面最需要被评的部分。
    //
    // 三条自我约束（避免它变成"伪功能"）：
    //   1. 只在 DEBUG 构建存在，release 里连入口都没有；
    //   2. UI 上明确写「预览」，不冒充真实发送；
    //   3. 只在 ViewModel 做显示层覆盖，不写进 ChatSession、不落库。

    enum PreviewMode: String, CaseIterable, Identifiable {
        case conversation = "预览：完整对话"
        case generating = "预览：生成中状态"
        var id: String { rawValue }
    }

    @Published private(set) var previewMode: PreviewMode?
    /// 预览用的对话（在 `startPreview` 时**一次性构建**并缓存，
    /// 不在每次访问时重算 —— 见 `displayMessages`）。
    @Published private(set) var previewMessages: [ChatSession.Message]?

    /// 页面渲染统一走这几个属性，预览时被覆盖。
    var displayMessages: [ChatSession.Message] {
        previewMessages ?? messages
    }
    var displayReasoning: String {
        previewMode == .generating ? Self.previewReasoning : reasoningText
    }
    var displayStreaming: String {
        previewMode == .generating ? Self.previewStreaming : streamingText
    }
    var displayPhase: Phase {
        previewMode == .generating ? .streaming : phase
    }

    func startPreview(_ mode: PreviewMode) {
        switch mode {
        case .conversation:
            // ⚠️ 用**库里真实存在的**表情 id。
            // 上一版写死 `[1]`，而库里通常没有 id=1 的表情 ——
            // 于是预览里那条恰好显示占位，反而让"表情渲染"这条无法被验收。
            let convo = Self.previewConversation(stickerEntryId: firstStickerEntryId())
            previewMessages = convo
            // 预览消息不走 session.$messages，必须显式解析一次，
            // 否则表情图字典是空的 —— 预览里还是占位。
            resolveStickerImages(convo)
        case .generating:
            previewMessages = nil
            resolveStickerImages(messages)
        }
        previewMode = mode
        errorMessage = nil
    }

    func stopPreview() {
        previewMode = nil
        previewMessages = nil
        resolveStickerImages(messages)
    }

    /// 取库里任一条表情的 id；没有表情时返回 nil（预览里就少一条表情消息）。
    private func firstStickerEntryId() -> Int64? {
        guard let database = environment.database else { return nil }
        return try? database.pool.read { db in
            try Int64.fetchOne(db, sql: "SELECT id FROM sticker_entries ORDER BY id LIMIT 1")
        }
    }

    /// 覆盖多种真实情况：连续同角色（测分组节奏）、长文本（测换行）、
    /// 表情（测非文本气泡，**只在库里有表情时才放**）、短回复（测紧凑排布），
    /// 以及**跨时段**（测时间分隔线是否出现）。
    private static func previewConversation(stickerEntryId: Int64?) -> [ChatSession.Message] {
        let now = Date()
        func ago(_ minutes: Double) -> Date { now.addingTimeInterval(-minutes * 60) }

        var items: [ChatSession.Message] = [
            .init(role: .user, text: "在吗？今天有点累。", timestamp: ago(46)),
            .init(role: .assistant, text: "在的。", timestamp: ago(45)),
            .init(role: .assistant, text: "怎么了，发生什么事了吗？", timestamp: ago(45)),
        ]
        if let stickerEntryId {
            items.append(.init(role: .assistant, text: "[\(stickerEntryId)]",
                               isSticker: true, timestamp: ago(44)))
        }
        // ↑ 与下一条相隔约 40 分钟：应当出现一条时间分隔线
        items.append(contentsOf: [
            .init(role: .user, text: "工作上出了点问题，被领导当众说了一顿。", timestamp: ago(4)),
            .init(role: .assistant, text: """
                那确实很难受。被当众说，最难过的往往不是那句话本身，而是那种「所有人都在看着」的感觉。

                你现在是想先吐槽一下，还是想让我陪你想想怎么处理？
                """, timestamp: ago(4)),
            .init(role: .user, text: "先吐槽。", timestamp: ago(2)),
            .init(role: .assistant, text: "那就说吧，我在听。想骂多久都行。", timestamp: ago(1)),
        ])
        return items
    }

    private static let previewReasoning = """
        用户情绪偏负面，提到了工作挫折。先共情，不要急着给建议。
        上一轮用户说过「今天有点累」，可以呼应一下。
        """

    private static let previewStreaming = "我大概能想象那个场面。被当众指出问题，难受的不只是内容本身"

    var isGenerating: Bool {
        switch displayPhase {
        case .typing, .streaming, .stopping: true
        case .booting, .idle: false
        }
    }

    var subtitle: String? {
        switch displayPhase {
        case .typing: "正在输入…"
        case .streaming: "正在回复…"
        case .stopping: "正在停止…"
        case .booting: "正在载入…"
        case .idle: nil
        }
    }

    init(companionId: Int64, environment: AppEnvironment) {
        self.companionId = companionId
        self.environment = environment
        // 第 221 轮：会话由 App 级持有（见 AppEnvironment.chatSession(for:)）。
        // 复用上一份时，用户返回聊天页会看到**仍在流式的回复**，而不是被清空重来。
        let adopted = environment.chatSession(for: companionId)
        self.session = adopted.session
        self.reusedSession = adopted.reused
        self.interruptedCompanionName = adopted.cancelledOtherCompanion
        self.draft = UserDefaults.standard.string(forKey: "chat.draft.\(companionId)") ?? ""
        bindSession()
        // 播完（或被停）清掉过程状态；失败提示保留 —— 那是用户要看的。
        speaker.onFinish = { [weak self] _ in self?.clearTtsProgress() }
    }

    func appear() async {
        guard !didAppear else {
            markRead()
            return
        }
        didAppear = true

        companion = try? environment.companions?.fetch(id: companionId)

        if reusedSession {
            // ⚠️ 复用会话时**不要重新灌历史**：
            // 上一轮可能还在流式，`messages` 里已经有它；重灌会把正在跑的那条挤掉。
            // 阶段直接按会话当前状态推。
            phase = session.isRunning ? phase : .idle
            updatePhase()
        } else {
            phase = .booting
            session.loadHistory(from: environment, limit: 100)
            updatePhase()
        }

        // ⚠️ 第 208 轮：灌完历史才允许自动朗读 ——
        // 否则"进聊天页"这个动作本身会让最后一条历史被念一遍。
        didFinishInitialLoad = true
        lastSeenMessageCount = session.messages.count
        markRead()
    }

    /// 页面离开：**不再取消正在生成的回合**（第 221 轮，用户反馈）。
    ///
    /// ## 改了什么，以及为什么可以改
    /// 旧实现里有 `session.cancel(in:)` 与 `sendTask?.cancel()`，注释写着
    /// "避免第二个 ChatSession 同时订阅全局 StreamSink 导致串会话"。
    /// 那个顾虑是真的，但**解法不是取消回合**：
    /// 现在会话由 `AppEnvironment` 持有（`chatSession(for:)`），
    /// 离开页面时它继续活着、继续消费事件流 —— 同一时刻仍然只有一个订阅者，
    /// 而回复会在后台跑完并正常落库（返回列表就能看到新消息）。
    ///
    /// 回到同一个角色的聊天页时复用这一份会话；若中途打开了**别的**角色，
    /// `chatSession(for:)` 会取消旧回合并把"是谁被中断了"回报给界面。
    func disappear() {
        // ❌ 删掉了 session.cancel(...) 与 sendTask?.cancel()（见上面的说明）
        //
        // 有待确认的写操作时离页：**登记拒绝**（fail-closed）。
        // 与 Android `AgentConfirmGuard` 同一条原则 —— 没有确认界面的回合上，
        // 写操作不得被放行；否则那份一次性批准会悬在集合里，
        // 将来某个同名同参数的调用会**静默**命中。
        session.abandonConfirmation(in: environment)
        confirmTask?.cancel()
        confirmTask = nil
        // 离开页面就停朗读：后台继续出声而屏幕上没有任何"正在念"的指示，
        // 用户只能靠"声音从哪来"猜，那还不如停掉。
        stopSpeaking()
    }

    // MARK: - 删除单条消息（第 221 轮，用户反馈"补一个删除句子"）

    /// 这条消息能不能删。
    ///
    /// 只有**已落库**的消息能删（`storedId != nil`）：
    /// 正在流式的草稿还没有库行，删它没有意义 —— 界面据此**不显示入口**。
    func canDelete(_ message: ChatSession.Message) -> Bool {
        message.storedId != nil
    }

    /// 删除一条消息（真的删库行，不是只从界面移除）。
    ///
    /// ## 为什么删完要重灌历史
    /// 发给模型的上下文来自**库**（`historyForRequest` 走仓储），
    /// 所以"界面少了这一条"与"请求里少了这一条"必须同时成立；
    /// 只改内存数组会让下一轮请求继续把已删的消息送给模型。
    ///
    /// - Returns: 是否删除成功（供界面给出可读反馈）
    @discardableResult
    func deleteMessage(_ message: ChatSession.Message) -> Bool {
        guard let storedId = message.storedId else { return false }
        guard let repo = environment.messages else { return false }
        do {
            try repo.delete(messageId: storedId)
        } catch {
            notice = "删除失败，请稍后重试"
            return false
        }
        // 重灌：界面与请求都从库重建，避免两份状态不一致
        session.loadHistory(from: environment, limit: 100)
        notice = "已删除这一条"
        return true
    }

    // MARK: - 朗读

    /// 朗读一条消息（长按气泡 → 朗读）。
    ///
    /// 走 `feature_route.tts` 指向的语音渠道（多线路 P0 ②）。
    /// 再点同一条 = 停止。
    func speak(_ message: ChatSession.Message) {
        if ttsStates[message.id] != nil {
            stopSpeaking()
            return
        }
        stopSpeaking()

        guard let repo = environment.apiConfigs else { return }
        let settings = TtsSettings.load(from: repo)

        // 与 Android `ChatTtsController` 同一条清理链（`TtsTextCleaner`）：
        // 表情 id、`[图片]`、`[[生图: …]]`、Markdown 标记都不该被念出来。
        let text = TtsClient.cleanText(message.text, skipParentheses: settings.skipParentheses)
        guard !text.isEmpty else {
            ttsStates[message.id] = .failed("这条没有可朗读的文字")
            return
        }
        guard let route = (try? repo.resolve(purpose: .tts)) ?? nil else {
            ttsStates[message.id] = .failed("还没有可用的渠道")
            return
        }
        let apiKey = environment.resolvedAPIKey(configId: route.configId)
        guard !apiKey.isEmpty else {
            let name = environment.displayName(forProvider: route.provider)
            ttsStates[message.id] = .failed("「\(name)」缺少 API Key")
            return
        }

        // ⚠️ 第 208 轮：音色**按角色解析**（角色覆盖 > 全局）。
        // 直接用 `settings.voice` 会让"给这个角色单独配的音色"永远不生效。
        let voice = VoiceProfileStore.effectiveVoice(
            companionId: session.companionId,
            global: settings.voice,
            repository: repo
        )

        ttsStates[message.id] = .synthesizing
        let plan = TtsClient.Plan(
            baseUrl: route.baseUrl,
            apiKey: apiKey,
            model: route.model,
            provider: route.provider,
            proto: settings.proto,
            voice: voice,
            format: settings.format,
            text: text
        )

        ttsTask = Task { [weak self] in
            guard let self else { return }
            do {
                let audio = try await TtsClient().synthesize(plan)
                guard !Task.isCancelled else { return }
                try self.speaker.play(audio, format: settings.format)
                self.ttsStates[message.id] = .speaking
            } catch {
                guard !Task.isCancelled else { return }
                self.ttsStates[message.id] = .failed(TtsClient.describe(error))
            }
        }
    }

    // MARK: - 自动朗读新回复（第 208 轮）

    /// 新回复落地后自动朗读。
    ///
    /// ## 触发条件（每一条都必须满足，否则"突然出声"）
    /// 1. 总开关 `feature_route.tts.auto_read` 开着（**默认关**）
    /// 2. **历史已灌完**（`didFinishInitialLoad`）——
    ///    否则一进聊天页会把最后一条历史也念一遍
    /// 3. 消息数**增加**且新增的最后一条是**助手的文本消息**
    ///    （表情/图片/自己的消息都不念）
    /// 4. 用户没有正在听另一条（`ttsStates` 里没有进行中的状态）
    private func autoReadIfNeeded(_ messages: [ChatSession.Message]) {
        defer { lastSeenMessageCount = messages.count }
        guard didFinishInitialLoad else { return }
        guard messages.count == lastSeenMessageCount + 1 else { return }
        guard let last = messages.last, last.role == .assistant else { return }
        guard !last.isSticker, last.imageData == nil else { return }
        guard !last.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        guard ttsStates.values.allSatisfy({ $0.isFailure }) else { return }
        guard let repo = environment.apiConfigs,
              TtsSettings.load(from: repo).autoRead else { return }
        speak(last)
    }

    /// 停止朗读并清掉过程状态（失败提示保留）。
    func stopSpeaking() {
        ttsTask?.cancel()
        ttsTask = nil
        speaker.stop()
        clearTtsProgress()
    }

    /// 清掉"合成中 / 朗读中"，保留失败提示 —— 用户需要看到失败原因。
    private func clearTtsProgress() {
        ttsStates = ttsStates.filter { $0.value.isFailure }
    }

    /// 错误文案统一走 `TtsClient.describe` ——
    /// 聊天页与设置页试听共用一份，避免"同一句话两处说法不同"。

    func sendText() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, phase == .idle else { return }

        draft = ""
        errorMessage = nil
        // Android 语义：成功入队即乐观 typing。当前 iOS 无独立队列，
        // 这里在发起 session.send 前立即切换。
        phase = .typing

        sendTask = Task { [weak self] in
            guard let self else { return }
            await self.session.send(text, in: self.environment)
            if !Task.isCancelled {
                self.updatePhase()
                self.markRead()
                self.notifyReplyIfNeeded()
            }
        }
    }

    /// 回复跑完时，如果 App **不在前台**就发一条本地通知（第 227 轮）。
    ///
    /// ## 为什么要有"不在前台"这个判断
    /// 用户正盯着屏幕时再弹一条通知是打扰 —— 通知只解决"我没在看"的场景。
    ///
    /// ## iOS 的现实边界（写在代码里，免得以后有人以为能做更多）
    /// App 切到后台后通常会被挂起，回合也会停 —— 所以这条主要覆盖
    /// "切走但还没被挂起""回到桌面后系统仍给了几秒"这类窗口期。
    /// 真正"角色在后台主动找你"在 iOS 上只能靠**定时提醒**（见 `AppNotifications`）。
    private func notifyReplyIfNeeded() {
        guard environment.notificationSettings.replyDoneEnabled else { return }
        guard !AppNotifications.isActive else { return }
        // 取最后一条**非用户**消息当预览；没有就不发（空通知等于骚扰）
        guard let reply = messages.last(where: { $0.role != .user })?.text,
              !reply.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }

        let preview = String(reply.replacingOccurrences(of: "\n", with: " ").prefix(60))
        let name = (try? environment.companions?.fetch(id: companionId))?.name ?? "予念"
        Task {
            await AppNotifications.notifyReplyDone(companionName: name,
                                                  preview: preview,
                                                  companionId: companionId)
        }
    }

    func stopGenerating() {
        guard session.isRunning else { return }
        phase = .stopping
        session.cancel(in: environment)
    }

    func clearError() {
        errorMessage = nil
    }

    private func bindSession() {
        session.$messages
            .receive(on: RunLoop.main)
            .sink { [weak self] in
                self?.messages = $0
                self?.resolveStickerImages($0)
                self?.autoReadIfNeeded($0)
            }
            .store(in: &cancellables)

        session.$streamingText
            .receive(on: RunLoop.main)
            .sink { [weak self] value in
                self?.streamingText = value
                self?.updatePhase()
            }
            .store(in: &cancellables)

        session.$reasoningText
            .receive(on: RunLoop.main)
            .sink { [weak self] in self?.reasoningText = $0 }
            .store(in: &cancellables)

        session.$isRunning
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in self?.updatePhase() }
            .store(in: &cancellables)

        session.$lastError
            .receive(on: RunLoop.main)
            .sink { [weak self] in
                guard let value = $0, !value.isEmpty else { return }
                self?.errorMessage = value
            }
            .store(in: &cancellables)

        // 写入确认（P0 ③）：Rust 停下回合等宿主批准/拒绝，界面据此弹卡。
        session.$pendingConfirmation
            .receive(on: RunLoop.main)
            .sink { [weak self] in self?.pendingConfirmation = $0 }
            .store(in: &cancellables)

        session.$isAwaitingConfirmation
            .receive(on: RunLoop.main)
            .sink { [weak self] value in
                self?.isAwaitingConfirmation = value
                self?.updatePhase()
            }
            .store(in: &cancellables)
    }

    /// 批准 / 拒绝待确认的工具调用，然后由 session 重跑那一回合。
    func resolveConfirmation(approve: Bool) {
        guard pendingConfirmation != nil else { return }
        confirmTask = Task { [weak self] in
            guard let self else { return }
            await self.session.resolveConfirmation(approve: approve, in: self.environment)
        }
    }

    private func updatePhase() {
        if phase == .stopping, session.isRunning { return }
        if !session.isRunning {
            phase = .idle
        } else if streamingText.isEmpty {
            phase = .typing
        } else {
            phase = .streaming
        }
    }

    private func markRead() {
        guard let database = environment.database else { return }
        _ = try? ConversationRepository(database: database)
            .markReadThroughLatest(companionId: companionId)
        // 标已读之后角标要跟着掉 —— 否则"角标还在、消息已经读完"。
        Task { await environment.refreshBadge() }
    }

    // MARK: - 表情图片解析

    /// `[<entryId>]` → entryId。约定见 `ChatSession.applyEvents` 的注释
    /// （与 Android 全仓统一：贴纸是普通 TEXT 消息，内容为 `[stickerId]`）。
    /// 按文件名直接读表情图片。
    ///
    /// 用 `UIImage(contentsOfFile:)` 而不是 `Data(contentsOf:)` +
    /// `UIImage(data:)` 两步 —— 后者会先把整个文件读进内存再解码，
    /// 对 GIF/大图是双份开销；前者让 UIKit 自己按需映射。
    static func loadStickerImage(fileName: String) -> UIImage? {
        guard let directory = try? AppPaths.stickersDirectory() else { return nil }
        return UIImage(contentsOfFile: directory.appendingPathComponent(fileName).path)
    }

    private static func entryId(fromStickerText text: String) -> Int64? {
        guard text.hasPrefix("["), text.hasSuffix("]"), text.count > 2 else { return nil }
        return Int64(text.dropFirst().dropLast())
    }

    /// 按「消息变化」解析一次，而不是每次重绘。
    private func resolveStickerImages(_ source: [ChatSession.Message]) {
        guard let database = environment.database else {
            if !stickerImages.isEmpty { stickerImages = [:] }
            return
        }
        var resolved: [UUID: UIImage] = [:]
        var failures: [UUID: String] = [:]
        for message in source where message.isSticker {
            // ① 首选事件里带来的**文件名**：它是物理标识，
            //    不依赖 Rust 侧与本地 id 空间是否一致。
            //    真机症状（表情库 25 张正常、聊天页只有占位符）正是
            //    id 空间不一致造成的，所以这条路必须是首选。
            let eventFileName = message.stickerFileName ?? ""
            if !eventFileName.isEmpty {
                if let image = Self.loadStickerImage(fileName: eventFileName) {
                    resolved[message.id] = image
                    continue
                }
                failures[message.id] = "文件缺失：\(eventFileName)"
                continue
            }

            // 事件没带文件名：只能用 entry_id 查库。
            // 库里查不到就说明 Rust 回的 id 不在本地 id 空间里。
            if Self.entryId(fromStickerText: message.text) == nil {
                failures[message.id] = "事件无文件名，且正文不是 [id]：\(message.text)"
                continue
            }

            // ② 文件名缺失（历史消息、旧版本落库）时退回按 entry_id 查库
            guard let entryId = Self.entryId(fromStickerText: message.text),
                  let model = StickerBubbleModel(entryId: entryId, database: database) else {
                // 条目不存在：正常情况下 `applyEvents` 已经拦掉了，
                // 这里再挡一次是为了历史数据（旧版本落过无效 id）。
                failures[message.id] = "本地无此表情（id=\(Self.entryId(fromStickerText: message.text) ?? -1)）"
                continue
            }
            guard let image = model.image else {
                // ⚠️ 条目在、**文件不在**。这与"没有这个表情"是两回事：
                // 前者是资源缺失（备份导入后文件名对不上、文件被清理），
                // 后者是数据本来就没有。留一行日志，否则界面只会
                // 沉默地显示占位，无从判断是哪种。
                Self.stickerLog.error("表情文件读不到：entry=\(entryId, privacy: .public) file=\(model.fileName, privacy: .public)")
                failures[message.id] = model.fileName.isEmpty
                    ? "条目无文件名（id=\(entryId)）"
                    : "文件缺失：\(model.fileName)"
                continue
            }
            resolved[message.id] = image
        }
        // 键集合相同就跳过赋值，避免无谓的 @Published 触发（否则列表白重绘一次）。
        // ⚠️ 只比 count 是不够的：预览消息与真实消息的**条数可能恰好相同**，
        // 而 id 完全不同 —— 那样会漏掉一次必要的更新。
        if Set(resolved.keys) != Set(stickerImages.keys) {
            stickerImages = resolved
        }
        if failures != stickerFailures {
            stickerFailures = failures
        }
    }
}
