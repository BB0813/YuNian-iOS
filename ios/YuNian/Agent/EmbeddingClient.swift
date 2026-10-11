import Foundation

/// 向量嵌入客户端 —— 多线路 P0 ② 里 `embedding` 线路的消费者。
///
/// ## 权威来源（逐项来自 Android 源码）
/// `core/network/.../EmbeddingService.kt`（176 行）：
///
/// | 项 | Kotlin | 行号 |
/// |---|---|---|
/// | 端点 | `POST {base}/embeddings` | :126-129 |
/// | Gemini 端点 | `{base}/openai/embeddings` | :127 |
/// | 请求体 | `{model, input}` | :131-134 |
/// | 认证 | `Authorization: Bearer <key>` | :138 |
/// | 响应取值 | `data[0].embedding` | :152-155 |
/// | 默认模型 | `text-embedding-3-small` | :31 |
/// | 支持嵌入的 provider | `EMBEDDING_CAPABLE_PROVIDERS` | :33-41 |
/// | provider → 推荐模型 | `PROVIDER_EMBEDDING_MODELS` | :43-50 |
/// | 超时 | connect 10s / read 15s / write 10s | :90-97 |
///
/// `unified_memories.embedding` 是 **little-endian Float32** 的 BLOB，
/// 那条约定在 `AgentStores` 里实现（`littleEndianFloats`）。
///
/// ## 与 Android 的差异（如实记录，不是简化）
/// Android **只**看「当前启用配置」的 provider，并用它自己的推荐模型表 ——
/// 渠道行里的 `model` 列**完全不读**。于是 Android 上"给某个用途指定别的
/// 向量模型"这件事**无法表达**。
///
/// iOS 多线路把**显式绑定**当权威（这正是用户要的"向量嵌入也有模型渠道可以接入"）：
///   · 显式绑定了 `feature_route.embedding` → 用那一行的模型，**不看** provider 白名单
///   · 没绑定 → 回退「当前启用渠道」+ Android 那套 provider 白名单与推荐模型
///
/// 第二条保留 Android 语义：没配过多线路的库，行为与改动前一致
/// （不会因为给对话渠道配了个不支持的 provider 就突然开始发嵌入请求）。
struct EmbeddingClient {

    /// `EmbeddingService.kt:31`
    static let defaultModel = "text-embedding-3-small"

    /// `EMBEDDING_CAPABLE_PROVIDERS`（:33-41）—— 与 `ApiProvider` 的枚举名逐字对应。
    static let capableProviders: Set<String> = [
        "OPENAI", "OPENROUTER", "SILICONFLOW", "DASHSCOPE", "ZHIPU", "GEMINI", "CUSTOM",
    ]

    /// `PROVIDER_EMBEDDING_MODELS`（:43-50）
    static let providerModels: [String: String] = [
        "OPENAI": "text-embedding-3-small",
        "OPENROUTER": "openai/text-embedding-3-small",
        "SILICONFLOW": "BAAI/bge-m3",
        "DASHSCOPE": "text-embedding-v3",
        "ZHIPU": "embedding-3",
        "GEMINI": "text-embedding-004",
    ]

    /// Android 侧该 provider 是否被认为「支持嵌入」。
    static func isSupported(provider: String) -> Bool {
        capableProviders.contains(provider)
    }

    /// 该 provider 的推荐向量模型（无推荐 → `defaultModel`）。
    static func recommendedModel(provider: String) -> String {
        providerModels[provider] ?? defaultModel
    }

    /// 嵌入端点。Gemini 的 OpenAI 兼容入口多一层 `/openai`（:126-129）。
    ///
    /// `baseUrl` 尾斜杠一律去掉；空串 → nil（不猜地址）。
    static func endpoint(baseUrl: String, provider: String) -> URL? {
        var base = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        while base.hasSuffix("/") { base.removeLast() }
        guard !base.isEmpty else { return nil }
        let path = provider == "GEMINI" ? "/openai/embeddings" : "/embeddings"
        return URL(string: base + path)
    }

    /// 解析响应里的第一个向量（`data[0].embedding`，:152-161）。
    ///
    /// 缺字段、字段不是数组、元素不是数字一律返回 nil —— 宁可不做语义检索，
    /// 也不要把一个"长度不对的向量"交给余弦相似度（那会得到看似有效的乱序分数）。
    static func parseEmbedding(_ data: Data) -> [Float]? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let items = root["data"] as? [[String: Any]],
              let first = items.first,
              let raw = first["embedding"] as? [Any] else { return nil }
        var floats: [Float] = []
        floats.reserveCapacity(raw.count)
        for value in raw {
            if let number = value as? NSNumber {
                floats.append(number.floatValue)
            } else if let text = value as? String, let parsed = Float(text) {
                floats.append(parsed)
            } else {
                return nil
            }
        }
        return floats.isEmpty ? nil : floats
    }

    /// 一次嵌入请求的输入。
    struct Request {
        var baseUrl: String
        var apiKey: String
        var model: String
        var provider: String
        var text: String
    }

    enum EmbeddingError: Error, Equatable {
        case emptyText
        case badBaseUrl
        case missingKey
        case unsupportedProvider(String)
        case http(Int, String?)
        case transport(String)
        case malformedResponse

        /// 面向界面/日志的一句话（不泄露密钥）。
        var message: String {
            switch self {
            case .emptyText: return "嵌入文本为空"
            case .badBaseUrl: return "未配置接口地址"
            case .missingKey: return "未配置 API Key"
            case .unsupportedProvider(let p): return "\(p) 未被视为支持向量嵌入的服务商"
            case .http(let code, let detail): return "HTTP \(code)" + (detail.map { "：\($0)" } ?? "")
            case .transport(let detail): return "请求失败：\(detail)"
            case .malformedResponse: return "响应缺少 data[0].embedding"
            }
        }
    }

    /// 默认读取超时（`EmbeddingService.kt:94` 的 15s）。
    static let readTimeout: TimeInterval = 15

    /// **同步**取向量。
    ///
    /// ## 为什么必须是同步的
    /// Rust 通过 UniFFI 回调 `MemoryStore.embed_text` 拿向量
    /// （`memory_selector.rs:65`），那个方法签名就是同步返回 `Option<String>`；
    /// Android 的实现同样是同步阻塞（`MemoryStoreImpl.embedText` 里的
    /// `runBlockingOnIo { provider.embed(text) }`）。
    ///
    /// ## 为什么用 `dataTask` + 信号量，而不是 `await URLSession.data(for:)`
    /// 同步上下文里"等一个 async 任务"只能再起 `Task` 并阻塞等待，
    /// 那会占住 Swift 并发线程池的线程去等同一个池里的任务 —— 池被占满即死锁。
    /// `dataTask` 的完成回调走 URLSession 自己的队列，等它不会和并发池互相堵。
    func embedSync(_ request: Request, timeout: TimeInterval = readTimeout) throws -> [Float] {
        let text = request.text
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw EmbeddingError.emptyText
        }
        guard let url = Self.endpoint(baseUrl: request.baseUrl, provider: request.provider) else {
            throw EmbeddingError.badBaseUrl
        }
        let key = request.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else { throw EmbeddingError.missingKey }

        var httpRequest = URLRequest(url: url, timeoutInterval: timeout)
        httpRequest.httpMethod = "POST"
        httpRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        httpRequest.setValue("application/json", forHTTPHeaderField: "Accept")
        httpRequest.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        httpRequest.httpBody = try JSONSerialization.data(
            withJSONObject: ["model": request.model, "input": text]
        )

        let semaphore = DispatchSemaphore(value: 0)
        let lock = NSLock()
        var outcome: Result<[Float], Error> = .failure(EmbeddingError.transport("未发起请求"))

        let task = URLSession.shared.dataTask(with: httpRequest) { data, response, error in
            let result: Result<[Float], Error>
            if let error {
                result = .failure(EmbeddingError.transport(error.localizedDescription))
            } else {
                let code = (response as? HTTPURLResponse)?.statusCode ?? -1
                let body = data ?? Data()
                if !(200...299).contains(code) {
                    result = .failure(EmbeddingError.http(code, Self.serverMessage(body)))
                } else if let floats = Self.parseEmbedding(body) {
                    result = .success(floats)
                } else {
                    result = .failure(EmbeddingError.malformedResponse)
                }
            }
            lock.lock()
            outcome = result
            lock.unlock()
            semaphore.signal()
        }
        task.resume()

        if semaphore.wait(timeout: .now() + timeout) == .timedOut {
            task.cancel()
            throw EmbeddingError.transport("超时（\(Int(timeout))s）")
        }
        lock.lock()
        let result = outcome
        lock.unlock()
        return try result.get()
    }

    /// 服务端错误体里的 message（与 Android 一样只用于**提示**，不落库）。
    private static func serverMessage(_ data: Data) -> String? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        if let error = root["error"] as? [String: Any],
           let message = error["message"] as? String, !message.isEmpty {
            return message
        }
        if let message = root["message"] as? String, !message.isEmpty { return message }
        return nil
    }
}

/// 极小的「文本 → 向量」记忆缓存（进程内，超出容量丢最旧的）。
///
/// ## 为什么值得有
/// Rust 每回合召回都会对**同一句 query** 调一次 `embed_text`；重发同一句话
/// 既慢（百毫秒级）又要花钱。缓存只活在进程内、进程退出即失，
/// 不落盘 —— 向量本身在 `unified_memories.embedding` 里有正式归宿，
/// 这里只是别再问一遍同样的问题。
///
/// ## 为什么是 `@unchecked Sendable`
/// 本类会被 Rust 的回调从任意线程调用（`MemoryStore` 是 `Send + Sync`），
/// 所以它必须跨线程安全。内部**每一次读写都在同一把 `NSLock` 下**，
/// 因此 `@unchecked` 是如实描述而不是绕过检查；不标的话，
/// `AgentStores` 会多出 "non-Sendable stored property" 警告。
final class EmbeddingQueryCache: @unchecked Sendable {

    private let capacity: Int
    private var store: [String: [Float]] = [:]
    private var order: [String] = []
    private let lock = NSLock()

    init(capacity: Int = 64) {
        self.capacity = max(1, capacity)
    }

    func value(for key: String) -> [Float]? {
        lock.lock()
        defer { lock.unlock() }
        return store[key]
    }

    func insert(_ value: [Float], for key: String) {
        lock.lock()
        defer { lock.unlock() }
        if store[key] == nil { order.append(key) }
        store[key] = value
        while order.count > capacity, let oldest = order.first {
            order.removeFirst()
            store.removeValue(forKey: oldest)
        }
    }
}

/// 嵌入失败的**退避**：连续失败时一段时间内直接放弃，不再每回合都去撞同一个超时。
///
/// ## 为什么必须有
/// `embedText` 是**同步阻塞**的（UniFFI 回调签名决定的，见 `AgentStores.embedText`），
/// 而 Rust 每回合召回都会调它。一条配错 / 欠费的向量渠道会让**每一回合**都先卡满
/// 一个读超时 —— 用户看到的是"聊天变慢了"，根因却在另一条线路上。
/// 这类间接症状最难查，所以这里结构性挡住它：
/// 失败后退避 60 秒，期间语义分直接缺席（**本来失败时也是缺席**，
/// Rust 把 None 当"本机无嵌入能力"降级为关键词召回），但回合不再被拖住。
///
/// 与 `EmbeddingQueryCache` 同样用一把锁保证跨线程安全（回调可能来自任意线程）。
final class EmbeddingFailureBackoff: @unchecked Sendable {

    /// 失败后的静默期（秒）。
    static let cooldown: TimeInterval = 60

    private let lock = NSLock()
    private var failedAt: TimeInterval?

    /// 现在是否应该跳过（还在静默期里）。
    func shouldSkip(now: TimeInterval = Date().timeIntervalSince1970) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let failedAt else { return false }
        if now - failedAt >= Self.cooldown {
            self.failedAt = nil      // 静默期已过，允许再试一次
            return false
        }
        return true
    }

    func recordFailure(now: TimeInterval = Date().timeIntervalSince1970) {
        lock.lock()
        failedAt = now
        lock.unlock()
    }

    func recordSuccess() {
        lock.lock()
        failedAt = nil
        lock.unlock()
    }
}
