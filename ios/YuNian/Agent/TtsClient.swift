import AVFoundation
import Foundation
import os

/// 语音合成（朗读）—— 多线路 P0 ② 里 `tts` 线路的消费者。
///
/// ## 权威来源（两套协议都来自 Android 源码）
///
/// **① OpenAI 兼容** —— `core/network/.../tts/OpenAiCompatibleTtsProvider.kt`（275 行）
/// | 项 | Kotlin | 行号 |
/// |---|---|---|
/// | 端点 | `POST <规整后的 /v1/audio/speech>` | :47、:169-191 |
/// | 请求体 | `{model, input, voice, response_format}` | :68-73 |
/// | 认证 | `Authorization: Bearer <key>` | :79 |
/// | 默认模型 / 音色 | `tts-1` / `alloy` | :165-166 |
/// | 允许格式 | mp3 / opus / aac / flac / wav / pcm | :167 |
/// | 响应 | **音频字节**（非 JSON），用魔数校验 | :93、:218-267 |
/// | http | 仅允许私网主机 | :177、:193-203 |
///
/// **② 小米 MiMo** —— `core/network/.../tts/MiMoTtsProvider.kt`（389 行）
/// | 项 | Kotlin | 行号 |
/// |---|---|---|
/// | 端点 | `POST https://<白名单主机>/v1/chat/completions` | :73、:364-378 |
/// | 请求体 | `{model, messages, audio:{format, voice}}` | :182-242 |
/// | 音频位置 | `choices[0].message.audio.data`（base64） | :331-337 |
/// | 模型 | `mimo-v2.5-tts` / `-voicedesign` / `-voiceclone` | :341-344 |
/// | 默认音色 | `mimo_default` | :345 |
/// | 输出格式 | `pcm` / `wav` | :382-385 |
/// | 白名单主机 | `api.xiaomimimo.com` 与三个 `token-plan-*` | :348-353 |
///
/// 超时预算同样是 Kotlin 的：`(字数 × 1000ms).coerceIn(30s, 180s)`
/// （`TimeoutBudgets.ttsSynthTimeoutMs`）。
///
/// ## 与 Android 的差异（如实记录，不是简化）
/// 1. **不支持音色设计 / 音色复刻模型**（`-voicedesign` / `-voiceclone`）。
///    前者要多一个提示词字段并单独验收，后者要选本地音频样本（还带魔数校验与
///    10MB 上限）。两条都需要各自的设置页与文件选择器，本机无法验收 ——
///    所以这里**明确报错**，而不是接受参数后必然失败。
/// 2. 输出格式只保留 **mp3 / wav**：iOS 用 `AVAudioPlayer` 播放，
///    它吃不下裸 PCM（那要 `AVAudioEngine` 自己拼帧）。
/// 3. 未接证书固定：iOS 的 ATS 已强制 TLS 1.2+，而 Android 那条
///    `RequestSecurityInterceptor` 主要针对 lianyu.ai 的固定证书。
struct TtsClient {

    // MARK: - 协议与格式

    /// 语音接口协议。
    enum SpeechProtocol: String, CaseIterable, Identifiable {
        /// 按渠道的 provider 自动判断（`XIAOMI` → MiMo，其余 → OpenAI 兼容）。
        case auto
        case openai
        case mimo

        var id: String { rawValue }

        var title: String {
            switch self {
            case .auto: "自动"
            case .openai: "OpenAI 兼容"
            case .mimo: "小米 MiMo"
            }
        }
    }

    /// 输出音频格式（只列 iOS 播得动的两种）。
    enum Format: String, CaseIterable, Identifiable {
        case mp3
        case wav

        var id: String { rawValue }
        var title: String { rawValue.uppercased() }
    }

    /// 自动判定实际协议：MiMo 的渠道（`ApiProvider.XIAOMI`）走对话式接口，
    /// 其余按 OpenAI 兼容的 `/audio/speech` 走。
    static func resolveProtocol(_ requested: SpeechProtocol, provider: String) -> SpeechProtocol {
        guard requested == .auto else { return requested }
        return provider == "XIAOMI" ? .mimo : .openai
    }

    // MARK: - 一次合成的输入

    struct Plan {
        var baseUrl: String
        var apiKey: String
        var model: String
        var provider: String
        var proto: SpeechProtocol
        var voice: String
        var format: Format
        var text: String
    }

    enum TtsError: Error, Equatable {
        case emptyText
        case missingKey
        case badBaseUrl(String)
        case unsupportedModel(String)
        case http(Int, String?)
        case notAudio(String)
        case emptyAudio

        /// 面向界面的一句话（不泄露密钥）。
        var message: String {
            switch self {
            case .emptyText: return "没有可朗读的文字"
            case .missingKey: return "该语音渠道没有可用的 API Key"
            case .badBaseUrl(let detail): return "接口地址不可用：\(detail)"
            case .unsupportedModel(let model):
                return "iOS 暂不支持 \(model)（音色设计/复刻需要额外的字段与本地样本），请改用 mimo-v2.5-tts 或其他语音渠道"
            case .http(let code, let detail): return "HTTP \(code)" + (detail.map { "：\($0)" } ?? "")
            case .notAudio(let detail): return "接口返回的不是音频：\(detail)"
            case .emptyAudio: return "接口返回了空音频"
            }
        }
    }

    /// Kotlin `TimeoutBudgets.ttsSynthTimeoutMs`
    static func synthTimeout(textLength: Int) -> TimeInterval {
        let ms = min(max(textLength * 1_000, 30_000), 180_000)
        return Double(ms) / 1000
    }

    // MARK: - 合成

    func synthesize(_ plan: Plan) async throws -> Data {
        let text = plan.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw TtsError.emptyText }
        let key = plan.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else { throw TtsError.missingKey }

        switch Self.resolveProtocol(plan.proto, provider: plan.provider) {
        case .mimo:
            return try await synthesizeMimo(plan, text: text, key: key)
        case .openai, .auto:
            return try await synthesizeOpenAICompatible(plan, text: text, key: key)
        }
    }

    // MARK: - ① OpenAI 兼容（`/audio/speech`）

    private func synthesizeOpenAICompatible(
        _ plan: Plan, text: String, key: String
    ) async throws -> Data {
        guard let url = Self.normalizeSpeechUrl(plan.baseUrl) else {
            throw TtsError.badBaseUrl(plan.baseUrl.isEmpty ? "未填写" : "无法规整为 /v1/audio/speech")
        }
        let model = plan.model.trimmingCharacters(in: .whitespaces).isEmpty
            ? Self.defaultOpenAIModel
            : plan.model.trimmingCharacters(in: .whitespaces)
        let voice = plan.voice.trimmingCharacters(in: .whitespaces).isEmpty
            ? Self.defaultOpenAIVoice
            : plan.voice.trimmingCharacters(in: .whitespaces)

        let payload: [String: Any] = [
            "model": model,
            "input": text,
            "voice": voice,
            "response_format": plan.format.rawValue,
        ]
        let body = try JSONSerialization.data(withJSONObject: payload)

        var request = URLRequest(url: url, timeoutInterval: Self.synthTimeout(textLength: text.count))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        request.httpBody = body

        let (data, response) = try await Self.transport(request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? -1
        guard (200...299).contains(code) else {
            throw TtsError.http(code, Self.serverMessage(data))
        }
        guard !data.isEmpty else { throw TtsError.emptyAudio }
        let contentType = (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Type") ?? ""
        guard Self.isLikelyAudioBody(data, contentType: contentType) else {
            throw TtsError.notAudio(Self.bodyPreview(data, contentType: contentType))
        }
        return data
    }

    // MARK: - ② 小米 MiMo（`/chat/completions`）

    private func synthesizeMimo(_ plan: Plan, text: String, key: String) async throws -> Data {
        guard let base = Self.normalizeMimoBaseUrl(plan.baseUrl) else {
            throw TtsError.badBaseUrl("MiMo 只接受 https 且主机在白名单内的地址")
        }
        let model = Self.normalizeMimoModel(plan.model)
        guard model == Self.mimoModelTTS else {
            throw TtsError.unsupportedModel(model)
        }
        let voice = plan.voice.trimmingCharacters(in: .whitespaces).isEmpty
            ? Self.defaultMimoVoice
            : plan.voice.trimmingCharacters(in: .whitespaces)
        // MiMo 的 `audio.format` 只认真 wav/pcm（`normalizeOutputFormat`）。
        let format = "wav"

        let payload: [String: Any] = [
            "model": model,
            "messages": [
                ["role": "user", "content": "请将下一条 assistant 消息合成为自然中文语音。"],
                ["role": "assistant", "content": text],
            ],
            "audio": ["format": format, "voice": voice],
        ]
        let body = try JSONSerialization.data(withJSONObject: payload)

        var request = URLRequest(
            url: base.appendingPathComponent("chat/completions"),
            timeoutInterval: Self.synthTimeout(textLength: text.count)
        )
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        // MiMo 文档同时接受 `api-key` 头；Android 两个都发（:76-77）。
        request.setValue(key, forHTTPHeaderField: "api-key")
        request.httpBody = body

        let (data, response) = try await Self.transport(request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? -1
        guard (200...299).contains(code) else {
            throw TtsError.http(code, Self.serverMessage(data))
        }
        guard let encoded = Self.extractMimoAudioBase64(data), !encoded.isEmpty else {
            throw TtsError.notAudio("响应缺少 choices[0].message.audio.data")
        }
        guard let audio = Data(base64Encoded: encoded), !audio.isEmpty else {
            throw TtsError.emptyAudio
        }
        // 与 OpenAI 那条一样过一遍魔数：MiMo 声称 wav，但网关可能回 JSON 错误体。
        guard Self.isLikelyAudioBody(audio, contentType: "") else {
            throw TtsError.notAudio("解码后的字节不像音频")
        }
        return audio
    }

    // MARK: - 纯逻辑（单测对象，逐条对应 Kotlin）

    /// `OpenAiCompatibleTtsProvider.normalizeSpeechUrl`（:169-191）。
    ///
    /// 用户可能填三种地址：`https://host`、`https://host/v1`、
    /// `https://host/v1/audio/speech`；三种都要归到同一条 speech 端点。
    /// 其余形态（带 query / userInfo / 未知路径）**一律拒绝** ——
    /// 猜错的后果是把密钥发到一个没预期的路径上。
    static func normalizeSpeechUrl(_ raw: String) -> URL? {
        var value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        while value.hasSuffix("/") { value.removeLast() }
        guard !value.isEmpty, let parts = URLComponents(string: value) else { return nil }
        let scheme = parts.scheme?.lowercased()
        guard let host = parts.host?.lowercased(), !host.isEmpty else { return nil }
        guard scheme == "https" || scheme == "http" else { return nil }
        if scheme == "http", !isPrivateHost(host) { return nil }
        guard parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil else { return nil }

        var path = parts.path
        while path.hasSuffix("/") { path.removeLast() }
        let speechPath: String
        switch true {
        case path.hasSuffix("/audio/speech"): speechPath = path
        case path.hasSuffix("/v1/audio"): speechPath = path + "/speech"
        case path.hasSuffix("/v1"): speechPath = path + "/audio/speech"
        case path.isEmpty: speechPath = "/v1/audio/speech"
        default: return nil
        }
        let port = parts.port.map { ":\($0)" } ?? ""
        return URL(string: "\(scheme ?? "https")://\(host)\(port)\(speechPath)")
    }

    /// `isPrivateHost`（:193-211）：http 只允许本机/内网 —— 明文 key 不该出公网。
    static func isPrivateHost(_ host: String) -> Bool {
        var h = host.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        while h.hasSuffix(".") { h.removeLast() }
        if h == "localhost" || h == "::1" { return true }
        let pieces = h.split(separator: ".").map(String.init)
        guard pieces.count == 4 else { return false }
        var octets: [Int] = []
        for piece in pieces {
            guard let value = Int(piece), (0...255).contains(value) else { return false }
            octets.append(value)
        }
        if octets[0] == 127 || octets[0] == 10 { return true }
        if octets[0] == 172, (16...31).contains(octets[1]) { return true }
        if octets[0] == 192, octets[1] == 168 { return true }
        if octets[0] == 169, octets[1] == 254 { return true }
        return false
    }

    /// `isLikelyAudioBody`（:218-267）。
    ///
    /// ⚠️ Kotlin 那版还收一个 `format` 参数，但**函数体从未用它**（读过整段确认）。
    /// 这里就不收，免得看起来像在按格式判定，实际不是。
    static func isLikelyAudioBody(_ body: Data, contentType: String) -> Bool {
        let ct = contentType.lowercased()
        if !ct.isEmpty {
            if ct.hasPrefix("audio/") { return true }
            if ct.contains("json") || ct.hasPrefix("text/")
                || ct.contains("html") || ct.contains("xml") {
                return false
            }
        }
        let bytes = [UInt8](body.prefix(12))
        if bytes.count >= 12,
           bytes[0] == 0x52, bytes[1] == 0x49, bytes[2] == 0x46, bytes[3] == 0x46,   // RIFF
           bytes[8] == 0x57, bytes[9] == 0x41, bytes[10] == 0x56, bytes[11] == 0x45 { // WAVE
            return true
        }
        if bytes.count >= 3, bytes[0] == 0x49, bytes[1] == 0x44, bytes[2] == 0x33 {   // ID3
            return true
        }
        if bytes.count >= 2, bytes[0] == 0xFF,
           bytes[1] == 0xFB || bytes[1] == 0xF3 || bytes[1] == 0xF2 {                 // MP3 帧同步
            return true
        }
        if bytes.count >= 4, bytes[0] == 0x66, bytes[1] == 0x4C,
           bytes[2] == 0x61, bytes[3] == 0x43 {                                      // fLaC
            return true
        }
        if bytes.count >= 4, bytes[0] == 0x4F, bytes[1] == 0x67,
           bytes[2] == 0x67, bytes[3] == 0x53 {                                      // OggS
            return true
        }
        if bytes.count >= 2, bytes[0] == 0xFF, bytes[1] == 0xF1 || bytes[1] == 0xF9 {  // AAC ADTS
            return true
        }
        // 没有可识别的魔数：短片音频（<128B）多半是错误体，长的一律放行。
        return body.count >= 128
    }

    /// `MiMoTtsProvider.normalizeBaseUrl`（:364-378）。
    static func normalizeMimoBaseUrl(_ raw: String) -> URL? {
        var value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        while value.hasSuffix("/") { value.removeLast() }
        guard !value.isEmpty, let parts = URLComponents(string: value) else { return nil }
        guard parts.scheme?.lowercased() == "https" else { return nil }
        guard let host = parts.host?.lowercased(), mimoAllowedHosts.contains(host) else { return nil }
        guard parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil, parts.port == nil else { return nil }
        var path = parts.path
        while path.hasSuffix("/") { path.removeLast() }
        guard path.isEmpty || path == "/v1" else { return nil }
        return URL(string: "https://\(host)/v1")
    }

    /// `normalizeModel`（:355-362）。
    static func normalizeMimoModel(_ raw: String) -> String {
        switch raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case mimoModelVoiceDesign: return mimoModelVoiceDesign
        case mimoModelVoiceClone: return mimoModelVoiceClone
        default: return mimoModelTTS
        }
    }

    /// `extractAudioData`（:331-337）。
    static func extractMimoAudioBase64(_ data: Data) -> String? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let choices = root["choices"] as? [[String: Any]],
              let first = choices.first,
              let message = first["message"] as? [String: Any],
              let audio = message["audio"] as? [String: Any] else { return nil }
        return audio["data"] as? String
    }

    /// 服务端错误体里的 message（`extractServerMessage`，:307-317）。
    static func serverMessage(_ data: Data) -> String? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        if let error = root["error"] as? [String: Any] {
            if let message = error["message"] as? String, !message.isEmpty { return message }
            if let type = error["type"] as? String, !type.isEmpty { return type }
        }
        if let message = root["message"] as? String, !message.isEmpty { return message }
        return nil
    }

    /// 失败时给一句话诊断：状态码之外的正文头 32 字节（`toHexPreview`，:269-273）。
    /// 只用于**提示**，不进日志文件、不含密钥。
    static func bodyPreview(_ data: Data, contentType: String) -> String {
        let head = String(data: data.prefix(48), encoding: .utf8)?
            .replacingOccurrences(of: "\n", with: " ") ?? ""
        let ct = contentType.isEmpty ? "无 Content-Type" : contentType
        let preview = head.isEmpty ? "（非文本内容）" : String(head.prefix(48))
        return "\(ct)，\(data.count) 字节，开头：\(preview)"
    }

    // MARK: - 常量（与 Kotlin 逐字对应）

    static let defaultOpenAIModel = "tts-1"
    static let defaultOpenAIVoice = "alloy"
    static let defaultMimoVoice = "mimo_default"
    static let mimoModelTTS = "mimo-v2.5-tts"
    static let mimoModelVoiceDesign = "mimo-v2.5-tts-voicedesign"
    static let mimoModelVoiceClone = "mimo-v2.5-tts-voiceclone"
    static let mimoDefaultBaseUrl = "https://api.xiaomimimo.com/v1"

    /// `MiMoTtsProvider.ALLOWED_HOSTS`（:348-353）。
    static let mimoAllowedHosts: Set<String> = [
        "api.xiaomimimo.com",
        "token-plan-cn.xiaomimimo.com",
        "token-plan-sgp.xiaomimimo.com",
        "token-plan-ams.xiaomimimo.com",
    ]

    /// `TtsTextCleaner.kt`（全文 24 行）+ 两处针对本仓语法的补丁。
    ///
    /// 朗读前把不该念出来的东西去掉：`<标签>`、表情的 `[12]`、`[[生图: …]]`、
    /// Markdown 的 `*_~`。`skipParentheses` 对应 Android 的
    /// `ChatTtsConfig.skipParentheses` —— **默认同样是 false**
    /// （角色常用括号写动作描写，但"念不念"由用户决定，不由我们替他决定）。
    ///
    /// ## ⚠️ 对 Android 版的两处补丁（都写明理由，不是随手改）
    /// 1. **先吃掉双层方括号**。本仓的生图标记是 `[[生图: 描述]]`，
    ///    而 `\[[^\]]+\]` 只能匹配到**第一个** `]` ——
    ///    于是 `[[生图: 一只猫]] 好看` 会被削成 `] 好看`，留下一个孤零零的右括号。
    /// 2. **闭合标签也要清**。Android 的 `<[a-z_]+:?[^>]*>` 匹配不到
    ///    `</thinking>`（`<` 后面是 `/`，不在 `[a-z_]` 里），
    ///    于是闭合标签会被真的念出来。改成 `</?…` 一并覆盖。
    static func cleanText(_ text: String, skipParentheses: Bool = false) -> String {
        var s = text
        s = s.replacingOccurrences(
            of: "</?[a-z_]+:?[^>]*>", with: "",
            options: [.regularExpression, .caseInsensitive]
        )
        if skipParentheses {
            // 括号（含中英文）+ 残余标签 —— 动作/旁白描写
            s = s.replacingOccurrences(
                of: "(<[\\s\\S]*?>|\\([^)]*?\\)|（[^）]*?）)",
                with: "", options: .regularExpression
            )
        }
        // 双层方括号（`[[生图: …]]`）整体吃掉，避免留下孤立的 `]`
        s = s.replacingOccurrences(of: "\\[\\[[^\\]]*\\]\\]", with: "", options: .regularExpression)
        // `[...]`：表情 id、`[图片]` 都在这一类里
        s = s.replacingOccurrences(of: "\\[[^\\]]+\\]", with: "", options: .regularExpression)
        s = s.replacingOccurrences(of: "[*_~]", with: "", options: .regularExpression)
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// 实际发请求（单独一层，便于在测试里替换掉）。
    private static func transport(_ request: URLRequest) async throws -> (Data, URLResponse) {
        try await URLSession.shared.data(for: request)
    }
}

/// 朗读参数 —— 存在 `app_meta`，键前缀 `feature_route.tts.`。
///
/// ## 为什么不放 `api_configs`
/// 那条表与 Android Room 共用、有闸门逐字比对，**不能加列**
/// （同 `FeaturePurpose` 的理由）。
///
/// ## 为什么按线路存、不按渠道存
/// 音色是「这条线路怎么念」：同一条语音渠道在不同角色上换音色是常见诉求，
/// 而渠道行只有一个 `model` 字段，没有音色的位置。
struct TtsSettings: Equatable {
    /// 音色 id。空 = 用协议默认（OpenAI `alloy` / MiMo `mimo_default`）。
    var voice = ""
    /// 语音接口协议。`auto` 按渠道 provider 判断。
    var proto: TtsClient.SpeechProtocol = .auto
    /// 输出格式（只列 iOS 播得动的两种）。
    var format: TtsClient.Format = .mp3
    /// 是否跳过括号内的内容（动作/旁白描写）。
    /// 与 Android `ChatTtsConfig.skipParentheses` 的默认值一致：**false**。
    var skipParentheses = false

    static let voiceKey = "feature_route.tts.voice"
    static let protocolKey = "feature_route.tts.protocol"
    static let formatKey = "feature_route.tts.format"
    static let skipParenthesesKey = "feature_route.tts.skip_parentheses"

    static func load(from repo: ApiConfigRepository) -> TtsSettings {
        var settings = TtsSettings()
        // ⚠️ Swift 5 起 `try?` 会把 `String?` 拍平成一层（不再是 `String??`），
        // 所以这里**只能解一层** —— 写两个 `let` 会报
        // "initializer for conditional binding must have Optional type"。
        if let voice = try? repo.metaValue(forKey: voiceKey) { settings.voice = voice }
        if let raw = try? repo.metaValue(forKey: protocolKey),
           let proto = TtsClient.SpeechProtocol(rawValue: raw) {
            settings.proto = proto
        }
        if let raw = try? repo.metaValue(forKey: formatKey),
           let format = TtsClient.Format(rawValue: raw) {
            settings.format = format
        }
        // 缺键 = 没配过 = 用默认值 false（不要用"键存在即为真"那种读法）
        settings.skipParentheses = (try? repo.metaValue(forKey: skipParenthesesKey)) == "1"
        return settings
    }

    func save(to repo: ApiConfigRepository) throws {
        try repo.setMetaValue(voice, forKey: Self.voiceKey)
        try repo.setMetaValue(proto.rawValue, forKey: Self.protocolKey)
        try repo.setMetaValue(format.rawValue, forKey: Self.formatKey)
        try repo.setMetaValue(skipParentheses ? "1" : "", forKey: Self.skipParenthesesKey)
    }
}

/// 音频播放（`AVAudioPlayer`）。
///
/// ## 为什么必须是一个被持有的对象
/// `AVAudioPlayer` 一旦被释放，声音**立刻停**。把 player 放成局部变量
/// 是"合成成功但没声音"最常见的原因，所以这里由 `TtsSpeaker` 持有它。
///
/// ## 为什么不用 `AVAudioPlayerDelegate` 判断播放结束
/// delegate 的方法是 nonisolated，而本类要 `@MainActor`（UI 状态在它这里）。
/// 在 Swift 5 语言模式下那只是警告，但会长期悬着一个"Swift 6 会报错"的坑。
/// 这里用一个 0.25s 的轮询任务代替：只为一个"朗读结束"的事件，够用且没有隔离问题。
/// 轮询用 `Task` 而不是 `Timer` —— `Timer` 会一直持有闭包，
/// speaker 被释放后那个定时器还在空转（要么泄漏，要么得靠 own-timer 的循环引用解掉）。
@MainActor
final class TtsSpeaker {

    /// 传出「播完了 / 被停了」的一次性回调（ViewModel 用来清"朗读中"状态）。
    var onFinish: ((Bool) -> Void)?

    private var player: AVAudioPlayer?
    private var pollTask: Task<Void, Never>?

    var isPlaying: Bool { player?.isPlaying ?? false }

    /// 播放一段音频。
    /// - Throws: 解码失败时抛出（多半是格式不支持或字节不是音频）。
    func play(_ data: Data, format: TtsClient.Format) throws {
        stop()
        let session = AVAudioSession.sharedInstance()
        // `.spokenAudio` 让系统按"朗读"处理（音量/耳机行为更贴近听书）。
        // 失败不致命：仍可能出声，只是没有这层语义。
        try? session.setCategory(.playback, mode: .spokenAudio)
        try? session.setActive(true)

        let player = try AVAudioPlayer(data: data)
        player.prepareToPlay()
        self.player = player
        guard player.play() else {
            self.player = nil
            throw TtsClient.TtsError.notAudio("\(format.title) 解码成功但无法播放")
        }

        pollTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 250_000_000)
                // speaker 已释放 → 结束循环，不留一个空转的任务
                guard let self else { return }
                if !(self.player?.isPlaying ?? false) {
                    self.finish()
                    return
                }
            }
        }
    }

    func stop() {
        pollTask?.cancel()
        pollTask = nil
        player?.stop()
        player = nil
    }

    private func finish() {
        pollTask?.cancel()
        pollTask = nil
        player = nil
        onFinish?(true)
    }
}
