import Foundation

/// 供应商探测 —— 包装 Rust 的 `ApiProbe`（Android 侧由 `AgentFacade.apiProbe()` 暴露）。
///
/// ## 为什么补这个
/// 第 93 轮的 API 覆盖面机械核对发现 `ApiProbe` 的四个方法**全未接**。
/// 后果很具体：iOS 用户填了 API key **无法在保存前验证它是否可用**，
/// 也无法从服务端拉取模型列表，只能手填模型名。
/// 这直接影响"首次配置能否成功"—— 而填错 provider 的症状是
/// "发消息没反应"（我在第 40 轮把它列为上线即故障的一类）。
///
/// ## 字段顺序
/// `ApiProbeConfig` 的字段顺序我在生成绑定的 `FfiConverterTypeApiProbeConfig`
/// 里核对过（`LianyuAgent.swift:7998-8006`）：
/// provider / baseUrl / apiKey / model / temperature / maxTokens? / formatHint。
enum ApiProbeService {

    enum ProbeError: Error, CustomStringConvertible {
        case notConfigured
        case rustFailed(String)
        /// 该服务商不提供模型列表端点（**正常分支,不是故障**）。
        case modelsNotSupportedByProvider(String)

        var description: String {
            switch self {
            case .notConfigured:
                return "尚未选择供应商或未填密钥"
            case let .rustFailed(msg):
                return msg
            case let .modelsNotSupportedByProvider(provider):
                return "\(provider) 不支持模型列表查询，请手动填写模型名"
            }
        }

        /// 给界面的提示。当前与 `description` 相同，保留独立入口
        /// 以便将来 UI 需要更友好的措辞时不必改动 error 语义。
        var userMessage: String { description }
    }

    /// 测试连接：发一个最小请求，验证 key / baseUrl / 协议是否都通。
    ///
    /// ## 为什么这是三个里最有用的
    /// `fetchModels` 已经能验证「能不能读到模型列表」，但有些供应商
    /// **不支持 `/models`**（服务端会返回 HTML，Rust 转成
    /// 「该API不支持模型列表查询」）。此时 `testOpenai` / `testAnthropic`
    /// 走的是真正的对话端点，是更接近"能不能用"的验证。
    ///
    /// ## 协议选择交给 Rust 决定
    /// 用生成绑定里的 `apiUsesAnthropicProtocol(provider:formatHint:)`
    /// 而不是自己 if provider == "ANTHROPIC" —— 前者是权威判断
    /// （`formatHint` 也可能覆盖 provider 的默认行为）。
    // ⚠️ 第 72 轮：`ApiConfig` 是 `ApiConfigRepository` 的**嵌套** struct
    /// （ApiConfigRepository.swift:29），未限定名在这里解析不到 ——
    /// CI 实测三处 "cannot find type 'ApiConfig' in scope"。
    /// 我的关卡只查「类型在项目里存在」，没查「在使用点可见」，这是它的盲区。
    static func testConnection(
        config: ApiConfigRepository.ApiConfig,
        extraHeaders: [HttpHeader]
    ) -> Result<String, ProbeError> {
        let apiKey = KeychainStore.string(for: KeychainStore.Key.apiKey) ?? ""
        let isPartner = config.provider == "PARTNER"
        guard isPartner || !apiKey.isEmpty else { return .failure(.notConfigured) }

        let cfg = ApiProbeConfig(
            provider: config.provider,
            baseUrl: config.baseUrl,
            apiKey: apiKey,
            model: config.model,
            temperature: 0.7,
            // Rust 侧 Anthropic 测试固定 5、OpenAI 测试固定 1（见字段注释），
            // 传 nil 让它按自己的默认走。
            maxTokens: nil,
            formatHint: config.formatHint
        )

        // keys：PARTNER 的 key 走 extraHeaders（session/clientId），
        // 其余用 apiKey；空数组表示"无附加 key"。
        let keys: [String] = isPartner ? [] : [apiKey]

        do {
            if apiUsesAnthropicProtocol(provider: config.provider, formatHint: config.formatHint) {
                let reply = try ApiProbe().testAnthropic(
                    cfg: cfg, keys: keys,
                    messages: [ProbeMessage(role: "user", content: "ping")],
                    systemPrompt: "You are a connectivity probe. Reply with pong.",
                    extraHeaders: extraHeaders
                )
                return .success(reply)
            } else {
                let reply = try ApiProbe().testOpenai(
                    cfg: cfg, keys: keys,
                    messages: [ProbeMessage(role: "user", content: "ping")],
                    extraHeaders: extraHeaders
                )
                return .success(reply)
            }
        } catch let ApiProbeError.Message(message) {
            return .failure(.rustFailed(message))
        } catch {
            return .failure(.rustFailed(String(describing: error)))
        }
    }

    /// 查询余额。多数服务端**不返回**结构化余额，Rust 侧失败时会带原因。
    static func queryBalance(
        config: ApiConfigRepository.ApiConfig,
        extraHeaders: [HttpHeader]
    ) -> Result<BalanceInfo, ProbeError> {
        let apiKey = KeychainStore.string(for: KeychainStore.Key.apiKey) ?? ""
        let isPartner = config.provider == "PARTNER"
        guard isPartner || !apiKey.isEmpty else { return .failure(.notConfigured) }

        let cfg = ApiProbeConfig(
            provider: config.provider, baseUrl: config.baseUrl, apiKey: apiKey,
            model: config.model, temperature: 0.7, maxTokens: nil,
            formatHint: config.formatHint
        )
        do {
            return .success(try ApiProbe().queryBalance(
                cfg: cfg, keys: isPartner ? [] : [apiKey], extraHeaders: extraHeaders))
        } catch let ApiProbeError.Message(message) {
            return .failure(.rustFailed(message))
        } catch {
            return .failure(.rustFailed(String(describing: error)))
        }
    }

    /// 按 provider 构造认证头。
    ///
    /// 规则来自生成绑定的文档注释（对齐 Kotlin 的 fetchModels）：
    /// - PARTNER → `X-LianYu-Session` + `X-LianYu-Client-Id`
    /// - XIAOMI  → `api-key`
    /// - 其他    → `Authorization: Bearer`
    ///
    /// ⚠️ 注意这与「Rust 每回合用的头」是**两套路径**：回合里的 PARTNER 头由 Rust
    /// 依 credentials 自行拼装，而 `fetchModels` 由宿主显式传入。
    /// 两者用同一份 Keychain 数据，故结论一致。
    static func authHeaders(
        provider: String,
        apiKey: String,
        partnerSession: String?,
        partnerClientId: String?
    ) -> [HttpHeader] {
        switch provider {
        case "PARTNER":
            var out: [HttpHeader] = []
            if let s = partnerSession, !s.isEmpty { out.append(HttpHeader(name: "X-LianYu-Session", value: s)) }
            if let c = partnerClientId, !c.isEmpty { out.append(HttpHeader(name: "X-LianYu-Client-Id", value: c)) }
            return out
        case "XIAOMI":
            return [HttpHeader(name: "api-key", value: apiKey)]
        default:
            return [HttpHeader(name: "Authorization", value: "Bearer \(apiKey)")]
        }
    }

    /// 拉取模型列表（`GET /models`）。
    ///
    /// ⚠️ **apiKey 必须从 Keychain 取，不能从 `ApiConfig` 拿**：
    /// iOS 侧 `api_configs` 行的 `apiKey` 被刻意写成空串（不把明文落进未加密的
    /// SQLite，见 `ApiConfigRepository.upsertActiveConfig` 的安全决策注释），
    /// 真 key 只在 Keychain。用行里的空串去打，只会得到一个必然失败的请求。
    ///
    /// - Parameters:
    ///   - config: 本机启用的 API 配置（provider / baseUrl / model）
    ///   - extraHeaders: 认证头。PARTNER 需要 `X-LianYu-Session` /
    ///     `X-LianYu-Client-Id`，其余走 `Authorization: Bearer` ——
    ///     由调用方按 `AgentSettings.buildExtraHeaders()` 的结论传入，
    ///     避免这里重复实现一套认证规则。
    static func fetchModels(
        config: ApiConfigRepository.ApiConfig,
        extraHeaders: [HttpHeader]
    ) -> Result<[String], ProbeError> {
        // PARTNER 模式下 key 可为空（凭 session/clientId），其余必须有 key。
        let apiKey = KeychainStore.string(for: KeychainStore.Key.apiKey) ?? ""
        let isPartner = config.provider == "PARTNER"
        guard isPartner || !apiKey.isEmpty else { return .failure(.notConfigured) }

        let cfg = ApiProbeConfig(
            provider: config.provider,
            baseUrl: config.baseUrl,
            apiKey: apiKey,
            model: config.model,
            temperature: 0.7,
            maxTokens: nil,               // 仅连接测试用；拉列表不需要
            formatHint: ""                // 由 Rust 按 provider 推断
        )

        do {
            let models = try ApiProbe().fetchModels(cfg: cfg, extraHeaders: extraHeaders)
            return .success(models)
        } catch let ApiProbeError.Message(message) {
            // ⚠️ 第 108 轮：不要再把这句话原样当错误抛给用户。
            // 它来自 Rust api_probe.rs，是「该服务商不提供 GET /models」的
            // 正常分支，不是故障。把它当 error 显示，用户看到的就是
            // 一句看不懂的技术错误 —— 而这个信息真正该说的是
            // 「这家不支持列表查询，模型名请手动填」。
            let lower = message.lowercased()
            let unsupported = lower.contains("不支持") || lower.contains("not support")
                || lower.contains("404") || lower.contains("method not allowed")
            if unsupported {
                return .failure(.modelsNotSupportedByProvider(config.provider))
            }
            return .failure(.rustFailed(message))
        } catch {
            return .failure(.rustFailed(String(describing: error)))
        }
    }
}
