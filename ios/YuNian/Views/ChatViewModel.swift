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
    @Published var draft: String {
        didSet { UserDefaults.standard.set(draft, forKey: draftKey) }
    }

    let capabilities = Capabilities()
    let companionId: Int64

    private let environment: AppEnvironment
    private let session: ChatSession
    private var cancellables = Set<AnyCancellable>()
    private var sendTask: Task<Void, Never>?
    private var didAppear = false

    /// 朗读播放器（持有 `AVAudioPlayer` —— 见 TtsSpeaker 的注释：
    /// 播放器一旦被释放声音立刻停）。
    private let speaker = TtsSpeaker()
    private var ttsTask: Task<Void, Never>?

    private var draftKey: String { "chat.draft.\(companionId)" }

    /// 当前生效的模型名（输入栏下方显示）。
    ///
    /// ## 刻意只给模型名，不给 token 用量
    /// 竞品会在输入栏下摊出「模型 + tokens + 用时」。iOS 这条链路只能做到第一项：
    ///   · 生成绑定的 `AgentTurnResult` 只有 `roundsUsed` / `finishedReason`，
    ///     **没有用量字段**；
    ///   · `token_usage` 表虽在 schema 里，但全仓**只有备份恢复会写它**，
    ///     运行时无人写 → 查出来恒为 0。
    ///
    /// 所以这里不摆一个永远显示 0 的数字。要做得先让 Rust 侧回传用量。
    var activeModelName: String? {
        guard let active = try? environment.apiConfigs?.activeConfig(),
              !active.model.isEmpty else { return nil }
        return active.model
    }

    var canSend: Bool {
        !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && phase == .idle
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
        self.session = ChatSession(companionId: companionId)
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
        phase = .booting

        companion = try? environment.companions?.fetch(id: companionId)
        session.loadHistory(from: environment, limit: 100)
        markRead()
        updatePhase()
    }

    /// 页面离开不直接销毁已完成消息；若仍在生成则取消底层回合，
    /// 避免第二个 ChatSession 同时订阅全局 StreamSink 导致串会话。
    func disappear() {
        if session.isRunning {
            session.cancel(in: environment)
        }
        sendTask?.cancel()
        sendTask = nil
        // 离开页面就停朗读：后台继续出声而屏幕上没有任何"正在念"的指示，
        // 用户只能靠"声音从哪来"猜，那还不如停掉。
        stopSpeaking()
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

        ttsStates[message.id] = .synthesizing
        let plan = TtsClient.Plan(
            baseUrl: route.baseUrl,
            apiKey: apiKey,
            model: route.model,
            provider: route.provider,
            proto: settings.proto,
            voice: settings.voice,
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
                self.ttsStates[message.id] = .failed(Self.describeTtsError(error))
            }
        }
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

    private static func describeTtsError(_ error: Error) -> String {
        if let tts = error as? TtsClient.TtsError { return tts.message }
        return error.localizedDescription
    }

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
            }
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
