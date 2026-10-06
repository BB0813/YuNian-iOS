import Foundation

/// OpenAI 兼容协议的 AI 生图接入。
///
/// ## 权威来源（第 149 轮，全部来自源码）
/// `core/network/.../ImageGenerationService.kt`（496 行）：
///
/// | 项 | Kotlin 值 | 行号 |
/// |---|---|---|
/// | 模型列表 | `GET {base}/models` | :58-71 |
/// | 生图 | `POST {base}/images/generations` | :94-134、:189 |
/// | 响应取图 | `data[].b64_json` 优先，`data[].url` 回退 | :27、:208 |
/// | 请求参数回退 | 4 级降级，逐级去掉 responseFormat / n / size | :219-234 |
/// | 参数类错误判定 | message 含 response_format/unsupported/invalid/n must/size/参数 | :236-244 |
/// | 模型启发式 | IMAGE_MODEL_HINTS 命中且 CHAT_MODEL_EXCLUDES 不命中 | :425-429、:484-494 |
/// | 请求体 DTO | `{model, prompt, n?, size?, response_format?}` | :440-447 |
/// | 响应 DTO | `{data:[{b64_json?, url?, revised_prompt?, partial_image_index?}]}` | :449-463 |
/// | 数量上限 | `MAX_IMAGES_PER_REQUEST = 4` | :476 |
/// | 超时 | read 180s / call 240s | :477-478 |
///
/// ## 与 Kotlin 的差异（如实记录）
/// - **未接入对话主链路**。Kotlin 由 `ImageGenCoordinator` 触发
///   （关键词 → 概率 → 冷却），iOS 侧目前是**独立服务 + 手动入口**。
/// - **未做签名**（RequestSecurityInterceptor / 仅对 lianyu.ai 签名）。
///   iOS 侧已有 `SecureEnclaveSigner`，但那套是给聊天链路用的；
///   生图链路要不要签，需要服务端确认，未确认前**不签**。
/// - 未做流式分片累积（`partial_image_index` 分支）——Kotlin 的
///   `saveImages` 处理了它，但那需要服务端确认流式协议，暂不实现。
struct ImageGenClient {

    // MARK: - 常量（Kotlin companion object）

    /// `MAX_IMAGES_PER_REQUEST = 4`（ImageGenerationService.kt:476）
    static let maxImagesPerRequest = 4

    /// `READ_TIMEOUT_SECONDS = 180L`（:477）
    static let readTimeout: TimeInterval = 180

    /// `CALL_TIMEOUT_SECONDS = 240L`（:478）
    static let callTimeout: TimeInterval = 240

    /// `IMAGE_MODEL_HINTS`（:484-488）
    static let imageModelHints = [
        "flux", "dall-e", "dalle", "sd-", "sd3", "sd2", "sdxl", "stable-diffusion",
        "wanx", "wan2", "cogview", "qwen-image", "imagen", "ideogram", "seedream",
        "kolors", "hidream", "hunyuan", "irag", "midjourney", "seededit", "image",
    ]

    /// `CHAT_MODEL_EXCLUDES`（:491-494）
    static let chatModelExcludes = [
        "vl-", "-vl", "vision", "embedding", "embed", "rerank", "tts", "asr",
        "whisper", "chat", "instruct", "coder", "reasoner",
    ]

    // MARK: - 输入输出

    struct Attempt: Equatable {
        let model: String
        let prompt: String
        let count: Int
        let size: String?
        let responseFormat: String?
    }

    struct ModelCatalog: Equatable {
        var imageModels: [String]
        var allModels: [String]
    }

    enum ImageGenError: Error, Equatable {
        case emptyPrompt
        case noModel
        case noImageData
        case http(String)

        var message: String {
            switch self {
            case .emptyPrompt: return "生图描述为空"
            case .noModel: return "未配置生图模型"
            case .noImageData: return "接口未返回图片数据"
            case .http(let m): return m
            }
        }
    }

    // MARK: - 模型列表

    /// `GET {base}/models`。
    ///
    /// Kotlin (:58-71) 拿到全部 id 后过滤出可能是生图模型的子集。
    /// 空列表时 Kotlin 抛 `IllegalStateException("该接口未返回任何模型")`。
    func fetchModels(baseUrl: String, apiKey: String) async throws -> ModelCatalog {
        let all = try await rawModelIds(baseUrl: baseUrl, apiKey: apiKey)
        if all.isEmpty {
            throw ImageGenError.http("该接口未返回任何模型")
        }
        let imageModels = all.filter { Self.isLikelyImageModel($0) }
        return ModelCatalog(imageModels: imageModels, allModels: all)
    }

    private func rawModelIds(baseUrl: String, apiKey: String) async throws -> [String] {
        for base in Self.baseCandidates(baseUrl) {
            guard let url = URL(string: base + "/models") else { continue }
            var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
            req.setValue("application/json", forHTTPHeaderField: "Accept")
            Self.applyAuth(apiKey, to: &req)
            Self.applyTimeouts(to: &req)
            do {
                let (data, resp) = try await URLSession.shared.data(for: req)
                let code = (resp as? HTTPURLResponse)?.statusCode ?? -1
                if code == 404 { continue }             // Kotlin :202-203 逐个 base 试
                guard (200...299).contains(code) else {
                    throw ImageGenError.http(extractErrorMessage(data) ?? "HTTP \(code)")
                }
                return parseModelIds(data)
            } catch let e as ImageGenError {
                throw e
            } catch {
                continue
            }
        }
        return []
    }

    // MARK: - 生图

    func generate(
        baseUrl: String,
        apiKey: String,
        prompt: String,
        size: String?,
        count: Int
    ) async throws -> [Data] {
        let trimmed = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw ImageGenError.emptyPrompt }

        var lastError: Error = ImageGenError.noModel
        for attempt in Self.buildAttempts(prompt: trimmed, size: size, count: count) {
            do {
                let datas = try await postGeneration(baseUrl: baseUrl, apiKey: apiKey, attempt: attempt)
                if datas.isEmpty { throw ImageGenError.noImageData }
                return datas
            } catch {
                lastError = error
                // Kotlin :126-129 仅在参数类错误上回退，其余直接失败
                if !Self.isRecoverableParamError(error) { break }
            }
        }
        throw lastError
    }

    private func postGeneration(baseUrl: String, apiKey: String, attempt: Attempt) async throws -> [Data] {
        var payload: [String: Any] = ["model": attempt.model, "prompt": attempt.prompt]
        if let n = Optional(attempt.count) { payload["n"] = n }
        if let size = attempt.size { payload["size"] = size }
        if let rf = attempt.responseFormat { payload["response_format"] = rf }

        let body = try JSONSerialization.data(withJSONObject: payload)

        for base in Self.baseCandidates(baseUrl) {
            guard let url = URL(string: base + "/images/generations") else { continue }
            var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData)
            req.httpMethod = "POST"
            req.setValue("application/json", forHTTPHeaderField: "Accept")
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            Self.applyAuth(apiKey, to: &req)
            Self.applyTimeouts(to: &req)
            req.httpBody = body

            do {
                let (data, resp) = try await URLSession.shared.data(for: req)
                let code = (resp as? HTTPURLResponse)?.statusCode ?? -1
                if code == 404 { continue }
                guard (200...299).contains(code) else {
                    throw ImageGenError.http(extractErrorMessage(data) ?? "HTTP \(code)")
                }
                return try await parseImageItems(data)
            } catch let e as ImageGenError {
                throw e
            } catch {
                continue
            }
        }
        throw ImageGenError.http("无法连接生图接口")
    }

    // MARK: - 参数回退序列

    /// Kotlin `buildRequestAttempts`（:215-234）。
    /// 4 级降级 + distinct；`nil` 的字段不序列化。
    static func buildAttempts(prompt: String, size: String?, count: Int) -> [Attempt] {
        let safeCount = min(max(count, 1), maxImagesPerRequest)
        let safeSize = {
            let t = (size ?? "").trimmingCharacters(in: .whitespaces)
            return t.isEmpty ? "1024x1024" : t
        }()
        let primary = Attempt(model: "", prompt: prompt, count: safeCount,
                              size: safeSize, responseFormat: "b64_json")
        var out: [Attempt] = []
        for a in [
            primary,
            Attempt(model: primary.model, prompt: prompt, count: safeCount,
                    size: safeSize, responseFormat: nil),
            Attempt(model: primary.model, prompt: prompt, count: 1,
                    size: safeSize, responseFormat: nil),
            Attempt(model: primary.model, prompt: prompt, count: 1,
                    size: nil, responseFormat: nil),
        ] {
            if !out.contains(a) { out.append(a) }     // distinct
        }
        return out
    }

    /// Kotlin `isRecoverableParamError`（:236-244）
    static func isRecoverableParamError(_ error: Error) -> Bool {
        let msg: String
        if let e = error as? ImageGenError {
            msg = e.message
        } else {
            msg = error.localizedDescription
        }
        let low = msg.lowercased()
        return low.contains("response_format")
            || low.contains("unsupported")
            || low.contains("invalid")
            || low.contains("n must")
            || low.contains("size")
            || low.contains("参数")
    }

    /// Kotlin `isLikelyImageModel`（:425-429）
    static func isLikelyImageModel(_ modelId: String) -> Bool {
        let id = modelId.lowercased()
        return imageModelHints.contains { id.contains($0) }
            && !chatModelExcludes.contains { id.contains($0) }
    }

    // MARK: - base 候选

    /// Kotlin `baseCandidates(baseUrl)`：把用户填的地址规整成候选列表
    /// （去掉尾斜杠、去掉已带的 /v1 再各生成一份）。
    ///
    /// ⚠️ 这里是按 Kotlin 行为推断的复刻：原函数在 :187 被调用但定义
    /// 未在本次勘察范围内。行为以「逐个试到不 404」为准则，
    /// 具体候选集合若与 Kotlin 不一致，影响只是多重试一次。
    static func baseCandidates(_ baseUrl: String) -> [String] {
        var s = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        guard !s.isEmpty else { return [] }
        var out: [String] = []
        func add(_ v: String) { if !out.contains(v) { out.append(v) } }
        add(s)
        if s.hasSuffix("/v1") {
            add(String(s.dropLast(3)))
        } else {
            add(s + "/v1")
        }
        return out
    }

    // MARK: - 解析

    private func parseModelIds(_ data: Data) -> [String] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let arr = root["data"] as? [[String: Any]] else { return [] }
        return arr.compactMap { $0["id"] as? String }
    }

    /// 取图：`b64_json` 优先，`url` 回退（Kotlin :27、:208）。
    ///
    /// ⚠️ 第 151 轮：改成 `async`。
    /// Kotlin 的 `parseImageItems` 是普通函数，但它内部调的是同步的
    /// `runBlocking`/OkHttp 同步调用；Swift 侧 `URLSession.data(from:)`
    /// 是 async，所以本函数必须是 async。
    /// CI（f2a194e）报 :282 "'async' call in a function that does not
    /// support concurrency" —— 正是这里。
    private func parseImageItems(_ data: Data) async throws -> [Data] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let arr = root["data"] as? [[String: Any]] else {
            throw ImageGenError.noImageData
        }
        var out: [Data] = []
        for item in arr {
            if let b64 = item["b64_json"] as? String,
               let d = Data(base64Encoded: b64) {
                out.append(d)
            } else if let urlStr = item["url"] as? String,
                      let url = URL(string: urlStr) {
                let (d, _) = try await URLSession.shared.data(from: url)
                out.append(d)
            }
        }
        return out
    }

    private func extractErrorMessage(_ data: Data) -> String? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        if let err = root["error"] as? [String: Any], let m = err["message"] as? String {
            return m
        }
        if let m = root["message"] as? String { return m }
        return nil
    }

    // MARK: - 请求细节

    private static func applyAuth(_ apiKey: String, to req: inout URLRequest) {
        let k = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if !k.isEmpty {
            req.setValue("Bearer \(k)", forHTTPHeaderField: "Authorization")
        }
    }

    private static func applyTimeouts(to req: inout URLRequest) {
        req.timeoutInterval = callTimeout          // Kotlin callTimeout :478
    }
}
