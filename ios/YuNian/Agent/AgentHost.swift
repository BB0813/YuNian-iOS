import Foundation
import os

/// Agent 宿主层 —— Swift 侧实现 Rust 通过 UniFFI 回调的 7 个 foreign trait。
///
/// 这是 M1（Agent 核心跨端）的主体。对应 Android 侧 `core/agent` 的手写部分：
/// `AgentFacade` / `AgentDialogueCoordinator` / `AgentToolHost` /
/// `MemoryStoreImpl` / `SkillStoreImpl` / `StickerPreferenceStoreImpl` / `AgentRequestSigner`。
/// Rust 侧只负责决策，副作用（落库、发表情、写文件）全部经这些回调回到宿主。
///
/// ## ⚠️ Swift 方法名的对齐说明
/// 下面各 `extension` 里写的 Swift 方法名，是按 UniFFI 0.29 的既定命名规则
/// （`snake_case` → `lowerCamelCase`，record 字段同样转换）**推断**的。
/// 权威来源是 bindgen 的产物 `ios/Generated/LianyuAgent.swift`。
/// 首次跑通 M0（`scripts/build_agent_ios.sh` 或 CI）后，请按生成文件校对本文件的方法名
/// —— 这是 M0 的 V2 验证项的一部分。命名规则本身的依据：
/// `core/agent/src/main/kotlin/com/yunian/ai/agent/uniffi/lianyu_agent.kt` 中
/// Kotlin 侧同样是 `snake_case` → `camelCase`。
///
/// ## ⚠️ 线程约束（来自 Rust 侧审计）
/// `run_turn` / `run_turn_stream` 是**阻塞**调用：内部做阻塞 HTTP、`std::thread::sleep`
/// 退避，并有回合级全局 `Mutex`。因此：
///   - 必须从后台队列调用，绝不能在主线程；
///   - 同一时刻只允许一个回合（Rust 侧 `turn_lock` 已串行化，但 Swift 侧也应避免排队堆积）。
/// 回调（本文件的 trait 实现）会被 Rust 在**该回合的执行线程**上同步调用，
/// 因此实现里不要做长耗时同步 IO，也不要再回调进 Rust（会自我死锁）。
enum AgentHostThreading {
    /// 所有 run_turn* 调用都应提交到这里。
    static let turnQueue = DispatchQueue(
        label: "com.yunian.ai.agent.turn",
        qos: .userInitiated
    )
}

// MARK: - 流式回调

/// `StreamSink` 的 Swift 实现：把 Rust 的 SSE 增量桥接成 Swift 并发流。
///
/// Rust 侧已把 SSE 解析、重试、交付守卫全部做完，这里只负责转发。
final class AgentStreamSinkImpl: StreamSink, @unchecked Sendable {

    enum Event: Sendable {
        case textDelta(String)
        case reasoningDelta(String)
        case done(fullText: String, finishReason: String)
        case error(String)
    }

    private let lock = NSLock()
    private var continuations: [UUID: AsyncStream<Event>.Continuation] = [:]
    private let log = Logger(subsystem: "com.yunian.ai", category: "agent.stream")

    /// 订阅一次流式回合。
    ///
    /// ⚠️ **不会**在 `on_done` / `on_error` 后自动结束。
    /// Rust 的 `on_done` 代表**一次 HTTP 响应**完成（`native_gateway.rs:1144`），
    /// 而一个 Agent 回合可有多轮（工具调用后继续请求模型，最多 `max_rounds` 轮）。
    /// 回合级终止由 `ChatSession.send` 在阻塞的 `runTurnStream` 返回后
    /// 调用 `closeAll()` 保证 —— 这也是既有的死锁防护。
    func makeStream() -> AsyncStream<Event> {
        let id = UUID()
        return AsyncStream { continuation in
            lock.lock()
            continuations[id] = continuation
            lock.unlock()

            continuation.onTermination = { [weak self] _ in
                self?.lock.lock()
                self?.continuations.removeValue(forKey: id)
                self?.lock.unlock()
            }
        }
    }

    // MARK: StreamSink

    func onTextDelta(text: String) {
        broadcast(.textDelta(text))
    }

    func onReasoningDelta(text: String) {
        broadcast(.reasoningDelta(text))
    }

    func onDone(fullText: String, finishReason: String) {
        // ⚠️ 只广播，**不能**在这里 closeAll()。
        //
        // 已核对 Rust 契约：`on_done` 只代表**一次 HTTP 响应**收尾
        // （`native_gateway.rs` 的 `ureq_post_stream` 末尾 / Anthropic 降级分支），
        // 一个回合里可能被回调多次。收到首次 `on_done` 就关流，会把后续轮次
        // （工具执行后的第二次请求）的 text / done 回调全部丢掉 ——
        // 真机症状正是「等待指示结束，但没有回复」。
        // 回合终止由 ChatSession 在 `runTurnStream` 返回后 `closeAll()` 收束。
        broadcast(.done(fullText: fullText, finishReason: finishReason))
    }

    func onError(error: String) {
        log.error("流式回合出错：\(error, privacy: .public)")
        // ⚠️ 同理不关闭订阅：`handle_sse_line` 的 error chunk 会先回调
        // `on_error` 再返回 Err，而 `send_stream` 在**未交付任何增量**时仍可能
        // 换 Key / 重试并继续本回合。终态错误由 `AgentTurnResult.error` 上报，
        // 这里只把传输层错误作为事件透传给消费者。
        broadcast(.error(error))
    }

    /// 强制结束全部订阅。
    ///
    /// **调用方必须在阻塞的 `runTurnStream` 返回后调用它**：
    /// Rust 在非流式降级或异常路径下可能既不回 `onDone` 也不回 `onError`，
    /// 此时 `AsyncStream` 不会自行结束，等它的人会永久挂起。
    func closeAll() {
        lock.lock()
        let targets = Array(continuations.values)
        continuations.removeAll()
        lock.unlock()
        for continuation in targets {
            continuation.finish()
        }
    }

    // MARK: 内部

    private func broadcast(_ event: Event) {
        lock.lock()
        let targets = Array(continuations.values)
        lock.unlock()
        for continuation in targets {
            continuation.yield(event)
        }
    }
}

// MARK: - 工具宿主

/// `ToolHost` 的 Swift 实现：承接 Rust 决定要调用的工具，执行副作用并回灌结果文本。
///
/// 对应 Android 侧的 `AgentToolHost` / ChannelSendTool。返回的字符串会以 TOOL 角色
/// 回灌给模型，因此失败时要返回**可读的错误文本**而不是空串（否则模型会反复重试）。
final class AgentToolHostImpl: ToolHost, @unchecked Sendable {

    typealias Handler = @Sendable (_ argumentsJSON: String, _ contextJSON: String) -> String

    private let lock = NSLock()
    private var handlers: [String: Handler] = [:]
    private let log = Logger(subsystem: "com.yunian.ai", category: "agent.tool")

    init() {}

    /// 注册一个工具处理器。与 Rust 侧的 `register_global_tools` 配合使用：
    /// Rust 拿到工具**定义**（供模型选择），Swift 提供**执行**。
    func register(_ name: String, handler: @escaping Handler) {
        lock.lock()
        handlers[name] = handler
        lock.unlock()
    }

    func unregister(_ name: String) {
        lock.lock()
        handlers.removeValue(forKey: name)
        lock.unlock()
    }

    var registeredToolNames: [String] {
        lock.lock()
        defer { lock.unlock() }
        return handlers.keys.sorted()
    }

    // MARK: ToolHost

    func execute(toolName: String, argumentsJson: String, contextJson: String) -> String {
        lock.lock()
        let handler = handlers[toolName]
        lock.unlock()

        guard let handler else {
            log.warning("模型请求了未注册的工具：\(toolName, privacy: .public)")
            return #"{"ok":false,"error":"工具未注册：\#(toolName)"}"#
        }

        let result = handler(argumentsJson, contextJson)
        log.debug("工具 \(toolName, privacy: .public) 执行完成，返回 \(result.count) 字符")
        return result
    }
}

// MARK: - 设备签名

/// `RequestSignatureProvider` 的 Swift 实现：为 PARTNER（suflow.cloud）请求注入签名头。
///
/// Rust 侧是 **fail-closed**：本方法返回空数组时，Rust 会**拒绝发送**该请求
/// （`native_gateway.rs` 的 `inject_partner_signature`）。因此签名失败必须如实返回空，
/// 不要返回伪造头。
///
/// 签名算法与 payload 规范见 `RequestSigner`；Android 侧对应
/// `core/agent/.../AgentRequestSigner.kt`。
final class AgentSignatureProviderImpl: RequestSignatureProvider, @unchecked Sendable {

    private let log = Logger(subsystem: "com.yunian.ai", category: "agent.sign")

    /// 当宿主无法给出权威路径时使用的兜底值。
    ///
    /// ⚠️ 这里存在一个**已知的跨端不一致**：Rust 侧 `native_gateway.rs` 硬编码
    /// `"/chat/completions"`，而 Kotlin 侧取 `url.encodedPath` 得到
    /// `"/v1/chat/completions"`（PARTNER base 为 `https://suflow.cloud/v1`）。
    /// 本实现沿用了 **Rust 的值**（因为签名由 Rust 触发、路径也由 Rust 拼接），
    /// 但该分歧必须先与服务端确认 —— 见 `docs/ios-port-feasibility.md` §7.5 / V9。
    static let partnerPathPrefix = ""

    private let pathPrefix: String

    init(pathPrefix: String = AgentSignatureProviderImpl.partnerPathPrefix) {
        self.pathPrefix = pathPrefix
    }

    // MARK: RequestSignatureProvider

    func signHeaders(method: String, path: String, body: String, clientId: String) -> [RequestHeader] {
        let fullPath = pathPrefix + path
        do {
            let headers = try RequestSigner.sign(
                method: method,
                path: fullPath,
                body: body.isEmpty ? nil : Data(body.utf8),
                clientId: clientId
            )
            return headers.values
                .sorted { $0.key < $1.key }
                .map { RequestHeader(name: $0.key, value: $0.value) }
        } catch {
            // fail-closed：宁可拒发也不发未签名（或错误签名）的请求
            log.error("设备签名失败，请求将被 Rust 侧拒绝：\(String(describing: error), privacy: .public)")
            return []
        }
    }
}
